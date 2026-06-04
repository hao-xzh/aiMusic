package app.pipo.nativeapp.data.agent.runtime

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.AgentUiCard
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.QueuePlan
import app.pipo.nativeapp.data.agent.domain.TurnOutcome
import app.pipo.nativeapp.data.agent.domain.TurnTrace
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.normalize.CommandNormalizer
import app.pipo.nativeapp.data.agent.planner.MusicCommandPlanner
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder

class AgentRuntime(
    repository: PipoRepository,
    private val ledger: AgentLedgerStore,
    private val planner: MusicCommandPlanner = MusicCommandPlanner(repository, ledger),
    private val normalizer: CommandNormalizer = CommandNormalizer(),
    private val resolver: MusicResolver = MusicResolver(repository),
    private val queuePlanner: AgentQueuePlanner = AgentQueuePlanner(),
    private val replyGrounder: ReplyGrounder = ReplyGrounder(),
) {
    suspend fun handle(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
    ): TurnOutcome {
        return runPipeline(input, executor, planner.plan(input), source = "runtime")
    }

    private suspend fun runPipeline(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        plan: app.pipo.nativeapp.data.agent.domain.MusicTurnPlan,
        source: String,
    ): TurnOutcome {
        val normalized = normalizer.normalize(plan)
        val resolution = resolver.resolve(normalized, input)
        val resolvedPlan = resolution.plan
        val queuePlan = queuePlanner.plan(resolvedPlan)
        val results = execute(queuePlan, executor)
        val reply = replyGrounder.ground(
            plan = resolvedPlan.copy(actions = queuePlan.actions),
            validation = queuePlan.validation,
            results = results,
            persona = input.persona,
        )
        val trace = TurnTrace(
            turnId = normalized.turnId,
            plannerRaw = normalized.plannerRaw.ifBlank { "source=$source; replyHintLen=${normalized.replyHint.length}; actions=${plan.actions.size}" }.take(420),
            normalizedPlan = normalized.actions.joinToString(",") { describeAction(it) }.take(420),
            resolution = resolution.summary.take(420),
            queuePlan = queuePlan.actions.joinToString(",") { describeQueue(it) }.take(420),
            validation = queuePlan.validation.messages.joinToString("|"),
            execution = results.joinToString(",") {
                "${it.type}:${it.success}:accepted=${it.acceptedByPlayer}:err=${it.errorMessage.orEmpty()}"
            },
            finalReply = reply,
        )
        ledger.record(resolvedPlan.copy(actions = queuePlan.actions), queuePlan.validation, results, reply)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "agent_runtime_finish",
            fields = mapOf(
                "turnId" to normalized.turnId,
                "source" to source,
                "actionCount" to queuePlan.actions.size,
                "success" to results.all { it.success },
                "validationPassed" to queuePlan.validation.passed,
                "replyLen" to reply.length,
                "plannerRaw" to trace.plannerRaw.take(160),
                "normalizedPlan" to trace.normalizedPlan.take(160),
                "resolution" to trace.resolution.take(220),
                "queuePlan" to trace.queuePlan.take(220),
                "validation" to trace.validation.take(220),
                "execution" to trace.execution.take(220),
                "finalReply" to trace.finalReply.take(220),
                "ledgerCount" to ledger.recent().size,
            ),
        )
        return TurnOutcome(
            reply = reply,
            cards = results.mapNotNull(::cardFor),
            trace = trace,
            musicReferences = normalized.musicReferences,
        )
    }

    private suspend fun execute(
        queuePlan: QueuePlan,
        executor: AgentActionExecutor,
    ): List<ActionExecutionResult> {
        val results = ArrayList<ActionExecutionResult>()
        for (action in queuePlan.actions) {
            val result = when (action) {
                is PlannedAction.PlayRequest -> ActionExecutionResult(
                    actionId = action.actionId,
                    type = "play_queue",
                    success = false,
                    message = "这次计划还没解析成可播放队列，我不硬放错。",
                )
                is PlannedAction.PlayTracks -> {
                    when {
                        !queuePlan.validation.passed -> ActionExecutionResult(
                            actionId = action.actionId,
                            type = "play_queue",
                            success = false,
                            message = validationFailureMessage(queuePlan.validation.messages),
                            warnings = queuePlan.validation.messages,
                            errorMessage = "queue_validation_failed",
                        )
                        action.tracks.isEmpty() -> ActionExecutionResult(
                            actionId = action.actionId,
                            type = "play_queue",
                            success = false,
                            message = "这次没排出能播的歌，我不硬放错。",
                            errorMessage = "empty_resolved_tracks",
                        )
                        action.mode == PlayMode.ReplaceQueue || action.mode == PlayMode.PlayNow -> executor.playQueue(
                            actionId = action.actionId,
                            mode = action.mode,
                            tracks = action.tracks,
                            continuous = action.continuous,
                            primaryGoal = action.primaryGoal,
                            target = action.target,
                            similar = action.similar,
                        )
                        else -> executor.insertNext(
                            actionId = action.actionId,
                            track = action.tracks.first(),
                            jumpToInserted = action.jumpToInserted,
                        )
                    }
                }
                is PlannedAction.PlayPlaylist -> {
                    if (action.tracks.isEmpty()) {
                        ActionExecutionResult(
                            actionId = action.actionId,
                            type = "play_queue",
                            success = false,
                            message = "没找到可播放的「${action.name}」歌单。",
                            playlistName = action.name,
                            errorMessage = "playlist_empty_or_not_found",
                        )
                    } else {
                        executor.playQueue(
                            actionId = action.actionId,
                            mode = PlayMode.ReplaceQueue,
                            tracks = action.tracks,
                            continuous = null,
                            primaryGoal = app.pipo.nativeapp.data.agent.domain.MusicGoal(),
                            target = null,
                            similar = false,
                        )
                    }
                }
                is PlannedAction.LikeCurrent -> executor.likeCurrent(action.actionId, action.like)
                is PlannedAction.ModifyPlaylist -> executor.modifyPlaylist(action.actionId, action.add, action.playlistName)
                is PlannedAction.SkipCurrent -> executor.skip(action.actionId)
                is PlannedAction.Say -> ActionExecutionResult(
                    actionId = action.actionId,
                    type = "say",
                    success = true,
                    message = action.text,
                )
                is PlannedAction.Clarify -> ActionExecutionResult(
                    actionId = action.actionId,
                    type = "clarify",
                    success = true,
                    message = action.question,
                )
            }
            results.add(result)
        }
        return results
    }

    private fun validationFailureMessage(messages: List<String>): String {
        return when {
            messages.any { it.startsWith("primary_tracks_missed") } -> "指定的歌没全排上，我不硬放错。"
            messages.any { it == "direct_target_missed" || it == "insert_target_missed" } -> "指定的歌没接上，我不硬放错。"
            messages.any { it.startsWith("strict_artist_scope_violated") || it.startsWith("primary_artist_missed") } ->
                "歌手范围没接稳，我不硬塞别的。"
            messages.any { it == "must_include_missed" } -> "要带上的歌没接上，我不硬放错。"
            messages.any { it == "closer_missed" } -> "收尾那首没接上，我不硬放错。"
            messages.any { it == "exclude_term_hit" || it == "exclude_language_hit" } -> "这组混进了你不想要的内容，我不硬放错。"
            messages.any { it == "opening_energy_too_high" } -> "开场情绪没接对，我不硬放错。"
            messages.any { it == "language_interleave_weak" } -> "语言穿插没接稳，我不硬放错。"
            else -> "这次队列没接稳，我不硬放错。"
        }
    }

    private fun cardFor(result: ActionExecutionResult): AgentUiCard? {
        if (result.type == "say" || result.type == "clarify") return null
        if (!result.success) {
            return AgentUiCard(kind = AgentUiCard.Kind.Error, label = result.message, ok = false)
        }
        return when (result.type) {
            "play_queue", "insert_next" -> AgentUiCard(
                kind = AgentUiCard.Kind.Play,
                label = result.message,
                count = result.tracks.size,
                artists = result.tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().take(3).joinToString("、"),
                covers = result.tracks.mapNotNull { it.artworkUrl }.take(3),
                insert = result.insert,
                similar = result.similar,
            )
            "skip" -> AgentUiCard(kind = AgentUiCard.Kind.Skip, label = result.message)
            "like" -> AgentUiCard(
                kind = if (result.message.contains("取消")) AgentUiCard.Kind.Unlike else AgentUiCard.Kind.Like,
                label = result.message,
            )
            "playlist" -> AgentUiCard(
                kind = if (result.message.contains("移出")) AgentUiCard.Kind.PlaylistRemove else AgentUiCard.Kind.PlaylistAdd,
                label = result.message,
            )
            else -> null
        }
    }

    private fun describeAction(action: PlannedAction): String =
        when (action) {
            is PlannedAction.PlayRequest -> "${action.mode}:request:${action.target?.title.orEmpty()}"
            is PlannedAction.PlayTracks -> "${action.mode}:${action.tracks.firstOrNull()?.title.orEmpty()}"
            is PlannedAction.PlayPlaylist -> "playlist:${action.name}"
            is PlannedAction.LikeCurrent -> "like:${action.like}"
            is PlannedAction.ModifyPlaylist -> "playlistModify:${action.playlistName}"
            is PlannedAction.SkipCurrent -> "skip"
            is PlannedAction.Say -> "say"
            is PlannedAction.Clarify -> "clarify"
        }

    private fun describeQueue(action: PlannedAction): String =
        if (action is PlannedAction.PlayTracks) {
            action.tracks.take(5).joinToString(">") { it.title }
        } else {
            describeAction(action)
        }
}
