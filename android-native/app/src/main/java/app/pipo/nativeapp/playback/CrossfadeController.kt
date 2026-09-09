package app.pipo.nativeapp.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.playback.orchestrator.TransitionMode
import app.pipo.nativeapp.playback.orchestrator.TransitionResult
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 实时双 player crossfade(阶段2,**seek 接管版**)。对标 Apple Music:对该叠加的歌对做真·叠加,
 * 而不是顺序淡变(响度凹陷)。
 *
 * - [mainPlayer](A):MediaSession 播放权威,持完整队列与 AudioFocus,复用现有全部 listener。
 *   crossfade 期间播当前曲尾(等功率淡出)。
 * - [auxPlayer](B):临时淡入出声器(handleAudioFocus=false),播下一首头段。
 *
 * **接管用 `seekTo`,不用 gapless**:crossfade 结束时 A 主动 `seekTo` 到接续点(B 播到的位置)、停 B。
 * 这是现有 main-only 验证过、不会重播的方式 —— 旧的"`replaceMediaItem(裁剪版)` + 等 A gapless 自然
 * 进入"在真机上会因 gapless 预加载残留让下一首开头被多播 ~160ms(用户报告的"同一句听到两遍")。
 * seek 强制定位,无残留,代价是接管处可能有极短 buffer(下一首深位置)——用 prewarm 缓解。
 */
