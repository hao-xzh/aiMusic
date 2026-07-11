package app.pipo.nativeapp.data.agent.task

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RecommendationLog
import app.pipo.nativeapp.data.agent.domain.*
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.runtime.AgentTurnExecutionException
import app.pipo.nativeapp.data.agent.runtime.AgentRuntime
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.playback.PlaybackUrlResolver
import app.pipo.nativeapp.playback.PipoPlaybackService
import app.pipo.nativeapp.playback.PlayerMediaFactory
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
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
        val executor = BackgroundPlayerAgentExecutor(controllerProvider, current, PipoGraph.repository)
        val history = runCatching { PipoGraph.petMemory.conversationContext() }.getOrNull()
        val input = AgentTurnInput(
            userText = task.userText,
            history = history?.turns ?: emptyList(),
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

private fun JSONObject.toTrack() = NativeTrack(optString("id"), optLong("neteaseId").takeIf { it != 0L }, optString("title"), optString("artist"), optString("album"), optString("streamUrl"), artworkUrl = optString("artworkUrl").takeIf { it.isNotBlank() })

private class BackgroundPlayerAgentExecutor(
    private val controllerProvider: suspend () -> MediaController,
    private val current: NativeTrack?,
    private val repository: app.pipo.nativeapp.data.PipoRepository,
) : AgentActionExecutor {
    private val factory = PlayerMediaFactory(PipoGraph.audioFeaturesStore)
    private val playbackUrlResolver = PlaybackUrlResolver(
        repository = repository,
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
    ) = ActionExecutionResult(
        actionId = id,
        type = type,
        success = true,
        message = message,
        tracks = tracks,
        acceptedByPlayer = true,
        actuallyStarted = started,
        currentTrack = tracks.firstOrNull(),
        queueSnapshot = tracks,
        insertedTrack = inserted,
        insert = inserted != null,
    )

    override suspend fun playQueue(actionId: String, mode: PlayMode, tracks: List<NativeTrack>, continuous: ContinuousQueueSource?, primaryGoal: MusicGoal, target: TrackRequirement?, similar: Boolean): ActionExecutionResult {
        if (tracks.isEmpty()) return ActionExecutionResult(actionId, "play", false, "没有可播放歌曲", acceptedByPlayer = false)
        val playable = playbackUrlResolver.resolvePlayableQueue(tracks).filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) return ActionExecutionResult(actionId, "play", false, "没有找到可播放音源", acceptedByPlayer = false)
        val controller = controllerProvider()
        val started = withContext(Dispatchers.Main.immediate) {
            controller.setMediaItems(playable.map { factory.toMediaItem(it) })
            controller.prepare()
            controller.play()
            controller.playWhenReady
        }
        runCatching { PipoGraph.recommendationLog.logTracks(playable, RecommendationLog.Source.Pet) }
        return ok(actionId, "play_queue", "队列接上了：${playable.first().title}", playable, started = started)
    }

    override suspend fun insertNext(actionId: String, tracks: List<NativeTrack>, jumpToInserted: Boolean): ActionExecutionResult {
        if (tracks.isEmpty()) return ActionExecutionResult(actionId, "insert_next", false, "没有可插入歌曲", acceptedByPlayer = false)
        val playable = playbackUrlResolver.resolvePlayableQueue(tracks).filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) return ActionExecutionResult(actionId, "insert_next", false, "没有找到可播放音源", acceptedByPlayer = false)
        val controller = controllerProvider()
        val started = withContext(Dispatchers.Main.immediate) {
            val insertIndex = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
            controller.addMediaItems(insertIndex, playable.map { factory.toMediaItem(it) })
            controller.prepare()
            if (jumpToInserted) {
                controller.seekTo(insertIndex, 0L)
                controller.play()
            }
            jumpToInserted && controller.playWhenReady
        }
        runCatching { PipoGraph.recommendationLog.logTracks(playable, RecommendationLog.Source.Pet) }
        return ok(actionId, "insert_next", "下一首给你接上", playable, started = started, inserted = playable.first())
    }

    override suspend fun skip(actionId: String): ActionExecutionResult {
        val controller = controllerProvider()
        val skipped = withContext(Dispatchers.Main.immediate) {
            if (!controller.hasNextMediaItem()) false
            else {
                controller.seekToNextMediaItem()
                true
            }
        }
        return if (skipped) ok(actionId, "skip", "换一首")
        else ActionExecutionResult(actionId, "skip", false, "已经是队列最后一首", acceptedByPlayer = false)
    }

    override suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult = likeTrackId(actionId, current?.neteaseId, like)

    override suspend fun likeTrack(actionId: String, like: Boolean, target: TrackRequirement): ActionExecutionResult {
        val track = resolveTrackForLike(target)
        return likeTrackId(actionId, track?.neteaseId, like, track)
    }

    private suspend fun likeTrackId(id: String, neteaseId: Long?, like: Boolean, track: NativeTrack? = current): ActionExecutionResult {
        if (neteaseId == null) return ActionExecutionResult(id, "like", false, "找不到歌曲", acceptedByPlayer = false)
        return runCatching { repository.likeSong(neteaseId, like) }.fold(
            { ActionExecutionResult(id, "like", true, if (like) "收藏好了" else "取消收藏", tracks = listOfNotNull(track), likedTrack = track) },
            { ActionExecutionResult(id, "like", false, "收藏失败", acceptedByPlayer = false) },
        )
    }

    private suspend fun resolveTrackForLike(target: TrackRequirement): NativeTrack? {
        current?.takeIf { matchesTarget(it, target) }?.let { return it }
        val query = listOfNotNull(target.artist, target.title).joinToString(" ").trim()
        if (query.isBlank()) return null
        return runCatching { repository.searchTracks(query, limit = 10) }
            .getOrDefault(emptyList())
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
        val tid = current?.neteaseId ?: return ActionExecutionResult(actionId, "playlist", false, "找不到歌曲", acceptedByPlayer = false)
        var playlists = repository.playlists.first()
        if (playlists.isEmpty()) {
            runCatching { repository.refreshPlaylists() }
            playlists = repository.playlists.first()
        }
        val playlist = playlists.firstOrNull {
            it.name.equals(playlistName, true) ||
                it.name.contains(playlistName, true) ||
                playlistName.contains(it.name, true)
        }
            ?: return ActionExecutionResult(actionId, "playlist", false, "后台歌单未找到", acceptedByPlayer = false)
        return runCatching { repository.playlistModifyTracks(playlist.id, if (add) "add" else "del", listOf(tid)); ok(actionId, "playlist", if (add) "已加入歌单" else "已移出歌单") }
            .getOrElse { ActionExecutionResult(actionId, "playlist", false, "歌单操作失败", acceptedByPlayer = false) }
    }

    private companion object {
        val STREAM_LEVEL_FALLBACKS = listOf("lossless", "exhigh", "higher", "standard")
        const val STREAM_URL_TIMEOUT_MS = 8_000L
    }
}
