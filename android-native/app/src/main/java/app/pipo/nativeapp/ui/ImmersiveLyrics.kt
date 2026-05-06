package app.pipo.nativeapp.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.progress

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
    lyrics: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    isPlaying: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
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
            .graphicsLayer { alpha = progress * contentFade.value }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
    ) {
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
            Column(modifier = Modifier.padding(end = 12.dp).fillMaxWidth(0.7f)) {
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
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImmersiveIconButton(onClick = onToggle) {
                    if (isPlaying) PauseGlyph(color = fg, modifier = Modifier.size(22.dp))
                    else PlayGlyph(color = fg, modifier = Modifier.size(22.dp))
                }
                ImmersiveIconButton(onClick = onNext) {
                    SkipForwardGlyph(color = fg, modifier = Modifier.size(22.dp))
                }
            }
        }

        // 歌词列 —— 绝对定位，顶部"啃"进封面底 28dp（mask 让边界自然溶解）
        val lyricsTopPadding = (coverAreaHeight - 28.dp).coerceAtLeast(80.dp)
        AppleMusicLyricColumn(
            lines = lyrics,
            activeLyricIndex = activeLyricIndex,
            positionMs = positionMs,
            isPlaying = isPlaying,
            fg = fg,
            fgDim = fgDim,
            fgUnsung = fgUnsung,
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
private fun ImmersiveIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
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
@Composable
private fun rememberSmoothPositionMs(rawPositionMs: Long, isPlaying: Boolean): State<Long> {
    val output = remember { mutableStateOf(rawPositionMs) }
    // raw 来一拍就更新 anchor（值 + 当时 monotonic nanos），coroutine 内按帧读最新。
    val rawAnchor = remember { mutableStateOf(rawPositionMs to System.nanoTime()) }
    LaunchedEffect(rawPositionMs) {
        rawAnchor.value = rawPositionMs to System.nanoTime()
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            output.value = rawAnchor.value.first
            return@LaunchedEffect
        }
        var smoothed = rawAnchor.value.first.toFloat()
        while (true) {
            withFrameNanos { frameNanos ->
                val (lastRaw, lastRawNanos) = rawAnchor.value
                val target = lastRaw.toFloat() +
                    (frameNanos - lastRawNanos).toFloat() / 1_000_000f
                val diff = target - smoothed
                when {
                    kotlin.math.abs(diff) > 500f -> smoothed = target           // seek
                    diff > 0f -> smoothed += diff * 0.4f                        // 前进追赶
                    // diff <= 0：target 比 smoothed 落后（线程抖动 / 短暂停拍），原地等。
                }
                output.value = smoothed.toLong()
            }
        }
    }
    return output
}

