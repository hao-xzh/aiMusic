package app.pipo.nativeapp.data.agent.planner

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.ContinuationMode
import app.pipo.nativeapp.data.agent.domain.ContinuationPolicy
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.ReferenceContext
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.normalize.ContextReferenceResolver
import app.pipo.nativeapp.data.agent.normalize.MusicUnderstanding
import app.pipo.nativeapp.data.agent.normalize.PlaylistScopedRequest
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
        val parsed = parsePlannerJson(turnId, input.userText, raw)
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

    private fun parsePlannerJson(turnId: String, userText: String, raw: String): MusicTurnPlan? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val actionsArr = obj.optJSONArray("actions") ?: return null
        val actions = mutableListOf<PlannedAction>()
        for (i in 0 until actionsArr.length()) {
            val actionObj = actionsArr.optJSONObject(i) ?: continue
            parseAction("a${i + 1}", actionObj, userText)?.let { actions.add(it) }
        }
        if (actions.isEmpty()) return null
        return MusicTurnPlan(
            turnId = obj.optString("turnId").takeIf { it.isNotBlank() && it != "auto" } ?: turnId,
            userText = obj.optString("userText").ifBlank { userText },
            actions = actions,
            isRepair = obj.optBoolean("isRepair", CommandTextSignals.isRepair(userText)),
            repairTargetTurnId = obj.optString("repairTargetTurnId").takeIf { it.isNotBlank() },
            confidence = obj.optDouble("confidence", 0.75).coerceIn(0.0, 1.0),
            plannerRaw = raw.take(1200),
            musicReferences = parseMusicReferences(obj),
        )
    }

    private fun parseAction(actionId: String, obj: JSONObject, userText: String): PlannedAction? {
        val type = obj.optString("type").trim().lowercase()
        val scoped = CommandTextSignals.playlistScopedRequest(userText)
        val explicitTracks = CommandTextSignals.explicitTrackList(userText)
        val understood = MusicUnderstanding.analyze(userText)
        val goal = musicGoalFromJson(obj, userText, scoped, explicitTracks, understood)
        return when (type) {
            "replace_queue" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.ReplaceQueue,
                primaryGoal = goal,
                desiredCount = desiredCountFor(obj, userText, explicitTracks, fallback = understood.desiredCount ?: 12, min = 1, max = 60),
                similar = goal.referenceContext != ReferenceContext.None || CommandTextSignals.looksLikeSimilarRequest(userText),
            )
            "play_now" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.PlayNow,
                primaryGoal = goal,
                target = trackRequirement(obj.optJSONObject("target"), TrackPlacement.Now),
                desiredCount = 1,
            )
            "insert_next" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.InsertNext,
                primaryGoal = goal,
                target = trackRequirement(obj.optJSONObject("target"), TrackPlacement.Next),
                desiredCount = 1,
                jumpToInserted = false,
            )
            "play_playlist" -> PlannedAction.PlayPlaylist(
                actionId = actionId,
                name = obj.optString("playlistName").ifBlank { obj.optString("name") },
                tracks = emptyList(),
            )
            "play_similar" -> PlannedAction.PlayRequest(
                actionId = actionId,
                mode = PlayMode.ReplaceQueue,
                primaryGoal = goal.copy(artistScope = ArtistScope.Similar),
                desiredCount = desiredCountFor(obj, userText, emptyList(), fallback = understood.desiredCount ?: 12, min = 1, max = 30),
                similar = true,
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

    private fun musicGoalFromJson(
        obj: JSONObject,
        userText: String,
        scoped: PlaylistScopedRequest?,
        explicitTracks: List<TrackRequirement>,
        understood: MusicUnderstanding.Result,
    ): MusicGoal {
        val primaryObj = obj.optJSONObject("primaryGoal")
        val constraintsObj = obj.optJSONObject("constraints")
        val artists = stringArray(primaryObj, "artists")
            .filterNot(CommandTextSignals::isCatalogDescriptor)
            .ifEmpty { scoped?.primaryArtists.orEmpty() }
        val styleJson = primaryObj?.optJSONObject("styleProfile") ?: obj.optJSONObject("styleProfile")
        val parsedStyle = styleProfileFromJson(styleJson, understood.styleProfile)
        val reference = referenceContextFromJson(
            primaryObj?.optString("referenceContext").orEmpty().ifBlank { obj.optString("referenceContext") },
            understood.referenceContext,
        )
        return MusicGoal(
            primaryArtists = artists,
            artistScope = artistScopeFromJson(
                primaryObj?.optString("artistScope").orEmpty(),
                CommandTextSignals.artistScope(userText),
            ),
            playlistName = obj.optString("playlistName")
                .ifBlank { primaryObj?.optString("playlistName").orEmpty() }
                .ifBlank { scoped?.playlistName.orEmpty() },
            primaryTracks = trackRequirements(primaryObj, "primaryTracks", TrackPlacement.MustInclude)
                .ifEmpty { explicitTracks },
            mustInclude = mustIncludeRequirements(obj, userText),
            closer = closerRequirement(obj, userText),
            excludeTerms = stringArray(constraintsObj, "excludeTerms") +
                stringArray(constraintsObj, "excludeLanguages") +
                stringArray(constraintsObj, "excludeArtists") +
                stringArray(constraintsObj, "avoidWords"),
            hardGenres = parsedStyle.genres,
            hardLanguages = parsedStyle.languages,
            hardVocalTypes = parsedStyle.vocalTypes,
            softMoods = parsedStyle.moods,
            softScenes = parsedStyle.scenes,
            softTextures = parsedStyle.textures,
            softQualityWords = parsedStyle.qualityWords,
            softEnergy = parsedStyle.energy,
            refStyles = parsedStyle.refStyles,
            aiMainStyles = parsedStyle.genres + parsedStyle.moods + parsedStyle.scenes + parsedStyle.textures,
            aiAvoidStyles = parsedStyle.avoidTags,
            includeArtists = CommandTextSignals.includedArtistHints(userText),
            searchSeeds = listOf(parsedStyle.semanticQuery).filter { it.isNotBlank() },
            useCurrentStyleAnchor = reference == ReferenceContext.CurrentStyle,
            continuationKey = parsedStyle.semanticQuery.ifBlank { userText },
            styleProfile = parsedStyle,
            referenceContext = reference,
            continuationPolicy = continuationPolicyFromJson(
                obj.optJSONObject("continuationPolicy") ?: primaryObj?.optJSONObject("continuationPolicy"),
                understood.continuationPolicy,
            ),
        )
    }

    private fun deterministicPlan(turnId: String, input: AgentTurnInput): MusicTurnPlan {
        val text = input.userText
        val key = CommandTextSignals.normalizeCommandText(text)
        val explicitTracks = CommandTextSignals.explicitTrackList(text)
        val explicitCount = CommandTextSignals.explicitDesiredCount(text)
        val understood = MusicUnderstanding.analyze(text, input.currentTrack, input.currentQueue, input.historySummary)
        fun understoodGoal(extraArtists: List<String> = emptyList(), scope: ArtistScope = CommandTextSignals.artistScope(text)): MusicGoal = MusicGoal(
            primaryArtists = (extraArtists + CommandTextSignals.primaryArtistHints(text)).distinctBy { CommandTextSignals.normalizeForMatch(it) },
            artistScope = scope,
            primaryTracks = explicitTracks,
            mustInclude = CommandTextSignals.includedTrackTitle(text)?.let {
                listOf(TrackRequirement(title = it, placement = TrackPlacement.MustInclude))
            }.orEmpty(),
            closer = CommandTextSignals.closerTrackTitle(text)?.let {
                TrackRequirement(title = it, placement = TrackPlacement.Closer)
            },
            excludeTerms = CommandTextSignals.excludeTerms(text),
            hardGenres = understood.styleProfile.genres,
            hardLanguages = understood.styleProfile.languages,
            hardVocalTypes = understood.styleProfile.vocalTypes,
            softMoods = understood.styleProfile.moods,
            softScenes = understood.styleProfile.scenes,
            softTextures = understood.styleProfile.textures,
            softQualityWords = understood.styleProfile.qualityWords,
            softEnergy = understood.styleProfile.energy,
            refStyles = understood.styleProfile.refStyles,
            aiMainStyles = understood.styleProfile.genres + understood.styleProfile.moods + understood.styleProfile.scenes + understood.styleProfile.textures,
            aiAvoidStyles = understood.styleProfile.avoidTags,
            includeArtists = CommandTextSignals.includedArtistHints(text),
            searchSeeds = listOf(understood.styleProfile.semanticQuery).filter { it.isNotBlank() },
            useCurrentStyleAnchor = understood.referenceContext == ReferenceContext.CurrentStyle,
            continuationKey = understood.styleProfile.semanticQuery.ifBlank { text },
            styleProfile = understood.styleProfile,
            referenceContext = understood.referenceContext,
            continuationPolicy = understood.continuationPolicy,
        )
        val actions = when {
            understood.wantsStyleExplanation ->
                listOf(PlannedAction.Say("a1", text = MusicUnderstanding.styleExplanation(text, input.currentTrack)))
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
                            primaryGoal = understoodGoal(scoped.primaryArtists, CommandTextSignals.artistScope(text)).copy(
                                primaryArtists = scoped.primaryArtists,
                                playlistName = scoped.playlistName,
                            ),
                            target = scoped.target?.copy(placement = TrackPlacement.Now),
                            desiredCount = explicitCount ?: if (scoped.target != null) 1 else 12,
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
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = understoodGoal(scope = ArtistScope.Similar),
                        desiredCount = explicitCount ?: understood.desiredCount ?: 12,
                        similar = true,
                    ),
                )
            CommandTextSignals.genericCatalogRequest(text) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = understoodGoal(scope = ArtistScope.Focus),
                        desiredCount = explicitCount ?: understood.desiredCount ?: 12,
                    ),
                )
            listOf("取消收藏", "不喜欢这首", "取消加心").any { it in key } ->
                listOf(PlannedAction.LikeCurrent("a1", like = false))
            listOf("收藏这首", "加心", "喜欢这首").any { it in key } ->
                listOf(PlannedAction.LikeCurrent("a1", like = true))
            listOf("跳过", "换一首", "下一首").any { it in key } &&
                !understood.wantsInsertNext &&
                !understood.wantsPlayback &&
                !listOf("下一首插", "插到下一首").any { it in key } ->
                listOf(PlannedAction.SkipCurrent("a1"))
            looksLikeInsertNext(key) || understood.wantsInsertNext ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.InsertNext,
                        primaryGoal = understoodGoal(),
                        target = CommandTextSignals.insertNextTrack(text) ?: TrackRequirement(
                            title = CommandTextSignals.includedTrackTitle(text) ?: trailingTarget(text).ifBlank { text },
                            placement = TrackPlacement.Next,
                        ).takeUnless { understood.styleProfile.hasSignal },
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
            explicitTracks.isNotEmpty() ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = understoodGoal().copy(primaryTracks = explicitTracks),
                        desiredCount = explicitCount ?: explicitTracks.size,
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
                    ),
                )
            }
            understood.wantsPlayback ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = if (understood.wantsInsertNext && understood.styleProfile.hasSignal) PlayMode.InsertNext else PlayMode.ReplaceQueue,
                        primaryGoal = understoodGoal(),
                        target = null,
                        desiredCount = if (understood.wantsInsertNext) 1 else explicitCount ?: understood.desiredCount ?: 12,
                        similar = understood.referenceContext != ReferenceContext.None || understood.styleProfile.hasSignal,
                        jumpToInserted = !CommandTextSignals.noInterrupt(text),
                    ),
                )
            looksLikePlayRequest(key) ->
                listOf(
                    PlannedAction.PlayRequest(
                        actionId = "a1",
                        mode = PlayMode.ReplaceQueue,
                        primaryGoal = understoodGoal(),
                        desiredCount = explicitCount ?: understood.desiredCount ?: explicitTracks.takeIf { it.isNotEmpty() }?.size ?: 12,
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


    private fun styleProfileFromJson(obj: JSONObject?, fallback: MusicStyleProfile): MusicStyleProfile {
        if (obj == null) return fallback
        val parsed = MusicStyleProfile(
            semanticQuery = obj.optString("semanticQuery").ifBlank { fallback.semanticQuery },
            energy = obj.optString("energy").ifBlank { fallback.energy },
            moods = stringArray(obj, "moods").ifEmpty { fallback.moods },
            scenes = stringArray(obj, "scenes").ifEmpty { fallback.scenes },
            genres = stringArray(obj, "genres").ifEmpty { fallback.genres },
            textures = stringArray(obj, "textures").ifEmpty { fallback.textures },
            qualityWords = stringArray(obj, "qualityWords").ifEmpty { fallback.qualityWords },
            languages = stringArray(obj, "languages").ifEmpty { fallback.languages },
            vocalTypes = stringArray(obj, "vocalTypes").ifEmpty { fallback.vocalTypes },
            refStyles = stringArray(obj, "refStyles").ifEmpty { fallback.refStyles },
            avoidTags = stringArray(obj, "avoidTags").ifEmpty { fallback.avoidTags },
            transitionStyle = obj.optString("transitionStyle").ifBlank { fallback.transitionStyle },
            exploration = obj.optString("exploration").ifBlank { fallback.exploration },
        )
        return parsed.mergedWith(fallback)
    }

    private fun referenceContextFromJson(raw: String, fallback: ReferenceContext): ReferenceContext = when (raw.trim().lowercase()) {
        "currenttrack", "current_track", "track", "这首", "当前歌曲" -> ReferenceContext.CurrentTrack
        "currentstyle", "current_style", "style", "当前风格", "这个风格" -> ReferenceContext.CurrentStyle
        "currentqueue", "current_queue", "queue", "当前队列" -> ReferenceContext.CurrentQueue
        "previousintent", "previous_intent", "last", "刚才", "之前" -> ReferenceContext.PreviousIntent
        "mentionedtrack", "mentioned_track", "mentioned" -> ReferenceContext.MentionedTrack
        "none", "" -> fallback
        else -> fallback
    }

    private fun continuationPolicyFromJson(obj: JSONObject?, fallback: ContinuationPolicy): ContinuationPolicy {
        if (obj == null) return fallback
        return ContinuationPolicy(
            mode = when (obj.optString("mode").trim().lowercase()) {
                "disabled", "off", "none", "播完停" -> ContinuationMode.Disabled
                "sameintent", "same_intent", "intent" -> ContinuationMode.SameIntent
                "samestyle", "same_style", "style" -> ContinuationMode.SameStyle
                "samequeue", "same_queue", "queue" -> ContinuationMode.SameQueue
                else -> fallback.mode
            },
            preserveWhenInserting = obj.optBoolean("preserveWhenInserting", fallback.preserveWhenInserting),
            invalidatePreviousOnReplace = obj.optBoolean("invalidatePreviousOnReplace", fallback.invalidatePreviousOnReplace),
        )
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
                out.add(parsed.copy(placement = TrackPlacement.MustInclude))
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
        )
    }

    private fun placementFromString(raw: String, fallback: TrackPlacement): TrackPlacement {
        val value = raw.lowercase()
        return when {
            value.contains("now") || value.contains("first") -> TrackPlacement.Now
            value.contains("next") -> TrackPlacement.Next
            value.contains("end") || value.contains("closer") || value.contains("最后") -> TrackPlacement.Closer
            else -> fallback
        }
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
        listOf("下一首插", "插到下一首", "等这首完", "不要打断", "别打断", "放完接", "下一首想听", "下一首放", "下一首听", "下一首播放", "接下来想听", "接下来放").any { it in key }

    private fun looksLikePlayRequest(key: String): Boolean =
        listOf("想听", "听点", "听首", "放点", "放首", "播放", "来点", "来一首", "排一组", "换一组", "播放列表", "歌单", "包含", "带上", "最后", "不要", "接").any { it in key }

    private fun trailingTarget(text: String): String =
        text.replace(Regex(".*(?:下一首插|插到下一首|下一首(?:我)?(?:想听|放|听|播放)?|接下来(?:想听|放|听)?|放完接|等这首完了放)"), "").trim()

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
7. “不要/别/不想听”进入 constraints，同时“别太吵/别太炸/不要太苦”要进 styleProfile.avoidTags。
8. 用户纠错时标记 isRepair=true。
	9. 只有“打开/播放 我的/已有/收藏/具体某个歌单”才是 type=play_playlist；“换一个播放列表，包含某歌”是 replace_queue + mustInclude。
10. “放嗨一点/燃一点/忧郁一点/开车听/工作听/别太吵”都必须是 replace_queue，并填 styleProfile；不能输出 say。
11. “当前风格/这个感觉/刚才那个味道/多来几首”必须保留 referenceContext 和 styleProfile，用 current track/queue 作为上下文。
12. 用户只是“下一首插/不要打断”时用 insert_next；不要改变主队列续播意图。
13. artists 只能写真正的歌手/乐队名；“欢快的/嗨一点/开心点/轻快点/忧郁点/适合开车/适合工作”这类需求是风格、情绪或场景，必须放进 styleProfile，不能放进 artists。

	艺人作用域规则：
	- 用户说“我想听 X / 放点 X / 来点 X / 播 X / X 的歌”，只有 X 是真实歌手/乐队名时才放进 artists，并默认 primaryGoal.artistScope="Strict"。
	- 用户说“类似 X / X 那种 / X 风格 / 像 X”，primaryGoal.artistScope="Similar"。
	- 用户说“X 为主 / 主打 X / X 混一点别的”，primaryGoal.artistScope="Focus"。
	- Strict 时不得主动混入其他艺人，除非 mustInclude 里有用户显式点名的具体歌。

	输出 schema：
{
  "turnId":"auto",
  "userText":"原文",
  "isRepair":false,
  "confidence":0.0-1.0,
  "musicReferences":[{"title":"七里香","artist":"周杰伦","reason":"刚才介绍/推荐过的可播放歌曲"}],
  "actions":[
	    {"type":"replace_queue","primaryGoal":{"artists":["陈奕迅"],"artistScope":"Strict","playlistName":"可选：只在某个歌单/我的网盘内找","styleProfile":{"semanticQuery":"燃一点但不要吵","energy":"mid_high","moods":["energetic"],"scenes":["party"],"genres":[],"textures":["rhythmic"],"qualityWords":[],"languages":[],"vocalTypes":[],"refStyles":[],"avoidTags":["loud"],"transitionStyle":"energy_up","exploration":"balanced"},"referenceContext":"None"},"mustInclude":[{"title":"暗号","artistHint":null,"placement":"AfterPrimaryAnchor"}],"constraints":{"excludeTerms":[]},"continuationPolicy":{"mode":"SameIntent"},"desiredCount":12},
	    {"type":"replace_queue","primaryGoal":{"artists":[],"artistScope":"Focus","styleProfile":{"semanticQuery":"欢快一点的流行歌","energy":"mid_high","moods":["happy","bright","uplifting"],"scenes":[],"genres":[],"textures":["bouncy"],"qualityWords":["catchy"],"languages":[],"vocalTypes":[],"refStyles":[],"avoidTags":[],"transitionStyle":"energy_up","exploration":"balanced"},"referenceContext":"None"},"constraints":{"excludeTerms":[]},"continuationPolicy":{"mode":"SameIntent"},"desiredCount":12},
    {"type":"play_now","target":{"title":"然后怎样","artistHint":"陈奕迅","placement":"Now"}},
    {"type":"insert_next","target":{"title":"暗号","artistHint":"周杰伦","placement":"Next"}},
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
