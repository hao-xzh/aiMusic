package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.MusicProviders
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.library.UnifiedPlaylist
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.QueueHardConstraints
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.playback.orchestrator.QueueOperation
import app.pipo.nativeapp.playback.PlaybackUrlResolver
import app.pipo.nativeapp.playback.PlaybackSessionClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PlayerAgentExecutor(
    private val repository: PipoRepository,
    private val currentTrackProvider: () -> NativeTrack?,
    private val sourceUserText: String,
    private val onApplyAgentQueueRequest: suspend (AgentQueueRequest) -> QueueCommitResult,
    private val onSkip: () -> Unit,
    /** Durable task id; makes playback request ids stable across Worker retries. */
    private val taskId: String = "",
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    resolvePlayableTrack: (suspend (NativeTrack) -> NativeTrack?)? = null,
    private val currentQueueProvider: () -> List<NativeTrack> = { emptyList() },
    private val expectedSelectionRevision: Long? = null,
) : AgentActionExecutor {
    private val playbackUrlResolver = PlaybackUrlResolver(
        repository = repository,
        music = PipoGraph.music,
        streamLevelFallbacks = STREAM_LEVEL_FALLBACKS,
        streamUrlTimeoutMs = STREAM_URL_TIMEOUT_MS,
    )
    private val resolvePlayableTrack = resolvePlayableTrack ?: playbackUrlResolver::resolveSinglePlayable
    private val playlistImportService = PlaylistImportService(repository)
    private var committedCurrentTrack: NativeTrack? = null
    private var committedQueue: List<NativeTrack>? = null
    private var currentCursorUnconfirmed = false

    override suspend fun playQueue(
        actionId: String,
        mode: PlayMode,
        tracks: List<NativeTrack>,
        continuous: ContinuousQueueSource?,
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        similar: Boolean,
        preserveCurrent: Boolean,
    ): ActionExecutionResult {
        val operation = when {
            similar -> QueueOperation.PlaySimilar
            mode == PlayMode.PlayNow -> QueueOperation.PlayNow
            else -> QueueOperation.ReplaceQueue
        }
        val requestTracks = when (operation) {
            QueueOperation.PlayNow,
            QueueOperation.ReplaceQueue,
            QueueOperation.PlaySimilar -> {
                val first = tracks.firstOrNull() ?: return noPlayableSource(actionId, "play_queue", null)
                val resolvedFirst = resolvePlayableTarget(actionId, operation, first)
                    ?: return noPlayableSource(actionId, "play_queue", first)
                listOf(resolvedFirst) + tracks.drop(1)
            }
            else -> tracks
        }
        val request = AgentQueueRequest(
            requestId = stableId(
                "play",
                tracks.joinToString(",") { it.id },
                mode.name,
                similar.toString(),
                preserveCurrent.toString(),
            ),
            sourceUserText = sourceUserText,
            operation = operation,
            tracks = requestTracks,
            continuous = continuous,
            preserveCurrent = preserveCurrent,
            desiredCount = requestTracks.size,
            hardConstraints = hardConstraintsFor(primaryGoal, target, operation),
        )
        val currentBeforeCommit = actionCurrentTrack()
        val commit = applyQueueRequest(request)
        return resultForCommit(request.requestId, "play_queue", request, commit, similar, currentBeforeCommit)
    }

    override suspend fun insertNext(
        actionId: String,
        tracks: List<NativeTrack>,
        jumpToInserted: Boolean,
    ): ActionExecutionResult {
        val first = tracks.firstOrNull() ?: return noPlayableSource(actionId, "insert_next", null)
        // 只预检第一首（批量时逐首预检太慢）；其余在播放层整批解析，失败的丢掉。
        val resolvedFirst = resolvePlayableTarget(actionId, QueueOperation.InsertNext, first)
            ?: return noPlayableSource(actionId, "insert_next", first)
        val requestTracks = listOf(resolvedFirst) + tracks.drop(1)
        val request = AgentQueueRequest(
            requestId = stableId("insert", tracks.joinToString(",") { it.id }, jumpToInserted.toString()),
            sourceUserText = sourceUserText,
            operation = QueueOperation.InsertNext,
            tracks = requestTracks,
            continuous = null,
            jumpToInserted = jumpToInserted,
            desiredCount = requestTracks.size,
            hardConstraints = QueueHardConstraints(
                nextTrack = TrackRequirement(title = resolvedFirst.title, artist = resolvedFirst.artist, placement = TrackPlacement.Next),
            ),
        )
        val currentBeforeCommit = actionCurrentTrack()
        val commit = applyQueueRequest(request)
        return resultForCommit(
            request.requestId,
            "insert_next",
            request,
            commit,
            similar = false,
            currentBeforeCommit = currentBeforeCommit,
        )
    }

    private suspend fun applyQueueRequest(request: AgentQueueRequest): QueueCommitResult =
        withContext(playerDispatcher) {
            if (!PlaybackSessionClock.isSelectionCurrent(expectedSelectionRevision)) {
                QueueCommitResult.Rejected(
                    request, "playback_selection_changed",
                    listOf("你已经手动选了歌曲，已保留当前播放；想听这次推荐可以重新告诉我。"),
                )
            } else onApplyAgentQueueRequest(request)
        }

    override suspend fun skip(actionId: String): ActionExecutionResult {
        val stableActionId = stableId("skip")
        val previousIds = withContext(playerDispatcher) {
            listOfNotNull(currentTrackProvider()?.id, committedCurrentTrack?.id).toSet()
        }
        withContext(playerDispatcher) { onSkip() }
        val currentAfterSkip = awaitCurrentAfterSkip(previousIds)
        if (currentAfterSkip == null) {
            currentCursorUnconfirmed = true
            committedCurrentTrack = null
            committedQueue = null
            return ActionExecutionResult(
                stableActionId,
                "skip",
                success = false,
                message = "切歌后未确认新的当前歌曲，不能继续按旧歌曲操作。",
                acceptedByPlayer = false,
            )
        }
        currentCursorUnconfirmed = false
        committedCurrentTrack = currentAfterSkip
        if (committedQueue?.any { it.id == currentAfterSkip.id } != true) {
            committedQueue = null
        }
        return ActionExecutionResult(
            stableActionId,
            "skip",
            success = true,
            message = "换一首",
            currentTrack = currentAfterSkip,
            queueSnapshot = executionQueue(),
        )
    }

    override suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult {
        val stableActionId = stableId("like_current", like.toString())
        val currentTrack = actionCurrentTrack()
        if (currentTrack == null) {
            return ActionExecutionResult(stableActionId, "like", success = false, message = "现在没在放歌，没法${if (like) "收藏" else "取消收藏"}。")
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "like_intent_invoke",
            fields = mapOf(
                "trackId" to currentTrack.id,
                "providerId" to currentTrack.musicSource?.providerId,
                "neteaseId" to currentTrack.neteaseId,
                "like" to like,
                "title" to currentTrack.title,
            ),
        )
        return applyLibraryLike(stableActionId, currentTrack, like, currentTrack, executionQueue())
    }

    override suspend fun likeTrack(
        actionId: String,
        like: Boolean,
        target: TrackRequirement,
    ): ActionExecutionResult {
        val stableActionId = stableId("like_track", like.toString(), target.artist.orEmpty(), target.title)
        val track = try {
            resolveTrackForLike(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ActionExecutionResult(
                stableActionId,
                "like",
                false,
                "搜索歌曲失败：${error.message ?: error::class.java.simpleName}",
                acceptedByPlayer = false,
            )
        }
        if (track == null) {
            val label = listOfNotNull(target.artist, target.title).joinToString(" - ")
            return ActionExecutionResult(
                actionId = stableActionId,
                type = "like",
                success = false,
                message = "没找到可${if (like) "收藏" else "取消收藏"}的「$label」。",
            )
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "like_track_invoke",
            fields = mapOf(
                "trackId" to track.id,
                "providerId" to track.musicSource?.providerId,
                "neteaseId" to track.neteaseId,
                "like" to like,
                "title" to track.title,
                "artist" to track.artist,
                "targetTitle" to target.title,
                "targetArtist" to target.artist,
            ),
        )
        return applyLibraryLike(stableActionId, track, like, track, executionQueue())
    }

    override suspend fun retryFavorite(
        actionId: String, track: NativeTrack, like: Boolean, accountUserId: Long,
    ): ActionExecutionResult {
        val tid = track.neteaseId
        if (tid == null || repository.account.first()?.userId != accountUserId) {
            return ActionExecutionResult(actionId, "like", false, "账号或歌曲已变化，请明确要收藏的歌曲。", acceptedByPlayer = false)
        }
        return try {
            repository.likeSong(tid, like)
            ActionExecutionResult(actionId, "like", true,
                if (like) "收藏好了：${trackLabel(track)}" else "取消收藏了：${trackLabel(track)}",
                tracks = listOf(track), likedTrack = track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionExecutionResult(actionId, "like", false,
                "${if (like) "收藏" else "取消收藏"}失败：${error.message ?: error::class.java.simpleName}",
                tracks = listOf(track), acceptedByPlayer = false)
        }
    }

    override suspend fun modifyPlaylist(
        actionId: String,
        add: Boolean,
        playlistName: String,
    ): ActionExecutionResult {
        val stableActionId = stableId("playlist", add.toString(), playlistName)
        val currentTrack = actionCurrentTrack()
        if (currentTrack == null) {
            return ActionExecutionResult(stableActionId, "playlist", success = false, message = "现在没在放歌，没法操作歌单。")
        }
        val matches = try {
            uniquePlaylistCandidates(playlistName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ActionExecutionResult(
                stableActionId,
                "playlist",
                false,
                "读取资料库失败：${error.message ?: error::class.java.simpleName}",
                acceptedByPlayer = false,
            )
        }
        val target = matches.singleOrNull() ?: return playlistBindingRequired(stableActionId, playlistName, matches.size)
        return applyLibraryPlaylist(stableActionId, target, currentTrack, add, executionQueue())
    }

    override suspend fun createPlaylist(
        actionId: String,
        playlistName: String,
        tracks: List<TrackRequirement>,
    ): ActionExecutionResult {
        val stableActionId = stableId(
            "playlist_create",
            playlistName,
            tracks.joinToString("|") { "${it.title}:${it.artist.orEmpty()}" },
        )
        return playlistImportService.create(stableActionId, playlistName, tracks)
    }

    private suspend fun resolveTrackForLike(target: TrackRequirement): NativeTrack? {
        actionCurrentTrack()?.takeIf { matchesTarget(it, target) }?.let { return it }
        val query = listOfNotNull(target.artist, target.title).joinToString(" ").trim()
        if (query.isBlank()) return null
        val candidates = PipoGraph.music.searchCandidates(query, limit = 10, offset = 0)
        return candidates
            .filter { titleLooksCompatible(it, target) }
            .minByOrNull { likeCandidateScore(it, target) }
    }

    private suspend fun applyLibraryLike(
        actionId: String,
        track: NativeTrack,
        like: Boolean,
        currentTrack: NativeTrack?,
        queue: List<NativeTrack>,
    ): ActionExecutionResult {
        val library = PipoGraph.music.library
        return try {
            val result = library.like(track, like)
            val confirmed = result.confirmedProviderIds.map(MusicProviders::label)
            val pending = result.pendingProviderIds.map(MusicProviders::label)
            val complete = confirmed.isNotEmpty() && pending.isEmpty()
            val receipt = listOfNotNull(
                confirmed.takeIf { it.isNotEmpty() }?.joinToString("、")?.let {
                    if (like) "已加入${it}的我喜欢" else "已取消${it}的收藏"
                },
                pending.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "${it}尚未完成，可在对应歌单的同步详情查看" },
            ).joinToString("；")
            ActionExecutionResult(
                actionId, "like", complete, "$receipt：${trackLabel(track)}",
                tracks = listOf(track), currentTrack = currentTrack, queueSnapshot = queue,
                likedTrack = track.takeIf { confirmed.isNotEmpty() }, acceptedByPlayer = complete,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionExecutionResult(
                actionId, "like", false,
                "${if (like) "收藏" else "取消收藏"}失败：${error.message ?: error::class.java.simpleName}",
                tracks = listOf(track), currentTrack = currentTrack, queueSnapshot = queue, acceptedByPlayer = false,
            )
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
        queue: List<NativeTrack>,
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
                    tracks = listOf(track), currentTrack = track, queueSnapshot = queue, playlistName = playlist.name,
                )
            } else {
                val detail = errors.values.firstOrNull()
                ActionExecutionResult(
                    actionId, "playlist", false,
                    detail?.let { "歌单操作未确认：$it" }
                        ?: "歌单操作已提交，正在等待资料库确认（待同步 ${after.pendingCount} 项）。",
                    tracks = listOf(track), currentTrack = track, queueSnapshot = queue,
                    playlistName = playlist.name, acceptedByPlayer = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ActionExecutionResult(
                actionId, "playlist", false,
                "操作歌单失败：${error.message ?: error::class.java.simpleName}",
                tracks = listOf(track), currentTrack = track, queueSnapshot = queue,
                playlistName = playlist.name, acceptedByPlayer = false,
            )
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

    private fun matchesTarget(track: NativeTrack, target: TrackRequirement): Boolean =
        titleLooksCompatible(track, target) &&
            (target.artist.isNullOrBlank() || artistMatches(track.artist, target.artist))

    private fun titleLooksCompatible(track: NativeTrack, target: TrackRequirement): Boolean {
        val left = CommandTextSignals.normalizeForMatch(track.title)
        val right = CommandTextSignals.normalizeForMatch(target.title)
        return right.isNotBlank() && left.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun artistMatches(leftRaw: String, rightRaw: String?): Boolean {
        if (rightRaw.isNullOrBlank()) return true
        val left = CommandTextSignals.normalizeForMatch(leftRaw)
        val right = CommandTextSignals.normalizeForMatch(rightRaw)
        return right.isNotBlank() && left.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    private fun likeCandidateScore(track: NativeTrack, target: TrackRequirement): Int {
        val title = CommandTextSignals.normalizeForMatch(track.title)
        val targetTitle = CommandTextSignals.normalizeForMatch(target.title)
        val titleScore = when {
            title == targetTitle -> 0
            title.contains(targetTitle) || targetTitle.contains(title) -> 50
            else -> 1000
        }
        val artistScore = if (target.artist.isNullOrBlank() || artistMatches(track.artist, target.artist)) 0 else 500
        return titleScore + artistScore + variantWeight(track.title)
    }

    private fun variantWeight(title: String): Int {
        val lower = title.lowercase()
        var weight = title.length
        if ("live" in lower || "现场" in lower || "演唱会" in lower) weight += 1000
        if ("伴奏" in lower || "instrumental" in lower || "karaoke" in lower) weight += 1000
        if ("cover" in lower || "翻唱" in lower) weight += 800
        if ("remix" in lower || "混音" in lower) weight += 700
        return weight
    }

    private fun trackLabel(track: NativeTrack): String =
        listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { track.title }

    private suspend fun resolvePlayableTarget(
        actionId: String,
        operation: QueueOperation,
        track: NativeTrack,
    ): NativeTrack? {
        val resolved = resolvePlayableTrack(track)
        if (resolved == null) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "playback_preflight_no_source",
                fields = mapOf(
                    "actionId" to actionId,
                    "operation" to operation.name,
                    "trackId" to track.id,
                    "neteaseId" to track.neteaseId,
                    "title" to track.title,
                    "artist" to track.artist,
                ),
            )
        }
        return resolved
    }

    private fun noPlayableSource(
        actionId: String,
        type: String,
        track: NativeTrack?,
    ): ActionExecutionResult =
        ActionExecutionResult(
            actionId = actionId,
            type = type,
            success = false,
            message = NO_PLAYABLE_SOURCE_MESSAGE,
            tracks = listOfNotNull(track),
            acceptedByPlayer = false,
            errorMessage = NO_PLAYABLE_SOURCE_MESSAGE,
            warnings = listOf(NO_PLAYABLE_SOURCE_MESSAGE),
        )

    private fun hardConstraintsFor(
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        operation: QueueOperation,
    ): QueueHardConstraints {
        val firstTrack = when {
            operation == QueueOperation.PlayNow && target != null -> target
            else -> null
        }
        val mustInclude = mergeRequirements(
            primaryGoal.primaryTracks +
                primaryGoal.mustInclude +
                listOfNotNull(primaryGoal.closer),
        )
        val excludeLanguages = primaryGoal.excludeTerms.filter(::looksLikeLanguage)
        val excludeArtists = primaryGoal.excludeTerms.filterNot(::looksLikeLanguage)
        return QueueHardConstraints(
            firstTrack = firstTrack,
            endingTrack = primaryGoal.closer,
            mustIncludeTracks = mustInclude,
            requiredArtists = mergeStrings(primaryGoal.primaryArtists),
            artistScope = if (primaryGoal.primaryArtists.any { it.isNotBlank() }) {
                primaryGoal.artistScope
            } else {
                ArtistScope.Focus
            },
            excludedArtists = mergeStrings(excludeArtists),
            excludedLanguages = mergeStrings(excludeLanguages),
            catalogConstraint = primaryGoal.catalogConstraint,
            catalogExceptionTracks = mergeRequirements(primaryGoal.mustInclude + listOfNotNull(primaryGoal.closer)),
        )
    }

    private fun mergeRequirements(requirements: List<TrackRequirement>): List<TrackRequirement> {
        val seen = HashSet<String>()
        return requirements.filter { requirement ->
            val key = listOf(
                CommandTextSignals.normalizeForMatch(requirement.title),
                CommandTextSignals.normalizeForMatch(requirement.artist.orEmpty()),
                requirement.placement.name,
            ).joinToString("|")
            key.isNotBlank() && seen.add(key)
        }
    }

    private fun mergeStrings(values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.map { it.trim() }
            .filter { it.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(it)) }
    }

    private fun looksLikeLanguage(value: String): Boolean {
        val key = value.lowercase()
        return listOf("韩语", "国语", "粤语", "英文", "日语", "中文", "korean", "mandarin", "cantonese", "english", "japanese")
            .any { it in key }
    }

    private fun resultForCommit(
        actionId: String,
        type: String,
        request: AgentQueueRequest,
        commit: QueueCommitResult,
        similar: Boolean,
        currentBeforeCommit: NativeTrack?,
    ): ActionExecutionResult {
        return when (commit) {
            is QueueCommitResult.Success -> {
                val plan = commit.plan
                val tracks = plan.tracks
                updateCommittedPlayback(plan, currentBeforeCommit)
                val summary = plan.toSummary(accepted = true)
                ActionExecutionResult(
                    actionId = actionId,
                    type = type,
                    success = true,
                    message = when (plan.operation) {
                        QueueOperation.InsertNext -> {
                            val title = summary.insertedTitle.ifBlank { tracks.firstOrNull()?.title.orEmpty() }
                            val suffix = if (tracks.size > 1) " 等 ${tracks.size} 首" else ""
                            if (plan.jumpToInserted) "切歌请求接上：$title$suffix" else "下一首给你接上：$title$suffix"
                        }
                        else -> {
                            val first = summary.firstTitle.ifBlank { tracks.firstOrNull()?.title.orEmpty() }
                            if (summary.reordered) "队列接上了，顺序按接歌优化：$first" else "队列接上了：$first"
                        }
                    },
                    tracks = tracks,
                    acceptedByPlayer = true,
                    actuallyStarted = false,
                    currentTrack = executionCurrentTrack(),
                    queueSnapshot = executionQueue(),
                    insertedTrack = tracks.firstOrNull().takeIf { plan.operation == QueueOperation.InsertNext },
                    insert = plan.operation == QueueOperation.InsertNext,
                    similar = similar,
                    warnings = summary.warnings,
                    committedQueueSummary = summary,
                )
            }
            is QueueCommitResult.Rejected -> {
                ActionExecutionResult(
                    actionId = actionId,
                    type = type,
                    success = false,
                    message = commit.messages.joinToString("、").ifBlank { "播放请求没有被播放器接收" },
                    acceptedByPlayer = false,
                    errorMessage = commit.reason,
                    warnings = commit.messages,
                )
            }
        }
    }

    private suspend fun actionCurrentTrack(): NativeTrack? {
        if (currentCursorUnconfirmed) return null
        return committedCurrentTrack ?: withContext(playerDispatcher) {
            if (committedQueue == null) {
                committedQueue = currentQueueProvider().takeIf { it.isNotEmpty() }?.toList()
            }
            currentTrackProvider()
        }
    }

    private suspend fun awaitCurrentAfterSkip(previousIds: Set<String>): NativeTrack? {
        if (previousIds.isEmpty()) return null
        return withTimeoutOrNull(SKIP_CURRENT_CONFIRM_TIMEOUT_MS) {
            var candidate = withContext(playerDispatcher) { currentTrackProvider() }
            while (candidate == null || candidate.id in previousIds) {
                delay(SKIP_CURRENT_CONFIRM_POLL_MS)
                candidate = withContext(playerDispatcher) { currentTrackProvider() }
            }
            candidate
        }
    }

    private fun executionCurrentTrack(): NativeTrack? =
        if (currentCursorUnconfirmed) null else committedCurrentTrack

    private fun executionQueue(): List<NativeTrack> =
        committedQueue ?: listOfNotNull(executionCurrentTrack())

    private fun updateCommittedPlayback(
        plan: app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlan,
        currentBeforeCommit: NativeTrack?,
    ) {
        val tracks = plan.tracks
        if (tracks.isEmpty()) return
        currentCursorUnconfirmed = false
        if (plan.operation == QueueOperation.InsertNext) {
            val base = committedQueue ?: listOfNotNull(currentBeforeCommit)
            val currentIndex = base.indexOfFirst { it.id == currentBeforeCommit?.id }
            val insertIndex = (currentIndex + 1).coerceIn(0, base.size)
            committedQueue = buildList {
                addAll(base.take(insertIndex))
                addAll(tracks)
                addAll(base.drop(insertIndex))
            }
            committedCurrentTrack = if (plan.jumpToInserted) tracks.first() else currentBeforeCommit
            return
        }
        if (plan.preserveCurrent && currentBeforeCommit != null) {
            val base = committedQueue ?: listOf(currentBeforeCommit)
            val currentIndex = base.indexOfFirst { it.id == currentBeforeCommit.id }
            val prefix = if (currentIndex >= 0) base.take(currentIndex + 1) else listOf(currentBeforeCommit)
            committedCurrentTrack = currentBeforeCommit
            committedQueue = prefix + tracks
            return
        }
        committedCurrentTrack = tracks.first()
        committedQueue = tracks
    }

    private fun stableId(operation: String, vararg parts: String): String {
        val seed = listOf(taskId, sourceUserText, operation, *parts).joinToString("|")
        return "agent-${seed.hashCode().toUInt().toString(16)}-$operation"
    }

    private companion object {
        private const val STREAM_URL_TIMEOUT_MS = 15_000L
        private const val SKIP_CURRENT_CONFIRM_TIMEOUT_MS = 1_000L
        private const val SKIP_CURRENT_CONFIRM_POLL_MS = 20L
        private const val NO_PLAYABLE_SOURCE_MESSAGE = "很抱歉，没有找到可播放的音源。"
        private val STREAM_LEVEL_FALLBACKS = listOf("lossless", "exhigh", "higher", "standard")
    }
}
