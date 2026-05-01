package app.pipo.nativeapp.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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

    // 计算封面占据屏幕顶部的高度（跟 TransitioningCover 同步）
    val configuration = LocalConfiguration.current
    val screenWDp = configuration.screenWidthDp.dp
    val coverAreaHeight = screenWDp * progress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 容器根据 progress 从透明 fade 到不透明（标题/歌词跟着浮现）
            .graphicsLayer { alpha = progress }
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

@Composable
private fun AppleMusicLyricColumn(
    lines: List<PipoLyricLine>,
    activeLyricIndex: Int,
    positionMs: Long,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    modifier: Modifier = Modifier,
) {
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
    val scrollLookaheadMs = 600L
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
            animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing),
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
            itemsIndexed(
                items = lines,
                key = { _, line -> line.startMs },
            ) { idx, line ->
                val distance = kotlin.math.abs(idx - activeLyricIndex)
                // heightIn(min) 而不是 height(fixed) —— 长歌词自然换行成两行不被切。
                // LazyColumn 内部支持变高 item，animateScrollToItem 仍能按 idx 定位。
                Box(modifier = Modifier.heightIn(min = rowHeightDp)) {
                    AppleMusicLyricRow(
                        line = line,
                        isActive = idx == activeLyricIndex,
                        distance = distance,
                        positionMs = positionMs,
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
    distance: Int,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
) {
    // 距离活动行越远 alpha 越低。
    // distance-1（刚唱完那行）从 0.50 提到 0.70 —— 之前 alpha 1.0 → 0.5 衰减过大，
    // 看起来像"色变"。0.70 让上一句保留视觉重量，跟焦点行的过渡更顺滑。
    val targetAlpha = when (distance) {
        0 -> 1.0f
        1 -> 0.70f
        2 -> 0.40f
        3 -> 0.22f
        4 -> 0.12f
        else -> 0.06f
    }
    val rowAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(380),
        label = "lyricAlpha",
    )
    // Apple Music：28sp Black，letter-spacing -0.5sp。
    //
    // 锁死行高 + 锁死 baseline 在行内的位置 ——
    // 仅靠 lineHeight = 44sp 还不够：Compose paragraph 在 baselineShift 变化时会把
    // 基线在 lineHeight 块内重新定位（基线位置 = top + max ascent），这会把同行其它词
    // 一起带上下挪。把 LineHeightStyle 锁死 + includeFontPadding=false 一起用，
    // 基线在每帧的位置不再随 baselineShift 漂移：
    //   - Alignment.Center：基线相对块顶居中（= 固定的 lineHeight/2 偏移）
    //   - Trim.None：不裁前后 leading
    //   - includeFontPadding=false：去掉 Android 平台默认的字体 padding 影响
    val style = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
            includeFontPadding = false,
        ),
    )

    // ⚠ 关键：active 和 inactive 都走相同的 Text(annotated, style) 渲染路径。
    //   之前 inactive = Text(line.text, color = fg) vs active = Text(annotated)
    //   两条路径的 paragraph layout 在亚像素级有细微差别，切换瞬间字符微移
    //   ——用户报"快要唱时左右抖一下"。现在统一用 annotated，inactive 就是
    //   一段单 SpanStyle 全 fg；切换无 layout 变化，无抖动。
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        if (isActive && line.chars.isNotEmpty()) {
            // YRC: per-letter L→R sweep
            buildActiveAnnotated(line.chars, positionMs, fg, fgUnsung)
        } else {
            // 非活动 / LRC：整行单 SpanStyle 全 fg。透明度交给 row alpha 处理，
            // 不在字符颜色上做花样 —— 用户明确说"上一句要消失了，没必要动它颜色"
            append(line.text)
            addStyle(
                androidx.compose.ui.text.SpanStyle(color = fg),
                0, line.text.length,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rowAlpha }
            .padding(vertical = 8.dp),
    ) {
        Text(text = annotated, style = style)
    }
}

/** 给 buildAnnotatedString 复用的 helper —— 计算活动行的 per-letter spans */
private fun androidx.compose.ui.text.AnnotatedString.Builder.buildActiveAnnotated(
    chars: List<PipoLyricChar>,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
) {
    var idx = 0
    for (token in chars) {
        val p = token.progress(positionMs)
        val text = token.text
        val start = idx
        append(text)
        val end = start + text.length
        idx = end
        when {
            p >= 1f -> addStyle(
                androidx.compose.ui.text.SpanStyle(color = fg),
                start, end,
            )
            p <= 0f -> addStyle(
                androidx.compose.ui.text.SpanStyle(color = fgUnsung),
                start, end,
            )
            else -> {
                val n = text.length
                if (n == 1) {
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = lerp(fgUnsung, fg, p),
                        ),
                        start, end,
                    )
                } else {
                    for (i in 0 until n) {
                        val letterStart = i.toFloat() / n
                        val letterEnd = (i + 1f) / n
                        val letterT = ((p - letterStart) / (letterEnd - letterStart))
                            .coerceIn(0f, 1f)
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = lerp(fgUnsung, fg, letterT),
                            ),
                            start + i, start + i + 1,
                        )
                    }
                }
            }
        }
    }
}

// AppleMusicActiveLyricRow 已删除 —— 改成 AppleMusicLyricRow 直接 buildAnnotatedString
// + 调用 buildActiveAnnotated helper，active / inactive 共用同一个 Text 渲染路径。


