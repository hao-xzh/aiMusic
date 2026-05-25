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
import androidx.compose.ui.graphics.TileMode
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
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PipoLyricAlignment
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
    progress: Float,                  // 0=compact, 1=immersive（封面 FLIP 时间线）
    contentProgress: Float,           // 0=未入场, 1=已入场（标题/歌词的独立内容时间线）
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

    // 切歌淡出淡入：title 变了就 fade 0 → 1。跟入场/出场的 contentProgress 解耦。
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

    // 布局：标题 / 歌词列的位置**固定在 immersive 终态**（不再跟着封面 progress 走），
    // 入场只做小幅 translateY + alpha。封面 FLIP 时其它东西不再跟着大幅平移 = "丝滑" 的关键。
    val configuration = LocalConfiguration.current
    val screenWDp = configuration.screenWidthDp.dp
    // tap 关闭区域仍跟着封面 progress 增长，避免点空封面尚未飞到的位置
    val coverTapHeight = screenWDp * progress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val cp = contentProgress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 只承载切歌的 cross-fade；入场 alpha 由内层每个元素独立处理
            .graphicsLayer { alpha = contentFade.value },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverTapHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        // 标题 / 歌词列绝对定位到 immersive 终态。封面 FLIP 期间它们还是 alpha 0，
        // 由 contentProgress（延后 120ms 起跳）控制软入。

        // 标题 + 副标题 + 控件条 —— 固定在封面下 1/4
        val titleTopPadding = (screenWDp - 84.dp).coerceAtLeast(14.dp)
        val titleRiseDp = 16.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = titleTopPadding, start = 24.dp, end = 24.dp)
                .graphicsLayer {
                    alpha = cp
                    translationY = (1f - cp) * titleRiseDp.toPx()
                },
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

        // 歌词列 —— 固定在封面终态底部下方 28dp。封面 FLIP 期间整列 translateY 抬起 24dp，
        // 内部各行按距 active 行的索引差 stagger 入场（cascade 焦点感）。
        val lyricsTopPadding = (screenWDp - 28.dp).coerceAtLeast(80.dp)
        val lyricsRiseDp = 24.dp
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
            enterProgress = cp,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = lyricsTopPadding, bottom = 20.dp)
                .navigationBarsPadding()
                .graphicsLayer {
                    translationY = (1f - cp) * lyricsRiseDp.toPx()
                },
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
    val activeEase = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }
    val activeProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = activeEase),
        label = "immersiveIconActive",
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(50))
            .background(activeColor.copy(alpha = 0.13f * activeProgress))
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
                    // 启动阶段（前 SMOOTH_POSITION_START_GUARD_MS ms 还没攒到、且没经过 reset）：
                    // 不按帧外插，raw 直接驱动 smoothed。但 ExoPlayer 在切歌后的 prepare /
                    // buffering 期间 currentPosition 经常会出现 0 → 100 → 0 → 133 这种小幅
                    // 倒退抖动，如果 smoothed 跟着抖，token.progress 计算结果就会反复回到 0，
                    // 字级 sweep 和慢词 emphasis 浮动看起来像「从头重走一遍」。
                    // 这里复用外插路径同样的「小倒退原地等」规则 —— diff 在 [-BACKWARD_RESET, 0]
                    // 区间时保持 smoothed 不动，等 raw 自己追上；只有大幅倒退（>300ms，是真 seek
                    // 或真的回到歌曲早段）才让 smoothed 跟回去。
                    val rawF = anchor.positionMs.toFloat()
                    val diff = rawF - smoothed
                    when {
                        diff < -SMOOTH_POSITION_BACKWARD_RESET_MS.toFloat() -> smoothed = rawF
                        diff > 0f -> smoothed = rawF
                        // diff in [-BACKWARD_RESET, 0]：raw 微抖，原地等
                    }
                    output.value = smoothed.toLong()
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
    // 0=未入场，1=完全入场。<1 时按行距 active 索引差做 stagger（active 先现，外圈延后），
    // =1 时公式恒等返回 1，对稳态无任何影响（横屏 / 默认场景安全）。
    enterProgress: Float = 1f,
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

    // listState 创建时就用当前 active 行作为初始位置 —— 避免「LazyColumn 先渲染 index=0 顶部，
    // 再异步 LaunchedEffect 滚到 active」中间那一帧；用户在 immersive 打开 / 切歌瞬间能立刻
    // 看到正在唱的那行歌词，不会出现「行 N 唱完切到 N+1 时 N 才出来」的滞后现象。
    val listState = remember(lyricSessionKey) {
        val initialIdx = if (lines.isEmpty()) {
            0
        } else {
            val hasWordTimingLocal = lines.any { it.chars.isNotEmpty() }
            val leadMs = if (hasWordTimingLocal) {
                LYRIC_WORD_LINE_FOCUS_LEAD_MS
            } else {
                LyricTiming.focusLeadMs(lines)
            }
            val ledMs = positionMs + leadMs
            lines.indexOfLast { line -> ledMs >= LyricTiming.audioStartMs(line) }
                .coerceAtLeast(0)
        }
        LazyListState(firstVisibleItemIndex = initialIdx)
    }
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
        // 用户点击歌词行触发的 seek —— 立即把目标行 snap 到焦点位（11% from top），
        // 不走 720ms spring，避免 "点了一下还要等 1/3 秒被拽过去" 的迟滞感。
        // 自然播放切句仍走下方的 spring，保留 Apple Music "被拽过去" 的物理感。
        val isClickSeekScroll = heldClickIndex != null && heldClickIndex == targetIdx
        if (isClickSeekScroll) {
            listState.scrollToItem(targetIdx, scrollOffset = -focusOffsetPx)
            return@LaunchedEffect
        }
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
                // 入场 stagger：active 行先到 1，距离每 +1 → 延后约 4% 的 enterProgress。
                // 公式特点：enterProgress=1 时对任意 distance 恒返回 1（无副作用）。
                val rowStaggerAlpha = if (enterProgress >= 0.999f) {
                    1f
                } else {
                    val delayFrac = (distance * 0.04f).coerceAtMost(0.36f)
                    val raw = ((enterProgress - delayFrac) / (1f - delayFrac))
                        .coerceIn(0f, 1f)
                    // ease-out cubic
                    1f - (1f - raw).let { it * it * it }
                }
                // heightIn(min) 而不是 height(fixed) —— 长歌词自然换行成两行不被切。
                // LazyColumn 内部支持变高 item，animateScrollToItem 仍能按 idx 定位。
                val hasCompanionCue = hasVisibleCompanionLyric(line, smoothedPositionMs)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowMinHeight)
                        .graphicsLayer {
                            // 整列已经做了 24dp 抬升，这里行级只补 alpha，避免双层位移叠加。
                            alpha = rowStaggerAlpha
                        }
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
    val companionEase = cssEase
    val density = LocalDensity.current
    val lineAudioStartMs = remember(line.startMs, line.chars) { LyricTiming.audioStartMs(line) }
    val shouldSnapActiveEffects = isActive && positionMs - lineAudioStartMs > LYRIC_ACTIVE_ENTRY_SNAP_MS
    val timedCompanions = line.timedCompanionLines()
    val translationLines = line.translationLines()
    var keepTranslationVisible by remember(line.startMs, line.text) { mutableStateOf(showTranslation) }
    LaunchedEffect(showTranslation, translationLines.isNotEmpty()) {
        if (showTranslation && translationLines.isNotEmpty()) {
            keepTranslationVisible = true
        }
    }
    val translationVisibility by animateFloatAsState(
        targetValue = if (showTranslation) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (showTranslation) TRANSLATION_TOGGLE_SHOW_MS else TRANSLATION_TOGGLE_HIDE_MS,
            easing = companionEase,
        ),
        label = "translationVisibility",
        finishedListener = { value ->
            if (!showTranslation && value <= 0.001f) {
                keepTranslationVisible = false
            }
        },
    )
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
                    animationSpec = tween(durationMillis = 280, easing = companionEase),
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // AMLL 对唱排版：ttm:agent="v1" 的主唱行靠左，agent="v2/v3..." 的第二位
            // 演唱者整行靠右，参考 AMLL 官方播放器的对唱效果。Column 默认 horizontalAlignment
            // 是 Start，这里在每个子项上显式 .align(itemAlignment)，避免影响 Column 其他子项。
            val itemAlignment = if (line.alignment == PipoLyricAlignment.End) {
                Alignment.End
            } else {
                Alignment.Start
            }
            val itemTextAlign = if (line.alignment == PipoLyricAlignment.End) {
                TextAlign.End
            } else {
                TextAlign.Start
            }
            Box(modifier = Modifier.align(itemAlignment)) {
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
                    textAlign = itemTextAlign,
                )
            }
            timedCompanions.forEach { companion ->
                // 副词不再随结束时间消失：渲染条件保留「已经到了起播时间」，
                // 但删除「past 即移除」—— 让副词唱完后跟主行同步停留（isPast=true，
                // 颜色切到 fg 已唱色），整体亮度跟着 rowAlpha 走，看起来和主行历史副歌的
                // 排版一致，不会出现"上一段副歌唱完一句话突然抽掉一半"的视觉断层。
                if (shouldRenderCompanionLyric(companion, positionMs)) {
                    val companionActive = isCompanionLyricActive(companion, positionMs)
                    val appear = companionEase.transform(companionLyricAppearProgress(companion, positionMs))
                    val risePx = with(density) { COMPANION_LYRIC_APPEAR_RISE_DP.dp.toPx() }
                    // 副词跟随主行对齐方向：v1 主唱里的 x-bg 靠左，v2 副唱里的 x-bg 靠右。
                    val companionAlignment = if (companion.alignment == PipoLyricAlignment.End) {
                        Alignment.End
                    } else {
                        itemAlignment
                    }
                    val companionTextAlign = if (companionAlignment == Alignment.End) {
                        TextAlign.End
                    } else {
                        TextAlign.Start
                    }
                    Box(
                        modifier = Modifier
                            .align(companionAlignment)
                            .graphicsLayer {
                                alpha = appear
                                translationY = risePx * (1f - appear)
                                val scale = COMPANION_LYRIC_APPEAR_SCALE_FROM +
                                    (1f - COMPANION_LYRIC_APPEAR_SCALE_FROM) * appear
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
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
                            textAlign = companionTextAlign,
                        )
                    }
                }
            }
            if (showTranslation || keepTranslationVisible) {
                val translationRisePx = with(density) { TRANSLATION_TOGGLE_RISE_DP.dp.toPx() }
                translationLines.forEach { translation ->
                    Box(
                        modifier = Modifier
                            .align(itemAlignment)
                            .graphicsLayer {
                                alpha = translationVisibility
                                translationY = translationRisePx * (1f - translationVisibility)
                                val scale = TRANSLATION_TOGGLE_SCALE_FROM +
                                    (1f - TRANSLATION_TOGGLE_SCALE_FROM) * translationVisibility
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
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
                            textAlign = itemTextAlign,
                        )
                    }
                }
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

private fun companionLyricAppearProgress(
    line: PipoLyricLine,
    positionMs: Long,
): Float {
    val appearStartMs = LyricTiming.audioStartMs(line) - COMPANION_LYRIC_LEAD_MS
    return ((positionMs - appearStartMs).toFloat() / COMPANION_LYRIC_APPEAR_MS)
        .coerceIn(0f, 1f)
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
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = line.text,
        color = color,
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            lineHeight = lineHeight,
            textAlign = textAlign,
            // 强制按词组边界换行：英文单词到行尾若放不下，整词推到下一行，不再从字母中间断。
            // WordBreak.Phrase 同时优化 CJK 的标点 / 词组贴边。
            lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
            hyphens = Hyphens.None,
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
    textAlign: TextAlign = TextAlign.Start,
) {
    val isYrcLine = line.chars.isNotEmpty()
    val isActiveYrc = isActive && isYrcLine
    val isActiveLrc = isActive && !isYrcLine
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        // 对唱行（v2 / v3）传 TextAlign.End：长英文折行时 Text 会自动撑到 parent.maxWidth，
        // 仅靠外层 Box .align(End) 没用（Box 也跟着撑满），必须让 Text 内部 textAlign=End
        // 才能让每一折行视觉上贴右。逐字 sweep 由 layout 计算字符位置，textAlign 改变后
        // layout 自然反映出新的位置，sweep 无需额外处理。
        textAlign = textAlign,
        lineBreak = LineBreak.Paragraph,
        hyphens = Hyphens.None,
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

private const val COMPANION_LYRIC_LEAD_MS = 320L
private const val COMPANION_LYRIC_APPEAR_MS = 260L
private const val COMPANION_LYRIC_APPEAR_RISE_DP = 4f
private const val COMPANION_LYRIC_APPEAR_SCALE_FROM = 0.995f
private const val TRANSLATION_TOGGLE_SHOW_MS = 260
private const val TRANSLATION_TOGGLE_HIDE_MS = 220
private const val TRANSLATION_TOGGLE_RISE_DP = 2.5f
private const val TRANSLATION_TOGGLE_SCALE_FROM = 0.996f
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
 *   · 慢词（duration 与平均字母时长都足够慢）：交给 drawEmphasizedToken 逐字母渲染，
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
        // 位移 / 呼吸 / 辉光必须共用同一个 eligibility，避免出现“按慢词动但没发光”的半状态。
        if (shouldUseSlowEmphasis(unit.timing)) {
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

// ---- Apple Music 慢词 emphasis（连续左→右扫过的辉光 + 色带 + 呼吸 + 起伏）----
//
// 关键：辉光是「一束连续的光」从左到右扫过整词，不是逐字母点亮 ——
//   · 颜色 sweep：整词共用一条横向渐变（fg→fgUnsung），柔边连续滑过、跨字母不分段。
//   · 辉光：只在同一次字形绘制里加 Shadow，强度仍按连续"彗星"曲线计算。
//     不再额外画一层模糊整词，避免双字 / 灰色背景块 / 相邻非慢词被带亮。
//   · 呼吸 / 起伏：逐字母（缩放 / 位移步进极小，肉眼连续），跟扫描位置走。
//   · 触发：词 duration ≥ EMP_MIN_DURATION_MS；amp 按时长在 MIN..FULL 间插值。
private const val EMP_MIN_DURATION_MS = 800L
private const val EMP_FULL_DURATION_MS = 2000L
private const val EMP_AMP_FLOOR = 0.7f            // 刚过阈值的慢词也保底 70% 强度
private const val EMP_PEAK_SCALE = 0.05f          // 呼吸缩放峰值（1.0 → 1.05）
private const val EMP_FLOAT_PEAK_DP = 1.6f          // 字母上浮量峰值 —— 从 3.2 降到 1.6dp，
//                                                     与普通词 lyricLiftEnvelope 的 0.95dp 峰值仅差 0.65dp，
//                                                     肉眼几乎看不出落差；前一个普通词切到当前慢词时，
//                                                     字母从 ~1dp 衔接到 ~1.6dp，不再有「突然跳起来一截」的视觉断层。
private const val EMP_FLOAT_ATTACK_MS = 480L        // 单字母上浮目标时长 —— 与普通词
//                                                     lyricLiftEnvelope 的中位 riseMs（280~900 取中）对齐，
//                                                     让慢词字母浮起的"速度感"和同行其它普通词保持一致
private const val EMP_FLOAT_RELEASE_LETTERS = 3.0f  // 下落过渡半宽（字母）—— 位置驱动，
//                                                     释放时长 = 3 × msPerLetter，与之前的下落节奏完全一致
private const val EMP_GLOW_OPACITY = 0.40f        // 辉光峰值不透明度（白色光晕）
private const val EMP_GLOW_BLUR_DP = 8f           // 单层字形 Shadow 模糊半径
private const val EMP_GLOW_TRAIL_DP = 34f         // 辉光在扫描位置之后（已唱侧）的拖尾长度
private const val EMP_GLOW_LEAD_DP = 8f           // 辉光略微探到扫描位置之前
private const val EMP_GLOW_PEAK_DP = 6f           // 辉光最亮点落在扫描位置之后多少
private const val EMP_FILL_EDGE_DP = 13f          // 颜色 sweep 柔边总宽度
private const val EMP_MIN_MS_PER_GLYPH = 180L     // 平均每个可见字母足够慢，才进入慢词通道

/**
 * 字母浮动 envelope：attack 时间驱动 + release 位置驱动。
 *
 *  · attack：单字母从 baseline 升到峰值用 EMP_FLOAT_ATTACK_MS（固定毫秒数），
 *    与普通词 lyricLiftEnvelope 的中位 riseMs 对齐 —— 不再随字母数被挤压成几十毫秒的「快闪」，
 *    串播时和同行普通词的浮起速度看起来一致。
 *  · release：保留原"位置驱动"思路，下落时长 = EMP_FLOAT_RELEASE_LETTERS × msPerLetter ——
 *    跟之前的下落手感完全一致（长慢词字母慢慢落、短慢词字母快落）。
 *  · 字母 0 的 attack 起点限制在 token.startMs（不允许越过 token 起点提前浮起）——
 *    避免「前一个普通词还没唱完，慢词字母 0 已经先浮一半」的视觉不同步。
 *    词首字母的实际 attack 时长会被压到 0.5 × msPerLetter（可能短于 EMP_FLOAT_ATTACK_MS），
 *    会有一点点「跳」的感觉，但由于 EMP_FLOAT_PEAK_DP 已被压到接近普通词浮幅，
 *    跳变高度差很小，肉眼几乎看不出来 —— 是「峰值幅度」而不是「attack 时长」在主导突兀感。
 *
 * peak 处用 quintic smoothstep（10t³-15t⁴+6t⁵）拼接 —— 二阶导数也为 0，没有加速度跳变。
 * Token 唱完后 release 段仍按 letterCenterMs 继续推进，词尾字母自然回到 baseline，
 * 与同行非慢词字母保持水平对齐。
 */
private fun letterFloatEnvelope(
    letterIdx: Int,
    letterCount: Int,
    token: PipoLyricChar,
    positionMs: Long,
): Float {
    val safeLetterCount = letterCount.coerceAtLeast(1)
    val msPerLetter = token.durationMs.toFloat() / safeLetterCount
    val letterCenterMs = token.startMs + (letterIdx + 0.5f) * msPerLetter
    val elapsed = positionMs.toFloat() - letterCenterMs
    val maxAttack = (letterCenterMs - token.startMs.toFloat()).coerceAtLeast(0f)
    val attackMs = minOf(EMP_FLOAT_ATTACK_MS.toFloat(), maxAttack)
    val releaseMs = EMP_FLOAT_RELEASE_LETTERS * msPerLetter
    return when {
        attackMs <= 0f && elapsed < 0f -> 0f
        elapsed < -attackMs -> 0f
        elapsed < 0f -> {
            val t = ((elapsed + attackMs) / attackMs).coerceIn(0f, 1f)
            quinticSmoothstep(t)
        }
        elapsed < releaseMs -> {
            val t = (1f - elapsed / releaseMs).coerceIn(0f, 1f)
            quinticSmoothstep(t)
        }
        else -> 0f
    }
}

/** Ken Perlin 的 quintic smoothstep —— f(0)=0, f(1)=1, f'(0)=f'(1)=0, f''(0)=f''(1)=0。 */
private fun quinticSmoothstep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

/** 强调幅度：所有慢词都有保底 EMP_AMP_FLOOR；词越长越夸张，到 EMP_FULL 拉满。 */
private fun emphasisAmp(durationMs: Long): Float {
    if (durationMs < EMP_MIN_DURATION_MS) return 0f
    val ramp = ((durationMs - EMP_MIN_DURATION_MS).toFloat() /
        (EMP_FULL_DURATION_MS - EMP_MIN_DURATION_MS).toFloat()).coerceIn(0f, 1f)
    return EMP_AMP_FLOOR + (1f - EMP_AMP_FLOOR) * ramp
}

private fun shouldUseSlowEmphasis(token: PipoLyricChar): Boolean {
    if (token.durationMs < EMP_MIN_DURATION_MS) return false
    val glyphMs = token.durationMs / visibleLyricGlyphCount(token.text)
    return glyphMs >= EMP_MIN_MS_PER_GLYPH
}

private fun visibleLyricGlyphCount(text: String): Int {
    return text.count { !it.isWhitespace() }.coerceAtLeast(1)
}

/**
 * 慢词 emphasis 渲染：
 *  · 逐字母清晰文字只绘制一次 —— 整词共用连续色带渐变（连续扫色，不分字母），
 *    叠加逐字母呼吸缩放与上浮起伏。
 *  · 辉光不再走额外 saveLayer；它作为当前字母同一次 drawText 的 Shadow 出现，
 *    强度按连续彗星曲线随 sweepX 流动，避免双重文字和矩形背景。
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
    // token.progress 是 timingParts 感知的「文本分数」；词外用线性外插（衔接处天然连续）。
    val tokenProgress = token.progress(positionMs).coerceIn(0f, 1f)
    val rawLinear = (positionMs - token.startMs).toFloat() / durationF
    val sweepProgress = if (rawLinear in 0f..1f) tokenProgress else rawLinear
    val glowSweep = sweepProgress * letterCount
    val empGain = (emphasisAmp(token.durationMs) * envelope.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    val glowGain = empGain

    val lineTopY = layout.getLineTop(unit.line)
    val lineBottomY = layout.getLineBottom(unit.line)
    val floatPeakPx = EMP_FLOAT_PEAK_DP.dp.toPx()
    val glowBlurPx = EMP_GLOW_BLUR_DP.dp.toPx()
    val fillEdgeHalfPx = EMP_FILL_EDGE_DP.dp.toPx() * 0.5f

    // 辉光裁切框：纵向锁在本可视行行高带内（不渗到折行的其它可视行 / 上下相邻歌词行），
    // 横向可探入相邻空白，但不越过相邻 token 的真实字形 —— 不影响其它单词。
    val clipLeft = when {
        prev == null -> layout.getLineLeft(unit.line) - glowBlurPx
        unit.left > prev.inkRight -> maxOf(prev.inkRight, unit.left - glowBlurPx)
        else -> unit.left
    }
    val clipRight = when {
        next == null -> layout.getLineRight(unit.line) + glowBlurPx
        next.inkLeft > unit.right -> minOf(next.inkLeft, unit.right + glowBlurPx)
        else -> unit.right
    }

    // 颜色 / 辉光共用的连续扫描 x —— 行程两端各多走半个柔边，保证 progress 0/1 时
    // 柔边完全移出词外（词首全未唱 / 词尾全已唱）。
    val segProgress = lyricUnitSegmentProgress(unit, positionMs)
    val sweepX = (unit.left - fillEdgeHalfPx) +
        (unit.right - unit.left + 2f * fillEdgeHalfPx) * segProgress

    AmllLyricLogger.maybeRecord(
        event = "emphasis_token",
        key = token.text.hashCode().toString(),
        fields = {
            mapOf(
                "tokenText" to lyricLogPreview(token.text, max = 24),
                "tokenDurationMs" to token.durationMs,
                "tokenProgress" to tokenProgress,
                "segProgress" to segProgress,
                "sweepX" to sweepX,
                "empGain" to empGain,
                "glowGain" to glowGain,
                "letterCount" to letterCount,
                "visibleGlyphCount" to visibleLyricGlyphCount(token.text),
            )
        },
    )

    val glowTrailPx = EMP_GLOW_TRAIL_DP.dp.toPx()
    val glowLeadPx = EMP_GLOW_LEAD_DP.dp.toPx()
    val glowPeakLagPx = EMP_GLOW_PEAK_DP.dp.toPx()

    val scaleBandHalfPx = ((unit.right - unit.left) / letterCount).coerceAtLeast(1f)
    clipRect(
        left = clipLeft, top = lineTopY, right = clipRight, bottom = lineBottomY,
        clipOp = ClipOp.Intersect,
    ) {
        for (gi in unit.glyphStart until unit.glyphEnd) {
            if (gi !in text.indices) continue
            if (text[gi].isWhitespace()) continue
            val box = layout.getBoundingBox(gi)
            if (box.width <= 0f) continue

            val letterIdx = gi - unit.tokenStartChar
            // 呼吸缩放：该字母被连续扫描带覆盖的比例（逐字母步进极小、肉眼连续）。
            val scaleE = appleBandCoverage(box.left, box.right, sweepX, scaleBandHalfPx) * empGain
            val scaleAmt = 1f + EMP_PEAK_SCALE * scaleE
            val glowE = appleCometCoverage(
                left = box.left,
                right = box.right,
                sweepX = sweepX,
                trailPx = glowTrailPx,
                leadPx = glowLeadPx,
                peakLagPx = glowPeakLagPx,
            ) * glowGain
            val glow = if (glowE > 0.01f) {
                Shadow(
                    color = Color.White.copy(alpha = (EMP_GLOW_OPACITY * glowE).coerceIn(0f, 1f)),
                    offset = Offset.Zero,
                    blurRadius = glowBlurPx,
                )
            } else {
                null
            }
            // 起伏：attack 走固定毫秒（与普通词节奏一致），release 走 letter-distance（保留连绵波浪手感）。
            val letterEnv = letterFloatEnvelope(letterIdx, letterCount, token, positionMs)
            val ty = -floatPeakPx * letterEnv * empGain

            val letterLayout = glyphMeasurer.measure(text[gi].toString(), glyphStyle)
            val topLeft = Offset(box.left, lineTopY + ty)
            // drawText(letterLayout, topLeft=box.left) 会在字母自己的局部坐标里采样 Brush。
            // sweepX 仍然是整词的全局扫描位置，但传给单字母 Brush 前必须减去 box.left，
            // 否则每个字母都会直接吃到同一种 clamp 色，看起来像整词瞬间变色。
            val localSweepStartX = sweepX - fillEdgeHalfPx - box.left
            val localSweepEndX = (sweepX + fillEdgeHalfPx - box.left)
                .coerceAtLeast(localSweepStartX + 1f)
            val letterFillBrush = Brush.horizontalGradient(
                colorStops = arrayOf(0f to fg, 1f to fgUnsung),
                startX = localSweepStartX,
                endX = localSweepEndX,
                tileMode = TileMode.Clamp,
            )
            if (scaleE > 0.001f) {
                val pivot = Offset(box.left + box.width / 2f, box.center.y + ty)
                withTransform({ scale(scaleX = scaleAmt, scaleY = scaleAmt, pivot = pivot) }) {
                    drawText(letterLayout, brush = letterFillBrush, topLeft = topLeft, shadow = glow)
                }
            } else {
                drawText(letterLayout, brush = letterFillBrush, topLeft = topLeft, shadow = glow)
            }
        }
    }
}

private fun appleBandCoverage(
    left: Float,
    right: Float,
    centerX: Float,
    halfWidth: Float,
): Float {
    if (right <= left || halfWidth <= 0f) return 0f
    val bandLeft = centerX - halfWidth
    val bandRight = centerX + halfWidth
    val overlap = (minOf(right, bandRight) - maxOf(left, bandLeft)).coerceAtLeast(0f)
    return (overlap / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

private fun appleCometCoverage(
    left: Float,
    right: Float,
    sweepX: Float,
    trailPx: Float,
    leadPx: Float,
    peakLagPx: Float,
): Float {
    if (right <= left) return 0f
    val startX = sweepX - trailPx
    val peakX = sweepX - peakLagPx
    val endX = sweepX + leadPx

    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun sample(x: Float): Float {
        return when {
            x <= startX || x >= endX -> 0f
            x <= peakX -> smooth((x - startX) / (peakX - startX).coerceAtLeast(1f))
            else -> smooth((endX - x) / (endX - peakX).coerceAtLeast(1f))
        }
    }

    val q1 = left + (right - left) * 0.25f
    val mid = (left + right) * 0.5f
    val q3 = left + (right - left) * 0.75f
    return ((sample(q1) + sample(mid) + sample(q3)) / 3f).coerceIn(0f, 1f)
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
