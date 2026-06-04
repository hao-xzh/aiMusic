package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.ContinuationMode
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.ReferenceContext
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement

class CommandNormalizer {
    fun normalize(plan: MusicTurnPlan): MusicTurnPlan {
        val noInterrupt = CommandTextSignals.noInterrupt(plan.userText)
        val understood = MusicUnderstanding.analyze(plan.userText)
        val allowLocalFallback = plan.plannerRaw == "deterministic" || plan.plannerRaw.isBlank()
        val forcedAction = if (allowLocalFallback) forcedPlayAction(plan, understood) else null
        val normalizedActions = if (forcedAction != null && plan.actions.all { it is PlannedAction.Say }) {
            listOf(forcedAction)
        } else {
            plan.actions.map { action -> normalizeAction(plan.userText, action, noInterrupt, understood) }
        }
        val actions = appendImpliedActions(plan.userText, normalizedActions)
        val normalized = plan.copy(
            actions = actions,
            isRepair = plan.isRepair || CommandTextSignals.isRepair(plan.userText),
        )
        val primaryGoals = normalized.actions
            .mapNotNull {
                when (it) {
                    is PlannedAction.PlayRequest -> it.primaryGoal
                    is PlannedAction.PlayTracks -> it.primaryGoal
                    else -> null
                }
            }
        val primaryArtists = primaryGoals.flatMap { it.primaryArtists }.distinct()
        val artistScope = primaryGoals.firstOrNull { it.primaryArtists.isNotEmpty() }?.artistScope
            ?: CommandTextSignals.artistScope(normalized.userText)
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "normalized_plan",
            fields = mapOf(
                "turnId" to normalized.turnId,
                "actionCount" to normalized.actions.size,
                "isRepair" to normalized.isRepair,
                "repairTargetTurnId" to normalized.repairTargetTurnId.orEmpty(),
                "noInterrupt" to noInterrupt,
                "mustInclude" to CommandTextSignals.includedTrackTitle(normalized.userText).orEmpty().take(40),
                "closer" to CommandTextSignals.closerTrackTitle(normalized.userText).orEmpty().take(40),
                "primaryArtists" to primaryArtists.joinToString(",").take(80),
                "artistScope" to artistScope.name,
                "styleEnergy" to understood.styleProfile.energy,
                "styleMoods" to understood.styleProfile.moods.joinToString(",").take(80),
                "referenceContext" to understood.referenceContext.name,
            ),
        )
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "artist_scope_decision",
            fields = mapOf(
                "turnId" to normalized.turnId,
                "userText" to normalized.userText.take(120),
                "primaryArtists" to primaryArtists.joinToString(",").take(80),
                "artistScope" to artistScope.name,
                "source" to "planner+normalizer",
            ),
        )
        return normalized
    }

    private fun normalizeAction(
        userText: String,
        action: PlannedAction,
        noInterrupt: Boolean,
        understood: MusicUnderstanding.Result,
    ): PlannedAction {
        return when (action) {
            is PlannedAction.PlayRequest -> CommandTextSignals.existingPlaylistQuery(userText)
                ?.let { playlistName ->
                    PlannedAction.PlayPlaylist(
                        actionId = action.actionId,
                        name = playlistName,
                        tracks = emptyList(),
                    )
                }
                ?: normalizePlayRequest(userText, action, noInterrupt, understood)
            is PlannedAction.PlayTracks -> if (action.mode == PlayMode.InsertNext && noInterrupt) {
                action.copy(jumpToInserted = false)
            } else {
                action
            }
            else -> action
        }
    }

    private fun normalizePlayRequest(
        userText: String,
        action: PlannedAction.PlayRequest,
        noInterrupt: Boolean,
        understood: MusicUnderstanding.Result,
    ): PlannedAction.PlayRequest {
        CommandTextSignals.playlistScopedRequest(userText)?.let { scoped ->
            return playlistScopedPlayRequest(userText, action, scoped, noInterrupt, understood)
        }

        if (CommandTextSignals.genericSimilarRequest(userText)) {
            return action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(userText, action.primaryGoal.copy(artistScope = ArtistScope.Similar), understood),
                target = null,
                desiredCount = CommandTextSignals.explicitDesiredCount(userText) ?: understood.desiredCount ?: action.desiredCount.coerceAtLeast(12),
                similar = true,
            )
        }
        if (CommandTextSignals.genericCatalogRequest(userText)) {
            return action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(userText, action.primaryGoal.copy(artistScope = ArtistScope.Focus), understood),
                target = null,
                desiredCount = CommandTextSignals.explicitDesiredCount(userText) ?: understood.desiredCount ?: action.desiredCount.coerceAtLeast(12),
                similar = understood.referenceContext != ReferenceContext.None,
            )
        }

        val explicitTracks = CommandTextSignals.explicitTrackList(userText)
        if (explicitTracks.isNotEmpty()) {
            val desired = CommandTextSignals.explicitDesiredCount(userText) ?: explicitTracks.size
            return action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(userText, action.primaryGoal.copy(
                    primaryTracks = mergeRequirements(action.primaryGoal.primaryTracks, explicitTracks),
                ), understood),
                target = null,
                desiredCount = desired,
            )
        }

        val artistTrack = CommandTextSignals.artistTrackTarget(userText)
        if (artistTrack != null) {
            return action.copy(
                mode = PlayMode.PlayNow,
                target = artistTrack.copy(placement = TrackPlacement.Now),
                desiredCount = 1,
                jumpToInserted = true,
            )
        }

        val connectiveLead = CommandTextSignals.connectiveLeadTrack(userText)
        if (connectiveLead != null) {
            return action.copy(
                mode = PlayMode.PlayNow,
                target = connectiveLead.copy(placement = TrackPlacement.Now),
                desiredCount = action.desiredCount.coerceAtLeast(8),
                jumpToInserted = true,
            )
        }

        val insertTarget = CommandTextSignals.insertNextTrack(userText)
        if (insertTarget != null) {
            return action.copy(
                mode = PlayMode.InsertNext,
                target = insertTarget,
                desiredCount = 1,
                jumpToInserted = !noInterrupt,
            )
        }

        if (action.mode != PlayMode.ReplaceQueue) {
            val enriched = action.copy(primaryGoal = normalizeGoal(userText, action.primaryGoal, understood))
            return if (enriched.mode == PlayMode.InsertNext && noInterrupt) enriched.copy(jumpToInserted = false) else enriched
        }

        val normalizedGoal = normalizeGoal(userText, action.primaryGoal, understood)
        return action.copy(
            primaryGoal = normalizedGoal,
            desiredCount = CommandTextSignals.explicitDesiredCount(userText)
                ?: normalizedGoal.primaryTracks.takeIf { it.isNotEmpty() }?.size
                ?: action.desiredCount,
        )
    }

    private fun forcedPlayAction(plan: MusicTurnPlan, understood: MusicUnderstanding.Result): PlannedAction? {
        if (understood.wantsStyleExplanation) return null
        val scoped = CommandTextSignals.playlistScopedRequest(plan.userText)
        if (scoped != null) {
            val desired = CommandTextSignals.explicitDesiredCount(plan.userText)
            if (scoped.target == null && scoped.primaryArtists.isEmpty()) {
                return PlannedAction.PlayPlaylist(
                    actionId = "a1",
                    name = scoped.playlistName,
                    tracks = emptyList(),
                )
            }
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = if (scoped.target != null) PlayMode.PlayNow else PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(plan.userText, MusicGoal(
                    primaryArtists = scoped.primaryArtists,
                    artistScope = if (scoped.primaryArtists.isNotEmpty()) CommandTextSignals.artistScope(plan.userText) else MusicGoal().artistScope,
                    playlistName = scoped.playlistName,
                ), understood),
                target = scoped.target?.copy(placement = TrackPlacement.Now),
                desiredCount = desired ?: if (scoped.target != null) 1 else 12,
            )
        }
        val playlistQuery = CommandTextSignals.existingPlaylistQuery(plan.userText)
        if (playlistQuery != null) {
            return PlannedAction.PlayPlaylist(
                actionId = "a1",
                name = playlistQuery,
                tracks = emptyList(),
            )
        }
        if (CommandTextSignals.genericSimilarRequest(plan.userText)) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(plan.userText, MusicGoal(artistScope = ArtistScope.Similar), understood),
                desiredCount = CommandTextSignals.explicitDesiredCount(plan.userText) ?: understood.desiredCount ?: 12,
                similar = true,
            )
        }
        if (CommandTextSignals.genericCatalogRequest(plan.userText)) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(plan.userText, MusicGoal(artistScope = ArtistScope.Focus), understood),
                desiredCount = CommandTextSignals.explicitDesiredCount(plan.userText) ?: understood.desiredCount ?: 12,
            )
        }
        val explicitTracks = CommandTextSignals.explicitTrackList(plan.userText)
        if (explicitTracks.isNotEmpty()) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.ReplaceQueue,
                primaryGoal = normalizeGoal(plan.userText, MusicGoal(primaryTracks = explicitTracks), understood),
                desiredCount = CommandTextSignals.explicitDesiredCount(plan.userText) ?: explicitTracks.size,
            )
        }
        val artistTrack = CommandTextSignals.artistTrackTarget(plan.userText)
        if (artistTrack != null) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.PlayNow,
                target = artistTrack.copy(placement = TrackPlacement.Now),
                desiredCount = 1,
            )
        }
        val connectiveLead = CommandTextSignals.connectiveLeadTrack(plan.userText)
        if (connectiveLead != null) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.PlayNow,
                target = connectiveLead.copy(placement = TrackPlacement.Now),
                desiredCount = 12,
            )
        }
        val insertTarget = CommandTextSignals.insertNextTrack(plan.userText)
        if (insertTarget != null) {
            return PlannedAction.PlayRequest(
                actionId = "a1",
                mode = PlayMode.InsertNext,
                target = insertTarget,
                desiredCount = 1,
                jumpToInserted = !CommandTextSignals.noInterrupt(plan.userText),
            )
        }
        if (!understood.wantsPlayback && !understood.wantsInsertNext && !CommandTextSignals.looksLikeReplaceRequest(plan.userText)) return null
        val goal = normalizeGoal(plan.userText, MusicGoal(), understood)
        return PlannedAction.PlayRequest(
            actionId = "a1",
            mode = if (understood.wantsInsertNext && understood.styleProfile.hasSignal) PlayMode.InsertNext else PlayMode.ReplaceQueue,
            primaryGoal = goal,
            target = null,
            desiredCount = if (understood.wantsInsertNext) 1 else CommandTextSignals.explicitDesiredCount(plan.userText)
                ?: understood.desiredCount
                ?: goal.primaryTracks.takeIf { it.isNotEmpty() }?.size
                ?: 12,
            similar = understood.referenceContext != ReferenceContext.None || understood.styleProfile.hasSignal,
            jumpToInserted = !CommandTextSignals.noInterrupt(plan.userText),
        )
    }

    private fun appendImpliedActions(
        userText: String,
        actions: List<PlannedAction>,
    ): List<PlannedAction> {
        val out = actions.toMutableList()
        val hasPlay = out.any { it is PlannedAction.PlayRequest || it is PlannedAction.PlayTracks || it is PlannedAction.PlayPlaylist }
        if (CommandTextSignals.wantsSkipCurrent(userText) && out.none { it is PlannedAction.SkipCurrent }) {
            out.add(0, PlannedAction.SkipCurrent("a_skip"))
        }
        if (!hasPlay && CommandTextSignals.looksLikeSimilarRequest(userText)) {
            out.add(
                PlannedAction.PlayRequest(
                    actionId = "a${out.size + 1}",
                    mode = PlayMode.ReplaceQueue,
                    primaryGoal = normalizeGoal(userText, MusicGoal(), MusicUnderstanding.analyze(userText)),
                    desiredCount = 12,
                    similar = true,
                ),
            )
        }
        return out
    }

    private fun normalizeGoal(userText: String, current: MusicGoal, understood: MusicUnderstanding.Result): MusicGoal {
        val scoped = CommandTextSignals.playlistScopedRequest(userText)
        val primaryArtistsFromText = scoped?.primaryArtists?.takeIf { it.isNotEmpty() }
            ?: CommandTextSignals.primaryArtistHints(userText)
        val explicitTracks = CommandTextSignals.explicitTrackList(userText)
        val includeRequirement = CommandTextSignals.includedTrackRequirement(userText)
        val closerTitle = CommandTextSignals.closerTrackTitle(userText)
        val excludes = CommandTextSignals.excludeTerms(userText)
        val primaryArtists = mergeDistinct(
            current.primaryArtists.filterNot(CommandTextSignals::isCatalogDescriptor),
            primaryArtistsFromText,
        )
        return current.copy(
            primaryArtists = primaryArtists,
            artistScope = if (primaryArtists.isNotEmpty()) CommandTextSignals.artistScope(userText) else current.artistScope,
            playlistName = scoped?.playlistName ?: current.playlistName,
            primaryTracks = mergeRequirements(current.primaryTracks, explicitTracks),
            mustInclude = mergeRequirements(
                current.mustInclude,
                includeRequirement?.let { listOf(it.copy(placement = TrackPlacement.MustInclude)) }.orEmpty(),
            ),
            closer = closerTitle?.let { title ->
                current.closer
                    ?.takeIf { titleMatches(it.title, title) }
                    ?.copy(placement = TrackPlacement.Closer)
                    ?: TrackRequirement(title = title, placement = TrackPlacement.Closer)
            },
            excludeTerms = mergeDistinct(current.excludeTerms, excludes),
            hardGenres = mergeDistinct(current.hardGenres, understood.styleProfile.genres),
            hardLanguages = mergeDistinct(current.hardLanguages, understood.styleProfile.languages),
            hardVocalTypes = mergeDistinct(current.hardVocalTypes, understood.styleProfile.vocalTypes),
            softMoods = mergeDistinct(current.softMoods, understood.styleProfile.moods),
            softScenes = mergeDistinct(current.softScenes, understood.styleProfile.scenes),
            softTextures = mergeDistinct(current.softTextures, understood.styleProfile.textures),
            softQualityWords = mergeDistinct(current.softQualityWords, understood.styleProfile.qualityWords),
            softEnergy = if (current.softEnergy != "any") current.softEnergy else understood.styleProfile.energy,
            refStyles = mergeDistinct(current.refStyles, understood.styleProfile.refStyles),
            aiMainStyles = mergeDistinct(
                current.aiMainStyles,
                understood.styleProfile.genres + understood.styleProfile.moods + understood.styleProfile.scenes + understood.styleProfile.textures,
            ),
            aiAvoidStyles = mergeDistinct(current.aiAvoidStyles, understood.styleProfile.avoidTags),
            includeArtists = mergeDistinct(current.includeArtists, CommandTextSignals.includedArtistHints(userText)),
            searchSeeds = mergeDistinct(current.searchSeeds, listOf(understood.styleProfile.semanticQuery).filter { it.isNotBlank() }),
            useCurrentStyleAnchor = current.useCurrentStyleAnchor || understood.referenceContext == ReferenceContext.CurrentStyle,
            continuationKey = current.continuationKey.ifBlank { understood.styleProfile.semanticQuery.ifBlank { userText } },
            styleProfile = current.styleProfile.mergedWith(understood.styleProfile),
            referenceContext = if (current.referenceContext != ReferenceContext.None) current.referenceContext else understood.referenceContext,
            continuationPolicy = if (current.continuationPolicy.mode != ContinuationMode.Default) {
                current.continuationPolicy
            } else {
                understood.continuationPolicy
            },
        )
    }

    private fun playlistScopedPlayRequest(
        userText: String,
        action: PlannedAction.PlayRequest,
        scoped: PlaylistScopedRequest,
        noInterrupt: Boolean,
        understood: MusicUnderstanding.Result,
    ): PlannedAction.PlayRequest {
        val primaryArtists = mergeDistinct(action.primaryGoal.primaryArtists, scoped.primaryArtists)
        val desired = CommandTextSignals.explicitDesiredCount(userText)
        val goal = normalizeGoal(userText, action.primaryGoal.copy(
            primaryArtists = primaryArtists,
            artistScope = if (primaryArtists.isNotEmpty()) action.primaryGoal.artistScope else action.primaryGoal.artistScope,
            playlistName = scoped.playlistName,
        ), understood)
        val target = scoped.target
        return when {
            target != null -> action.copy(
                mode = PlayMode.PlayNow,
                primaryGoal = goal,
                target = target.copy(placement = TrackPlacement.Now),
                desiredCount = 1,
                jumpToInserted = true,
            )
            primaryArtists.isNotEmpty() -> action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = goal,
                target = null,
                desiredCount = desired ?: action.desiredCount.coerceAtLeast(12),
            )
            else -> action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = goal,
                target = null,
                desiredCount = desired ?: action.desiredCount.coerceAtLeast(12),
                jumpToInserted = !noInterrupt,
            )
        }
    }

    private fun titleMatches(left: String, right: String): Boolean {
        val leftKey = CommandTextSignals.normalizeForMatch(left)
        val rightKey = CommandTextSignals.normalizeForMatch(right)
        return rightKey.isNotBlank() && (leftKey == rightKey || leftKey.contains(rightKey) || rightKey.contains(leftKey))
    }

    private fun mergeDistinct(first: List<String>, second: List<String>): List<String> {
        val out = ArrayList<String>(first.size + second.size)
        val seen = HashSet<String>()
        for (value in first + second) {
            val trimmed = value.trim()
            if (trimmed.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(trimmed))) {
                out.add(trimmed)
            }
        }
        return out
    }

    private fun mergeRequirements(
        first: List<TrackRequirement>,
        second: List<TrackRequirement>,
    ): List<TrackRequirement> {
        val out = ArrayList<TrackRequirement>(first.size + second.size)
        val seen = HashSet<String>()
        for (requirement in first + second) {
            val key = CommandTextSignals.normalizeForMatch(
                "${requirement.artist.orEmpty()}:${requirement.title}",
            )
            if (requirement.title.isNotBlank() && seen.add(key)) {
                out.add(requirement)
            }
        }
        return out
    }
}