@Composable
private fun AppleMusicLyricColumn(
    lines: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    isPlaying: Boolean,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    modifier: Modifier = Modifier,
) {
    // 把 player 的 30Hz tick 平滑成按帧位置（120Hz 屏丝滑度提升关键）
    val smoothedPositionMs by rememberSmoothPositionMs(positionMs, isPlaying)
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
    val rowHeightDp = 60.dp
    var containerHeightPx by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()

    // ---- 关键升级：预滚动 ----
    // 用 + lookaheadMs 计算"应当被滚到焦点的行"——比当前在唱的那行 / 待唱的那行
    // 更早一拍切换，下一句**在唱响之前**已经平滑滚到焦点位置。
    // 这接近 Apple Music："new line slides in 0.5–0.7s before its first word"。
    val scrollLookaheadMs = 500L
    val scrollTargetIdx = remember(positionMs, lines) {
        if (lines.isEmpty()) 0
        else lines.indexOfLast { line -> positionMs + scrollLookaheadMs >= line.startMs }
            .coerceAtLeast(0)
    }

    // ---- Apple Music 风滚动：~720ms FastOutSlowIn，CSS ease 同曲线 ----
    // animateScrollToItem 默认是 spring，对长歌词来说太弹；这里手写 tween 让滚动更线性 + 后段缓收。
    LaunchedEffect(scrollTargetIdx, containerHeightPx) {
        if (scrollTargetIdx !in lines.indices || containerHeightPx == 0) return@LaunchedEffect
        // 焦点位置：容器顶 18%
        // 焦点位置：容器顶 11%，正好让活动行上方只露出 1 句历史歌词
        val focusOffsetPx = (containerHeightPx * 0.11f).toInt()
        val info = listState.layoutInfo
        val visibleHit = info.visibleItemsInfo.firstOrNull { it.index == scrollTargetIdx }
        if (visibleHit == null) {
            // 不在可视区：先 snap 到大致位置，避免跨太大段做长动画
            listState.scrollToItem(scrollTargetIdx, scrollOffset = -focusOffsetPx)
            return@LaunchedEffect
        }
        val delta = (visibleHit.offset - focusOffsetPx).toFloat()
        if (kotlin.math.abs(delta) <= 1f) return@LaunchedEffect
        // tween 720ms FastOutSlowIn 跑增量积分：每帧给 LazyListState 一个 scrollBy(d-prev)
        var prev = 0f
        animate(
            initialValue = 0f,
            targetValue = delta,
            // 切换歌词时滚动加速一倍（720 → 360ms），与焦点动画 300ms 同节奏，
            // 整体"切歌词"的视觉节拍更紧，不再有"等滚动"的拖沓感。
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        ) { value, _ ->
            val step = value - prev
            prev = value
            // suspend 不能直接调，但 listState.dispatchRawDelta 是同步的
            listState.dispatchRawDelta(step)
        }
    }

    // 上方留 11%（约 1 行 + 少量呼吸空间），让活动行上方只露出 1 句历史歌词
    val topPadDp = with(density) { (containerHeightPx * 0.11f).toDp() }
    val bottomPadDp = with(density) { (containerHeightPx * 0.89f).toDp() }

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
                            0.80f to Color.Black,
                            0.94f to Color.Black.copy(alpha = 0.4f),
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
                .padding(horizontal = 24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = topPadDp,
                bottom = bottomPadDp,
            ),
            // 关掉 user 滚动，让歌词只跟着进度走（避免用户误触把活动行甩出 30% 位置）
            userScrollEnabled = false,
        ) {
            // PlayerViewModel 算 activeLyricIndex 时用 coerceAtLeast(0)：歌还没开始就把
            // line 0 强制标成 active，导致 song start 时 line 0 立刻进入"焦点动效"，等到
            // 真正开唱时 sweep 又触发字符级 wave —— 看起来像同一句**播放了两遍**。
            // 这里用与 scroll lookahead 一致的 500ms 提前量做 effective active：
            //   smoothPos + 500ms 还没到 line[0].startMs → effectiveActiveIdx = -1（无 active）
            //   到了再切到真正的 activeLyricIndex
            // 这样焦点动画**只在即将开唱前 500ms 触发一次**，紧接着 sweep wave 接力，视觉合一。
            val effectiveActiveIdx = if (smoothedPositionMs + scrollLookaheadMs >= lines.first().startMs) {
                activeLyricIndex
            } else {
                -1
            }
            itemsIndexed(
                items = lines,
                key = { _, line -> line.startMs },
            ) { idx, line ->
                val distance = if (effectiveActiveIdx < 0) {
                    idx + 1   // 整列都按"未来"摊开，line 0 距离 1, line 1 距离 2, ...
                } else {
                    kotlin.math.abs(idx - effectiveActiveIdx)
                }
                // heightIn(min) 而不是 height(fixed) —— 长歌词自然换行成两行不被切。
                // LazyColumn 内部支持变高 item，animateScrollToItem 仍能按 idx 定位。
                Box(modifier = Modifier.heightIn(min = rowHeightDp)) {
                    AppleMusicLyricRow(
                        line = line,
                        isActive = idx == effectiveActiveIdx,
                        isPast = effectiveActiveIdx >= 0 && idx < effectiveActiveIdx,
                        distance = distance,
                        // 活动行的字符级 sweep / lift 用按帧外插的 smoothed 位置，颤动消失；
                        // 非活动行其实只用 isActive/isPast 派生状态，跟 positionMs 精度关系不大。
                        positionMs = smoothedPositionMs,
                        fg = fg,
                        fgUnsung = fgUnsung,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleMusicLyricRow(
    line: PipoLyricLine,
    isActive: Boolean,
    isPast: Boolean,    // idx < activeLyricIndex —— 已唱完的行
    distance: Int,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
) {
    // 行级焦点过渡：整行作为单一图层平滑进入焦点。
    // 进入态 (isActive=true)：alpha 1, blur 0, translateY 0dp, scale 1
    // 离焦态：alpha 0.35（distance=1），blur 2dp，translateY 6dp，scale 0.97
    // 远端只继续衰减 alpha，scale/blur/translateY 已无视觉差异，省 GPU。
    val targetAlpha = when (distance) {
        0 -> 1.0f
        1 -> 0.35f
        2 -> 0.18f
        3 -> 0.10f
        4 -> 0.06f
        else -> 0.04f
    }
    // cubic-bezier(0.22, 1, 0.36, 1) — easeOutQuint 风，前段快、后段缓收。
    // 切换歌词加速一倍：600ms → 300ms，与 360ms 滚动同节拍，整段"切句"的动画更紧凑、灵动。
    val focusEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    val focusSpec = remember(focusEasing) {
        tween<Float>(durationMillis = 300, easing = focusEasing)
    }
    val rowAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = focusSpec,
        label = "lyricAlpha",
    )
    // 关键：scale / blur / lift 这些"焦点动效"只给 YRC（字符级时间戳的数据）。
    // LRC（整行时间戳）只走 alpha + 整句变色 —— 没有词级数据可以同步动画，整行抖动反而怪。
    val isYrcLineForFocus = line.chars.isNotEmpty()
    val rowScale by animateFloatAsState(
        targetValue = if (isYrcLineForFocus) {
            if (isActive) 1f else 0.97f
        } else 1f,
        animationSpec = focusSpec,
        label = "lyricScale",
    )
    // 行级 translateY：YRC 非活动行保持 ±dp 偏移，与 per-char wave 的 rest / settled 对齐
    // → future（+1dp 微沉，对齐字符 rest）→ active（0，per-char 接管）→ past（-0.5dp 微抬，对齐字符 settled）
    // 关键：用 animateFloatAsState 跑 focusSpec 让位移**平滑**变化（之前是直接赋值，
    // 进 / 出 active 瞬间会跳 1-2dp，再被 lift envelope "拉回"，看起来就是"先抖一下再落位"）。
    val isYrcLine = line.chars.isNotEmpty()
    val isActiveYrc = isActive && isYrcLine
    val isActiveLrc = isActive && !isYrcLine
    val rowTranslateYTargetDp: Float = when {
        !isYrcLine -> 0f
        isActiveYrc -> 0f
        isPast -> -0.5f
        else -> 1f
    }
    val rowTranslateYDp by animateFloatAsState(
        targetValue = rowTranslateYTargetDp,
        animationSpec = focusSpec,
        label = "lyricRowTy",
    )

    // ---- Lift envelope ----
    // active YRC 时从 0 平滑上升到 1（per-char wave 强度从无到全），离开 active 时再降回 0。
    // 用与 alpha/scale/blur/rowTy 相同的 300ms cubic-bezier 曲线，节奏一致。
    // → future → active 切入时 wave 慢慢"长出来"，active → past 切出时 wave 慢慢"收回去"，
    //   不会有 ±dp 的跳变，整体看起来只有焦点行有动效。
    val liftEnvelope by animateFloatAsState(
        targetValue = if (isActiveYrc) 1f else 0f,
        animationSpec = focusSpec,
        label = "liftEnvelope",
    )
    val rowBlurDp by animateFloatAsState(
        // blur 给所有行（LRC / YRC 都要）—— 模糊只是焦距感，不会引起位置错乱。
        targetValue = if (isActive) 0f else 2f,
        animationSpec = focusSpec,
        label = "lyricBlur",
    )
    // Apple Music 28sp Black 风格。
    //
    // ⚠ 不能用负 letterSpacing —— Compose 的 letter-spacing 是 per-glyph-run 应用的：
    //   inactive 所有字同色 → Compose 合并成 1 个 run → letter-spacing 在 N-1 个间隙
    //   都生效（紧凑）。Active 第一个字符颜色一变 → run 数增多 → run 间不应用 letter-spacing
    //   → 整行变宽几像素，看起来"右边抖一下"。letterSpacing = 0 让两态宽度一致。
    val style = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        lineHeight = 44.sp,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
            includeFontPadding = false,
        ),
    )

    // 渲染策略：
    //   - 非 active YRC（即 future / past / active LRC）走单层 Text：颜色由 baseColor 决定，
    //     位移由 rowTy 决定，最朴素。
    //   - active YRC：底层 Text 透明（只用来跑 layout/measure），覆一层 drawWithContent，
    //     按字符索引逐个 clip 出该字符的横向条带，每条带内的整行文本被 translateY (per-char)
    //     上移到对应位置。每个字符的 raw 用 sweep 的横坐标计算 —— 见 drawPerCharLiftedSweep。
    //     新版 3-phase wave：rest(+1dp) → peak(-1dp) → settled(-0.5dp)，每字符走完整波形再稳定。
    //
    // 字符之间没有 fontWeight / scale / blur 差异，只有颜色和 translateY，避免单字跳跃感。
    val baseColor = when {
        isPast -> fg
        isActiveLrc -> fg
        isActiveYrc -> Color.Transparent   // 上层 drawWithContent 重新逐字绘制
        else -> fgUnsung
    }
    var layout by remember(line.text) {
        androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null)
    }

    // shader 路径已禁用 —— 多次尝试（state-tracked / Offscreen / 多行扩展 / 类型修复）后仍有
    // 颜色异常或闪退问题，移动 GPU 驱动对 AGSL 的支持差异太大不可控。统一走 fallback per-char clipRect。
    @Suppress("UNUSED_VARIABLE")
    val cur = layout
    val canUseShader = false

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rowAlpha
                scaleX = rowScale
                scaleY = rowScale
                translationY = rowTranslateYDp.dp.toPx()
                // 起点左、中线锚定 —— 左对齐文本在 scale 时左边不平移，避免横向抖动。
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            // Modifier.blur 在 API 31+ 生效；旧版本静默忽略，不影响其他动效。
            .blur(rowBlurDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = line.text,
            color = baseColor,
            style = style,
            onTextLayout = { layout = it },
            modifier = if (isActiveYrc || liftEnvelope > 0.001f) {
                Modifier.drawWithContent {
                    drawContent()  // active YRC 时 baseColor=Transparent，drawContent 等同 no-op
                    val cur2 = layout
                    if (cur2 != null) {
                        drawPerCharLiftedSweep(
                            cur2, line.chars, positionMs, fg, fgUnsung,
                            envelope = liftEnvelope,
                        )
                    }
                }
            } else Modifier,
        )
    }
}

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

