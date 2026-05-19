package app.pipo.nativeapp.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.progress
import app.pipo.nativeapp.data.timingPartsForProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 沉浸式歌词层 —— 镜像 src/components/PlayerCard.tsx ImmersiveLyrics 的非封面部分。
 *
 * 封面由 TransitioningCover 在外层独立渲染（FLIP），这里只负责：
 *   - 黑兜底 + 同源模糊 backdrop（用 progress fade）
 *   - 顶/底采样色渐变压底
 *   - 标题 / 副标题 / 控件条
 *   - Apple Music 风格的歌词列：
 *       · 全部行同字号 24sp Bold，活动行不变大但满 alpha；非活动行按距离 alpha 衰减
 *       · 上下两端用 BlendMode.DstIn mask 渐隐到几乎全透明
 *       · 活动行落在容器 ~32% 高度处（偏上）
 *       · YRC 字符级 wipe（活动行内未唱字符 alpha 0.42、已唱 1.0）
 */
/**
 * 仅渲染沉浸式的"底"：黑兜底 + 同源模糊封面 + 顶/底采样色渐变。
 * 在 PipoNativeApp 里被 TransitioningCover 叠在上面，再叠上标题 / 歌词。
 */
@Composable
fun ImmersiveBackdrop(
    progress: Float,
    coverUrl: String?,
) {
    if (progress <= 0.001f) return
    val edges = useCoverEdgeColors(coverUrl)
    val topColor = rgbToColor(edges.top, fallback = PipoColors.Bg1)
    val rightColor = rgbToColor(edges.right, fallback = PipoColors.Bg1)
    val seamColor = rgbToColor(edges.bottom, fallback = PipoColors.Bg1)
    // Apple Music 的"封面就是页"做法：根本不用 blur，靠从封面边缘采样的多个色块
    // 在屏幕上形成"色彩云"。封面 sharp 边缘 fade 进色彩里就完成了"图融化进页"。
    //
    //   底层：bg color cloud —— 多个 radialGradient 叠出 mesh-like 效果
    //     · 屏幕左上：topColor 半径 70% → 淡掉
    //     · 屏幕右上：rightColor 半径 65% → 淡掉
    //     · 屏幕底中：seamColor 半径 80% → 淡掉
    //   这三个 radial 叠加 + 互相填补，结果是封面色调温柔铺满整屏，没有可见的图样。
    //
    //   不再叠 blurred cover：blur 出来的图永远有 pattern 残留，跟 sharp 封面拼一起
    //   总有"两张图叠在一起"的视觉断层。Apple Music 的诀窍就是直接用 SOLID color，
    //   没有第二张图，所以 sharp 封面边缘 fade 时直接溶进色彩本身，浑然一体。
    // 用 drawBehind 直接画圆（中心 = 屏幕分数坐标 × 尺寸），避免 Brush.radialGradient
    // 的 center 必须是 px 坐标的繁琐
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(PipoColors.Bg0)  // 纯黑兜底，渐变叠到上面
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val maxDim = kotlin.math.max(w, h) * 1.4f
                // 三个色块叠合形成 mesh-like 色彩云
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(topColor.copy(alpha = 0.95f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                        radius = maxDim * 0.85f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.18f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(rightColor.copy(alpha = 0.85f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                        radius = maxDim * 0.80f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.30f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(seamColor.copy(alpha = 0.95f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                        radius = maxDim * 0.95f,
                    ),
                    radius = maxDim,
                    center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
                )
            },
    )
}

@Composable
fun ImmersiveLyricsOverlay(
    progress: Float,                  // 0=compact, 1=immersive
    coverUrl: String?,
    title: String,
    artist: String,
    trackId: String?,
    lyrics: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    hasTranslation: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onToggleTranslation: () -> Unit,
    onSeekToMs: (Long) -> Unit,
) {
    if (progress <= 0.001f) return

    val edges = useCoverEdgeColors(coverUrl)
    val tone = computeTone(edges.bottom)
    val fg = pickFg(tone)
    val fgDim = pickFgDim(tone)
    val fgUnsung = pickFgUnsung(tone)

    // 切歌淡出淡入：title 变了就 fade 0 → 1。封面和背景由外层 TransitioningCover /
    // ImmersiveBackdrop 自己处理过渡，这里只管标题 + 歌词的内容层。
    var lastTitle by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val contentFade = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(title) {
        if (lastTitle != null && lastTitle != title) {
            contentFade.snapTo(0f)
            contentFade.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.tween(360),
            )
        }
        lastTitle = title
    }

    // 计算封面占据屏幕顶部的高度（跟 TransitioningCover 同步）
    val configuration = LocalConfiguration.current
    val screenWDp = configuration.screenWidthDp.dp
    val coverAreaHeight = screenWDp * progress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // progress 控制 immersive 进出整体透明度，contentFade 控制切歌时内容淡入淡出
            .graphicsLayer { alpha = progress * contentFade.value },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverAreaHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        // 内容用绝对定位（Box.padding(top=...)），不再用 Column 顺序排：
        //   - 标题压在封面下 1/4 处（progress=1 时 y ≈ coverArea - 80dp，叠在封面之上）
        //   - 歌词从 coverArea - 28dp 开始（轻微"啃"进封面底）
        //     由于 TransitioningCover 在底部加了 progress 驱动的渐隐 mask，
        //     重叠区域看起来是封面平滑溶进歌词页

        // 标题 + 副标题 + 控件条 —— 绝对定位在封面下 1/4
        val titleTopPadding = (coverAreaHeight - 84.dp).coerceAtLeast(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = titleTopPadding, start = 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = title.ifBlank { "—" },
                    color = fg,
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist,
                    color = fgDim,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasTranslation) {
                    ImmersiveIconButton(
                        onClick = onToggleTranslation,
                        active = showTranslation,
                        activeColor = fg,
                    ) {
                        TranslateGlyph(
                            color = if (showTranslation) fg else fgDim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                ImmersiveIconButton(onClick = onToggle) {
                    if (isPlaying) PauseGlyph(color = fg, modifier = Modifier.size(24.dp))
                    else PlayGlyph(color = fg, modifier = Modifier.size(24.dp))
                }
                ImmersiveIconButton(onClick = onNext) {
                    SkipForwardGlyph(color = fg, modifier = Modifier.size(25.dp))
                }
            }
        }

        // 歌词列 —— 绝对定位，顶部"啃"进封面底 28dp（mask 让边界自然溶解）
        val lyricsTopPadding = (coverAreaHeight - 28.dp).coerceAtLeast(80.dp)
        AppleMusicLyricColumn(
            lines = lyrics,
            sessionId = trackId,
            activeLyricIndex = activeLyricIndex,
            positionMs = positionMs,
            isPlaying = isPlaying,
            fg = fg,
            fgDim = fgDim,
            fgUnsung = fgUnsung,
            showTranslation = showTranslation,
            onSeekToMs = onSeekToMs,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = lyricsTopPadding, bottom = 20.dp)
                .navigationBarsPadding(),
        )
    }
}

// BackdropBlurredCover 已删除：换成 ImmersiveBackdrop 里 drawBehind 多个 radialGradient
// 的"色彩云"做法，没有 blurred image 这第二层贴图，跟 Apple Music 的视觉一致。

@Composable
private fun ImmersiveIconButton(
    onClick: () -> Unit,
    active: Boolean = false,
    activeColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) activeColor.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------- Apple Music 风格歌词列 ----------

private const val MANUAL_LYRIC_SCROLL_HOLD_MS = 2_000L
private const val CLICK_SEEK_SCROLL_HOLD_MS = 1_100L

/**
 * 把 player 的 30Hz 离散 raw 位置流，转成按帧平滑且**始终跟踪音频**的连续位置 ——
 * 字符级 sweep / lift wave 的时间源。
 *
 * 设计：软低通追踪 + 单调护栏
 *   - 每帧算 `target = lastRaw + (now - lastRawNanos)`（预测此刻"真实"位置）
 *   - `smoothed += (target - smoothed) * 0.4`（指数低通，τ ≈ 40ms）
 *   - 但 **只允许前进**：如果 target 因为系统抖动暂时倒退（一拍 raw 来得慢，下一拍来得快这种情况），
 *     smoothed 原地等，不跟着倒退。这是消除"sweep 在颤"的关键。
 *   - 仅 seek（|diff| > 500ms）时硬锚定 —— 换歌、跳进度。
 *
 * 稳态滞后 ≈ 40ms（视觉上无感，远低于人眼 80ms 同步阈值）；不漂移、不抖动。
 */
private data class SmoothPositionAnchor(
    val positionMs: Long,
    val capturedAtNanos: Long,
    val resetToken: Long,
    val canExtrapolate: Boolean,
)

private fun lyricColumnSessionKey(sessionId: String?, lines: List<PipoLyricLine>): String {
    val first = lines.firstOrNull()
    val last = lines.lastOrNull()
    return "${sessionId.orEmpty()}:${lines.size}:${first?.startMs}:${first?.text.hashCode()}:${last?.startMs}:${last?.text.hashCode()}"
}

