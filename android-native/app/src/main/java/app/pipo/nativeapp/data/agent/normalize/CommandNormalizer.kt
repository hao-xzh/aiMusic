package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement

class CommandNormalizer {
    fun normalize(plan: MusicTurnPlan): MusicTurnPlan {
        val noInterrupt = CommandTextSignals.noInterrupt(plan.userText)
        val normalizedActions = plan.actions.map { action -> normalizeAction(plan.userText, action, noInterrupt) }
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
                ?: normalizePlayRequest(userText, action, noInterrupt)
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
    ): PlannedAction.PlayRequest {
        CommandTextSignals.playlistScopedRequest(userText)?.let { scoped ->
            return playlistScopedPlayRequest(userText, action, scoped, noInterrupt)
        }

        if (CommandTextSignals.genericSimilarRequest(userText)) {
            return action.copy(
                primaryGoal = if (hasConcreteGoal(action.primaryGoal)) {
                    action.primaryGoal.copy(artistScope = ArtistScope.Similar)
                } else {
                    MusicGoal(artistScope = ArtistScope.Similar)
                },
                desiredCount = CommandTextSignals.explicitDesiredCount(userText) ?: action.desiredCount.coerceAtLeast(12),
                similar = true,
            )
        }
        if (CommandTextSignals.genericCatalogRequest(userText)) {
            if (hasConcreteGoal(action.primaryGoal) || action.target != null) return action
            return action.copy(
                primaryGoal = MusicGoal(artistScope = ArtistScope.Focus),
                target = null,
                desiredCount = CommandTextSignals.explicitDesiredCount(userText) ?: action.desiredCount.coerceAtLeast(12),
                similar = false,
            )
        }

        val explicitTracks = CommandTextSignals.explicitTrackList(userText)
        if (explicitTracks.isNotEmpty()) {
            val desired = CommandTextSignals.explicitDesiredCount(userText) ?: explicitTracks.size
            return action.copy(
                mode = PlayMode.ReplaceQueue,
                primaryGoal = action.primaryGoal.copy(
                    primaryTracks = mergeRequirements(action.primaryGoal.primaryTracks, explicitTracks),
                ),
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
            return if (action.mode == PlayMode.InsertNext && noInterrupt) action.copy(jumpToInserted = false) else action
        }

        val normalizedGoal = normalizeGoal(userText, action.primaryGoal)
        return action.copy(
            primaryGoal = normalizedGoal,
            desiredCount = CommandTextSignals.explicitDesiredCount(userText)
                ?: normalizedGoal.primaryTracks.takeIf { it.isNotEmpty() }?.size
                ?: action.desiredCount,
        )
    }

    private fun appendImpliedActions(
        userText: String,
        actions: List<PlannedAction>,
    ): List<PlannedAction> {
        val out = actions.toMutableList()
        if (CommandTextSignals.wantsSkipCurrent(userText) && out.none { it is PlannedAction.SkipCurrent }) {
            out.add(0, PlannedAction.SkipCurrent("a_skip"))
        }
        return out
    }

    private fun hasConcreteGoal(goal: MusicGoal): Boolean =
        goal.primaryArtists.isNotEmpty() ||
            goal.playlistName.isNotBlank() ||
            goal.primaryTracks.isNotEmpty() ||
            goal.mustInclude.isNotEmpty() ||
            goal.closer != null ||
            goal.excludeTerms.isNotEmpty()

    private fun normalizeGoal(userText: String, current: MusicGoal): MusicGoal {
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
                includeRequirement?.let { listOf(it) }.orEmpty(),
            ),
            closer = closerTitle?.let { title ->
                current.closer
                    ?.takeIf { titleMatches(it.title, title) }
                    ?.copy(placement = TrackPlacement.Closer)
                    ?: TrackRequirement(title = title, placement = TrackPlacement.Closer)
            },
            excludeTerms = mergeDistinct(current.excludeTerms, excludes),
        )
    }

    private fun playlistScopedPlayRequest(
        userText: String,
        action: PlannedAction.PlayRequest,
        scoped: PlaylistScopedRequest,
        noInterrupt: Boolean,
    ): PlannedAction.PlayRequest {
        val primaryArtists = mergeDistinct(action.primaryGoal.primaryArtists, scoped.primaryArtists)
        val desired = CommandTextSignals.explicitDesiredCount(userText)
        val goal = action.primaryGoal.copy(
            primaryArtists = primaryArtists,
            artistScope = if (primaryArtists.isNotEmpty()) action.primaryGoal.artistScope else action.primaryGoal.artistScope,
            playlistName = scoped.playlistName,
        )
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
