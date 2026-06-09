package app.pipo.nativeapp.playback.orchestrator

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioFeaturesStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.SmoothQueue
import app.pipo.nativeapp.data.TransitionScore
import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.CommittedQueueSummary
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.playback.PlaybackSessionClock
import app.pipo.nativeapp.playback.SeamlessRuntimeFlags
import kotlin.math.abs
import kotlin.math.max

data class AgentQueueRequest(
    val requestId: String,
    val sourceUserText: String,
    val operation: QueueOperation,
    val tracks: List<NativeTrack>,
    val continuous: ContinuousQueueSource? = null,
    val jumpToInserted: Boolean = true,
    val desiredCount: Int = tracks.size,
    val mixPolicy: MixPolicy = MixPolicy.fromUserText(sourceUserText),
    val hardConstraints: QueueHardConstraints = QueueHardConstraints.fromUserText(sourceUserText),
    val softPreferences: QueueSoftPreferences = QueueSoftPreferences.fromUserText(sourceUserText),
)

enum class QueueOperation {
    PlayNow,
    InsertNext,
    ReplaceQueue,
    AppendQueue,
    PlaySimilar,
    PreserveCurrentThenReplace,
}

data class MixPolicy(
    val enabled: Boolean = true,
    val mode: MixMode = MixMode.Smart,
    val priority: MixPriority = MixPriority.Normal,
    val transitionFeel: TransitionFeel = TransitionFeel.Natural,
    val allowReorderSoftSlots: Boolean = true,
    val maxReorderDistance: Int = 4,
    val preferCachedNext: Boolean = true,
    val preferAnalyzedFeatures: Boolean = true,
    val allowOnlineBackfill: Boolean = true,
) {
    companion object {
        fun fromUserText(text: String): MixPolicy {
            val key = CommandTextSignals.normalizeCommandText(text)
            if (listOf("不用接歌", "不要接歌", "直接正常播", "正常播").any { it in key }) {
                return MixPolicy(enabled = false, mode = MixMode.Off)
            }
            if (listOf("按我说的顺序", "不要乱排", "别乱排", "保持顺序", "按顺序").any { it in key }) {
                return MixPolicy(
                    mode = MixMode.PreserveOrder,
                    priority = MixPriority.High,
                    allowReorderSoftSlots = false,
                )
            }
            if (listOf("睡前", "别突然大声", "不要突然大声", "别突然炸", "不要突然炸").any { it in key }) {
                return MixPolicy(priority = MixPriority.High, transitionFeel = TransitionFeel.Sleep)
            }
            if (listOf("开车", "车上", "一路听").any { it in key }) {
                return MixPolicy(priority = MixPriority.High, transitionFeel = TransitionFeel.Drive)
            }
            if (listOf("慢慢嗨", "嗨起来", "嗨一点", "放嗨", "燃一点", "高能", "动感", "提神", "派对", "party", "运动", "健身", "跑步").any { it in key }) {
                return MixPolicy(priority = MixPriority.High, transitionFeel = TransitionFeel.Party)
            }
            if (listOf("顺一点", "顺滑", "不要硬切", "别硬切", "像电台", "电台一样", "接得自然").any { it in key }) {
                return MixPolicy(priority = MixPriority.High, transitionFeel = TransitionFeel.Radio)
            }
            return MixPolicy()
        }
    }
}

enum class MixMode {
    Off,
    NativeGaplessOnly,
    Smart,
    CrossfadePreferred,
    PreserveOrder,
}

enum class MixPriority {
    Low,
    Normal,
    High,
    Critical,
}

enum class TransitionFeel {
    Natural,
    Radio,
    DJ,
    Sleep,
    Drive,
    Focus,
    Party,
}

