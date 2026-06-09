package app.pipo.nativeapp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.PipoLyricAlignment
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.progress
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 原生 AMLL 风歌词渲染器。
 *
 * 关键取舍：
 * - 不再用 LazyColumn 滚动歌词；所有行以绝对位移排布，纵向焦点由单一 spring 控制。
 * - 播放进度用稳定锚点 + Choreographer 帧外插，逐词动画不追 30Hz raw position。
 * - 单词颜色、上浮、慢词发光都在 draw 阶段读取同一时钟，不触发整列逐帧重组。
 */
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
    lineWidthAspect: Float = NATIVE_LINE_WIDTH_ASPECT,
    enterProgress: Float = 1f,
    // 固定歌词锚点的纵向偏移：正值=整体下移。横屏传 80dp 下移，竖屏默认 0 不变。
    anchorBiasDp: Dp = 0.dp,
) {
    val sessionKey = remember(sessionId, lines) { nativeLyricSessionKey(sessionId, lines) }
    val clockState = rememberNativeLyricClockMs(positionMs, isPlaying, sessionKey)
    val rawPositionState = rememberUpdatedState(positionMs)
    val hasWordTiming = remember(lines) { lines.any { it.chars.isNotEmpty() } }
    val hasDuetLine = remember(lines) { lines.any { it.alignment == PipoLyricAlignment.End } }
    val focusLeadMs = LyricTiming.focusLeadMs(lines)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()

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

    var containerWidthPx by remember(sessionKey) { mutableStateOf(0) }
    var containerHeightPx by remember(sessionKey) { mutableStateOf(0) }
    // 主体行高（不含译文，稳定）与译文完整高度（稳定）分开测量，
    // 让「切行滚动」与「译文展开」彻底解耦——译文展开不再经由整行 onSizeChanged
    // 反馈回 rowTop，从根上消除测量回路的一帧延迟造成的列表抽动。
    val mainRowHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    val transFullHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    val estimatedRowHeightPx = with(density) { rowMinHeight.toPx().toInt().coerceAtLeast(1) }
    val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
    val compactWidthPx = with(density) { NATIVE_COMPACT_WIDTH_DP.dp.toPx() }
    val effectsSettled = enterProgress >= NATIVE_EFFECTS_ENABLE_ENTER_PROGRESS

    // 全列共享单一译文展开进度：切换译文时整列同相，避免逐行各自 spring 的相位差与取整漂移。
    val transAnim = remember(sessionKey) { Animatable(if (showTranslation) 1f else 0f) }
    LaunchedEffect(showTranslation) {
        transAnim.animateTo(
            targetValue = if (showTranslation) 1f else 0f,
            animationSpec = spring(
                dampingRatio = nativeDampingRatio(
                    stiffness = NATIVE_SUBLINE_SLIDE_STIFFNESS,
                    damping = NATIVE_SUBLINE_SLIDE_DAMPING,
                ),
                stiffness = NATIVE_SUBLINE_SLIDE_STIFFNESS,
            ),
        )
    }
    val transProgress = transAnim.value.coerceAtLeast(0f)

    fun mainHeight(index: Int): Int = mainRowHeights[index]?.coerceAtLeast(1) ?: estimatedRowHeightPx
    fun transFull(index: Int): Int = transFullHeights[index]?.coerceAtLeast(0) ?: 0
    // 与各行 reveal 的 collapsedHeight 同源取整，保证 renderTop 与 transUpTo 逐像素一致。
    fun transContribution(index: Int): Int =
        (transFull(index) * transProgress).roundToInt().coerceAtLeast(0)
    fun rowHeight(index: Int): Int = mainHeight(index) + transContribution(index)
    // 渲染坐标：含译文展开（整行实际高度累加）。
    fun renderTop(index: Int): Float {
        var top = 0f
        for (i in 0 until index.coerceIn(0, lines.lastIndex + 1)) {
            top += rowHeight(i)
        }
        return top
    }
    // 译文在某行之上累计撑开的高度（同步随 transProgress 变化）。
    fun transUpTo(index: Int): Float {
        var sum = 0f
        for (i in 0 until index.coerceIn(0, lines.lastIndex + 1)) {
            sum += transContribution(i)
        }
        return sum
    }
    // 基准坐标：剔除译文展开，仅由主体高度累加，scrollSpring 全程工作在此坐标系。
    fun rowTopBase(index: Int): Float = renderTop(index) - transUpTo(index)
    fun rowAnchor(index: Int): Float {
        val safeIndex = index.coerceIn(lines.indices)
        return rowTopBase(safeIndex)
    }
    // 基准坐标位置 → 需叠加的译文累计量：随基准位置连续累加每行译文高度，
    // 跨越某行时按比例计入。这样「切行滚动」与「译文展开」都连续无跳变
    // （切行时译文高度随 spring 平滑分摊，而非整段瞬跳）。
    fun transOffsetForBase(basePos: Float): Float {
        var baseTop = 0f
        var sum = 0f
        for (i in lines.indices) {
            val h = mainHeight(i).toFloat()
            val t = transContribution(i).toFloat()
            if (basePos >= baseTop + h) {
                sum += t
                baseTop += h
            } else {
                if (basePos > baseTop && h > 0f) sum += t * ((basePos - baseTop) / h)
                break
            }
        }
        return sum
    }
    // 渲染坐标 → 基准坐标：手动拖动以 1:1 手感工作在渲染坐标，松手时换算回基准交给 spring。
    fun baseForRenderCenter(renderPos: Float): Float {
        var renderTopAcc = 0f
        var baseTopAcc = 0f
        for (i in lines.indices) {
            val h = mainHeight(i).toFloat()
            val rowRender = h + transContribution(i).toFloat()
            if (renderPos >= renderTopAcc + rowRender) {
                renderTopAcc += rowRender
                baseTopAcc += h
            } else {
                if (renderPos > renderTopAcc && rowRender > 0f) {
                    baseTopAcc += h * ((renderPos - renderTopAcc) / rowRender)
                }
                break
            }
        }
        return baseTopAcc
    }

    val timelineSnapshot by remember(lines, hasWordTiming, focusLeadMs) {
        derivedStateOf {
            val clockMs = rawPositionState.value
            nativeTimelineSnapshot(
                lines = lines,
                positionMs = clockMs + focusLeadMs,
                targetPositionMs = clockMs + focusLeadMs,
            )
        }
    }
    val playbackActiveIdx = timelineSnapshot.targetIndex
    val singingAnchorIdx = (timelineSnapshot.activeIndices.minOrNull() ?: playbackActiveIdx)
        .coerceIn(lines.indices)
    val latestActiveIdx = timelineSnapshot.latestIndex
    val interlude: NativeInterlude? = null
    val activeLineTopLiftPx = estimatedRowHeightPx.toFloat() * NATIVE_ACTIVE_LINE_TOP_UPSHIFT_ROWS
    val activeLineExtraLiftPx = with(density) { NATIVE_ACTIVE_LINE_EXTRA_UPSHIFT_DP.dp.toPx() }
    val anchorBiasPx = with(density) { anchorBiasDp.toPx() }
    val anchorYPx = containerHeightPx * NATIVE_ALIGN_POSITION - activeLineTopLiftPx - activeLineExtraLiftPx + anchorBiasPx

    val scrollSpring = remember(sessionKey) {
        Animatable(rowAnchor(activeLyricIndex.coerceIn(lines.indices)))
    }
    var lastScrollTargetIdx by remember(sessionKey) { mutableStateOf(playbackActiveIdx) }
    var initialLayoutSettled by remember(sessionKey) { mutableStateOf(false) }
    var lastScrollTargetCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var lastLayoutAnchorIdx by remember(sessionKey) { mutableStateOf(singingAnchorIdx) }
    var lastLayoutAnchorCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var motionFromCenter by remember(sessionKey) {
        mutableFloatStateOf(rowAnchor(activeLyricIndex.coerceIn(lines.indices)))
    }
    var motionFromIdx by remember(sessionKey) { mutableStateOf(activeLyricIndex.coerceIn(lines.indices)) }
    var motionTargetIdx by remember(sessionKey) { mutableStateOf(playbackActiveIdx) }
    val scrollTargetCenter = rowAnchor(playbackActiveIdx.coerceIn(lines.indices))
    val layoutAnchorCenter = rowAnchor(singingAnchorIdx)
    val measuredRowCount = mainRowHeights.size
    var manualScrollCenterPx by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var manualHoldUntilMs by remember(sessionKey) { mutableLongStateOf(0L) }
    var isUserDragging by remember(sessionKey) { mutableStateOf(false) }
    // manualScrollActive 用 derivedStateOf 包裹：拖动中 manualScrollCenterPx 每帧变，但 isFinite()
    // 结果稳定，derivedState 只在「是否手动」真正切换时通知下游，避免每帧重组。
    val manualScrollActive by remember(sessionKey) {
        derivedStateOf { manualScrollCenterPx.isFinite() }
    }
    // 渲染中心（含译文偏移）：scrollSpring 工作在基准坐标、手动拖动用 1:1 渲染坐标。
    // 关键性能约束：只在 layout(offset) / draw(graphicsLayer) / 事件回调里调用本函数，
    // 绝不要在 composition 顶层读 —— 否则 spring/拖动每帧都会触发整列重组（不流畅根源）。
    fun renderCenterNow(): Float = manualScrollCenterPx.let { m ->
        if (m.isFinite()) m else scrollSpring.value + transOffsetForBase(scrollSpring.value)
    }
    fun focusCenterBaseNow(): Float = manualScrollCenterPx.let { m ->
        if (m.isFinite()) baseForRenderCenter(m) else scrollSpring.value
    }

    LaunchedEffect(
        sessionKey,
        scrollTargetCenter,
        layoutAnchorCenter,
        playbackActiveIdx,
        singingAnchorIdx,
        interlude != null,
        measuredRowCount,
        containerHeightPx,
        isPlaying,
        manualScrollActive,
        isUserDragging,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (manualScrollActive || isUserDragging || manualHoldUntilMs > now) {
            lastScrollTargetIdx = playbackActiveIdx
            lastScrollTargetCenter = scrollTargetCenter
            lastLayoutAnchorIdx = singingAnchorIdx
            lastLayoutAnchorCenter = layoutAnchorCenter
            return@LaunchedEffect
        }
        val previousIdx = lastScrollTargetIdx
        val previousTargetCenter = lastScrollTargetCenter
        val previousLayoutAnchorIdx = lastLayoutAnchorIdx
        val previousLayoutAnchorCenter = lastLayoutAnchorCenter
        if (previousIdx != playbackActiveIdx) {
            motionFromCenter = scrollSpring.value
            motionFromIdx = previousIdx.coerceIn(lines.indices)
            motionTargetIdx = playbackActiveIdx
        }
        lastScrollTargetIdx = playbackActiveIdx
        lastScrollTargetCenter = scrollTargetCenter
        lastLayoutAnchorIdx = singingAnchorIdx
        lastLayoutAnchorCenter = layoutAnchorCenter
        val sameTargetLayoutShift =
            initialLayoutSettled &&
                previousIdx == playbackActiveIdx &&
                previousLayoutAnchorIdx == singingAnchorIdx &&
                previousLayoutAnchorCenter.isFinite()
        if (sameTargetLayoutShift) {
            val layoutDelta = layoutAnchorCenter - previousLayoutAnchorCenter
            if (kotlin.math.abs(layoutDelta) > 0.5f) {
                scrollSpring.snapTo(scrollSpring.value + layoutDelta)
                motionFromCenter += layoutDelta
            }
        }
        val distance = kotlin.math.abs(scrollSpring.value - scrollTargetCenter)
        val targetIdx = playbackActiveIdx.coerceIn(lines.indices)
        val targetRowHeight = rowHeight(targetIdx).toFloat()
        val targetRowTop = rowTopBase(targetIdx) + anchorYPx - scrollSpring.value
        val targetLineInvisible = containerHeightPx > 0 &&
            (targetRowTop + targetRowHeight < 0f || targetRowTop > containerHeightPx.toFloat())
        if (!initialLayoutSettled || targetLineInvisible) {
            scrollSpring.snapTo(scrollTargetCenter)
            motionFromCenter = scrollTargetCenter
            motionFromIdx = playbackActiveIdx
            motionTargetIdx = playbackActiveIdx
            if (!initialLayoutSettled && containerHeightPx > 0 && measuredRowCount > 0) {
                initialLayoutSettled = true
            }
            return@LaunchedEffect
        }
        if (!isPlaying) {
            scrollSpring.stop()
            motionFromCenter = scrollSpring.value
            motionFromIdx = playbackActiveIdx
            motionTargetIdx = playbackActiveIdx
            return@LaunchedEffect
        }
        if (distance > NATIVE_SCROLL_SNAP_DISTANCE_PX || kotlin.math.abs(playbackActiveIdx - previousIdx) > 6) {
            scrollSpring.snapTo(scrollTargetCenter)
            motionFromCenter = scrollTargetCenter
            motionFromIdx = playbackActiveIdx
            motionTargetIdx = playbackActiveIdx
            return@LaunchedEffect
        }
        scrollSpring.animateTo(
            targetValue = scrollTargetCenter,
            animationSpec = nativeScrollSpringSpec(
                lines = lines,
                activeIdx = playbackActiveIdx,
                isInterludeActive = interlude != null,
            ),
        )
    }

    LaunchedEffect(manualHoldUntilMs, isUserDragging, scrollTargetCenter, playbackActiveIdx) {
        val waitMs = manualHoldUntilMs - SystemClock.elapsedRealtime()
        if (waitMs > 0L || isUserDragging) {
            delay(waitMs.coerceAtLeast(0L))
        }
        if (!isUserDragging && manualHoldUntilMs <= SystemClock.elapsedRealtime() && manualScrollCenterPx.isFinite()) {
            // manualScrollCenterPx 是渲染坐标，换算回基准坐标后交给 spring 复位。
            val startCenter = baseForRenderCenter(manualScrollCenterPx)
            manualScrollCenterPx = Float.NaN
            scrollSpring.snapTo(startCenter)
            motionFromCenter = startCenter
            motionFromIdx = lines.indices.minByOrNull { idx -> kotlin.math.abs(rowAnchor(idx) - startCenter) }
                ?: playbackActiveIdx
            motionTargetIdx = playbackActiveIdx
            scrollSpring.animateTo(
                targetValue = scrollTargetCenter,
                animationSpec = nativeManualRestoreSpringSpec(),
            )
        }
    }

    // derivedStateOf：拖动中每帧重算就近行，但只在「行号真正变化」时才通知下游（渲染窗口/焦点），
    // 把手动滚动的重组从「每帧」降到「每越过一行」；自动播放时它等于 targetIndex（切句才变）。
    val visualActiveIdx by remember(sessionKey) {
        derivedStateOf {
            val pa = timelineSnapshot.targetIndex.coerceIn(lines.indices)
            val m = manualScrollCenterPx
            if (!m.isFinite()) {
                pa
            } else {
                lines.indices.minByOrNull { kotlin.math.abs(renderTop(it) - m) } ?: pa
            }
        }
    }

    val lineWidthAspectState = rememberUpdatedState(lineWidthAspect.coerceIn(0.2f, 1f))
    val topFadeEnd = 0.16f
    val bottomSolidStop = bottomFadeStart.coerceIn(0.60f, 0.96f)
    val bottomSoftStop = bottomFadeSoftEnd.coerceIn(bottomSolidStop, 0.99f)

    Box(
        modifier = modifier
            .onSizeChanged {
                containerWidthPx = it.width
                containerHeightPx = it.height
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.05f to Color.Black.copy(alpha = 0.12f),
                            0.11f to Color.Black.copy(alpha = 0.72f),
                            topFadeEnd to Color.Black,
                            bottomSolidStop to Color.Black,
                            bottomSoftStop to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            .pointerInput(sessionKey) {
                detectDragGestures(
	                    onDragStart = {
	                        isUserDragging = true
	                        manualHoldUntilMs = SystemClock.elapsedRealtime() + NATIVE_MANUAL_HOLD_MS
	                        // 拖动工作在渲染坐标（1:1 手感），以当前渲染中心为起点。
	                        manualScrollCenterPx = renderCenterNow()
	                        gestureScope.launch {
	                            scrollSpring.stop()
	                        }
	                    },
                    onDragEnd = {
                        isUserDragging = false
                        manualHoldUntilMs = SystemClock.elapsedRealtime() + NATIVE_MANUAL_HOLD_MS
                    },
                    onDragCancel = {
                        isUserDragging = false
                        manualHoldUntilMs = SystemClock.elapsedRealtime() + NATIVE_MANUAL_HOLD_MS
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        manualHoldUntilMs = SystemClock.elapsedRealtime() + NATIVE_MANUAL_HOLD_MS
                        val currentCenter = if (manualScrollCenterPx.isFinite()) {
                            manualScrollCenterPx
                        } else {
                            renderCenterNow()
                        }
                        val next = nativeClampScrollCenter(
                            current = currentCenter - dragAmount.y,
                            anchorY = anchorYPx,
                            // manualScrollCenterPx 工作在渲染坐标，用含译文的整行高度合计夹取边界。
                            totalHeight = (0 until lines.size).sumOf { rowHeight(it) }.toFloat(),
                            viewportHeight = containerHeightPx.toFloat(),
                        )
                        manualScrollCenterPx = next
                    },
                )
            },
    ) {
        // 渲染窗口按「行号」裁剪（基于 visualActiveIdx），不再依赖每帧 rowY：
        // 用最小行高估算半径（高估行数→多渲染几行，保证滚动时不漏、不空白）。
        val renderRadiusRows = if (containerHeightPx > 0) {
            containerHeightPx / estimatedRowHeightPx.coerceAtLeast(1) + NATIVE_RENDER_WINDOW_BUFFER_ROWS
        } else {
            NATIVE_INITIAL_RENDER_RADIUS_LINES
        }
        lines.forEachIndexed { idx, line ->
            val distance = kotlin.math.abs(idx - visualActiveIdx)
            if (distance > renderRadiusRows) {
                return@forEachIndexed
            }
            val rowEnter = nativeRowEnterProgress(enterProgress, distance)
            val isActive = idx in timelineSnapshot.activeIndices
            // 焦点统一为一个 composition 稳定的布尔（仅切句/越行才变）；缩放/上浮/透明度/虚化
            // 都在行内用 tween 平滑驱动，滚动期间不再每帧重组（流畅度关键）。
            val isFocused = if (manualScrollActive) {
                idx == visualActiveIdx
            } else {
                idx == playbackActiveIdx ||
                    idx == lastScrollTargetIdx ||
                    (idx == singingAnchorIdx && isActive)
            }
            val isPast = idx < playbackActiveIdx && !isActive
            val isCompactLayout = with(density) { containerWidthPx.toDp().value <= NATIVE_COMPACT_LAYOUT_DP }
            val blurTarget = nativeLineBlur(
                itemIndex = idx,
                scrollToIndex = playbackActiveIdx,
                latestIndex = latestActiveIdx,
                isActive = isFocused,
                isUserDragging = isUserDragging,
                isCompact = isCompactLayout,
            )
            val opacityTarget = nativeLineOpacity(
                isActive = isFocused,
            )
            val itemAlignment = if (line.alignment == PipoLyricAlignment.End) Alignment.TopEnd else Alignment.TopStart
            val lineWidthPx = nativeLineWidthPx(
                containerWidthPx = containerWidthPx,
                horizontalPaddingPx = horizontalPaddingPx,
                compactWidthPx = compactWidthPx,
                aspect = lineWidthAspectState.value,
            )
            val lineWidthDp = with(density) { lineWidthPx.toDp() }
            val duetInsetDp = with(density) {
                (lineWidthPx * NATIVE_DUET_INSET_ASPECT).toDp()
            }
            val duetStartPadding = if (hasDuetLine && line.alignment == PipoLyricAlignment.End) duetInsetDp else 0.dp
            val duetEndPadding = if (hasDuetLine && line.alignment != PipoLyricAlignment.End) duetInsetDp else 0.dp
            val lineContentWidthPx = (
                lineWidthPx - if (hasDuetLine) lineWidthPx * NATIVE_DUET_INSET_ASPECT else 0f
                ).coerceAtLeast(1f)
            val rowScaleAnchor = rowAnchor(idx)
            val rowScaleSpanPx = estimatedRowHeightPx.toFloat() * NATIVE_ROW_SCALE_FOCUS_SPAN_ROWS
            val rowFocusProvider: () -> Float = {
                nativePositionFocus(
                    rowAnchor = rowScaleAnchor,
                    focusAnchor = focusCenterBaseNow(),
                    spanPx = rowScaleSpanPx,
                )
            }
            // 渲染窗口内的行才会被组合，屏外 buffer 行用户点不到，所以不再按 rowY 判定可点击。
            val rowVisibleForClick = enterProgress >= NATIVE_ROW_CLICK_ENTER_PROGRESS &&
                rowEnter > NATIVE_ROW_CLICK_MIN_ALPHA
            val rowInteractionSource = remember(line.startMs, line.text) { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    // 行位置在 layout 阶段计算：读取滚动中心只触发重新布局、不触发重组，滚动因此流畅。
                    .offset {
                        IntOffset(0, (renderTop(idx) + anchorYPx - renderCenterNow()).roundToInt())
                    }
                    .graphicsLayer {
                        alpha = rowEnter
                    },
                contentAlignment = itemAlignment,
            ) {
                Box(
                    modifier = Modifier
                        .width(lineWidthDp)
                        .padding(start = duetStartPadding, end = duetEndPadding)
                        .then(
                            if (rowVisibleForClick) {
                                Modifier.clickable(
                                    interactionSource = rowInteractionSource,
                                    indication = null,
	                                    onClick = {
	                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
	                                        manualHoldUntilMs = 0L
                                            isUserDragging = false
                                            val seekCenter = rowAnchor(idx)
                                            manualScrollCenterPx = Float.NaN
                                            gestureScope.launch {
                                                scrollSpring.stop()
                                                // 处于手动态时渲染坐标≠基准坐标，先无缝接续，避免点击瞬跳。
                                                scrollSpring.snapTo(baseForRenderCenter(renderCenterNow()))
                                                motionFromCenter = scrollSpring.value
                                                motionFromIdx = visualActiveIdx
                                                motionTargetIdx = idx
                                                scrollSpring.animateTo(
                                                    targetValue = seekCenter,
                                                    animationSpec = nativeSeekSpringSpec(),
                                                )
                                            }
	                                        onSeekToMs(LyricTiming.audioStartMs(line).coerceAtLeast(0L))
	                                    },
	                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = itemAlignment,
                ) {
                    NativeAmllLyricRow(
                        line = line,
                        isActive = isActive,
                        isFocused = isFocused,
                        isPast = isPast,
                        positionMs = positionMs,
                        clockState = clockState,
                        fg = fg,
                        fgDim = fgDim,
                        fgUnsung = fgUnsung,
                        transProgress = transProgress,
                        onMainHeight = { h -> if (mainRowHeights[idx] != h) mainRowHeights[idx] = h },
                        onTransFullHeight = { h -> if (transFullHeights[idx] != h) transFullHeights[idx] = h },
                        rowMinHeight = rowMinHeight,
                        isUserDragging = isUserDragging,
                        isPlaying = isPlaying,
                        targetOpacity = opacityTarget,
                        targetBlur = blurTarget,
                        rowFocusProvider = rowFocusProvider,
                        fontSize = lyricFontSize,
                        lineHeight = lyricLineHeight,
                        fontWeight = lyricFontWeight,
                        verticalPadding = rowVerticalPadding,
                        lineWidthPx = lineContentWidthPx,
                        effectsEnabled = effectsSettled,
                    )
                }
            }
        }

        if (interlude != null && containerWidthPx > 0 && containerHeightPx > 0) {
            val nextIndex = (interlude.anchorLineIndex + 1).coerceIn(lines.indices)
            val nextLine = lines[nextIndex]
            val lineWidthPx = nativeLineWidthPx(
                containerWidthPx = containerWidthPx,
                horizontalPaddingPx = horizontalPaddingPx,
                compactWidthPx = compactWidthPx,
                aspect = lineWidthAspectState.value,
            )
            val fontPx = with(density) { lyricFontSize.toPx() }
            val dotSizePx = nativeInterludeDotSizePx(fontPx, containerHeightPx.toFloat())
            val dotGapPx = dotSizePx * NATIVE_INTERLUDE_DOT_GAP_EM
            val dotsWidthPx = dotSizePx * 3f + dotGapPx * 2f
            val dotMarginPx = fontPx * NATIVE_INTERLUDE_MARGIN_EM
            val nextRowTop = renderTop(nextIndex) + anchorYPx - renderCenterNow()
            val dotsY = nextRowTop - dotMarginPx - dotSizePx
            val alignEnd = nextLine.alignment == PipoLyricAlignment.End

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer { translationY = dotsY },
                contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
            ) {
                Box(
                    modifier = Modifier.width(with(density) { lineWidthPx.toDp() }),
                    contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
                ) {
                    NativeInterludeDots(
                        interlude = interlude,
                        clockState = clockState,
                        offsetMs = 20L,
                        color = fg,
                        dotSize = with(density) { dotSizePx.toDp() },
                        dotGap = with(density) { dotGapPx.toDp() },
                        width = with(density) { dotsWidthPx.toDp() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeAmllLyricRow(
    line: PipoLyricLine,
    isActive: Boolean,
    isFocused: Boolean,
    isPast: Boolean,
    positionMs: Long,
    clockState: State<Float>,
    fg: Color,
    fgDim: Color,
    fgUnsung: Color,
    transProgress: Float,
    onMainHeight: (Int) -> Unit,
    onTransFullHeight: (Int) -> Unit,
    rowMinHeight: Dp,
    isUserDragging: Boolean,
    isPlaying: Boolean,
    targetOpacity: Float,
    targetBlur: Float,
    rowFocusProvider: () -> Float,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    verticalPadding: Dp,
    lineWidthPx: Float,
    effectsEnabled: Boolean,
) {
    val textMeasurer = rememberTextMeasurer()
    val ease = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }
    // 背景人声（大字浮入、参与时间轴）= Companion；小字行 = 罗马音(音译) + 翻译。
    val timedCompanions = remember(line) { line.companionLines.filter { it.role == PipoLyricRole.Companion } }
    // 罗马音排在翻译之前：主词 → 罗马音 → 翻译，对齐 AMLL / Apple 的子行顺序。
    val translations = remember(line) {
        line.companionLines
            .filter { it.role == PipoLyricRole.Translation || it.role == PipoLyricRole.Romaji }
            .sortedBy { if (it.role == PipoLyricRole.Romaji) 0 else 1 }
    }
    val mainStartMs = remember(line) { nativeLineMainStartMs(line) }
    val leadingCompanions = remember(line, mainStartMs) {
        timedCompanions.filter { nativeLineAudioStartMs(it) < mainStartMs }
    }
    val trailingCompanions = remember(line, mainStartMs) {
        timedCompanions.filter { nativeLineAudioStartMs(it) >= mainStartMs }
    }
    val rowAlpha by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec = tween(durationMillis = if (isFocused) 120 else 260, easing = ease),
        label = "nativeLyricRowAlpha",
    )
    val rowBlur by animateFloatAsState(
        targetValue = if (effectsEnabled) targetBlur else 0f,
        animationSpec = tween(durationMillis = 260, easing = ease),
        label = "nativeLyricRowBlur",
    )
    val maskAlpha = rememberNativeMaskAlpha(
        target = nativeMaskAlpha(gradient = isActive || isFocused),
    )
    val pivotX = if (line.alignment == PipoLyricAlignment.End) 1f else 0f
    val itemAlignment = if (line.alignment == PipoLyricAlignment.End) Alignment.End else Alignment.Start
    val textAlign = if (line.alignment == PipoLyricAlignment.End) TextAlign.End else TextAlign.Start
    val mainTextStyle = nativeLyricTextStyle(fontSize, lineHeight, fontWeight, textAlign)
    val displayLine = remember(line, lineWidthPx, mainTextStyle, textMeasurer) {
        nativeBalancedLyricLine(
            line = line,
            containerWidthPx = lineWidthPx,
            style = mainTextStyle,
            textMeasurer = textMeasurer,
        )
    }
    // 行距全部放在顶部（= 2×verticalPadding，保持原相邻行 16dp 间距），底部不留白：
    // 译文紧贴主歌词，且收起时尾部无空白会被 clip 透出。
    val rowTopPadPx = with(LocalDensity.current) { (verticalPadding * 2).roundToPx() }
    val rowMinHeightPx = with(LocalDensity.current) { rowMinHeight.roundToPx() }
    // 主体与译文以单次 Layout 组合：主体高度（含行距、应用最小行高）稳定且不含译文，
    // 译文完整高度单独上报，折叠高度 = transFull × transProgress；二者高度彻底分离，
    // 译文展开/收起不再经测量回路扰动 rowTop/scrollSpring，从根上消除列表抽动。
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val rowScale = nativeRowScale(rowFocusProvider())
                alpha = rowAlpha
                scaleX = rowScale
                scaleY = rowScale
                // 绕行中心缩放：放大/缩小不再把内容往下顶（pivotY=0 顶边缩放会让放大时底部下坠，
                // 切句时表现为“先往下顶一下再滚上去”）。水平仍按对齐边（左/右）作支点。
                transformOrigin = TransformOrigin(pivotX, 0.5f)
            }
            .blur(rowBlur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .clipToBounds(),
        content = {
            // slot 0：主体（伴唱 + 主歌词），不含纵向行距；高度稳定。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
        leadingCompanions.forEach { companion ->
            NativeAmllCompanionLine(
                companion = companion,
                hostActive = isActive,
                itemAlignment = itemAlignment,
                positionMs = positionMs,
                clockState = clockState,
                fg = fg,
                fgUnsung = fgUnsung,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maskAlpha = maskAlpha,
                isPlaying = isPlaying,
                isBeforeMain = true,
                ease = ease,
                lineWidthPx = lineWidthPx,
                effectsEnabled = effectsEnabled,
            )
        }

        Box(modifier = Modifier.align(itemAlignment)) {
            NativeAmllLyricText(
                line = displayLine,
                isActive = isActive,
                isFocused = isFocused,
                isPast = isPast,
                positionMs = positionMs,
                clockState = if (line.chars.isNotEmpty() || isActive) clockState else null,
                motionFocus = if (isFocused) 1f else 0f,
                motionFocusProvider = rowFocusProvider,
                fg = fg,
                fgUnsung = fgUnsung,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = fontWeight,
                textAlign = textAlign,
                maskAlpha = maskAlpha,
                effectsEnabled = effectsEnabled,
            )
        }

        trailingCompanions.forEach { companion ->
            NativeAmllCompanionLine(
                companion = companion,
                hostActive = isActive,
                itemAlignment = itemAlignment,
                positionMs = positionMs,
                clockState = clockState,
                fg = fg,
                fgUnsung = fgUnsung,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maskAlpha = maskAlpha,
                isPlaying = isPlaying,
                isBeforeMain = false,
                ease = ease,
                lineWidthPx = lineWidthPx,
                effectsEnabled = effectsEnabled,
            )
        }

            }
            // slot 1：译文完整内容（顶部 1dp 贴合主歌词；该 1dp 计入 transFull，progress=0 时一并收起）。
            if (translations.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    translations.forEach { translation ->
                        NativeAmllTranslationLine(
                            translation = translation,
                            progress = transProgress,
                            itemAlignment = itemAlignment,
                            textAlign = textAlign,
                            fg = fg,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // 最小行高内化到主体测量：保证 measuredMainHeight≥rowMinHeight，且主体下方无留白可透出译文。
        // coerce 上界防止容器尚未测量（maxHeight=0）时 minHeight>maxHeight 崩溃。
        val bodyMinHeight = (rowMinHeightPx - rowTopPadPx).coerceIn(0, constraints.maxHeight)
        val bodyPlaceable = measurables[0].measure(constraints.copy(minHeight = bodyMinHeight))
        val transPlaceable = measurables.getOrNull(1)?.measure(constraints)
        val measuredMainHeight = bodyPlaceable.height + rowTopPadPx
        onMainHeight(measuredMainHeight)
        val fullTrans = transPlaceable?.height ?: 0
        onTransFullHeight(fullTrans)
        val collapsedTrans = (fullTrans * transProgress).roundToInt().coerceAtLeast(0)
        val width = constraints.maxWidth
        val totalHeight = measuredMainHeight + collapsedTrans
        layout(width, totalHeight) {
            // 顶部留行距；译文紧贴主体底部展开，超出 collapsedTrans 的部分由 clipToBounds 收起（底部无留白，收起时不透出）。
            bodyPlaceable.place(0, rowTopPadPx)
            transPlaceable?.place(0, rowTopPadPx + bodyPlaceable.height)
        }
    }
}

@Composable
private fun NativeAmllCompanionLine(
    companion: PipoLyricLine,
    hostActive: Boolean,
    itemAlignment: Alignment.Horizontal,
    positionMs: Long,
    clockState: State<Float>,
    fg: Color,
    fgUnsung: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    maskAlpha: NativeMaskAlpha,
    isPlaying: Boolean,
    isBeforeMain: Boolean,
    ease: CubicBezierEasing,
    lineWidthPx: Float,
    effectsEnabled: Boolean,
) {
    val textMeasurer = rememberTextMeasurer()
    val companionActive = nativeIsCompanionActive(companion, positionMs)
    val companionVisible = nativeIsCompanionVisible(companion, positionMs)
    // 副词（背景人声）一旦出现就保持显示，不再随时间收起：反复收起/重现会来回改变行高，
    // 经测量回路扰动 rowTop 造成列表抖动。出现时浮入一次即可，之后常驻。
    var hasAppeared by remember(companion) { mutableStateOf(companionVisible || !isPlaying) }
    LaunchedEffect(companion, companionVisible) {
        if (companionVisible) hasAppeared = true
    }
    val targetAppear = if (hasAppeared || !isPlaying) 1f else 0f
    val appearAnim = remember(companion) { Animatable(targetAppear) }
    LaunchedEffect(companion, targetAppear) {
        appearAnim.animateTo(
            targetValue = targetAppear,
            animationSpec = spring(
                dampingRatio = nativeDampingRatio(
                    stiffness = NATIVE_BG_SLIDE_STIFFNESS,
                    damping = NATIVE_BG_SLIDE_DAMPING,
                ),
                stiffness = NATIVE_BG_SLIDE_STIFFNESS,
            ),
        )
    }
    val appear = if (isPlaying) ease.transform(appearAnim.value.coerceIn(0f, 1f)) else 1f
    val companionAlignment = if (companion.alignment == PipoLyricAlignment.End) Alignment.End else itemAlignment
    val companionTextAlign = if (companionAlignment == Alignment.End) TextAlign.End else TextAlign.Start
    val boxAlignment = if (companionAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart
    val companionFontSize = fontSize * 0.70f
    val companionLineHeight = lineHeight * 0.84f
    val companionStyle = nativeLyricTextStyle(
        fontSize = companionFontSize,
        lineHeight = companionLineHeight,
        fontWeight = FontWeight.SemiBold,
        textAlign = companionTextAlign,
    )
    val displayCompanion = remember(companion, lineWidthPx, companionStyle, textMeasurer) {
        nativeBalancedLyricLine(
            line = companion,
            containerWidthPx = lineWidthPx,
            style = companionStyle,
            textMeasurer = textMeasurer,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .nativeBackgroundVocalReveal(appear = appear, isBeforeMain = isBeforeMain)
            .graphicsLayer {
                alpha = appear
                val scale = NATIVE_BG_WRAPPER_MIN_SCALE + (1f - NATIVE_BG_WRAPPER_MIN_SCALE) * appear
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (companionAlignment == Alignment.End) 1f else 0f,
                    pivotFractionY = if (isBeforeMain) 1f else 0f,
                )
            },
        contentAlignment = boxAlignment,
    ) {
        NativeAmllLyricText(
            line = displayCompanion,
            isActive = companionActive,
            isPast = nativeIsCompanionPast(companion, positionMs),
            positionMs = positionMs,
            clockState = if (companion.chars.isNotEmpty()) clockState else null,
            fg = fg,
            fgUnsung = fgUnsung,
            fontSize = companionFontSize,
            lineHeight = companionLineHeight,
            fontWeight = FontWeight.SemiBold,
            textAlign = companionTextAlign,
            isBackgroundVocal = false,
            maskAlpha = maskAlpha,
            effectsEnabled = effectsEnabled,
        )
    }
}

@Composable
private fun NativeAmllTranslationLine(
    translation: PipoLyricLine,
    progress: Float,
    itemAlignment: Alignment.Horizontal,
    textAlign: TextAlign,
    fg: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    val hiddenSlidePx = with(LocalDensity.current) { NATIVE_SUBLINE_HIDDEN_SLIDE_DP.dp.toPx() }
    // 高度折叠由外层容器统一处理；这里只做淡入、缩放与轻微上滑，全部跟随同一进度。
    val appear = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear
                scaleX = NATIVE_SUBLINE_WRAPPER_MIN_SCALE + (1f - NATIVE_SUBLINE_WRAPPER_MIN_SCALE) * appear
                scaleY = NATIVE_SUBLINE_WRAPPER_MIN_SCALE + (1f - NATIVE_SUBLINE_WRAPPER_MIN_SCALE) * appear
                translationY = (1f - appear) * hiddenSlidePx
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (itemAlignment == Alignment.End) 1f else 0f,
                    pivotFractionY = 0f,
                )
            },
        contentAlignment = if (itemAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = translation.text,
            color = nativeTranslationColor(fg),
            style = nativeLyricTextStyle(
                fontSize = fontSize * NATIVE_SUBLINE_FONT_SCALE,
                lineHeight = lineHeight * NATIVE_SUBLINE_LINE_HEIGHT_SCALE,
                fontWeight = FontWeight.SemiBold,
                textAlign = textAlign,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.nativeBackgroundVocalReveal(
    appear: Float,
    isBeforeMain: Boolean,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val progress = appear.coerceIn(0f, 1f)
    val collapsedHeight = (placeable.height * progress).roundToInt()
    val hiddenSlide = placeable.height * NATIVE_BG_HIDDEN_SLIDE_RATIO * (1f - progress)
    val y = if (isBeforeMain) {
        collapsedHeight - placeable.height + hiddenSlide.roundToInt()
    } else {
        -hiddenSlide.roundToInt()
    }
    layout(placeable.width, collapsedHeight) {
        placeable.placeRelative(0, y)
    }
}

@Composable
private fun NativeAmllLyricText(
    line: PipoLyricLine,
    isActive: Boolean,
    isFocused: Boolean = isActive,
    isPast: Boolean,
    positionMs: Long,
    clockState: State<Float>? = null,
    motionFocus: Float = if (isActive) 1f else 0f,
    motionFocusProvider: (() -> Float)? = null,
    fg: Color,
    fgUnsung: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    isBackgroundVocal: Boolean = false,
    maskAlpha: NativeMaskAlpha = NativeMaskAlpha.Solid,
    effectsEnabled: Boolean = true,
) {
    var layout by remember(line.startMs, line.text) { mutableStateOf<TextLayoutResult?>(null) }
    var timedPlan by remember(line.startMs, line.text, line.chars) { mutableStateOf<NativeTimedLyricPlan?>(null) }
    val glowMeasurer = rememberTextMeasurer(cacheSize = 64)
    val lyricAccent = LocalLyricAccent.current
    val style = nativeLyricTextStyle(fontSize, lineHeight, fontWeight, textAlign)
    val glyphLayoutCache = remember(line.text, style) { LinkedHashMap<String, TextLayoutResult>() }
    val lineEndMs = remember(line) { nativeLineAudioEndMs(line) }
    val exitMotionProgress = if (!isActive && isPast) {
        (1f - (positionMs - lineEndMs).toFloat() / NATIVE_WORD_EXIT_FLOAT_MS.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }
    val focusMotion = if (isFocused && !isPast) motionFocus.coerceIn(0f, 1f) else 0f
    val isPreActiveFocus = !isActive && focusMotion > 0.001f
    val shouldDrawTimed = line.chars.isNotEmpty() && (isActive || isPreActiveFocus || exitMotionProgress > 0f)
    val staticDrawPositionMs = if (isActive || isPreActiveFocus) positionMs else lineEndMs
    val useFrameClockForDraw = clockState != null && (isActive || isPreActiveFocus)
    val baseColor = when {
        shouldDrawTimed -> Color.Transparent
        isActive -> fg
        isFocused && !isPast -> fg
        isPast -> nativeSolidLineColor(fg)
        else -> fgUnsung
    }

    Text(
        text = line.text,
        color = baseColor,
        style = style,
        onTextLayout = { result ->
            if (layout !== result) {
                layout = result
                timedPlan = if (line.chars.isNotEmpty()) {
                    NativeTimedLyricPlan(
                        layout = result,
                        segments = nativeLyricSegments(result, line.chars),
                    )
                } else {
                    null
                }
            }
        },
        modifier = if (shouldDrawTimed) {
            Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val plan = timedPlan
                if (result == null || plan == null || plan.layout !== result) {
                    drawContent()
                } else {
                    val drawPositionMs = if (useFrameClockForDraw) {
                        nativeRenderPositionMs(clockState?.value ?: staticDrawPositionMs.toFloat())
                    } else {
                        staticDrawPositionMs
                    }
                    val drawFocusMotion = if (isFocused && !isPast) {
                        (motionFocusProvider?.invoke() ?: focusMotion).coerceIn(0f, 1f)
                    } else {
                        focusMotion
                    }
                    drawNativeTimedLyric(
                        plan = plan,
                        glyphMeasurer = glowMeasurer,
                        glyphLayoutCache = glyphLayoutCache,
                        positionMs = drawPositionMs,
                        focusMotion = drawFocusMotion,
                        fg = fg,
                        fgUnsung = fgUnsung,
                        isPast = isPast,
                        isBackgroundVocal = isBackgroundVocal,
                        maskAlpha = maskAlpha,
                        motionScale = if (isActive || isPreActiveFocus) 1f else exitMotionProgress,
                        effectsEnabled = effectsEnabled,
                        accent = lyricAccent,
                    )
                }
            }
        } else {
            Modifier.fillMaxWidth()
        },
    )
}

private fun nativeLyricTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
): TextStyle {
    return TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = textAlign,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

private data class NativeLineBalanceChild(
    val text: String,
    val widthPx: Float,
    val isSpace: Boolean,
)

private fun nativeBalancedLyricLine(
    line: PipoLyricLine,
    containerWidthPx: Float,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): PipoLyricLine {
    val safeWidth = containerWidthPx.coerceAtLeast(1f)
    if (line.text.isBlank() || line.text.contains('\n')) return line
    val children = if (line.chars.isNotEmpty()) {
        line.chars.mapNotNull { char ->
            if (char.text.isEmpty()) {
                null
            } else {
                nativeBalanceChild(char.text, style, textMeasurer)
            }
        }
    } else {
        nativePlainBalanceChildren(line.text, style, textMeasurer)
    }
    if (children.size <= 1) return line
    val layoutWidth = children.sumOf { it.widthPx.toDouble() }.toFloat()
    if (layoutWidth <= safeWidth) return line

    val breaks = nativeCalcBalancedBreaks(
        children = children,
        containerWidthPx = safeWidth,
        fullText = children.joinToString(separator = "") { it.text },
    )
    if (breaks.isEmpty()) return line

    return if (line.chars.isNotEmpty()) {
        nativeApplyDynamicLineBreaks(line, breaks)
    } else {
        line.copy(text = nativeTextWithBalancedBreaks(children, breaks))
    }
}

private fun nativeBalanceChild(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): NativeLineBalanceChild {
    val measured = runCatching {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style.copy(textAlign = TextAlign.Start),
            softWrap = false,
            maxLines = 1,
        ).size.width.toFloat()
    }.getOrDefault(0f)
    val fallback = nativeEstimatedBalanceWidth(text, style)
    return NativeLineBalanceChild(
        text = text,
        widthPx = measured.takeIf { it.isFinite() && it > 0f } ?: fallback,
        isSpace = text.isBlank(),
    )
}

private fun nativePlainBalanceChildren(
    text: String,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): List<NativeLineBalanceChild> {
    val children = ArrayList<NativeLineBalanceChild>()
    var index = 0
    while (index < text.length) {
        val start = index
        if (text[index].isWhitespace()) {
            while (index < text.length && text[index].isWhitespace()) index++
        } else if (nativeIsCjkChar(text[index])) {
            index++
            while (index < text.length && text[index].isWhitespace()) index++
        } else {
            while (index < text.length && !text[index].isWhitespace() && !nativeIsCjkChar(text[index])) {
                index++
            }
            while (index < text.length && text[index].isWhitespace()) index++
        }
        if (index > start) {
            children.add(nativeBalanceChild(text.substring(start, index), style, textMeasurer))
        }
    }
    return children
}

private fun nativeCalcBalancedBreaks(
    children: List<NativeLineBalanceChild>,
    containerWidthPx: Float,
    fullText: String,
): List<Int> {
    val n = children.size
    if (n == 0 || containerWidthPx <= 0f) return emptyList()
    val charOffsets = IntArray(n + 1)
    val prefixWidth = DoubleArray(n + 1)
    for (i in 0 until n) {
        charOffsets[i + 1] = charOffsets[i] + children[i].text.length
        prefixWidth[i + 1] = prefixWidth[i] + children[i].widthPx.toDouble()
    }
    if (prefixWidth[n] <= containerWidthPx) return emptyList()

    val cjkBoundaries = nativeCjkBoundaryOffsets(fullText)
    val dp = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val nextBreak = IntArray(n + 1) { -1 }
    dp[n] = 0.0
    val width = containerWidthPx.toDouble()
    val cjkPenalty = (width * NATIVE_CJK_BREAK_PENALTY_RATIO).let { it * it }
    val normalPenalty = (width * NATIVE_NORMAL_BREAK_PENALTY_RATIO).let { it * it }

    for (i in n - 1 downTo 0) {
        for (j in i + 1..n) {
            val lineWidth = prefixWidth[j] - prefixWidth[i]
            val lineCost = if (lineWidth > width) {
                if (j == i + 1) {
                    val overflow = lineWidth - width
                    overflow * overflow * NATIVE_OVERFLOW_PENALTY_MULTIPLIER
                } else {
                    break
                }
            } else {
                val slack = width - lineWidth
                slack * slack
            }
            val breakPenalty = if (j < n) {
                val previous = children[j - 1]
                when {
                    nativeEndsWithBreakPunctuation(previous.text) ->
                        -((width * NATIVE_PUNCTUATION_BREAK_REWARD_RATIO).let { it * it })
                    nativeEndsWithBreakableSpace(previous.text) || previous.isSpace ->
                        -((width * NATIVE_SPACE_BREAK_REWARD_RATIO).let { it * it })
                    charOffsets[j] in cjkBoundaries -> cjkPenalty
                    else -> normalPenalty
                }
            } else {
                0.0
            }
            val total = lineCost + breakPenalty + dp[j]
            if (total < dp[i]) {
                dp[i] = total
                nextBreak[i] = j
            }
        }
    }

    val breaks = ArrayList<Int>()
    var current = 0
    var guard = 0
    while (current < n && guard <= n) {
        val next = nextBreak[current]
        if (next <= current || next > n) return emptyList()
        if (next in 1 until n) breaks.add(next)
        current = next
        guard++
    }
    return breaks
}

private fun nativeApplyDynamicLineBreaks(
    line: PipoLyricLine,
    breaks: List<Int>,
): PipoLyricLine {
    val breakAfter = breaks.map { it - 1 }.filter { it in line.chars.indices }.toSet()
    if (breakAfter.isEmpty()) return line
    val adjustedChars = line.chars.mapIndexed { index, char ->
        if (index in breakAfter) {
            char.copy(text = nativeTextWithTrailingLineBreak(char.text))
        } else {
            char
        }
    }
    return line.copy(
        text = adjustedChars.joinToString(separator = "") { it.text },
        chars = adjustedChars,
    )
}

private fun nativeTextWithBalancedBreaks(
    children: List<NativeLineBalanceChild>,
    breaks: List<Int>,
): String {
    val breakAfter = breaks.map { it - 1 }.filter { it in children.indices }.toSet()
    val out = StringBuilder()
    children.forEachIndexed { index, child ->
        if (index in breakAfter) {
            out.append(nativeTextWithTrailingLineBreak(child.text))
        } else {
            out.append(child.text)
        }
    }
    return out.toString()
}

private fun nativeTextWithTrailingLineBreak(text: String): String {
    val trimmed = text.dropLastWhile { it.isWhitespace() && it != '\n' }
    return if (trimmed.endsWith('\n')) trimmed else "$trimmed\n"
}

private fun nativeEstimatedBalanceWidth(text: String, style: TextStyle): Float {
    val em = style.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 16f
    return text.sumOf { char ->
        when {
            char.isWhitespace() -> 0.33
            nativeIsCjkChar(char) -> 1.0
            char in "mwMW" -> 0.86
            char in "ilI.,'’!|:;`" -> 0.30
            char.isDigit() -> 0.56
            char.isLetter() && char.isUpperCase() -> 0.66
            char.isLetter() -> 0.56
            else -> 0.42
        }
    }.toFloat() * em
}

private fun nativeCjkBoundaryOffsets(text: String): Set<Int> {
    if (text.length <= 1) return emptySet()
    val out = LinkedHashSet<Int>()
    for (index in 1 until text.length) {
        if (nativeIsCjkChar(text[index - 1]) || nativeIsCjkChar(text[index])) {
            out.add(index)
        }
    }
    return out
}

private fun nativeEndsWithBreakableSpace(text: String): Boolean {
    return text.lastOrNull()?.isWhitespace() == true
}

private fun nativeEndsWithBreakPunctuation(text: String): Boolean {
    val char = text.dropLastWhile { it.isWhitespace() }.lastOrNull() ?: return false
    return char in NATIVE_BREAK_PUNCTUATION
}

private data class NativeTimedLyricPlan(
    val layout: TextLayoutResult,
    val segments: List<NativeLyricSegment>,
)

private fun DrawScope.drawNativeTimedLyric(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    glyphLayoutCache: MutableMap<String, TextLayoutResult>,
    positionMs: Long,
    focusMotion: Float,
    fg: Color,
    fgUnsung: Color,
    isPast: Boolean,
    isBackgroundVocal: Boolean,
    maskAlpha: NativeMaskAlpha,
    motionScale: Float,
    effectsEnabled: Boolean,
    accent: Color,
) {
    val layout = plan.layout
    val segments = plan.segments
    if (segments.isEmpty()) {
        drawText(layout, color = if (isPast) nativeSolidLineColor(fg) else fgUnsung)
        return
    }
    val activeUnsung = if (isPast) nativeSolidLineColor(fg) else fgUnsung
    val activeSung = fg
    val fadeWidth = nativeWordFadeWidth(layout)
    val front = nativeLyricFront(segments, positionMs, fadeWidth)
        drawNativeSegmentSweepText(
            layout = layout,
            glyphMeasurer = glyphMeasurer,
            glyphLayoutCache = glyphLayoutCache,
            segments = segments,
        front = front,
        fg = activeSung,
        activeUnsung = activeUnsung,
        fadeWidth = fadeWidth,
        isBackgroundVocal = isBackgroundVocal,
        positionMs = positionMs,
        focusMotion = focusMotion,
        motionScale = motionScale,
        effectsEnabled = effectsEnabled,
        accent = accent,
    )
}

private fun DrawScope.drawNativeSegmentSweepText(
    layout: TextLayoutResult,
    glyphMeasurer: TextMeasurer,
    glyphLayoutCache: MutableMap<String, TextLayoutResult>,
    segments: List<NativeLyricSegment>,
    front: NativeLyricFront,
    fg: Color,
    activeUnsung: Color,
    fadeWidth: Float,
    isBackgroundVocal: Boolean,
    positionMs: Long,
    focusMotion: Float,
    motionScale: Float,
    effectsEnabled: Boolean,
    accent: Color,
) {
    val fontPx = layout.layoutInput.style.fontSize.toPx()
    val verticalClipPad = fontPx * NATIVE_GLYPH_VERTICAL_CLIP_PAD_EM
    segments.forEachIndexed { index, segment ->
        val lineTop = layout.getLineTop(segment.line)
        val lineBottom = layout.getLineBottom(segment.line)
        val progress = nativeSegmentFillProgress(segment, positionMs)
        val bounds = nativeSegmentClipBounds(layout, segments, index, fadeWidth)
        val slow = nativeSlowWordShape(segment.timing, index == segments.lastIndex)
        val slowFocusPreview = focusMotion > 0.001f && front.index < 0 && index == 0
        if (effectsEnabled && (nativeShouldDrawSlowGlyphs(slow, segment.timing, positionMs) || slowFocusPreview)) {
            drawNativeSlowSegmentText(
                layout = layout,
                glyphMeasurer = glyphMeasurer,
                glyphLayoutCache = glyphLayoutCache,
                segment = segment,
                bounds = bounds,
                slow = slow,
                motionPositionMs = positionMs,
                focusMotion = focusMotion,
                progress = progress,
                fg = fg,
                activeUnsung = activeUnsung,
                fadeWidth = fadeWidth,
                isBackgroundVocal = isBackgroundVocal,
                motionScale = motionScale,
                accent = accent,
            )
            return@forEachIndexed
        }
        val liftEm = if (isBackgroundVocal) NATIVE_BG_WORD_LIFT_EM else NATIVE_MAIN_WORD_LIFT_EM
        val liftPx = -fontPx * liftEm *
            NATIVE_WORD_FLOAT_EASE.transform(progress.coerceIn(0f, 1f)) *
            motionScale.coerceIn(0f, 1f)
        val topLeft = Offset(0f, liftPx)

        drawNativeSweepTextSlice(
            layout = layout,
            clipLeft = bounds.left,
            clipTop = lineTop - verticalClipPad,
            clipRight = bounds.right,
            clipBottom = lineBottom + verticalClipPad,
            segment = segment,
            progress = progress,
            fg = fg,
            activeUnsung = activeUnsung,
            fadeWidth = fadeWidth,
            topLeft = topLeft,
            positionMs = positionMs,
            accent = accent,
        )
    }
}

private fun DrawScope.drawNativeSweepTextSlice(
    layout: TextLayoutResult,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
    segment: NativeLyricSegment,
    progress: Float,
    fg: Color,
    activeUnsung: Color,
    fadeWidth: Float,
    topLeft: Offset,
    positionMs: Long,
    accent: Color,
) {
    if (clipRight <= clipLeft || clipBottom <= clipTop) return
    val p = progress.coerceIn(0f, 1f)
    clipRect(clipLeft, clipTop, clipRight, clipBottom) {
        when {
            p <= NATIVE_SWEEP_PROGRESS_EPS || positionMs <= segment.timing.startMs -> {
                drawText(layout, color = activeUnsung, topLeft = topLeft)
            }
            p >= 0.999f -> drawText(layout, color = fg, topLeft = topLeft)
            else -> {
                val segmentWidth = (segment.right - segment.left).coerceAtLeast(1f)
                val sweepX = (segment.left + (segment.right - segment.left) * p)
                    .coerceIn(segment.left, segment.right)
                val transitionWidth = maxOf(
                    segmentWidth * NATIVE_SWEEP_TRANSITION_FRACTION,
                    fadeWidth * NATIVE_SWEEP_TRANSITION_MIN_EM,
                )
                val fadeEndX = sweepX + transitionWidth
                drawText(layout, color = activeUnsung, topLeft = topLeft)
                if (sweepX > clipLeft) {
                    clipRect(
                        clipLeft,
                        clipTop,
                        minOf(sweepX, clipRight),
                        clipBottom,
                    ) {
                        drawText(layout, color = fg, topLeft = topLeft)
                    }
                }
                if (fadeEndX > clipLeft && sweepX < clipRight) {
                    val fadeLeft = sweepX.coerceAtLeast(clipLeft)
                    val fadeRight = fadeEndX.coerceAtMost(clipRight)
                    val fadeBrush = nativeSweepTransitionBrush(
                        fg = fg,
                        accent = accent,
                        startX = fadeLeft,
                        endX = fadeRight,
                    )
                    clipRect(
                        fadeLeft,
                        clipTop,
                        fadeRight,
                        clipBottom,
                    ) {
                        drawText(
                            layout,
                            brush = fadeBrush,
                            topLeft = topLeft,
                        )
                    }
                }
            }
        }
    }
}

private fun nativeSweepTransitionBrush(
    fg: Color,
    accent: Color,
    startX: Float,
    endX: Float,
): Brush {
    val safeEndX = endX.coerceAtLeast(startX + 1f)
    val mid = nativeSweepAccentColor(fg, accent)
    return Brush.horizontalGradient(
        colorStops = arrayOf<Pair<Float, Color>>(
            0f to fg,
            NATIVE_SWEEP_ACCENT_START to mid,
            NATIVE_SWEEP_ACCENT_END to mid.copy(alpha = mid.alpha * 0.82f),
            1f to Color.Transparent,
        ),
        startX = startX,
        endX = safeEndX,
        tileMode = TileMode.Clamp,
    )
}

private fun nativeSweepAccentColor(fg: Color, accent: Color): Color {
    val effective = if (accent == Color.Unspecified || accent.alpha <= 0.001f) PipoColors.Accent else accent
    return effective.copy(alpha = (fg.alpha * NATIVE_SWEEP_ACCENT_ALPHA).coerceIn(0f, 1f))
}

@Composable
private fun NativeInterludeDots(
    interlude: NativeInterlude,
    clockState: State<Float>,
    offsetMs: Long,
    color: Color,
    dotSize: Dp,
    dotGap: Dp,
    width: Dp,
) {
    Canvas(modifier = Modifier.size(width = width, height = dotSize)) {
        val progress = nativeInterludeProgress(
            interlude = interlude,
            positionMs = nativeRenderPositionMs(clockState.value) + offsetMs,
        )
        if (progress.scale <= 0.001f || progress.globalOpacity <= 0.001f) return@Canvas
        val dot = dotSize.toPx()
        val gap = dotGap.toPx()
        val radius = dot / 2f
        val scaledRadius = radius * progress.scale
        val centerY = size.height / 2f
        val centers = floatArrayOf(
            radius,
            radius + dot + gap,
            radius + (dot + gap) * 2f,
        )
        centers.forEachIndexed { idx, centerX ->
            drawCircle(
                color = color.copy(alpha = progress.dotAlphas[idx]),
                radius = scaledRadius,
                center = Offset(centerX, centerY),
            )
        }
    }
}

private data class NativeInterludeProgress(
    val scale: Float,
    val globalOpacity: Float,
    val dotAlphas: FloatArray,
)

private fun nativeInterludeProgress(
    interlude: NativeInterlude,
    positionMs: Long,
): NativeInterludeProgress {
    val duration = (interlude.endMs - interlude.startMs).coerceAtLeast(1L).toFloat()
    val current = (positionMs - interlude.startMs).toFloat().coerceIn(0f, duration)
    val breatheDuration = duration / kotlin.math.ceil(duration / NATIVE_INTERLUDE_BREATHE_TARGET_MS).coerceAtLeast(1f)
    var scale = kotlin.math.sin(1.5 * Math.PI - (current / breatheDuration) * 2.0).toFloat() / 20f + 1f
    var globalOpacity = 1f

    if (current < 2_000f) {
        scale *= nativeEaseOutExpo(current / 2_000f)
    }
    if (current < 500f) {
        globalOpacity = 0f
    } else if (current < 1_000f) {
        globalOpacity *= (current - 500f) / 500f
    }
    val remaining = duration - current
    if (remaining < 750f) {
        scale *= 1f - nativeEaseInOutBack((750f - remaining) / 750f / 2f)
    }
    if (remaining < 375f) {
        globalOpacity *= (remaining / 375f).coerceIn(0f, 1f)
    }

    val dotsDuration = (duration - 750f).coerceAtLeast(1f)
    scale = scale.coerceAtLeast(0f) * 0.7f
    val dot0 = nativeInterludeDotAlpha(current, dotsDuration, 0f) * globalOpacity
    val dot1 = nativeInterludeDotAlpha(current, dotsDuration, dotsDuration / 3f) * globalOpacity
    val dot2 = nativeInterludeDotAlpha(current, dotsDuration, dotsDuration / 3f * 2f) * globalOpacity
    return NativeInterludeProgress(
        scale = scale,
        globalOpacity = globalOpacity,
        dotAlphas = floatArrayOf(dot0.coerceIn(0f, 1f), dot1.coerceIn(0f, 1f), dot2.coerceIn(0f, 1f)),
    )
}

private fun nativeInterludeDotAlpha(
    current: Float,
    dotsDuration: Float,
    delay: Float,
): Float {
    val value = (((current - delay) * 3f) / dotsDuration) * 0.75f
    return maxOf(0.25f, value).coerceAtMost(1f)
}

private fun nativeEaseOutExpo(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return if (t >= 1f) 1f else (1f - Math.pow(2.0, (-10f * t).toDouble()).toFloat())
}

private fun nativeEaseInOutBack(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val c1 = 1.70158f
    val c2 = c1 * 1.525f
    return if (t < 0.5f) {
        val p = 2f * t
        (p * p * ((c2 + 1f) * p - c2)) / 2f
    } else {
        val p = 2f * t - 2f
        (p * p * ((c2 + 1f) * p + c2) + 2f) / 2f
    }
}

private data class NativeSegmentBounds(
    val left: Float,
    val right: Float,
)

private fun nativeSegmentClipBounds(
    layout: TextLayoutResult,
    segments: List<NativeLyricSegment>,
    index: Int,
    fadeWidth: Float,
): NativeSegmentBounds {
    val segment = segments[index]
    val prev = segments.getOrNull(index - 1)?.takeIf { it.line == segment.line }
    val next = segments.getOrNull(index + 1)?.takeIf { it.line == segment.line }
    val lineLeft = layout.getLineLeft(segment.line)
    val lineRight = layout.getLineRight(segment.line)
    val left = when {
        prev == null -> lineLeft - fadeWidth
        prev.right < segment.left -> (prev.right + segment.left) / 2f
        else -> segment.left
    }
    val right = when {
        next == null -> lineRight + fadeWidth
        segment.right < next.left -> (segment.right + next.left) / 2f
        else -> segment.right
    }
    return NativeSegmentBounds(
        left = left.coerceAtMost(right),
        right = right.coerceAtLeast(left),
    )
}

private fun DrawScope.nativeWordFadeWidth(layout: TextLayoutResult): Float {
    val lineHeightPx = (0 until layout.lineCount).maxOfOrNull { line ->
        (layout.getLineBottom(line) - layout.getLineTop(line)).toDouble()
    }?.toFloat()
    val fallback = layout.layoutInput.style.fontSize.toPx()
    return ((lineHeightPx ?: fallback).coerceAtLeast(fallback) * NATIVE_WORD_FADE_WIDTH_RATIO)
        .coerceAtLeast(1f)
}

private fun nativeShouldDrawSlowGlyphs(
    slow: NativeSlowShape,
    token: PipoLyricChar,
    positionMs: Long,
): Boolean {
    if (slow.amount <= 0f || slow.animateDurationMs <= 0f) return false
    val start = token.startMs - NATIVE_SLOW_SWEEP_PREVIEW_LEAD_MS
    val end = token.startMs + token.durationMs.coerceAtLeast(1L) + NATIVE_SLOW_SWEEP_GLOW_TAIL_MS
    return positionMs in start..end
}

private fun DrawScope.drawNativeSlowSegmentText(
    layout: TextLayoutResult,
    glyphMeasurer: TextMeasurer,
    glyphLayoutCache: MutableMap<String, TextLayoutResult>,
    segment: NativeLyricSegment,
    bounds: NativeSegmentBounds,
    slow: NativeSlowShape,
    motionPositionMs: Long,
    focusMotion: Float,
    progress: Float,
    fg: Color,
    activeUnsung: Color,
    fadeWidth: Float,
    isBackgroundVocal: Boolean,
    motionScale: Float,
    accent: Color,
) {
    // 慢词逐字行波：Apple Music 更像低幅度 rolling wave，而不是单字弹跳。
    // 波峰稍宽、前后都缓入缓出，扫过后自然落回基线。
    val fontPx = layout.layoutInput.style.fontSize.toPx()
    val p = progress.coerceIn(0f, 1f)
    val lineTop = layout.getLineTop(segment.line)
    val lineBottom = layout.getLineBottom(segment.line)
    val glyphCenterY = (lineTop + lineBottom) * 0.5f
    val mScale = motionScale.coerceIn(0f, 1f)
    val baseLiftEm = if (isBackgroundVocal) NATIVE_BG_WORD_LIFT_EM else NATIVE_MAIN_WORD_LIFT_EM
    val segmentWidth = (segment.right - segment.left).coerceAtLeast(1f)
    val transitionWidth = maxOf(
        segmentWidth * NATIVE_SWEEP_TRANSITION_FRACTION,
        fadeWidth * NATIVE_SWEEP_TRANSITION_MIN_EM,
    ).coerceAtLeast(1f)
    // 波的宽度（前沿附近多大范围参与顶起）。
    val trailPx = NATIVE_SLOW_COMET_TRAIL_DP.dp.toPx()
    val leadPx = NATIVE_SLOW_COMET_LEAD_DP.dp.toPx()
    val peakLagPx = NATIVE_SLOW_COMET_PEAK_DP.dp.toPx()
    val sweepMotion = nativeSlowSweepMotion(segment, p, motionPositionMs, trailPx, leadPx)
    val sweepX = sweepMotion.x
    val emphasisScale = sweepMotion.emphasisScale

    val durationStrength = nativeSlowDurationGlowStrength(segment.timing.durationMs)
    val glowMax = if (isBackgroundVocal) NATIVE_SLOW_SHADOW_BG_ALPHA_MAX else NATIVE_SLOW_SHADOW_MAIN_ALPHA_MAX
    val glowAlpha = (durationStrength * glowMax * NATIVE_SLOW_SHADOW_STABLE_GAIN * mScale *
        (0.45f + 0.55f * focusMotion.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    val glowBlurPx = fontPx *
        (NATIVE_SLOW_SHADOW_BLUR_MIN_EM + durationStrength * NATIVE_SLOW_SHADOW_BLUR_GAIN_EM) *
        NATIVE_SLOW_SHADOW_STABLE_BLUR_GAIN

    val text = layout.layoutInput.text.text
    val glyphStyle = layout.layoutInput.style.copy(textAlign = TextAlign.Start)

    for (i in segment.startChar until segment.endChar) {
        if (i !in text.indices) continue
        val ch = text[i]
        if (ch.isWhitespace() || ch.isISOControl()) continue
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue
        val center = (box.left + box.right) * 0.5f
        // 上浮仍按字母中心取样；颜色绘制走连续 sweep helper，避免整字跳色。
        val sung = nativeSmoothStep((sweepX - center) / transitionWidth + 0.5f)
        // 行波包络：峰值随扫色前沿滚过，保持低幅、宽峰，避免字母像逐个跳起。
        val wave = nativeAppleMusicSlowWave(box.left, box.right, sweepX, trailPx, leadPx, peakLagPx) * emphasisScale
        val glowWave = nativeSlowProgressGlowCoverage(box.left, box.right, sweepX, fontPx) * emphasisScale
        val liftPx = -fontPx * (baseLiftEm * sung + slow.amount * NATIVE_SLOW_LIFT_EM_GAIN * wave) * mScale
        val glyphScale = 1f + slow.amount * NATIVE_SLOW_SCALE_GAIN * wave * mScale
        val glyphText = ch.toString()
        val glyphLayout = glyphLayoutCache.getOrPut("$i:$glyphText") {
            glyphMeasurer.measure(
                text = AnnotatedString(glyphText),
                style = glyphStyle,
                softWrap = false,
                maxLines = 1,
            )
        }
        val shadow = if (glowAlpha > 0.003f && glowBlurPx > 0.2f && glowWave > 0.02f) {
            Shadow(
                color = fg.copy(alpha = (glowAlpha * glowWave).coerceIn(0f, 1f)),
                offset = Offset.Zero,
                blurRadius = glowBlurPx,
            )
        } else {
            null
        }
        translate(left = 0f, top = liftPx) {
            scale(glyphScale, glyphScale, pivot = Offset(center, glyphCenterY)) {
                drawNativeSlowGlyphSweepText(
                    glyphLayout = glyphLayout,
                    glyphLeft = box.left,
                    glyphRight = box.right,
                    clipTop = lineTop - fontPx,
                    clipBottom = lineBottom + fontPx,
                    topLeft = Offset(box.left, lineTop),
                    tokenStarted = p > NATIVE_SWEEP_PROGRESS_EPS && motionPositionMs > segment.timing.startMs,
                    sweepX = sweepX,
                    fadeEndX = sweepX + transitionWidth,
                    fg = fg,
                    activeUnsung = activeUnsung,
                    accent = accent,
                    shadow = shadow,
                )
            }
        }
    }
}

private fun DrawScope.drawNativeSlowGlyphSweepText(
    glyphLayout: TextLayoutResult,
    glyphLeft: Float,
    glyphRight: Float,
    clipTop: Float,
    clipBottom: Float,
    topLeft: Offset,
    tokenStarted: Boolean,
    sweepX: Float,
    fadeEndX: Float,
    fg: Color,
    activeUnsung: Color,
    accent: Color,
    shadow: Shadow?,
) {
    if (glyphRight <= glyphLeft || clipBottom <= clipTop) return
    if (!tokenStarted || fadeEndX <= glyphLeft) {
        drawText(glyphLayout, color = activeUnsung, topLeft = topLeft)
        return
    }
    if (sweepX >= glyphRight) {
        drawText(glyphLayout, color = fg, topLeft = topLeft, shadow = shadow)
        return
    }

    drawText(glyphLayout, color = activeUnsung, topLeft = topLeft)

    if (sweepX > glyphLeft) {
        val sungRight = minOf(sweepX, glyphRight)
        if (sungRight > glyphLeft) {
            clipRect(glyphLeft, clipTop, sungRight, clipBottom) {
                drawText(glyphLayout, color = fg, topLeft = topLeft, shadow = shadow)
            }
        }
    }

    if (fadeEndX > glyphLeft && sweepX < glyphRight) {
        val fadeLeft = sweepX.coerceAtLeast(glyphLeft)
        val fadeRight = fadeEndX.coerceAtMost(glyphRight)
        if (fadeRight > fadeLeft) {
            val fadeBrush = nativeSweepTransitionBrush(
                fg = fg,
                accent = accent,
                startX = fadeLeft - glyphLeft,
                endX = fadeRight - glyphLeft,
            )
            clipRect(fadeLeft, clipTop, fadeRight, clipBottom) {
                drawText(
                    glyphLayout,
                    brush = fadeBrush,
                    topLeft = topLeft,
                    shadow = shadow,
                )
            }
        }
    }
}

private fun DrawScope.drawNativeSlowSweepGlow(
    layout: TextLayoutResult,
    glyphMeasurer: TextMeasurer,
    segment: NativeLyricSegment,
    bounds: NativeSegmentBounds,
    sweepX: Float,
    clipTop: Float,
    clipBottom: Float,
    liftedTop: Float,
    glowAlpha: Float,
    glowBlurPx: Float,
    positionMs: Long,
) {
    if (glowAlpha <= 0.003f || glowBlurPx <= 0.2f) return
    val tokenEndMs = segment.timing.startMs + segment.timing.durationMs.coerceAtLeast(1L)
    val tailProgress = if (positionMs <= tokenEndMs) {
        1f
    } else {
        (1f - (positionMs - tokenEndMs).toFloat() / NATIVE_SLOW_SWEEP_GLOW_TAIL_MS.toFloat())
            .coerceIn(0f, 1f)
    }
    if (tailProgress <= 0.001f) return
    val trailPx = NATIVE_SLOW_COMET_TRAIL_DP.dp.toPx()
    val leadPx = NATIVE_SLOW_COMET_LEAD_DP.dp.toPx()
    val peakLagPx = NATIVE_SLOW_COMET_PEAK_DP.dp.toPx()
    val text = layout.layoutInput.text.text
    val glyphStyle = layout.layoutInput.style.copy(textAlign = TextAlign.Start)
    clipRect(
        left = bounds.left,
        top = clipTop,
        right = bounds.right,
        bottom = clipBottom,
    ) {
        for (index in segment.startChar until segment.endChar) {
            if (index !in text.indices) continue
            val ch = text[index]
            if (ch.isWhitespace() || ch.isISOControl()) continue
            val box = layout.getBoundingBox(index)
            if (box.right <= box.left) continue
            val glowE = nativeAppleMusicSlowWave(
                left = box.left,
                right = box.right,
                sweepX = sweepX,
                trailPx = trailPx,
                leadPx = leadPx,
                peakLagPx = peakLagPx,
            )
            if (glowE <= 0.01f) continue
            val shadow = Shadow(
                color = Color.White.copy(alpha = (glowAlpha * glowE * tailProgress).coerceIn(0f, 1f)),
                offset = Offset.Zero,
                blurRadius = glowBlurPx,
            )
            val barelyVisibleFill = Color.White.copy(
                alpha = (NATIVE_SLOW_COMET_FILL_ALPHA * glowE * tailProgress).coerceIn(0f, 1f),
            )
            val glyphLayout = glyphMeasurer.measure(
                text = AnnotatedString(ch.toString()),
                style = glyphStyle,
                softWrap = false,
                maxLines = 1,
            )
            drawText(
                glyphLayout,
                color = barelyVisibleFill,
                topLeft = Offset(box.left, layout.getLineTop(segment.line) + liftedTop),
                shadow = shadow,
            )
        }
    }
}

private fun nativeSmoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun nativePositionFocus(
    rowAnchor: Float,
    focusAnchor: Float,
    spanPx: Float,
): Float {
    val span = spanPx.coerceAtLeast(1f)
    val raw = (1f - kotlin.math.abs(rowAnchor - focusAnchor) / span).coerceIn(0f, 1f)
    return nativeSmoothStep(raw)
}

private fun nativeRowScale(positionFocus: Float): Float {
    return NATIVE_INACTIVE_SCALE + (1f - NATIVE_INACTIVE_SCALE) * positionFocus.coerceIn(0f, 1f)
}

private data class NativeSlowSweepMotion(
    val x: Float,
    val emphasisScale: Float,
)

private fun nativeSlowSweepMotion(
    segment: NativeLyricSegment,
    progress: Float,
    positionMs: Long,
    trailPx: Float,
    leadPx: Float,
): NativeSlowSweepMotion {
    val segmentWidth = (segment.right - segment.left).coerceAtLeast(1f)
    val p = progress.coerceIn(0f, 1f)
    val baseX = segment.left + segmentWidth * p
    val tokenEndMs = segment.timing.startMs + segment.timing.durationMs.coerceAtLeast(1L)
    if (positionMs <= tokenEndMs) {
        return NativeSlowSweepMotion(x = baseX, emphasisScale = 1f)
    }
    val tail = ((positionMs - tokenEndMs).toFloat() / NATIVE_SLOW_SWEEP_GLOW_TAIL_MS.toFloat())
        .coerceIn(0f, 1f)
    val exit = nativeSmoothStep(tail)
    val exitDistance = trailPx + leadPx
    return NativeSlowSweepMotion(
        x = baseX + exitDistance * exit,
        emphasisScale = 1f - exit,
    )
}

private fun nativeAppleMusicSlowWave(
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

    fun smoother(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun sample(x: Float): Float {
        return when {
            x <= startX || x >= endX -> 0f
            x <= peakX -> smoother((x - startX) / (peakX - startX).coerceAtLeast(1f))
            else -> smoother((endX - x) / (endX - peakX).coerceAtLeast(1f))
        }
    }

    val q1 = left + (right - left) * 0.25f
    val mid = (left + right) * 0.5f
    val q3 = left + (right - left) * 0.75f
    val coverage = (sample(q1) + sample(mid) * 1.4f + sample(q3)) / 3.4f
    return nativeSmoothStep(coverage).coerceIn(0f, 1f)
}

private fun nativeSlowProgressGlowCoverage(
    glyphLeft: Float,
    glyphRight: Float,
    sweepX: Float,
    fontPx: Float,
): Float {
    if (glyphRight <= glyphLeft) return 0f
    val glyphWidth = glyphRight - glyphLeft
    val edgePad = (fontPx * NATIVE_SLOW_GLOW_EDGE_PAD_EM).coerceAtLeast(1f)
    if (sweepX < glyphLeft - edgePad || sweepX > glyphRight + edgePad) return 0f
    val center = (glyphLeft + glyphRight) * 0.5f
    val halfWidth = (glyphWidth * 0.5f + edgePad).coerceAtLeast(1f)
    val raw = (1f - kotlin.math.abs(sweepX - center) / halfWidth).coerceIn(0f, 1f)
    return nativeSmoothStep(raw)
}

private data class NativeLyricSegment(
    val timing: PipoLyricChar,
    val line: Int,
    val left: Float,
    val right: Float,
    val segmentStartProgress: Float,
    val segmentEndProgress: Float,
    val startChar: Int,
    val endChar: Int,
)

private fun nativeLyricSegments(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
): List<NativeLyricSegment> {
    val text = layout.layoutInput.text.text
    if (text.isEmpty() || chars.isEmpty()) return emptyList()
    val segments = ArrayList<NativeLyricSegment>(chars.size)
    var cursor = 0
    chars.forEach { timing ->
        val start = cursor.coerceAtMost(text.length)
        val end = (cursor + timing.text.length).coerceAtMost(text.length)
        cursor = end
        if (start >= end) return@forEach
        var segStart = start
        while (segStart < end) {
            val line = layout.getLineForOffset(segStart)
            val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true).coerceAtLeast(segStart + 1))
            nativeAddSegment(segments, layout, timing, segStart, lineEnd, start, end, line)
            segStart = lineEnd
        }
    }
    return segments
}

private fun nativeAddSegment(
    out: MutableList<NativeLyricSegment>,
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
    for (i in start until end) {
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue
        left = minOf(left, box.left)
        right = maxOf(right, box.right)
    }
    if (left.isFinite() && right.isFinite() && right > left) {
        val tokenLength = (tokenEnd - tokenStart).coerceAtLeast(1)
        val startProgress = ((start - tokenStart).toFloat() / tokenLength.toFloat()).coerceIn(0f, 1f)
        val endProgress = ((end - tokenStart).toFloat() / tokenLength.toFloat()).coerceIn(startProgress, 1f)
        out.add(NativeLyricSegment(timing, line, left, right, startProgress, endProgress, start, end))
    }
}

private data class NativeLyricFront(
    val index: Int,
    val progress: Float,
    val x: Float,
    val line: Int,
)

private fun nativeLyricFront(
    segments: List<NativeLyricSegment>,
    positionMs: Long,
    fadeWidth: Float,
): NativeLyricFront {
    val index = segments.indexOfLast { it.timing.startMs <= positionMs }
    if (index < 0) return NativeLyricFront(-1, 0f, Float.NEGATIVE_INFINITY, 0)
    val current = segments[index]
    val next = segments.getOrNull(index + 1)
    val progress = nativeTokenMaskProgress(current.timing, positionMs)
    val sameLineNext = next?.takeIf { it.line == current.line }
    val targetX = sameLineNext?.left ?: (current.right + fadeWidth * 0.5f)
    return NativeLyricFront(
        index = index,
        progress = progress,
        x = current.left + (targetX - current.left) * progress,
        line = current.line,
    )
}

private fun nativeSegmentFillProgress(
    segment: NativeLyricSegment,
    positionMs: Long,
): Float {
    val tokenProgress = nativeTokenMaskProgress(segment.timing, positionMs).coerceIn(0f, 1f)
    val start = segment.segmentStartProgress
    val end = segment.segmentEndProgress
    if (tokenProgress <= start) return 0f
    if (tokenProgress >= end) return 1f
    return ((tokenProgress - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
}

private fun nativeTokenMaskProgress(
    token: PipoLyricChar,
    positionMs: Long,
): Float {
    return token.progress(positionMs)
}

private fun nativeTimingTextUnits(text: String): Int {
    val visible = text.count { !it.isWhitespace() && !it.isISOControl() }
    return visible.takeIf { it > 0 } ?: text.length.coerceAtLeast(1)
}

private data class NativeSlowShape(
    val amount: Float,
    val blur: Float,
    val animateDurationMs: Float,
)

private fun nativeSlowWordShape(token: PipoLyricChar, isLast: Boolean): NativeSlowShape {
    val rawDuration = token.durationMs.coerceAtLeast(1L).toFloat()
    if (rawDuration < NATIVE_SLOW_WORD_MIN_DURATION_MS.toFloat()) return NativeSlowShape(0f, 0f, 0f)
    val duration = maxOf(NATIVE_SLOW_WORD_MIN_DURATION_MS.toFloat(), rawDuration)
    var animateDuration = duration
    var amount = duration / 2000f
    amount = if (amount > 1f) kotlin.math.sqrt(amount) else amount * amount * amount
    var blur = duration / 3000f
    blur = if (blur > 1f) kotlin.math.sqrt(blur) else blur * blur * blur
    amount = maxOf(amount, 0.32f)
    blur = maxOf(blur, 0.25f)
    amount *= 0.58f
    blur *= 0.50f
    if (isLast) {
        amount *= 1.25f
        blur *= 1.25f
        animateDuration *= 1.12f
    }
    return NativeSlowShape(
        amount = amount.coerceAtMost(0.95f),
        blur = blur.coerceAtMost(0.8f),
        animateDurationMs = animateDuration,
    )
}

private fun nativeIsCjkText(text: String): Boolean {
    return text.any(::nativeIsCjkChar)
}

private fun nativeIsCjkChar(ch: Char): Boolean {
    return ch in '\u4E00'..'\u9FFF' ||
        ch in '\u3040'..'\u30FF' ||
        ch in '\uAC00'..'\uD7AF'
}

private fun nativeVisibleGlyphCount(text: String): Int {
    return text.count { !it.isWhitespace() && !it.isISOControl() }
}

private fun nativeLineOpacity(
    isActive: Boolean,
): Float {
    return when {
        isActive -> NATIVE_BUFFERED_OPACITY
        else -> NATIVE_NON_DYNAMIC_OPACITY
    }
}

private fun nativeSolidLineColor(fg: Color): Color {
    return fg.copy(alpha = fg.alpha * NATIVE_SOLID_LINE_ALPHA)
}

private fun nativeSlowDurationGlowStrength(durationMs: Long): Float {
    val raw = ((durationMs - NATIVE_SLOW_WORD_MIN_DURATION_MS).toFloat() / NATIVE_SLOW_SHADOW_FULL_DURATION_SPAN_MS)
        .coerceIn(0f, 1f)
    val eased = kotlin.math.sqrt(raw)
    return NATIVE_SLOW_SHADOW_MIN_DURATION_STRENGTH +
        (NATIVE_SLOW_SHADOW_MAX_DURATION_STRENGTH - NATIVE_SLOW_SHADOW_MIN_DURATION_STRENGTH) * eased
}

private data class NativeMaskAlpha(
    val bright: Float,
    val dark: Float,
) {
    companion object {
        val Solid = NativeMaskAlpha(bright = 1f, dark = 0.40f)
    }
}

private fun nativeMaskAlpha(
    gradient: Boolean,
): NativeMaskAlpha {
    return if (gradient) {
        NativeMaskAlpha(bright = 1f, dark = NATIVE_ACTIVE_UNSUNG_ALPHA)
    } else {
        NativeMaskAlpha(bright = NATIVE_SOLID_LINE_ALPHA, dark = NATIVE_SOLID_LINE_ALPHA)
    }
}

@Composable
private fun rememberNativeMaskAlpha(target: NativeMaskAlpha): NativeMaskAlpha {
    val bright = remember { Animatable(target.bright) }
    val dark = remember { Animatable(target.dark) }

    LaunchedEffect(target.bright, target.dark) {
        var lastFrame = withFrameNanos { it }
        while (
            kotlin.math.abs(bright.value - target.bright) > 0.001f ||
            kotlin.math.abs(dark.value - target.dark) > 0.001f
        ) {
            val now = withFrameNanos { it }
            val deltaSeconds = ((now - lastFrame).toDouble() / 1_000_000_000.0)
                .coerceIn(0.0, 0.05)
                .toFloat()
            lastFrame = now
            bright.snapTo(nativeAlphaStep(bright.value, target.bright, deltaSeconds))
            dark.snapTo(nativeAlphaStep(dark.value, target.dark, deltaSeconds))
        }
        bright.snapTo(target.bright)
        dark.snapTo(target.dark)
    }

    return NativeMaskAlpha(bright = bright.value, dark = dark.value)
}

private fun nativeAlphaStep(current: Float, target: Float, deltaSeconds: Float): Float {
    val speed = if (target > current) NATIVE_MASK_ALPHA_ATTACK_SPEED else NATIVE_MASK_ALPHA_RELEASE_SPEED
    val factor = 1f - kotlin.math.exp((-speed * deltaSeconds).toDouble()).toFloat()
    return current + (target - current) * factor
}

private fun nativeLineBlur(
    itemIndex: Int,
    scrollToIndex: Int,
    latestIndex: Int,
    isActive: Boolean,
    isUserDragging: Boolean,
    isCompact: Boolean,
): Float {
    if (isUserDragging || isActive) return 0f
    val focusIndex = maxOf(scrollToIndex, latestIndex)
    var blurLevel = if (itemIndex >= focusIndex) {
        0.35f + (itemIndex - focusIndex) * 1.15f
    } else {
        0.55f + kotlin.math.abs(scrollToIndex - itemIndex) * 0.75f
    }
    if (isCompact) blurLevel *= 0.8f
    return blurLevel.coerceAtMost(4f)
}

private fun nativeTranslationColor(fg: Color): Color {
    return fg.copy(alpha = NATIVE_SUBLINE_OPACITY)
}

private fun nativeIsCompanionActive(line: PipoLyricLine, positionMs: Long): Boolean {
    return positionMs >= LyricTiming.audioStartMs(line) && positionMs < nativeCompanionEndMs(line)
}

private fun nativeIsCompanionVisible(line: PipoLyricLine, positionMs: Long): Boolean {
    val startMs = LyricTiming.audioStartMs(line) - NATIVE_BG_APPEAR_LEAD_MS
    val endMs = nativeCompanionEndMs(line) + NATIVE_BG_DISAPPEAR_TAIL_MS
    return positionMs in startMs until endMs
}

private fun nativeIsCompanionPast(line: PipoLyricLine, positionMs: Long): Boolean {
    return positionMs >= nativeCompanionEndMs(line)
}

private fun nativeCompanionEndMs(line: PipoLyricLine): Long {
    val charEnd = line.chars.maxOfOrNull { it.startMs + it.durationMs }
    return maxOf(charEnd ?: line.startMs, line.startMs + line.durationMs)
}

private data class NativeTimelineSnapshot(
    val targetIndex: Int,
    val activeIndices: Set<Int>,
    val latestIndex: Int,
    val interlude: NativeInterlude?,
)

private data class NativeInterlude(
    val startMs: Long,
    val endMs: Long,
    val anchorLineIndex: Int,
    val isNextDuet: Boolean,
)

private fun nativeTimelineSnapshot(
    lines: List<PipoLyricLine>,
    positionMs: Long,
    targetPositionMs: Long = positionMs,
): NativeTimelineSnapshot {
    if (lines.isEmpty()) return NativeTimelineSnapshot(0, emptySet(), 0, null)
    val hot = LinkedHashSet<Int>()
    lines.forEachIndexed { idx, _ ->
        val start = nativeTimelineStartMs(lines, idx)
        val end = nativeTimelineEndMs(lines, idx)
        if (positionMs >= start && positionMs < end) {
            hot.add(idx)
        }
    }
    var targetStartedIndex = -1
    lines.forEachIndexed { idx, _ ->
        if (targetPositionMs >= nativeTimelineStartMs(lines, idx)) {
            targetStartedIndex = idx
        }
    }
    var latestStartedIndex = -1
    lines.forEachIndexed { idx, _ ->
        if (positionMs >= nativeTimelineStartMs(lines, idx)) {
            latestStartedIndex = idx
        }
    }
    val active = LinkedHashSet<Int>()
    if (hot.isNotEmpty()) {
        val first = hot.min()
        val last = hot.max()
        for (idx in first..last) {
            active.add(idx)
        }
    }
    val currentCueIndex = hot.minOrNull()
    val target = if (currentCueIndex != null) {
        currentCueIndex
    } else if (targetStartedIndex >= 0) {
        targetStartedIndex
    } else {
        latestStartedIndex.coerceAtLeast(0)
    }
    val latest = maxOf(active.maxOrNull() ?: target, target)
    val coercedTarget = target.coerceIn(lines.indices)
    return NativeTimelineSnapshot(
        targetIndex = coercedTarget,
        activeIndices = active,
        latestIndex = latest.coerceIn(lines.indices),
        interlude = nativeCurrentInterlude(lines, positionMs, coercedTarget),
    )
}

private fun nativeCurrentInterlude(
    lines: List<PipoLyricLine>,
    positionMs: Long,
    currentIndex: Int,
): NativeInterlude? {
    val currentTime = positionMs + 20L
    fun checkGap(anchorIndex: Int): NativeInterlude? {
        if (anchorIndex < -1 || anchorIndex >= lines.lastIndex) return null
        val gapStart = if (anchorIndex == -1) {
            0L
        } else {
            nativeTimelineEndMs(lines, anchorIndex)
        }
        val nextIndex = anchorIndex + 1
        val nextStart = nativeTimelineStartMs(lines, nextIndex)
        val gapEnd = maxOf(gapStart, nextStart - 250L)
        if (gapEnd - gapStart < NATIVE_INTERLUDE_MIN_GAP_MS) return null
        if (gapEnd > currentTime && gapStart < currentTime) {
            return NativeInterlude(
                startMs = gapStart,
                endMs = gapEnd,
                anchorLineIndex = anchorIndex,
                isNextDuet = lines[nextIndex].alignment == PipoLyricAlignment.End,
            )
        }
        return null
    }
    return checkGap(currentIndex - 1) ?: checkGap(currentIndex) ?: checkGap(currentIndex + 1)
}

private fun nativeTimelineStartMs(lines: List<PipoLyricLine>, index: Int): Long {
    return nativeLineAudioStartMs(lines[index]).coerceAtLeast(0L)
}

private fun nativeTimelineEndMs(lines: List<PipoLyricLine>, index: Int): Long {
    return nativeLineAudioEndMs(lines[index])
}

private fun nativeLineMainStartMs(line: PipoLyricLine): Long {
    return minOf(line.startMs, line.chars.firstOrNull()?.startMs ?: line.startMs)
}

private fun nativeLineAudioStartMs(line: PipoLyricLine): Long {
    val ownStart = nativeLineMainStartMs(line)
    val companionStart = line.companionLines
        .filter { it.role == PipoLyricRole.Companion }
        .minOfOrNull { nativeLineMainStartMs(it) }
    return minOf(ownStart, companionStart ?: ownStart)
}

private fun nativeLineAudioEndMs(line: PipoLyricLine): Long {
    val timedCompanions = line.companionLines.filter { it.role == PipoLyricRole.Companion }
    val charEnd = (line.chars + timedCompanions.flatMap { it.chars })
        .maxOfOrNull { it.startMs + it.durationMs }
    val lineEnd = maxOf(
        line.startMs + line.durationMs,
        timedCompanions.maxOfOrNull { it.startMs + it.durationMs } ?: line.startMs,
    )
    return maxOf(charEnd ?: lineEnd, lineEnd)
}

@Composable
private fun rememberNativeVisibleInterlude(
    interlude: NativeInterlude?,
    currentTimeMs: Long,
    sessionKey: String,
): NativeInterlude? {
    var activeKey by remember(sessionKey) { mutableStateOf<String?>(null) }
    var visibleStartMs by remember(sessionKey) { mutableLongStateOf(0L) }
    val interludeKey = interlude?.let {
        "${it.anchorLineIndex}:${it.startMs}:${it.endMs}:${it.isNextDuet}"
    }

    LaunchedEffect(sessionKey, interludeKey) {
        if (interlude == null || interludeKey == null) {
            activeKey = null
            visibleStartMs = 0L
        } else {
            activeKey = interludeKey
            visibleStartMs = maxOf(interlude.startMs, currentTimeMs)
        }
    }

    return if (interlude != null && activeKey == interludeKey && visibleStartMs > 0L) {
        interlude.copy(startMs = visibleStartMs)
    } else {
        interlude
    }
}

@Composable
private fun rememberNativeLyricClockMs(
    rawPositionMs: Long,
    isPlaying: Boolean,
    sessionKey: String,
): State<Float> {
    val out = remember(sessionKey) { mutableFloatStateOf(rawPositionMs.toFloat().coerceAtLeast(0f)) }
    val anchorMs = remember(sessionKey) { mutableFloatStateOf(rawPositionMs.toFloat().coerceAtLeast(0f)) }
    val anchorNanos = remember(sessionKey) { mutableLongStateOf(System.nanoTime()) }
    val resetToken = remember(sessionKey) { mutableLongStateOf(0L) }
    val canExtrapolate = remember(sessionKey) {
        mutableStateOf(rawPositionMs > NATIVE_CLOCK_START_GUARD_MS)
    }

    LaunchedEffect(rawPositionMs, isPlaying, sessionKey) {
        val now = System.nanoTime()
        val nextRaw = rawPositionMs.toFloat().coerceAtLeast(0f)
        val previousRaw = anchorMs.floatValue
        val jumpedBackward = nextRaw + NATIVE_CLOCK_BACKWARD_RESET_MS < previousRaw
        val jumpedForward = nextRaw > previousRaw + NATIVE_CLOCK_SEEK_RESET_MS
        val shouldReset = !isPlaying || jumpedBackward || jumpedForward || out.floatValue <= 0.001f
        anchorMs.floatValue = nextRaw
        anchorNanos.longValue = now
        canExtrapolate.value = if (shouldReset) {
            nextRaw > NATIVE_CLOCK_START_GUARD_MS
        } else {
            canExtrapolate.value || nextRaw > NATIVE_CLOCK_START_GUARD_MS
        }
        if (shouldReset) {
            resetToken.longValue += 1L
            out.floatValue = nextRaw
        }
    }

    LaunchedEffect(isPlaying, sessionKey) {
        if (!isPlaying) {
            out.floatValue = anchorMs.floatValue
            return@LaunchedEffect
        }
        var smoothed = out.floatValue
        var seenResetToken = resetToken.longValue
        while (isActive) {
            withFrameNanos { frame ->
                if (seenResetToken != resetToken.longValue) {
                    seenResetToken = resetToken.longValue
                    smoothed = anchorMs.floatValue
                }
                if (!canExtrapolate.value) {
                    val rawTarget = anchorMs.floatValue
                    val rawDiff = rawTarget - smoothed
                    smoothed = when {
                        rawDiff < -NATIVE_CLOCK_BACKWARD_RESET_MS -> rawTarget
                        rawDiff > 0f -> rawTarget
                        else -> smoothed
                    }
                    out.floatValue = smoothed
                    return@withFrameNanos
                }
                val target = (anchorMs.floatValue + (frame - anchorNanos.longValue) / 1_000_000f)
                    .coerceAtLeast(0f)
                val diff = target - smoothed
                smoothed = when {
                    kotlin.math.abs(diff) > NATIVE_CLOCK_FRAME_RESET_MS -> target
                    diff < -NATIVE_CLOCK_BACKWARD_RESET_MS -> target
                    diff > 0f -> smoothed + diff * NATIVE_CLOCK_FOLLOW_ALPHA
                    else -> smoothed
                }
                out.floatValue = smoothed
            }
        }
    }
    return out
}

private fun nativeScrollSpringSpec(
    lines: List<PipoLyricLine>,
    activeIdx: Int,
    isInterludeActive: Boolean,
): AnimationSpec<Float> {
    if (isInterludeActive) {
        return spring(
            dampingRatio = nativeDampingRatio(
                stiffness = NATIVE_INTERLUDE_SCROLL_STIFFNESS,
                damping = NATIVE_INTERLUDE_SCROLL_DAMPING,
            ),
            stiffness = NATIVE_INTERLUDE_SCROLL_STIFFNESS,
        )
    }
    val interval = if (activeIdx in lines.indices && activeIdx - 1 in lines.indices) {
        nativeTimelineStartMs(lines, activeIdx) - nativeTimelineStartMs(lines, activeIdx - 1)
    } else {
        420L
    }
    val clamped = interval.coerceIn(100L, 800L).toFloat()
    val ratio = 1f - (clamped - 100f) / 700f
    val stiffness = 170f + ratio.pow(0.2f) * 50f
    val damping = kotlin.math.sqrt(stiffness) * NATIVE_SCROLL_DAMPING_MULTIPLIER
    return spring(
        dampingRatio = nativeDampingRatio(stiffness = stiffness, damping = damping),
        stiffness = stiffness.coerceIn(170f, 220f),
    )
}

private fun nativeManualRestoreSpringSpec(): AnimationSpec<Float> {
    return spring(
        dampingRatio = nativeDampingRatio(
            stiffness = NATIVE_MANUAL_RESTORE_STIFFNESS,
            damping = NATIVE_MANUAL_RESTORE_DAMPING,
        ),
        stiffness = NATIVE_MANUAL_RESTORE_STIFFNESS,
    )
}

private fun nativeSeekSpringSpec(): AnimationSpec<Float> {
    return spring(
        dampingRatio = nativeDampingRatio(
            stiffness = NATIVE_SEEK_SCROLL_STIFFNESS,
            damping = NATIVE_SEEK_SCROLL_DAMPING,
        ),
        stiffness = NATIVE_SEEK_SCROLL_STIFFNESS,
    )
}

private fun nativeDampingRatio(
    stiffness: Float,
    damping: Float,
): Float {
    val root = kotlin.math.sqrt(stiffness.coerceAtLeast(0.0001f))
    return (damping / (2f * root)).coerceAtLeast(0.01f)
}

private fun nativeClampScrollCenter(
    current: Float,
    anchorY: Float,
    totalHeight: Float,
    viewportHeight: Float,
): Float {
    if (totalHeight <= 0f || viewportHeight <= 0f) return current
    val minCenter = viewportHeight * NATIVE_MANUAL_TOP_BOUNCE_FRACTION - anchorY
    val maxCenter = totalHeight + viewportHeight * NATIVE_MANUAL_BOTTOM_BOUNCE_FRACTION - anchorY
    return current.coerceIn(minCenter, maxCenter.coerceAtLeast(minCenter))
}

private fun nativeLineWidthPx(
    containerWidthPx: Int,
    horizontalPaddingPx: Float,
    compactWidthPx: Float,
    aspect: Float,
): Float {
    val available = (containerWidthPx.toFloat() - horizontalPaddingPx * 2f).coerceAtLeast(1f)
    return if (available <= compactWidthPx) {
        available
    } else {
        available * aspect.coerceIn(0.2f, 1f)
    }
}

private fun nativeInterludeDotSizePx(
    fontPx: Float,
    viewportHeightPx: Float,
): Float {
    return (viewportHeightPx * 0.01f).coerceIn(fontPx * 0.5f, fontPx * 3f)
}

private fun nativeRowEnterProgress(enterProgress: Float, distance: Int): Float {
    if (enterProgress >= 0.999f) return 1f
    val delay = (distance * 0.035f).coerceAtMost(0.32f)
    val raw = ((enterProgress - delay) / (1f - delay)).coerceIn(0f, 1f)
    return 1f - (1f - raw).let { it * it * it }
}

private fun nativeScrollMotionProgress(
    fromCenter: Float,
    targetCenter: Float,
    currentCenter: Float,
): Float {
    val distance = targetCenter - fromCenter
    if (kotlin.math.abs(distance) < 1f) return 1f
    return ((currentCenter - fromCenter) / distance).coerceIn(0f, 1f)
}

private fun nativeLyricSessionKey(sessionId: String?, lines: List<PipoLyricLine>): String {
    val first = lines.firstOrNull()
    val last = lines.lastOrNull()
    return "${sessionId.orEmpty()}:${lines.size}:${first?.startMs}:${first?.text.hashCode()}:${last?.startMs}:${last?.text.hashCode()}"
}

private fun nativeRenderPositionMs(clockMs: Float): Long {
    return (clockMs + NATIVE_SWEEP_VISUAL_LEAD_MS).toLong().coerceAtLeast(0L)
}

private fun Float.pow(power: Float): Float = Math.pow(this.toDouble(), power.toDouble()).toFloat()

private val NATIVE_WORD_FLOAT_EASE = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val NATIVE_BREAK_PUNCTUATION = setOf(
    ',',
    '.',
    ';',
    ':',
    '!',
    '?',
    '，',
    '。',
    '；',
    '：',
    '！',
    '？',
    '、',
    '）',
    '】',
    '》',
    '」',
    '』',
    '’',
    '”',
    ')',
    ']',
    '}',
    '>',
    '~',
    '…',
)

private const val NATIVE_ALIGN_POSITION = 0.35f
private const val NATIVE_ACTIVE_LINE_TOP_UPSHIFT_ROWS = 0.8f
private const val NATIVE_ACTIVE_LINE_EXTRA_UPSHIFT_DP = 80f
private const val NATIVE_EFFECTS_ENABLE_ENTER_PROGRESS = 0.985f
private const val NATIVE_ROW_CLICK_ENTER_PROGRESS = 0.995f
private const val NATIVE_ROW_CLICK_MIN_ALPHA = 0.05f
private const val NATIVE_INACTIVE_SCALE = 0.97f
private const val NATIVE_ROW_SCALE_FOCUS_SPAN_ROWS = 1.15f
private const val NATIVE_LINE_WIDTH_ASPECT = 0.8f
private const val NATIVE_COMPACT_WIDTH_DP = 768f
private const val NATIVE_INITIAL_RENDER_RADIUS_LINES = 10
private const val NATIVE_RENDER_WINDOW_BUFFER_ROWS = 7
private const val NATIVE_RENDER_WINDOW_BUFFER_VIEWPORT_FRACTION = 0.45f
private const val NATIVE_WORD_FADE_WIDTH_RATIO = 1.0f
private const val NATIVE_SWEEP_VISUAL_LEAD_MS = 45f
private const val NATIVE_SWEEP_TRANSITION_FRACTION = 0.20f
private const val NATIVE_SWEEP_TRANSITION_MIN_EM = 0.16f
private const val NATIVE_SWEEP_PROGRESS_EPS = 0.001f
private const val NATIVE_SWEEP_ACCENT_START = 0.34f
private const val NATIVE_SWEEP_ACCENT_END = 0.62f
private const val NATIVE_SWEEP_ACCENT_ALPHA = 0.88f
private const val NATIVE_SLOW_GLYPH_CLIP_PAD_EM = 0.22f
private const val NATIVE_SLOW_GLYPH_CLIP_BLUR_GAIN = 0.25f
private const val NATIVE_GLYPH_VERTICAL_CLIP_PAD_EM = 0.12f
private const val NATIVE_SLOW_GLYPH_DESCENDER_EXTRA_EM = 0.10f
// 慢词只看当前 token/词的总时长，不再按字母数拆平均时长。
private const val NATIVE_SLOW_WORD_MIN_DURATION_MS = 600L
// 慢词 rolling wave 高度：Apple Music 风格是低幅漂浮，不是单字弹跳。
private const val NATIVE_SLOW_LIFT_EM_GAIN = 0.20f
// 只保留一点呼吸式放大，避免字母尺寸变化造成跳动感。
private const val NATIVE_SLOW_SCALE_GAIN = 0.06f
private const val NATIVE_SLOW_SHADOW_FULL_DURATION_SPAN_MS = 2_600f
private const val NATIVE_SLOW_SHADOW_MIN_DURATION_STRENGTH = 0.70f
private const val NATIVE_SLOW_SHADOW_MAX_DURATION_STRENGTH = 1.0f
private const val NATIVE_SLOW_SHADOW_MAIN_ALPHA_MAX = 0.55f
private const val NATIVE_SLOW_SHADOW_BG_ALPHA_MAX = 0.16f
private const val NATIVE_SLOW_SHADOW_BLUR_MIN_EM = 0.07f
private const val NATIVE_SLOW_SHADOW_BLUR_GAIN_EM = 0.16f
private const val NATIVE_SLOW_SHADOW_STABLE_GAIN = 0.92f
private const val NATIVE_SLOW_SHADOW_STABLE_BLUR_GAIN = 0.95f
private const val NATIVE_SLOW_GLOW_EDGE_PAD_EM = 0.08f
private const val NATIVE_SLOW_COMET_FILL_ALPHA = 0.001f
private const val NATIVE_SLOW_COMET_TRAIL_DP = 56f
private const val NATIVE_SLOW_COMET_LEAD_DP = 18f
private const val NATIVE_SLOW_COMET_PEAK_DP = 10f
private const val NATIVE_SLOW_SWEEP_PREVIEW_LEAD_MS = 120L
private const val NATIVE_SLOW_SWEEP_GLOW_TAIL_MS = 280L
private const val NATIVE_MAIN_WORD_LIFT_EM = 0.025f
private const val NATIVE_BG_WORD_LIFT_EM = 0.055f
private const val NATIVE_DUET_INSET_ASPECT = 0.15f
private const val NATIVE_BUFFERED_OPACITY = 1.0f
private const val NATIVE_NON_DYNAMIC_OPACITY = 0.26f
private const val NATIVE_SOLID_LINE_ALPHA = 0.40f
private const val NATIVE_ACTIVE_UNSUNG_ALPHA = 0.54f
private const val NATIVE_COMPACT_LAYOUT_DP = 1024f
private const val NATIVE_BG_WRAPPER_MIN_SCALE = 0.80f
private const val NATIVE_BG_HIDDEN_SLIDE_RATIO = 0.80f
private const val NATIVE_BG_APPEAR_LEAD_MS = 160L
private const val NATIVE_BG_DISAPPEAR_TAIL_MS = 260L
private const val NATIVE_BG_SLIDE_STIFFNESS = 90f
private const val NATIVE_BG_SLIDE_DAMPING = 15f
private const val NATIVE_SUBLINE_OPACITY = 0.42f
private const val NATIVE_SUBLINE_WRAPPER_MIN_SCALE = 0.92f
private const val NATIVE_SUBLINE_HIDDEN_SLIDE_DP = 8f
private const val NATIVE_SUBLINE_HIDDEN_SLIDE_RATIO = 0.18f
private const val NATIVE_SUBLINE_SLIDE_STIFFNESS = 110f
private const val NATIVE_SUBLINE_SLIDE_DAMPING = 17f
private const val NATIVE_SUBLINE_FONT_SCALE = 0.50f
private const val NATIVE_SUBLINE_LINE_HEIGHT_SCALE = 0.75f
private const val NATIVE_MASK_ALPHA_ATTACK_SPEED = 50.0f
private const val NATIVE_MASK_ALPHA_RELEASE_SPEED = 7.0f
private const val NATIVE_WORD_EXIT_FLOAT_MS = 360L
private const val NATIVE_MANUAL_HOLD_MS = 2_800L
private const val NATIVE_INTERLUDE_MIN_GAP_MS = 4_000L
private const val NATIVE_INTERLUDE_BREATHE_TARGET_MS = 1_500f
private const val NATIVE_INTERLUDE_DOT_GAP_EM = 0.25f
private const val NATIVE_INTERLUDE_MARGIN_EM = 0.40f
private const val NATIVE_SCROLL_SNAP_DISTANCE_PX = 1_200f
private const val NATIVE_LAYOUT_TARGET_SNAP_DISTANCE_PX = 80f
private const val NATIVE_SCROLL_DAMPING_MULTIPLIER = 2.2f
private const val NATIVE_MANUAL_TOP_BOUNCE_FRACTION = 0.18f
private const val NATIVE_MANUAL_BOTTOM_BOUNCE_FRACTION = 0.70f
private const val NATIVE_MANUAL_RESTORE_STIFFNESS = 150f
private const val NATIVE_MANUAL_RESTORE_DAMPING = 22f
private const val NATIVE_SEEK_SCROLL_STIFFNESS = 230f
private const val NATIVE_SEEK_SCROLL_DAMPING = 28f
private const val NATIVE_INTERLUDE_SCROLL_STIFFNESS = 90f
private const val NATIVE_INTERLUDE_SCROLL_DAMPING = 15f
private const val NATIVE_OVERFLOW_PENALTY_MULTIPLIER = 1_000.0
private const val NATIVE_CJK_BREAK_PENALTY_RATIO = 0.15
private const val NATIVE_NORMAL_BREAK_PENALTY_RATIO = 0.50
private const val NATIVE_SPACE_BREAK_REWARD_RATIO = 0.40
private const val NATIVE_PUNCTUATION_BREAK_REWARD_RATIO = 0.60
private const val NATIVE_CLOCK_SEEK_RESET_MS = 1_500f
private const val NATIVE_CLOCK_FRAME_RESET_MS = 1_500f
private const val NATIVE_CLOCK_BACKWARD_RESET_MS = 300f
private const val NATIVE_CLOCK_FOLLOW_ALPHA = 0.82f
private const val NATIVE_CLOCK_START_GUARD_MS = 900L
