package app.pipo.nativeapp.data.agent.runtime

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.CLOUD_DISK_PLAYLIST_ID
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.AgentUiCard
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.QueuePlan
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import app.pipo.nativeapp.data.agent.domain.ReferenceContext
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.domain.TurnOutcome
import app.pipo.nativeapp.data.agent.domain.TurnTrace
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.resolve.PlaylistResolver
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AgentToolLoop(
    private val repository: PipoRepository,
    private val ledger: AgentLedgerStore,
    private val resolver: MusicResolver,
    private val queuePlanner: AgentQueuePlanner,
    private val replyGrounder: ReplyGrounder,
) {
    suspend fun run(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
    ): TurnOutcome? {
        val turnId = UUID.randomUUID().toString()
        val state = LoopState(turnId = turnId, userText = input.userText)
        val messages = mutableListOf(
            JSONObject()
                .put("role", "system")
                .put("content", TOOL_SYSTEM),
            JSONObject()
                .put("role", "user")
                .put("content", buildUserPrompt(input)),
        )
        val tools = toolSchemas().toString()

        repeat(MAX_STEPS) { step ->
            val raw = runCatching {
                repository.aiChatTools(
                    messagesJson = JSONArray(messages).toString(),
                    toolsJson = tools,
                    temperature = 0.15f,
                    maxTokens = 1100,
                )
            }.getOrElse { error ->
                state.trace("aiChatTools_failed:${error.message ?: error::class.java.simpleName}")
                return null
            }
            val assistant = parseAssistantMessage(raw) ?: run {
                state.trace("assistant_parse_failed")
                return null
            }
            messages.add(assistant)
            val content = cleanString(assistant.opt("content"))
            if (content.isNotBlank()) state.lastAssistantContent = content
            val calls = parseToolCalls(assistant)
            if (calls.isEmpty()) {
                state.trace("assistant_no_tool_call")
                messages.add(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "你没有调用工具。请自己判断用户意图：需要执行就选执行工具；只是聊天/澄清就调用 final_response。不要只用自然语言回答。"),
                )
                return@repeat
            }
            for (call in calls) {
                val observation = executeTool(call, input, executor, state)
                messages.add(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("name", call.name)
                        .put("content", observation.toString()),
                )
            }
            if (state.done || (state.hasTerminalResult && step >= MAX_STEPS - 2)) {
                return buildOutcome(input, state, "")
            }
        }
        if (!state.hasTerminalResult && commitLastValidatedDraft(input, executor, state)) {
            return buildOutcome(input, state, "")
        }
        return if (state.hasTerminalResult) buildOutcome(input, state, "") else null
    }

    private suspend fun commitLastValidatedDraft(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): Boolean {
        if (state.playbackCommitted || state.hasTerminalResult) return false
        val entry = state.drafts.entries.lastOrNull { (_, draft) ->
            draft.queuePlan.validation.passed && draft.play.tracks.isNotEmpty()
        } ?: return false
        val (draftId, draft) = entry
        state.trace("auto_commit_validated_draft:$draftId:${draft.play.tracks.size}")
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "auto_commit_validated_draft",
            fields = mapOf(
                "turnId" to state.turnId,
                "draftId" to draftId,
                "trackCount" to draft.play.tracks.size,
                "mode" to draft.play.mode.name,
            ),
        )
        commitPlayTracks(draft.plan, draft.queuePlan.validation, draft.play, executor, state)
        return state.hasTerminalResult
    }

    private suspend fun buildOutcome(
        input: AgentTurnInput,
        state: LoopState,
        finalContent: String,
    ): TurnOutcome {
        val committed = state.committed
        if (committed.isEmpty()) {
            // 不变量上 buildOutcome 只在 committed 非空时被调用；这里是防御性兜底。
            val message = finalContent.ifBlank { state.lastAssistantContent }.ifBlank { "嗯。" }
            return TurnOutcome(
                reply = message,
                cards = emptyList(),
                trace = TurnTrace(
                    turnId = state.turnId,
                    plannerRaw = "tool_loop:${state.rawToolCalls.joinToString("|").take(360)}",
                    validation = "no_action",
                    execution = "say:true:accepted=false",
                    finalReply = message,
                ),
                musicReferences = input.musicReferences,
            )
        }
        val results = committed.map { it.result }
        val reply = composeReply(committed, input.persona)
        val combinedPlan = MusicTurnPlan(
            turnId = state.turnId,
            userText = input.userText,
            actions = committed.flatMap { it.plan.actions },
            plannerRaw = "tool_loop",
            musicReferences = carriedReferences(input, results),
        )
        val combinedValidation = QueueValidation(
            passed = results.all { it.success } && committed.all { it.validation.passed },
            messages = committed.flatMap { it.validation.messages }.distinct(),
        )
        val trace = TurnTrace(
            turnId = state.turnId,
            plannerRaw = "tool_loop:${state.rawToolCalls.joinToString("|").take(360)}",
            normalizedPlan = state.normalizedPlan.take(420),
            resolution = state.resolution.take(420),
            queuePlan = state.queuePlan.take(420),
            validation = combinedValidation.messages.joinToString("|"),
            execution = results.joinToString(",") {
                "${it.type}:${it.success}:accepted=${it.acceptedByPlayer}:err=${it.errorMessage.orEmpty()}"
            },
            finalReply = reply,
        )
        ledger.record(combinedPlan, combinedValidation, results, reply)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "agent_tool_loop_finish",
            fields = mapOf(
                "turnId" to state.turnId,
                "toolCalls" to state.rawToolCalls.joinToString(",").take(180),
                "success" to results.all { it.success },
                "resultTypes" to results.joinToString("+") { it.type },
                "validationPassed" to combinedValidation.passed,
                "trace" to state.traceLines.joinToString("|").take(220),
            ),
        )
        return TurnOutcome(
            reply = reply,
            cards = results.mapNotNull(::cardFor),
            trace = trace,
            musicReferences = combinedPlan.musicReferences,
        )
    }

    /**
     * 多动作一轮（如「收藏这首，再放类似的」）的回复合成：按执行顺序，播放/插播动作走
     * ReplyGrounder（人格化 + ReplyVerifier 校验），收藏/跳过/改歌单等用 executor 的事实文案。
     * 每条都基于真实结果，言行一致；失败也照实说（对齐文档 15.4「收藏失败但播放成功」）。
     */
    private suspend fun composeReply(
        committed: List<LoopState.Committed>,
        persona: PetPersona,
    ): String {
        // 纯聊天 / 澄清（final_response 单条）：直接用文本。
        if (committed.size == 1) {
            val only = committed.first()
            if (only.result.type == "say" || only.result.type == "clarify") return only.result.message
        }
        // 其余每个真实动作（含收藏/跳过/改歌单）都过 ReplyGrounder → 人格 LLM 文案 + ReplyVerifier
        // 校验 + 模板兜底，按执行顺序拼接，避免模板的千篇一律。
        val parts = ArrayList<String>()
        for (c in committed) {
            if (c.result.type == "say" || c.result.type == "clarify") continue
            parts.add(replyGrounder.ground(c.plan, c.validation, listOf(c.result), persona))
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ").ifBlank { committed.last().result.message }
    }

    /**
     * 保留跨轮「可执行音乐指代」：透传上轮 references，并把本轮明确指到的单曲（插播 / 收藏）
     * 补进去，让下一轮「再放刚那首 / 把刚那首收藏了」能解析。整队列的 vibe 歌不算指代，避免刷屏。
     * （宽泛纠错的上下文走 ledger.recent，不依赖这里。）
     */
    private fun carriedReferences(
        input: AgentTurnInput,
        results: List<ActionExecutionResult>,
    ): List<PetMemory.MusicReference> {
        val fresh = ArrayList<PetMemory.MusicReference>()
        results.forEach { result ->
            result.insertedTrack?.let {
                fresh.add(PetMemory.MusicReference(title = it.title, artist = it.artist, reason = "刚接到下一首"))
            }
            result.likedTrack?.let {
                fresh.add(PetMemory.MusicReference(title = it.title, artist = it.artist, reason = "刚收藏的歌"))
            }
        }
        if (fresh.isEmpty()) return input.musicReferences
        return (fresh + input.musicReferences)
            .distinctBy { "${it.title}|${it.artist}" }
            .take(8)
    }

    private suspend fun executeTool(
        call: ToolCall,
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        state.rawToolCalls.add(call.name)
        return runCatching {
            when (call.name) {
                "list_playlists" -> listPlaylists(state)
                "get_playlist_tracks" -> getPlaylistTracks(call.arguments, state)
                "search_tracks" -> searchTracks(call.arguments, state)
                "draft_queue" -> draftQueue(call.arguments, input, state)
                "commit_queue" -> commitQueue(call.arguments, input, executor, state)
                "final_response" -> finalResponse(call.arguments, input, state)
                "skip_current" -> skipCurrent(input, executor, state)
                "like_current" -> commitSimple(
                    state = state,
                    plan = MusicTurnPlan(
                        turnId = state.turnId,
                        userText = input.userText,
                        actions = listOf(PlannedAction.LikeCurrent("like", call.arguments.optBoolean("like", true))),
                        plannerRaw = "tool_loop",
                    ),
                    validation = QueueValidation(true),
                    result = executor.likeCurrent("like", call.arguments.optBoolean("like", true)),
                )
                "like_track" -> likeTrack(call.arguments, input, executor, state)
                "modify_playlist_current" -> modifyPlaylist(call.arguments, input, executor, state)
                else -> JSONObject()
                    .put("ok", false)
                    .put("error", "unknown_tool:${call.name}")
            }
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("tool", call.name)
                .put("error", error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun listPlaylists(state: LoopState): JSONObject {
        val playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
        val cloudCount = runCatching { repository.cachedTracksFor(CLOUD_DISK_PLAYLIST_ID)?.size ?: 0 }.getOrDefault(0)
        val arr = JSONArray()
        if (cloudCount > 0) {
            arr.put(
                JSONObject()
                    .put("id", CLOUD_DISK_PLAYLIST_ID)
                    .put("name", "我的网盘")
                    .put("trackCount", cloudCount)
                    .put("kind", "cloud"),
            )
        }
        playlists.take(40).forEach { playlist ->
            arr.put(playlistJson(playlist))
        }
        state.trace("list_playlists:${arr.length()}")
        return JSONObject()
            .put("ok", true)
            .put("playlists", arr)
    }

    private suspend fun getPlaylistTracks(args: JSONObject, state: LoopState): JSONObject {
        val name = args.optString("playlist_name").ifBlank { args.optString("name") }
        val id = args.optLong("playlist_id", Long.MIN_VALUE)
        val limit = args.optInt("limit", 40).coerceIn(1, 80)
        val tracks = when {
            id == CLOUD_DISK_PLAYLIST_ID || CommandTextSignals.isCloudPlaylistName(name) ->
                runCatching { repository.cloudDiskTracks() }.getOrDefault(emptyList())
            id != Long.MIN_VALUE -> runCatching { repository.tracksForPlaylist(id) }.getOrDefault(emptyList())
            else -> {
                val playlists = runCatching { repository.playlists.first() }.getOrDefault(emptyList())
                val playlist = PlaylistResolver().resolve(name, playlists)?.playlist
                playlist?.let { runCatching { repository.tracksForPlaylist(it.id) }.getOrDefault(emptyList()) }.orEmpty()
            }
        }
        val arr = tracks.take(limit).toTrackArray(state)
        state.trace("get_playlist_tracks:${name.ifBlank { id.toString() }}:${tracks.size}")
        return JSONObject()
            .put("ok", tracks.isNotEmpty())
            .put("trackCount", tracks.size)
            .put("tracks", arr)
            .put("error", if (tracks.isEmpty()) "playlist_empty_or_not_found" else JSONObject.NULL)
    }

    private suspend fun searchTracks(args: JSONObject, state: LoopState): JSONObject {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 12).coerceIn(1, 30)
        if (query.isBlank()) {
            return JSONObject().put("ok", false).put("error", "blank_query")
        }
        val tracks = runCatching { repository.searchTracks(query, limit) }.getOrDefault(emptyList())
        state.trace("search_tracks:$query:${tracks.size}")
        return JSONObject()
            .put("ok", tracks.isNotEmpty())
            .put("query", query)
            .put("tracks", tracks.toTrackArray(state))
            .put("error", if (tracks.isEmpty()) "no_search_results" else JSONObject.NULL)
    }

    private suspend fun draftQueue(
        args: JSONObject,
        input: AgentTurnInput,
        state: LoopState,
    ): JSONObject {
        val action = playRequestFromArgs(args, input)
        val basePlan = MusicTurnPlan(
            turnId = state.turnId,
            userText = input.userText,
            actions = listOf(action),
            plannerRaw = "tool_loop",
            confidence = 0.9,
        )
        val normalized = basePlan
        val resolution = resolver.resolve(normalized, input)
        val queuePlan = queuePlanner.plan(resolution.plan)
        val play = queuePlan.actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()
        val draftId = state.nextDraftId()
        if (play != null) {
            state.drafts[draftId] = QueueDraft(
                plan = resolution.plan.copy(actions = queuePlan.actions),
                queuePlan = queuePlan,
                play = play,
            )
            play.tracks.forEach { state.trackKey(it) }
        }
        state.normalizedPlan = normalized.actions.joinToString(",") { describeAction(it) }
        state.resolution = resolution.summary
        state.queuePlan = queuePlan.actions.joinToString(",") { describeAction(it) }
        state.trace("draft_queue:$draftId:${play?.tracks?.size ?: 0}:${queuePlan.validation.passed}")
        return JSONObject()
            .put("ok", play != null && play.tracks.isNotEmpty())
            .put("draftId", draftId)
            .put("mode", play?.mode?.name.orEmpty())
            .put("validationPassed", queuePlan.validation.passed)
            .put("validation", JSONArray(queuePlan.validation.messages))
            .put("tracks", play?.tracks.orEmpty().toTrackArray(state))
            .put("error", if (play == null || play.tracks.isEmpty()) "empty_draft_queue" else JSONObject.NULL)
    }

    private suspend fun commitQueue(
        args: JSONObject,
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        if (state.playbackCommitted) {
            return JSONObject()
                .put("ok", false)
                .put("error", "already_committed")
                .put("message", "本轮已经提交过一组播放队列了，别把同一组重复提交。")
        }
        val draftId = args.optString("draft_id").ifBlank { args.optString("draftId") }
        val draft = state.drafts[draftId]
        if (draft != null) {
            return commitPlayTracks(draft.plan, draft.queuePlan.validation, draft.play, executor, state)
        }
        val tracks = tracksForKeys(args.optJSONArray("track_keys"), state)
        if (tracks.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty_track_keys")
        }
        val action = PlannedAction.PlayTracks(
            actionId = "commit",
            mode = playMode(args.optString("operation"), PlayMode.ReplaceQueue),
            tracks = tracks,
            continuous = null,
            primaryGoal = goalFromArgs(args),
            target = trackRequirement(args.optJSONObject("target"), TrackPlacement.Now),
            similar = args.optBoolean("similar", false),
            jumpToInserted = args.optBoolean("jump_to_inserted", defaultJumpToInserted(playMode(args.optString("operation"), PlayMode.ReplaceQueue))),
        )
        val plan = MusicTurnPlan(
            turnId = state.turnId,
            userText = input.userText,
            actions = listOf(action),
            plannerRaw = "tool_loop_manual_commit",
        )
        val queuePlan = queuePlanner.plan(plan)
        val play = queuePlan.actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull() ?: action
        return commitPlayTracks(plan.copy(actions = queuePlan.actions), queuePlan.validation, play, executor, state)
    }

    private suspend fun commitPlayTracks(
        plan: MusicTurnPlan,
        validation: QueueValidation,
        play: PlannedAction.PlayTracks,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        if (!validation.passed) {
            return JSONObject()
                .put("ok", false)
                .put("error", "queue_validation_failed")
                .put("validation", JSONArray(validation.messages))
                .put("repairHint", "Use search_tracks/get_playlist_tracks/draft_queue again, then commit a different draft.")
        }
        if (play.tracks.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty_tracks")
        }
        val result = when (play.mode) {
            PlayMode.ReplaceQueue, PlayMode.PlayNow -> executor.playQueue(
                actionId = play.actionId,
                mode = play.mode,
                tracks = play.tracks,
                continuous = play.continuous,
                primaryGoal = play.primaryGoal,
                target = play.target,
                similar = play.similar,
            )
            PlayMode.InsertNext -> executor.insertNext(
                actionId = play.actionId,
                track = play.tracks.first(),
                jumpToInserted = play.jumpToInserted,
            )
        }
        if (result.success) {
            state.record(plan, validation, result, playback = true)
        }
        state.trace("commit_queue:${result.success}:${result.errorMessage.orEmpty()}")
        return resultObservation(result)
            .put("validation", JSONArray(validation.messages))
            .put("tracks", result.queueSnapshot.take(12).toTrackArray(state))
            .put(
                "repairHint",
                if (result.success) JSONObject.NULL else "Playback rejected this queue. Observe error/warnings, call search_tracks/get_playlist_tracks/draft_queue again, then commit a repaired queue.",
            )
    }

    private suspend fun likeTrack(
        args: JSONObject,
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        val requirement = trackRequirement(args.optJSONObject("target"), TrackPlacement.MustInclude)
            ?: TrackRequirement(
                title = args.optString("title"),
                artist = args.optString("artist").ifBlank { args.optString("artist_hint") }.takeIf { it.isNotBlank() },
            )
        val like = args.optBoolean("like", true)
        val plan = MusicTurnPlan(
            turnId = state.turnId,
            userText = input.userText,
            actions = listOf(PlannedAction.LikeTrack("like_track", like, requirement)),
            plannerRaw = "tool_loop",
        )
        return commitSimple(
            state = state,
            plan = plan,
            validation = QueueValidation(true),
            result = executor.likeTrack("like_track", like, requirement),
        )
    }

    private suspend fun modifyPlaylist(
        args: JSONObject,
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        val add = args.optBoolean("add", true)
        val playlistName = args.optString("playlist_name").ifBlank { args.optString("playlistName") }
        val plan = MusicTurnPlan(
            turnId = state.turnId,
            userText = input.userText,
            actions = listOf(PlannedAction.ModifyPlaylist("modify_playlist", add, playlistName)),
            plannerRaw = "tool_loop",
        )
        return commitSimple(
            state = state,
            plan = plan,
            validation = QueueValidation(true),
            result = executor.modifyPlaylist("modify_playlist", add, playlistName),
        )
    }

    private fun commitSimple(
        state: LoopState,
        plan: MusicTurnPlan,
        validation: QueueValidation,
        result: ActionExecutionResult,
    ): JSONObject {
        state.record(plan, validation, result, playback = false)
        state.trace("commit_simple:${result.type}:${result.success}")
        return resultObservation(result)
    }

    private fun finalResponse(
        args: JSONObject,
        input: AgentTurnInput,
        state: LoopState,
    ): JSONObject {
        val message = args.optString("message").ifBlank { args.optString("text") }.ifBlank { "嗯。" }
        // 本轮已有成功动作：回复必须由 ReplyGrounder 按真实执行结果生成，不能被这条自述文案覆盖
        // （否则会丢掉播放卡片、跳过 ReplyVerifier 校验）。
        if (state.hasSucceededAction) {
            state.trace("final_response_after_action_ignored")
            state.done = true
            return JSONObject()
                .put("ok", true)
                .put("note", "already_executed")
                .put("message", "本轮已有成功执行的动作，回复会按真实结果生成，无需再用 final_response 覆盖。本轮到此结束。")
        }
        // 言行一致护栏：final_response 是纯文本、不经 ReplyVerifier。若它声称已放/切/收藏/排好，
        // 但本轮没有任何执行工具成功提交，则拒收，逼模型要么真去执行、要么改成诚实说明。
        if (claimsActionSuccess(message)) {
            state.trace("final_response_blocked_unverified_claim")
            return JSONObject()
                .put("ok", false)
                .put("error", "unverified_success_claim")
                .put(
                    "message",
                    "你在 final_response 里说了已放/切了/收藏了/排好了，但本轮没有任何执行工具成功提交。" +
                        "要么先真正调用 draft_queue+commit_queue / like / skip 完成动作，要么改成‘还没执行 / 没找到’的诚实说明再 final_response。",
                )
        }
        state.done = true
        return commitSimple(
            state = state,
            plan = MusicTurnPlan(
                turnId = state.turnId,
                userText = input.userText,
                actions = listOf(PlannedAction.Say("final", message)),
                replyHint = message,
                plannerRaw = "tool_loop",
            ),
            validation = QueueValidation(true),
            result = ActionExecutionResult(
                actionId = "final",
                type = "say",
                success = true,
                message = message,
            ),
        )
    }

    private fun claimsActionSuccess(message: String): Boolean {
        val compact = message.replace(Regex("\\s+"), "")
        return SUCCESS_CLAIM_WORDS.any { it in compact }
    }

    private fun playRequestFromArgs(args: JSONObject, input: AgentTurnInput): PlannedAction.PlayRequest {
        val operation = args.optString("operation").ifBlank { args.optString("action") }
        val mode = playMode(operation, PlayMode.ReplaceQueue)
        val target = trackRequirement(args.optJSONObject("target"), if (mode == PlayMode.InsertNext) TrackPlacement.Next else TrackPlacement.Now)
            ?: args.optString("target_title").takeIf { it.isNotBlank() }?.let {
                TrackRequirement(
                    title = it,
                    artist = args.optString("target_artist").ifBlank { args.optString("artist_hint") }.takeIf { artist -> artist.isNotBlank() },
                    placement = if (mode == PlayMode.InsertNext) TrackPlacement.Next else TrackPlacement.Now,
                )
            }
        return PlannedAction.PlayRequest(
            actionId = "draft",
            mode = mode,
            primaryGoal = goalFromArgs(args),
            target = target,
            desiredCount = args.optInt("count", args.optInt("desired_count", 12)).coerceIn(1, 60),
            similar = args.optBoolean("similar", operation.contains("similar", ignoreCase = true)),
            jumpToInserted = args.optBoolean("jump_to_inserted", defaultJumpToInserted(mode)),
        )
    }

    private suspend fun skipCurrent(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
        state: LoopState,
    ): JSONObject {
        return commitSimple(
            state = state,
            plan = MusicTurnPlan(
                turnId = state.turnId,
                userText = input.userText,
                actions = listOf(PlannedAction.SkipCurrent("skip")),
                plannerRaw = "tool_loop",
            ),
            validation = QueueValidation(true),
            result = executor.skip("skip"),
        )
    }

    private fun defaultJumpToInserted(mode: PlayMode): Boolean = mode != PlayMode.InsertNext

    private fun goalFromArgs(args: JSONObject): MusicGoal {
        val style = styleFromArgs(args.optJSONObject("style") ?: args.optJSONObject("styleProfile"), args.optString("query"))
        val artists = stringArray(args, "artists").ifEmpty { stringArray(args, "primary_artists") }
        val mustInclude = trackRequirements(args.optJSONArray("must_include"), TrackPlacement.MustInclude) +
            trackRequirements(args.optJSONArray("mustInclude"), TrackPlacement.MustInclude) +
            stringArray(args, "must_include_titles").map { TrackRequirement(it, placement = TrackPlacement.MustInclude) }
        val closer = trackRequirement(args.optJSONObject("closer"), TrackPlacement.Closer)
            ?: args.optString("closer_title").takeIf { it.isNotBlank() }?.let { TrackRequirement(it, placement = TrackPlacement.Closer) }
        return MusicGoal(
            primaryArtists = artists,
            artistScope = artistScope(args.optString("artist_scope").ifBlank { args.optString("artistScope") }, ArtistScope.Focus),
            playlistName = args.optString("playlist_name").ifBlank { args.optString("playlistName") },
            mustInclude = mustInclude,
            closer = closer,
            excludeTerms = stringArray(args, "exclude_terms").ifEmpty { stringArray(args, "excludeTerms") },
            hardGenres = stringArray(args, "genres"),
            hardLanguages = stringArray(args, "languages"),
            softMoods = stringArray(args, "moods"),
            softScenes = stringArray(args, "scenes"),
            searchSeeds = listOf(args.optString("query")).filter { it.isNotBlank() },
            styleProfile = style,
            referenceContext = referenceContext(args.optString("reference_context").ifBlank { args.optString("referenceContext") }),
            includeArtists = stringArray(args, "include_artists").ifEmpty { stringArray(args, "includeArtists") },
        )
    }

    private fun styleFromArgs(obj: JSONObject?, fallbackQuery: String): MusicStyleProfile {
        if (obj == null) return MusicStyleProfile(semanticQuery = fallbackQuery)
        return MusicStyleProfile(
            semanticQuery = obj.optString("semanticQuery").ifBlank { obj.optString("semantic_query") }.ifBlank { fallbackQuery },
            energy = obj.optString("energy").ifBlank { "any" },
            moods = stringArray(obj, "moods"),
            scenes = stringArray(obj, "scenes"),
            genres = stringArray(obj, "genres"),
            textures = stringArray(obj, "textures"),
            qualityWords = stringArray(obj, "qualityWords").ifEmpty { stringArray(obj, "quality_words") },
            languages = stringArray(obj, "languages"),
            vocalTypes = stringArray(obj, "vocalTypes").ifEmpty { stringArray(obj, "vocal_types") },
            refStyles = stringArray(obj, "refStyles").ifEmpty { stringArray(obj, "ref_styles") },
            avoidTags = stringArray(obj, "avoidTags").ifEmpty { stringArray(obj, "avoid_tags") },
            transitionStyle = obj.optString("transitionStyle").ifBlank { obj.optString("transition_style") }.ifBlank { "soft" },
            exploration = obj.optString("exploration").ifBlank { "balanced" },
        )
    }

    private fun playMode(raw: String, fallback: PlayMode): PlayMode =
        when (raw.trim().lowercase()) {
            "play_now", "playnow", "now" -> PlayMode.PlayNow
            "insert_next", "insert", "next" -> PlayMode.InsertNext
            "replace_queue", "replace", "queue", "" -> if (raw.isBlank()) fallback else PlayMode.ReplaceQueue
            else -> fallback
        }

    private fun artistScope(raw: String, fallback: ArtistScope): ArtistScope =
        when (raw.trim().lowercase()) {
            "strict", "only", "hard" -> ArtistScope.Strict
            "similar", "style" -> ArtistScope.Similar
            "focus", "primary", "mostly" -> ArtistScope.Focus
            else -> fallback
        }

    private fun referenceContext(raw: String): ReferenceContext =
        when (raw.trim().lowercase()) {
            "currenttrack", "current_track" -> ReferenceContext.CurrentTrack
            "currentstyle", "current_style" -> ReferenceContext.CurrentStyle
            "currentqueue", "current_queue" -> ReferenceContext.CurrentQueue
            "previousintent", "previous_intent" -> ReferenceContext.PreviousIntent
            "mentionedtrack", "mentioned_track" -> ReferenceContext.MentionedTrack
            else -> ReferenceContext.None
        }

    private fun trackRequirements(arr: JSONArray?, placement: TrackPlacement): List<TrackRequirement> {
        if (arr == null) return emptyList()
        val out = ArrayList<TrackRequirement>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null) {
                trackRequirement(obj, placement)?.let(out::add)
            } else {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(TrackRequirement(it, placement = placement)) }
            }
        }
        return out
    }

    private fun trackRequirement(obj: JSONObject?, placement: TrackPlacement): TrackRequirement? {
        if (obj == null) return null
        val title = obj.optString("title").trim()
        if (title.isBlank()) return null
        return TrackRequirement(
            title = title,
            artist = obj.optString("artist").ifBlank { obj.optString("artistHint") }.ifBlank { obj.optString("artist_hint") }
                .takeIf { it.isNotBlank() },
            placement = when (obj.optString("placement").lowercase()) {
                "now", "first" -> TrackPlacement.Now
                "next" -> TrackPlacement.Next
                "closer", "end" -> TrackPlacement.Closer
                else -> placement
            },
        )
    }

    private fun tracksForKeys(arr: JSONArray?, state: LoopState): List<NativeTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<NativeTrack>()
        for (i in 0 until arr.length()) {
            val key = arr.optString(i)
            state.tracks[key]?.let(out::add)
        }
        return out
    }

    private fun List<NativeTrack>.toTrackArray(state: LoopState): JSONArray {
        val arr = JSONArray()
        take(80).forEach { track -> arr.put(trackJson(track, state.trackKey(track))) }
        return arr
    }

    private fun trackJson(track: NativeTrack, key: String): JSONObject =
        JSONObject()
            .put("key", key)
            .put("id", track.id)
            .put("neteaseId", track.neteaseId ?: JSONObject.NULL)
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("durationMs", track.durationMs)
            .put("hasStreamUrl", track.streamUrl.isNotBlank())

    private fun playlistJson(playlist: PipoPlaylist): JSONObject =
        JSONObject()
            .put("id", playlist.id)
            .put("name", playlist.name)
            .put("trackCount", playlist.trackCount)
            .put("kind", "playlist")

    private fun resultObservation(result: ActionExecutionResult): JSONObject =
        JSONObject()
            .put("ok", result.success)
            .put("type", result.type)
            .put("message", result.message)
            .put("acceptedByPlayer", result.acceptedByPlayer)
            .put("actuallyStarted", result.actuallyStarted)
            .put("error", result.errorMessage ?: JSONObject.NULL)
            .put("warnings", JSONArray(result.warnings))

    private fun stringArray(obj: JSONObject?, key: String): List<String> {
        val arr = obj?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    private fun parseAssistantMessage(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
            ?.also {
                if (!it.has("role")) it.put("role", "assistant")
            }
            ?: JSONObject()
                .put("role", "assistant")
                .put("content", trimmed)
    }

    private fun parseToolCalls(message: JSONObject): List<ToolCall> {
        val arr = message.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<ToolCall>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: continue
            val name = fn.optString("name").trim()
            if (name.isBlank()) continue
            val rawArgs = fn.opt("arguments")
            val args = when (rawArgs) {
                is JSONObject -> rawArgs
                is String -> runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            out.add(
                ToolCall(
                    id = item.optString("id").ifBlank { "call_$i" },
                    name = name,
                    arguments = args,
                ),
            )
        }
        return out
    }

    private fun cleanString(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        val out = value.toString().trim()
        return out.takeIf { it != "null" }.orEmpty()
    }

    private fun buildUserPrompt(input: AgentTurnInput): String = buildString {
        appendLine("用户原文：${input.userText}")
        input.currentTrack?.let { appendLine("当前播放：${it.artist} - ${it.title}") }
        if (input.currentQueue.isNotEmpty()) {
            appendLine("当前队列前 ${input.currentQueue.take(12).size} 首：")
            input.currentQueue.take(12).forEachIndexed { index, track ->
                appendLine("${index + 1}. ${track.artist} - ${track.title}")
            }
        }
        val recentTurns = input.history.takeLast(6).filter { it.text.isNotBlank() }
        if (recentTurns.isNotEmpty()) {
            appendLine("最近对话（最新在后，用于理解“这首/刚才那个/再来几首/不要这个”等指代）：")
            recentTurns.forEach { turn ->
                val who = if (turn.role == PetMemory.ROLE_USER) "用户" else "你"
                appendLine("$who：${turn.text.take(120)}")
            }
        }
        if (input.historySummary.isNotBlank()) appendLine("早前对话摘要：${input.historySummary.take(500)}")
        if (input.userFacts.isNotBlank()) appendLine("用户事实：${input.userFacts.take(500)}")
        val recent = ledger.recent(5).joinToString("\n") { entry ->
            "用户=${entry.userText}; success=${entry.success}; tracks=${entry.firstTracks.take(4).joinToString("/")}; validation=${entry.validation}; execution=${entry.execution}"
        }
        if (recent.isNotBlank()) {
            appendLine("最近真实执行：")
            appendLine(recent)
        }
        if (input.musicReferences.isNotEmpty()) {
            appendLine("可执行音乐指代：")
            input.musicReferences.takeLast(5).forEach { ref ->
                appendLine("- ${ref.artist.orEmpty()} - ${ref.title}")
            }
        }
        appendLine("必须先调用工具观察真实候选；需要播放/收藏/改歌单时必须以 commit/动作工具结束。")
    }

    private fun describeAction(action: PlannedAction): String =
        when (action) {
            is PlannedAction.PlayRequest -> "${action.mode}:request:${action.target?.title.orEmpty()}:${action.primaryGoal.primaryArtists.joinToString("/")}"
            is PlannedAction.PlayTracks -> "${action.mode}:tracks:${action.tracks.take(3).joinToString("/") { it.title }}"
            is PlannedAction.PlayPlaylist -> "playlist:${action.name}:${action.tracks.size}"
            is PlannedAction.LikeCurrent -> "like:${action.like}"
            is PlannedAction.LikeTrack -> "likeTrack:${action.target.artist.orEmpty()}-${action.target.title}:${action.like}"
            is PlannedAction.ModifyPlaylist -> "playlistModify:${action.playlistName}"
            is PlannedAction.SkipCurrent -> "skip"
            is PlannedAction.Say -> "say"
            is PlannedAction.Clarify -> "clarify"
        }

    private fun cardFor(result: ActionExecutionResult): AgentUiCard? {
        if (result.type == "say" || result.type == "clarify") return null
        if (!result.success) return AgentUiCard(kind = AgentUiCard.Kind.Error, label = result.message, ok = false)
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

    private fun toolSchemas(): JSONArray = JSONArray()
        .put(
            functionTool(
                "final_response",
                "Use only for pure chat, clarification, or an honest cannot-do response. Do not use it to claim playback/queue/like success.",
                JSONObject()
                    .put("message", stringSchema("Short response to the user"))
                    .put("reason", stringSchema("Why no playback/control tool is needed")),
                listOf("message"),
            ),
        )
        .put(functionTool("list_playlists", "List real user playlists before opening or scoping a playlist.", JSONObject(), emptyList()))
        .put(
            functionTool(
                "get_playlist_tracks",
                "Load real tracks from a playlist/cloud disk. Use before committing a named playlist.",
                JSONObject()
                    .put("playlist_name", stringSchema("Playlist name, e.g. 我的网盘"))
                    .put("playlist_id", integerSchema("Playlist id when known"))
                    .put("limit", integerSchema("Max tracks to return")),
                emptyList(),
            ),
        )
        .put(
            functionTool(
                "search_tracks",
                "Search NetEase tracks by title, artist, or natural query.",
                JSONObject()
                    .put("query", stringSchema("Search query"))
                    .put("limit", integerSchema("Max results, 1-30")),
                listOf("query"),
            ),
        )
        .put(
            functionTool(
                "draft_queue",
                "Ask the local resolver/ranker to build and validate a queue candidate. Use this before commit_queue for most playback requests.",
                queueDraftProperties(),
                emptyList(),
            ),
        )
        .put(
            functionTool(
                "commit_queue",
                "Commit a validated draft or explicit track keys to playback. If it fails, observe the reason and repair with more tools.",
                queueCommitProperties(),
                emptyList(),
            ),
        )
        .put(functionTool("skip_current", "Skip current track only when the user clearly asks to skip/换一首/跳过 with no target song to queue.", JSONObject(), emptyList()))
        .put(
            functionTool(
                "like_current",
                "Like or unlike the currently playing track.",
                JSONObject().put("like", booleanSchema("true to like, false to unlike")),
                emptyList(),
            ),
        )
        .put(
            functionTool(
                "like_track",
                "Like or unlike a named track after resolving it.",
                JSONObject()
                    .put("like", booleanSchema("true to like, false to unlike"))
                    .put("title", stringSchema("Track title"))
                    .put("artist", stringSchema("Artist hint")),
                listOf("title"),
            ),
        )
        .put(
            functionTool(
                "modify_playlist_current",
                "Add/remove the currently playing track to/from a playlist.",
                JSONObject()
                    .put("add", booleanSchema("true add, false remove"))
                    .put("playlist_name", stringSchema("Target playlist name")),
                listOf("playlist_name"),
            ),
        )

    private fun queueDraftProperties(): JSONObject =
        JSONObject()
            .put("operation", enumSchema("Queue operation", listOf("replace_queue", "play_now", "insert_next")))
            .put("query", stringSchema("Natural language music query/style"))
            .put("playlist_name", stringSchema("Scope to a playlist/cloud disk when requested"))
            .put("artists", arraySchema("Primary real artist names"))
            .put("artist_scope", enumSchema("Artist scope", listOf("Strict", "Focus", "Similar")))
            .put("count", integerSchema("Desired count"))
            .put("target_title", stringSchema("Specific first/next track title"))
            .put("target_artist", stringSchema("Specific first/next artist hint"))
            .put("jump_to_inserted", booleanSchema("LLM semantic decision for insert_next: true means jump immediately, false means keep current song playing and only queue next"))
            .put("must_include_titles", arraySchema("Track titles that must appear"))
            .put("closer_title", stringSchema("Track title requested at the end"))
            .put("exclude_terms", arraySchema("Artists/languages/styles to avoid"))
            .put("moods", arraySchema("Mood words"))
            .put("scenes", arraySchema("Scene words"))
            .put("genres", arraySchema("Genre words"))
            .put("languages", arraySchema("Language hints"))
            .put("style", JSONObject().put("type", "object"))

    private fun queueCommitProperties(): JSONObject =
        JSONObject()
            .put("draft_id", stringSchema("draftId returned by draft_queue"))
            .put("track_keys", arraySchema("Explicit track keys returned by search/get_playlist/draft"))
            .put("operation", enumSchema("Queue operation", listOf("replace_queue", "play_now", "insert_next")))
            .put("jump_to_inserted", booleanSchema("For insert_next, true only when the user explicitly asks to jump immediately; 下一首想听/接下来想听 should be false"))
            .put("similar", booleanSchema("Whether this is a similar/style continuation"))

    private fun functionTool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", JSONArray(required)),
                    ),
            )

    private fun stringSchema(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integerSchema(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun booleanSchema(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun arraySchema(description: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("description", description)
            .put("items", JSONObject().put("type", "string"))

    private fun enumSchema(description: String, values: List<String>): JSONObject =
        stringSchema(description).put("enum", JSONArray(values))

    private class LoopState(
        val turnId: String,
        val userText: String,
    ) {
        val tracks = LinkedHashMap<String, NativeTrack>()
        private val trackKeyById = HashMap<String, String>()
        val drafts = LinkedHashMap<String, QueueDraft>()
        val rawToolCalls = ArrayList<String>()
        val traceLines = ArrayList<String>()
        var normalizedPlan: String = ""
        var resolution: String = ""
        var queuePlan: String = ""
        var lastAssistantContent: String = ""

        /** 模型调用 final_response 收尾后置 true，让循环立即结束，不再空跑到 MAX_STEPS。 */
        var done = false

        /** 本轮所有已落地的动作（成功的播放/插播/收藏/跳过/改歌单，以及失败的简单动作），按执行顺序。 */
        val committed = ArrayList<Committed>()

        /** 已成功提交过播放队列/插播。只用它挡「同一轮重复提交播放」，不挡 like/skip 之后再排歌。 */
        var playbackCommitted = false
            private set

        private var trackSeq = 0
        private var draftSeq = 0

        fun record(
            plan: MusicTurnPlan,
            validation: QueueValidation,
            result: ActionExecutionResult,
            playback: Boolean,
        ) {
            committed.add(Committed(plan, validation, result))
            if (playback && result.success) playbackCommitted = true
        }

        val hasTerminalResult: Boolean get() = committed.isNotEmpty()

        /** 本轮是否已有「真实动作」成功（play/insert/like/skip/playlist），区别于纯 say/clarify。 */
        val hasSucceededAction: Boolean
            get() = committed.any { it.result.success && it.result.type != "say" && it.result.type != "clarify" }

        data class Committed(
            val plan: MusicTurnPlan,
            val validation: QueueValidation,
            val result: ActionExecutionResult,
        )

        fun trackKey(track: NativeTrack): String {
            val id = track.id.ifBlank { "${track.neteaseId}:${track.title}:${track.artist}" }
            trackKeyById[id]?.let { return it }
            val key = "t${++trackSeq}"
            trackKeyById[id] = key
            tracks[key] = track
            return key
        }

        fun nextDraftId(): String = "d${++draftSeq}"

        fun trace(value: String) {
            traceLines.add(value.take(120))
        }
    }

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    )

    private data class QueueDraft(
        val plan: MusicTurnPlan,
        val queuePlan: QueuePlan,
        val play: PlannedAction.PlayTracks,
    )

    private companion object {
        private const val MAX_STEPS = 7

        /** final_response 自述里出现这些词即视为「声称动作已完成」，需要有真实成功动作背书。 */
        private val SUCCESS_CLAIM_WORDS = listOf(
            "已放", "放了", "放好", "切了", "切过去", "插了", "插好", "接上了",
            "收藏了", "已收藏", "加好了", "已加入", "打开了", "排好了",
            "已经放", "已经播放", "已播放", "专场",
        )

        private val TOOL_SYSTEM = """
你是 Pipo Android 的执行型音乐 agent。所有理解和操作都由你完成：你用工具观察真实候选、修复、再提交。代码不替你兜底意图，也不替你硬执行。

【循环纪律】
1. 每一轮都必须至少调用一个工具。纯聊天 / 澄清 / 诚实说明做不到时，调用 final_response。
2. 要播放、排歌、插歌、打开歌单、收藏、改歌单，必须真正调用对应执行工具；绝不能只在 final_response 里口头答应。
3. 先观察再提交：用 list_playlists / get_playlist_tracks / search_tracks / draft_queue 看到真实候选，再 commit_queue。draft_queue 会跑本地解析+排序+校验，commit_queue 才真正交给播放器。
4. 工具失败时按 observation 修复：换 query、换 playlist、搜具体歌、放宽 artist_scope、重新 draft，再 commit，直到能播。
5. 不要编造歌名、歌手、歌单名或“已经放好”。final_response 只能描述工具已成功提交的动作；没成功就如实说没找到 / 没放成，不要假装完成。

【动作区分】
6. “播放/放/听 +（某歌手的）某首歌” = 立即播放：draft_queue(operation="play_now", target_title=歌名, target_artist=歌手) 再 commit_queue。
7. “下一首/插到下一首/等这首放完/不要打断” = 排到下一首：draft_queue(operation="insert_next", target_title=歌名, jump_to_inserted=false)；只有用户明确“现在就切过去”才 jump_to_inserted=true。
8. “我想听 X，加一首 Y / 带上 Y / 包含 Y” = 重排队列且必含：draft_queue(operation="replace_queue", artists=[X], must_include_titles=[Y])。X 是主目标，Y 只是必含；Y 默认不能排第一首，除非用户明确说“先放 Y / 开头放 Y”。别让附加的 Y 抢掉主目标 X。
9. “最后/收尾/结尾用 Z 收住” → closer_title=Z。
10. “不要/别/不想听 …”（歌手、语言、风格）→ exclude_terms；“别太吵/别太炸/别太苦” → style.avoid_tags。

【艺人范围 artist_scope】
11. 默认 "Focus"（以该歌手为主，可少量同味歌），避免硬塞导致排不出。
12. 只有用户明确“只听本人/就听 X 一个人/不要别人”才用 "Strict"。
13. “类似 X / X 那种 / X 风格 / 像 X” 用 "Similar"。
14. artists 只能填真实歌手/乐队名；“欢快的/嗨一点/开车/工作/忧郁点”是风格情绪场景，放进 query 和 style，不要塞进 artists。

【歌单】
15. “打开/播放 我的/某个 已有歌单（含‘我的网盘’）” → 先 get_playlist_tracks 再 commit_queue。
16. “换一组/排一组，要包含某歌手的某歌” 不是打开旧歌单 → draft_queue(operation="replace_queue", must_include_titles=[那首]，需要时 artists=[那个歌手])。

【收藏】
17. 收藏/取消收藏“当前正在放的这首” → like_current。
18. 收藏/取消收藏“具名的某首歌” → like_track(title, artist)。

【上下文纠错】
19. 用户像“陈奕迅呢？/不是这个/我说的是…/怎么只有暗号”这类纠错时，读上面“最近真实执行”，对照上一轮真实队列找出偏差，重新 draft+commit 修正，并在 final_response 里承认上一轮排偏了。

【宽泛 vibe】
20. “开车/晚安/工作/学习/燃一点/忧郁一点/安静点”等没有具体歌名的需求 → draft_queue(operation="replace_queue", query=自然语言, style={energy/moods/scenes...})，交给本地库和语义索引排。
21. skip_current 只用于用户明确要“跳过/换一首”当前这首、且没有指定要排的目标歌。
""".trimIndent()
    }
}