data class QueueHardConstraints(
    val preserveUserOrder: Boolean = false,
    val firstTrack: TrackRequirement? = null,
    val nextTrack: TrackRequirement? = null,
    val endingTrack: TrackRequirement? = null,
    val mustIncludeTracks: List<TrackRequirement> = emptyList(),
    val requiredArtists: List<String> = emptyList(),
    val artistScope: ArtistScope = ArtistScope.Focus,
    val excludedArtists: List<String> = emptyList(),
    val excludedLanguages: List<String> = emptyList(),
    val excludedTrackIds: List<String> = emptyList(),
) {
    companion object {
        fun fromUserText(text: String): QueueHardConstraints {
            val preserve = listOf("按我说的顺序", "不要乱排", "别乱排", "保持顺序", "按顺序")
                .any { it in text } || CommandTextSignals.explicitTrackList(text).isNotEmpty()
            val must = CommandTextSignals.includedTrackRequirement(text)
            val closer = CommandTextSignals.closerTrackTitle(text)
                ?.let { TrackRequirement(title = it, placement = TrackPlacement.Closer) }
            val next = CommandTextSignals.insertNextTrack(text)
            val first = CommandTextSignals.connectiveLeadTrack(text) ?: CommandTextSignals.artistTrackTarget(text)
            val primaryArtists = CommandTextSignals.primaryArtistHints(text)
            return QueueHardConstraints(
                preserveUserOrder = preserve,
                firstTrack = first,
                nextTrack = next,
                endingTrack = closer,
                mustIncludeTracks = listOfNotNull(must),
                requiredArtists = primaryArtists,
                artistScope = CommandTextSignals.artistScope(text),
            )
        }

        fun fromGoal(goal: MusicGoal, text: String): QueueHardConstraints {
            val textConstraints = fromUserText(text)
            val requiredArtists = mergeStrings(
                goal.primaryArtists.ifEmpty { textConstraints.requiredArtists },
            )
            return textConstraints.copy(
                endingTrack = textConstraints.endingTrack ?: goal.closer,
                mustIncludeTracks = mergeRequirements(
                    textConstraints.mustIncludeTracks +
                        goal.primaryTracks +
                        goal.mustInclude +
                        listOfNotNull(goal.closer),
                ),
                requiredArtists = requiredArtists,
                artistScope = if (requiredArtists.isNotEmpty()) goal.artistScope else textConstraints.artistScope,
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
                requirement.title.isNotBlank() && seen.add(key)
            }
        }

        private fun mergeStrings(values: List<String>): List<String> {
            val seen = HashSet<String>()
            return values.map { it.trim() }
                .filter { it.isNotBlank() && seen.add(CommandTextSignals.normalizeForMatch(it)) }
        }
    }
}

data class QueueSoftPreferences(
    val preferredLanguages: List<String> = emptyList(),
    val preferredMoods: List<String> = emptyList(),
    val preferredGenres: List<String> = emptyList(),
    val energyCurve: EnergyCurve = EnergyCurve.Smooth,
    val avoidRecentPlayed: Boolean = true,
    val maxSameArtistInWindow: Int = 2,
    val transitionFriendly: Boolean = true,
) {
    companion object {
        fun fromUserText(text: String): QueueSoftPreferences {
            val energyCurve = when {
                listOf("睡前", "安静", "别突然", "慢慢降", "舒缓", "放松", "低能量").any { it in text } -> EnergyCurve.Sleep
                listOf("慢慢嗨", "嗨起来", "越来越嗨", "嗨一点", "放嗨", "燃一点", "高能", "动感", "提神", "运动", "健身", "跑步").any { it in text } -> EnergyCurve.EnergyUp
                listOf("专注", "上班", "工作", "学习", "写代码", "focus").any { it in text.lowercase() } -> EnergyCurve.Focus
                listOf("派对", "party", "蹦", "炸场").any { it in text.lowercase() } -> EnergyCurve.Party
                else -> EnergyCurve.Smooth
            }
            return QueueSoftPreferences(energyCurve = energyCurve)
        }
    }
}

enum class EnergyCurve {
    Smooth,
    SmoothDown,
    EnergyUp,
    EnergyDown,
    Sleep,
    Focus,
    Party,
}

data class QueueSlot(
    val track: NativeTrack,
    val slotType: SlotType,
    val locked: Boolean,
    val reason: String,
    val source: SlotSource,
    val originalIndex: Int,
)

enum class SlotType {
    FirstRequired,
    NextRequired,
    EndingRequired,
    MustInclude,
    PrimaryGoal,
    SimilarFill,
    TransitionFill,
}

enum class SlotSource {
    UserExplicit,
    AgentPrimaryGoal,
    Recommender,
    SeamlessOptimizer,
    SystemRecovery,
}

data class QueueValidationResult(
    val passed: Boolean,
    val messages: List<String> = emptyList(),
) {
    companion object {
        val Passed = QueueValidationResult(passed = true)
    }
}

data class QueueSmoothnessReport(
    val averageTransitionScore: Double,
    val minimumTransitionScore: Double,
    val weakPairs: List<String>,
    val hardConstraintPassed: Boolean,
    val reordered: Boolean,
)

data class TransitionPlan(
    val pairKey: String,
    val sessionId: String,
    val queueVersion: Long,
    val fromIndex: Int,
    val toIndex: Int,
    val fromTrackId: String,
    val toTrackId: String,
    val mode: TransitionMode,
    val risk: TransitionRisk,
)