@Composable
private fun rememberSmoothPositionMs(
    rawPositionMs: Long,
    isPlaying: Boolean,
    sessionKey: String,
): State<Long> {
    val output = remember(sessionKey) { mutableStateOf(rawPositionMs) }
    // raw 来一拍就更新 anchor（值 + 当时 monotonic nanos），coroutine 内按帧读最新。
    val rawAnchor = remember(sessionKey) {
        mutableStateOf(
            SmoothPositionAnchor(
                positionMs = rawPositionMs,
                capturedAtNanos = System.nanoTime(),
                resetToken = 0L,
                canExtrapolate = rawPositionMs > SMOOTH_POSITION_START_GUARD_MS,
            )
        )
    }
    LaunchedEffect(sessionKey) {
        DiagnosticsLogStore.record(
            area = "lyrics_speed",
            event = "smooth_session",
            fields = mapOf(
                "sessionKeyHash" to sessionKey.hashCode(),
                "rawPositionMs" to rawPositionMs,
                "isPlaying" to isPlaying,
                "canExtrapolate" to rawAnchor.value.canExtrapolate,
            ),
        )
    }
    LaunchedEffect(rawPositionMs, isPlaying, sessionKey) {
        val previous = rawAnchor.value
        val jumpedBackward = rawPositionMs + SMOOTH_POSITION_BACKWARD_RESET_MS < previous.positionMs
        val jumpedForward = rawPositionMs > previous.positionMs + SMOOTH_POSITION_SEEK_RESET_MS
        val shouldReset = jumpedBackward || jumpedForward
        val advanced = rawPositionMs > previous.positionMs
        val canExtrapolate = if (shouldReset) {
            rawPositionMs > SMOOTH_POSITION_START_GUARD_MS
        } else {
            previous.canExtrapolate || advanced || rawPositionMs > SMOOTH_POSITION_START_GUARD_MS
        }
        rawAnchor.value = SmoothPositionAnchor(
            positionMs = rawPositionMs,
            capturedAtNanos = System.nanoTime(),
            resetToken = if (shouldReset) previous.resetToken + 1L else previous.resetToken,
            canExtrapolate = canExtrapolate,
        )
        if (shouldReset) {
            DiagnosticsLogStore.record(
                area = "lyrics_speed",
                event = "smooth_anchor_reset",
                fields = mapOf(
                    "sessionKeyHash" to sessionKey.hashCode(),
                    "reason" to when {
                        jumpedBackward -> "backward"
                        jumpedForward -> "forward_seek"
                        else -> "unknown"
                    },
                    "previousRawMs" to previous.positionMs,
                    "newRawMs" to rawPositionMs,
                    "currentOutputMs" to output.value,
                    "isPlaying" to isPlaying,
                    "canExtrapolate" to canExtrapolate,
                    "resetToken" to rawAnchor.value.resetToken,
                ),
            )
        } else if (!previous.canExtrapolate && canExtrapolate) {
            DiagnosticsLogStore.record(
                area = "lyrics_speed",
                event = "smooth_extrapolate_enabled",
                fields = mapOf(
                    "sessionKeyHash" to sessionKey.hashCode(),
                    "rawPositionMs" to rawPositionMs,
                    "previousRawMs" to previous.positionMs,
                    "isPlaying" to isPlaying,
                ),
            )
        }
        val outputLagMs = rawPositionMs - output.value
        if (isPlaying && !shouldReset && outputLagMs > SMOOTH_POSITION_OUTPUT_STALE_MS) {
            DiagnosticsLogStore.record(
                area = "lyrics_speed",
                event = "smooth_output_catchup",
                fields = mapOf(
                    "sessionKeyHash" to sessionKey.hashCode(),
                    "rawPositionMs" to rawPositionMs,
                    "previousOutputMs" to output.value,
                    "outputLagMs" to outputLagMs,
                    "canExtrapolate" to canExtrapolate,
                ),
            )
            output.value = rawPositionMs
        }
        if (!isPlaying || shouldReset) {
            output.value = rawPositionMs
        }
    }

    LaunchedEffect(isPlaying, sessionKey) {
        if (!isPlaying) {
            output.value = rawAnchor.value.positionMs
            return@LaunchedEffect
        }
        var smoothed = rawAnchor.value.positionMs.toFloat()
        var seenResetToken = rawAnchor.value.resetToken
        while (true) {
            withFrameNanos { frameNanos ->
                val anchor = rawAnchor.value
                if (anchor.resetToken != seenResetToken) {
                    seenResetToken = anchor.resetToken
                    smoothed = anchor.positionMs.toFloat()
                }
                if (!anchor.canExtrapolate) {
                    smoothed = anchor.positionMs.toFloat()
                    output.value = anchor.positionMs
                    return@withFrameNanos
                }
                val target = anchor.positionMs.toFloat() +
                    (frameNanos - anchor.capturedAtNanos).toFloat() / 1_000_000f
                val diff = target - smoothed
                when {
                    kotlin.math.abs(diff) > SMOOTH_POSITION_FRAME_RESET_MS.toFloat() -> smoothed = target
                    diff < -SMOOTH_POSITION_BACKWARD_RESET_MS.toFloat() -> smoothed = target
                    diff > 0f -> smoothed += diff * SMOOTH_POSITION_FOLLOW_ALPHA // 前进追赶
                    // diff <= 0：target 比 smoothed 落后（线程抖动 / 短暂停拍），原地等。
                }
                output.value = smoothed.toLong()
            }
        }
    }
    return output
}

private const val SMOOTH_POSITION_SEEK_RESET_MS = 1_500L
private const val SMOOTH_POSITION_FRAME_RESET_MS = 1_500L
private const val SMOOTH_POSITION_BACKWARD_RESET_MS = 300L
private const val SMOOTH_POSITION_OUTPUT_STALE_MS = 360L
private const val SMOOTH_POSITION_START_GUARD_MS = 900L
private const val SMOOTH_POSITION_FOLLOW_ALPHA = 0.68f
private const val LYRIC_SWEEP_VISUAL_LEAD_MS = 45L
private const val LYRIC_WORD_LINE_FOCUS_LEAD_MS = 95L
private const val LYRIC_WORD_SCROLL_LOOKAHEAD_MS = 170L

