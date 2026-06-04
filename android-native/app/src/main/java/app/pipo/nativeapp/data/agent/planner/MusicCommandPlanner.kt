package app.pipo.nativeapp.data.agent.planner

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.MusicIntentCompiler
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.normalize.ContextReferenceResolver
import app.pipo.nativeapp.data.agent.session.ContinuationMode
import app.pipo.nativeapp.data.agent.session.ContinuationPolicy
import app.pipo.nativeapp.data.agent.session.SessionMutation
import org.json.JSONObject
import java.util.UUID

class MusicCommandPlanner(
    private val repository: PipoRepository,
    private val ledger: AgentLedgerStore,
) {
    private val contextReferenceResolver = ContextReferenceResolver()

    suspend fun plan(input: AgentTurnInput): MusicTurnPlan {
        val turnId = UUID.randomUUID().toString()
        val raw = runCatching {
            repository.aiChat(
                system = PLANNER_SYSTEM,
                user = buildUserPrompt(input),
                temperature = 0.2f,
                maxTokens = 900,
            )
        }.getOrNull().orEmpty()
        val parsed = parsePlannerJson(turnId, input, raw)
        val plan = attachRepairTarget(parsed ?: deterministicPlan(turnId, input))
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "planner_raw",
            fields = mapOf(
                "turnId" to plan.turnId,
                "actionCount" to plan.actions.size,
                "confidence" to plan.confidence,
                "rawLen" to raw.length,
                "source" to if (parsed == null) "deterministic" else "json",
            ),
        )
        return plan
    }

    private fun attachRepairTarget(plan: MusicTurnPlan): MusicTurnPlan {
        if (!plan.isRepair || plan.repairTargetTurnId != null) return plan
        val target = ledger.recent(1).lastOrNull()?.turnId?.takeIf { it.isNotBlank() } ?: return plan
        return plan.copy(repairTargetTurnId = target)
    }

    private fun buildUserPrompt(input: AgentTurnInput): String = buildString {
        appendLine("用户原文：${input.userText}")
        input.currentTrack?.let {
            appendLine("当前播放：${it.artist} - ${it.title}")
        }
        if (input.currentQueue.isNotEmpty()) {
            appendLine("当前队列前 ${input.currentQueue.take(10).size} 首：")
            input.currentQueue.take(10).forEachIndexed { index, track ->
                appendLine("${index + 1}. ${track.artist} - ${track.title}")
            }
        }
        input.activeSession?.let { session ->
            appendLine("当前活跃音乐意图 session：id=${session.sessionId}; generation=${session.generation}; origin=${session.origin}; intentHash=${session.activeIntentHash}; continuation=${session.continuationPolicy.mode}/${session.continuationPolicy.enabled}")
            appendLine("session 主要求：artists=${session.activeIntent.primaryArtists.joinToString("/")}; styles=${(session.activeIntent.refStyles + session.activeIntent.aiMainStyles).take(8).joinToString("/")}; root=${session.rootUserText.take(120)}")
        }
        input.currentTrackStyle?.let { style ->
            appendLine("当前曲风格：${style.asSearchTerms().take(10).joinToString("、")}; summary=${style.summary.take(160)}")
        }
        input.currentQueueStyle?.let { style ->
            appendLine("当前队列风格：${style.asSearchTerms().take(10).joinToString("、")}; summary=${style.summary.take(160)}")
        }
        if (input.references.isNotEmpty()) {
            appendLine("typed references：")
            input.references.takeLast(8).forEach { ref ->
                appendLine("- ${ref::class.simpleName}:${ref.refId}:${ref.label}")
            }
        }
        if (input.referenceBindings.isNotEmpty()) {
            appendLine("本轮已解析指代：${input.referenceBindings.joinToString(";") { "${it.phrase}->${it.refType}:${it.refId}" }}")
        }
        input.resolvedTrackReference?.let { track ->
            appendLine("本轮已解析歌曲引用：${track.artist.orEmpty()} - ${track.title}; placement=${track.placement}")
        }
        input.resolvedArtistReference?.takeIf { it.isNotBlank() }?.let { artist ->
            appendLine("本轮已解析歌手引用：$artist")
        }
        appendLine("续播设置：aiAutoContinue=${input.aiAutoContinueEnabled}; defaultContinuationMode=${input.defaultContinuationMode}; inheritAgentIntent=${input.inheritAgentIntentWhenAvailable}")
        if (input.historySummary.isNotBlank()) {
            appendLine("早前对话摘要：${input.historySummary.take(500)}")
        }
        if (input.userFacts.isNotBlank()) {
            appendLine("用户事实：${input.userFacts.take(500)}")
        }
        val ledgerText = ledger.recent(5).joinToString("\n") { entry ->
            "用户=${entry.userText}; plan=${entry.normalizedPlan}; success=${entry.success}; tracks=${entry.firstTracks.take(4).joinToString("/")}; validation=${entry.validation}; execution=${entry.execution}"
        }
        if (ledgerText.isNotBlank()) {
            appendLine("最近真实执行：")
            appendLine(ledgerText)
        }
        if (input.musicReferences.isNotEmpty()) {
            appendLine("可执行音乐指代：")
            input.musicReferences.takeLast(5).forEach { ref ->
                appendLine("- ${ref.artist.orEmpty()} - ${ref.title}")
            }
        }
        appendLine("只输出 JSON。")
    }

    private fun parsePlannerJson(turnId: String, input: AgentTurnInput, raw: String): MusicTurnPlan? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val actionsArr = obj.optJSONArray("actions") ?: return null
        val actions = mutableListOf<PlannedAction>()
        for (i in 0 until actionsArr.length()) {
            val actionObj = actionsArr.optJSONObject(i) ?: continue
            parseAction("a${i + 1}", actionObj, input)?.let { actions.add(it) }
        }
        if (actions.isEmpty()) return null
        return MusicTurnPlan(
            turnId = obj.optString("turnId").takeIf { it.isNotBlank() && it != "auto" } ?: turnId,
            userText = obj.optString("userText").ifBlank { input.userText },
            actions = actions,
            isRepair = obj.optBoolean("isRepair", CommandTextSignals.isRepair(input.userText)),
            repairTargetTurnId = obj.optString("repairTargetTurnId").takeIf { it.isNotBlank() },
            confidence = obj.optDouble("confidence", 0.75).coerceIn(0.0, 1.0),
            plannerRaw = raw.take(1200),
            musicReferences = parseMusicReferences(obj),
            sessionMutation = sessionMutationFromString(obj.optString("sessionMutation")),
            continuationPolicy = parseContinuationPolicy(obj.optJSONObject("continuationPolicy")),
            referenceBindings = input.referenceBindings,
        )
    }

    private fun parseAction(actionId: String, obj: JSONObject, input: AgentTurnInput): PlannedAction? {
        val userText = input.userText
        val type = obj.optString("type").trim().lowercase()
        val scoped = CommandTextSignals.playlistScopedRequest(userText)
        val explicitTracks = CommandTextSignals.explicitTrackList(userText)
        return when (type) {
            "replace_queue" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.ReplaceQueue,
                primaryGoal = MusicGoal(
                    primaryArtists = stringArray(obj.optJSONObject("primaryGoal"), "artists")
                        .filterNot(CommandTextSignals::isCatalogDescriptor)
                        .ifEmpty { scoped?.primaryArtists.orEmpty() }
                        .ifEmpty { input.resolvedArtistReference?.let(::listOf).orEmpty() },
                    artistScope = artistScopeFromJson(
                        obj.optJSONObject("primaryGoal")?.optString("artistScope").orEmpty(),
                        CommandTextSignals.artistScope(userText),
                    ),
                    playlistName = obj.optString("playlistName")
                        .ifBlank { obj.optJSONObject("primaryGoal")?.optString("playlistName").orEmpty() }
                        .ifBlank { scoped?.playlistName.orEmpty() },
                    primaryTracks = trackRequirements(obj.optJSONObject("primaryGoal"), "primaryTracks", TrackPlacement.MustInclude)
                        .ifEmpty { explicitTracks },
                    mustInclude = mustIncludeRequirements(obj, userText),
                    closer = closerRequirement(obj, userText),
                    excludeTerms = stringArray(obj.optJSONObject("constraints"), "excludeTerms") +
                        stringArray(obj.optJSONObject("constraints"), "excludeLanguages") +
                        stringArray(obj.optJSONObject("constraints"), "excludeArtists") +
                        stringArray(obj.optJSONObject("constraints"), "avoidWords"),
                ),
                desiredCount = desiredCountFor(obj, userText, explicitTracks, fallback = 12, min = 1, max = 60),
                continuationPolicy = parseContinuationPolicy(obj.optJSONObject("continuationPolicy")),
                sessionMutation = sessionMutationFromString(obj.optString("sessionMutation")).takeIf { it != SessionMutation.None }
                    ?: SessionMutation.CreateNewSession,
            )
            "play_now" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.PlayNow,
                target = trackRequirement(obj.optJSONObject("target"), TrackPlacement.Now)
                    ?: input.resolvedTrackReference?.copy(placement = TrackPlacement.Now),
                desiredCount = 1,
            )
            "insert_next" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.InsertNext,
                target = trackRequirement(obj.optJSONObject("target"), TrackPlacement.Next)
                    ?: input.resolvedTrackReference?.copy(placement = TrackPlacement.Next),
                desiredCount = 1,
                jumpToInserted = false,
                sessionMutation = SessionMutation.KeepCurrentSession,
            )
            "play_playlist" -> PlannedAction.PlayPlaylist(
                actionId = actionId,
                name = obj.optString("playlistName").ifBlank { obj.optString("name") },
                tracks = emptyList(),
            )
            "play_similar" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = if (CommandTextSignals.currentStyleRequest(userText)) PlayMode.PreserveCurrentThenReplace else PlayMode.ReplaceQueue,
                primaryGoal = MusicGoal(
                    primaryArtists = stringArray(obj.optJSONObject("primaryGoal"), "artists")
                        .filterNot(CommandTextSignals::isCatalogDescriptor)
                        .ifEmpty { CommandTextSignals.primaryArtistHints(userText) }
                        .ifEmpty { input.resolvedArtistReference?.let(::listOf).orEmpty() },
                    artistScope = ArtistScope.Similar,
                    playlistName = scoped?.playlistName.orEmpty(),
                ),
                desiredCount = desiredCountFor(obj, userText, emptyList(), fallback = 12, min = 1, max = 30),
                similar = true,
                styleCapsule = input.resolvedStyleReference
                    ?: input.currentTrackStyle.takeIf { CommandTextSignals.currentStyleRequest(userText) }
                    ?: input.currentQueueStyle.takeIf { CommandTextSignals.currentStyleRequest(userText) },
                sessionMutation = SessionMutation.CreateNewSession,
            )
            "answer_style" -> {
                val capsule = input.resolvedStyleReference ?: input.currentTrackStyle ?: input.currentQueueStyle
                if (capsule != null) PlannedAction.AnswerStyle(actionId, capsule, obj.optString("text").ifBlank { obj.optString("reply") })
                else PlannedAction.Clarify(actionId, "现在没有正在播放的歌，我没法判断风格。")
            }
            "update_continuation" -> PlannedAction.UpdateContinuation(
                actionId = actionId,
                policy = parseContinuationPolicy(obj.optJSONObject("continuationPolicy"))
                    ?: ContinuationPolicy(enabled = !CommandTextSignals.disableContinuation(userText), mode = continuationModeFor(userText)),
            )
            "skip" -> PlannedAction.SkipCurrent(actionId)
            "like" -> PlannedAction.LikeCurrent(actionId, like = true)
            "unlike" -> PlannedAction.LikeCurrent(actionId, like = false)
            "add_to_playlist" -> PlannedAction.ModifyPlaylist(actionId, add = true, playlistName = obj.optString("playlistName"))
            "remove_from_playlist" -> PlannedAction.ModifyPlaylist(actionId, add = false, playlistName = obj.optString("playlistName"))
            "say" -> PlannedAction.Say(actionId, obj.optString("text").ifBlank { obj.optString("reply") })
            "clarify" -> PlannedAction.Clarify(actionId, obj.optString("question").ifBlank { "你想听哪首？" })
            else -> null
        }
    }

    private fun deterministicPlan(turnId: String, input: AgentTurnInput): MusicTurnPlan {
        val text = input.userText
        val key = CommandTextSignals.normalizeCommandText(text)
        val explicitTracks = CommandTextSignals.explicitTrackList(text)
        val explicitCount = CommandTextSignals.explicitDesiredCount(text)
        val actions = when {
            CommandTextSignals.styleQuestion(text) -> {
                val capsule = input.resolvedStyleReference ?: input.currentTrackStyle ?: input.currentQueueStyle
                listOf(
                    if (capsule != null) PlannedAction.AnswerStyle("a1", capsule)
                    else PlannedAction.Clarify("a1", "现在没有正在播放的歌，我没法判断风格。"),
                )
            }
            CommandTextSignals.wantsMoreFromStyle(text) -> {
                val style = input.resolvedStyleReference ?: input.currentTrackStyle ?: input.currentQueueStyle
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.PreserveCurrentThenReplace,
                        primaryGoal = MusicGoal(artistScope = ArtistScope.Similar),
                        desiredCount = explicitCount ?: 12,
                        similar = true,
                        styleCapsule = style,
                        sessionMutation = if (input.activeSession?.isActive() == true) SessionMutation.UpdateCurrentSession else SessionMutation.CreateNewSession,
                        continuationPolicy = ContinuationPolicy(enabled = true, mode = ContinuationMode.CurrentTrackStyle),
                    ),
                )
            }
            CommandTextSignals.playlistScopedRequest(text) != null -> {
                val scoped = CommandTextSignals.playlistScopedRequest(text)!!
                if (scoped.target == null && scoped.primaryArtists.isEmpty()) {
                    listOf(
                        PlannedAction.PlayPlaylist(
                            actionId = "a1",
                            name = scoped.playlistName,
                            tracks = emptyList(),
                        ),
                    )
                } else {
                    listOf(
                        PlannedAction.PlayRequest(
                            actionId = "a1",
                            mode = if (scoped.target != null) PlayMode.PlayNow else PlayMode.ReplaceQueue,
                            primaryGoal = MusicGoal(
                                primaryArtists = scoped.primaryArtists.ifEmpty { input.resolvedArtistReference?.let(::listOf).orEmpty() },
                                artistScope = CommandTextSignals.artistScope(text),
                                playlistName = scoped.playlistName,
                            ),
                            target = scoped.target?.copy(placement = TrackPlacement.Now),
                            desiredCount = explicitCount ?: if (scoped.target != null) 1 else 12,
                            sessionMutation = if (scoped.target != null) SessionMutation.None else SessionMutation.CreateNewSession,
                        ),
                    )
                }
            }
            CommandTextSignals.existingPlaylistQuery(text) != null ->
                listOf(
                    PlannedAction.PlayPlaylist(
                        actionId = "a1",
                        name = CommandTextSignals.existingPlaylistQuery(text)!!,
                        tracks = emptyList(),
                    ),
                )
            CommandTextSignals.genericSimilarRequest(text) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = if (CommandTextSignals.currentStyleRequest(text)) PlayMode.PreserveCurrentThenReplace else PlayMode.ReplaceQueue,
                        primaryGoal = MusicGoal(artistScope = ArtistScope.Similar),
                        desiredCount = explicitCount ?: 12,
                        similar = true,
                        styleCapsule = input.resolvedStyleReference
                            ?: input.currentTrackStyle.takeIf { CommandTextSignals.currentStyleRequest(text) }
                            ?: input.currentQueueStyle.takeIf { CommandTextSignals.currentStyleRequest(text) },
                        sessionMutation = SessionMutation.CreateNewSession,
                    ),
                )
            CommandTextSignals.genericCatalogRequest(text) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = MusicGoal(artistScope = ArtistScope.Focus),
                        desiredCount = explicitCount ?: 12,
                        sessionMutation = SessionMutation.CreateNewSession,
                    ),
                )
            listOf("取消收藏", "不喜欢这首", "取消加心").any { it in key } ->
                listOf(PlannedAction.LikeCurrent("a1", like = false))
            listOf("收藏这首", "加心", "喜欢这首").any { it in key } ->
                listOf(PlannedAction.LikeCurrent("a1", like = true))
            listOf("跳过", "换一首", "下一首").any { it in key } && !listOf("下一首插", "插到下一首").any { it in key } ->
                listOf(PlannedAction.SkipCurrent("a1"))
            looksLikeInsertNext(key) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.InsertNext,
                        target = TrackRequirement(
                            title = CommandTextSignals.includedTrackTitle(text) ?: trailingTarget(text).ifBlank { text },
                            placement = TrackPlacement.Next,
                        ),
                        desiredCount = 1,
                        jumpToInserted = !CommandTextSignals.noInterrupt(text),
                    ),
                )
            contextReferenceResolver.resolveMentionedMusic(text, input.musicReferences) != null ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.PlayNow,
                        target = contextReferenceResolver.resolveMentionedMusic(text, input.musicReferences),
                        desiredCount = 1,
                    ),
                )
            input.resolvedTrackReference != null && looksLikePlayRequest(key) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.PlayNow,
                        target = input.resolvedTrackReference.copy(placement = TrackPlacement.Now),
                        desiredCount = 1,
                    ),
                )
            explicitTracks.isNotEmpty() ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = MusicGoal(primaryTracks = explicitTracks),
                        desiredCount = explicitCount ?: explicitTracks.size,
                        continuationPolicy = ContinuationPolicy(
                            enabled = !CommandTextSignals.disableContinuation(text) && CommandTextSignals.enableContinuation(text),
                            mode = continuationModeFor(text),
                        ),
                        sessionMutation = if (CommandTextSignals.disableContinuation(text)) SessionMutation.PauseCurrentSession else SessionMutation.CreateNewSession,
                    ),
                )
            CommandTextSignals.artistTrackTarget(text) != null -> {
                val target = CommandTextSignals.artistTrackTarget(text)!!
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.PlayNow,
                        target = target.copy(placement = TrackPlacement.Now),
                        desiredCount = 1,
                        sessionMutation = SessionMutation.CreateNewSession,
                    ),
                )
            }
            CommandTextSignals.connectiveLeadTrack(text) != null -> {
                val target = CommandTextSignals.connectiveLeadTrack(text)!!
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.PlayNow,
                        target = target.copy(placement = TrackPlacement.Now),
                        desiredCount = explicitCount ?: 12,
                        sessionMutation = SessionMutation.CreateNewSession,
                    ),
                )
            }
            looksLikePlayRequest(key) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = MusicGoal(
                            primaryArtists = CommandTextSignals.primaryArtistHints(text)
                                .ifEmpty { input.resolvedArtistReference?.let(::listOf).orEmpty() },
                            artistScope = CommandTextSignals.artistScope(text),
                            primaryTracks = explicitTracks,
                            mustInclude = CommandTextSignals.includedTrackTitle(text)?.let {
                                listOf(TrackRequirement(title = it, placement = TrackPlacement.MustInclude))
                            }.orEmpty(),
                            closer = CommandTextSignals.closerTrackTitle(text)?.let {
                                TrackRequirement(title = it, placement = TrackPlacement.Closer)
                            },
                            excludeTerms = CommandTextSignals.excludeTerms(text),
                        ),
                        desiredCount = explicitCount ?: explicitTracks.takeIf { it.isNotEmpty() }?.size ?: 12,
                        continuationPolicy = ContinuationPolicy(
                            enabled = input.aiAutoContinueEnabled || CommandTextSignals.enableContinuation(text),
                            mode = continuationModeFor(text),
                        ),
                        sessionMutation = SessionMutation.CreateNewSession,
                    ),
                )
            else -> listOf(PlannedAction.Say("a1", text = "嗯。"))
        }
        return MusicTurnPlan(
            turnId = turnId,
            userText = text,
            actions = actions,
            isRepair = CommandTextSignals.isRepair(text),
            confidence = 0.55,
            plannerRaw = "deterministic",
        )
    }

    private fun trackRequirements(obj: JSONObject?, key: String, placement: TrackPlacement): List<TrackRequirement> {
        val arr = obj?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<TrackRequirement>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsedPlacement = placementFromString(item.optString("placement"), placement)
            if (placement == TrackPlacement.Closer && parsedPlacement != TrackPlacement.Closer) continue
            if (placement != TrackPlacement.Closer && parsedPlacement == TrackPlacement.Closer) continue
            trackRequirement(item, parsedPlacement)?.let { out.add(it) }
        }
        return out
    }

    private fun desiredCountFor(
        obj: JSONObject,
        userText: String,
        explicitTracks: List<TrackRequirement>,
        fallback: Int,
        min: Int,
        max: Int,
    ): Int {
        val value = CommandTextSignals.explicitDesiredCount(userText)
            ?: explicitTracks.takeIf { it.isNotEmpty() }?.size
            ?: obj.optInt("desiredCount", fallback)
        return value.coerceIn(min, max)
    }

    private fun mustIncludeRequirements(obj: JSONObject, userText: String): List<TrackRequirement> {
        val arr = obj.optJSONArray("mustInclude") ?: return emptyList()
        val explicitCloserTitle = CommandTextSignals.closerTrackTitle(userText)
        val out = ArrayList<TrackRequirement>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = trackRequirement(item, TrackPlacement.MustInclude) ?: continue
            val isExplicitCloser = !explicitCloserTitle.isNullOrBlank() &&
                parsed.placement == TrackPlacement.Closer &&
                titleMatches(parsed.title, explicitCloserTitle)
            if (!isExplicitCloser) {
                out.add(parsed)
            }
        }
        return out
    }

    private fun closerRequirement(obj: JSONObject, userText: String): TrackRequirement? {
        val explicitCloserTitle = CommandTextSignals.closerTrackTitle(userText) ?: return null
        val fromMustInclude = trackRequirements(obj, "mustInclude", TrackPlacement.Closer)
            .firstOrNull { titleMatches(it.title, explicitCloserTitle) }
        return (fromMustInclude ?: TrackRequirement(title = explicitCloserTitle, placement = TrackPlacement.Closer))
            .copy(placement = TrackPlacement.Closer)
    }

    private fun trackRequirement(obj: JSONObject?, fallbackPlacement: TrackPlacement): TrackRequirement? {
        if (obj == null) return null
        val title = obj.optString("title").trim()
        if (title.isBlank()) return null
        return TrackRequirement(
            title = title,
            artist = obj.optString("artistHint").ifBlank { obj.optString("artist") }.takeIf { it.isNotBlank() },
            placement = placementFromString(obj.optString("placement"), fallbackPlacement),
            index = obj.optInt("index").takeIf { obj.has("index") && it >= 0 },
        )
    }

    private fun placementFromString(raw: String, fallback: TrackPlacement): TrackPlacement {
        val value = raw.lowercase()
        return when {
            value.contains("now") || value.contains("first") -> TrackPlacement.Now
            value.contains("next") -> TrackPlacement.Next
            value.contains("aftercurrent") || value.contains("after_current") || value.contains("接当前") -> TrackPlacement.AfterCurrent
            value.contains("middle") || value.contains("中间") -> TrackPlacement.Middle
            value.contains("atindex") || value.contains("at_index") -> TrackPlacement.AtIndex
            value.contains("end") || value.contains("closer") || value.contains("最后") -> TrackPlacement.Closer
            else -> fallback
        }
    }

    private fun parseContinuationPolicy(obj: JSONObject?): ContinuationPolicy? {
        if (obj == null) return null
        val enabled = obj.optBoolean("enabled", true)
        return ContinuationPolicy(
            enabled = enabled,
            mode = if (enabled) continuationModeFromString(obj.optString("mode")) else ContinuationMode.Off,
            desiredBatchSize = obj.optInt("desiredBatchSize", 8).coerceIn(1, 30),
        )
    }

    private fun continuationModeFor(text: String): ContinuationMode = when {
        CommandTextSignals.disableContinuation(text) -> ContinuationMode.Off
        CommandTextSignals.currentStyleRequest(text) -> ContinuationMode.CurrentTrackStyle
        else -> ContinuationMode.SameIntent
    }

    private fun continuationModeFromString(raw: String): ContinuationMode =
        when (raw.trim().lowercase()) {
            "sameintent", "same_intent", "intent", "同要求" -> ContinuationMode.SameIntent
            "currenttrackstyle", "current_track_style", "style", "当前风格" -> ContinuationMode.CurrentTrackStyle
            "currentqueuestyle", "current_queue_style" -> ContinuationMode.CurrentQueueStyle
            "manualplayliststyle", "manual_playlist_style" -> ContinuationMode.ManualPlaylistStyle
            "defaultairadio", "default_ai_radio" -> ContinuationMode.DefaultAiRadio
            "off", "disabled", "false" -> ContinuationMode.Off
            else -> ContinuationMode.SameIntent
        }

    private fun sessionMutationFromString(raw: String): SessionMutation =
        when (raw.trim().lowercase()) {
            "keepcurrentsession", "keep_current_session", "keep" -> SessionMutation.KeepCurrentSession
            "createnewsession", "create_new_session", "create", "new" -> SessionMutation.CreateNewSession
            "updatecurrentsession", "update_current_session", "update" -> SessionMutation.UpdateCurrentSession
            "supersedecurrentsession", "supersede_current_session", "supersede" -> SessionMutation.SupersedeCurrentSession
            "pausecurrentsession", "pause_current_session", "pause" -> SessionMutation.PauseCurrentSession
            "disablecontinuation", "disable_continuation", "disable" -> SessionMutation.DisableContinuation
            else -> SessionMutation.None
        }

    private fun titleMatches(left: String, right: String): Boolean {
        val leftKey = CommandTextSignals.normalizeForMatch(left)
        val rightKey = CommandTextSignals.normalizeForMatch(right)
        return rightKey.isNotBlank() && (leftKey == rightKey || leftKey.contains(rightKey) || rightKey.contains(leftKey))
    }

    private fun stringArray(obj: JSONObject?, key: String): List<String> {
        val arr = obj?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    private fun artistScopeFromJson(raw: String, fallback: ArtistScope): ArtistScope =
        when (raw.trim().lowercase()) {
            "strict", "only", "hard", "本人", "只听" -> ArtistScope.Strict
            "similar", "style", "风格", "类似", "像" -> ArtistScope.Similar
            "focus", "primary", "mostly", "为主", "主打" -> ArtistScope.Focus
            else -> fallback
        }

    private fun parseMusicReferences(obj: JSONObject): List<PetMemory.MusicReference> {
        val arr = obj.optJSONArray("musicReferences") ?: return emptyList()
        val out = ArrayList<PetMemory.MusicReference>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            out.add(
                PetMemory.MusicReference(
                    title = title,
                    artist = item.optString("artist").trim(),
                    reason = item.optString("reason").trim(),
                ),
            )
        }
        return out.take(8)
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    private fun looksLikeInsertNext(key: String): Boolean =
        listOf("下一首插", "插到下一首", "等这首完", "不要打断", "别打断", "放完接").any { it in key }

    private fun looksLikePlayRequest(key: String): Boolean =
        listOf("想听", "听点", "听首", "放点", "放首", "播放", "来点", "来一首", "再来", "排一组", "换一组", "播放列表", "歌单", "包含", "带上", "最后", "不要", "接").any { it in key }

    private fun trailingTarget(text: String): String =
        text.replace(Regex(".*(?:下一首插|插到下一首|放完接|等这首完了放)"), "").trim()

    private companion object {
        val PLANNER_SYSTEM: String = """
你是 Claudio Android 的音乐操作 Planner。只输出 JSON，不输出自然语言。

硬规则：
1. 不能写最终成功回复，不能说已播放、已收藏、已切换。
	2. 用户显式点名的歌手、歌曲、顺序、否定条件必须保留。
	3. “我想听 X，加一首 Y”中，X 是 primaryGoal，Y 是 mustInclude，Y 默认不是第一首。
4. “播放/放/听 + 歌手的 + 歌名”默认 type=play_now。
5. “下一首/插到下一首/等这首完了/不要打断”才是 type=insert_next。
6. “最后/收住/结尾”对应 placement=End。
7. “不要/别/不想听”进入 constraints。
8. 用户纠错时标记 isRepair=true。
	9. 只有“打开/播放 我的/已有/收藏/具体某个歌单”才是 type=play_playlist；“换一个播放列表，包含某歌”是 replace_queue + mustInclude。
10. “这首什么风格 / 什么类型”必须 type=answer_style，不改队列。
11. “当前风格 / 这种感觉 / 像这首，多来几首”必须 type=play_similar，sessionMutation=UpdateCurrentSession 或 CreateNewSession，continuationPolicy.enabled=true。
12. “下一首插 X，不要打断”必须 type=insert_next，sessionMutation=KeepCurrentSession，不能污染当前主续播要求。
13. 用户要求“自动续播 / 播完继续 / 续同要求”必须输出 continuationPolicy.enabled=true；“只播这几首 / 播完停 / 不要续”必须 enabled=false。

	艺人作用域规则：
	- 用户说“我想听 X / 放点 X / 来点 X / 播 X / X 的歌”，默认 primaryGoal.artistScope="Strict"。
	- 用户说“类似 X / X 那种 / X 风格 / 像 X”，primaryGoal.artistScope="Similar"。
	- 用户说“X 为主 / 主打 X / X 混一点别的”，primaryGoal.artistScope="Focus"。
	- Strict 时不得主动混入其他艺人，除非 mustInclude 里有用户显式点名的具体歌。

	输出 schema：
{
  "turnId":"auto",
  "userText":"原文",
  "isRepair":false,
  "confidence":0.0-1.0,
  "sessionMutation":"KeepCurrentSession|CreateNewSession|UpdateCurrentSession|PauseCurrentSession|DisableContinuation|None",
  "continuationPolicy":{"enabled":true,"mode":"SameIntent|CurrentTrackStyle|CurrentQueueStyle|ManualPlaylistStyle|DefaultAiRadio|Off","desiredBatchSize":8},
  "musicReferences":[{"title":"七里香","artist":"周杰伦","reason":"刚才介绍/推荐过的可播放歌曲"}],
  "actions":[
	    {"type":"replace_queue","sessionMutation":"CreateNewSession","continuationPolicy":{"enabled":true,"mode":"SameIntent"},"primaryGoal":{"artists":["陈奕迅"],"artistScope":"Strict","playlistName":"可选：只在某个歌单/我的网盘内找"},"mustInclude":[{"title":"暗号","artistHint":null,"placement":"AfterCurrent|Middle|End"}],"constraints":{"excludeTerms":[]},"desiredCount":12},
    {"type":"play_now","target":{"title":"然后怎样","artistHint":"陈奕迅","placement":"Now"}},
    {"type":"insert_next","sessionMutation":"KeepCurrentSession","target":{"title":"暗号","artistHint":"周杰伦","placement":"Next"}},
    {"type":"answer_style","text":"只在你需要补充口语解释时填写"},
    {"type":"update_continuation","continuationPolicy":{"enabled":false,"mode":"Off"}},
    {"type":"play_playlist","playlistName":"我的歌单名"},
    {"type":"like"},
    {"type":"unlike"},
    {"type":"skip"},
    {"type":"add_to_playlist","playlistName":"歌单名"},
    {"type":"remove_from_playlist","playlistName":"歌单名"},
    {"type":"say","text":"纯聊天回复"},
    {"type":"clarify","question":"澄清问题"}
  ]
}
""".trimIndent()
    }
}
