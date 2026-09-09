package app.pipo.nativeapp.playback.transition

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.playback.orchestrator.TransitionMode
import app.pipo.nativeapp.playback.orchestrator.TransitionResult

data class TransitionVerification(
    val passed: Boolean,
    val gapOk: Boolean?,
    val driftOk: Boolean?,
    val overlapOk: Boolean?,
    val failureReason: String? = null,
    val missingEvidence: List<String> = emptyList(),
)

class TransitionVerifier(
    private val maxHandoffGapMs: Long = 80L,
    private val maxResumeDriftMs: Long = 120L,
    private val minOverlapMs: Long = 1_350L,
) {
    fun verify(result: TransitionResult): TransitionVerification {
        val gapOk = result.handoffGapMs?.let { it <= maxHandoffGapMs }
        val driftOk = result.resumeDriftMs?.let { it <= maxResumeDriftMs }
        val overlapOk = if (result.mode == TransitionMode.RealtimeCrossfade) {
            result.actualOverlapMs?.let { it >= minOverlapMs }
        } else {
            true
        }
        val missingEvidence = buildList {
            if (gapOk == null) add("handoff_gap_ms")
            if (driftOk == null) add("resume_drift_ms")
            if (overlapOk == null) add("actual_overlap_ms")
        }
        return TransitionVerification(
            passed = result.success && missingEvidence.isEmpty() &&
                gapOk == true && driftOk == true && overlapOk == true,
            gapOk = gapOk,
            driftOk = driftOk,
            overlapOk = overlapOk,
            failureReason = when {
                !result.success -> result.failureReason ?: "transition_failed"
                gapOk == false -> "handoff_gap_over_threshold"
                driftOk == false -> "resume_drift_over_threshold"
                overlapOk == false -> "overlap_under_threshold"
                else -> null
            },
        )
    }
}

object TransitionMetrics {
    fun record(result: TransitionResult, verification: TransitionVerification = TransitionVerifier().verify(result)) {
        DiagnosticsLogStore.record(
            area = "transition",
            event = "transition_summary",
            fields = mapOf(
                "pairKey" to result.pairKey,
                "queueVersion" to result.queueVersion,
                "mode" to result.mode.name,
                "plannedMode" to result.plannedMode?.name,
                "modeSource" to result.modeSource,
                "plannedRisk" to result.plannedRisk?.name,
                "success" to result.success,
                "verified" to verification.passed,
                "gapOk" to verification.gapOk,
                "driftOk" to verification.driftOk,
                "overlapOk" to verification.overlapOk,
                "missingEvidence" to verification.missingEvidence.joinToString(","),
                "completedReason" to result.completedReason,
                "failureReason" to (result.failureReason ?: verification.failureReason),
                "auxReadyDelayMs" to result.auxReadyDelayMs,
                "actualOverlapMs" to result.actualOverlapMs,
                "handoffGapMs" to result.handoffGapMs,
                "resumeDriftMs" to result.resumeDriftMs,
                "actualResumePositionMs" to result.actualResumePositionMs,
            ),
        )
    }
}