@Composable
internal fun AppleMusicLyricColumn(
    lines: List<PipoLyricLine>,
    sessionId: String? = null,
    activeLyricIndex: Int,
    positionMs: Long,
    isPlaying: Boolean,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    showTranslation: Boolean = false,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 24.dp,
    rowMinHeight: Dp = 60.dp,
    rowVerticalPadding: Dp = 8.dp,
    lyricFontSize: TextUnit = 28.sp,
    lyricLineHeight: TextUnit = 44.sp,
    lyricFontWeight: FontWeight = FontWeight.ExtraBold,
    bottomFadeStart: Float = 0.80f,
    bottomFadeSoftEnd: Float = 0.94f,
) {
    val lyricSessionKey = remember(sessionId, lines) { lyricColumnSessionKey(sessionId, lines) }
    // 把 player 的 30Hz tick 平滑成按帧位置（120Hz 屏丝滑度提升关键）
    val smoothedPositionMs by rememberSmoothPositionMs(positionMs, isPlaying, lyricSessionKey)
    val renderPositionMs = (smoothedPositionMs + LYRIC_SWEEP_VISUAL_LEAD_MS).coerceAtLeast(0L)
    val haptics = LocalHapticFeedback.current
    if (lines.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "歌词加载中…",
                color = fgDim,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }
        return
    }

    // 用 LazyColumn 替代全列渲染 —— 之前用 Column + graphicsLayer.translationY 在长
    // 歌词（80+ 行 × 60dp ≈ 14400px）会超出 GPU 纹理上限 4096px，导致 layer 渲染失败、
    // translationY 不生效，活动行进到第 8 行后就出可视区。LazyColumn 只渲染可视窗口，
    // 没有这个问题。
    //
    // contentPadding.top = 容器高度 18%：活动行落在偏上一点的位置（更接近 Apple Music
    // 那种"焦点行靠上 1/4"的视觉重心），上方 fade 区给得更狠。
    val density = LocalDensity.current
    var containerHeightPx by remember { mutableStateOf(0) }

    val listState = remember(lyricSessionKey) { LazyListState() }
    var manualScrollHoldUntilMs by remember(lyricSessionKey) { mutableStateOf(0L) }
    var clickSeekHoldUntilMs by remember(lyricSessionKey) { mutableStateOf(0L) }
    var clickSeekFocusIndex by remember(lyricSessionKey) { mutableStateOf<Int?>(null) }
    val focusOffsetPx = (containerHeightPx * 0.11f).toInt()
    val manualFocusIndex by remember(listState, focusOffsetPx) {
        derivedStateOf {
            if (manualScrollHoldUntilMs <= 0L || focusOffsetPx <= 0) {
                null
            } else {
                listState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    kotlin.math.abs((item.offset + item.size / 2) - focusOffsetPx)
                }?.index
            }
        }
    }

    // ---- 滚动 / 焦点切换的 lookahead ----
    // 之前是 500ms：滚动比 alpha/blur 早 500ms 触发。Spring 滚动 ~360ms 内就稳了，
    // 但 alpha/blur 要等到 sing boundary 才开始（再 250ms），中间有 ~140ms 的"空窗"——
    // 用户看到的现象就是"滚上去 → 顿一下 → 突然加模糊"。
    // 行级歌词没有逐字时间轴，高亮本身有 250ms alpha 过渡；焦点需要稍微提前启动。
    // YRC 的"行焦点"可提前，逐字 sweep 仍走真实 token timing。
    val focusLeadMs = remember(lines) { LyricTiming.focusLeadMs(lines) }
    val hasWordTiming = remember(lines) { lines.any { it.chars.isNotEmpty() } }
    val lineFocusLeadMs = if (hasWordTiming) LYRIC_WORD_LINE_FOCUS_LEAD_MS else focusLeadMs
    val scrollLookaheadMs = if (hasWordTiming) LYRIC_WORD_SCROLL_LOOKAHEAD_MS else maxOf(100L, focusLeadMs)
    val lineClockPositionMs = if (hasWordTiming) renderPositionMs else positionMs
    val scrollClockPositionMs = if (hasWordTiming) renderPositionMs else positionMs
    val scrollTargetIdx = remember(scrollClockPositionMs, lines, scrollLookaheadMs) {
        if (lines.isEmpty()) 0
        else lines.indexOfLast { line -> scrollClockPositionMs + scrollLookaheadMs >= LyricTiming.audioStartMs(line) }
            .coerceAtLeast(0)
    }
    // 活动行也用按帧平滑后的时钟直接判定，避免 ViewModel 的 position tick 让快歌切句慢半拍。
    // 这里只提前行焦点；逐词/逐字扫色仍由 token timing 控制。
    val playbackActiveIdx = if (lineClockPositionMs + lineFocusLeadMs >= LyricTiming.audioStartMs(lines.first())) {
        lines.indexOfLast { line -> lineClockPositionMs + lineFocusLeadMs >= LyricTiming.audioStartMs(line) }
            .coerceAtLeast(0)
    } else -1
    val effectiveActiveIdx = manualFocusIndex ?: playbackActiveIdx
    LaunchedEffect(scrollTargetIdx, clickSeekFocusIndex, clickSeekHoldUntilMs) {
        val heldIndex = clickSeekFocusIndex ?: return@LaunchedEffect
        if (scrollTargetIdx >= heldIndex && clickSeekHoldUntilMs > 0L) {
            clickSeekHoldUntilMs = 0L
            clickSeekFocusIndex = null
        }
    }

    // ---- Apple Music 风滚动：~720ms FastOutSlowIn，CSS ease 同曲线 ----
    // animateScrollToItem 默认是 spring，对长歌词来说太弹；这里手写 tween 让滚动更线性 + 后段缓收。
    LaunchedEffect(scrollTargetIdx, containerHeightPx, manualScrollHoldUntilMs, clickSeekHoldUntilMs) {
        val heldClickIndex = clickSeekFocusIndex
        val now = SystemClock.elapsedRealtime()
        if (
            heldClickIndex != null &&
            clickSeekHoldUntilMs > now &&
            scrollTargetIdx < heldClickIndex
        ) {
            return@LaunchedEffect
        }

        val targetIdx = scrollTargetIdx
        if (targetIdx !in lines.indices || containerHeightPx == 0) return@LaunchedEffect
        val waitMs = manualScrollHoldUntilMs - now
        val returningFromManualScroll = waitMs > 0L
        if (waitMs > 0L) delay(waitMs)
        // 焦点位置：容器顶 11%，正好让活动行上方只露出 1 句历史歌词
        val info = listState.layoutInfo
        val visibleHit = info.visibleItemsInfo.firstOrNull { it.index == targetIdx }
        if (visibleHit == null) {
            if (returningFromManualScroll) {
                listState.animateScrollToItem(targetIdx, scrollOffset = -focusOffsetPx)
                if (manualScrollHoldUntilMs <= SystemClock.elapsedRealtime()) {
                    manualScrollHoldUntilMs = 0L
                }
            } else {
                // 正常播放跨很远切句时先 snap 到大致位置，避免一段超长滚动追不上音频。
                listState.scrollToItem(targetIdx, scrollOffset = -focusOffsetPx)
            }
            return@LaunchedEffect
        }
        val delta = (visibleHit.offset - focusOffsetPx).toFloat()
        if (kotlin.math.abs(delta) <= 1f) {
            if (returningFromManualScroll && manualScrollHoldUntilMs <= SystemClock.elapsedRealtime()) {
                manualScrollHoldUntilMs = 0L
            }
            return@LaunchedEffect
        }
        // tween 720ms FastOutSlowIn 跑增量积分：每帧给 LazyListState 一个 scrollBy(d-prev)
        var prev = 0f
        animate(
            initialValue = 0f,
            targetValue = delta,
            // Apple Music 行级滚动：从 AMLL 的 posY spring（mass=0.9 damping=15 stiffness=90）
            // 翻译过来 → ζ≈0.83, k≈100。spring 比 tween 的 cubic-bezier 更"有重量"——
            // 起步沉、收尾微弹，是 Apple Music 切句时那种"被拽过去"的物理感的核心。
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 190f),
        ) { value, _ ->
            val step = value - prev
            prev = value
            // suspend 不能直接调，但 listState.dispatchRawDelta 是同步的
            listState.dispatchRawDelta(step)
        }
        if (returningFromManualScroll && manualScrollHoldUntilMs <= SystemClock.elapsedRealtime()) {
            manualScrollHoldUntilMs = 0L
        }
    }

    // 上方留 11%（约 1 行 + 少量呼吸空间），让活动行上方只露出 1 句历史歌词
    val topPadDp = with(density) { (containerHeightPx * 0.11f).toDp() }
    val bottomPadDp = with(density) { (containerHeightPx * 0.89f).toDp() }
    val bottomSolidStop = bottomFadeStart.coerceIn(0.60f, 0.96f)
    val bottomSoftStop = bottomFadeSoftEnd.coerceIn(bottomSolidStop, 0.99f)
    val activeLineSource = if (manualFocusIndex != null) "manual" else "playback"
    val lineEntryCounts = remember(lyricSessionKey) { mutableMapOf<Int, Int>() }
    val tokenEntryCounts = remember(lyricSessionKey) { mutableMapOf<String, Int>() }
    val activeTokenLog = lines.getOrNull(playbackActiveIdx)
        ?.let { line -> activeLyricTimingForLog(playbackActiveIdx, line, renderPositionMs) }
    val activeTokenLogKey = activeTokenLog
        ?.let { "${it.lineIndex}:${it.tokenIndex}:${it.partIndex}" }

    LaunchedEffect(lyricSessionKey) {
        val firstLine = lines.firstOrNull()
        val secondLine = lines.getOrNull(1)
        DiagnosticsLogStore.record(
            area = "lyrics_speed",
            event = "session_lines",
            fields = mapOf(
                "sessionId" to sessionId,
                "sessionKeyHash" to lyricSessionKey.hashCode(),
                "lineCount" to lines.size,
                "wordLineCount" to lines.count { it.chars.isNotEmpty() },
                "tokenCount" to lines.sumOf { it.chars.size },
                "timingPartCount" to lines.sumOf { line ->
                    line.chars.sumOf { it.timingParts.size.coerceAtLeast(1) }
                },
                "focusLeadMs" to focusLeadMs,
                "lineFocusLeadMs" to lineFocusLeadMs,
                "scrollLookaheadMs" to scrollLookaheadMs,
                "hasWordTiming" to hasWordTiming,
                "firstLineStartMs" to firstLine?.startMs,
                "firstAudioStartMs" to firstLine?.let { LyricTiming.audioStartMs(it) },
                "firstLineDurationMs" to firstLine?.durationMs,
                "firstLineText" to firstLine?.text?.let(::lyricLogPreview),
                "secondLineStartMs" to secondLine?.startMs,
                "secondAudioStartMs" to secondLine?.let { LyricTiming.audioStartMs(it) },
                "secondLineText" to secondLine?.text?.let(::lyricLogPreview),
            ),
        )
    }

    LaunchedEffect(lyricSessionKey, effectiveActiveIdx, activeLineSource) {
        if (effectiveActiveIdx !in lines.indices) return@LaunchedEffect
        val line = lines[effectiveActiveIdx]
        val entryCount = (lineEntryCounts[effectiveActiveIdx] ?: 0) + 1
        lineEntryCounts[effectiveActiveIdx] = entryCount
        val fields = mapOf(
            "sessionId" to sessionId,
            "sessionKeyHash" to lyricSessionKey.hashCode(),
            "lineIndex" to effectiveActiveIdx,
            "entryCount" to entryCount,
            "source" to activeLineSource,
            "rawPositionMs" to positionMs,
            "smoothedPositionMs" to smoothedPositionMs,
            "renderPositionMs" to renderPositionMs,
            "activeLyricIndex" to activeLyricIndex,
            "playbackActiveIdx" to playbackActiveIdx,
            "scrollTargetIdx" to scrollTargetIdx,
            "scrollLookaheadMs" to scrollLookaheadMs,
            "focusLeadMs" to focusLeadMs,
            "lineFocusLeadMs" to lineFocusLeadMs,
            "lineClockPositionMs" to lineClockPositionMs,
            "lineStartMs" to line.startMs,
            "lineDurationMs" to line.durationMs,
            "audioStartMs" to LyricTiming.audioStartMs(line),
            "audioEndMs" to lyricLineAudioEndMsForLog(line),
            "tokenCount" to line.chars.size,
            "timingPartCount" to line.chars.sumOf { it.timingParts.size.coerceAtLeast(1) },
            "firstTokenStartMs" to line.chars.firstOrNull()?.startMs,
            "lastTokenEndMs" to line.chars.maxOfOrNull { it.startMs + it.durationMs },
            "text" to lyricLogPreview(line.text),
        )
        DiagnosticsLogStore.record(
            area = "lyrics_speed",
            event = "active_line",
            fields = fields,
        )
        if (entryCount > 1) {
            DiagnosticsLogStore.record(
                area = "lyrics_speed",
                event = "active_line_reentered",
                fields = fields,
            )
        }
    }

    LaunchedEffect(lyricSessionKey, activeTokenLogKey) {
        val tokenLog = activeTokenLog ?: return@LaunchedEffect
        val tokenKey = activeTokenLogKey ?: return@LaunchedEffect
        val entryCount = (tokenEntryCounts[tokenKey] ?: 0) + 1
        tokenEntryCounts[tokenKey] = entryCount
        val fields = mapOf(
            "sessionId" to sessionId,
            "sessionKeyHash" to lyricSessionKey.hashCode(),
            "lineIndex" to tokenLog.lineIndex,
            "tokenIndex" to tokenLog.tokenIndex,
            "partIndex" to tokenLog.partIndex,
            "entryCount" to entryCount,
            "rawPositionMs" to positionMs,
            "smoothedPositionMs" to smoothedPositionMs,
            "renderPositionMs" to renderPositionMs,
            "tokenStartMs" to tokenLog.tokenStartMs,
            "tokenDurationMs" to tokenLog.tokenDurationMs,
            "tokenProgress" to tokenLog.tokenProgress,
            "partStartMs" to tokenLog.partStartMs,
            "partDurationMs" to tokenLog.partDurationMs,
            "effectivePartDurationMs" to tokenLog.effectivePartDurationMs,
            "partProgress" to tokenLog.partProgress,
            "tokenText" to lyricLogPreview(tokenLog.tokenText, max = 40),
            "partText" to lyricLogPreview(tokenLog.partText, max = 40),
        )
        DiagnosticsLogStore.record(
            area = "lyrics_speed",
            event = "active_token",
            fields = fields,
        )
        if (entryCount > 1) {
            DiagnosticsLogStore.record(
                area = "lyrics_speed",
                event = "active_token_reentered",
                fields = fields,
            )
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerHeightPx = it.height }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 焦点行落在 11% —— 上方只露 1 句历史歌词，那一句要狠狠淡出。
                // fade band 收紧到 0..14%，恰好覆盖那 1 句的范围。
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.05f to Color.Black.copy(alpha = 0.10f),
                            0.10f to Color.Black.copy(alpha = 0.50f),
                            0.14f to Color.Black,
                            bottomSolidStop to Color.Black,
                            bottomSoftStop to Color.Black.copy(alpha = 0.4f),
                            1f to Color.Transparent,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .pointerInput(Unit) {
                    val touchSlopPx = 8.dp.toPx()
                    awaitPointerEventScope {
                        var downY: Float? = null
                        var hasDragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed || it.previousPressed }
                                ?: continue
                            if (change.pressed && !change.previousPressed) {
                                downY = change.position.y
                                hasDragged = false
                            }
                            if (change.pressed) {
                                val startY = downY
                                if (
                                    startY != null &&
                                    kotlin.math.abs(change.position.y - startY) > touchSlopPx
                                ) {
                                    hasDragged = true
                                }
                            }
                            if (hasDragged) {
                                manualScrollHoldUntilMs =
                                    SystemClock.elapsedRealtime() + MANUAL_LYRIC_SCROLL_HOLD_MS
                            }
                            if (!change.pressed && change.previousPressed) {
                                if (hasDragged) {
                                    manualScrollHoldUntilMs =
                                        SystemClock.elapsedRealtime() + MANUAL_LYRIC_SCROLL_HOLD_MS
                                }
                                downY = null
                                hasDragged = false
                            }
                        }
                    }
                },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = topPadDp,
                bottom = bottomPadDp,
            ),
            userScrollEnabled = true,
        ) {
            itemsIndexed(
                items = lines,
                key = { idx, line -> lyricLineRenderKey(idx, line) },
            ) { idx, line ->
                val isManualFocusLine = manualFocusIndex == idx
                val distance = if (effectiveActiveIdx < 0) {
                    idx + 1   // 整列都按"未来"摊开，line 0 距离 1, line 1 距离 2, ...
                } else {
                    kotlin.math.abs(idx - effectiveActiveIdx)
                }
                // heightIn(min) 而不是 height(fixed) —— 长歌词自然换行成两行不被切。
                // LazyColumn 内部支持变高 item，animateScrollToItem 仍能按 idx 定位。
                val hasCompanionCue = hasVisibleCompanionLyric(line, smoothedPositionMs)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowMinHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                manualScrollHoldUntilMs = 0L
                                clickSeekFocusIndex = idx
                                clickSeekHoldUntilMs =
                                    SystemClock.elapsedRealtime() + CLICK_SEEK_SCROLL_HOLD_MS
                                onSeekToMs(LyricTiming.audioStartMs(line).coerceAtLeast(0L))
                            },
                        ),
                ) {
                    AppleMusicLyricRow(
                        line = line,
                        isActive = idx == effectiveActiveIdx,
                        isPast = effectiveActiveIdx >= 0 && idx < effectiveActiveIdx,
                        distance = distance,
                        // 活动行的字符级 sweep / lift 用按帧外插的 smoothed 位置，颤动消失；
                        // 非活动行其实只用 isActive/isPast 派生状态，跟 positionMs 精度关系不大。
                        positionMs = if ((isManualFocusLine || idx < effectiveActiveIdx) && !hasCompanionCue) {
                            completedLinePositionMs(line)
                        } else {
                            renderPositionMs
                        },
                        fg = fg,
                        fgDim = fgDim,
                        fgUnsung = fgUnsung,
                        showTranslation = showTranslation,
                        fontSize = lyricFontSize,
                        lineHeight = lyricLineHeight,
                        fontWeight = lyricFontWeight,
                        verticalPadding = rowVerticalPadding,
                    )
                }
            }
        }
    }
}

