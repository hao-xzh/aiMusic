package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.QueueHardConstraints
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.playback.orchestrator.QueueOperation
import kotlinx.coroutines.flow.first

class PlayerAgentExecutor(
    private val repository: PipoRepository,
    private val currentTrackProvider: () -> NativeTrack?,
    private val sourceUserText: String,
    private val onApplyAgentQueueRequest: suspend (AgentQueueRequest) -> QueueCommitResult,
    private val onSkip: () -> Unit,
) : AgentActionExecutor {
    override suspend fun playQueue(
        actionId: String,
        mode: PlayMode,
        tracks: List<NativeTrack>,
        continuous: ContinuousQueueSource?,
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        similar: Boolean,
    ): ActionExecutionResult {
        val operation = when {
            similar -> QueueOperation.PlaySimilar
            mode == PlayMode.PlayNow -> QueueOperation.PlayNow
            else -> QueueOperation.ReplaceQueue
        }
        val request = AgentQueueRequest(
            requestId = actionId,
            sourceUserText = sourceUserText,
            operation = operation,
            tracks = tracks,
            continuous = continuous,
            desiredCount = tracks.size,
            hardConstraints = hardConstraintsFor(primaryGoal, target, operation),
        )
        return resultForCommit(actionId, "play_queue", request, onApplyAgentQueueRequest(request), similar)
    }

    override suspend fun insertNext(
        actionId: String,
        track: NativeTrack,
        jumpToInserted: Boolean,
    ): ActionExecutionResult {
        val request = AgentQueueRequest(
            requestId = actionId,
            sourceUserText = sourceUserText,
            operation = QueueOperation.InsertNext,
            tracks = listOf(track),
            continuous = null,
            jumpToInserted = jumpToInserted,
            desiredCount = 1,
        )
        return resultForCommit(actionId, "insert_next", request, onApplyAgentQueueRequest(request), similar = false)
    }

    override suspend fun skip(actionId: String): ActionExecutionResult {
        onSkip()
        return ActionExecutionResult(actionId, "skip", success = true, message = "换一首")
    }

    override suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult {
        val currentTrack = currentTrackProvider()
        val tid = currentTrack?.neteaseId
        if (currentTrack == null || tid == null) {
            return ActionExecutionResult(actionId, "like", success = false, message = "现在没在放歌，没法${if (like) "收藏" else "取消收藏"}。")
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "like_intent_invoke",
            fields = mapOf("neteaseId" to tid, "like" to like, "title" to currentTrack.title),
        )
        return runCatching { repository.likeSong(tid, like) }
            .fold(
                onSuccess = {
                    ActionExecutionResult(
                        actionId = actionId,
                        type = "like",
                        success = true,
                        message = if (like) "收藏好了：${currentTrack.title}" else "取消收藏了：${currentTrack.title}",
                        tracks = listOf(currentTrack),
                        currentTrack = currentTrack,
                        likedTrack = currentTrack,
                    )
                },
                onFailure = { err ->
                    ActionExecutionResult(
                        actionId = actionId,
                        type = "like",
                        success = false,
                        message = "${if (like) "收藏" else "取消收藏"}失败：${err.message ?: err::class.java.simpleName}",
                    )
                },
            )
    }

    override suspend fun modifyPlaylist(
        actionId: String,
        add: Boolean,
        playlistName: String,
    ): ActionExecutionResult {
        val currentTrack = currentTrackProvider()
        val tid = currentTrack?.neteaseId
        if (currentTrack == null || tid == null) {
            return ActionExecutionResult(actionId, "playlist", success = false, message = "现在没在放歌，没法操作歌单。")
        }
        val playlists = repository.playlists.first()
        val target = matchPlaylist(playlists, playlistName)
            ?: return ActionExecutionResult(actionId, "playlist", success = false, message = "没找到歌单「$playlistName」。")
        val opStr = if (add) "add" else "del"
        return runCatching { repository.playlistModifyTracks(target.id, opStr, listOf(tid)) }
            .fold(
                onSuccess = {
                    ActionExecutionResult(
                        actionId = actionId,
                        type = "playlist",
                        success = true,
                        message = "${if (add) "已加入" else "已移出"}「${target.name}」",
                        tracks = listOf(currentTrack),
                        playlistName = target.name,
                    )
                },
                onFailure = { err ->
                    ActionExecutionResult(
                        actionId = actionId,
                        type = "playlist",
                        success = false,
                        message = "操作歌单失败：${err.message ?: err::class.java.simpleName}",
                    )
                },
            )
    }

    private fun matchPlaylist(playlists: List<PipoPlaylist>, query: String): PipoPlaylist? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        return playlists.firstOrNull { it.name.lowercase() == q }
            ?: playlists.firstOrNull { it.name.lowercase().contains(q) }
            ?: playlists.firstOrNull { q.contains(it.name.lowercase()) }
    }

    private fun hardConstraintsFor(
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        operation: QueueOperation,
    ): QueueHardConstraints {
        val textConstraints = QueueHardConstraints.fromGoal(primaryGoal, sourceUserText)
        val firstTrack = when {
            operation == QueueOperation.PlayNow && target != null -> target
            else -> textConstraints.firstTrack
        }
        val mustInclude = mergeRequirements(
            textConstraints.mustIncludeTracks +
                primaryGoal.primaryTracks +
                primaryGoal.mustInclude +
                listOfNotNull(primaryGoal.closer),
        )
        val excludeLanguages = primaryGoal.excludeTerms.filter(::looksLikeLanguage)
        val excludeArtists = primaryGoal.excludeTerms.filterNot(::looksLikeLanguage)
        return textConstraints.copy(
            firstTrack = firstTrack,
            endingTrack = textConstraints.endingTrack ?: primaryGoal.closer,
            mustIncludeTracks = mustInclude,
            requiredArtists = mergeStrings(textConstraints.requiredArtists + primaryGoal.primaryArtists),
            artistScope = if (primaryGoal.primaryArtists.any { it.isNotBlank() }) {
                primaryGoal.artistScope
            } else {
                textConstraints.artistScope
            },
            excludedArtists = mergeStrings(textConstraints.excludedArtists + excludeArtists),
            excludedLanguages = mergeStrings(textConstraints.excludedLanguages + excludeLanguages),
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
    ): ActionExecutionResult {
        return when (commit) {
            is QueueCommitResult.Success -> {
                val plan = commit.plan
                val tracks = plan.tracks
                val summary = plan.toSummary(accepted = true)
                ActionExecutionResult(
                    actionId = actionId,
                    type = type,
                    success = true,
                    message = when (plan.operation) {
                        QueueOperation.InsertNext -> {
                            val title = summary.insertedTitle.ifBlank { tracks.firstOrNull()?.title.orEmpty() }
                            if (plan.jumpToInserted) "切歌请求接上：$title" else "下一首给你接上：$title"
                        }
                        else -> {
                            val first = summary.firstTitle.ifBlank { tracks.firstOrNull()?.title.orEmpty() }
                            if (summary.reordered) "队列接上了，顺序按接歌优化：$first" else "队列接上了：$first"
                        }
                    },
                    tracks = tracks,
                    acceptedByPlayer = true,
                    actuallyStarted = false,
                    currentTrack = null,
                    queueSnapshot = tracks,
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
}
