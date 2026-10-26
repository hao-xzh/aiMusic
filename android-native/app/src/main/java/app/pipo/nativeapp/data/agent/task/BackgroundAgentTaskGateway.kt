package app.pipo.nativeapp.data.agent.task

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RecommendationLog
import app.pipo.nativeapp.data.library.UnifiedPlaylist
import app.pipo.nativeapp.data.agent.domain.*
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.execute.PlaylistImportService
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.runtime.AgentTurnExecutionException
import app.pipo.nativeapp.data.agent.runtime.AgentRuntime
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.playback.PlaybackUrlResolver
import app.pipo.nativeapp.playback.PlaybackSessionClock
import app.pipo.nativeapp.playback.BackgroundAgentContinuation
import app.pipo.nativeapp.playback.PipoPlaybackService
import app.pipo.nativeapp.playback.PlayerMediaFactory
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** UI-independent gateway used by WorkManager after process recreation. */
class BackgroundAgentTaskGateway(private val context: Context) : AgentTaskGateway {
    override suspend fun execute(task: AgentTask): TurnOutcome {
        var connectedController: MediaController? = null
        val controllerProvider: suspend () -> MediaController = {
            connectedController ?: withTimeoutOrNull(MEDIA_CONTROLLER_CONNECT_TIMEOUT_MS) { connect() }
                ?.also { connectedController = it }
                ?: throw AgentTurnExecutionException(true, "media_controller_connect_timeout")
        }
        val snapshot = JSONObject(task.contextJson.ifBlank { "{}" })
        val current = snapshot.optJSONObject("currentTrack")?.toTrack()
        val queue = snapshot.optJSONArray("queue")?.let { arr ->
            List(arr.length()) { arr.optJSONObject(it)?.toTrack() }.filterNotNull()
        } ?: emptyList()
        val executor = BackgroundPlayerAgentExecutor(
            controllerProvider, PipoGraph.repository,
            snapshot.takeIf { it.has("manualSelectionRevision") }?.optLong("manualSelectionRevision"),
        )
        val history = runCatching { PipoGraph.petMemory.conversationContext() }.getOrNull()
        val input = AgentTurnInput(
            userText = task.userText,
            history = history?.turns?.filterNot { it.taskId == task.id } ?: emptyList(),
            historySummary = history?.summary.orEmpty(),
            musicReferences = history?.musicReferences ?: emptyList(),
            currentTrack = current,
            currentQueue = queue,
            userFacts = snapshot.optString("userFacts"),
            persona = PetPersona.fromId(snapshot.optString("persona")),
        )
        return try {
            AgentRuntime(PipoGraph.repository, AgentLedgerStore(context)).handle(input, executor)
        } finally {
            connectedController?.let { controller ->
                try {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { controller.release() }
                } catch (_: Throwable) {
                    // Cleanup must not turn an already completed durable task into a retry.
                }
            }
        }
    }

    private suspend fun connect(): MediaController = suspendCancellableCoroutine { cont ->
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PipoPlaybackService::class.java))).buildAsync()
        future.addListener({
            if (!cont.isActive) return@addListener
            runCatching { cont.resume(future.get()) }
                .onFailure { if (cont.isActive) cont.resumeWithException(it) }
        }, MoreExecutors.directExecutor())
        cont.invokeOnCancellation { future.cancel(true) }
    }

    private companion object {
        const val MEDIA_CONTROLLER_CONNECT_TIMEOUT_MS = 8_000L
    }
}

private fun JSONObject.toTrack() = app.pipo.nativeapp.data.NativeTrackJson.decode(this, includeStreamUrl = true)