private fun lyricLineRenderKey(index: Int, line: PipoLyricLine): String {
    return "${line.startMs}:$index:${line.text.hashCode()}"
}

private fun completedLinePositionMs(line: PipoLyricLine): Long {
    val timedCompanions = line.timedCompanionLines()
    val charEnd = (line.chars + timedCompanions.flatMap { it.chars })
        .maxOfOrNull { it.startMs + it.durationMs }
    val lineEnd = maxOf(
        line.startMs + line.durationMs,
        timedCompanions.maxOfOrNull { it.startMs + it.durationMs } ?: line.startMs,
    )
    return maxOf(charEnd ?: lineEnd, lineEnd, line.startMs + 1L)
}

private data class LyricSpeedTokenLog(
    val lineIndex: Int,
    val tokenIndex: Int,
    val partIndex: Int,
    val tokenText: String,
    val partText: String,
    val tokenStartMs: Long,
    val tokenDurationMs: Long,
    val tokenProgress: Float,
    val partStartMs: Long,
    val partDurationMs: Long,
    val effectivePartDurationMs: Long,
    val partProgress: Float,
)

private fun activeLyricTimingForLog(
    lineIndex: Int,
    line: PipoLyricLine,
    positionMs: Long,
): LyricSpeedTokenLog? {
    line.chars.forEachIndexed { tokenIndex, token ->
        val parts = token.timingPartsForProgress()
        parts.forEachIndexed { partIndex, part ->
            val effectiveDurationMs = effectiveTimingPartDurationMs(parts, partIndex)
            val partEndMs = part.startMs + effectiveDurationMs
            if (positionMs >= part.startMs && positionMs < partEndMs) {
                val partProgress = ((positionMs - part.startMs).toFloat() / effectiveDurationMs.toFloat())
                    .coerceIn(0f, 1f)
                return LyricSpeedTokenLog(
                    lineIndex = lineIndex,
                    tokenIndex = tokenIndex,
                    partIndex = partIndex,
                    tokenText = token.text,
                    partText = part.text,
                    tokenStartMs = token.startMs,
                    tokenDurationMs = token.durationMs,
                    tokenProgress = token.progress(positionMs),
                    partStartMs = part.startMs,
                    partDurationMs = part.durationMs,
                    effectivePartDurationMs = effectiveDurationMs,
                    partProgress = partProgress,
                )
            }
        }
    }
    return null
}

private fun effectiveTimingPartDurationMs(
    parts: List<app.pipo.nativeapp.data.PipoLyricTimingPart>,
    index: Int,
): Long {
    val part = parts[index]
    val durationMs = part.durationMs.coerceAtLeast(1L)
    val nextStartMs = parts.getOrNull(index + 1)?.startMs
    return nextStartMs
        ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(durationMs) }
        ?: durationMs
}

private fun lyricLineAudioEndMsForLog(line: PipoLyricLine): Long {
    val charEnd = (line.chars + line.companionLines.flatMap { it.chars })
        .maxOfOrNull { it.startMs + it.durationMs }
    val lineEnd = maxOf(
        line.startMs + line.durationMs,
        line.companionLines.maxOfOrNull { it.startMs + it.durationMs } ?: line.startMs,
    )
    return maxOf(charEnd ?: lineEnd, lineEnd)
}

private fun lyricLogPreview(text: String, max: Int = 64): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= max) compact else compact.take(max) + "..."
}