enum class TransitionMode {
    NativeGapless,
    RealtimeCrossfade,
    SafeCut,
    NoMix,
}

enum class TransitionRisk {
    Low,
    Medium,
    High,
}

data class TransitionResult(
    val pairKey: String,
    val sessionId: String = "",
    val queueVersion: Long,
    val mode: TransitionMode,
    val success: Boolean,
    val plannedMode: TransitionMode? = null,
    val modeSource: String = "runtime",
    val plannedRisk: TransitionRisk? = null,
    val completedReason: String? = null,
    val failureReason: String? = null,
    val auxReadyDelayMs: Long? = null,
    val actualOverlapMs: Long? = null,
    val handoffGapMs: Long? = null,
    val resumeDriftMs: Long? = null,
    val actualResumePositionMs: Long? = null,
)

data class CommittedQueuePlan(
    val sessionId: String,
    val queueVersion: Long,
    val requestId: String,
    val operation: QueueOperation,
    val sourceUserText: String,
    val slots: List<QueueSlot>,
    val transitionPlans: List<TransitionPlan>,
    val validation: QueueValidationResult,
    val smoothnessReport: QueueSmoothnessReport,
    val mixPolicy: MixPolicy,
    val hardConstraints: QueueHardConstraints,
    val softPreferences: QueueSoftPreferences,
    val continuous: ContinuousQueueSource?,
    val jumpToInserted: Boolean,
) {
    val tracks: List<NativeTrack> get() = slots.map { it.track }

    companion object {
        fun snapshot(
            sessionId: String,
            queueVersion: Long,
            requestId: String,
            operation: QueueOperation,
            sourceUserText: String,
            tracks: List<NativeTrack>,
            mixPolicy: MixPolicy = MixPolicy.fromUserText(sourceUserText),
            continuous: ContinuousQueueSource? = null,
        ): CommittedQueuePlan {
            val slots = tracks.mapIndexed { index, track ->
                QueueSlot(
                    track = track,
                    slotType = if (index == 0) SlotType.FirstRequired else SlotType.SimilarFill,
                    locked = index == 0,
                    reason = if (index == 0) "snapshot_current" else "snapshot_queue",
                    source = SlotSource.SystemRecovery,
                    originalIndex = index,
                )
            }
            val transitionPlans = slots.zipWithNext().mapIndexed { index, (from, to) ->
                TransitionPlan(
                    pairKey = "${from.track.id}->${to.track.id}",
                    sessionId = sessionId,
                    queueVersion = queueVersion,
                    fromIndex = index,
                    toIndex = index + 1,
                    fromTrackId = from.track.id,
                    toTrackId = to.track.id,
                    mode = if (mixPolicy.enabled) TransitionMode.SafeCut else TransitionMode.NoMix,
                    risk = TransitionRisk.Medium,
                )
            }
            return CommittedQueuePlan(
                sessionId = sessionId,
                queueVersion = queueVersion,
                requestId = requestId,
                operation = operation,
                sourceUserText = sourceUserText,
                slots = slots,
                transitionPlans = transitionPlans,
                validation = QueueValidationResult.Passed,
                smoothnessReport = QueueSmoothnessReport(
                    averageTransitionScore = 1.0,
                    minimumTransitionScore = 1.0,
                    weakPairs = emptyList(),
                    hardConstraintPassed = true,
                    reordered = false,
                ),
                mixPolicy = mixPolicy,
                hardConstraints = QueueHardConstraints.fromUserText(sourceUserText),
                softPreferences = QueueSoftPreferences.fromUserText(sourceUserText),
                continuous = continuous,
                jumpToInserted = false,
            )
        }
    }

    fun toSummary(accepted: Boolean): CommittedQueueSummary =
        CommittedQueueSummary(
            requestId = requestId,
            queueVersion = queueVersion,
            operation = operation.name,
            accepted = accepted,
            trackCount = slots.size,
            firstTitle = slots.firstOrNull()?.track?.title.orEmpty(),
            insertedTitle = if (operation == QueueOperation.InsertNext) slots.firstOrNull()?.track?.title.orEmpty() else "",
            mixMode = mixPolicy.mode.name,
            transitionFeel = mixPolicy.transitionFeel.name,
            energyCurve = softPreferences.energyCurve.name,
            smoothnessAvg = smoothnessReport.averageTransitionScore,
            validationPassed = validation.passed,
            reordered = smoothnessReport.reordered,
            warnings = validation.messages,
        )
}

sealed class QueueCommitResult {
    data class Success(val plan: CommittedQueuePlan) : QueueCommitResult()
    data class Rejected(
        val request: AgentQueueRequest,
        val reason: String,
        val messages: List<String> = emptyList(),
    ) : QueueCommitResult()
}