/**
 * 逐字符的"颜色 + lift"波浪（Apple Music 风涟漪）：
 *
 *   颜色 (colorProgress)：per-char 精确同步 sweep —— sweep 走过 charLeft → 染色，
 *                         保证"歌词颜色变化和音频同步"这条最硬的红线不破。
 *
 *   上浮 (3-phase wave)：每个字符走一段完整波形 rest → peak → settled，不再"飙到顶冻住"。
 *     · rest    = +1dp（未唱字微沉，与 row.future +1dp 对齐）
 *     · peak    = -1dp（短暂上冲，对应"被扫到瞬间"）
 *     · settled = -0.5dp（已唱字稳态，与 row.past -0.5dp 对齐）
 *     总位移 2dp（之前 3dp，按用户要求降 1dp）；峰值后回落 0.5dp 给"柔和落位"的尾韵，
 *     避免 smootherstep 末端导数为 0 + clamp 冻结造成的"跳到顶"观感。
 *
 *   窗口拉到 200dp（≈ 28sp 下 6-7 字宽，之前 60dp 只有 1-2 字）：同时有更多字符在过渡，
 *   相邻 ty 差从 ~1.5px 降到 ~0.5px，"裂开"感大幅减弱，整道波看起来是连续涟漪而不是格子跳。
 *
 *   字符之间没有 scale / blur / fontWeight 差异，只有颜色 + translateY。
 */
