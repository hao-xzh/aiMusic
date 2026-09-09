package app.pipo.nativeapp.playback.orchestrator

import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioFeaturesStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.playback.NextTrackPrewarmer
import app.pipo.nativeapp.playback.NextTrackPrewarmOutcome
import app.pipo.nativeapp.playback.NextTrackPrewarmPriority
import app.pipo.nativeapp.playback.PlaybackCacheKeys
import app.pipo.nativeapp.playback.PlaybackPreparationGate
import app.pipo.nativeapp.playback.PlaybackSessionClock
import app.pipo.nativeapp.playback.PlaybackUrlResolver
import kotlinx.coroutines.withTimeoutOrNull

data class PrepareReport(
    val queueVersion: Long,
    val urlReadyCount: Int,
    val prewarmedCount: Int,
    val featuresReadyCount: Int,
    val transitionPlanCount: Int,
    val elapsedMs: Long,
    val resolvedTracks: List<NativeTrack> = emptyList(),
)

@UnstableApi
internal class TransitionPreparer(
    private val urlResolver: PlaybackUrlResolver,
    private val prewarmer: NextTrackPrewarmer,
    private val featuresStore: AudioFeaturesStore,
    private val repository: PipoRepository,
) {
    suspend fun prepareAhead(plan: CommittedQueuePlan): PrepareReport {
        var ownedPrewarmKey: String? = null
        try {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val resolved = ArrayList<NativeTrack>()
            for (track in plan.tracks.take(RESOLVE_URL_COUNT)) {
                if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) break
                val playable = withTimeoutOrNull(PER_TRACK_TIMEOUT_MS) {
                    urlResolver.resolveSinglePlayable(track)
                }
                if (PlaybackSessionClock.isCurrent(plan.queueVersion) && playable != null) {
                    resolved.add(playable)
                }
            }

            var prewarmed = 0
            for (track in resolved.drop(1).take(PREWARM_COUNT)) {
                if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) break
                ownedPrewarmKey = PlaybackCacheKeys.forTrack(track) ?: track.streamUrl
                val outcome = try {
                    withTimeoutOrNull(PER_TRACK_TIMEOUT_MS) {
                        prewarmer.prewarm(track, NextTrackPrewarmPriority.FAR)
                    } ?: NextTrackPrewarmOutcome.YIELDED
                } finally {
                    // 等待超时也结束本轮 FAR writer；已提升为 NEAR 的同曲任务会被保留。
                    prewarmer.cancel(NextTrackPrewarmPriority.FAR, ownedPrewarmKey)
                    ownedPrewarmKey = null
                }
                when (outcome) {
                    NextTrackPrewarmOutcome.FULL,
                    NextTrackPrewarmOutcome.SEEDED -> prewarmed += 1
                    else -> Unit
                }
            }

            var featuresReady = 0
            for (track in resolved.take(FEATURE_COUNT)) {
                if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) break
                if (featuresStore.get(track.id) != null) {
                    featuresReady += 1
                    continue
                }
                // 特征分析同样会走网络；主曲没有 READY 实播余量时不新起请求。
                if (!PlaybackPreparationGate.canSeedNextTrack()) break
                val neteaseId = track.neteaseId ?: continue
                val features = withTimeoutOrNull(PER_TRACK_TIMEOUT_MS) {
                    runCatching { repository.audioFeatures(neteaseId, track.streamUrl) }.getOrNull()
                }
                if (PlaybackSessionClock.isCurrent(plan.queueVersion) &&
                    PlaybackPreparationGate.canSeedNextTrack() &&
                    features != null
                ) {
                    featuresStore.put(track.id, features)
                    featuresReady += 1
                }
            }

            val report = PrepareReport(
                queueVersion = plan.queueVersion,
                urlReadyCount = resolved.size,
                prewarmedCount = prewarmed,
                featuresReadyCount = featuresReady,
                transitionPlanCount = plan.transitionPlans.take(TRANSITION_PLAN_COUNT).size,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt,
                resolvedTracks = resolved,
            )
            DiagnosticsLogStore.record(
                area = "transition",
                event = "transition_prepare_report",
                fields = mapOf(
                    "queueVersion" to report.queueVersion,
                    "urlReadyCount" to report.urlReadyCount,
                    "prewarmedCount" to report.prewarmedCount,
                    "featuresReadyCount" to report.featuresReadyCount,
                    "transitionPlanCount" to report.transitionPlanCount,
                    "elapsedMs" to report.elapsedMs,
                    "current" to PlaybackSessionClock.isCurrent(plan.queueVersion),
                ),
            )
            return report
        } finally {
            // 旧 plan 只拥有 FAR 级工作，不能取消已经升级的近曲 writer。
            ownedPrewarmKey?.let { prewarmer.cancel(NextTrackPrewarmPriority.FAR, it) }
        }
    }

    private companion object {
        private const val RESOLVE_URL_COUNT = 6
        private const val PREWARM_COUNT = 2
        private const val FEATURE_COUNT = 4
        private const val TRANSITION_PLAN_COUNT = 3
        private const val PER_TRACK_TIMEOUT_MS = 8_000L
    }
}