sealed class PlaybackCommand {
    data class CommitQueue(val request: AgentQueueRequest) : PlaybackCommand()
    data class Skip(val reason: String = "user") : PlaybackCommand()
}

interface PlaybackQueueWriter {
    fun replaceQueue(plan: CommittedQueuePlan): Boolean
    fun insertNext(plan: CommittedQueuePlan): Boolean
}

class PlaybackSessionManager(
    private val writer: PlaybackQueueWriter,
) {
    fun commitQueue(plan: CommittedQueuePlan): QueueCommitResult {
        val accepted = when (plan.operation) {
            QueueOperation.InsertNext -> writer.insertNext(plan)
            QueueOperation.PlayNow,
            QueueOperation.ReplaceQueue,
            QueueOperation.PlaySimilar,
            QueueOperation.PreserveCurrentThenReplace,
            QueueOperation.AppendQueue -> writer.replaceQueue(plan)
        }
        DiagnosticsLogStore.record(
            area = "playback_orchestrator",
            event = "queue_commit",
            fields = mapOf(
                "requestId" to plan.requestId,
                "queueVersion" to plan.queueVersion,
                "operation" to plan.operation.name,
                "slotCount" to plan.slots.size,
                "lockedCount" to plan.slots.count { it.locked },
                "mixMode" to plan.mixPolicy.mode.name,
                "transitionFeel" to plan.mixPolicy.transitionFeel.name,
                "energyCurve" to plan.softPreferences.energyCurve.name,
                "smoothnessAvg" to "%.3f".format(plan.smoothnessReport.averageTransitionScore),
                "hardPassed" to plan.validation.passed,
                "accepted" to accepted,
                "reordered" to plan.smoothnessReport.reordered,
            ),
        )
        return if (accepted) {
            CommittedQueuePlanStore.set(plan)
            QueueCommitResult.Success(plan)
        } else {
            QueueCommitResult.Rejected(plan.toRequest(), "player_rejected", plan.validation.messages)
        }
    }

    private fun CommittedQueuePlan.toRequest(): AgentQueueRequest =
        AgentQueueRequest(
            requestId = requestId,
            sourceUserText = sourceUserText,
            operation = operation,
            tracks = tracks,
            continuous = continuous,
            jumpToInserted = jumpToInserted,
            desiredCount = tracks.size,
            mixPolicy = mixPolicy,
            hardConstraints = hardConstraints,
            softPreferences = softPreferences,
        )
}

