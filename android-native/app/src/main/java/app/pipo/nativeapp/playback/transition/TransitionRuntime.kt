package app.pipo.nativeapp.playback.transition

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.playback.orchestrator.TransitionMode
import app.pipo.nativeapp.playback.orchestrator.TransitionResult

data class TransitionVerification(
    val passed: Boolean,
    val gapOk: Boolean,
    val driftOk: Boolean,
    val overlapOk: Boolean,
    val failureReason: String? = null,
)

class TransitionVerifier(
    private val maxHandoffGapMs: Long = 80L,
    private val maxResumeDriftMs: Long = 120L,
    private val minOverlapMs: Long = 1_350L,
) {
    fun verify(result: TransitionResult): TransitionVerification {
        if (!result.success) {
            return TransitionVerification(
                passed = false,
                gapOk = false,
                driftOk = false,
                overlapOk = false,
                failureReason = result.failureReason ?: "transition_failed",
            )
        }
        val gapOk = result.handoffGapMs?.let { it <= maxHandoffGapMs } ?: true
        val driftOk = result.resumeDriftMs?.let { it <= maxResumeDriftMs } ?: true
        val overlapOk = if (result.mode == TransitionMode.RealtimeCrossfade) {
            result.actualOverlapMs?.let { it >= minOverlapMs } ?: false
        } else {
            true
        }
        return TransitionVerification(
            passed = gapOk && driftOk && overlapOk,
            gapOk = gapOk,
            driftOk = driftOk,
            overlapOk = overlapOk,
            failureReason = when {
                !gapOk -> "handoff_gap_over_threshold"
                !driftOk -> "resume_drift_over_threshold"
                !overlapOk -> "overlap_under_threshold"
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
