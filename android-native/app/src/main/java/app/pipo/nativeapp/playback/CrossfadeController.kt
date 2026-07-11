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

    val isRunning: Boolean get() = active != null
    val hasActiveAuxPlayback: Boolean
        get() = auxPlayer.isPlaying

    private data class PlaybackSignature(
        val mediaId: String,
        val uri: String,
        val cacheKey: String?,
        val clipStartMs: Long,
        val clipEndMs: Long,
    )

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
        /** 主队列里 next 条目的头裁剪量（源坐标）：接管 seek 前必须换算，否则跳位。 */
        val nextItemClipStartMs: Long = 0L,
        var auxReadyDelayMs: Long? = null,
        var fadeStartedAtMs: Long? = null,
        var effectiveCrossfadeMs: Long? = null,
        var handoffWaitExtendedLogged: Boolean = false,
        var alignmentAttempts: Int = 0,
        var queueVersionChangeLogged: Boolean = false,
        var takingOver: Boolean = false,
    )

    private val mainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val a = active ?: return
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
            val a = active ?: return
            if (a.takingOver) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                cancel("main-seek-r$reason")
            }
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
     * @param beatmatchSpeed 下一首变速比(对齐 BPM,Sonic 保音高);1f 不变速
     * @param nextGainLinear 下一首响度对齐线性增益(≤1)
     */
    fun start(
        nextMediaItem: MediaItem,
        nextIndex: Int,
        nextStartMs: Long,
        crossfadeMs: Long,
        beatmatchSpeed: Float,
        nextGainLinear: Float,
        queueVersion: Long,
        pairKey: String,
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
        val resumeMs = effectiveNextStartMs + crossfadeMs
        // B 从 nextStart 开始播，并额外保留足够的尾垫：主播放器深 seek 在弱网/严格 ROM
        // 上可能超过 900ms；B 必须持续出声到 A 真正 READY，不能先自然 ended。
        val auxItem = nextMediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(effectiveNextStartMs)
                    .setEndPositionMs(resumeMs + AUX_TAIL_PAD_MS)
                    .build(),
            )
            .build()
        val auxStartResult = runCatching {
            auxGain.setLinear(nextGainLinear)
            auxPlayer.setPlaybackParameters(PlaybackParameters(beatmatchSpeed))
            auxPlayer.setMediaItem(auxItem)
            auxPlayer.volume = 0f
            auxPlayer.prepare()
            auxPlayer.playWhenReady = true
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
            beatmatchSpeed = beatmatchSpeed,
            startedAtMs = SystemClock.elapsedRealtime(),
            // nextMediaItem 是主队列里的原条目（带头裁剪）；aux 用 buildUpon 覆盖了
            // 裁剪所以播的是源坐标，主播放器接管时必须减回这个差值。
            nextItemClipStartMs = nextItemClipStartMs,
        )
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
                "beatmatchSpeed" to "%.4f".format(beatmatchSpeed),
                "nextGain" to "%.3f".format(nextGainLinear),
            ),
        )
        scheduleTick()
        return true
    }

    private val tickRunnable = Runnable { tick() }
    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
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
            val auxReady = auxPlayer.playbackState == Player.STATE_READY &&
                (auxPlayer.isPlaying || auxPlayer.currentPosition > 0L || auxPlayer.playWhenReady)
            if (!auxReady) {
                if (now - a.startedAtMs >= AUX_READY_TIMEOUT_MS) {
                    cancel("aux-ready-timeout")
                    return
                }
                scheduleTick()
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
                ),
            )
            scheduleTick()
            return
        }
        val elapsed = now - fadeStartedAtMs
        val fadeDurationMs = a.effectiveCrossfadeMs ?: a.crossfadeMs
        val p = (elapsed.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
        // 等功率:A(当前曲尾)淡出 cos,B(下一曲头)淡入 sin
        val theta = p * (Math.PI / 2.0)
        mainPlayer.volume = cos(theta).toFloat()
        auxPlayer.volume = sin(theta).toFloat()
        if (p >= HANDOFF_TAKEOVER_PROGRESS) {
            // A 已低于约 -16dB、B 已接近满音量时提前交权，给主播放器留出 seek/READY
            // 时间，避免 A 先自然进入 next 后漏出一小段开头。
            takeover(a, "fade-handoff")
            return
        }
        scheduleTick()
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
        runCatching {
            mainPlayer.volume = 0f
            // A 与 B 在交接淡变期间保持同速，避免同一段音频以两个速度叠加产生拍频；
            // 完成交接时再在 180ms 内平滑回到 1x。
            mainPlayer.setPlaybackParameters(PlaybackParameters(a.beatmatchSpeed))
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
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - handoffStartedAtMs
        val ready = mainPlayer.currentMediaItemIndex == a.nextIndex &&
            mainPlayer.currentMediaItem?.mediaId == a.nextId &&
            mainPlayer.playbackState == Player.STATE_READY
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
        if (mainPlayer.currentMediaItemIndex != a.nextIndex ||
            mainPlayer.currentMediaItem?.mediaId != a.nextId ||
            mainPlayer.playbackState != Player.STATE_READY
        ) {
            mainPlayer.volume = 0f
            auxPlayer.volume = 1f
            waitForMainReadyThenFinish(a, reason, actualResumeMs, handoffStartedAtMs)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAtMs
        val p = (elapsed.toFloat() / HANDOFF_FADE_MS.toFloat()).coerceIn(0f, 1f)
        mainPlayer.volume = p
        auxPlayer.volume = 1f - p
        val handoffSpeed = a.beatmatchSpeed + ((1f - a.beatmatchSpeed) * p)
        runCatching { mainPlayer.setPlaybackParameters(PlaybackParameters(handoffSpeed)) }
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
        restorePlayers()
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        active = null
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
                // 整个 seek/READY 期间 B 持续出声，成功交接的可听空洞为 0；旧指标把
                // “等待+180ms 淡变总耗时”误当 gap，导致所有成功样本都被误判失败。
                handoffGapMs = 0L,
                resumeDriftMs = handoffDriftMs,
                actualResumePositionMs = actualResumeMs,
            ),
        )
    }

    private fun actualResumePositionMs(a: Active): Long {
        // ClippingMediaSource 的 player position 以裁剪后的窗口起点为 0。这里必须无条件
        // 加回 nextStartMs；旧的“auxPosition >= nextStartMs 就当源坐标”启发式会在 B
        // 播过 nextStartMs 后突然少加一次入口偏移，主播放器向前回跳并重复一段旋律。
        return auxSourcePositionMs(a)
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
        val itemPositionMs = (sourcePositionMs - a.nextItemClipStartMs).coerceAtLeast(0L)
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
            mainPlayer.currentMediaItem?.playbackSignature() == a.currentSignature
        val alreadyOnNext = currentIndex == a.nextIndex &&
            currentId == a.nextId &&
            mainPlayer.currentMediaItem?.playbackSignature() == a.nextSignature
        if (!stillOnCurrent && !alreadyOnNext) return false
        if (stillOnCurrent && immediateNextIndex(currentIndex) != a.nextIndex) return false
        return matchesExpectedNext(a.nextIndex, a.nextSignature)
    }

    private fun matchesExpectedNext(nextIndex: Int, signature: PlaybackSignature): Boolean {
        if (nextIndex !in 0 until mainPlayer.mediaItemCount) return false
        val liveNext = mainPlayer.getMediaItemAt(nextIndex)
        return liveNext.playbackSignature() == signature
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
        handler.removeCallbacks(tickRunnable)
        runCatching { mainPlayer.removeListener(mainListener) }
        restorePlayers()
        runCatching { auxPlayer.stop() }
        runCatching { auxPlayer.clearMediaItems() }
        active = null
        DiagnosticsLogStore.record(
            area = "automix",
            event = "realtime_crossfade_cancel",
            fields = mapOf(
                "nextId" to a.nextId,
                "pairKey" to a.pairKey,
                "queueVersion" to a.queueVersion,
                "reason" to reason,
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

    private fun restorePlayers() {
        mainPlayer.volume = 1f
        auxPlayer.volume = 0f
        runCatching { mainPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
        runCatching { auxPlayer.setPlaybackParameters(PlaybackParameters(1f)) }
        runCatching { auxGain.setLinear(1f) }
    }

    fun release() {
        cancel("release")
        runCatching { auxPlayer.release() }
    }

    private companion object {
        private const val TICK_MS = 33L
        private const val AUX_TAIL_PAD_MS = 4_000L
        private const val AUX_READY_TIMEOUT_MS = 1_200L
        private const val MIN_EFFECTIVE_CROSSFADE_MS = 1_500L
        private const val HANDOFF_TAKEOVER_PROGRESS = 0.90f
        private const val HANDOFF_TARGET_LEAD_MS = 35L
        private const val HANDOFF_ALIGNMENT_TOLERANCE_MS = 70L
        private const val MAX_HANDOFF_ALIGNMENT_ATTEMPTS = 3
        private const val HANDOFF_READY_SOFT_TIMEOUT_MS = 900L
        private const val HANDOFF_READY_HARD_TIMEOUT_MS = 3_500L
        private const val HANDOFF_FADE_MS = 180L
    }
}