@Composable
private fun AppleMusicLyricRow(
    line: PipoLyricLine,
    isActive: Boolean,
    isPast: Boolean,    // idx < activeLyricIndex —— 已唱完的行
    distance: Int,
    positionMs: Long,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    showTranslation: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    verticalPadding: Dp,
) {
    var wasActive by remember(line.startMs, line.text) { mutableStateOf(isActive) }
    var becamePastAtRealtime by remember(line.startMs, line.text) { mutableStateOf<Long?>(null) }
    LaunchedEffect(isActive, isPast) {
        val now = SystemClock.elapsedRealtime()
        if (wasActive && !isActive && isPast) {
            becamePastAtRealtime = now
        }
        if (isActive || !isPast) {
            becamePastAtRealtime = null
        }
        wasActive = isActive
    }

    val cssEase = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }
    val lineAudioStartMs = remember(line.startMs, line.chars) { LyricTiming.audioStartMs(line) }
    val shouldSnapActiveEffects = isActive && positionMs - lineAudioStartMs > LYRIC_ACTIVE_ENTRY_SNAP_MS
    val timedCompanions = line.timedCompanionLines()
    val translationLines = if (showTranslation) line.translationLines() else emptyList()
    val hasCompanionCue = timedCompanions.any {
        shouldRenderCompanionLyric(it, positionMs) && !isCompanionLyricPast(it, positionMs)
    }
    // 行级焦点过渡：整行作为单一图层平滑进入焦点。
    // 焦点感靠 alpha 对比 + 字符级 ramp 上浮，不靠 scale。
    //
    // alpha 关键约束：**past line（distance=1）必须比 fgUnsung（active 行未唱字符）更暗**。
    // pickFgUnsung 现在是 0.40（深底）/ 0.43（浅底），所以 distance=1 必须 < 0.40。
    // 整体提一档，过去/未来行更亮更可读，仍守住"past 不亮于 active 未唱"红线。
    val baseTargetAlpha = if (hasCompanionCue) {
        1.0f
    } else {
        when (distance) {
            0 -> 1.0f
            1 -> 0.36f
            2 -> 0.27f
            3 -> 0.20f
            4 -> 0.15f
            else -> 0.11f
        }
    }
    val targetAlpha = remember(
        baseTargetAlpha,
        becamePastAtRealtime,
        distance,
        isPast,
        positionMs,
        cssEase,
    ) {
        val enteredAt = becamePastAtRealtime
        if (isPast && distance == 1 && enteredAt != null) {
            val elapsed = (SystemClock.elapsedRealtime() - enteredAt).coerceAtLeast(0L)
            val t = (elapsed.toFloat() / 380f).coerceIn(0f, 1f)
            val eased = cssEase.transform(t)
            val exitStartAlpha = 0.74f
            baseTargetAlpha + (exitStartAlpha - baseTargetAlpha) * (1f - eased)
        } else {
            baseTargetAlpha
        }
    }
    // 模糊：只给"列首尾"（distance ≥ 2 的远端行）。
    // 之前给所有非活动行加 blur 是错的 —— Apple Music 实际是焦点附近清晰、远端模糊（焦距感），
    // 不是"非焦点全模糊"。distance=1 的邻句（上 / 下一句）保持清晰，给读者扫读余光。
    val targetBlur = when {
        hasCompanionCue -> 0f
        distance == 0 -> 0f
        distance == 1 -> 0f          // 紧邻焦点：不模糊，只是 alpha 减弱
        distance == 2 -> 1.2f        // 开始模糊
        else -> 2f                    // 远端：完整 2dp 模糊
    }
    // ---- Apple Music 行级过渡曲线（从 AMLL lyric-player.module.css 实测得出）----
    // AMLL 真正的 CSS 是：
    //     transition: opacity 0.25s, filter 0.2s
    // 默认 CSS `ease` = cubic-bezier(0.25, 0.1, 0.25, 1)。**根本不是 spring**。
    // spring 物理感只用在滚动 posY 上（行间相对位移），切句的 alpha/blur/lift 都是 tween。
    // 之前误把 spring 全套给 alpha → 视觉上有 bouncy 感，不是 Apple Music。
    val alphaSpec: AnimationSpec<Float> = remember(cssEase) {
        tween(durationMillis = 170, easing = cssEase)
    }
    val blurSpec: AnimationSpec<Float> = remember(cssEase) {
        tween(durationMillis = 140, easing = cssEase)
    }
    val snapSpec: AnimationSpec<Float> = remember { snap() }
    val rowAlphaSpec = if (shouldSnapActiveEffects) snapSpec else alphaSpec
    val rowBlurSpec = if (shouldSnapActiveEffects) snapSpec else blurSpec
    val liftSpec = if (shouldSnapActiveEffects) snapSpec else alphaSpec
    val rowAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = rowAlphaSpec,
        label = "lyricAlpha",
    )
    // 行级 translateY：归零，让 scale + alpha 承担"焦点层次"。
    // Apple Music / AMLL 的做法就是这样 —— 非活动行不靠位移区分，靠**比例缩小 + 透明度降低**，
    // 加上 scale 的弹簧落地感，整体"对齐感"比原来"future +2dp / past -1dp"那种位移要干净。
    val isYrcLine = line.chars.isNotEmpty()
    val isActiveYrc = isActive && isYrcLine
    val isActiveLrc = isActive && !isYrcLine
    val rowTranslateYDp = 0f

    // ---- Lift envelope ----
    // active YRC 时从 0 平滑上升到 1（per-char ramp 整体强度从无到全），离开 active 时再降回 0。
    // 跟 alpha 同节奏（250ms CSS ease），不带物理弹跳 —— Apple Music 的切句没有 bounce 感。
    val liftEnvelope by animateFloatAsState(
        targetValue = if (isActiveYrc) 1f else 0f,
        animationSpec = liftSpec,
        label = "liftEnvelope",
    )
    val rowBlurDp by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = rowBlurSpec,
        label = "lyricBlur",
    )

    // ---- AMLL 调试日志：active YRC 行每 ~120ms dump 一次动画快照 ----
    // 通过 withFrameNanos 在 Choreographer 节拍上采样，读取动画 state 的当前值，
    // 不阻塞渲染。导出走「设置 → 诊断日志 → 分享 txt」。
    val rowLogKey = remember(line.startMs, line.text) { line.text.hashCode() }
    LaunchedEffect(rowLogKey, isActive, isYrcLine) {
        if (!isActive || !isYrcLine) return@LaunchedEffect
        var lastDumpNanos = 0L
        AmllLyricLogger.forceRecord(
            event = "row_active_enter",
            fields = mapOf(
                "lineHash" to rowLogKey,
                "lineStartMs" to line.startMs,
                "lineDurationMs" to line.durationMs,
                "audioStartMs" to LyricTiming.audioStartMs(line),
                "tokenCount" to line.chars.size,
                "lineText" to lyricLogPreview(line.text),
                "distance" to distance,
            ),
        )
        try {
            while (true) {
                withFrameNanos { nowNanos ->
                    if (nowNanos - lastDumpNanos < 120_000_000L) return@withFrameNanos
                    lastDumpNanos = nowNanos
                    val curPos = positionMs
                    val activeToken = line.chars.firstOrNull { ch ->
                        val p = ch.progress(curPos)
                        p > 0f && p < 1f
                    }
                    val activeTokenDur = activeToken?.durationMs ?: -1L
                    val activeTokenProgress = activeToken?.progress(curPos) ?: -1f
                    AmllLyricLogger.maybeRecord(
                        event = "row_frame",
                        key = rowLogKey.toString(),
                        fields = {
                            mapOf(
                                "lineHash" to rowLogKey,
                                "positionMs" to curPos,
                                "lineStartMs" to line.startMs,
                                "lineDurationMs" to line.durationMs,
                                "lineElapsedMs" to (curPos - line.startMs),
                                "targetAlpha" to targetAlpha,
                                "rowAlpha" to rowAlpha,
                                "rowBlurDp" to rowBlurDp,
                                "liftEnvelope" to liftEnvelope,
                                "shouldSnap" to shouldSnapActiveEffects,
                                "activeTokenIdx" to (activeToken?.let { line.chars.indexOf(it) } ?: -1),
                                "activeTokenText" to (activeToken?.text?.let { lyricLogPreview(it, max = 24) } ?: ""),
                                "activeTokenStartMs" to (activeToken?.startMs ?: -1L),
                                "activeTokenDurMs" to activeTokenDur,
                                "activeTokenProgress" to activeTokenProgress,
                            )
                        },
                    )
                }
            }
        } finally {
            AmllLyricLogger.forceRecord(
                event = "row_active_exit",
                fields = mapOf(
                    "lineHash" to rowLogKey,
                    "lineStartMs" to line.startMs,
                    "rowAlphaAtExit" to rowAlpha,
                    "liftEnvelopeAtExit" to liftEnvelope,
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rowAlpha
                translationY = rowTranslateYDp.dp.toPx()
            }
            // Modifier.blur 在 API 31+ 生效；旧版本静默忽略，不影响其他动效。
            .blur(rowBlurDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .padding(vertical = verticalPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(durationMillis = 180, easing = cssEase),
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            AppleMusicLyricText(
                line = line,
                isActive = isActive,
                isPast = isPast,
                positionMs = positionMs,
                fg = fg,
                fgUnsung = fgUnsung,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight,
                liftEnvelope = liftEnvelope,
            )
            timedCompanions.forEach { companion ->
                if (shouldRenderCompanionLyric(companion, positionMs)) {
                    val companionActive = isCompanionLyricActive(companion, positionMs)
                    AppleMusicLyricText(
                        line = companion,
                        isActive = companionActive,
                        isPast = isCompanionLyricPast(companion, positionMs),
                        positionMs = positionMs,
                        fg = fg,
                        fgUnsung = fgUnsung,
                        fontSize = fontSize * 0.76f,
                        lineHeight = lineHeight * 0.76f,
                        fontWeight = FontWeight.SemiBold,
                        liftEnvelope = if (companionActive) 1f else liftEnvelope,
                    )
                }
            }
            translationLines.forEach { translation ->
                AppleMusicTranslationText(
                    line = translation,
                    isActive = isActive,
                    color = translationLyricColor(
                        isActive = isActive,
                        isPast = isPast,
                        fg = fg,
                        fgDim = fgDim,
                    ),
                    fontSize = fontSize * 0.64f,
                    lineHeight = lineHeight * 0.66f,
                )
            }
        }
    }
}

private fun shouldRenderCompanionLyric(
    line: PipoLyricLine,
    positionMs: Long,
): Boolean {
    return positionMs >= LyricTiming.audioStartMs(line) - COMPANION_LYRIC_LEAD_MS
}

private fun hasVisibleCompanionLyric(line: PipoLyricLine, positionMs: Long): Boolean {
    return line.timedCompanionLines().any { companion ->
        shouldRenderCompanionLyric(companion, positionMs) && !isCompanionLyricPast(companion, positionMs)
    }
}

private fun isCompanionLyricActive(line: PipoLyricLine, positionMs: Long): Boolean {
    return positionMs >= LyricTiming.audioStartMs(line) && positionMs < companionLyricEndMs(line)
}

private fun isCompanionLyricPast(line: PipoLyricLine, positionMs: Long): Boolean {
    return positionMs >= companionLyricEndMs(line)
}

private fun companionLyricEndMs(line: PipoLyricLine): Long {
    val charEnd = line.chars.maxOfOrNull { it.startMs + it.durationMs }
    return maxOf(charEnd ?: line.startMs, line.startMs + line.durationMs)
}

private fun PipoLyricLine.timedCompanionLines(): List<PipoLyricLine> {
    return companionLines.filter { it.role != PipoLyricRole.Translation }
}

private fun PipoLyricLine.translationLines(): List<PipoLyricLine> {
    return companionLines.filter { it.role == PipoLyricRole.Translation }
}

private fun translationLyricColor(
    isActive: Boolean,
    isPast: Boolean,
    fg: Color,
    fgDim: Color,
): Color {
    return when {
        isActive -> lerp(fgDim, fg, 0.36f).copy(alpha = 0.78f)
        isPast -> fgDim.copy(alpha = 0.70f)
        else -> fgDim.copy(alpha = 0.74f)
    }
}

@Composable
private fun AppleMusicTranslationText(
    line: PipoLyricLine,
    isActive: Boolean,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Text(
        text = line.text,
        color = color,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            lineHeight = lineHeight,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
            ),
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                includeFontPadding = false,
            ),
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AppleMusicLyricText(
    line: PipoLyricLine,
    isActive: Boolean,
    isPast: Boolean,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    liftEnvelope: Float,
) {
    val isYrcLine = line.chars.isNotEmpty()
    val isActiveYrc = isActive && isYrcLine
    val isActiveLrc = isActive && !isYrcLine
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
            includeFontPadding = false,
        ),
    )
    val overlayDrawing = isYrcLine && (isActiveYrc || liftEnvelope > 0.001f)
    val baseColor = when {
        isPast -> fg
        isActiveLrc -> fg
        isActiveYrc -> fgUnsung
        else -> fgUnsung
    }
    var layout by remember(line.startMs, line.text) {
        androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null)
    }
    // 慢词逐字母 emphasis 用：每个字母单独 measure 成独立 layout，缓存按 (字符, style) 命中。
    val glyphMeasurer = rememberTextMeasurer(cacheSize = 48)

    Text(
        text = line.text,
        color = baseColor,
        style = style,
        onTextLayout = { layout = it },
        modifier = if (overlayDrawing) {
            Modifier.drawWithContent {
                val cur = layout
                if (cur != null) {
                    drawPerCharLiftedSweep(
                        cur, line.chars, positionMs, fg, fgUnsung,
                        envelope = liftEnvelope,
                        glyphMeasurer = glyphMeasurer,
                        glyphStyle = style,
                    )
                }
            }
        } else Modifier,
    )
}

private const val COMPANION_LYRIC_LEAD_MS = 450L
private const val LYRIC_ACTIVE_ENTRY_SNAP_MS = 450L