private class BackgroundPlayerAgentExecutor(
    private val controllerProvider: suspend () -> MediaController,
    private val repository: app.pipo.nativeapp.data.PipoRepository,
    private val expectedSelectionRevision: Long?,
) : AgentActionExecutor {
    private data class PlaybackSnapshot(
        val current: NativeTrack?,
        val queue: List<NativeTrack>,
    )

    private val factory = PlayerMediaFactory(PipoGraph.audioFeaturesStore)
    private val playlistImportService = PlaylistImportService(repository)
    private val playbackUrlResolver = PlaybackUrlResolver(
        repository = repository,
        music = PipoGraph.music,
        streamLevelFallbacks = STREAM_LEVEL_FALLBACKS,
        streamUrlTimeoutMs = STREAM_URL_TIMEOUT_MS,
    )
    private fun ok(
        id: String,
        type: String,
        message: String,
        tracks: List<NativeTrack> = emptyList(),
        started: Boolean = false,
        inserted: NativeTrack? = null,
        snapshot: PlaybackSnapshot,
    ) = ActionExecutionResult(
        actionId = id,
        type = type,
        success = true,
        message = message,
        tracks = tracks,
        acceptedByPlayer = true,
        actuallyStarted = started,
        currentTrack = snapshot.current,
        queueSnapshot = snapshot.queue,
        insertedTrack = inserted,
        insert = inserted != null,
    )

    private suspend fun playbackSnapshot(): PlaybackSnapshot {
        val controller = controllerProvider()
        return withContext(Dispatchers.Main.immediate) { controller.snapshot() }
    }

    private fun MediaController.snapshot(): PlaybackSnapshot {
        val queue = (0 until mediaItemCount).mapNotNull { index ->
            getMediaItemAt(index).toNativeTrack()
        }
        val current = currentMediaItem?.toNativeTrack()
            ?: queue.getOrNull(currentMediaItemIndex)
        return PlaybackSnapshot(current = current, queue = queue)
    }

    private fun MediaItem.toNativeTrack(): NativeTrack? {
        val id = mediaId.takeIf { it.isNotBlank() } ?: return null
        val source = PlayerMediaFactory.sourceRefFrom(mediaMetadata)
        return NativeTrack(
            id = id,
            neteaseId = if (source != null) source.neteaseId else id.toLongOrNull(),
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString().orEmpty(),
            streamUrl = localConfiguration?.uri?.toString().orEmpty(),
            artworkUrl = mediaMetadata.artworkUri?.toString(),
            durationMs = mediaMetadata.extras
                ?.takeIf { it.containsKey(PlayerMediaFactory.SOURCE_DURATION_MS_EXTRA) }
                ?.getLong(PlayerMediaFactory.SOURCE_DURATION_MS_EXTRA)
                ?: 0L,
            sourceRef = source,
        )
    }

    private suspend fun ActionExecutionResult.withPlaybackSnapshot(): ActionExecutionResult {
        val snapshot = playbackSnapshot()
        return copy(currentTrack = snapshot.current, queueSnapshot = snapshot.queue)
    }

    override suspend fun playQueue(actionId: String, mode: PlayMode, tracks: List<NativeTrack>, continuous: ContinuousQueueSource?, primaryGoal: MusicGoal, target: TrackRequirement?, similar: Boolean, preserveCurrent: Boolean): ActionExecutionResult {
        if (tracks.isEmpty()) {
            return ActionExecutionResult(actionId, "play", false, "没有可播放歌曲", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
        val playable = playbackUrlResolver.resolvePlayableQueue(tracks).filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) {
            return ActionExecutionResult(actionId, "play", false, "没有找到可播放音源", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
        val controller = controllerProvider()
        val result = withContext(Dispatchers.Main.immediate) {
            if (!PlaybackSessionClock.isSelectionCurrent(expectedSelectionRevision)) return@withContext null
            PipoGraph.lastPlayback.setContinuation(continuous?.snapshot())
            BackgroundAgentContinuation.install(continuous, primaryGoal)
            if (continuous != null) {
                PipoGraph.lastPlayback.setPlaybackMode(when {
                    continuous.repeatsCurrentTrack() -> "SingleLoop"
                    continuous.startsAutomatically() -> "AiRadio"
                    else -> "OrderOnce"
                })
                controller.repeatMode = when {
                    continuous.repeatsCurrentTrack() -> Player.REPEAT_MODE_ONE
                    continuous.startsAutomatically() -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            if (preserveCurrent && controller.currentMediaItem != null) {
                val start = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
                controller.replaceMediaItems(start, controller.mediaItemCount, playable.map { factory.toMediaItem(it) })
                val snapshot = controller.snapshot()
                PipoGraph.lastPlayback.save(snapshot.queue, controller.currentMediaItemIndex, controller.currentPosition)
                false to snapshot
            } else {
                controller.setMediaItems(playable.map { factory.toMediaItem(it) })
                controller.prepare()
                controller.play()
                val snapshot = controller.snapshot()
                PipoGraph.lastPlayback.save(snapshot.queue, controller.currentMediaItemIndex, controller.currentPosition)
                controller.playWhenReady to snapshot
            }
        }
            ?: return selectionChanged(actionId, "play_queue")
        runCatching { PipoGraph.recommendationLog.logTracks(playable, RecommendationLog.Source.Pet) }
        return ok(
            actionId,
            "play_queue",
            "队列接上了：${playable.first().title}",
            playable,
            started = result.first,
            snapshot = result.second,
        )
    }

    override suspend fun insertNext(actionId: String, tracks: List<NativeTrack>, jumpToInserted: Boolean): ActionExecutionResult {
        if (tracks.isEmpty()) {
            return ActionExecutionResult(actionId, "insert_next", false, "没有可插入歌曲", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
        val playable = playbackUrlResolver.resolvePlayableQueue(tracks).filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) {
            return ActionExecutionResult(actionId, "insert_next", false, "没有找到可播放音源", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
        val controller = controllerProvider()
        val result = withContext(Dispatchers.Main.immediate) {
            if (!PlaybackSessionClock.isSelectionCurrent(expectedSelectionRevision)) return@withContext null
            val insertIndex = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
            controller.addMediaItems(insertIndex, playable.map { factory.toMediaItem(it) })
            controller.prepare()
            if (jumpToInserted) {
                controller.seekTo(insertIndex, 0L)
                controller.play()
            }
            (jumpToInserted && controller.playWhenReady) to controller.snapshot()
        }
            ?: return selectionChanged(actionId, "insert_next")
        runCatching { PipoGraph.recommendationLog.logTracks(playable, RecommendationLog.Source.Pet) }
        return ok(
            actionId,
            "insert_next",
            "下一首给你接上",
            playable,
            started = result.first,
            inserted = playable.first(),
            snapshot = result.second,
        )
    }

    private fun selectionChanged(actionId: String, type: String) = ActionExecutionResult(
        actionId, type, false,
        "你已经手动选了歌曲，已保留当前播放；想听这次推荐可以重新告诉我。",
        acceptedByPlayer = false,
        errorMessage = "playback_selection_changed",
    )

    override suspend fun skip(actionId: String): ActionExecutionResult {
        val controller = controllerProvider()
        val result = withContext(Dispatchers.Main.immediate) {
            val skipped = if (!controller.hasNextMediaItem()) false
            else {
                controller.seekToNextMediaItem()
                true
            }
            skipped to controller.snapshot()
        }
        return if (result.first) {
            ok(actionId, "skip", "换一首", tracks = listOfNotNull(result.second.current), snapshot = result.second)
        } else {
            ActionExecutionResult(actionId, "skip", false, "已经是队列最后一首", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
    }

    override suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult {
        val current = playbackSnapshot().current
            ?: return ActionExecutionResult(actionId, "like", false, "现在没在放歌，没法${if (like) "收藏" else "取消收藏"}。", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        return applyLibraryLike(actionId, current, like)
    }

    override suspend fun likeTrack(actionId: String, like: Boolean, target: TrackRequirement): ActionExecutionResult {
        val track = try {
            resolveTrackForLike(target, playbackSnapshot().current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ActionExecutionResult(
                actionId, "like", false,
                "搜索歌曲失败：${error.message ?: error::class.java.simpleName}",
                acceptedByPlayer = false,
            ).withPlaybackSnapshot()
        }
            ?: return ActionExecutionResult(actionId, "like", false, "找不到歌曲", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        return applyLibraryLike(actionId, track, like)
    }

    override suspend fun retryFavorite(
        actionId: String, track: NativeTrack, like: Boolean, accountUserId: Long,
    ): ActionExecutionResult {
        if (repository.account.first()?.userId != accountUserId) {
            return ActionExecutionResult(actionId, "like", false, "账号已变化，请明确要收藏的歌曲。", acceptedByPlayer = false)
        }
        return likeTrackId(actionId, track.neteaseId, like, track)
    }

    private suspend fun likeTrackId(id: String, neteaseId: Long?, like: Boolean, track: NativeTrack?): ActionExecutionResult {
        if (neteaseId == null) {
            return ActionExecutionResult(id, "like", false, "找不到歌曲", acceptedByPlayer = false)
                .withPlaybackSnapshot()
        }
        return try {
            repository.likeSong(neteaseId, like)
            ActionExecutionResult(id, "like", true, if (like) "收藏好了" else "取消收藏", tracks = listOfNotNull(track), likedTrack = track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ActionExecutionResult(id, "like", false, "收藏失败", tracks = listOfNotNull(track), acceptedByPlayer = false)
        }.withPlaybackSnapshot()
    }

    private suspend fun resolveTrackForLike(target: TrackRequirement, current: NativeTrack?): NativeTrack? {
        current?.takeIf { matchesTarget(it, target) }?.let { return it }
        val query = listOfNotNull(target.artist, target.title).joinToString(" ").trim()
        if (query.isBlank()) return null
        return PipoGraph.music.searchCandidates(query, limit = 10, offset = 0)
            .filter { matchesTarget(it, target) }
            .minByOrNull { candidateScore(it, target) }
    }

    private fun matchesTarget(track: NativeTrack, target: TrackRequirement): Boolean {
        val title = CommandTextSignals.normalizeForMatch(track.title)
        val targetTitle = CommandTextSignals.normalizeForMatch(target.title)
        if (title.isBlank() || targetTitle.isBlank() || (title != targetTitle && !title.contains(targetTitle) && !targetTitle.contains(title))) return false
        val targetArtist = target.artist?.takeIf { it.isNotBlank() } ?: return true
        val artist = CommandTextSignals.normalizeForMatch(track.artist)
        val wantedArtist = CommandTextSignals.normalizeForMatch(targetArtist)
        return artist == wantedArtist || artist.contains(wantedArtist) || wantedArtist.contains(artist)
    }

    private fun candidateScore(track: NativeTrack, target: TrackRequirement): Int {
        val title = CommandTextSignals.normalizeForMatch(track.title)
        val wantedTitle = CommandTextSignals.normalizeForMatch(target.title)
        val titleScore = if (title == wantedTitle) 0 else 50
        val artistScore = if (target.artist.isNullOrBlank()) 0 else {
            val artist = CommandTextSignals.normalizeForMatch(track.artist)
            val wantedArtist = CommandTextSignals.normalizeForMatch(target.artist.orEmpty())
            if (artist == wantedArtist) 0 else 25
        }
        return titleScore + artistScore
    }

    override suspend fun modifyPlaylist(actionId: String, add: Boolean, playlistName: String): ActionExecutionResult {
        val current = playbackSnapshot().current
        if (current == null) return ActionExecutionResult(actionId, "playlist", false, "找不到歌曲", acceptedByPlayer = false)
            .withPlaybackSnapshot()
        val matches = try {
            uniquePlaylistCandidates(playlistName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ActionExecutionResult(
                actionId, "playlist", false,
                "读取资料库失败：${error.message ?: error::class.java.simpleName}",
                acceptedByPlayer = false,
            ).withPlaybackSnapshot()
        }
        val playlist = matches.singleOrNull()
            ?: return playlistBindingRequired(actionId, playlistName, matches.size)
                .withPlaybackSnapshot()
        return applyLibraryPlaylist(actionId, playlist, current, add)
    }

    private suspend fun applyLibraryLike(actionId: String, track: NativeTrack, like: Boolean): ActionExecutionResult {
        val library = PipoGraph.music.library
        val before = library.state.value
        return try {
            library.like(track, like)
            val after = library.state.value
            val errors = newLibraryErrors(before.errors, after.errors)
            val confirmed = library.isLiked(track) == like &&
                after.pendingCount <= before.pendingCount && errors.isEmpty()
            if (confirmed) {
                ActionExecutionResult(
                    actionId, "like", true,
                    if (like) "收藏好了：${trackLabel(track)}" else "取消收藏了：${trackLabel(track)}",
                    tracks = listOf(track), likedTrack = track,
                ).withPlaybackSnapshot()
            } else {
                val detail = errors.values.firstOrNull()
                ActionExecutionResult(
                    actionId, "like", false,
                    detail?.let { "${if (like) "收藏" else "取消收藏"}未确认：$it" }
                        ?: "${if (like) "收藏" else "取消收藏"}已提交，正在等待资料库确认（待同步 ${after.pendingCount} 项）。",
                    tracks = listOf(track), acceptedByPlayer = false,
                ).withPlaybackSnapshot()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionExecutionResult(
                actionId, "like", false,
                "${if (like) "收藏" else "取消收藏"}失败：${error.message ?: error::class.java.simpleName}",
                tracks = listOf(track), acceptedByPlayer = false,
            ).withPlaybackSnapshot()
        }
    }

    private suspend fun uniquePlaylistCandidates(name: String): List<UnifiedPlaylist> {
        val library = PipoGraph.music.library
        if (library.state.value.playlists.isEmpty()) library.refresh()
        val query = name.trim()
        return if (query.isBlank()) emptyList() else library.state.value.playlists.filter { it.name.equals(query, ignoreCase = true) }
    }

    private suspend fun applyLibraryPlaylist(
        actionId: String,
        playlist: UnifiedPlaylist,
        track: NativeTrack,
        add: Boolean,
    ): ActionExecutionResult {
        val library = PipoGraph.music.library
        val before = library.state.value
        return try {
            if (add) library.add(playlist.id, track) else library.remove(playlist.id, track)
            val after = library.state.value
            val errors = newLibraryErrors(before.errors, after.errors)
            val updated = after.playlists.firstOrNull { it.id == playlist.id }
            val containsTrack = updated?.tracks?.any { sameLibraryTrack(it, track) }
            val confirmed = after.pendingCount <= before.pendingCount && errors.isEmpty() &&
                (if (add) containsTrack == true else containsTrack == false)
            if (confirmed) {
                ActionExecutionResult(
                    actionId, "playlist", true,
                    "${if (add) "已加入" else "已移出"}「${playlist.name}」",
                    tracks = listOf(track), playlistName = playlist.name,
                ).withPlaybackSnapshot()
            } else {
                val detail = errors.values.firstOrNull()
                ActionExecutionResult(
                    actionId, "playlist", false,
                    detail?.let { "歌单操作未确认：$it" }
                        ?: "歌单操作已提交，正在等待资料库确认（待同步 ${after.pendingCount} 项）。",
                    tracks = listOf(track), playlistName = playlist.name, acceptedByPlayer = false,
                ).withPlaybackSnapshot()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionExecutionResult(
                actionId, "playlist", false,
                "操作歌单失败：${error.message ?: error::class.java.simpleName}",
                tracks = listOf(track), playlistName = playlist.name, acceptedByPlayer = false,
            ).withPlaybackSnapshot()
        }
    }

    private fun playlistBindingRequired(actionId: String, name: String, matches: Int): ActionExecutionResult =
        ActionExecutionResult(
            actionId, "playlist", false,
            if (matches == 0) "资料库没有唯一歌单「$name」，请先在资料库选择并绑定。"
            else "歌单「$name」有多个绑定，请先在资料库选择要操作的绑定。",
            acceptedByPlayer = false,
        )

    private fun newLibraryErrors(before: Map<String, String>, after: Map<String, String>): Map<String, String> =
        after.filter { (id, message) -> before[id] != message }

    private fun sameLibraryTrack(left: NativeTrack, right: NativeTrack): Boolean =
        left.sourceKey == right.sourceKey ||
            (!left.recordingId.isNullOrBlank() && left.recordingId == right.recordingId)

    private fun trackLabel(track: NativeTrack): String =
        listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { track.title }

    override suspend fun createPlaylist(
        actionId: String,
        playlistName: String,
        tracks: List<TrackRequirement>,
    ): ActionExecutionResult = playlistImportService.create(actionId, playlistName, tracks).withPlaybackSnapshot()

    private companion object {
        val STREAM_LEVEL_FALLBACKS = listOf("lossless", "exhigh", "higher", "standard")
        const val STREAM_URL_TIMEOUT_MS = 8_000L
    }
}