private fun DrawScope.drawPerCharLiftedSweep(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    envelope: Float = 1f,                       // 0 = 完全无 lift；1 = 完整波形
) {
    val sweep = computeSweepPos(layout, chars, positionMs)
    val text = layout.layoutInput.text.text
    // 三段式锚点：rest → peak → settled，envelope 按比例缩放整段位移
    val liftRestPx = 1f.dp.toPx() * envelope
    val liftPeakPx = (-1f).dp.toPx() * envelope
    val liftSettledPx = (-0.5f).dp.toPx() * envelope
    // 200dp 窗口 ≈ 6-7 个字符同步在过渡（之前 60dp 只有 1-2 字）
    val liftWindowPx = 200.dp.toPx()
    // 上升段占 50%、回落段占 50%（峰值居中），smootherstep 给两段各自柔和起止。
    // 注意：piecewise smootherstep 在 raw=0.5（peak）处导数=0，会让字符"在峰值短暂悬停"，
    // 这正是 Apple Music"涟漪到达再回落"的视觉特征，不是 bug。
    val peakSplit = 0.5f

    for (i in text.indices) {
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue   // 行末空白等零宽字符，跳过
        val charLine = layout.getLineForOffset(i)

        // 颜色：per-char 精确，与 sweep 严格同步
        val colorProgress: Float = when {
            sweep.notStarted -> 0f
            sweep.allDone -> 1f
            charLine < sweep.line -> 1f
            charLine > sweep.line -> 0f
            else -> ((sweep.x - box.left) / (box.right - box.left)).coerceIn(0f, 1f)
        }

        // 上浮：与 color 同锚（box.left），sweep 之后才开始 → "即将亮起"的字母完全不动
        val liftRaw: Float = when {
            sweep.notStarted -> 0f
            sweep.allDone -> 1f
            charLine < sweep.line -> 1f
            charLine > sweep.line -> 0f
            else -> ((sweep.x - box.left) / liftWindowPx).coerceIn(0f, 1f)
        }

        val ty = if (liftRaw < peakSplit) {
            // 抬起段：rest → peak
            val t = liftRaw / peakSplit
            val s = t * t * t * (t * (t * 6f - 15f) + 10f)   // smootherstep
            liftRestPx + (liftPeakPx - liftRestPx) * s
        } else {
            // 回落段：peak → settled
            val t = (liftRaw - peakSplit) / (1f - peakSplit)
            val s = t * t * t * (t * (t * 6f - 15f) + 10f)
            liftPeakPx + (liftSettledPx - liftPeakPx) * s
        }
        val color = lerp(fgUnsung, fg, colorProgress)

        // 多行文本严格按所在行 box clip，避免相邻行 ghost。
        // lineHeight 44sp > fontSize 28sp，行 box 内部上下各 ~8sp 空白能吸收 ±dp 位移。
        clipRect(
            left = box.left,
            top = layout.getLineTop(charLine),
            right = box.right,
            bottom = layout.getLineBottom(charLine),
            clipOp = ClipOp.Intersect,
        ) {
            drawText(layout, color = color, topLeft = Offset(0f, ty))
        }
    }
}