class PlaybackOrchestrator(
    private val featuresStore: AudioFeaturesStore,
    private val sessionManager: PlaybackSessionManager,
) {
    private val builder = AgentQueueBuilder()
    private val optimizer = SeamlessQueueOptimizer(featuresStore)
    private val validator = QueueValidator()

    fun applyAgentRequest(request: AgentQueueRequest): QueueCommitResult {
        if (request.tracks.isEmpty()) {
            return QueueCommitResult.Rejected(request, "empty_tracks")
        }
        val draft = builder.build(request)
        val optimized = if (SeamlessRuntimeFlags.current.seamlessOptimizerEnabled) {
            optimizer.optimize(draft, request)
        } else {
            optimizer.passthrough(draft)
        }
        var finalOptimized = optimized
        var finalValidation = validator.validate(request, optimized)
        if (!finalValidation.passed) {
            val repaired = repairValidationFailure(request, optimized, finalValidation)
            if (repaired != null) {
                val repairedValidation = validator.validate(request, repaired)
                DiagnosticsLogStore.record(
                    area = "playback_orchestrator",
                    event = "queue_validation_auto_repair",
                    fields = mapOf(
                        "requestId" to request.requestId,
                        "operation" to request.operation.name,
                        "before" to finalValidation.messages.joinToString("|").take(180),
                        "after" to repairedValidation.messages.joinToString("|").take(180),
                        "passed" to repairedValidation.passed,
                    ),
                )
                if (repairedValidation.passed) {
                    finalOptimized = repaired
                    finalValidation = repairedValidation
                } else {
                    return QueueCommitResult.Rejected(request, "queue_validation_failed", repairedValidation.messages)
                }
            } else {
                return QueueCommitResult.Rejected(request, "queue_validation_failed", finalValidation.messages)
            }
        }
        val queueVersion = PlaybackSessionClock.bump("agent_${request.operation.name.lowercase()}")
        val transitionPlans = buildTransitionPlans(finalOptimized, queueVersion, request.mixPolicy)
        val plan = CommittedQueuePlan(
            sessionId = PlaybackSessionClock.sessionId,
            queueVersion = queueVersion,
            requestId = request.requestId,
            operation = request.operation,
            sourceUserText = request.sourceUserText,
            slots = finalOptimized.slots,
            transitionPlans = transitionPlans,
            validation = finalValidation,
            smoothnessReport = finalOptimized.report,
            mixPolicy = request.mixPolicy,
            hardConstraints = request.hardConstraints,
            softPreferences = request.softPreferences,
            continuous = request.continuous,
            jumpToInserted = request.jumpToInserted,
        )
        return sessionManager.commitQueue(plan)
    }

    private fun repairValidationFailure(
        request: AgentQueueRequest,
        optimized: OptimizedQueuePlan,
        validation: QueueValidationResult,
    ): OptimizedQueuePlan? {
        if (optimized.slots.isEmpty()) return null
        val slots = optimized.slots.toMutableList()
        var changed = false

        request.hardConstraints.firstTrack?.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (validation.messages.any { it.startsWith("第一首没有满足") || it == "第一首被无缝优化移动" }) {
                changed = moveMatchingSlot(
                    slots = slots,
                    title = title,
                    targetIndex = 0,
                    slotType = SlotType.FirstRequired,
                    reason = "repair_first_track",
                ) || changed
            }
        }

        request.hardConstraints.nextTrack?.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (request.operation == QueueOperation.InsertNext && validation.messages.any { it.startsWith("下一首没有满足") }) {
                changed = moveMatchingSlot(
                    slots = slots,
                    title = title,
                    targetIndex = 0,
                    slotType = SlotType.NextRequired,
                    reason = "repair_next_track",
                ) || changed
            }
        }

        request.hardConstraints.endingTrack?.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (validation.messages.any { it.startsWith("最后一首不是") }) {
                changed = moveMatchingSlot(
                    slots = slots,
                    title = title,
                    targetIndex = slots.lastIndex,
                    slotType = SlotType.EndingRequired,
                    reason = "repair_ending_track",
                ) || changed
            }
        }

        return if (changed) {
            optimized.copy(
                slots = slots,
                report = optimized.report.copy(reordered = true, hardConstraintPassed = true),
            )
        } else {
            null
        }
    }

    private fun moveMatchingSlot(
        slots: MutableList<QueueSlot>,
        title: String,
        targetIndex: Int,
        slotType: SlotType,
        reason: String,
    ): Boolean {
        val currentIndex = slots.indexOfFirst { titleMatches(it.track.title, title) }
        if (currentIndex < 0) return false
        val currentSlot = slots.removeAt(currentIndex)
        val insertion = targetIndex.coerceIn(0, slots.size)
        slots.add(
            insertion,
            currentSlot.copy(
                slotType = slotType,
                locked = true,
                reason = reason,
                source = SlotSource.UserExplicit,
            ),
        )
        return currentIndex != insertion
    }

    private fun titleMatches(left: String, right: String): Boolean {
        val leftKey = CommandTextSignals.normalizeForMatch(left)
        val rightKey = CommandTextSignals.normalizeForMatch(right)
        return rightKey.isNotBlank() && (leftKey == rightKey || leftKey.contains(rightKey) || rightKey.contains(leftKey))
    }

    private fun buildTransitionPlans(
        optimized: OptimizedQueuePlan,
        queueVersion: Long,
        mixPolicy: MixPolicy,
    ): List<TransitionPlan> {
        if (!mixPolicy.enabled || mixPolicy.mode == MixMode.Off) return emptyList()
        return optimized.slots.zipWithNext().mapIndexed { index, (from, to) ->
            val score = pairScore(from.track, to.track)
            val risk = when {
                score >= 0.78 -> TransitionRisk.Low
                score >= 0.52 -> TransitionRisk.Medium
                else -> TransitionRisk.High
            }
            TransitionPlan(
                pairKey = "${from.track.id}->${to.track.id}",
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = queueVersion,
                fromIndex = index,
                toIndex = index + 1,
                fromTrackId = from.track.id,
                toTrackId = to.track.id,
                mode = when {
                    risk == TransitionRisk.Low -> TransitionMode.RealtimeCrossfade
                    risk == TransitionRisk.Medium -> TransitionMode.SafeCut
                    else -> TransitionMode.NoMix
                },
                risk = risk,
            )
        }
    }

    private fun pairScore(from: NativeTrack, to: NativeTrack): Double {
        val fit = TransitionScore.fitScore(
            TransitionScore.Scored(from, featuresStore.get(from.id)),
            TransitionScore.Scored(to, featuresStore.get(to.id)),
        )
        return fit.score
    }
}