@UnstableApi
internal class CrossfadeController(
    private val mainPlayer: ExoPlayer,
    private val auxPlayer: ExoPlayer,
    private val auxGain: PlaybackGain,
    private val onResult: (TransitionResult) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active: Active? = null
    private var expectedMainSeek: ExpectedSeek? = null

    private data class ExpectedSeek(
        val mediaId: String,
        val index: Int,
        val positionMs: Long,
        var observed: Boolean = false,
    )

    val isRunning: Boolean get() = active != null
    val hasActiveAuxPlayback: Boolean
        get() = auxPlayer.isPlaying

    private data class PlaybackSignature(
        val mediaId: String,
        val uri: String,
        val cacheKey: String?,
        val clipStartMs: Long,
        val clipEndMs: Long,
    ) {
        fun matches(other: PlaybackSignature?): Boolean = other != null &&
            mediaId == other.mediaId && clipStartMs == other.clipStartMs && clipEndMs == other.clipEndMs &&
            if (cacheKey != null && other.cacheKey != null) cacheKey == other.cacheKey else uri == other.uri
    }

    private class Active(
        val pairKey: String,
        var queueVersion: Long,
        val currentId: String,
        val currentIndex: Int,
        val currentSignature: PlaybackSignature,
        val nextId: String,
        val nextIndex: Int,
        val nextSignature: PlaybackSignature,
        val nextStartMs: Long,
        val resumePositionMs: Long,
        val crossfadeMs: Long,
        val beatmatchSpeed: Float,
        val startedAtMs: Long,
        val basePlaybackParameters: PlaybackParameters,
        val baseVolume: Float,
        val currentItemClipStartMs: Long,
        val nominalStartSourceMs: Long,
        val beatAlignment: BeatPhaseAlignment?,
        /** 主队列里 next 条目的头裁剪量（源坐标）：接管 seek 前必须换算，否则跳位。 */
        val nextItemClipStartMs: Long = 0L,
        var auxReadyDelayMs: Long? = null,
        var fadeStartedAtMs: Long? = null,
        var effectiveCrossfadeMs: Long? = null,
        var handoffWaitExtendedLogged: Boolean = false,
        var alignmentAttempts: Int = 0,
        var queueVersionChangeLogged: Boolean = false,
        var takingOver: Boolean = false,
        var scheduledStartSourceMs: Long? = null,
        var auxStartedAtMs: Long? = null,
        var lastAuxPositionMs: Long = -1L,
        var lastAuxProgressAtMs: Long = startedAtMs,
        var initialBeatPhaseErrorMs: Double? = null,
        var maxBeatPhaseErrorMs: Double? = null,
        var lastPhaseCorrectionAtMs: Long = 0L,
        var handoffSpeed: Float = beatmatchSpeed,
        var beatPhaseTracking: Boolean = beatAlignment != null,
        var lastAudibleNextSourceMs: Long? = null,
    )

    private val mainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val a = active ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                expectedMainSeek?.let { it.mediaId == mediaItem?.mediaId && it.index == mainPlayer.currentMediaItemIndex } != true
            ) {
                cancel("manual-media-transition")
                return
            }
            if (mediaItem?.mediaId == a.nextId && mainPlayer.currentMediaItemIndex == a.nextIndex) {
                if (a.fadeStartedAtMs == null || auxPlayer.playbackState != Player.STATE_READY) {
                    // B 还没形成可听的连续接力时，保留 A 的自然切歌；否则把 A 静音后等 B
                    // 只会人为制造空洞。
                    cancel("early-transition-before-aux-ready-r$reason")
                    return
                }
                // A 比 crossfade 早一步自然(gapless)进了 next(当前曲时长不准时会发生):
                // 立即接管 + seek 修正到接续点,把"从头多播"压到最小。
                takeover(a, "early-transition-r$reason")
            } else {
                cancel("transition-r$reason")
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (active != null && !playWhenReady) cancel("main-paused")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (active == null) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                val expected = expectedMainSeek
                if (expected != null &&
                    newPosition.mediaItem?.mediaId == expected.mediaId &&
                    newPosition.mediaItemIndex == expected.index &&
                    kotlin.math.abs(newPosition.positionMs - expected.positionMs) <= INTERNAL_SEEK_TOLERANCE_MS
                ) {
                    expected.observed = true
                    return
                }
                // 只认本控制器刚发出的目标；接管期间的进度条/歌词 seek 同样归用户所有。
                cancel("main-seek-r$reason")
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (expectedMainSeek?.observed == true) expectedMainSeek = null
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val a = active ?: return
            if (!hasExpectedQueuePair(a)) cancel("timeline-changed-r$reason")
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (active != null) cancel("main-error-${error.errorCodeName}")
        }
    }

    /**
     * @param nextStartMs 下一首进入点(entryCue,跳过头静音/前奏后)
     * @param crossfadeMs 叠加时长(= 调用时当前曲剩余)
     * @param nextGainLinear 下一首响度对齐线性增益(≤1)
     */
    fun start(
        nextMediaItem: MediaItem,
        nextIndex: Int,
        nextStartMs: Long,
        crossfadeMs: Long,
        nextGainLinear: Float,
        queueVersion: Long,
        pairKey: String,
        beatAlignment: BeatPhaseAlignment? = null,
    ): Boolean {
        if (active != null || crossfadeMs <= 0L) return false
        if (!PlaybackSessionClock.isCurrent(queueVersion)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to pairKey,
                    "planQueueVersion" to queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to "crossfade_start",
                ),
            )
            return false
        }
        val currentItem = mainPlayer.currentMediaItem ?: return false
        val currentId = currentItem.mediaId
        val currentIndex = mainPlayer.currentMediaItemIndex
        if (currentIndex == androidx.media3.common.C.INDEX_UNSET) return false
        val currentSignature = currentItem.playbackSignature() ?: return false
        val nextSignature = nextMediaItem.playbackSignature() ?: return false
        if (immediateNextIndex(currentIndex) != nextIndex || !matchesExpectedNext(nextIndex, nextSignature)) {
            return false
        }
        val nextItemClipStartMs = nextMediaItem.clippingConfiguration.startPositionMs.coerceAtLeast(0L)
        // B 的入口不能早于主队列 next 条目的裁剪起点，否则 B 播出的那段内容 A 根本
        // 无法 seek 到，交权时只能向后跳。统一到两边都能表示的源坐标。
        val effectiveNextStartMs = maxOf(nextStartMs.coerceAtLeast(0L), nextItemClipStartMs)
        val baseParameters = mainPlayer.playbackParameters
        val nextSpeed = if (beatAlignment != null) {
            baseParameters.speed * beatAlignment.nextTempoRatio
        } else {
            baseParameters.speed
        }
        val currentClipStartMs = currentItem.clippingConfiguration.startPositionMs.coerceAtLeast(0L)
        val currentSourceMs = mainPlayer.currentPosition.coerceAtLeast(0L) + currentClipStartMs
        val remainingMs = mainRemainingRealtimeMs() ?: return false
        val bufferedAheadMs = (mainPlayer.bufferedPosition - mainPlayer.currentPosition).coerceAtLeast(0L)
        if (bufferedAheadMs < (minOf(remainingMs, MIN_OUTGOING_BUFFER_MS) * baseParameters.speed).roundToLong()) {
            return false
        }
        val nominalStartSourceMs = currentSourceMs +
            ((remainingMs - crossfadeMs).coerceAtLeast(0L) * baseParameters.speed).roundToLong()
        val resumeMs = effectiveNextStartMs + (crossfadeMs * nextSpeed).roundToLong()
        // B 从 nextStart 开始播，并额外保留足够的尾垫：主播放器深 seek 在弱网/严格 ROM
        // 上可能超过 900ms；B 必须持续出声到 A 真正 READY，不能先自然 ended。
        val auxItem = nextMediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(effectiveNextStartMs)
                    .setEndPositionMs(minOf(
                        resumeMs + (AUX_TAIL_PAD_MS * nextSpeed).roundToLong(),
                        nextMediaItem.clippingConfiguration.endPositionMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
                    ))
                    .build(),
            )
            .build()
        val auxStartResult = runCatching {
            auxGain.setLinear(nextGainLinear)
            auxPlayer.setPlaybackParameters(PlaybackParameters(nextSpeed, baseParameters.pitch))
            auxPlayer.setMediaItem(auxItem)
            auxPlayer.volume = 0f
            auxPlayer.playWhenReady = false
            auxPlayer.prepare()
            true
        }
        if (auxStartResult.isFailure) {
            val error = auxStartResult.exceptionOrNull()
            runCatching { auxPlayer.volume = 0f }
            runCatching { auxPlayer.stop() }
            runCatching { auxPlayer.clearMediaItems() }
            runCatching { auxPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
            runCatching { auxGain.setLinear(1f) }
            DiagnosticsLogStore.record(
                area = "automix",
                event = "realtime_crossfade_aux_start_failed",
                fields = mapOf(
                    "nextId" to nextMediaItem.mediaId,
                    "pairKey" to pairKey,
                    "errorType" to error?.javaClass?.simpleName,
                    "message" to error?.message,
                ),
            )
            return false
        }

        mainPlayer.addListener(mainListener)
        active = Active(
            pairKey = pairKey,
            queueVersion = queueVersion,
            currentId = currentId,
            currentIndex = currentIndex,
            currentSignature = currentSignature,
            nextId = nextMediaItem.mediaId,
            nextIndex = nextIndex,
            nextSignature = nextSignature,
            nextStartMs = effectiveNextStartMs,
            resumePositionMs = resumeMs,
            crossfadeMs = crossfadeMs,
            beatmatchSpeed = nextSpeed,
            startedAtMs = SystemClock.elapsedRealtime(),
            basePlaybackParameters = baseParameters,
            baseVolume = mainPlayer.volume,
            currentItemClipStartMs = currentClipStartMs,
            nominalStartSourceMs = nominalStartSourceMs,
            beatAlignment = beatAlignment,
            // nextMediaItem 是主队列里的原条目（带头裁剪）；aux 用 buildUpon 覆盖了
            // 裁剪所以播的是源坐标，主播放器接管时必须减回这个差值。
            nextItemClipStartMs = nextItemClipStartMs,
        )
        protectedNextMediaId = nextMediaItem.mediaId
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_start",
            fields = mapOf(
                "nextId" to nextMediaItem.mediaId,
                "pairKey" to pairKey,
                "queueVersion" to queueVersion,
                "crossfadeMs" to crossfadeMs,
                "requestedNextStartMs" to nextStartMs,
                "nextStartMs" to effectiveNextStartMs,
                "nextItemClipStartMs" to nextItemClipStartMs,
                "resumeMs" to resumeMs,
                "beatmatchSpeed" to nextSpeed,
                "beatPhasePlanned" to (beatAlignment != null),
                "nominalStartSourceMs" to nominalStartSourceMs,
                "nextGain" to "%.3f".format(nextGainLinear),
            ),
        )
        scheduleTick()
        return true
    }

    private val tickRunnable = Runnable { tick() }
    private fun scheduleTick(delayMs: Long = TICK_MS) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs.coerceAtLeast(1L))
    }

    private fun tick() {
        val a = active ?: return
        if (a.takingOver) return
        if (!validateActivePair(a, "crossfade_tick")) return
        auxPlayer.playerError?.let {
            cancel("aux-error-${it.errorCodeName}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val fadeStartedAtMs = a.fadeStartedAtMs
        if (fadeStartedAtMs == null) {
            if (!mainPlayer.isPlaying) {
                cancel("main-not-playing-before-fade")
                return
            }
            if (a.auxStartedAtMs == null) {
                val bufferedMs = (auxPlayer.bufferedPosition - auxPlayer.currentPosition).coerceAtLeast(0L)
                if (auxPlayer.playbackState != Player.STATE_READY || bufferedMs < MIN_AUX_BUFFER_MS) {
                    if (now - a.startedAtMs >= AUX_READY_TIMEOUT_MS) {
                        cancel("aux-ready-timeout")
                        return
                    }
                    scheduleTick()
                    return
                }
                val currentSourceMs = outgoingSourcePositionMs(a)
                val startSourceMs = a.scheduledStartSourceMs ?: run {
                    val notBeforeMs = maxOf(
                        a.nominalStartSourceMs,
                        currentSourceMs + (START_SCHEDULING_LEAD_MS * mainPlayer.playbackParameters.speed).roundToLong(),
                    )
                    (a.beatAlignment?.startPositionMs(notBeforeMs, a.nextStartMs) ?: notBeforeMs)
                        .also { a.scheduledStartSourceMs = it }
                }
                val untilStartMs = ((startSourceMs - currentSourceMs) / mainPlayer.playbackParameters.speed).roundToLong()
                if ((mainRemainingRealtimeMs() ?: 0L) - untilStartMs.coerceAtLeast(0L) < MIN_EFFECTIVE_CROSSFADE_MS) {
                    cancel("aux-ready-too-late")
                    return
                }
                if (untilStartMs > 0L) {
                    scheduleTick(minOf(untilStartMs, if (untilStartMs <= 120L) START_TICK_MS else TICK_MS))
                    return
                }
                // 先 prepare 再按源时间开播，开播延迟不会被算进等功率淡变时长。
                a.auxStartedAtMs = now
                a.lastAuxPositionMs = auxPlayer.currentPosition
                a.lastAuxProgressAtMs = now
                auxPlayer.play()
                scheduleTick(START_TICK_MS)
                return
            }
            if (!auxIsAdvancing(a, now) || auxPlayer.currentPosition <= 0L) {
                if (now - (a.auxStartedAtMs ?: now) >= AUX_START_TIMEOUT_MS) {
                    cancel("aux-not-playing-after-start")
                    return
                }
                scheduleTick(START_TICK_MS)
                return
            }
            val effectiveCrossfadeMs = minOf(
                a.crossfadeMs,
                mainRemainingRealtimeMs() ?: a.crossfadeMs,
            )
            if (effectiveCrossfadeMs < MIN_EFFECTIVE_CROSSFADE_MS) {
                // B 准备得太晚时，不强行在曲尾做一个很短的音量跳变；回退到主播放器
                // 已经预缓冲好的自然 gapless，听感比仓促接管更稳定。
                cancel("aux-ready-too-late-${effectiveCrossfadeMs}ms")
                return
            }
            a.auxReadyDelayMs = now - a.startedAtMs
            a.fadeStartedAtMs = now
            a.effectiveCrossfadeMs = effectiveCrossfadeMs
            a.beatAlignment?.let { alignment ->
                val phaseErrorMs = alignment.phaseErrorMs(
                    outgoingSourcePositionMs(a), auxSourcePositionMs(a), mainPlayer.playbackParameters.speed,
                )
                a.initialBeatPhaseErrorMs = phaseErrorMs
                a.maxBeatPhaseErrorMs = abs(phaseErrorMs)
                if (abs(phaseErrorMs) > MAX_INITIAL_PHASE_ERROR_MS) {
                    // 启动时钟没能及时跟上时，在尚未淡入前退回普通交叉淡变，不跳音频补拍。
                    a.beatPhaseTracking = false
                    auxPlayer.setPlaybackParameters(a.basePlaybackParameters)
                }
            }
            DiagnosticsLogStore.record(
                area = "automix",
                event = "realtime_crossfade_aux_ready",
                fields = mapOf(
                    "nextId" to a.nextId,
                    "pairKey" to a.pairKey,
                    "queueVersion" to a.queueVersion,
                    "auxReadyDelayMs" to a.auxReadyDelayMs,
                    "effectiveCrossfadeMs" to effectiveCrossfadeMs,
                    "auxTimelinePositionMs" to auxPlayer.currentPosition.coerceAtLeast(0L),
                    "auxSourcePositionMs" to auxSourcePositionMs(a),
                    "scheduledStartSourceMs" to a.scheduledStartSourceMs,
                    "beatPhaseTracking" to a.beatPhaseTracking,
                    "initialBeatPhaseErrorMs" to a.initialBeatPhaseErrorMs,
                    "phaseClock" to "player-media-position",
                ),
            )
            scheduleTick()
            return
        }
        val elapsed = now - fadeStartedAtMs
        if (!auxIsAdvancing(a, now)) {
            // 辅助播放器失去可听状态后不能继续按墙钟淡出主播放器。
            cancel("aux-not-playing-during-fade")
            return
        }
        if (!mainPlayer.isPlaying) {
            if (mainPlayer.playWhenReady && mainPlayer.playbackState == Player.STATE_BUFFERING) {
                takeover(a, "main-buffering-aux-ready")
            } else {
                cancel("main-not-playing-during-fade")
            }
            return
        }
        correctBeatPhase(a, now)
        val fadeDurationMs = a.effectiveCrossfadeMs ?: a.crossfadeMs
        val p = (elapsed.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
        // 等功率:A(当前曲尾)淡出 cos,B(下一曲头)淡入 sin
        val theta = p * (Math.PI / 2.0)
        mainPlayer.volume = a.baseVolume * cos(theta).toFloat()
        auxPlayer.volume = a.baseVolume * sin(theta).toFloat()
        if (p >= HANDOFF_TAKEOVER_PROGRESS) {
            // A 已低于约 -16dB、B 已接近满音量时提前交权，给主播放器留出 seek/READY
            // 时间，避免 A 先自然进入 next 后漏出一小段开头。
            takeover(a, "fade-handoff")
            return
        }
        scheduleTick()
    }

    private fun outgoingSourcePositionMs(a: Active): Long =
        a.currentItemClipStartMs + mainPlayer.currentPosition.coerceAtLeast(0L)

    private fun auxIsAdvancing(a: Active, now: Long): Boolean {
        rememberAudibleNextPosition(a)
        if (!auxPlayer.isPlaying || auxPlayer.playbackState != Player.STATE_READY) return false
        val positionMs = auxPlayer.currentPosition
        if (positionMs > a.lastAuxPositionMs) {
            a.lastAuxPositionMs = positionMs
            a.lastAuxProgressAtMs = now
        }
        return now - a.lastAuxProgressAtMs < AUX_PROGRESS_STALL_MS
    }

    private fun rememberAudibleNextPosition(a: Active) {
        if (a.fadeStartedAtMs == null) return
        if (auxPlayer.volume > 0f) {
            a.lastAudibleNextSourceMs = maxOf(a.lastAudibleNextSourceMs ?: a.nextStartMs, auxSourcePositionMs(a))
        }
        if (mainPlayer.volume > 0f) {
            mainSourcePositionMs(a)?.let { sourceMs ->
                a.lastAudibleNextSourceMs = maxOf(a.lastAudibleNextSourceMs ?: a.nextStartMs, sourceMs)
            }
        }
    }

    /** 主播放器报错时，Service 也必须接着已混入的片段恢复，而不是读取错误后的零位置。 */
    fun resumePositionMsFor(mediaId: String): Long? {
        val a = active?.takeIf { it.nextId == mediaId } ?: return null
        rememberAudibleNextPosition(a)
        return a.lastAudibleNextSourceMs?.let { (it - a.nextItemClipStartMs).coerceAtLeast(0L) }
    }

    private fun correctBeatPhase(a: Active, now: Long) {
        val alignment = a.beatAlignment?.takeIf { a.beatPhaseTracking } ?: return
        if (now - a.lastPhaseCorrectionAtMs < PHASE_CORRECTION_INTERVAL_MS) return
        a.lastPhaseCorrectionAtMs = now
        val phaseErrorMs = alignment.phaseErrorMs(
            outgoingSourcePositionMs(a), auxSourcePositionMs(a), mainPlayer.playbackParameters.speed,
        )
        a.maxBeatPhaseErrorMs = maxOf(a.maxBeatPhaseErrorMs ?: 0.0, abs(phaseErrorMs))
        if (abs(phaseErrorMs) > MAX_TRACKING_PHASE_ERROR_MS) {
            // 已出声后不 seek/跳拍；保留连续的普通淡变，并如实标记本次没有保持对拍。
            a.beatPhaseTracking = false
            return
        }
        val correction = if (abs(phaseErrorMs) <= PHASE_DEADBAND_MS) 0.0 else
            (phaseErrorMs / PHASE_CORRECTION_HORIZON_MS).coerceIn(-MAX_PHASE_CORRECTION, MAX_PHASE_CORRECTION)
        val baseSpeed = mainPlayer.playbackParameters.speed
        val desiredSpeed = (baseSpeed * alignment.nextTempoRatio * (1.0 - correction)).toFloat()
            .coerceIn(
                (a.basePlaybackParameters.speed * BeatPhaseAlignment.MIN_TEMPO_RATIO).toFloat(),
                (a.basePlaybackParameters.speed * BeatPhaseAlignment.MAX_TEMPO_RATIO).toFloat(),
            )
        if (abs(auxPlayer.playbackParameters.speed - desiredSpeed) > 0.0005f) {
            auxPlayer.setPlaybackParameters(PlaybackParameters(desiredSpeed, a.basePlaybackParameters.pitch))
        }
    }

    /** 接管:先让 A 静音 seek/ready,B 继续出声;A 接上后再断 B。 */
    private fun takeover(a: Active, reason: String) {
        if (a.takingOver) return
        if (!validateActivePair(a, "handoff_start")) return
        a.takingOver = true
        handler.removeCallbacks(tickRunnable)
        val handoffStartedAtMs = SystemClock.elapsedRealtime()
        val actualResumeMs = actualResumePositionMs(a)
        val targetSourcePositionMs = actualResumeMs + HANDOFF_TARGET_LEAD_MS
        a.handoffSpeed = auxPlayer.playbackParameters.speed
        runCatching {
            mainPlayer.volume = 0f
            // A 与 B 在交接淡变期间保持同速，避免同一段音频以两个速度叠加产生拍频；
            // 完成交接时再在 180ms 内平滑回到 1x。
            mainPlayer.setPlaybackParameters(PlaybackParameters(a.handoffSpeed, a.basePlaybackParameters.pitch))
            seekMainToSourcePosition(a, targetSourcePositionMs)
            if (mainPlayer.playbackState == Player.STATE_IDLE || mainPlayer.playbackState == Player.STATE_ENDED) {
                mainPlayer.prepare()
            }
            mainPlayer.play()
            waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs)
        }.onFailure { err ->
            cancel("main-seek-failed-${err::class.java.simpleName}")
        }
    }

    private fun waitForMainReadyThenFinish(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
    ) {
        if (active !== a) return
        if (!validateActivePair(a, "handoff_wait")) return
        if (!auxIsAdvancing(a, SystemClock.elapsedRealtime())) {
            cancel("aux-not-playing-during-handoff")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - handoffStartedAtMs
        val ready = mainPlayer.currentMediaItemIndex == a.nextIndex &&
            mainPlayer.currentMediaItem?.mediaId == a.nextId &&
            mainPlayer.playbackState == Player.STATE_READY && mainPlayer.isPlaying
        if (!ready) {
            if (elapsedMs >= HANDOFF_READY_SOFT_TIMEOUT_MS && !a.handoffWaitExtendedLogged) {
                a.handoffWaitExtendedLogged = true
                DiagnosticsLogStore.record(
                    area = "automix",
                    event = "realtime_crossfade_handoff_wait_extended",
                    fields = mapOf(
                        "nextId" to a.nextId,
                        "pairKey" to a.pairKey,
                        "elapsedMs" to elapsedMs,
                        "mainState" to mainPlayer.playbackState,
                        "auxState" to auxPlayer.playbackState,
                        "auxSourcePositionMs" to auxSourcePositionMs(a),
                    ),
                )
            }
            if (elapsedMs >= HANDOFF_READY_HARD_TIMEOUT_MS ||
                auxPlayer.playbackState == Player.STATE_ENDED ||
                auxPlayer.playerError != null
            ) {
                // 绝不执行“main 未 READY 也把 aux 淡到 0”的旧逻辑；超时交给主播放器
                // 原有坏源/网络恢复链处理。
                cancel("main-ready-timeout")
                return
            }
            handler.postDelayed(
                { waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs) },
                TICK_MS,
            )
            return
        }

        val auxSourceMs = auxSourcePositionMs(a)
        val mainSourceMs = mainSourcePositionMs(a) ?: run {
            cancel("main-position-unavailable")
            return
        }
        val signedDriftMs = mainSourceMs - auxSourceMs
        if (kotlin.math.abs(signedDriftMs) > HANDOFF_ALIGNMENT_TOLERANCE_MS &&
            a.alignmentAttempts < MAX_HANDOFF_ALIGNMENT_ATTEMPTS
        ) {
            a.alignmentAttempts += 1
            runCatching {
                seekMainToSourcePosition(a, auxSourceMs + HANDOFF_TARGET_LEAD_MS)
                mainPlayer.play()
            }.onFailure {
                cancel("main-realign-failed-${it::class.java.simpleName}")
                return
            }
            handler.postDelayed(
                { waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs) },
                TICK_MS,
            )
            return
        }
        if (kotlin.math.abs(signedDriftMs) > HANDOFF_ALIGNMENT_TOLERANCE_MS) {
            DiagnosticsLogStore.record(
                area = "automix",
                event = "realtime_crossfade_alignment_failed",
                fields = mapOf(
                    "nextId" to a.nextId,
                    "pairKey" to a.pairKey,
                    "signedDriftMs" to signedDriftMs,
                    "alignmentAttempts" to a.alignmentAttempts,
                    "auxSourcePositionMs" to auxSourceMs,
                    "mainSourcePositionMs" to mainSourceMs,
                ),
            )
            // 大偏移时双开 180ms 会把同一鼓点播放两遍；宁可终止本次智能接歌并让
            // 主播放器继续，也不能把不合格交接记录为成功。
            cancel("handoff-drift-${signedDriftMs}ms")
            return
        }

        fadeMainInAuxOut(
            a = a,
            reason = reason,
            actualResumeMs = actualResumeMs,
            handoffStartedAtMs = handoffStartedAtMs,
            fadeStartedAtMs = now,
            initialDriftMs = kotlin.math.abs(signedDriftMs),
        )
    }

    private fun fadeMainInAuxOut(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
        fadeStartedAtMs: Long,
        initialDriftMs: Long,
    ) {
        if (active !== a) return
        if (!auxIsAdvancing(a, SystemClock.elapsedRealtime())) {
            cancel("aux-not-playing-during-handoff-fade")
            return
        }
        if (mainPlayer.currentMediaItemIndex != a.nextIndex ||
            mainPlayer.currentMediaItem?.mediaId != a.nextId ||
            mainPlayer.playbackState != Player.STATE_READY || !mainPlayer.isPlaying
        ) {
            mainPlayer.volume = 0f
            auxPlayer.volume = a.baseVolume
            waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAtMs
        val p = (elapsed.toFloat() / HANDOFF_FADE_MS.toFloat()).coerceIn(0f, 1f)
        mainPlayer.volume = a.baseVolume * p
        auxPlayer.volume = a.baseVolume * (1f - p)
        val handoffSpeed = a.handoffSpeed + ((a.basePlaybackParameters.speed - a.handoffSpeed) * p)
        // 此时两边是同一首歌，必须一起回到基准速度，不能在重叠出声时仅让 A 变速。
        val parameters = PlaybackParameters(handoffSpeed, a.basePlaybackParameters.pitch)
        mainPlayer.setPlaybackParameters(parameters)
        auxPlayer.setPlaybackParameters(parameters)
        if (p < 1f) {
            handler.postDelayed(
                {
                    fadeMainInAuxOut(
                        a,
                        reason,
                        actualResumeMs,
                        handoffStartedAtMs,
                        fadeStartedAtMs,
                        initialDriftMs,
                    )
                },
                TICK_MS,
            )
            return
        }
        val finalDriftMs = mainSourcePositionMs(a)?.let { mainSourceMs ->
            kotlin.math.abs(mainSourceMs - auxSourcePositionMs(a))
        } ?: initialDriftMs
        finishHandoff(
            a = a,
            reason = reason,
            actualResumeMs = actualResumeMs,
            handoffStartedAtMs = handoffStartedAtMs,
            handoffDriftMs = finalDriftMs,
        )
    }

    private fun finishHandoff(
        a: Active,
        reason: String,
        actualResumeMs: Long,
        handoffStartedAtMs: Long,
        handoffDriftMs: Long,
    ) {
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        active = null
        expectedMainSeek = null
        restorePlayers(a)
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        protectedNextMediaId = null
        val fadeStart = a.fadeStartedAtMs ?: a.startedAtMs
        val now = SystemClock.elapsedRealtime()
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_takeover",
            fields = mapOf(
                "nextId" to a.nextId,
                "pairKey" to a.pairKey,
                "queueVersion" to a.queueVersion,
                "reason" to reason,
                "resumeMs" to a.resumePositionMs,
                "actualResumeMs" to actualResumeMs,
                "plannedResumeDriftMs" to kotlin.math.abs(actualResumeMs - a.resumePositionMs),
                "handoffDriftMs" to handoffDriftMs,
                "alignmentAttempts" to a.alignmentAttempts,
                "effectiveCrossfadeMs" to a.effectiveCrossfadeMs,
                "handoffDurationMs" to (now - handoffStartedAtMs).coerceAtLeast(0L),
                "beatPhaseTracking" to a.beatPhaseTracking,
                "initialBeatPhaseErrorMs" to a.initialBeatPhaseErrorMs,
                "maxBeatPhaseErrorMs" to a.maxBeatPhaseErrorMs,
                "phaseClock" to "player-media-position",
                "mainMediaId" to mainPlayer.currentMediaItem?.mediaId,
                "success" to true,
            ),
        )
        onResult(
            TransitionResult(
                pairKey = a.pairKey,
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = a.queueVersion,
                mode = TransitionMode.RealtimeCrossfade,
                success = true,
                completedReason = reason,
                auxReadyDelayMs = a.auxReadyDelayMs,
                actualOverlapMs = (handoffStartedAtMs - fadeStart).coerceAtLeast(0L),
                // Player 状态只用于运行时保护，不能测出扬声器端的真实静音间隙。
                handoffGapMs = null,
                resumeDriftMs = handoffDriftMs,
                actualResumePositionMs = actualResumeMs,
            ),
        )
    }

    private fun actualResumePositionMs(a: Active): Long {
        // ClippingMediaSource 的 player position 以裁剪后的窗口起点为 0。这里必须无条件
        // 加回 nextStartMs；旧的“auxPosition >= nextStartMs 就当源坐标”启发式会在 B
        // 播过 nextStartMs 后突然少加一次入口偏移，主播放器向前回跳并重复一段旋律。
        rememberAudibleNextPosition(a)
        return maxOf(auxSourcePositionMs(a), a.lastAudibleNextSourceMs ?: a.nextStartMs)
    }

    private fun auxSourcePositionMs(a: Active): Long {
        val relativePositionMs = auxPlayer.currentPosition.coerceAtLeast(0L)
        return (a.nextStartMs + relativePositionMs).coerceAtLeast(a.nextStartMs)
    }

    private fun mainSourcePositionMs(a: Active): Long? {
        if (mainPlayer.currentMediaItemIndex != a.nextIndex ||
            mainPlayer.currentMediaItem?.mediaId != a.nextId
        ) return null
        return a.nextItemClipStartMs + mainPlayer.currentPosition.coerceAtLeast(0L)
    }

    private fun seekMainToSourcePosition(a: Active, sourcePositionMs: Long) {
        // 主队列条目可能已经裁掉头静音，其 0 点对应源音频 clipStart。
        val itemPositionMs = (maxOf(sourcePositionMs, a.lastAudibleNextSourceMs ?: a.nextStartMs) -
            a.nextItemClipStartMs).coerceAtLeast(0L)
        expectedMainSeek = ExpectedSeek(a.nextId, a.nextIndex, itemPositionMs)
        if (mainPlayer.currentMediaItemIndex == a.nextIndex &&
            mainPlayer.currentMediaItem?.mediaId == a.nextId
        ) {
            mainPlayer.seekTo(itemPositionMs)
        } else {
            mainPlayer.seekTo(a.nextIndex, itemPositionMs)
        }
    }

    private fun mainRemainingRealtimeMs(): Long? {
        val durationMs = mainPlayer.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
            ?: return null
        val mediaRemainingMs = (durationMs - mainPlayer.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
        val speed = mainPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
        return (mediaRemainingMs / speed).toLong().coerceAtLeast(0L)
    }

    /**
     * 激活后的 transition 只依赖“当前曲 -> 下一曲”这一对。AI Radio 在尾部 append 队列时
     * 会 bump queueVersion，但只要这对曲目、索引和 URL 没变，就应继续交接，不能突然停掉 B。
     */
    private fun validateActivePair(a: Active, stage: String): Boolean {
        if (!hasExpectedQueuePair(a)) {
            DiagnosticsLogStore.record(
                area = "transition",
                event = "stale_transition_cancel",
                fields = mapOf(
                    "pairKey" to a.pairKey,
                    "planQueueVersion" to a.queueVersion,
                    "currentQueueVersion" to PlaybackSessionClock.currentQueueVersion(),
                    "stage" to stage,
                    "reason" to "queue_pair_changed",
                ),
            )
            cancel("queue-pair-changed-$stage")
            return false
        }
        if (!PlaybackSessionClock.isCurrent(a.queueVersion) && !a.queueVersionChangeLogged) {
            a.queueVersionChangeLogged = true
            val currentQueueVersion = PlaybackSessionClock.currentQueueVersion()
            DiagnosticsLogStore.record(
                area = "transition",
                event = "queue_version_changed_pair_preserved",
                fields = mapOf(
                    "pairKey" to a.pairKey,
                    "planQueueVersion" to a.queueVersion,
                    "currentQueueVersion" to currentQueueVersion,
                    "stage" to stage,
                ),
            )
            a.queueVersion = currentQueueVersion
        } else if (!PlaybackSessionClock.isCurrent(a.queueVersion)) {
            a.queueVersion = PlaybackSessionClock.currentQueueVersion()
        }
        return true
    }

    private fun hasExpectedQueuePair(a: Active): Boolean {
        val currentIndex = mainPlayer.currentMediaItemIndex
        val currentId = mainPlayer.currentMediaItem?.mediaId
        val stillOnCurrent = currentIndex == a.currentIndex &&
            currentId == a.currentId &&
            a.currentSignature.matches(mainPlayer.currentMediaItem?.playbackSignature())
        val alreadyOnNext = currentIndex == a.nextIndex &&
            currentId == a.nextId &&
            a.nextSignature.matches(mainPlayer.currentMediaItem?.playbackSignature())
        if (!stillOnCurrent && !alreadyOnNext) return false
        if (stillOnCurrent && immediateNextIndex(currentIndex) != a.nextIndex) return false
        return matchesExpectedNext(a.nextIndex, a.nextSignature)
    }

    private fun matchesExpectedNext(nextIndex: Int, signature: PlaybackSignature): Boolean {
        if (nextIndex !in 0 until mainPlayer.mediaItemCount) return false
        val liveNext = mainPlayer.getMediaItemAt(nextIndex)
        return signature.matches(liveNext.playbackSignature())
    }

    private fun immediateNextIndex(currentIndex: Int): Int? {
        if (mainPlayer.repeatMode == Player.REPEAT_MODE_ONE) return null
        val nextIndex = mainPlayer.nextMediaItemIndex
        return nextIndex.takeIf {
            it != androidx.media3.common.C.INDEX_UNSET && it != currentIndex
        }
    }

    private fun MediaItem.playbackSignature(): PlaybackSignature? {
        val local = localConfiguration ?: return null
        return PlaybackSignature(
            mediaId = mediaId,
            uri = local.uri.toString().takeIf { it.isNotBlank() } ?: return null,
            cacheKey = local.customCacheKey,
            clipStartMs = clippingConfiguration.startPositionMs,
            clipEndMs = clippingConfiguration.endPositionMs,
        )
    }

    /** 被打断(手动切歌/暂停/seek/错误)时:停 B、恢复音量。A 队列未被改动,无需还原。 */
    fun cancel(reason: String) {
        val a = active ?: return
        rememberAudibleNextPosition(a)
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        active = null
        expectedMainSeek = null
        val continuationMs = a.lastAudibleNextSourceMs?.takeIf {
            // 用户明确 seek/切队列的落点必须保留；内部交接失败则不能重放已经混入的头段。
            (reason.startsWith("aux-not-playing") || reason.startsWith("aux-error") ||
                reason.startsWith("early-transition-before-aux-ready") || reason.startsWith("main-ready") ||
                reason.startsWith("main-realign") || reason.startsWith("main-alignment") ||
                reason.startsWith("handoff-drift") || reason.startsWith("main-position-unavailable") ||
                reason.startsWith("main-seek-failed") || reason.startsWith("main-error")) &&
                hasExpectedQueuePair(a)
        }
        if (continuationMs != null) {
            runCatching {
                seekMainToSourcePosition(a, maxOf(continuationMs, mainSourcePositionMs(a) ?: 0L))
                if (mainPlayer.playbackState == Player.STATE_IDLE || mainPlayer.playbackState == Player.STATE_ENDED) {
                    mainPlayer.prepare()
                }
            }
            expectedMainSeek = null
        }
        restorePlayers(a)
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        protectedNextMediaId = null
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_cancel",
            fields = mapOf(
                "nextId" to a.nextId,
                "pairKey" to a.pairKey,
                "queueVersion" to a.queueVersion,
                "reason" to reason,
                "preservedNextSourcePositionMs" to continuationMs,
            ),
        )
        onResult(
            TransitionResult(
                pairKey = a.pairKey,
                sessionId = PlaybackSessionClock.sessionId,
                queueVersion = a.queueVersion,
                mode = TransitionMode.RealtimeCrossfade,
                success = false,
                failureReason = reason,
                auxReadyDelayMs = a.auxReadyDelayMs,
            ),
        )
    }

    private fun restorePlayers(a: Active) {
        mainPlayer.volume = a.baseVolume
        auxPlayer.volume = 0f
        runCatching { mainPlayer.setPlaybackParameters(a.basePlaybackParameters) }
        runCatching { auxPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
        runCatching { auxGain.setLinear(1f) }
    }

    fun release() {
        cancel("release")
        runCatching { auxPlayer.release() }
    }

    companion object {
        @Volatile
        private var protectedNextMediaId: String? = null

        /** Service 是唯一交接者，ViewModel 的自动恢复和预热不能同时重建下一首。 */
        val ownsTransition: Boolean get() = protectedNextMediaId != null

        fun protectsMediaItem(mediaId: String): Boolean = protectedNextMediaId == mediaId

        private const val TICK_MS = 33L
        private const val START_TICK_MS = 8L
        private const val START_SCHEDULING_LEAD_MS = 100L
        private const val INTERNAL_SEEK_TOLERANCE_MS = 2L
        private const val AUX_TAIL_PAD_MS = 4_000L
        private const val AUX_READY_TIMEOUT_MS = 3_000L
        private const val AUX_START_TIMEOUT_MS = 750L
        private const val AUX_PROGRESS_STALL_MS = 300L
        private const val MIN_AUX_BUFFER_MS = 2_500L
        private const val MIN_OUTGOING_BUFFER_MS = 3_500L
        private const val MIN_EFFECTIVE_CROSSFADE_MS = 1_500L
        private const val HANDOFF_TAKEOVER_PROGRESS = 0.90f
        private const val HANDOFF_TARGET_LEAD_MS = 0L
        private const val HANDOFF_ALIGNMENT_TOLERANCE_MS = 20L
        private const val MAX_HANDOFF_ALIGNMENT_ATTEMPTS = 3
        private const val HANDOFF_READY_SOFT_TIMEOUT_MS = 900L
        private const val HANDOFF_READY_HARD_TIMEOUT_MS = 3_500L
        private const val HANDOFF_FADE_MS = 180L
        private const val MAX_INITIAL_PHASE_ERROR_MS = 65.0
        private const val MAX_TRACKING_PHASE_ERROR_MS = 110.0
        private const val PHASE_DEADBAND_MS = 8.0
        private const val PHASE_CORRECTION_INTERVAL_MS = 100L
        private const val PHASE_CORRECTION_HORIZON_MS = 2_500.0
        private const val MAX_PHASE_CORRECTION = 0.0125
    }
}
