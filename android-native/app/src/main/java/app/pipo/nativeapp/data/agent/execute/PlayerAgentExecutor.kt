package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.context.ReferenceBinding
import app.pipo.nativeapp.data.agent.context.StyleCapsule
import app.pipo.nativeapp.data.agent.context.StyleCapsuleBuilder
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.IntentSource
import app.pipo.nativeapp.data.agent.intent.MusicIntent
import app.pipo.nativeapp.data.agent.intent.MusicIntentCompiler
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.session.ContinuationMode
import app.pipo.nativeapp.data.agent.session.ContinuationPolicy
import app.pipo.nativeapp.data.agent.session.MusicIntentSessionContinuousSource
import app.pipo.nativeapp.data.agent.session.QueuePolicy
import app.pipo.nativeapp.data.agent.session.SessionMutation
import app.pipo.nativeapp.data.agent.session.SessionOrigin
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.QueueHardConstraints
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.playback.orchestrator.QueueOperation
import app.pipo.nativeapp.playback.orchestrator.QueueSoftPreferences
import app.pipo.nativeapp.playback.orchestrator.MixPolicy
import kotlinx.coroutines.flow.first

class PlayerAgentExecutor(
    private val repository: PipoRepository,
    private val currentTrackProvider: () -> NativeTrack?,
    private val currentQueueProvider: () -> List<NativeTrack> = { emptyList() },
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
        musicIntent: MusicIntent?,
        continuationPolicy: ContinuationPolicy?,
        sessionMutation: SessionMutation,
        styleCapsule: StyleCapsule?,
        referenceBindings: List<ReferenceBinding>,
    ): ActionExecutionResult {
        val operation = when {
            similar -> QueueOperation.PlaySimilar
            mode == PlayMode.PlayNow -> QueueOperation.PlayNow
            mode == PlayMode.PreserveCurrentThenReplace -> QueueOperation.PreserveCurrentThenReplace
            else -> QueueOperation.ReplaceQueue
        }
        val hardConstraints = hardConstraintsFor(primaryGoal, target, operation)
        val softPreferences = QueueSoftPreferences.fromUserText(sourceUserText)
        val mixPolicy = MixPolicy.fromUserText(sourceUserText)
        val activeIntent = musicIntent ?: MusicIntentCompiler.fromGoal(
            goal = primaryGoal,
            target = target,
            userText = sourceUserText,
            desiredCount = tracks.size.coerceAtLeast(1),
            style = styleCapsule,
        )
        val policy = continuationPolicy ?: continuationPolicyFor(sourceUserText)
        val session = prepareSession(
            mutation = sessionMutation,
            activeIntent = activeIntent,
            policy = policy,
            mixPolicy = mixPolicy,
            hardConstraints = hardConstraints,
            softPreferences = softPreferences,
            styleCapsule = styleCapsule,
            tracks = tracks,
        )
        val sessionAwareSource = session?.takeIf { policy.enabled }?.let { sessionSnapshot ->
            continuous?.let { source ->
                MusicIntentSessionContinuousSource(
                    sessionId = sessionSnapshot.sessionId,
                    generation = sessionSnapshot.generation,
                    activeIntentHash = sessionSnapshot.activeIntentHash,
                    origin = sessionSnapshot.origin,
                    intent = activeIntent,
                    policy = policy,
                    store = PipoGraph.playbackIntentSessionStore,
                ) { _, excludeIds, _ ->
                    source.fetchMore(excludeIds)
                }
            }
        } ?: continuous.takeIf { policy.enabled }
        val request = AgentQueueRequest(
            requestId = actionId,
            sourceUserText = sourceUserText,
            operation = operation,
            tracks = tracks,
            continuous = sessionAwareSource,
            desiredCount = tracks.size,
            mixPolicy = mixPolicy,
            hardConstraints = hardConstraints,
            softPreferences = softPreferences,
            musicIntent = activeIntent,
            continuationPolicy = policy,
            sessionMutation = sessionMutation,
            sessionId = session?.sessionId.orEmpty(),
            generation = session?.generation ?: 0L,
            activeIntentHash = session?.activeIntentHash.orEmpty(),
            referenceBindings = referenceBindings,
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
            sessionMutation = SessionMutation.KeepCurrentSession,
        )
        return resultForCommit(actionId, "insert_next", request, onApplyAgentQueueRequest(request), similar = false)
    }

    private fun continuationPolicyFor(userText: String): ContinuationPolicy {
        val disabled = CommandTextSignals.disableContinuation(userText)
        val enabled = !disabled && (CommandTextSignals.enableContinuation(userText) || CommandTextSignals.wantsMoreFromStyle(userText))
        val mode = when {
            disabled -> ContinuationMode.Off
            CommandTextSignals.currentStyleRequest(userText) -> ContinuationMode.CurrentTrackStyle
            else -> ContinuationMode.SameIntent
        }
        return ContinuationPolicy(
            enabled = enabled,
            mode = mode,
            desiredBatchSize = 8,
        )
    }

    private fun prepareSession(
        mutation: SessionMutation,
        activeIntent: MusicIntent,
        policy: ContinuationPolicy,
        mixPolicy: MixPolicy,
        hardConstraints: QueueHardConstraints,
        softPreferences: QueueSoftPreferences,
        styleCapsule: StyleCapsule?,
        tracks: List<NativeTrack>,
    ): app.pipo.nativeapp.data.agent.session.PlaybackIntentSession? {
        val store = runCatching { PipoGraph.playbackIntentSessionStore }.getOrNull() ?: return null
        return when (mutation) {
            SessionMutation.CreateNewSession,
            SessionMutation.SupersedeCurrentSession -> store.create(
                origin = if (styleCapsule != null) SessionOrigin.CurrentTrackStyle else SessionOrigin.AgentInstruction,
                rootUserText = sourceUserText,
                lastUserText = sourceUserText,
                activeIntent = activeIntent,
                continuationPolicy = policy,
                queuePolicy = QueuePolicy(
                    preserveCurrentTrack = CommandTextSignals.currentStyleRequest(sourceUserText),
                    defaultDesiredCount = tracks.size.coerceAtLeast(1),
                    allowOnlineBackfill = mixPolicy.allowOnlineBackfill,
                ),
                mixPolicy = mixPolicy,
                hardConstraints = hardConstraints,
                softPreferences = softPreferences,
                styleAnchor = styleCapsule,
                trackAnchor = currentTrackProvider(),
                queueAnchorIds = tracks.map { it.id },
            )
            SessionMutation.UpdateCurrentSession -> store.update(
                activeIntent = activeIntent,
                lastUserText = sourceUserText,
                continuationPolicy = policy,
                styleAnchor = styleCapsule,
                trackAnchor = currentTrackProvider(),
                queueAnchorIds = tracks.map { it.id },
            ) ?: store.create(
                origin = if (styleCapsule != null) SessionOrigin.CurrentTrackStyle else SessionOrigin.AgentInstruction,
                rootUserText = sourceUserText,
                lastUserText = sourceUserText,
                activeIntent = activeIntent,
                continuationPolicy = policy,
                queuePolicy = QueuePolicy(defaultDesiredCount = tracks.size.coerceAtLeast(1)),
                mixPolicy = mixPolicy,
                hardConstraints = hardConstraints,
                softPreferences = softPreferences,
                styleAnchor = styleCapsule,
                trackAnchor = currentTrackProvider(),
                queueAnchorIds = tracks.map { it.id },
            )
            SessionMutation.PauseCurrentSession,
            SessionMutation.DisableContinuation -> store.pauseActive()
            SessionMutation.KeepCurrentSession -> store.active()
            SessionMutation.None -> null
        }
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

    override suspend fun updateContinuation(
        actionId: String,
        policy: ContinuationPolicy,
        currentQueue: List<NativeTrack>,
        currentTrack: NativeTrack?,
        styleCapsule: StyleCapsule?,
        referenceBindings: List<ReferenceBinding>,
    ): ActionExecutionResult {
        val store = runCatching { PipoGraph.playbackIntentSessionStore }.getOrNull()
            ?: return ActionExecutionResult(
                actionId = actionId,
                type = "continuation",
                success = false,
                message = "续播状态暂时不可用。",
                errorMessage = "session_store_unavailable",
                referenceBindings = referenceBindings,
            )
        if (!policy.enabled) {
            val paused = store.pauseActive()
            return ActionExecutionResult(
                actionId = actionId,
                type = "continuation",
                success = true,
                message = "这轮播完就不再自动续了。",
                sessionId = paused?.sessionId.orEmpty(),
                generation = paused?.generation ?: 0L,
                sessionMutation = SessionMutation.DisableContinuation.name,
                continuationMode = policy.mode.name,
                activeIntentHash = paused?.activeIntentHash.orEmpty(),
                referenceBindings = referenceBindings,
            )
        }

        val queue = currentQueue.ifEmpty { currentQueueProvider() }
        val style = styleCapsule ?: StyleCapsuleBuilder.fromQueue(queue) ?: StyleCapsuleBuilder.fromTrack(currentTrack)
        val styleTerms = style?.asSearchTerms().orEmpty()
        val intent = MusicIntent(
            queryText = styleTerms.joinToString(" ").ifBlank { sourceUserText.ifBlank { "当前队列风格" } },
            refStyles = styleTerms,
            aiMainStyles = style?.genres.orEmpty(),
            softMoods = style?.moods.orEmpty(),
            softScenes = style?.scenes.orEmpty(),
            softTextures = style?.textures.orEmpty(),
            softEnergy = style?.energy ?: "any",
            softTempoFeel = style?.tempoFeel ?: "any",
            desiredCount = policy.desiredBatchSize.coerceAtLeast(1),
            source = if (style?.trackId != null) IntentSource.CurrentTrackStyle else IntentSource.CurrentQueueStyle,
        )
        val active = store.active()
        val session = if (active != null) {
            store.update(
                activeIntent = active.activeIntent.takeIf { it.queryText.isNotBlank() } ?: intent,
                lastUserText = sourceUserText,
                continuationPolicy = policy,
                styleAnchor = style,
                trackAnchor = currentTrack,
                queueAnchorIds = queue.map { it.id },
            ) ?: active
        } else {
            store.create(
                origin = if (style?.trackId != null) SessionOrigin.CurrentTrackStyle else SessionOrigin.CurrentQueueStyle,
                rootUserText = sourceUserText.ifBlank { "update_continuation" },
                lastUserText = sourceUserText,
                activeIntent = intent,
                continuationPolicy = policy,
                queuePolicy = QueuePolicy(defaultDesiredCount = policy.desiredBatchSize.coerceAtLeast(1)),
                mixPolicy = MixPolicy(),
                hardConstraints = QueueHardConstraints(),
                softPreferences = QueueSoftPreferences(
                    preferredMoods = style?.moods.orEmpty(),
                    preferredGenres = style?.genres.orEmpty(),
                ),
                styleAnchor = style,
                trackAnchor = currentTrack,
                queueAnchorIds = queue.map { it.id },
            )
        }
        return ActionExecutionResult(
            actionId = actionId,
            type = "continuation",
            success = true,
            message = "后面会按这个要求续上。",
            sessionId = session.sessionId,
            generation = session.generation,
            sessionMutation = if (active != null) SessionMutation.UpdateCurrentSession.name else SessionMutation.CreateNewSession.name,
            continuationMode = session.continuationPolicy.mode.name,
            activeIntentHash = session.activeIntentHash,
            referenceBindings = referenceBindings,
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
                    sessionId = summary.sessionId,
                    generation = summary.generation,
                    sessionMutation = summary.sessionMutation,
                    continuationMode = summary.continuationMode,
                    activeIntentHash = summary.activeIntentHash,
                    referenceBindings = request.referenceBindings,
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
                    sessionId = request.sessionId,
                    generation = request.generation,
                    sessionMutation = request.sessionMutation.name,
                    continuationMode = request.continuationPolicy?.mode?.name.orEmpty(),
                    activeIntentHash = request.activeIntentHash,
                    referenceBindings = request.referenceBindings,
                )
            }
        }
    }
}