private class AgentQueueBuilder {
    fun build(request: AgentQueueRequest): DraftQueuePlan {
        val preserveOrder = request.mixPolicy.mode == MixMode.PreserveOrder ||
            !request.mixPolicy.allowReorderSoftSlots ||
            request.hardConstraints.preserveUserOrder
        val includeTitles = request.hardConstraints.mustIncludeTracks.map { it.title }
        val closerTitle = request.hardConstraints.endingTrack?.title
        val slots = request.tracks.mapIndexed { index, track ->
            val isFirst = index == 0 &&
                request.operation in setOf(QueueOperation.PlayNow, QueueOperation.ReplaceQueue, QueueOperation.PlaySimilar)
            val isInsert = request.operation == QueueOperation.InsertNext && index == 0
            val isCloser = !closerTitle.isNullOrBlank() && track.title.contains(closerTitle, ignoreCase = true)
            val isMustInclude = includeTitles.any { include ->
                include.isNotBlank() && track.title.contains(include, ignoreCase = true)
            }
            val locked = preserveOrder || isFirst || isInsert || isCloser || isMustInclude
            QueueSlot(
                track = track,
                slotType = when {
                    isInsert -> SlotType.NextRequired
                    isFirst -> SlotType.FirstRequired
                    isCloser -> SlotType.EndingRequired
                    isMustInclude -> SlotType.MustInclude
                    index < max(3, request.desiredCount / 3) -> SlotType.PrimaryGoal
                    else -> SlotType.SimilarFill
                },
                locked = locked,
                reason = when {
                    preserveOrder -> "preserve_user_order"
                    isInsert -> "insert_next"
                    isFirst -> "first_track"
                    isCloser -> "ending_track"
                    isMustInclude -> "must_include"
                    else -> "soft_slot"
                },
                source = when {
                    locked || isMustInclude -> SlotSource.UserExplicit
                    else -> SlotSource.AgentPrimaryGoal
                },
                originalIndex = index,
            )
        }
        return DraftQueuePlan(slots)
    }
}

private data class DraftQueuePlan(val slots: List<QueueSlot>)

private data class OptimizedQueuePlan(
    val slots: List<QueueSlot>,
    val report: QueueSmoothnessReport,
)

private class SeamlessQueueOptimizer(
    private val featuresStore: AudioFeaturesStore,
) {
    fun passthrough(draft: DraftQueuePlan): OptimizedQueuePlan {
        return OptimizedQueuePlan(draft.slots, report(draft.slots, draft.slots, hardPassed = true))
    }

    fun optimize(draft: DraftQueuePlan, request: AgentQueueRequest): OptimizedQueuePlan {
        if (!request.mixPolicy.enabled ||
            !request.mixPolicy.allowReorderSoftSlots ||
            request.mixPolicy.mode == MixMode.Off ||
            draft.slots.size <= 2
        ) {
            return passthrough(draft)
        }

        val optimized = ArrayList<QueueSlot>()
        var cursor = 0
        while (cursor < draft.slots.size) {
            val locked = draft.slots[cursor]
            if (locked.locked) {
                optimized.add(locked)
                cursor += 1
                continue
            }
            val start = cursor
            while (cursor < draft.slots.size && !draft.slots[cursor].locked) cursor += 1
            val segment = draft.slots.subList(start, cursor)
            optimized.addAll(optimizeSegment(segment, request.mixPolicy.maxReorderDistance))
        }
        return OptimizedQueuePlan(optimized, report(draft.slots, optimized, hardPassed = true))
    }

    private fun optimizeSegment(segment: List<QueueSlot>, maxReorderDistance: Int): List<QueueSlot> {
        if (segment.size <= 2) return segment
        val smoothedTracks = SmoothQueue.smooth(
            tracks = segment.map { it.track },
            featuresStore = featuresStore,
            startTrackId = segment.first().track.id,
            mode = SmoothQueue.Mode.Discovery,
            force = true,
        )
        val byId = segment.associateBy { it.track.id }
        val smoothed = smoothedTracks.mapNotNull { byId[it.id] }
        if (smoothed.size != segment.size) return segment
        val improved = localSwapImprove(smoothed, maxIterations = 40)
        val originalIndex = segment.mapIndexed { index, slot -> slot.track.id to index }.toMap()
        val movedTooFar = improved.withIndex().any { (newIndex, slot) ->
            kotlin.math.abs((originalIndex[slot.track.id] ?: newIndex) - newIndex) > maxReorderDistance
        }
        return if (movedTooFar) segment else improved.map { it.copy(source = SlotSource.SeamlessOptimizer) }
    }

    private fun localSwapImprove(slots: List<QueueSlot>, maxIterations: Int): List<QueueSlot> {
        if (slots.size <= 2) return slots
        var best = slots
        var bestScore = segmentScore(best)
        repeat(maxIterations) {
            var improved = false
            for (i in best.indices) {
                for (j in i + 1 until best.size) {
                    val candidate = best.toMutableList().also { java.util.Collections.swap(it, i, j) }
                    val score = segmentScore(candidate)
                    if (score > bestScore + 0.001) {
                        best = candidate
                        bestScore = score
                        improved = true
                    }
                }
            }
            if (!improved) return best
        }
        return best
    }

    private fun segmentScore(slots: List<QueueSlot>): Double {
        if (slots.size <= 1) return 1.0
        return slots.zipWithNext().sumOf { (from, to) ->
            TransitionScore.fitScore(
                TransitionScore.Scored(from.track, featuresStore.get(from.track.id)),
                TransitionScore.Scored(to.track, featuresStore.get(to.track.id)),
            ).score
        } / (slots.size - 1).toDouble()
    }

    private fun report(
        original: List<QueueSlot>,
        slots: List<QueueSlot>,
        hardPassed: Boolean,
    ): QueueSmoothnessReport {
        val scores = slots.zipWithNext().map { (from, to) ->
            TransitionScore.fitScore(
                TransitionScore.Scored(from.track, featuresStore.get(from.track.id)),
                TransitionScore.Scored(to.track, featuresStore.get(to.track.id)),
            ).score
        }
        val reordered = original.map { it.track.id } != slots.map { it.track.id }
        return QueueSmoothnessReport(
            averageTransitionScore = scores.takeIf { it.isNotEmpty() }?.average() ?: 1.0,
            minimumTransitionScore = scores.minOrNull() ?: 1.0,
            weakPairs = slots.zipWithNext()
                .filterIndexed { index, _ -> scores.getOrNull(index)?.let { it < 0.45 } == true }
                .map { (a, b) -> "${a.track.title}->${b.track.title}" }
                .take(4),
            hardConstraintPassed = hardPassed,
            reordered = reordered,
        )
    }
}

