package app.pipo.nativeapp.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.pipo.nativeapp.data.AudioFeatures
import app.pipo.nativeapp.data.AudioFeaturesStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.TransitionScore
import app.pipo.nativeapp.runtime.Amp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Service-side dual-deck AutoMix.
 *
 * The MediaSession player remains the only authoritative player. This helper only brings in a
 * silent secondary ExoPlayer for a short, high-confidence overlap, then hands playback back to the
 * main player at the same position in the next track.
 */
@UnstableApi
internal class SmartAutoMixer(
    context: Context,
    private val mainPlayer: ExoPlayer,
    private val dataSourceFactory: CacheDataSource.Factory,
    private val audioAttributes: AudioAttributes,
    private val featuresStore: AudioFeaturesStore,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tickPosted = false
    private var armed: ArmedMix? = null
    private var active: ActiveMix? = null
    private var lastCompletedKey: String? = null
    private var lastCompletedAtMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickPosted = false
            runCatching { tick() }
                .onFailure {
                    Log.w(TAG, "AutoMix tick failed", it)
                    cancel("tick-error", keepMainVolume = true)
                }
            if (shouldKeepTicking()) scheduleTick()
        }
    }

    fun onMainPlayerEvent() {
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
            if (running.handoffStartedAtMs != null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                mediaItem?.mediaId == running.plan.nextId
            ) {
                // The main player has naturally advanced to B while the shadow deck is already
                // playing B. Mute it immediately and snap it to the shadow position so the intro
                // hook cannot leak twice before the handoff ramp begins.
                mainPlayer.volume = 0f
                beginHandoff(running)
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
        val plan = buildPlan() ?: return
        val remainingMs = remainingMs() ?: return
        if (remainingMs > plan.mixMs + PREPARE_LEAD_MS) return
        if (remainingMs < MIN_REMAINING_TO_ARM_MS) return
        if (plan.key == lastCompletedKey &&
            SystemClock.elapsedRealtime() - lastCompletedAtMs < COMPLETED_PAIR_COOLDOWN_MS
        ) return

        val shadow = buildShadowPlayer()
        shadow.setMediaItem(plan.nextItem, plan.nextStartPositionMs)
        shadow.volume = 0f
        shadow.prepare()
        armed = ArmedMix(plan = plan, shadow = shadow)
        Log.d(TAG, "armed ${plan.currentTitle} -> ${plan.nextTitle}, mix=${plan.mixMs}ms, ${plan.reason}")
    }

    private fun updateArmedMix(waiting: ArmedMix) {
        val plan = waiting.plan
        val currentId = mainPlayer.currentMediaItem?.mediaId
        if (currentId != plan.currentId) {
            cancel("armed-current-changed", keepMainVolume = true)
            return
        }

        val remainingMs = remainingMs() ?: run {
            cancel("armed-no-duration", keepMainVolume = true)
            return
        }
        if (remainingMs > plan.mixMs) return
        if (remainingMs < MIN_REMAINING_TO_START_MS) {
            cancel("shadow-too-late", keepMainVolume = true)
            return
        }
        if (waiting.shadow.playbackState != Player.STATE_READY) return
        if (!tailSignalAllowsMix(waiting, remainingMs)) return

        waiting.shadow.volume = 0f
        waiting.shadow.play()
        active = ActiveMix(
            plan = plan,
            shadow = waiting.shadow,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        armed = null
        Log.d(TAG, "started ${plan.currentTitle} -> ${plan.nextTitle}")
    }

    private fun updateActiveMix(running: ActiveMix) {
        val plan = running.plan
        if (running.handoffStartedAtMs != null) {
            updateHandoff(running)
            return
        }

        val currentId = mainPlayer.currentMediaItem?.mediaId
        if (currentId != plan.currentId) {
            if (currentId == plan.nextId) {
                beginHandoff(running)
            } else {
                cancel("active-current-changed", keepMainVolume = true)
            }
            return
        }

        val elapsed = SystemClock.elapsedRealtime() - running.startedAtMs
        val p = (elapsed.toFloat() / plan.mixMs.toFloat()).coerceIn(0f, 1f)
        val gains = equalPowerGains(p)
        mainPlayer.volume = gains.current
        running.shadow.volume = gains.next

        if (p >= 1f || (remainingMs() ?: 0L) <= HANDOFF_REMAINING_MS) {
            beginHandoff(running)
        }
    }

    private fun beginHandoff(running: ActiveMix) {
        if (running.handoffStartedAtMs != null) return
        val plan = running.plan
        val shadowPositionMs = running.shadow.currentPosition.coerceAtLeast(0L)
        running.handoffStartedAtMs = SystemClock.elapsedRealtime()
        mainPlayer.volume = 0f
        if (mainPlayer.currentMediaItem?.mediaId == plan.nextId) {
            mainPlayer.seekTo(shadowPositionMs)
        } else {
            mainPlayer.seekTo(plan.nextIndex, shadowPositionMs)
        }
        if (mainPlayer.playbackState == Player.STATE_IDLE) {
            mainPlayer.prepare()
        }
        mainPlayer.play()
        Log.d(TAG, "handoff ${plan.nextTitle} at ${shadowPositionMs}ms")
    }

    private fun updateHandoff(running: ActiveMix) {
        val started = running.handoffStartedAtMs ?: return
        val mainReady = mainPlayer.currentMediaItem?.mediaId == running.plan.nextId &&
            mainPlayer.playbackState == Player.STATE_READY &&
            mainPlayer.playWhenReady
        val now = SystemClock.elapsedRealtime()
        if (!mainReady && now - started < HANDOFF_TIMEOUT_MS) {
            mainPlayer.volume = 0f
            running.shadow.volume = 1f
            return
        }

        val p = ((now - started).toFloat() / HANDOFF_FADE_MS.toFloat()).coerceIn(0f, 1f)
        val smooth = smoothStep(p)
        mainPlayer.volume = smooth
        running.shadow.volume = 1f - smooth
        if (p >= 1f || now - started >= HANDOFF_TIMEOUT_MS) {
            lastCompletedKey = running.plan.key
            lastCompletedAtMs = now
            cleanupShadow(running.shadow)
            active = null
            mainPlayer.volume = 1f
            Log.d(TAG, "completed ${running.plan.currentTitle} -> ${running.plan.nextTitle}")
        }
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
        val mix = chooseMixWindow(
            currentTrack = currentTrack,
            nextTrack = nextTrack,
            currentFeatures = currentFeatures,
            nextFeatures = nextFeatures,
            currentDurationMs = currentDurationMs,
        ) ?: return null
        return AutoMixPlan(
            key = "${currentTrack.id}->${nextTrack.id}",
            currentId = currentTrack.id,
            nextId = nextTrack.id,
            nextIndex = nextIndex,
            nextItem = nextItem,
            nextStartPositionMs = 0L,
            currentTitle = currentTrack.title,
            nextTitle = nextTrack.title,
            mixMs = mix.mixMs,
            requiresTailDip = mix.requiresTailDip,
            tailAmpThreshold = mix.tailAmpThreshold,
            reason = mix.reason,
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
        if (currentFeatures == null || nextFeatures == null) return null
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
        if (bpmReliable && bpmDelta > MAX_BPM_DELTA) return null

        val energyDelta = abs(currentFeatures.outroEnergy - nextFeatures.introEnergy)
        val boundaryIsTight = currentFeatures.tailSilenceS <= TIGHT_SILENCE_S &&
            nextFeatures.headSilenceS <= TIGHT_SILENCE_S
        val busyOutgoingTail = currentFeatures.tailSilenceS < BUSY_TAIL_SILENCE_S &&
            currentFeatures.outroEnergy >= BUSY_OUTRO_ENERGY
        val tailNeedsLiveDip = currentFeatures.tailSilenceS < BUSY_TAIL_SILENCE_S &&
            currentFeatures.outroEnergy >= TAIL_DIP_MIN_OUTRO_ENERGY
        val busyIncomingHead = nextFeatures.headSilenceS < BUSY_HEAD_SILENCE_S &&
            nextFeatures.introEnergy >= BUSY_INTRO_ENERGY
        val clashRisk = tailNeedsLiveDip && busyIncomingHead
        if (energyDelta > MAX_ENERGY_DELTA) {
            val hasBreathableBoundary = currentFeatures.tailSilenceS >= BREATHABLE_SILENCE_S ||
                nextFeatures.headSilenceS >= BREATHABLE_SILENCE_S
            if (!hasBreathableBoundary) return null
            return MixWindow(
                mixMs = BREATH_MIX_MS,
                requiresTailDip = tailNeedsLiveDip,
                tailAmpThreshold = tailAmpThreshold(currentFeatures),
                reason = "${fit.style} breath energyDelta=${"%.2f".format(energyDelta)}",
            )
        }
        if (fit.score < MIN_FIT_SCORE && fit.style != TransitionScore.TransitionStyle.SilenceBreath) {
            return null
        }

        val avgBpm = if (bpmReliable) ((bpmA!! + bpmB!!) / 2.0).coerceIn(70.0, 180.0) else null
        val phraseMs = avgBpm?.let { bpm ->
            val beats = when {
                clashRisk -> 2
                busyOutgoingTail -> 4
                fit.style == TransitionScore.TransitionStyle.HardCut -> 4
                bpmDelta <= 4.0 && energyDelta <= 0.16 && boundaryIsTight -> 8
                else -> 4
            }
            ((60_000.0 / bpm) * beats).roundToLong()
        }
        val fallbackMs = when (fit.style) {
            TransitionScore.TransitionStyle.HardCut -> 1_400L
            TransitionScore.TransitionStyle.Tight -> 2_200L
            TransitionScore.TransitionStyle.Soft -> 2_600L
            TransitionScore.TransitionStyle.SilenceBreath -> BREATH_MIX_MS
        }
        val maxByBoundary = when {
            clashRisk -> MAX_CLASH_MIX_MS
            busyOutgoingTail -> MAX_BUSY_TAIL_MIX_MS
            boundaryIsTight -> MAX_TIGHT_MIX_MS
            else -> MAX_SOFT_MIX_MS
        }
        val mixMs = min(phraseMs ?: fallbackMs, maxByBoundary).coerceIn(MIN_MIX_MS, MAX_MIX_MS)
        return MixWindow(
            mixMs = mixMs,
            requiresTailDip = tailNeedsLiveDip,
            tailAmpThreshold = tailAmpThreshold(currentFeatures),
            reason = "${fit.style} score=${"%.2f".format(fit.score)} bpmDelta=${"%.1f".format(bpmDelta)} energyDelta=${"%.2f".format(energyDelta)} boundary=${if (boundaryIsTight) "tight" else "soft"}",
        )
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

        if (waiting.tailDipTicks >= REQUIRED_TAIL_DIP_TICKS) return true
        return remainingMs <= TAIL_DIP_LAST_CHANCE_MS
    }

    private fun tailAmpThreshold(features: AudioFeatures): Float {
        val energyBased = (features.outroEnergy * 0.72).toFloat()
        return energyBased.coerceIn(MIN_TAIL_AMP_THRESHOLD, MAX_TAIL_AMP_THRESHOLD)
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

    private fun buildShadowPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 8_000,
                /* maxBufferMs = */ 16_000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 900,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
                repeatMode = Player.REPEAT_MODE_OFF
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private fun cancel(reason: String, keepMainVolume: Boolean) {
        armed?.let { cleanupShadow(it.shadow) }
        active?.let { cleanupShadow(it.shadow) }
        armed = null
        active = null
        if (keepMainVolume) mainPlayer.volume = 1f
        if (reason != "main-not-playing") Log.d(TAG, "cancel $reason")
    }

    private fun cleanupShadow(player: ExoPlayer) {
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.release() }
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

    private fun equalPowerGains(progress: Float): Gains {
        val p = progress.coerceIn(0f, 1f)
        val current = cos(p * PI.toFloat() / 2f).coerceIn(0f, 1f)
        val next = sin(p * PI.toFloat() / 2f).coerceIn(0f, 1f)
        return Gains(current = current, next = next)
    }

    private fun smoothStep(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private data class Gains(val current: Float, val next: Float)

    private data class MixWindow(
        val mixMs: Long,
        val requiresTailDip: Boolean,
        val tailAmpThreshold: Float,
        val reason: String,
    )

    private data class AutoMixPlan(
        val key: String,
        val currentId: String,
        val nextId: String,
        val nextIndex: Int,
        val nextItem: MediaItem,
        val nextStartPositionMs: Long,
        val currentTitle: String,
        val nextTitle: String,
        val mixMs: Long,
        val requiresTailDip: Boolean,
        val tailAmpThreshold: Float,
        val reason: String,
    )

    private data class ArmedMix(
        val plan: AutoMixPlan,
        val shadow: ExoPlayer,
        var tailDipTicks: Int = 0,
    )

    private data class ActiveMix(
        val plan: AutoMixPlan,
        val shadow: ExoPlayer,
        val startedAtMs: Long,
        var handoffStartedAtMs: Long? = null,
    )

    private companion object {
        private const val TAG = "SmartAutoMix"
        private const val TICK_MS = 180L
        private const val PREPARE_LEAD_MS = 3_400L
        private const val MIN_REMAINING_TO_ARM_MS = 1_200L
        private const val MIN_REMAINING_TO_START_MS = 750L
        private const val HANDOFF_REMAINING_MS = 120L
        private const val HANDOFF_FADE_MS = 520L
        private const val HANDOFF_TIMEOUT_MS = 2_200L
        private const val COMPLETED_PAIR_COOLDOWN_MS = 15_000L
        private const val MIN_CURRENT_DURATION_MS = 35_000L
        private const val MIN_TRACK_DURATION_S = 35.0
        private const val MIN_BPM_CONFIDENCE = 0.28
        private const val MIN_FIT_SCORE = 0.56
        private const val MAX_BPM_DELTA = 12.0
        private const val MAX_ENERGY_DELTA = 0.38
        private const val MIN_MIX_MS = 900L
        private const val MAX_MIX_MS = 3_200L
        private const val MAX_CLASH_MIX_MS = 1_100L
        private const val MAX_BUSY_TAIL_MIX_MS = 1_600L
        private const val MAX_TIGHT_MIX_MS = 2_600L
        private const val MAX_SOFT_MIX_MS = 3_200L
        private const val BREATH_MIX_MS = 1_200L
        private const val TIGHT_SILENCE_S = 0.45
        private const val BREATHABLE_SILENCE_S = 0.85
        private const val BUSY_TAIL_SILENCE_S = 0.8
        private const val BUSY_HEAD_SILENCE_S = 0.6
        private const val BUSY_OUTRO_ENERGY = 0.10
        private const val BUSY_INTRO_ENERGY = 0.10
        private const val TAIL_DIP_MIN_OUTRO_ENERGY = 0.05
        private const val MIN_TAIL_AMP_THRESHOLD = 0.035f
        private const val MAX_TAIL_AMP_THRESHOLD = 0.095f
        private const val REQUIRED_TAIL_DIP_TICKS = 2
        private const val TAIL_DIP_LAST_CHANCE_MS = 950L
    }
}
