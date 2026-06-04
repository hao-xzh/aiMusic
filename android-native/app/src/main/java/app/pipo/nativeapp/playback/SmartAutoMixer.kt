package app.pipo.nativeapp.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioFeatures
import app.pipo.nativeapp.data.AudioFeaturesStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.TransitionScore
import app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlanStore
import app.pipo.nativeapp.playback.orchestrator.TransitionMode
import app.pipo.nativeapp.playback.orchestrator.TransitionResult
import app.pipo.nativeapp.playback.orchestrator.TransitionRisk
import app.pipo.nativeapp.playback.transition.TransitionMetrics
import app.pipo.nativeapp.runtime.Amp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Service-side smart AutoMix controller.
 *
 * The MediaSession player remains the single playback authority. This keeps transitions free of
 * duplicated next-track intros while we use analysis cues to pick safer cut/fade points.
 */
@UnstableApi
internal class SmartAutoMixer(
    private val mainPlayer: ExoPlayer,
    private val featuresStore: AudioFeaturesStore,
    private val crossfadeController: CrossfadeController? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tickPosted = false
    private var armed: ArmedMix? = null
    private var active: ActiveMix? = null
    private var lastCompletedKey: String? = null
    private var lastCompletedQueueVersion: Long = 0L
    private var lastCompletedAtMs: Long = 0L
    private var lastArmDiagAtMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickPosted = false
            runCatching { tick() }
                .onFailure {
                    DiagnosticsLogStore.record(
                        area = "automix",
                        event = "tick_failed",
                        fields = mapOf(
                            "errorType" to it::class.java.simpleName,
                            "message" to it.message,
                        ),
                    )
                    cancel("tick-error", keepMainVolume = true)
                }
            if (shouldKeepTicking()) scheduleTick()
        }
    }

    fun onMainPlayerEvent() {
        val running = active
        if (running != null) {
            if (!mainPlayer.playWhenReady) {
                cancel("main-not-playing", keepMainVolume = true)
            } else if (mainPlayer.playbackState == Player.STATE_IDLE) {
                cancel("main-not-playing", keepMainVolume = true)
            } else {
                updateActiveMix(running)
            }
            if (shouldKeepTicking()) scheduleTick()
            return
        }
        if (shouldKeepTicking()) scheduleTick()
        if (!mainPlayer.playWhenReady || mainPlayer.playbackState == Player.STATE_IDLE) {
            cancel("main-not-playing", keepMainVolume = true)
        }
    }

    fun onMainPlayerError() {
        cancel("main-error", keepMainVolume = true)
    }

    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val running = active
        if (running != null) {
            if (mediaItem?.mediaId == running.plan.nextId) {
                updateActiveMix(running)
                return
            }
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                cancel("manual-transition", keepMainVolume = true)
            }
            return
        }
        val waiting = armed
        if (waiting != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            cancel("manual-transition", keepMainVolume = true)
        }
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        tickPosted = false
        cancel("release", keepMainVolume = false)
    }

    private fun shouldKeepTicking(): Boolean {
        return armed != null ||
            active != null ||
            (mainPlayer.playWhenReady && mainPlayer.mediaItemCount > 1)
    }

    private fun scheduleTick() {
        if (tickPosted) return
        tickPosted = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun tick() {
        active?.let {
            updateActiveMix(it)
            return
        }
        armed?.let {
            updateArmedMix(it)
            return
        }
        maybeArmNextMix()
    }

    private fun maybeArmNextMix() {
        if (!mainPlayer.playWhenReady || mainPlayer.playbackState == Player.STATE_IDLE) return
        val plan = buildPlan()
        val remainingMs = remainingMs()
        // 诊断：限频记录 arming 视角，定位"为什么从不 arm"（buildPlan 空 / 时长未知 / 一直太早）。
        maybeLogArmDiag(plan, remainingMs)
        if (plan == null) return
        if (remainingMs == null) return
        val prepareLeadMs = prepareLeadMs(plan)
        if (remainingMs > plan.mixMs + prepareLeadMs) return
        if (remainingMs < MIN_REMAINING_TO_ARM_MS) return
        if (plan.queueVersion == lastCompletedQueueVersion &&
            plan.key == lastCompletedKey &&
            SystemClock.elapsedRealtime() - lastCompletedAtMs < COMPLETED_PAIR_COOLDOWN_MS
        ) return

        val waiting = ArmedMix(plan = plan)
        armed = waiting
        logMixEvent(
            "armed",
            plan,
            mapOf(
                "mixMs" to plan.mixMs,
                "reason" to plan.reason,
                "remainingMs" to remainingMs,
                "prepareLeadMs" to prepareLeadMs,
                "transitionMode" to plan.targetTransitionMode.name,
                "transitionModeSource" to plan.transitionModeSource,
                "realtimeCrossfadeEligible" to plan.realtimeCrossfadeEligible,
                "realtimeCrossfadeMs" to plan.realtimeCrossfadeMs,
                "nextStartPositionMs" to plan.nextStartPositionMs,
                "tempoLockSpeed" to "%.4f".format(plan.tempoLockSpeed),
                "tempoLockLeadMs" to plan.tempoLockLeadMs,
                "nextTempoScale" to "%.4f".format(plan.nextTempoScale),
            ),
        )
    }

    /**
     * 接歌为什么从不 arm 的探针 —— 限频(≥2s)记录 arming 视角。一次复现就能区分：
     * hasPlan=false → buildPlan 被某条件挡(缺特征/同专辑 gapless/曲太短)；
     * durationKnown=false → player 不知道时长(流没报 duration)，remainingMs 永远算不出；
     * remainingMs 一直 > armThresholdMs → 时长异常(位置/时长错)，永远进不了 arming 窗口。
     * 完全没有 arm_diag → tick 循环根本没在跑。
     */
    private fun maybeLogArmDiag(plan: AutoMixPlan?, remainingMs: Long?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastArmDiagAtMs < ARM_DIAG_INTERVAL_MS) return
        lastArmDiagAtMs = now
        val durationMs = mainPlayer.duration
        DiagnosticsLogStore.record(
            area = "automix",
            event = "arm_diag",
            fields = mapOf(
                "hasPlan" to (plan != null),
                "remainingMs" to remainingMs,
                "durationMs" to durationMs,
                "durationKnown" to (durationMs > 0 && durationMs != C.TIME_UNSET),
                "positionMs" to mainPlayer.currentPosition.coerceAtLeast(0L),
                "mediaItemCount" to mainPlayer.mediaItemCount,
                "currentIndex" to mainPlayer.currentMediaItemIndex,
                "playWhenReady" to mainPlayer.playWhenReady,
                "state" to playbackStateName(mainPlayer.playbackState),
                "mixMs" to plan?.mixMs,
                "armThresholdMs" to plan?.let { it.mixMs + prepareLeadMs(it) },
                "planPolicy" to plan?.policy,
            ),
        )
    }

    private fun updateArmedMix(waiting: ArmedMix) {
        val plan = waiting.plan
        if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to plan.key,
                    "planQueueVersion" to plan.queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "armed",
                ),
            )
            cancel("stale-transition-plan", keepMainVolume = true)
            return
        }
        val currentId = mainPlayer.currentMediaItem?.mediaId
        if (currentId != plan.currentId) {
            cancel("armed-current-changed", keepMainVolume = true)
            return
        }
        if (mainPlayer.playbackState != Player.STATE_READY) return

        val remainingMs = remainingMs() ?: run {
            cancel("armed-no-duration", keepMainVolume = true)
            return
        }
        if (remainingMs < MIN_REMAINING_TO_START_MS) {
            cancel("mix-too-late", keepMainVolume = true)
            return
        }
        maybeApplyTempoLock(waiting, remainingMs)

        if (plan.realtimeCrossfadeEligible &&
            remainingMs <= plan.realtimeCrossfadeMs + REALTIME_CROSSFADE_START_TOLERANCE_MS
        ) {
            if (startRealtimeCrossfade(waiting, remainingMs)) {
                return
            }
        }

        if (remainingMs > plan.mixMs) return
        if (!tailSignalAllowsMix(waiting, remainingMs)) return
        startMainOnlyNext(waiting, remainingMs)
    }

    /**
     * 用实时双 player crossfade 接歌(真·叠加,等功率)。启动后把控制权交给 CrossfadeController:它自己监听主
     * player 的接管/打断,SmartAutoMixer 不再干预本对(armed=null + 进 cooldown)。
     * @return true 表示已交给实时 crossfade;false 则调用方继续走 main-only。
     */
    private fun startRealtimeCrossfade(waiting: ArmedMix, remainingMs: Long): Boolean {
        val plan = waiting.plan
        if (plan.targetTransitionMode != TransitionMode.RealtimeCrossfade) return false
        if (!REALTIME_CROSSFADE_ENABLED || !SeamlessRuntimeFlags.current.realtimeCrossfadeV2Enabled) return false
        val controller = crossfadeController ?: return false
        if (controller.isRunning) return false
        if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) {
            cancel("stale-transition-plan", keepMainVolume = true)
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to plan.key,
                    "planQueueVersion" to plan.queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "realtime_start",
                ),
            )
            return false
        }
        if (remainingMs < MIN_REALTIME_CROSSFADE_MS) return false
        // 主控必须正播在当前曲,否则接续点/位置对不上。
        if (mainPlayer.currentMediaItem?.mediaId != plan.currentId) return false
        if (!mainPlayer.playWhenReady || mainPlayer.playbackState != Player.STATE_READY) return false
        val nextGain = PlaybackGain.linearForRms(featuresStore.get(plan.nextId)?.rmsDb)
        controller.start(
            nextMediaItem = plan.nextMediaItem,
            nextIndex = plan.nextIndex,
            nextStartMs = plan.nextStartPositionMs,
            crossfadeMs = remainingMs,
            beatmatchSpeed = plan.nextTempoScale,
            nextGainLinear = nextGain,
            queueVersion = plan.queueVersion,
            pairKey = plan.key,
        )
        armed = null
        logMixEvent(
            "realtime_crossfade_started",
            plan,
            mapOf(
                "remainingMs" to remainingMs,
                "nextStartPositionMs" to plan.nextStartPositionMs,
                "beatmatchSpeed" to "%.4f".format(plan.nextTempoScale),
                "nextGain" to "%.3f".format(nextGain),
            ),
        )
        return true
    }

    fun onRealtimeCrossfadeResult(result: TransitionResult) {
        TransitionMetrics.record(result)
        if (!result.success) return
        lastCompletedKey = result.pairKey
        lastCompletedQueueVersion = result.queueVersion
        lastCompletedAtMs = SystemClock.elapsedRealtime()
    }

    private fun startMainOnlyNext(waiting: ArmedMix, remainingMs: Long) {
        val plan = waiting.plan
        val outgoingPositionMs = mainPlayer.currentPosition.coerceAtLeast(0L)
        val startedAtMs = SystemClock.elapsedRealtime()

        active = ActiveMix(
            plan = plan,
            startedAtMs = startedAtMs,
            tempoLockApplied = waiting.tempoLockApplied,
        )
        armed = null

        logMixEvent(
            "started",
            plan,
            mapOf(
                "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                "remainingMs" to remainingMs,
                "tailDipTicks" to waiting.tailDipTicks,
                "tempoLockApplied" to waiting.tempoLockApplied,
                "tempoLockSpeed" to "%.4f".format(plan.tempoLockSpeed),
                "outgoingPositionMs" to outgoingPositionMs,
                "fadeOutMs" to plan.fadeOutMs,
                "fadeInMs" to plan.fadeInMs,
                "mainTargetIndex" to plan.nextIndex,
                "mainTargetPositionMs" to plan.nextStartPositionMs,
                "mainState" to playbackStateName(mainPlayer.playbackState),
                "mainMediaId" to mainPlayer.currentMediaItem?.mediaId,
            ),
        )
        updateActiveMix(active ?: return)
    }

    private fun updateActiveMix(running: ActiveMix) {
        val plan = running.plan
        if (!PlaybackSessionClock.isCurrent(plan.queueVersion)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to plan.key,
                    "planQueueVersion" to plan.queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "active",
                ),
            )
            cancel("stale-transition-plan", keepMainVolume = true)
            return
        }
        val currentId = mainPlayer.currentMediaItem?.mediaId
        if (running.phase == MixPhase.FadingOut) {
            when (currentId) {
                plan.currentId -> {
                    val fadeStartedAtMs = running.fadeOutStartedAtMs ?: running.startedAtMs
                    val fadeElapsed = SystemClock.elapsedRealtime() - fadeStartedAtMs
                    val p = (fadeElapsed.toFloat() / plan.fadeOutMs.toFloat()).coerceIn(0f, 1f)
                    mainPlayer.volume = 1f - smoothStep(p)
                    if (p >= FADE_OUT_JUMP_THRESHOLD) {
                        jumpToNextForMix(running, fadeElapsed)
                    }
                    return
                }
                plan.nextId -> {
                    resetTempoLock(running.plan, "natural-next")
                    running.phase = MixPhase.FadingIn
                    running.fadeStartedAtMs = SystemClock.elapsedRealtime()
                }
                else -> {
                    cancel("active-current-changed", keepMainVolume = true)
                    return
                }
            }
        }
        if (currentId != plan.nextId) {
            mainPlayer.volume = 0f
            if (!running.mainNextWaitLogged &&
                SystemClock.elapsedRealtime() - running.startedAtMs >= MAIN_NEXT_WAIT_LOG_MS
            ) {
                running.mainNextWaitLogged = true
                logMixEvent(
                    "main_next_waiting",
                    plan,
                    mapOf(
                        "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                        "mainMediaId" to currentId,
                        "mainState" to playbackStateName(mainPlayer.playbackState),
                        "elapsedMs" to (SystemClock.elapsedRealtime() - running.startedAtMs),
                    ),
                )
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val mainReady = mainPlayer.playbackState == Player.STATE_READY && mainPlayer.playWhenReady
        if (!mainReady) {
            mainPlayer.volume = 0f
            if (!running.mainNextWaitLogged && now - running.startedAtMs >= MAIN_NEXT_WAIT_LOG_MS) {
                running.mainNextWaitLogged = true
                logMixEvent(
                    "main_next_waiting",
                    plan,
                    mapOf(
                        "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                        "mainState" to playbackStateName(mainPlayer.playbackState),
                        "mainPositionMs" to mainPlayer.currentPosition.coerceAtLeast(0L),
                        "elapsedMs" to (now - running.startedAtMs),
                    ),
                )
            }
            return
        }

        if (running.fadeStartedAtMs == null) {
            running.fadeStartedAtMs = now
            if (!running.mainNextReadyLogged) {
                running.mainNextReadyLogged = true
                logMixEvent(
                    "main_next_ready",
                    plan,
                    mapOf(
                        "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                        "elapsedToReadyMs" to (now - running.startedAtMs),
                        "mainPositionMs" to mainPlayer.currentPosition.coerceAtLeast(0L),
                    ),
                )
            }
        }

        val fadeStartedAtMs = running.fadeStartedAtMs ?: now
        val fadeElapsed = now - fadeStartedAtMs
        val p = (fadeElapsed.toFloat() / plan.fadeInMs.toFloat()).coerceIn(0f, 1f)
        mainPlayer.volume = smoothStep(p)

        if (p >= 1f) {
            completeMainNextMix(running, now, fadeElapsed)
        }
    }

    private fun jumpToNextForMix(running: ActiveMix, fadeOutElapsedMs: Long) {
        val plan = running.plan
        mainPlayer.volume = 0f
        running.phase = MixPhase.WaitingForNext
        resetTempoLock(plan, "jump")
        if (mainPlayer.currentMediaItem?.mediaId == plan.nextId) {
            mainPlayer.seekTo(plan.nextStartPositionMs)
        } else {
            mainPlayer.seekTo(plan.nextIndex, plan.nextStartPositionMs)
        }
        if (mainPlayer.playbackState == Player.STATE_IDLE || mainPlayer.playbackState == Player.STATE_ENDED) {
            mainPlayer.prepare()
        }
        mainPlayer.play()
        logMixEvent(
            "jumped",
            plan,
            mapOf(
                "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                "fadeOutElapsedMs" to fadeOutElapsedMs,
                "mainTargetIndex" to plan.nextIndex,
                "mainTargetPositionMs" to plan.nextStartPositionMs,
                "mainState" to playbackStateName(mainPlayer.playbackState),
                "mainMediaId" to mainPlayer.currentMediaItem?.mediaId,
            ),
        )
    }

    private fun completeMainNextMix(running: ActiveMix, now: Long, fadeElapsedMs: Long) {
        val plan = running.plan
        val mainPositionMs = mainPlayer.currentPosition.coerceAtLeast(0L)
        resetTempoLock(plan, "completed")
        lastCompletedKey = plan.key
        lastCompletedQueueVersion = plan.queueVersion
        lastCompletedAtMs = now
        active = null
        mainPlayer.volume = 1f
        logMixEvent(
            "completed",
            plan,
            mapOf(
                "transitionMode" to MAIN_ONLY_TRANSITION_MODE,
                "mainPositionMs" to mainPositionMs,
                "elapsedToReadyMs" to ((running.fadeStartedAtMs ?: now) - running.startedAtMs),
                "fadeElapsedMs" to fadeElapsedMs,
                "tempoLockApplied" to running.tempoLockApplied,
                "continuityMode" to "main-only-next",
                "offsetOutsideTolerance" to false,
            ),
        )
        logTransitionSummary(
            plan = plan,
            mode = TransitionMode.SafeCut,
            success = true,
            completedReason = "main_only_completed",
            failureReason = null,
        )
    }

    private fun logTransitionSummary(
        plan: AutoMixPlan,
        mode: TransitionMode,
        success: Boolean,
        completedReason: String?,
        failureReason: String?,
    ) {
        TransitionMetrics.record(
            TransitionResult(
                pairKey = plan.key,
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = plan.queueVersion,
                mode = mode,
                success = success,
                plannedMode = plan.targetTransitionMode,
                modeSource = plan.transitionModeSource,
                plannedRisk = plan.committedTransitionRisk,
                completedReason = completedReason,
                failureReason = failureReason,
            ),
        )
    }

    private fun buildPlan(): AutoMixPlan? {
        val currentIndex = mainPlayer.currentMediaItemIndex.takeIf { it >= 0 } ?: return null
        val nextIndex = nextIndex(currentIndex) ?: return null
        val currentItem = mainPlayer.getMediaItemAt(currentIndex)
        val nextItem = mainPlayer.getMediaItemAt(nextIndex)
        val currentTrack = currentItem.toNativeTrack() ?: return null
        val nextTrack = nextItem.toNativeTrack() ?: return null
        val currentFeatures = featuresStore.get(currentTrack.id)
        val nextFeatures = featuresStore.get(nextTrack.id)
        val currentDurationMs = mainPlayer.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val currentClipSourceDurationMs = currentDurationMs +
            currentItem.clippingConfiguration.startPositionMs.coerceAtLeast(0L)
        if (shouldLeaveAlbumGapless(currentTrack, nextTrack, currentFeatures, nextFeatures)) return null
        val queueVersion = PlaybackSessionClock.currentQueueVersion()
        val committedTransitionPlan = CommittedQueuePlanStore.transitionFor(
            queueVersion = queueVersion,
            fromTrackId = currentTrack.id,
            toTrackId = nextTrack.id,
        )
        if (committedTransitionPlan?.risk == TransitionRisk.High ||
            committedTransitionPlan?.mode == TransitionMode.NoMix ||
            committedTransitionPlan?.mode == TransitionMode.NativeGapless
        ) {
            DiagnosticsLogStore.record(
                area = "automix",
                event = "committed_transition_downgraded",
                fields = mapOf(
                    "queueVersion" to queueVersion,
                    "pairKey" to "${currentTrack.id}->${nextTrack.id}",
                    "committedMode" to committedTransitionPlan.mode.name,
                    "committedRisk" to committedTransitionPlan.risk.name,
                    "reason" to "committed_no_mix_or_high_risk",
                ),
            )
            return null
        }
        val mix = chooseMixWindow(
            currentTrack = currentTrack,
            nextTrack = nextTrack,
            currentFeatures = currentFeatures,
            nextFeatures = nextFeatures,
            currentDurationMs = currentDurationMs,
        ) ?: return null
        val targetTransitionMode = when (committedTransitionPlan?.mode) {
            TransitionMode.RealtimeCrossfade ->
                if (mix.realtimeCrossfadeEligible) TransitionMode.RealtimeCrossfade else TransitionMode.SafeCut
            TransitionMode.SafeCut -> TransitionMode.SafeCut
            TransitionMode.NativeGapless, TransitionMode.NoMix -> return null
            null -> if (mix.realtimeCrossfadeEligible) TransitionMode.RealtimeCrossfade else TransitionMode.SafeCut
        }
        val transitionModeSource = if (committedTransitionPlan != null) "committed_plan" else "runtime"
        return AutoMixPlan(
            key = "${currentTrack.id}->${nextTrack.id}",
            queueVersion = queueVersion,
            currentId = currentTrack.id,
            nextId = nextTrack.id,
            nextIndex = nextIndex,
            nextStartPositionMs = mix.nextStartPositionMs,
            nextMediaItem = nextItem,
            currentUrl = currentTrack.streamUrl,
            nextUrl = nextTrack.streamUrl,
            currentDurationMs = currentClipSourceDurationMs,
            currentTitle = currentTrack.title,
            nextTitle = nextTrack.title,
            nextArtist = nextTrack.artist,
            nextAlbum = nextTrack.album,
            mixMs = mix.mixMs,
            realtimeCrossfadeMs = mix.realtimeCrossfadeMs,
            realtimeCrossfadeEligible = targetTransitionMode == TransitionMode.RealtimeCrossfade && mix.realtimeCrossfadeEligible,
            realtimeCrossfadeMode = mix.realtimeCrossfadeMode,
            requiresTailDip = mix.requiresTailDip,
            tailAmpThreshold = mix.tailAmpThreshold,
            requiredTailDipTicks = mix.requiredTailDipTicks,
            fadeOutMs = mix.fadeOutMs,
            fadeInMs = mix.fadeInMs,
            tempoLockSpeed = mix.tempoLockSpeed,
            tempoLockLeadMs = mix.tempoLockLeadMs,
            nextTempoScale = mix.nextTempoScale,
            policy = mix.policy,
            reason = mix.reason,
            targetTransitionMode = targetTransitionMode,
            transitionModeSource = transitionModeSource,
            committedTransitionRisk = committedTransitionPlan?.risk,
            diagnostics = mix.diagnostics + mapOf(
                "targetTransitionMode" to targetTransitionMode.name,
                "transitionModeSource" to transitionModeSource,
                "committedTransitionMode" to committedTransitionPlan?.mode?.name,
                "committedTransitionRisk" to committedTransitionPlan?.risk?.name,
            ),
        )
    }

    private fun chooseMixWindow(
        currentTrack: NativeTrack,
        nextTrack: NativeTrack,
        currentFeatures: AudioFeatures?,
        nextFeatures: AudioFeatures?,
        currentDurationMs: Long,
    ): MixWindow? {
        if (currentDurationMs in 1L until MIN_CURRENT_DURATION_MS) return null
        if (currentFeatures == null || nextFeatures == null) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "missing-features",
                diagnostics = mapOf(
                    "currentFeaturesReady" to (currentFeatures != null),
                    "nextFeaturesReady" to (nextFeatures != null),
                ),
            )
        }
        if (currentFeatures.durationS < MIN_TRACK_DURATION_S || nextFeatures.durationS < MIN_TRACK_DURATION_S) return null
        val fit = TransitionScore.fitScore(
            TransitionScore.Scored(currentTrack, currentFeatures),
            TransitionScore.Scored(nextTrack, nextFeatures),
        )

        val bpmA = currentFeatures.bpm
        val bpmB = nextFeatures.bpm
        val bpmReliable = bpmA != null && bpmB != null &&
            currentFeatures.bpmConfidence >= MIN_BPM_CONFIDENCE &&
            nextFeatures.bpmConfidence >= MIN_BPM_CONFIDENCE
        val bpmDelta = if (bpmReliable) abs(bpmA!! - bpmB!!) else 0.0
        if (bpmReliable && bpmDelta > MAX_BPM_DELTA) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "bpm-delta",
                diagnostics = mapOf("bpmDelta" to "%.1f".format(bpmDelta)),
            )
        }

        val energyDelta = abs(currentFeatures.outroEnergy - nextFeatures.introEnergy)
        val lowEnergyDelta = abs(currentFeatures.outroLowEnergy - nextFeatures.introLowEnergy)
        val brightnessDelta = abs(currentFeatures.spectralCentroidHz - nextFeatures.spectralCentroidHz)
        val tonalDistance = tonalDistance(currentFeatures, nextFeatures)
        val vocalClash = currentFeatures.outroVocalDensity * nextFeatures.introVocalDensity
        val boundaryIsTight = currentFeatures.tailSilenceS <= TIGHT_SILENCE_S &&
            nextFeatures.headSilenceS <= TIGHT_SILENCE_S
        val hasBreathableBoundary = currentFeatures.tailSilenceS >= BREATHABLE_SILENCE_S ||
            nextFeatures.headSilenceS >= BREATHABLE_SILENCE_S
        val busyOutgoingTail = currentFeatures.tailSilenceS < BUSY_TAIL_SILENCE_S &&
            currentFeatures.outroEnergy >= BUSY_OUTRO_ENERGY
        val melodicTailRisk = currentFeatures.tailSilenceS < MELODIC_TAIL_SILENCE_S &&
            (currentFeatures.outroEnergy >= MELODIC_TAIL_ENERGY ||
                currentFeatures.outroVocalDensity >= BUSY_OUTRO_VOCAL_DENSITY)
        val tailNeedsLiveDip = currentFeatures.tailSilenceS < BUSY_TAIL_SILENCE_S &&
            currentFeatures.outroEnergy >= TAIL_DIP_MIN_OUTRO_ENERGY
        val busyIncomingHead = nextFeatures.headSilenceS < BUSY_HEAD_SILENCE_S &&
            (nextFeatures.introEnergy >= BUSY_INTRO_ENERGY ||
                nextFeatures.introVocalDensity >= BUSY_INTRO_VOCAL_DENSITY)
        val clashRisk = tailNeedsLiveDip && busyIncomingHead
        val vocalTailBlocks = currentFeatures.outroVocalDensity >= BLOCKING_OUTRO_VOCAL_DENSITY &&
            currentFeatures.outroStartS == null &&
            currentFeatures.tailSilenceS < BREATHABLE_SILENCE_S
        val vocalEntryTooEarly = nextFeatures.vocalEntryS?.let { it <= EARLY_VOCAL_ENTRY_S } == true &&
            nextFeatures.introVocalDensity >= BUSY_INTRO_VOCAL_DENSITY
        val drumEntryTooEarly = nextFeatures.drumEntryS?.let { it <= EARLY_DRUM_ENTRY_S } == true &&
            nextFeatures.introLowEnergy >= BUSY_INTRO_LOW_ENERGY
        val tonalClash = tonalDistance != null &&
            tonalDistance >= TONAL_CLASH_DISTANCE &&
            currentFeatures.tonalConfidence >= MIN_TONAL_CONFIDENCE &&
            nextFeatures.tonalConfidence >= MIN_TONAL_CONFIDENCE
        val diagnostics = mixDiagnostics(
            fit = fit,
            bpmDelta = bpmDelta,
            energyDelta = energyDelta,
            lowEnergyDelta = lowEnergyDelta,
            brightnessDelta = brightnessDelta,
            tonalDistance = tonalDistance,
            vocalClash = vocalClash,
            boundaryIsTight = boundaryIsTight,
            hasBreathableBoundary = hasBreathableBoundary,
            clashRisk = clashRisk,
            requiresTailDip = tailNeedsLiveDip,
            melodicTailRisk = melodicTailRisk,
            vocalTailBlocks = vocalTailBlocks,
            vocalEntryTooEarly = vocalEntryTooEarly,
            drumEntryTooEarly = drumEntryTooEarly,
            tonalClash = tonalClash,
            currentFeatures = currentFeatures,
            nextFeatures = nextFeatures,
        )
        if (energyDelta > MAX_ENERGY_DELTA) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "energy-delta",
                diagnostics = diagnostics,
            )
        }
        if (clashRisk && (fit.score < MIN_CLASH_SAFE_SCORE || energyDelta > MAX_CLASH_ENERGY_DELTA)) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "clash-risk",
                diagnostics = diagnostics,
            )
        }
        if (melodicTailRisk && fit.score < MIN_MELODIC_TAIL_SCORE) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "melodic-tail",
                diagnostics = diagnostics,
            )
        }
        if (vocalTailBlocks) return null
        if (vocalClash >= MAX_VOCAL_CLASH) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "vocal-clash",
                diagnostics = diagnostics,
            )
        }
        if (lowEnergyDelta > MAX_LOW_ENERGY_DELTA && !boundaryIsTight && !hasBreathableBoundary) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "low-energy-delta",
                diagnostics = diagnostics,
            )
        }
        if (brightnessDelta > MAX_BRIGHTNESS_DELTA_HZ && !hasBreathableBoundary) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "brightness-delta",
                diagnostics = diagnostics,
            )
        }
        if (tonalClash && !boundaryIsTight && !hasBreathableBoundary) {
            return guardedLiveTailFallback(
                currentTrack = currentTrack,
                nextTrack = nextTrack,
                currentFeatures = currentFeatures,
                nextFeatures = nextFeatures,
                reason = "tonal-clash",
                diagnostics = diagnostics,
            )
        }

        val policy = when (fit.style) {
            TransitionScore.TransitionStyle.HardCut -> {
                if (!boundaryIsTight ||
                    fit.score < MIN_SEAMLESS_SCORE ||
                    energyDelta > MAX_SEAMLESS_ENERGY_DELTA ||
                    (vocalEntryTooEarly && vocalClash > MAX_SEAMLESS_VOCAL_CLASH)
                ) {
                    return guardedLiveTailFallback(
                        currentTrack = currentTrack,
                        nextTrack = nextTrack,
                        currentFeatures = currentFeatures,
                        nextFeatures = nextFeatures,
                        reason = "hard-cut-guarded",
                        diagnostics = diagnostics,
                    )
                }
                "seamless-tight"
            }
            TransitionScore.TransitionStyle.Tight -> {
                when {
                    boundaryIsTight && fit.score >= MIN_SEAMLESS_SCORE &&
                        energyDelta <= MAX_SEAMLESS_ENERGY_DELTA -> "seamless-tight"
                    fit.score >= MIN_SMART_CUT_SCORE &&
                        energyDelta <= MAX_SMART_CUT_ENERGY_DELTA &&
                        !melodicTailRisk &&
                        !vocalEntryTooEarly &&
                        !drumEntryTooEarly &&
                        !tonalClash -> "smart-cut"
                    else -> return guardedLiveTailFallback(
                        currentTrack = currentTrack,
                        nextTrack = nextTrack,
                        currentFeatures = currentFeatures,
                        nextFeatures = nextFeatures,
                        reason = "tight-guarded",
                        diagnostics = diagnostics,
                    )
                }
            }
            TransitionScore.TransitionStyle.Soft -> {
                if (fit.score < MIN_BREATH_CUT_SCORE ||
                    !hasBreathableBoundary ||
                    melodicTailRisk ||
                    busyIncomingHead ||
                    vocalEntryTooEarly ||
                    drumEntryTooEarly
                ) {
                    return guardedLiveTailFallback(
                        currentTrack = currentTrack,
                        nextTrack = nextTrack,
                        currentFeatures = currentFeatures,
                        nextFeatures = nextFeatures,
                        reason = "soft-guarded",
                        diagnostics = diagnostics,
                    )
                }
                "breath-cut"
            }
            TransitionScore.TransitionStyle.SilenceBreath -> {
                return guardedLiveTailFallback(
                    currentTrack = currentTrack,
                    nextTrack = nextTrack,
                    currentFeatures = currentFeatures,
                    nextFeatures = nextFeatures,
                    reason = "silence-breath",
                    diagnostics = diagnostics,
                )
            }
        }

        val fadeOutMs = fadeOutMsFor(policy, melodicTailRisk)
        val fadeInMs = fadeInMsFor(policy)
        val jumpDelayMs = fadeOutJumpDelayMs(fadeOutMs)
        val avgBpm = if (bpmReliable) ((bpmA!! + bpmB!!) / 2.0).coerceIn(70.0, 180.0) else null
        val phraseMs = avgBpm?.let { bpm ->
            val beats = when {
                melodicTailRisk -> 2
                busyOutgoingTail || clashRisk -> 2
                policy == "seamless-tight" && bpmDelta <= 4.0 && energyDelta <= 0.12 -> 8
                policy == "smart-cut" -> 4
                else -> 4
            }
            ((60_000.0 / bpm) * beats).roundToLong()
        }
        val fallbackMs = when (policy) {
            "seamless-tight" -> 3_400L
            "smart-cut" -> 2_800L
            else -> 2_200L
        }
        val maxByPolicy = when {
            melodicTailRisk -> MAX_MELODIC_TAIL_MIX_MS
            clashRisk -> MAX_CLASH_MIX_MS
            policy == "seamless-tight" -> MAX_SEAMLESS_MIX_MS
            policy == "smart-cut" -> MAX_SMART_CUT_MIX_MS
            else -> MAX_BREATH_CUT_MIX_MS
        }
        val rawCutLeadMs = min(phraseMs ?: fallbackMs, maxByPolicy).coerceIn(MIN_CUT_LEAD_MS, maxByPolicy)
        val alignedCutLeadMs = alignCutLeadToPhraseBoundary(
            currentDurationMs = currentDurationMs,
            rawCutLeadMs = rawCutLeadMs,
            features = currentFeatures,
            policy = policy,
        )
        val mixMs = (alignedCutLeadMs + jumpDelayMs).coerceIn(MIN_MIX_MS, MAX_MIX_MS)
        val requiredDipTicks = when {
            melodicTailRisk || clashRisk || currentFeatures.outroVocalDensity >= BUSY_OUTRO_VOCAL_DENSITY ->
                STRICT_TAIL_DIP_TICKS
            tailNeedsLiveDip -> REQUIRED_TAIL_DIP_TICKS
            else -> 0
        }
        val entryCue = nextEntryCue(nextFeatures, policy)
        val tempoLock = tempoLockFor(
            current = currentFeatures,
            next = nextFeatures,
            fit = fit,
            policy = policy,
            bpmDelta = bpmDelta,
            melodicTailRisk = melodicTailRisk,
            clashRisk = clashRisk,
            vocalEntryTooEarly = vocalEntryTooEarly,
            drumEntryTooEarly = drumEntryTooEarly,
        )
        val nextTempoScale = realtimeCrossfadeNextTempoScale(tempoLock)
        val realtimeCrossfadeEligible = realtimeCrossfadeEligible(
            policy = policy,
            fit = fit,
            melodicTailRisk = melodicTailRisk,
            clashRisk = clashRisk,
            vocalClash = vocalClash,
            currentFeatures = currentFeatures,
            nextFeatures = nextFeatures,
        )
        val realtimeBeats = when {
            melodicTailRisk || clashRisk -> 8
            policy == "seamless-tight" -> 16
            policy == "smart-cut" -> 12
            else -> 8
        }
        val realtimeTargetMs = avgBpm?.let { ((60_000.0 / it) * realtimeBeats).roundToLong() }
            ?: when (policy) {
                "seamless-tight" -> 4_600L
                "smart-cut" -> 3_800L
                else -> 3_000L
            }
        val realtimeCrossfadeMs = realtimeTargetMs.coerceIn(MIN_REALTIME_CROSSFADE_MS, MAX_REALTIME_CROSSFADE_MS)
        val realtimeCrossfadeMode = if (realtimeCrossfadeEligible) {
            if (policy == "breath-cut") "short-breath-handoff" else "beatmatched-handoff"
        } else {
            "none"
        }
        return MixWindow(
            mixMs = mixMs,
            realtimeCrossfadeMs = realtimeCrossfadeMs,
            realtimeCrossfadeEligible = realtimeCrossfadeEligible,
            realtimeCrossfadeMode = realtimeCrossfadeMode,
            nextStartPositionMs = entryCue.positionMs,
            requiresTailDip = requiredDipTicks > 0,
            tailAmpThreshold = tailAmpThreshold(currentFeatures, melodicTailRisk || clashRisk),
            requiredTailDipTicks = requiredDipTicks,
            fadeOutMs = fadeOutMs,
            fadeInMs = fadeInMs,
            tempoLockSpeed = tempoLock.speed,
            tempoLockLeadMs = tempoLock.leadMs,
            nextTempoScale = nextTempoScale,
            policy = policy,
            reason = "${fit.style} policy=$policy score=${"%.2f".format(fit.score)} bpmDelta=${"%.1f".format(bpmDelta)} energyDelta=${"%.2f".format(energyDelta)} vocalClash=${"%.2f".format(vocalClash)} boundary=${if (boundaryIsTight) "tight" else "soft"}",
            diagnostics = diagnostics + mapOf(
                "rawCutLeadMs" to rawCutLeadMs,
                "phraseAlignedCutLeadMs" to alignedCutLeadMs,
                "fadeOutJumpDelayMs" to jumpDelayMs,
                "startLeadMs" to mixMs,
                "realtimeCrossfadeEligible" to realtimeCrossfadeEligible,
                "realtimeCrossfadeMode" to realtimeCrossfadeMode,
                "realtimeCrossfadeMs" to realtimeCrossfadeMs,
                "nextTempoScale" to "%.4f".format(nextTempoScale),
                "nextStartPositionMs" to entryCue.positionMs,
                "nextStartReason" to entryCue.reason,
                "tempoLockSpeed" to "%.4f".format(tempoLock.speed),
                "tempoLockReason" to tempoLock.reason,
            ),
        )
    }

    private fun guardedLiveTailFallback(
        currentTrack: NativeTrack,
        nextTrack: NativeTrack,
        currentFeatures: AudioFeatures?,
        nextFeatures: AudioFeatures?,
        reason: String,
        diagnostics: Map<String, Any?>,
    ): MixWindow? {
        if (currentTrack.album.isNotBlank() &&
            currentTrack.album.equals(nextTrack.album, ignoreCase = true) &&
            sameArtist(currentTrack, nextTrack)
        ) {
            return null
        }

        val outroVocal = currentFeatures?.outroVocalDensity ?: 0.0
        val introVocal = nextFeatures?.introVocalDensity ?: 0.0
        val vocalTailStillOpen = currentFeatures?.let {
            it.outroVocalDensity >= BLOCKING_OUTRO_VOCAL_DENSITY &&
                it.outroStartS == null &&
                it.tailSilenceS < BREATHABLE_SILENCE_S
        } == true
        if (vocalTailStillOpen) return null
        if (outroVocal >= FALLBACK_BUSY_OUTRO_VOCAL_DENSITY &&
            introVocal >= BUSY_INTRO_VOCAL_DENSITY
        ) return null

        val entryCue = nextEntryCue(nextFeatures, "live-tail-cut")
        return MixWindow(
            mixMs = FALLBACK_LIVE_TAIL_MIX_MS,
            realtimeCrossfadeMs = FALLBACK_LIVE_TAIL_MIX_MS,
            realtimeCrossfadeEligible = false,
            realtimeCrossfadeMode = "none",
            nextStartPositionMs = entryCue.positionMs,
            requiresTailDip = true,
            tailAmpThreshold = currentFeatures
                ?.let { tailAmpThreshold(it, strict = true) }
                ?: FALLBACK_TAIL_AMP_THRESHOLD,
            requiredTailDipTicks = FALLBACK_TAIL_DIP_TICKS,
            fadeOutMs = FALLBACK_FADE_OUT_MS,
            fadeInMs = FALLBACK_FADE_IN_MS,
            tempoLockSpeed = DEFAULT_PLAYBACK_SPEED,
            tempoLockLeadMs = 0L,
            nextTempoScale = DEFAULT_PLAYBACK_SPEED,
            policy = "live-tail-cut",
            reason = "fallback=$reason currentFeatures=${currentFeatures != null} nextFeatures=${nextFeatures != null}",
            diagnostics = diagnostics + mapOf(
                "fallbackReason" to reason,
                "fallbackMode" to "live-tail-cut",
                "nextStartPositionMs" to entryCue.positionMs,
                "nextStartReason" to entryCue.reason,
                "outroVocalDensity" to "%.2f".format(outroVocal),
                "introVocalDensity" to "%.2f".format(introVocal),
            ),
        )
    }

    private fun shouldLeaveAlbumGapless(
        currentTrack: NativeTrack,
        nextTrack: NativeTrack,
        currentFeatures: AudioFeatures?,
        nextFeatures: AudioFeatures?,
    ): Boolean {
        if (currentFeatures == null || nextFeatures == null) return false
        val sameAlbum = currentTrack.album.isNotBlank() &&
            currentTrack.album.equals(nextTrack.album, ignoreCase = true)
        if (!sameAlbum || !sameArtist(currentTrack, nextTrack)) return false
        val boundaryIsTight = currentFeatures.tailSilenceS <= TIGHT_SILENCE_S &&
            nextFeatures.headSilenceS <= TIGHT_SILENCE_S
        val energyDelta = abs(currentFeatures.outroEnergy - nextFeatures.introEnergy)
        val fit = TransitionScore.fitScore(
            TransitionScore.Scored(currentTrack, currentFeatures),
            TransitionScore.Scored(nextTrack, nextFeatures),
        )
        return boundaryIsTight && energyDelta <= MAX_SEAMLESS_ENERGY_DELTA && fit.score >= MIN_ALBUM_GAPLESS_SCORE
    }

    private fun sameArtist(a: NativeTrack, b: NativeTrack): Boolean {
        val left = a.artist.split("/", "&", ",", "、").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val right = b.artist.split("/", "&", ",", "、").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        return left.any { it in right }
    }

    private fun fadeOutMsFor(policy: String, melodicTailRisk: Boolean): Long {
        return when {
            melodicTailRisk -> 190L
            policy == "seamless-tight" -> 110L
            policy == "smart-cut" -> 150L
            else -> 190L
        }
    }

    private fun fadeInMsFor(policy: String): Long {
        return when (policy) {
            "seamless-tight" -> 260L
            "smart-cut" -> 360L
            else -> 480L
        }
    }

    private fun fadeOutJumpDelayMs(fadeOutMs: Long): Long {
        return (fadeOutMs * FADE_OUT_JUMP_THRESHOLD).roundToLong()
    }

    private fun realtimeCrossfadeEligible(
        policy: String,
        fit: TransitionScore.FitScore,
        melodicTailRisk: Boolean,
        clashRisk: Boolean,
        vocalClash: Double,
        currentFeatures: AudioFeatures,
        nextFeatures: AudioFeatures,
    ): Boolean {
        if (policy == "live-tail-cut") return false
        if (melodicTailRisk || clashRisk) return false
        if (fit.score < MIN_REALTIME_CROSSFADE_SCORE) return false
        if (vocalClash > MAX_REALTIME_CROSSFADE_VOCAL_CLASH) return false
        if (policy == "breath-cut" &&
            (fit.score < MIN_BREATH_REALTIME_CROSSFADE_SCORE ||
                vocalClash > MAX_BREATH_REALTIME_CROSSFADE_VOCAL_CLASH)
        ) return false
        val bpmReady = currentFeatures.bpm != null &&
            nextFeatures.bpm != null &&
            currentFeatures.bpmConfidence >= REALTIME_CROSSFADE_MIN_BPM_CONFIDENCE &&
            nextFeatures.bpmConfidence >= REALTIME_CROSSFADE_MIN_BPM_CONFIDENCE
        val breathableWithoutBpm = !bpmReady &&
            (currentFeatures.tailSilenceS >= BREATHABLE_SILENCE_S ||
                nextFeatures.headSilenceS >= BREATHABLE_SILENCE_S) &&
            fit.score >= MIN_BREATH_REALTIME_CROSSFADE_SCORE
        if (!bpmReady && !breathableWithoutBpm && fit.score < HIGH_CONFIDENCE_CROSSFADE_WITHOUT_BPM_SCORE) return false
        val energyDelta = abs(currentFeatures.outroEnergy - nextFeatures.introEnergy)
        val lowEnergyDelta = abs(currentFeatures.outroLowEnergy - nextFeatures.introLowEnergy)
        val maxEnergyDelta = if (policy == "breath-cut") {
            MAX_BREATH_REALTIME_CROSSFADE_ENERGY_DELTA
        } else {
            MAX_REALTIME_CROSSFADE_ENERGY_DELTA
        }
        val maxLowEnergyDelta = if (policy == "breath-cut") {
            MAX_BREATH_REALTIME_CROSSFADE_LOW_ENERGY_DELTA
        } else {
            MAX_REALTIME_CROSSFADE_LOW_ENERGY_DELTA
        }
        if (energyDelta > maxEnergyDelta) return false
        if (lowEnergyDelta > maxLowEnergyDelta) return false
        val tonalDistance = tonalDistance(currentFeatures, nextFeatures)
        val tonalReady = currentFeatures.tonalConfidence >= MIN_TONAL_CONFIDENCE &&
            nextFeatures.tonalConfidence >= MIN_TONAL_CONFIDENCE
        if (tonalReady && tonalDistance != null && tonalDistance > MAX_REALTIME_CROSSFADE_TONAL_DISTANCE) {
            return false
        }
        if (currentFeatures.outroVocalDensity >= REALTIME_CROSSFADE_MAX_OUTRO_VOCAL_DENSITY &&
            currentFeatures.outroStartS == null &&
            currentFeatures.tailSilenceS < MIN_TAIL_SILENCE_FOR_OPEN_VOCAL
        ) return false
        if (nextFeatures.introVocalDensity >= REALTIME_CROSSFADE_MAX_INTRO_VOCAL_DENSITY &&
            (nextFeatures.vocalEntryS ?: 99.0) <= EARLY_VOCAL_ENTRY_S
        ) return false
        return true
    }

    private fun realtimeCrossfadeNextTempoScale(tempoLock: TempoLock): Float {
        if (tempoLock.speed == DEFAULT_PLAYBACK_SPEED) return DEFAULT_PLAYBACK_SPEED
        return (DEFAULT_PLAYBACK_SPEED / tempoLock.speed)
            .coerceIn(MIN_REALTIME_NEXT_TEMPO_SCALE, MAX_REALTIME_NEXT_TEMPO_SCALE)
    }

    private fun nextEntryCue(features: AudioFeatures?, policy: String): EntryCue {
        if (features == null) return EntryCue(0L, "no-features")
        val headSilenceMs = (features.headSilenceS * 1000.0).roundToLong()
        val safeHeadSkipMs = (headSilenceMs - HEAD_SILENCE_PAD_MS)
            .coerceAtLeast(0L)
            .takeIf { it >= MIN_HEAD_SILENCE_SKIP_MS }
            ?.coerceAtMost(MAX_NEXT_HEAD_SKIP_MS)
            ?: 0L
        val firstBeatMs = features.firstBeatS?.let { (it * 1000.0).roundToLong() }
        val vocalEntryMs = features.vocalEntryS?.let { (it * 1000.0).roundToLong() }
        val drumEntryMs = features.drumEntryS?.let { (it * 1000.0).roundToLong() }
        val headSkipWouldCutVocal = vocalEntryMs != null &&
            safeHeadSkipMs > 0L &&
            vocalEntryMs < safeHeadSkipMs + VOCAL_PICKUP_GUARD_MS
        if (headSkipWouldCutVocal) return EntryCue(0L, "protect-vocal-pickup")

        val beatAfterSilence = firstBeatMs != null &&
            safeHeadSkipMs > 0L &&
            firstBeatMs in safeHeadSkipMs..MAX_NEXT_BEAT_CUE_MS &&
            firstBeatMs - safeHeadSkipMs <= NEXT_BEAT_FROM_SILENCE_TOLERANCE_MS &&
            features.introVocalDensity < BUSY_INTRO_VOCAL_DENSITY &&
            (vocalEntryMs == null || vocalEntryMs >= firstBeatMs + VOCAL_PICKUP_GUARD_MS)
        if (policy != "live-tail-cut" && beatAfterSilence) {
            return EntryCue(
                positionMs = (firstBeatMs - NEXT_BEAT_CUE_PAD_MS)
                    .coerceAtLeast(safeHeadSkipMs)
                    .coerceAtMost(MAX_NEXT_HEAD_SKIP_MS),
                    reason = "first-beat-after-silence",
            )
        }

        val musicalPickupAllowed = policy != "live-tail-cut" &&
            policy != "breath-cut" &&
            features.introVocalDensity < MUSICAL_CUE_MAX_INTRO_VOCAL_DENSITY
        if (musicalPickupAllowed && safeHeadSkipMs == 0L) {
            val firstBeatCue = firstBeatMs
                ?.takeIf { it in MIN_NEXT_MUSICAL_CUE_MS..MAX_NEXT_MUSICAL_CUE_MS }
                ?.let { (it - NEXT_MUSICAL_CUE_PAD_MS).coerceAtLeast(0L) }
                ?.takeIf { cue -> !cueCutsIntoVocal(cue, vocalEntryMs) }
            if (firstBeatCue != null) {
                return EntryCue(firstBeatCue, "first-beat-pickup")
            }

            val drumCue = drumEntryMs
                ?.takeIf { it in MIN_NEXT_MUSICAL_CUE_MS..MAX_NEXT_MUSICAL_CUE_MS }
                ?.let { (it - NEXT_DRUM_CUE_PAD_MS).coerceAtLeast(0L) }
                ?.takeIf { cue -> !cueCutsIntoVocal(cue, vocalEntryMs) }
            if (drumCue != null && features.introLowEnergy <= MUSICAL_CUE_MAX_LOW_ENERGY) {
                return EntryCue(drumCue, "drum-pickup")
            }
        }

        if (safeHeadSkipMs > 0L) return EntryCue(safeHeadSkipMs, "head-silence")
        return EntryCue(0L, "from-zero")
    }

    private fun cueCutsIntoVocal(cueMs: Long, vocalEntryMs: Long?): Boolean {
        if (cueMs <= 0L || vocalEntryMs == null) return false
        return cueMs > vocalEntryMs - VOCAL_PICKUP_GUARD_MS
    }

    private fun tempoLockFor(
        current: AudioFeatures,
        next: AudioFeatures,
        fit: TransitionScore.FitScore,
        policy: String,
        bpmDelta: Double,
        melodicTailRisk: Boolean,
        clashRisk: Boolean,
        vocalEntryTooEarly: Boolean,
        drumEntryTooEarly: Boolean,
    ): TempoLock {
        val bpmA = current.bpm ?: return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "no-current-bpm")
        val bpmB = next.bpm ?: return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "no-next-bpm")
        if (current.bpmConfidence < TEMPO_LOCK_MIN_BPM_CONFIDENCE ||
            next.bpmConfidence < TEMPO_LOCK_MIN_BPM_CONFIDENCE
        ) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "low-bpm-confidence")
        }
        if (bpmDelta < MIN_TEMPO_LOCK_BPM_DELTA) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "already-close")
        }
        if (bpmDelta > MAX_TEMPO_LOCK_BPM_DELTA) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "bpm-delta-too-large")
        }
        if (policy == "breath-cut" || melodicTailRisk || clashRisk || vocalEntryTooEarly || drumEntryTooEarly) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "unsafe-boundary")
        }
        if (fit.score < MIN_TEMPO_LOCK_SCORE) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "fit-too-low")
        }
        val vocalTailOpen = current.outroVocalDensity >= TEMPO_LOCK_MAX_OUTRO_VOCAL_DENSITY &&
            current.outroStartS == null
        if (vocalTailOpen || next.introVocalDensity >= TEMPO_LOCK_MAX_INTRO_VOCAL_DENSITY) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "vocal-protection")
        }

        val rawSpeed = (bpmB / bpmA).toFloat()
        val speed = rawSpeed.coerceIn(MIN_TEMPO_LOCK_SPEED, MAX_TEMPO_LOCK_SPEED)
        if (abs(speed - DEFAULT_PLAYBACK_SPEED) < MIN_TEMPO_LOCK_SPEED_DELTA) {
            return TempoLock(DEFAULT_PLAYBACK_SPEED, 0L, "speed-delta-too-small")
        }
        return TempoLock(
            speed = speed,
            leadMs = TEMPO_LOCK_LEAD_MS,
            reason = "beat-match bpmA=${"%.1f".format(bpmA)} bpmB=${"%.1f".format(bpmB)}",
        )
    }

    private fun alignCutLeadToPhraseBoundary(
        currentDurationMs: Long,
        rawCutLeadMs: Long,
        features: AudioFeatures,
        policy: String,
    ): Long {
        val bpm = features.bpm ?: return rawCutLeadMs
        val firstBeatS = features.firstBeatS ?: return rawCutLeadMs
        if (features.bpmConfidence < MIN_BPM_CONFIDENCE || currentDurationMs <= 0L) return rawCutLeadMs
        val beatMs = 60_000.0 / bpm
        if (beatMs <= 0.0 || beatMs > 1_200.0) return rawCutLeadMs
        val phraseBeats = if (policy == "seamless-tight") 4.0 else 2.0
        val phraseMs = beatMs * phraseBeats
        val targetCutMs = currentDurationMs - rawCutLeadMs
        val firstBeatMs = firstBeatS * 1000.0
        val phraseIndex = ((targetCutMs - firstBeatMs) / phraseMs).roundToLong()
        val alignedCutMs = firstBeatMs + phraseIndex * phraseMs
        val alignedCutLeadMs = (currentDurationMs - alignedCutMs).roundToLong()
        val drift = abs(alignedCutLeadMs - rawCutLeadMs)
        return if (alignedCutLeadMs in MIN_CUT_LEAD_MS..MAX_MIX_MS && drift <= PHRASE_SNAP_TOLERANCE_MS) {
            alignedCutLeadMs
        } else {
            rawCutLeadMs
        }
    }

    private fun tonalDistance(a: AudioFeatures, b: AudioFeatures): Int? {
        val ak = a.tonalKey ?: return null
        val bk = b.tonalKey ?: return null
        val raw = abs(ak - bk).mod(12)
        return min(raw, 12 - raw)
    }

    private fun smoothStep(p: Float): Float {
        return p * p * (3f - 2f * p)
    }

    private fun maybeApplyTempoLock(waiting: ArmedMix, remainingMs: Long) {
        if (waiting.tempoLockApplied) return
        val plan = waiting.plan
        val speed = plan.tempoLockSpeed
        if (speed == DEFAULT_PLAYBACK_SPEED || plan.tempoLockLeadMs <= 0L) return
        if (remainingMs > plan.tempoLockLeadMs) return
        runCatching {
            mainPlayer.setPlaybackSpeed(speed)
            waiting.tempoLockApplied = true
            logMixEvent(
                "tempo_lock_applied",
                plan,
                mapOf(
                    "remainingMs" to remainingMs,
                    "tempoLockSpeed" to "%.4f".format(speed),
                    "tempoLockLeadMs" to plan.tempoLockLeadMs,
                ),
            )
        }.onFailure { err ->
            logMixEvent(
                "tempo_lock_failed",
                plan,
                mapOf(
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                ),
            )
        }
    }

    private fun resetTempoLock(plan: AutoMixPlan?, reason: String) {
        runCatching { mainPlayer.setPlaybackSpeed(DEFAULT_PLAYBACK_SPEED) }
            .onFailure { err ->
                val fields = mapOf(
                    "reason" to reason,
                    "errorType" to err::class.java.simpleName,
                    "message" to err.message,
                )
                if (plan != null) {
                    logMixEvent("tempo_lock_reset_failed", plan, fields)
                } else {
                    DiagnosticsLogStore.record(
                        area = "automix",
                        event = "tempo_lock_reset_failed",
                        fields = fields,
                    )
                }
            }
    }

    private fun tailSignalAllowsMix(waiting: ArmedMix, remainingMs: Long): Boolean {
        val plan = waiting.plan
        if (!plan.requiresTailDip) return true

        val liveAmp = Amp.flow.value
        val hasTailDip = liveAmp <= plan.tailAmpThreshold
        if (hasTailDip) {
            waiting.tailDipTicks += 1
        } else {
            waiting.tailDipTicks = 0
        }

        if (waiting.tailDipTicks >= plan.requiredTailDipTicks) return true
        if (remainingMs <= TAIL_DIP_LAST_CHANCE_MS) {
            logMixEvent(
                "tail_dip_missing",
                plan,
                mapOf(
                    "remainingMs" to remainingMs,
                    "liveAmp" to liveAmp,
                    "tailAmpThreshold" to plan.tailAmpThreshold,
                    "tailDipTicks" to waiting.tailDipTicks,
                    "requiredTailDipTicks" to plan.requiredTailDipTicks,
                ),
            )
            cancel("tail-dip-missing", keepMainVolume = true)
        }
        return false
    }

    private fun tailAmpThreshold(features: AudioFeatures, strict: Boolean): Float {
        val energyBased = (features.outroEnergy * if (strict) 0.52 else 0.68).toFloat()
        val min = if (strict) STRICT_MIN_TAIL_AMP_THRESHOLD else MIN_TAIL_AMP_THRESHOLD
        val max = if (strict) STRICT_MAX_TAIL_AMP_THRESHOLD else MAX_TAIL_AMP_THRESHOLD
        return energyBased.coerceIn(min, max)
    }

    private fun mixDiagnostics(
        fit: TransitionScore.FitScore,
        bpmDelta: Double,
        energyDelta: Double,
        lowEnergyDelta: Double,
        brightnessDelta: Double,
        tonalDistance: Int?,
        vocalClash: Double,
        boundaryIsTight: Boolean,
        hasBreathableBoundary: Boolean,
        clashRisk: Boolean,
        requiresTailDip: Boolean,
        melodicTailRisk: Boolean,
        vocalTailBlocks: Boolean,
        vocalEntryTooEarly: Boolean,
        drumEntryTooEarly: Boolean,
        tonalClash: Boolean,
        currentFeatures: AudioFeatures,
        nextFeatures: AudioFeatures,
    ): Map<String, Any?> {
        return mapOf(
            "fitStyle" to fit.style.name,
            "fitScore" to "%.2f".format(fit.score),
            "bpmDelta" to "%.1f".format(bpmDelta),
            "energyDelta" to "%.2f".format(energyDelta),
            "lowEnergyDelta" to "%.2f".format(lowEnergyDelta),
            "brightnessDeltaHz" to "%.0f".format(brightnessDelta),
            "tonalDistance" to tonalDistance,
            "tonalConfidenceA" to "%.2f".format(currentFeatures.tonalConfidence),
            "tonalConfidenceB" to "%.2f".format(nextFeatures.tonalConfidence),
            "vocalClash" to "%.2f".format(vocalClash),
            "boundary" to if (boundaryIsTight) "tight" else "soft",
            "breathableBoundary" to hasBreathableBoundary,
            "clashRisk" to clashRisk,
            "tailDipRequired" to requiresTailDip,
            "melodicTailRisk" to melodicTailRisk,
            "vocalTailBlocks" to vocalTailBlocks,
            "vocalEntryTooEarly" to vocalEntryTooEarly,
            "drumEntryTooEarly" to drumEntryTooEarly,
            "tonalClash" to tonalClash,
            "tailSilenceS" to "%.2f".format(currentFeatures.tailSilenceS),
            "headSilenceS" to "%.2f".format(nextFeatures.headSilenceS),
            "outroEnergy" to "%.2f".format(currentFeatures.outroEnergy),
            "introEnergy" to "%.2f".format(nextFeatures.introEnergy),
            "outroLowEnergy" to "%.2f".format(currentFeatures.outroLowEnergy),
            "introLowEnergy" to "%.2f".format(nextFeatures.introLowEnergy),
            "outroVocalDensity" to "%.2f".format(currentFeatures.outroVocalDensity),
            "introVocalDensity" to "%.2f".format(nextFeatures.introVocalDensity),
            "outroStartS" to currentFeatures.outroStartS,
            "vocalEntryS" to nextFeatures.vocalEntryS,
            "drumEntryS" to nextFeatures.drumEntryS,
        )
    }

    private fun nextIndex(currentIndex: Int): Int? {
        if (currentIndex + 1 < mainPlayer.mediaItemCount) return currentIndex + 1
        if (mainPlayer.repeatMode == Player.REPEAT_MODE_ALL && mainPlayer.mediaItemCount > 1) return 0
        return null
    }

    private fun remainingMs(): Long? {
        val duration = mainPlayer.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return null
        return (duration - mainPlayer.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun prepareLeadMs(plan: AutoMixPlan): Long {
        val styleLeadMs = when {
            plan.mixMs <= SHORT_MIX_PREPARE_THRESHOLD_MS -> SHORT_MIX_PREPARE_LEAD_MS
            plan.mixMs <= TIGHT_MIX_PREPARE_THRESHOLD_MS -> TIGHT_MIX_PREPARE_LEAD_MS
            else -> SOFT_MIX_PREPARE_LEAD_MS
        }
        val tailLeadMs = if (plan.requiresTailDip) TAIL_DIP_PREPARE_LEAD_MS else styleLeadMs
        val realtimeLeadMs = if (plan.realtimeCrossfadeEligible) {
            plan.realtimeCrossfadeMs + REALTIME_CROSSFADE_PREPARE_HEADROOM_MS
        } else {
            0L
        }
        return maxOf(styleLeadMs, tailLeadMs, realtimeLeadMs).coerceAtMost(MAX_PREPARE_LEAD_MS)
    }

    private fun cancel(reason: String, keepMainVolume: Boolean) {
        val plan = active?.plan ?: armed?.plan
        val phase = when {
            active != null -> "active"
            armed != null -> "armed"
            else -> "idle"
        }
        armed = null
        active = null
        resetTempoLock(plan, reason)
        if (keepMainVolume) mainPlayer.volume = 1f
        if (reason != "main-not-playing" || plan != null) {
            val fields = mapOf(
                "reason" to reason,
                "phase" to phase,
                "mainState" to playbackStateName(mainPlayer.playbackState),
                "playWhenReady" to mainPlayer.playWhenReady,
            )
            if (plan != null) {
                logMixEvent("cancel", plan, fields)
            } else {
                DiagnosticsLogStore.record(
                    area = "automix",
                    event = "cancel",
                    fields = fields,
                )
            }
        }
    }

    private fun logMixEvent(event: String, plan: AutoMixPlan, extra: Map<String, Any?> = emptyMap()) {
        DiagnosticsLogStore.record(
            area = "automix",
            event = event,
            fields = mapOf(
                "currentId" to plan.currentId,
                "nextId" to plan.nextId,
                "currentTitle" to plan.currentTitle,
                "nextTitle" to plan.nextTitle,
                "mixPolicy" to plan.policy,
                "mixReason" to plan.reason,
                "queueVersion" to plan.queueVersion,
                "targetTransitionMode" to plan.targetTransitionMode.name,
                "transitionModeSource" to plan.transitionModeSource,
                "committedTransitionRisk" to plan.committedTransitionRisk?.name,
            ) + plan.diagnostics + extra,
        )
    }

    private fun MediaItem.toNativeTrack(): NativeTrack? {
        val mediaId = mediaId.takeIf { it.isNotBlank() } ?: return null
        return NativeTrack(
            id = mediaId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString().orEmpty(),
            streamUrl = localConfiguration?.uri?.toString().orEmpty(),
            artworkUrl = mediaMetadata.artworkUri?.toString(),
        )
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> state.toString()
    }

    private data class MixWindow(
        val mixMs: Long,
        val realtimeCrossfadeMs: Long,
        val realtimeCrossfadeEligible: Boolean,
        val realtimeCrossfadeMode: String,
        val nextStartPositionMs: Long,
        val requiresTailDip: Boolean,
        val tailAmpThreshold: Float,
        val requiredTailDipTicks: Int,
        val fadeOutMs: Long,
        val fadeInMs: Long,
        val tempoLockSpeed: Float,
        val tempoLockLeadMs: Long,
        val nextTempoScale: Float,
        val policy: String,
        val reason: String,
        val diagnostics: Map<String, Any?>,
    )

    private data class AutoMixPlan(
        val key: String,
        val queueVersion: Long,
        val currentId: String,
        val nextId: String,
        val nextIndex: Int,
        val nextStartPositionMs: Long,
        val nextMediaItem: MediaItem,
        val currentUrl: String,
        val nextUrl: String,
        val currentDurationMs: Long,
        val currentTitle: String,
        val nextTitle: String,
        val nextArtist: String,
        val nextAlbum: String,
        val mixMs: Long,
        val realtimeCrossfadeMs: Long,
        val realtimeCrossfadeEligible: Boolean,
        val realtimeCrossfadeMode: String,
        val requiresTailDip: Boolean,
        val tailAmpThreshold: Float,
        val requiredTailDipTicks: Int,
        val fadeOutMs: Long,
        val fadeInMs: Long,
        val tempoLockSpeed: Float,
        val tempoLockLeadMs: Long,
        val nextTempoScale: Float,
        val policy: String,
        val reason: String,
        val targetTransitionMode: TransitionMode,
        val transitionModeSource: String,
        val committedTransitionRisk: TransitionRisk?,
        val diagnostics: Map<String, Any?>,
    )

    private data class ArmedMix(
        val plan: AutoMixPlan,
        var tailDipTicks: Int = 0,
        var tempoLockApplied: Boolean = false,
    )

    private data class ActiveMix(
        val plan: AutoMixPlan,
        val startedAtMs: Long,
        var phase: MixPhase = MixPhase.FadingOut,
        var tempoLockApplied: Boolean = false,
        var fadeOutStartedAtMs: Long? = startedAtMs,
        var fadeStartedAtMs: Long? = null,
        var mainNextReadyLogged: Boolean = false,
        var mainNextWaitLogged: Boolean = false,
    )

    private data class EntryCue(
        val positionMs: Long,
        val reason: String,
    )

    private data class FallbackClipDecision(
        val eligible: Boolean,
        val mode: String,
        val reason: String,
        val mixMs: Long,
    )

    private data class TempoLock(
        val speed: Float,
        val leadMs: Long,
        val reason: String,
    )

    private enum class MixPhase {
        FadingOut,
        WaitingForNext,
        FadingIn,
    }

    private companion object {
        private const val TICK_MS = 80L
        private const val ARM_DIAG_INTERVAL_MS = 2_000L
        private const val FADE_OUT_JUMP_THRESHOLD = 0.92f
        private const val SHORT_MIX_PREPARE_THRESHOLD_MS = 1_500L
        private const val TIGHT_MIX_PREPARE_THRESHOLD_MS = 2_300L
        private const val MAIN_NEXT_WAIT_LOG_MS = 700L
        private const val SHORT_MIX_PREPARE_LEAD_MS = 11_500L
        private const val TIGHT_MIX_PREPARE_LEAD_MS = 10_500L
        private const val SOFT_MIX_PREPARE_LEAD_MS = 9_500L
        private const val TAIL_DIP_PREPARE_LEAD_MS = 11_000L
        private const val MAX_PREPARE_LEAD_MS = 13_000L
        private const val REALTIME_CROSSFADE_PREPARE_HEADROOM_MS = 700L
        private const val MIN_REMAINING_TO_ARM_MS = 1_200L
        private const val MIN_REMAINING_TO_START_MS = 500L
        private const val MAIN_ONLY_TRANSITION_MODE = "main-only-next"
        private const val COMPLETED_PAIR_COOLDOWN_MS = 15_000L
        private const val MIN_CURRENT_DURATION_MS = 35_000L
        private const val MIN_TRACK_DURATION_S = 35.0
        private const val MIN_BPM_CONFIDENCE = 0.28
        private const val MIN_TONAL_CONFIDENCE = 0.025
        private const val MIN_ALBUM_GAPLESS_SCORE = 0.94
        private const val MIN_SEAMLESS_SCORE = 0.88
        private const val MIN_SMART_CUT_SCORE = 0.82
        private const val MIN_BREATH_CUT_SCORE = 0.80
        private const val MIN_CLASH_SAFE_SCORE = 0.92
        private const val MIN_MELODIC_TAIL_SCORE = 0.88
        private const val MAX_BPM_DELTA = 10.0
        private const val MAX_ENERGY_DELTA = 0.32
        private const val MAX_SEAMLESS_ENERGY_DELTA = 0.14
        private const val MAX_SMART_CUT_ENERGY_DELTA = 0.22
        private const val MAX_CLASH_ENERGY_DELTA = 0.12
        private const val MAX_LOW_ENERGY_DELTA = 0.16
        private const val MAX_BRIGHTNESS_DELTA_HZ = 1_900.0
        private const val MAX_VOCAL_CLASH = 0.16
        private const val MAX_SEAMLESS_VOCAL_CLASH = 0.22
        private const val TONAL_CLASH_DISTANCE = 5
        private const val PHRASE_SNAP_TOLERANCE_MS = 420L
        private const val DEFAULT_PLAYBACK_SPEED = 1.0f
        private const val MIN_CUT_LEAD_MS = 520L
        private const val MIN_MIX_MS = 650L
        private const val MAX_MIX_MS = 1_800L
        private const val MAX_CLASH_MIX_MS = 1_100L
        private const val MAX_MELODIC_TAIL_MIX_MS = 1_050L
        private const val MAX_SEAMLESS_MIX_MS = 1_650L
        private const val MAX_SMART_CUT_MIX_MS = 1_350L
        private const val MAX_BREATH_CUT_MIX_MS = 950L
        private const val MIN_REALTIME_CROSSFADE_MS = 1_800L
        private const val MAX_REALTIME_CROSSFADE_MS = 5_500L
        // 实时双 player crossfade 总开关。改 false 后回到 main-only SafeCut。
        private const val REALTIME_CROSSFADE_ENABLED = true
        private const val REALTIME_CROSSFADE_START_TOLERANCE_MS = 160L
        private const val MIN_REALTIME_CROSSFADE_SCORE = 0.82
        private const val MIN_BREATH_REALTIME_CROSSFADE_SCORE = 0.88
        private const val HIGH_CONFIDENCE_CROSSFADE_WITHOUT_BPM_SCORE = 0.88
        private const val MAX_REALTIME_CROSSFADE_VOCAL_CLASH = 0.12
        private const val MAX_BREATH_REALTIME_CROSSFADE_VOCAL_CLASH = 0.08
        private const val REALTIME_CROSSFADE_MAX_OUTRO_VOCAL_DENSITY = 0.28
        private const val REALTIME_CROSSFADE_MAX_INTRO_VOCAL_DENSITY = 0.28
        private const val REALTIME_CROSSFADE_MIN_BPM_CONFIDENCE = 0.25
        private const val MAX_REALTIME_CROSSFADE_ENERGY_DELTA = 0.22
        private const val MAX_REALTIME_CROSSFADE_LOW_ENERGY_DELTA = 0.16
        private const val MAX_BREATH_REALTIME_CROSSFADE_ENERGY_DELTA = 0.16
        private const val MAX_BREATH_REALTIME_CROSSFADE_LOW_ENERGY_DELTA = 0.11
        private const val MAX_REALTIME_CROSSFADE_TONAL_DISTANCE = 4
        private const val MIN_TAIL_SILENCE_FOR_OPEN_VOCAL = 1.20
        private const val MIN_REALTIME_NEXT_TEMPO_SCALE = 0.965f
        private const val MAX_REALTIME_NEXT_TEMPO_SCALE = 1.035f
        private const val FALLBACK_LIVE_TAIL_MIX_MS = 1_450L
        private const val FALLBACK_FADE_OUT_MS = 170L
        private const val FALLBACK_FADE_IN_MS = 430L
        private const val TIGHT_SILENCE_S = 0.45
        private const val BREATHABLE_SILENCE_S = 0.85
        private const val BUSY_TAIL_SILENCE_S = 0.8
        private const val BUSY_HEAD_SILENCE_S = 0.6
        private const val BUSY_OUTRO_ENERGY = 0.10
        private const val BUSY_INTRO_ENERGY = 0.10
        private const val BUSY_INTRO_LOW_ENERGY = 0.09
        private const val BUSY_OUTRO_VOCAL_DENSITY = 0.32
        private const val BUSY_INTRO_VOCAL_DENSITY = 0.32
        private const val BLOCKING_OUTRO_VOCAL_DENSITY = 0.44
        private const val FALLBACK_BUSY_OUTRO_VOCAL_DENSITY = 0.36
        private const val EARLY_VOCAL_ENTRY_S = 1.2
        private const val EARLY_DRUM_ENTRY_S = 0.8
        private const val MELODIC_TAIL_SILENCE_S = 0.55
        private const val MELODIC_TAIL_ENERGY = 0.16
        private const val TAIL_DIP_MIN_OUTRO_ENERGY = 0.05
        private const val MIN_TAIL_AMP_THRESHOLD = 0.035f
        private const val MAX_TAIL_AMP_THRESHOLD = 0.095f
        private const val STRICT_MIN_TAIL_AMP_THRESHOLD = 0.024f
        private const val STRICT_MAX_TAIL_AMP_THRESHOLD = 0.065f
        private const val REQUIRED_TAIL_DIP_TICKS = 2
        private const val STRICT_TAIL_DIP_TICKS = 3
        private const val FALLBACK_TAIL_DIP_TICKS = 2
        private const val FALLBACK_TAIL_AMP_THRESHOLD = 0.055f
        private const val TAIL_DIP_LAST_CHANCE_MS = 950L
        private const val HEAD_SILENCE_PAD_MS = 80L
        private const val MIN_HEAD_SILENCE_SKIP_MS = 220L
        private const val MAX_NEXT_HEAD_SKIP_MS = 1_800L
        private const val MAX_NEXT_BEAT_CUE_MS = 2_400L
        private const val NEXT_BEAT_FROM_SILENCE_TOLERANCE_MS = 850L
        private const val NEXT_BEAT_CUE_PAD_MS = 45L
        private const val MIN_NEXT_MUSICAL_CUE_MS = 320L
        private const val MAX_NEXT_MUSICAL_CUE_MS = 2_400L
        private const val NEXT_MUSICAL_CUE_PAD_MS = 90L
        private const val NEXT_DRUM_CUE_PAD_MS = 60L
        private const val MUSICAL_CUE_MAX_INTRO_VOCAL_DENSITY = 0.24
        private const val MUSICAL_CUE_MAX_LOW_ENERGY = 0.16
        private const val VOCAL_PICKUP_GUARD_MS = 450L
        private const val TEMPO_LOCK_LEAD_MS = 5_200L
        private const val TEMPO_LOCK_MIN_BPM_CONFIDENCE = 0.36
        private const val MIN_TEMPO_LOCK_BPM_DELTA = 1.4
        private const val MAX_TEMPO_LOCK_BPM_DELTA = 6.5
        private const val MIN_TEMPO_LOCK_SPEED = 0.965f
        private const val MAX_TEMPO_LOCK_SPEED = 1.035f
        private const val MIN_TEMPO_LOCK_SPEED_DELTA = 0.006f
        private const val MIN_TEMPO_LOCK_SCORE = 0.86
        private const val TEMPO_LOCK_MAX_OUTRO_VOCAL_DENSITY = 0.24
        private const val TEMPO_LOCK_MAX_INTRO_VOCAL_DENSITY = 0.28
    }
}