private class QueueValidator {
    fun validate(request: AgentQueueRequest, plan: OptimizedQueuePlan): QueueValidationResult {
        val messages = mutableListOf<String>()
        val tracks = plan.slots.map { it.track }
        val includeTitles = request.hardConstraints.mustIncludeTracks.map { it.title }.filter { it.isNotBlank() }
        val closerTitle = request.hardConstraints.endingTrack?.title
        val requiredArtists = request.hardConstraints.requiredArtists.filter { it.isNotBlank() }
        val excludedArtists = request.hardConstraints.excludedArtists.filter { it.isNotBlank() }
        val excludedTrackIds = request.hardConstraints.excludedTrackIds.toSet()
        if (request.operation == QueueOperation.InsertNext && tracks.firstOrNull() == null) {
            messages.add("下一首插入目标为空")
        }
        if (request.operation in setOf(QueueOperation.PlayNow, QueueOperation.ReplaceQueue) &&
            request.tracks.firstOrNull()?.id != tracks.firstOrNull()?.id
        ) {
            messages.add("第一首被无缝优化移动")
        }
        request.hardConstraints.firstTrack?.title?.takeIf { it.isNotBlank() }?.let { firstTitle ->
            if (!tracks.firstOrNull()?.title.orEmpty().contains(firstTitle, ignoreCase = true) &&
                request.operation in setOf(QueueOperation.PlayNow, QueueOperation.ReplaceQueue)
            ) {
                messages.add("第一首没有满足用户点名的《$firstTitle》")
            }
        }
        request.hardConstraints.nextTrack?.title?.takeIf { it.isNotBlank() }?.let { nextTitle ->
            if (request.operation == QueueOperation.InsertNext &&
                !tracks.firstOrNull()?.title.orEmpty().contains(nextTitle, ignoreCase = true)
            ) {
                messages.add("下一首没有满足用户点名的《$nextTitle》")
            }
        }
        includeTitles.forEach { includeTitle ->
            if (tracks.none { it.title.contains(includeTitle, ignoreCase = true) }) {
                messages.add("缺少用户要求包含的《$includeTitle》")
            }
        }
        if (!closerTitle.isNullOrBlank() && !tracks.lastOrNull()?.title.orEmpty().contains(closerTitle, ignoreCase = true)) {
            messages.add("最后一首不是用户要求的《$closerTitle》")
        }
        if (request.operation == QueueOperation.ReplaceQueue && requiredArtists.isNotEmpty()) {
            val exceptionTitles = (
                request.hardConstraints.mustIncludeTracks +
                    listOfNotNull(request.hardConstraints.endingTrack)
            ).map { CommandTextSignals.normalizeForMatch(it.title) }.toSet()
            validateArtistScope(
                request = request,
                tracks = tracks,
                requiredArtists = requiredArtists,
                artistScope = request.hardConstraints.artistScope,
                exceptionTitles = exceptionTitles,
                messages = messages,
            )
        }
        excludedArtists.forEach { artist ->
            if (tracks.any { artistMatches(it.artist, artist) }) {
                messages.add("队列包含了用户排除的艺人「$artist」")
            }
        }
        if (excludedTrackIds.isNotEmpty() && tracks.any { it.id in excludedTrackIds }) {
            messages.add("队列包含了用户排除的歌曲")
        }
        val overMoved = plan.slots.withIndex().firstOrNull { (finalIndex, slot) ->
            !slot.locked && abs(slot.originalIndex - finalIndex) > request.mixPolicy.maxReorderDistance
        }?.value
        if (overMoved != null) {
            messages.add("无缝优化移动《${overMoved.track.title}》超过允许距离")
        }
        if (request.hardConstraints.preserveUserOrder &&
            SeamlessRuntimeFlags.current.preserveOrderStrictEnabled
        ) {
            val originalIds = request.tracks.map { it.id }
            val finalIds = tracks.map { it.id }
            if (originalIds != finalIds) {
                messages.add("用户要求保序，但队列顺序被改变")
            }
        }
        return QueueValidationResult(
            passed = messages.none(::isBlockingMessage),
            messages = messages,
        )
    }