/**
 * AGSL（Android Graphics Shading Language，API 33+）shader：lift + color 都在 GPU 里
 * 逐**像素**做，不再有 per-char 离散步进，也不再有 clipRect 边缘 AA 抖动。
 *
 * 关键设计 —— "lift / color 完全发生在 sweep 已经经过的部分"（Apple Music 风格）：
 *   liftRaw = clamp((sweepX − c.x) / liftWindow, 0, 1)
 *     · c.x ≥ sweepX：raw=0 → 字母**不动**（没唱到的字母 100% 静止，不抖）
 *     · c.x < sweepX：raw 在 (0, 1] → 已经经过 sweep 的字母按距离平滑上浮
 *   colorRaw = clamp((sweepX − c.x) / colorWindow, 0, 1)
 *     · 同样的"已经过才染色"逻辑，colorWindow 比 liftWindow 短得多 → 颜色变化更紧贴 sweep
 *
 * 输出走 premultiplied alpha：rgb = fillColor.rgb × src.a，alpha = src.a。
 * src 是文字渲染纹理（用 `fg` 实色画），仅取 src.a 作为字形 mask；颜色完全由 shader 决定。
 */
private const val LIFT_SHADER_SRC = """
uniform shader content;
uniform float sweepX;
uniform float sweepLine;       // -1 = 还没开始；>= lineCount = 全部已唱；其他 = sweep 当前所在行
uniform float lineCount;
uniform half4 lineTops;        // 各行 top y（最多 4 行，未用填 1e9）
uniform half4 lineBottoms;
uniform float liftWindow;
uniform float liftDownPx;
uniform float liftDelta;
uniform float colorWindow;
uniform float envelope;
uniform half4 fg;
uniform half4 fgUnsung;

half4 main(float2 c) {
    // ---- 找当前像素所在行 + 该行 top/bottom（GLSL ES 不允许向量非常量索引，必须展开）----
    float myLine = -1.0;
    float lineTop = 0.0;
    float lineBottom = 0.0;
    if (c.y >= lineTops.x && c.y < lineBottoms.x) {
        myLine = 0.0; lineTop = lineTops.x; lineBottom = lineBottoms.x;
    } else if (c.y >= lineTops.y && c.y < lineBottoms.y) {
        myLine = 1.0; lineTop = lineTops.y; lineBottom = lineBottoms.y;
    } else if (c.y >= lineTops.z && c.y < lineBottoms.z) {
        myLine = 2.0; lineTop = lineTops.z; lineBottom = lineBottoms.z;
    } else if (c.y >= lineTops.w && c.y < lineBottoms.w) {
        myLine = 3.0; lineTop = lineTops.w; lineBottom = lineBottoms.w;
    }

    // 不在任何行（行间空白或者超出范围）→ 透明
    if (myLine < 0.0) return half4(0.0);

    // ---- 根据行 vs sweepLine 决定 progress ----
    float liftRaw;
    float colorRaw;
    if (myLine < sweepLine) {
        liftRaw = 1.0; colorRaw = 1.0;
    } else if (myLine > sweepLine) {
        liftRaw = 0.0; colorRaw = 0.0;
    } else {
        liftRaw = clamp((sweepX - c.x) / liftWindow, 0.0, 1.0);
        colorRaw = clamp((sweepX - c.x) / colorWindow, 0.0, 1.0);
    }

    // smootherstep
    float liftP = liftRaw * liftRaw * liftRaw * (liftRaw * (liftRaw * 6.0 - 15.0) + 10.0);
    float colorP = colorRaw * colorRaw * colorRaw * (colorRaw * (colorRaw * 6.0 - 15.0) + 10.0);

    float ty = (liftDownPx + liftDelta * liftP) * envelope;

    // 把采样限制在本行 box 内 → 防止跨行 bleed
    float sampleY = clamp(c.y - ty, lineTop, lineBottom - 0.001);
    half4 src = content.eval(float2(c.x, sampleY));
    if (src.a < 0.001) return half4(0.0);

    half4 fill = mix(fgUnsung, fg, colorP);
    return half4(fill.rgb * src.a, src.a);
}
"""

/**
 * Sweep 位置：当前字符级时间戳推算出的"颜色画到这条线、这个 x"的坐标。
 * 同时承载 lift wave 的进度计算 —— 字符 i 的 progress 用 sweepX 跟它的 boundingBox 算。
 */
private data class SweepPos(
    val line: Int,
    val x: Float,
    val notStarted: Boolean,
    val allDone: Boolean,
)

private fun computeSweepPos(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    positionMs: Long,
): SweepPos {
    var charIdxBeforeActive = 0
    var activeToken: PipoLyricChar? = null
    var activeProgress = 0f
    for (token in chars) {
        val p = token.progress(positionMs)
        when {
            p >= 1f -> charIdxBeforeActive += token.text.length
            p <= 0f -> { activeToken = null; break }
            else -> { activeToken = token; activeProgress = p; break }
        }
    }
    val total = chars.sumOf { it.text.length }
    val allDone = activeToken == null && charIdxBeforeActive == total
    val notStarted = charIdxBeforeActive == 0 && activeToken == null && !allDone

    return when {
        notStarted -> SweepPos(
            line = 0,
            x = if (layout.lineCount > 0) layout.getLineLeft(0) else 0f,
            notStarted = true,
            allDone = false,
        )
        allDone -> {
            val lastLine = (layout.lineCount - 1).coerceAtLeast(0)
            SweepPos(lastLine, layout.getLineRight(lastLine), notStarted = false, allDone = true)
        }
        activeToken != null -> {
            val tokenStartIdx = charIdxBeforeActive
            val tokenEnd = tokenStartIdx + activeToken.text.length
            val tokenLine = layout.getLineForOffset(tokenStartIdx)
            val startX = if (tokenStartIdx == layout.getLineStart(tokenLine)) {
                layout.getLineLeft(tokenLine)
            } else {
                layout.getBoundingBox(tokenStartIdx - 1).right
            }
            val endX = layout.getBoundingBox(tokenEnd - 1).right
            SweepPos(
                line = tokenLine,
                x = startX + (endX - startX) * activeProgress,
                notStarted = false,
                allDone = false,
            )
        }
        else -> {
            // 字符已唱了若干，当前不在任何 token 内（token 间隙）
            val lastSungIdx = charIdxBeforeActive - 1
            val l = layout.getLineForOffset(lastSungIdx)
            SweepPos(l, layout.getBoundingBox(lastSungIdx).right, notStarted = false, allDone = false)
        }
    }
}

/**
 * 只逐字符做颜色 sweep（不动 ty），shader 路径用 —— shader 会接管 lift。
 */
private fun DrawScope.drawPerCharColorOnly(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
) {
    val sweep = computeSweepPos(layout, chars, positionMs)
    val text = layout.layoutInput.text.text
    for (i in text.indices) {
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue
        val charLine = layout.getLineForOffset(i)
        val colorProgress: Float = when {
            sweep.notStarted -> 0f
            sweep.allDone -> 1f
            charLine < sweep.line -> 1f
            charLine > sweep.line -> 0f
            else -> ((sweep.x - box.left) / (box.right - box.left)).coerceIn(0f, 1f)
        }
        val color = lerp(fgUnsung, fg, colorProgress)
        clipRect(
            left = box.left,
            top = layout.getLineTop(charLine),
            right = box.right,
            bottom = layout.getLineBottom(charLine),
            clipOp = ClipOp.Intersect,
        ) {
            drawText(layout, color = color, topLeft = Offset.Zero)
        }
    }
}

/** CSS `ease-out` = cubic-bezier(0, 0, 0.58, 1) —— AMLL 的 float 动画用的就是这条。 */
private val EaseOutCss: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private data class LyricDrawUnit(
    val timing: PipoLyricChar,
    val line: Int,
    val left: Float,
    val right: Float,
    val inkLeft: Float,
    val inkRight: Float,
    val segmentStartProgress: Float,
    val segmentEndProgress: Float,
    // 该 segment 在整行文本里的字形下标区间 [glyphStart, glyphEnd)，
    // 以及它所属 token 的完整字形区间 —— 慢词逐字母渲染用来定位字母与算扫描位置。
    val glyphStart: Int,
    val glyphEnd: Int,
    val tokenStartChar: Int,
    val tokenCharCount: Int,
)

private fun lyricDrawUnits(layout: TextLayoutResult, chars: List<PipoLyricChar>): List<LyricDrawUnit> {
    val text = layout.layoutInput.text.text
    if (text.isEmpty() || chars.isEmpty()) return emptyList()
    val units = ArrayList<LyricDrawUnit>(chars.size)
    var cursor = 0
    chars.forEach { timing ->
        val start = cursor.coerceAtMost(text.length)
        val end = (cursor + timing.text.length).coerceAtMost(text.length)
        cursor = end
        if (start >= end) return@forEach
        val tokenStart = start
        val tokenEnd = end

        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        if (firstLine != lastLine) {
            var segmentStart = start
            while (segmentStart < end) {
                val line = layout.getLineForOffset(segmentStart)
                val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true).coerceAtLeast(segmentStart + 1))
                addLyricDrawUnit(units, layout, timing, segmentStart, lineEnd, tokenStart, tokenEnd, line)
                segmentStart = lineEnd
            }
        } else {
            addLyricDrawUnit(units, layout, timing, start, end, tokenStart, tokenEnd, firstLine)
        }
    }
    return units
}

private fun addLyricDrawUnit(
    out: MutableList<LyricDrawUnit>,
    layout: TextLayoutResult,
    timing: PipoLyricChar,
    start: Int,
    end: Int,
    tokenStart: Int,
    tokenEnd: Int,
    line: Int,
) {
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var inkLeft = Float.POSITIVE_INFINITY
    var inkRight = Float.NEGATIVE_INFINITY
    val text = layout.layoutInput.text.text
    for (i in start until end) {
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue
        left = minOf(left, box.left)
        right = maxOf(right, box.right)
        if (!text[i].isWhitespace()) {
            inkLeft = minOf(inkLeft, box.left)
            inkRight = maxOf(inkRight, box.right)
        }
    }
    if (left.isFinite() && right.isFinite() && right > left) {
        if (!inkLeft.isFinite() || !inkRight.isFinite() || inkRight <= inkLeft) {
            inkLeft = left
            inkRight = right
        }
        val tokenLength = (tokenEnd - tokenStart).coerceAtLeast(1)
        val startProgress = ((start - tokenStart).toFloat() / tokenLength.toFloat()).coerceIn(0f, 1f)
        val endProgress = ((end - tokenStart).toFloat() / tokenLength.toFloat()).coerceIn(startProgress, 1f)
        out.add(
            LyricDrawUnit(
                timing = timing,
                line = line,
                left = left,
                right = right,
                inkLeft = inkLeft,
                inkRight = inkRight,
                segmentStartProgress = startProgress,
                segmentEndProgress = endProgress,
                glyphStart = start,
                glyphEnd = end,
                tokenStartChar = tokenStart,
                tokenCharCount = (tokenEnd - tokenStart).coerceAtLeast(1),
            ),
        )
    }
}