    private fun validateArtistScope(
        request: AgentQueueRequest,
        tracks: List<NativeTrack>,
        requiredArtists: List<String>,
        artistScope: ArtistScope,
        exceptionTitles: Set<String>,
        messages: MutableList<String>,
    ): Boolean {
        val artistKeys = requiredArtists
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
        if (artistKeys.isEmpty() || tracks.isEmpty()) return true
        val scopedTracks = tracks.filterNot { track ->
            val title = CommandTextSignals.normalizeForMatch(track.title)
            exceptionTitles.any { it.isNotBlank() && (title == it || title.contains(it) || it.contains(title)) }
        }
        var illegalCount = 0
        val passed = when (artistScope) {
            ArtistScope.Strict -> {
                val illegal = scopedTracks.filterNot { artistMatchesAny(it.artist, artistKeys) }
                illegalCount = illegal.size
                if (illegal.isNotEmpty()) {
                    messages.add(
                        "strict_artist_scope_violated:" +
                            illegal.take(3).joinToString("|") { "${it.title}-${it.artist}" },
                    )
                    false
                } else {
                    true
                }
            }
            ArtistScope.Focus -> {
                val hit = scopedTracks.count { artistMatchesAny(it.artist, artistKeys) }
                val ratio = hit.toDouble() / scopedTracks.size.coerceAtLeast(1).toDouble()
                illegalCount = (scopedTracks.size - hit).coerceAtLeast(0)
                if (ratio < 0.7) {
                    messages.add("focus_artist_ratio_low:$hit/${scopedTracks.size}")
                }
                true
            }
            ArtistScope.Similar -> true
        }
        DiagnosticsLogStore.record(
            area = "playback_orchestrator",
            event = "queue_validation_artist_scope",
            fields = mapOf(
                "requestId" to request.requestId,
                "artistScope" to artistScope.name,
                "requiredArtists" to requiredArtists.joinToString(","),
                "trackCount" to tracks.size,
                "checkedCount" to scopedTracks.size,
                "illegalCount" to illegalCount,
                "passed" to passed,
            ),
        )
        return passed
    }

    private fun isBlockingMessage(message: String): Boolean =
        when {
            message.startsWith("focus_artist_ratio_low") -> false
            message.startsWith("无缝优化移动") -> false
            else -> true
        }

    private fun artistMatches(actualRaw: String, expectedRaw: String): Boolean {
        val expected = CommandTextSignals.normalizeForMatch(expectedRaw)
        if (expected.isBlank()) return false
        return actualRaw.split("/", "&", ",", "、")
            .map { CommandTextSignals.normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .any { actual -> actual == expected || actual.contains(expected) || expected.contains(actual) }
    }

    private fun artistMatchesAny(actualRaw: String, artistKeys: List<String>): Boolean =
        actualRaw.split("/", "&", ",", "、")
            .map { CommandTextSignals.normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .any { actual ->
                artistKeys.any { expected ->
                    actual == expected || actual.contains(expected) || expected.contains(actual)
                }
            }
}