private data class LyricWordPalette(
    val unsung: Color,
    val sung: Color,
)

private fun lyricWordPalette(fg: Color, fgUnsung: Color): LyricWordPalette {
    return LyricWordPalette(
        // 单词内部也沿用行级对比：未唱用行级未唱色，已唱必须回到完整高亮色。
        // 变色动画仍走原来的横向 sweep，只替换颜色本身。
        unsung = fgUnsung,
        sung = fg,
    )
}

/**
 * 逐视觉单元 "颜色扫描 + 上浮"。
 *   · 普通词：原有的逐单元横向 sweep（fgUnsung → fg 扫色）。
 *   · 慢词（duration ≥ EMP_MIN_DURATION_MS）：交给 drawEmphasizedToken 逐字母渲染，
 *     叠加随音频位置左→右流动的白色辉光 + 呼吸缩放（见 EMP_* 注释）。
 * 文字颜色自始至终只有 fgUnsung / fg 两色 —— 行级（整行）与词级（逐字）一致。
 * 辉光是独立叠加的白色光晕（Shadow），不改变字色本身。
 */
private fun DrawScope.drawPerCharLiftedSweep(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    envelope: Float = 1f,                       // 0 = 完全无 lift；1 = 完整波形
    glyphMeasurer: TextMeasurer,
    glyphStyle: TextStyle,
) {
    val units = lyricDrawUnits(layout, chars)
    val wordPalette = lyricWordPalette(fg, fgUnsung)
    val liftPeakPx = (-0.95f).dp.toPx() * envelope

    val fadeWidthPx = 6.dp.toPx()
    val glyphEdgeOverdrawPx = 5.dp.toPx()

    for (idx in units.indices) {
        val unit = units[idx]
        // 慢词：交给逐字母 emphasis 通道（辉光随音频左→右流动 + 呼吸 + 起伏）。
        if (unit.timing.durationMs >= EMP_MIN_DURATION_MS) {
            // 传入同一可视行的左右邻 token —— drawEmphasizedToken 用它把辉光裁切框
            // 收在本词自己的格子里，不溢到相邻单词 / 相邻行。
            val prevUnit = units.getOrNull(idx - 1)?.takeIf { it.line == unit.line }
            val nextUnit = units.getOrNull(idx + 1)?.takeIf { it.line == unit.line }
            drawEmphasizedToken(
                layout, glyphMeasurer, glyphStyle, unit, prevUnit, nextUnit,
                positionMs, fg, fgUnsung, envelope,
            )
            continue
        }
        val durationMs = unit.timing.durationMs.coerceAtLeast(1L)
        val elapsedMs = positionMs - unit.timing.startMs
        val liftT = lyricLiftEnvelope(durationMs, elapsedMs)
        val ty = snapTextOffset(liftPeakPx * liftT)
        val segmentProgress = lyricUnitSegmentProgress(unit, positionMs)

        // 按视觉单元 clip，而不是按单个字母 clip：
        // 英文单词会作为一个整体上浮和重绘，避免字母之间因 clip 边缘抗锯齿出现裂缝。
        // 字形真实 ink 会略微超出 advance box，j/y/w 这类首尾字母尤其明显；这里只向
        // 单词外侧的空白/行外借一点绘制安全区。英文空格会附在前一个 token 末尾，
        // 所以当前词向左借位时只允许借到 previous.inkRight 之后，不侵入前一个真实字形。
        // lineHeight 44sp > fontSize 28sp，行 box 内部上下各 ~8sp 空白能吸收 ±dp 位移。
        val previous = units.getOrNull(idx - 1)?.takeIf { it.line == unit.line }
        val next = units.getOrNull(idx + 1)?.takeIf { it.line == unit.line }
        val clipLeft = when {
            previous == null -> layout.getLineLeft(unit.line) - glyphEdgeOverdrawPx
            unit.left > previous.inkRight -> maxOf(previous.inkRight, unit.left - glyphEdgeOverdrawPx)
            else -> unit.left
        }
        val clipRight = when {
            next == null -> layout.getLineRight(unit.line) + glyphEdgeOverdrawPx
            next.inkLeft > unit.right -> minOf(next.inkLeft, unit.right + glyphEdgeOverdrawPx)
            else -> unit.right
        }
        val lineTop = layout.getLineTop(unit.line)
        val lineBottom = layout.getLineBottom(unit.line)
        val sweepX = unit.left + (unit.right - unit.left) * segmentProgress
        val sungRight = when {
            segmentProgress <= 0f -> clipLeft
            segmentProgress >= 1f -> clipRight
            else -> sweepX.coerceIn(clipLeft, clipRight)
        }
        clipRect(
            left = clipLeft,
            top = lineTop,
            right = clipRight,
            bottom = lineBottom,
            clipOp = ClipOp.Intersect,
        ) {
            drawText(layout, color = wordPalette.unsung, topLeft = Offset(0f, ty))
        }
        if (segmentProgress > 0f) {
            if (sungRight > clipLeft) {
                clipRect(
                    left = clipLeft,
                    top = lineTop,
                    right = sungRight,
                    bottom = lineBottom,
                    clipOp = ClipOp.Intersect,
                ) {
                    drawText(layout, color = wordPalette.sung, topLeft = Offset(0f, ty))
                }
            }
            if (segmentProgress < 1f) {
                val fadeRight = (sweepX + fadeWidthPx).coerceAtMost(clipRight)
                if (fadeRight > sweepX) {
                    clipRect(
                        left = sweepX.coerceAtLeast(clipLeft),
                        top = lineTop,
                        right = fadeRight,
                        bottom = lineBottom,
                        clipOp = ClipOp.Intersect,
                    ) {
                        drawText(
                            layout,
                            brush = Brush.horizontalGradient(
                                colors = listOf(wordPalette.sung, Color.Transparent),
                                startX = sweepX,
                                endX = sweepX + fadeWidthPx,
                            ),
                            topLeft = Offset(0f, ty),
                        )
                    }
                }
            }
        }
    }
}

private fun lyricUnitSegmentProgress(unit: LyricDrawUnit, positionMs: Long): Float {
    val tokenProgress = unit.timing.progress(positionMs).coerceIn(0f, 1f)
    val start = unit.segmentStartProgress
    val end = unit.segmentEndProgress
    if (tokenProgress <= start) return 0f
    if (tokenProgress >= end) return 1f
    return ((tokenProgress - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
}

private fun lyricLiftEnvelope(durationMs: Long, elapsedMs: Long): Float {
    if (elapsedMs <= 0L) return 0f
    val riseMs = maxOf(280L, minOf(durationMs, 900L))
    return EaseOutCss.transform((elapsedMs.toFloat() / riseMs.toFloat()).coerceIn(0f, 1f))
}

// ---- Apple Music 慢词 emphasis（逐字母辉光 + 呼吸，随音频位置左→右流动）----
//
// 设计要点（实测自 Apple Music 网页版逐帧采样 + 用户反馈"发光不要一闪一闪"）：
//   · 触发：词 duration ≥ EMP_MIN_DURATION_MS（短词不强调）。
//   · 辉光不改字色 —— 字色自始至终只有 fgUnsung→fg 两色（行级 / 词级一致）。
//     发光是叠加的一层白色光晕（Shadow，offset 0 + 模糊半径）。
//   · 辉光强度 = 以"当前音频扫到的字母位置"为中心的平滑钟形（empBell）。
//     扫到哪个字母哪个最亮，两侧平滑衰减 → 光斑随音频连续地左→右流动；
//     因为它是连续音频位置的连续函数，绝不会一闪一闪。
//   · 呼吸缩放与辉光同源（同一 e 驱动），字母被扫到时同步轻微放大。
//   · amp 按 duration 在 EMP_MIN..EMP_FULL 间插值：刚过阈值的词几乎不动，长音更强。
private const val EMP_MIN_DURATION_MS = 800L
private const val EMP_FULL_DURATION_MS = 2000L
private const val EMP_AMP_FLOOR = 0.7f            // 刚过阈值的慢词也保底 70% 强度（确保发光+起伏都明显）
private const val EMP_PEAK_SCALE = 0.05f          // 呼吸缩放峰值（1.0 → 1.05）
private const val EMP_FLOAT_PEAK_DP = 3.2f        // 字母上浮量峰值
private const val EMP_FLOAT_RAMP = 1.3f           // 上浮过渡半宽（单位：字母）—— 越大波浪越平缓连贯
private const val EMP_GLOW_OPACITY = 0.40f        // 辉光峰值不透明度（白色光晕）
private const val EMP_GLOW_BLUR_REST_DP = 4f      // 辉光最小模糊半径
private const val EMP_GLOW_BLUR_PEAK_DP = 11f     // 辉光峰值模糊半径（同时用作裁切框的横向外扩量）
private const val EMP_GLOW_SPREAD = 1.7f          // 辉光光斑半宽（单位：字母）
private const val EMP_GLOW_BIAS = 0.15f           // 光斑峰值相对扫描位置的滞后（字母）
private const val EMP_FILL_EDGE_FRAC = 0.5f       // 单字母内填充柔边宽度（占字母宽比例）

/**
 * 辉光 / 呼吸的空间钟形：dist = 当前音频扫描位置 − 字母中心（单位：字母）。
 * dist ≈ EMP_GLOW_BIAS 时取峰 1，向两侧各 EMP_GLOW_SPREAD 个字母平滑衰减到 0。
 * 是连续音频位置的连续函数 → 光斑只会平滑流动，不会一闪一闪。
 */
private fun empBell(dist: Float): Float {
    val x = (dist - EMP_GLOW_BIAS) / EMP_GLOW_SPREAD
    if (x <= -1f || x >= 1f) return 0f
    return 0.5f * (1f + kotlin.math.cos(3.1415927f * x))
}

/**
 * 起伏斜坡：dist = 当前音频扫描位置 − 字母中心（单位：字母）。
 * dist ≤ -EMP_FLOAT_RAMP 时 0（未上浮），≥ +EMP_FLOAT_RAMP 时 1（已上浮并保持），
 * 中间约 2·EMP_FLOAT_RAMP 字母宽的 smoothstep 过渡 —— 相邻字母平滑错开 → 连成上浮波浪，
 * 不再各自在 1 字母窗口里独立跳起。
 */
private fun floatRamp(dist: Float): Float {
    val t = ((dist + EMP_FLOAT_RAMP) / (2f * EMP_FLOAT_RAMP)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 强调幅度：所有慢词都有保底 EMP_AMP_FLOOR；词越长越夸张，到 EMP_FULL 拉满。 */
private fun emphasisAmp(durationMs: Long): Float {
    if (durationMs < EMP_MIN_DURATION_MS) return 0f
    val ramp = ((durationMs - EMP_MIN_DURATION_MS).toFloat() /
        (EMP_FULL_DURATION_MS - EMP_MIN_DURATION_MS).toFloat()).coerceIn(0f, 1f)
    return EMP_AMP_FLOOR + (1f - EMP_AMP_FLOOR) * ramp
}

/**
 * 慢词逐字母渲染：每个字母单独 measure 成独立 TextLayoutResult，单独绘制一次
 *（不裁切、不叠第二份字形）。镜像 Apple Music 把每个字母拆成独立 span 的做法 ——
 * 字母各自完整绘制，辉光（Shadow）自然外溢、字母间无裁切裂缝。
 *
 * 每个字母叠加：
 *   · 填充扫色：fgUnsung→fg 横向渐变（含柔边），随音频扫过；
 *   · 辉光：白色 Shadow 光晕，强度 = empBell(扫描位置 − 字母中心)，随音频左→右流动；
 *   · 呼吸：被扫到时绕字母中心轻微放大；
 *   · 起伏：唱过后上浮并保持。
 */
private fun DrawScope.drawEmphasizedToken(
    layout: TextLayoutResult,
    glyphMeasurer: TextMeasurer,
    glyphStyle: TextStyle,
    unit: LyricDrawUnit,
    prev: LyricDrawUnit?,
    next: LyricDrawUnit?,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    envelope: Float,
) {
    val text = layout.layoutInput.text.text
    val token = unit.timing
    val letterCount = unit.tokenCharCount.coerceAtLeast(1)
    val durationF = token.durationMs.coerceAtLeast(1L).toFloat()
    // token.progress 已经是 timingParts 感知的「文本分数」：含子音节真实节奏，
    // 不是均匀线性。填充、发光、起伏全部以它为准 → 三者与人声同一套时间轴。
    val tokenProgress = token.progress(positionMs).coerceIn(0f, 1f)
    // 填充扫描位置（夹取：字母唱过即保持已唱）。
    val fillSweep = tokenProgress * letterCount
    // 辉光 / 起伏扫描位置：
    //   · 词内（0..1）跟 token.progress 走 —— 贴合 timingParts 的子音节真实节奏，与填充同步；
    //   · 词外用线性外插（rawLinear <0 词前、>1 词后）—— 光斑能平滑滑入 / 滑出词，不赖着发光。
    //   rawLinear 与 progress 在 0 / 1 处天然一致，衔接连续无跳变。
    val rawLinear = (positionMs - token.startMs).toFloat() / durationF
    val sweepProgress = if (rawLinear in 0f..1f) tokenProgress else rawLinear
    val glowSweep = sweepProgress * letterCount
    // 辉光 / 呼吸 / 起伏统一用 empGain 作总增益 —— 三者永远同时出现、同时消失。
    val empGain = emphasisAmp(token.durationMs) * envelope.coerceIn(0f, 1f)

    val lineTopY = layout.getLineTop(unit.line)
    val lineBottomY = layout.getLineBottom(unit.line)
    val floatPeakPx = EMP_FLOAT_PEAK_DP.dp.toPx()
    val glowRestPx = EMP_GLOW_BLUR_REST_DP.dp.toPx()
    val glowPeakPx = EMP_GLOW_BLUR_PEAK_DP.dp.toPx()

    // ---- 辉光裁切框：把这个慢词的辉光严格关在它自己的"格子"里 ----
    //   · 纵向：只在本可视行的行高带 [lineTopY, lineBottomY] 内。折行歌词的不同可视行、
    //     以及上下相邻的歌词行，都不会被这个词的辉光晕染到。
    //   · 横向：可探入相邻空白处绽放（外扩 glowPeakPx），但绝不越过相邻 token 的真实
    //     字形 inkLeft/inkRight —— 不会影响同一行里的其它单词 / 字母。
    //   词内各字母的辉光仍在框内自由叠加（它们本就是同一个词）。
    val clipLeft = when {
        prev == null -> layout.getLineLeft(unit.line) - glowPeakPx
        unit.left > prev.inkRight -> maxOf(prev.inkRight, unit.left - glowPeakPx)
        else -> unit.left
    }
    val clipRight = when {
        next == null -> layout.getLineRight(unit.line) + glowPeakPx
        next.inkLeft > unit.right -> minOf(next.inkLeft, unit.right + glowPeakPx)
        else -> unit.right
    }

    AmllLyricLogger.maybeRecord(
        event = "emphasis_token",
        key = token.text.hashCode().toString(),
        fields = {
            mapOf(
                "tokenText" to lyricLogPreview(token.text, max = 24),
                "tokenStartMs" to token.startMs,
                "tokenDurationMs" to token.durationMs,
                "tokenProgress" to tokenProgress,
                "glowSweep" to glowSweep,
                "amp" to emphasisAmp(token.durationMs),
                "envelope" to envelope,
                "letterCount" to letterCount,
            )
        },
    )

    clipRect(
        left = clipLeft,
        top = lineTopY,
        right = clipRight,
        bottom = lineBottomY,
        clipOp = ClipOp.Intersect,
    ) {
        for (gi in unit.glyphStart until unit.glyphEnd) {
            if (gi !in text.indices) continue
            if (text[gi].isWhitespace()) continue
            val box = layout.getBoundingBox(gi)
            if (box.width <= 0f) continue

            val letterIdx = gi - unit.tokenStartChar
            // 该字母相对当前音频扫描位置的距离（字母为单位）—— 辉光与起伏共用同一位置。
            val letterDist = glowSweep - (letterIdx + 0.5f)
            // 该字母填充进度：扫描位置跨过 [k, k+1] 时 0→1，连续。
            val fp = (fillSweep - letterIdx).coerceIn(0f, 1f)
            // emphasis 强度：以音频扫描位置为中心的平滑钟形 → 光斑随音频左→右流动，连续不闪。
            val e = (empBell(letterDist) * empGain).coerceIn(0f, 1f)

            val scaleAmt = 1f + EMP_PEAK_SCALE * e
            // 起伏：宽过渡的平滑斜坡（约 2·EMP_FLOAT_RAMP 字母宽），与辉光同位置、同增益 ——
            // 多字母连成一道平滑上浮波浪，不再一个个独立跳起；发光与位移必定同时发生。
            val ty = -floatPeakPx * floatRamp(letterDist) * empGain
            val glowAlpha = EMP_GLOW_OPACITY * e
            val glowBlurPx = glowRestPx + (glowPeakPx - glowRestPx) * e

            val letterLayout = glyphMeasurer.measure(text[gi].toString(), glyphStyle)
            val drawW = letterLayout.size.width.toFloat()
            val topLeft = Offset(box.left, lineTopY + ty)

            // 单字母内填充扫色：fgUnsung→fg 横向渐变（含柔边）。
            val fillBrush: Brush = when {
                fp <= 0f -> SolidColor(fgUnsung)
                fp >= 1f -> SolidColor(fg)
                else -> {
                    val b1 = (fp + EMP_FILL_EDGE_FRAC).coerceIn(fp, 1f)
                    Brush.horizontalGradient(
                        0f to fg, fp to fg, b1 to fgUnsung, 1f to fgUnsung,
                        startX = box.left,
                        endX = box.left + drawW,
                    )
                }
            }
            // 辉光：独立叠加的白色光晕，不改字色；强度随音频扫描位置流动。
            val glow = if (glowAlpha > 0.01f) {
                Shadow(Color.White.copy(alpha = glowAlpha), Offset.Zero, glowBlurPx)
            } else {
                null
            }

            if (e > 0.001f) {
                val pivot = Offset(box.left + box.width / 2f, box.center.y + ty)
                withTransform({ scale(scaleX = scaleAmt, scaleY = scaleAmt, pivot = pivot) }) {
                    drawText(letterLayout, brush = fillBrush, topLeft = topLeft, shadow = glow)
                }
            } else {
                drawText(letterLayout, brush = fillBrush, topLeft = topLeft, shadow = glow)
            }
        }
    }
}

private fun snapTextOffset(value: Float): Float {
    return kotlin.math.round(value * 2f) / 2f
}

/**
 * AMLL 调试日志节流器。每个 (event, key) 通道独立 throttle，避免逐帧爆掉诊断 ndjson。
 * 默认 120ms 一帧；用户在「设置 → 诊断日志 → 分享 txt」导出即可拿到。
 */
private object AmllLyricLogger {
    private const val DEFAULT_INTERVAL_NANOS = 120_000_000L // 120ms
    private val channelNanos = ConcurrentHashMap<String, AtomicLong>()
    private val droppedCounts = ConcurrentHashMap<String, AtomicLong>()

    fun maybeRecord(
        event: String,
        key: String = "",
        intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
        fields: () -> Map<String, Any?>,
    ) {
        val channel = if (key.isEmpty()) event else "$event|$key"
        val now = System.nanoTime()
        val ref = channelNanos.getOrPut(channel) { AtomicLong(0L) }
        val last = ref.get()
        if (now - last < intervalNanos) {
            droppedCounts.getOrPut(channel) { AtomicLong(0L) }.incrementAndGet()
            return
        }
        if (!ref.compareAndSet(last, now)) {
            droppedCounts.getOrPut(channel) { AtomicLong(0L) }.incrementAndGet()
            return
        }
        val dropped = droppedCounts[channel]?.getAndSet(0L) ?: 0L
        val computed = runCatching { fields() }.getOrElse { return }
        val payload = if (dropped > 0L) computed + ("droppedFramesSinceLast" to dropped) else computed
        DiagnosticsLogStore.record(area = "amll_lyric", event = event, fields = payload)
    }

    fun forceRecord(event: String, fields: Map<String, Any?>) {
        val channel = event
        channelNanos.getOrPut(channel) { AtomicLong(0L) }.set(System.nanoTime())
        DiagnosticsLogStore.record(area = "amll_lyric", event = event, fields = fields)
    }
}
