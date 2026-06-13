package app.pipo.nativeapp.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.PipoLyricAlignment
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.effectiveDurationMs
import app.pipo.nativeapp.data.effectiveEndMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
    activeLyricIndex: Int = 0,
    positionMs: Long = 0L,
    isPlaying: Boolean,
    positionProvider: (() -> Long)? = null,
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
    lyricLineHeight: TextUnit = 33.sp,
    lyricFontWeight: FontWeight = FontWeight.Bold,
    bottomFadeStart: Float = 0.80f,
    bottomFadeSoftEnd: Float = 0.94f,
    lineWidthAspect: Float = NATIVE_LINE_WIDTH_ASPECT,
    enterProgress: Float = 1f,
    // 固定歌词锚点的纵向偏移：正值=整体下移。横屏传 80dp 下移，竖屏默认 0 不变。
    anchorBiasDp: Dp = 0.dp,
) {
    val sessionKey = remember(sessionId, lines) { nativeLyricSessionKey(sessionId, lines) }
    val initialPositionMs = remember(sessionKey) { positionMs.coerceAtLeast(0L) }
    val rawPositionState = rememberNativeRawPositionState(
        fallbackPositionMs = positionMs,
        positionProvider = positionProvider,
        sessionKey = sessionKey,
        initialPositionMs = initialPositionMs,
    )
    val clockState = rememberNativeLyricClockMs(
        rawPositionState = rawPositionState,
        isPlaying = isPlaying,
        sessionKey = sessionKey,
        initialRawPositionMs = initialPositionMs,
    )
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()
    val lyricPlanCache = remember(sessionKey) {
        NativeLyricPlanCache(maxEntries = NATIVE_PREPARED_LINE_CACHE_LIMIT)
    }
    val prewarmTextMeasurer = rememberTextMeasurer()
    val prewarmGlyphMeasurer = rememberTextMeasurer(cacheSize = 0)

    if (lines.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "暂无歌词",
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
    val transMaxHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    val estimatedRowHeightPx = with(density) { rowMinHeight.toPx().toInt().coerceAtLeast(1) }
    val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
    val compactWidthPx = with(density) { NATIVE_COMPACT_WIDTH_DP.dp.toPx() }
    val timelineCache = remember(lines) { NativeTimelineCache(lines) }
    val nativeSlots = timelineCache.slots
    val lineToSlot = timelineCache.lineToSlot
    val slotCount = nativeSlots.size

    fun slotForLine(index: Int): Int {
        if (nativeSlots.isEmpty()) return 0
        val safeLine = index.coerceIn(lines.indices)
        val slot = if (safeLine in lineToSlot.indices) lineToSlot[safeLine] else 0
        return slot.coerceIn(nativeSlots.indices)
    }

    // 全列共享单一译文展开进度：切换译文时整列同相，避免逐行各自 spring 的相位差与取整漂移。
    val transAnim = remember(sessionKey) { Animatable(if (showTranslation) 1f else 0f) }
    LaunchedEffect(showTranslation) {
        transAnim.animateTo(
            targetValue = if (showTranslation) 1f else 0f,
            animationSpec = tween(
                durationMillis = NATIVE_SUBLINE_ANIMATION_MS,
                // Apple Web revealTranslations/revealPronunciations omit easing and fall back to
                // AnimSystem's linear default for y/opacity/max-height.
                easing = LinearEasing,
            ),
        )
    }
    val transProgress = transAnim.value.coerceAtLeast(0f)
    val transProgressState = rememberUpdatedState(transProgress)
    val rowMetrics by remember(sessionKey, slotCount, estimatedRowHeightPx) {
        derivedStateOf {
            nativeRowMetrics(
                slots = nativeSlots,
                estimatedRowHeightPx = estimatedRowHeightPx,
                transProgress = transProgressState.value,
                mainRowHeights = mainRowHeights,
                transFullHeights = transFullHeights,
                transMaxHeights = transMaxHeights,
            )
        }
    }

    fun rowHeight(index: Int): Int = rowMetrics.rowHeight(index)
    fun renderTop(index: Int): Float = rowMetrics.renderTop(index)
    // 基准坐标：剔除译文展开，仅由主体高度累加，scrollSpring 全程工作在此坐标系。
    fun rowTopBase(index: Int): Float = rowMetrics.rowTopBase(index)
    fun rowAnchor(index: Int): Float {
        val safeIndex = index.coerceIn(nativeSlots.indices)
        return rowTopBase(safeIndex)
    }
    // 基准坐标位置 → 需叠加的译文累计量：随基准位置连续累加每行译文高度，
    // 跨越某行时按比例计入。这样「切行滚动」与「译文展开」都连续无跳变
    // （切行时译文高度随 spring 平滑分摊，而非整段瞬跳）。
    fun transOffsetForBase(basePos: Float): Float = rowMetrics.transOffsetForBase(basePos)
    // 渲染坐标 → 基准坐标：手动拖动以 1:1 手感工作在渲染坐标，松手时换算回基准交给 spring。
    fun baseForRenderCenter(renderPos: Float): Float = rowMetrics.baseForRenderCenter(renderPos)

    val initialTimelineSnapshot = remember(sessionKey, lines) {
        nativeTimelineSnapshot(
            lines = lines,
            cache = timelineCache,
            positionMs = initialPositionMs,
            // Apple Web updateCurrentIndex: currentPlaybackMillis + 250ms。
            // 先用于当前行/滚动目标前瞻；当前行词级 TimeGroup 也在绘制层接同一提前量。
            targetPositionMs = initialPositionMs + NATIVE_SCROLL_FOCUS_LEAD_MS,
        )
    }
    val timelineSnapshotState = remember(sessionKey, lines) { mutableStateOf(initialTimelineSnapshot) }
    LaunchedEffect(sessionKey, lines, isPlaying, clockState, rawPositionState) {
        while (isActive) {
            val clockMs = if (isPlaying) {
                clockState.value.toLong()
            } else {
                rawPositionState.value
            }
            val nextSnapshot = nativeTimelineSnapshot(
                lines = lines,
                cache = timelineCache,
                positionMs = clockMs,
                targetPositionMs = clockMs + NATIVE_SCROLL_FOCUS_LEAD_MS,
            )
            if (timelineSnapshotState.value !== nextSnapshot) {
                timelineSnapshotState.value = nextSnapshot
            }
            withFrameNanos { }
        }
    }
    val timelineSnapshot by timelineSnapshotState
    val playbackActiveSlotIdx = timelineSnapshot.targetSlotIndex.coerceIn(nativeSlots.indices)
    val playbackActiveIdx = timelineSnapshot.targetIndex.coerceIn(lines.indices)
    val currentLineIdx = timelineSnapshot.currentLineIndex
    val lineSwitchProgress = remember(sessionKey) { Animatable(1f) }
    val lineSwitchProgressState = remember(sessionKey) { mutableFloatStateOf(1f) }
    var lineSwitchFromIdx by remember(sessionKey) { mutableStateOf(currentLineIdx) }
    var lineSwitchToIdx by remember(sessionKey) { mutableStateOf(currentLineIdx) }
    val lineSwitchPending = currentLineIdx != lineSwitchToIdx
    val effectiveLineSwitchFromIdx = if (lineSwitchPending) lineSwitchToIdx else lineSwitchFromIdx
    val effectiveLineSwitchToIdx = if (lineSwitchPending) currentLineIdx else lineSwitchToIdx
    LaunchedEffect(sessionKey, currentLineIdx) {
        if (currentLineIdx == lineSwitchToIdx) return@LaunchedEffect
        lineSwitchFromIdx = lineSwitchToIdx
        lineSwitchToIdx = currentLineIdx
        lineSwitchProgress.snapTo(0f)
        lineSwitchProgressState.floatValue = 0f
        lineSwitchProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = NATIVE_LINE_GEOMETRY_SWITCH_MS,
                easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD,
            ),
        ) {
            lineSwitchProgressState.floatValue = value
        }
        lineSwitchProgressState.floatValue = 1f
    }
    val singingAnchorIdx = (timelineSnapshot.activeIndices.minOrNull() ?: playbackActiveIdx)
        .coerceIn(lines.indices)
    val layoutAnchorSlotIdx = if (currentLineIdx < 0) {
        playbackActiveSlotIdx
    } else {
        slotForLine(singingAnchorIdx)
    }
    val activeLineTopLiftPx = estimatedRowHeightPx.toFloat() * NATIVE_ACTIVE_LINE_TOP_UPSHIFT_ROWS
    val activeLineExtraLiftPx = with(density) { NATIVE_ACTIVE_LINE_EXTRA_UPSHIFT_DP.dp.toPx() }
    val anchorBiasPx = with(density) { anchorBiasDp.toPx() }
    val anchorYPx = containerHeightPx * NATIVE_ALIGN_POSITION - activeLineTopLiftPx - activeLineExtraLiftPx + anchorBiasPx

    val initialActiveLyricIndex = if (positionProvider != null) {
        initialTimelineSnapshot.targetIndex
    } else {
        activeLyricIndex
    }
    val scrollSpring = remember(sessionKey) {
        Animatable(rowAnchor(slotForLine(initialActiveLyricIndex)))
    }
    var lastScrollTargetIdx by remember(sessionKey) { mutableStateOf(playbackActiveSlotIdx) }
    var initialLayoutSettled by remember(sessionKey) { mutableStateOf(false) }
    var lastScrollTargetCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var lastLayoutAnchorIdx by remember(sessionKey) { mutableStateOf(layoutAnchorSlotIdx) }
    var lastLayoutAnchorCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    val scrollTargetCenter = rowAnchor(playbackActiveSlotIdx)
    val layoutAnchorCenter = rowAnchor(layoutAnchorSlotIdx)
    val measuredRowCount = mainRowHeights.size
    var manualScrollCenterPx by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var manualHoldUntilMs by remember(sessionKey) { mutableLongStateOf(0L) }
    var isUserDragging by remember(sessionKey) { mutableStateOf(false) }
    // 两段式跳转：第一击“固定”该行（pendingSeekIdx 高亮 + 暂停跟随），第二击同一行才 seek。
    var pendingSeekIdx by remember(sessionKey) { mutableStateOf(-1) }
    var pendingSeekUntilMs by remember(sessionKey) { mutableLongStateOf(0L) }
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
    // 首帧定位独立成 effect：容器与首屏行测量完成后，把滚动锚到当前目标并解锁入场。
    // 关键：把"新行进入渲染窗口被测量(measuredRowCount 变化)"从下面的跟随 effect 的重启 key 里剥离。
    // 否则每测量一行就重启一次跟随 effect、把切句滚动的 tween 打断重来、永远到不了目标，
    // 滚动持续落后于当前句，十几行后当前句被推出屏幕 → 看起来"动画消失了"。
    LaunchedEffect(sessionKey, containerHeightPx, measuredRowCount) {
        if (initialLayoutSettled) return@LaunchedEffect
        if (containerHeightPx <= 0 || measuredRowCount <= 0) return@LaunchedEffect
        scrollSpring.snapTo(scrollTargetCenter)
        initialLayoutSettled = true
    }
    LaunchedEffect(
        sessionKey,
        // 仅当"滚动目标"或"布局锚点"的实际位置变化时才重启跟随；新行(在当前句下方)被测量
        // 不会改变 mainPrefix[当前句] → scrollTargetCenter/layoutAnchorCenter 不变 → 不重启。
        scrollTargetCenter,
        layoutAnchorCenter,
        playbackActiveSlotIdx,
        layoutAnchorSlotIdx,
        containerHeightPx,
        isPlaying,
        manualScrollActive,
        isUserDragging,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (manualScrollActive || isUserDragging || manualHoldUntilMs > now) {
            lastScrollTargetIdx = playbackActiveSlotIdx
            lastScrollTargetCenter = scrollTargetCenter
            lastLayoutAnchorIdx = layoutAnchorSlotIdx
            lastLayoutAnchorCenter = layoutAnchorCenter
            return@LaunchedEffect
        }
        if (!initialLayoutSettled) {
            // 首帧定位由上面的 effect 负责；未定位前只记录基准、不做跟随。
            lastScrollTargetIdx = playbackActiveSlotIdx
            lastScrollTargetCenter = scrollTargetCenter
            lastLayoutAnchorIdx = layoutAnchorSlotIdx
            lastLayoutAnchorCenter = layoutAnchorCenter
            return@LaunchedEffect
        }
        val previousIdx = lastScrollTargetIdx
        val previousLayoutAnchorIdx = lastLayoutAnchorIdx
        val previousLayoutAnchorCenter = lastLayoutAnchorCenter
        lastScrollTargetIdx = playbackActiveSlotIdx
        lastScrollTargetCenter = scrollTargetCenter
        lastLayoutAnchorIdx = layoutAnchorSlotIdx
        lastLayoutAnchorCenter = layoutAnchorCenter
        val sameTargetLayoutShift =
            previousIdx == playbackActiveSlotIdx &&
                previousLayoutAnchorIdx == layoutAnchorSlotIdx &&
                previousLayoutAnchorCenter.isFinite()
        if (sameTargetLayoutShift) {
            val layoutDelta = layoutAnchorCenter - previousLayoutAnchorCenter
            if (kotlin.math.abs(layoutDelta) > 0.5f) {
                scrollSpring.snapTo(scrollSpring.value + layoutDelta)
            }
        }
        val targetIdx = playbackActiveSlotIdx.coerceIn(nativeSlots.indices)
        val targetRowHeight = rowHeight(targetIdx).toFloat()
        val targetRowTop = rowTopBase(targetIdx) + anchorYPx - scrollSpring.value
        // 只有"目标行完全在当前屏幕之外"才必须瞬移：中间这些行没被渲染窗口组合，
        // 动画滚过去只会划过空白。其余任意距离的可见切换都走弹簧跟随动画。
        val targetLineInvisible = containerHeightPx > 0 &&
            (targetRowTop + targetRowHeight < 0f || targetRowTop > containerHeightPx.toFloat())
        if (targetLineInvisible) {
            scrollSpring.snapTo(scrollTargetCenter)
            return@LaunchedEffect
        }
        if (!isPlaying) {
            scrollSpring.stop()
            return@LaunchedEffect
        }
        scrollSpring.animateTo(
            targetValue = scrollTargetCenter,
            animationSpec = nativeScrollFollowSpringSpec(),
        )
    }

    LaunchedEffect(manualHoldUntilMs, isUserDragging, scrollTargetCenter, playbackActiveSlotIdx) {
        val waitMs = manualHoldUntilMs - SystemClock.elapsedRealtime()
        if (waitMs > 0L || isUserDragging) {
            delay(waitMs.coerceAtLeast(0L))
        }
        if (!isUserDragging && manualHoldUntilMs <= SystemClock.elapsedRealtime() && manualScrollCenterPx.isFinite()) {
            // manualScrollCenterPx 是渲染坐标，换算回基准坐标后交给 spring 复位。
            val startCenter = baseForRenderCenter(manualScrollCenterPx)
            manualScrollCenterPx = Float.NaN
            scrollSpring.snapTo(startCenter)
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
            val pa = timelineSnapshot.targetSlotIndex.coerceIn(nativeSlots.indices)
            val m = manualScrollCenterPx
            if (!m.isFinite()) {
                pa
            } else {
                rowMetrics.nearestRenderIndex(m)
            }
        }
    }

    // 渲染窗口：固定行数半径（只依赖容器高度与 rowMinHeight 常量，不读实时测量 → 不会随行测量振荡）。
    // 半径里含一段“预热缓冲”——它让即将进入可视区的行提前完成 text layout / timed plan 构建，
    // 把每行较重的首帧构建成本藏在滚动之前；缓冲过小会导致“滚到的行才现场构建 → 卡顿”。
    // 该窗口宽度是本设备验证过的流畅基线。
    val renderRadiusRows = if (containerHeightPx > 0) {
        containerHeightPx / estimatedRowHeightPx.coerceAtLeast(1) + NATIVE_RENDER_WINDOW_BUFFER_ROWS
    } else {
        NATIVE_INITIAL_RENDER_RADIUS_LINES
    }
    val lastSlotIndex = nativeSlots.lastIndex.coerceAtLeast(0)
    val visibleStartIndex = (visualActiveIdx - renderRadiusRows).coerceIn(0, lastSlotIndex)
    val visibleEndIndex = (visualActiveIdx + renderRadiusRows).coerceIn(visibleStartIndex, lastSlotIndex)
    val initialWindowMeasured = if (containerHeightPx > 0 && nativeSlots.isNotEmpty()) {
        var ready = true
        for (slotIndex in visibleStartIndex..visibleEndIndex) {
            if (!mainRowHeights.containsKey(slotIndex)) ready = false
            val slot = nativeSlots[slotIndex]
            if (slot is NativeLyricSlot.Line) {
                val line = lines[slot.lineIndex]
                if (
                    showTranslation &&
                    nativeHasStaticSubline(line) &&
                    (!transFullHeights.containsKey(slotIndex) || !transMaxHeights.containsKey(slotIndex))
                ) {
                    ready = false
                }
            }
        }
        ready
    } else {
        false
    }
    var initialRevealReady by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(
        sessionKey,
        initialLayoutSettled,
        initialWindowMeasured,
        playbackActiveSlotIdx,
        showTranslation,
    ) {
        if (initialRevealReady) return@LaunchedEffect
        if (!initialLayoutSettled || !initialWindowMeasured) return@LaunchedEffect
        // Let the measure callbacks and the initial scroll snap land before the first visible frame.
        withFrameNanos { }
        withFrameNanos { }
        initialRevealReady = true
    }
    val initialRevealAlpha by animateFloatAsState(
        targetValue = if (initialRevealReady) 1f else 0f,
        animationSpec = tween(durationMillis = NATIVE_INITIAL_REVEAL_MS, easing = NATIVE_LINE_SWITCH_EASE),
        label = "nativeLyricInitialReveal",
    )
    val calibratedEnterProgress = enterProgress * initialRevealAlpha
    val effectsSettled = calibratedEnterProgress >= NATIVE_EFFECTS_ENABLE_ENTER_PROGRESS
    val interactionReady = initialRevealReady &&
        calibratedEnterProgress >= NATIVE_ROW_CLICK_ENTER_PROGRESS

    val lineWidthAspectState = rememberUpdatedState(lineWidthAspect.coerceIn(0.2f, 1f))
    val sharedLineWidthPx = nativeLineWidthPx(
        containerWidthPx = containerWidthPx,
        horizontalPaddingPx = horizontalPaddingPx,
        compactWidthPx = compactWidthPx,
        aspect = lineWidthAspectState.value,
    )

    fun warmPreparedLyricLine(line: PipoLyricLine, warmGlyphs: Boolean): Boolean {
        val lineTextAlign = if (line.alignment == PipoLyricAlignment.End) TextAlign.End else TextAlign.Start
        val lineStyle = nativeLyricTextStyle(
            fontSize = lyricFontSize,
            lineHeight = lyricLineHeight,
            fontWeight = nativeAppleLyricFontWeight(line.text, lyricFontWeight),
            textAlign = lineTextAlign,
        )
        var didWork = nativePrewarmLyricLine(
            sourceLine = line,
            lineWidthPx = sharedLineWidthPx,
            style = lineStyle,
            textAlign = lineTextAlign,
            textMeasurer = prewarmTextMeasurer,
            glyphMeasurer = prewarmGlyphMeasurer,
            density = density,
            cache = lyricPlanCache,
            warmGlyphs = warmGlyphs,
        )
        val hostAlignEnd = line.alignment == PipoLyricAlignment.End
        line.companionLines
            .filter { it.role == PipoLyricRole.Companion }
            .forEach { companion ->
                val companionAlignEnd = hostAlignEnd || companion.alignment == PipoLyricAlignment.End
                val companionTextAlign = if (companionAlignEnd) TextAlign.End else TextAlign.Start
                val companionFontSize = lyricFontSize * NATIVE_BG_FONT_SCALE
                val companionLineHeight = lyricLineHeight * NATIVE_BG_LINE_HEIGHT_SCALE
                val companionStyle = nativeLyricTextStyle(
                    fontSize = companionFontSize,
                    lineHeight = companionLineHeight,
                    fontWeight = nativeAppleLyricFontWeight(companion.text, FontWeight.Bold),
                    textAlign = companionTextAlign,
                )
                didWork = nativePrewarmLyricLine(
                    sourceLine = companion,
                    lineWidthPx = sharedLineWidthPx,
                    style = companionStyle,
                    textAlign = companionTextAlign,
                    textMeasurer = prewarmTextMeasurer,
                    glyphMeasurer = prewarmGlyphMeasurer,
                    density = density,
                    cache = lyricPlanCache,
                    warmGlyphs = warmGlyphs,
                ) || didWork
            }
        return didWork
    }

    suspend fun warmPreparedSlotRange(
        start: Int,
        endInclusive: Int,
        step: Int,
        linesPerFrame: Int,
        warmGlyphs: Boolean,
    ) {
        if (step == 0 || nativeSlots.isEmpty()) return
        val safeLinesPerFrame = linesPerFrame.coerceAtLeast(1)
        var warmedThisFrame = 0
        var slotIndex = start
        while (
            slotIndex in 0..lastSlotIndex &&
            if (step > 0) slotIndex <= endInclusive else slotIndex >= endInclusive
        ) {
            val slot = nativeSlots[slotIndex]
            if (slot is NativeLyricSlot.Line) {
                val line = lines[slot.lineIndex]
                if (warmPreparedLyricLine(line, warmGlyphs = warmGlyphs)) {
                    warmedThisFrame += 1
                    if (warmedThisFrame >= safeLinesPerFrame) {
                        warmedThisFrame = 0
                        withFrameNanos { }
                    }
                }
            }
            slotIndex += step
        }
    }

    LaunchedEffect(
        sessionKey,
        sharedLineWidthPx.roundToInt(),
        lyricFontSize,
        lyricLineHeight,
        lyricFontWeight,
        visualActiveIdx,
        initialRevealReady,
        isPlaying,
        manualScrollActive,
        isUserDragging,
    ) {
        if (
            containerWidthPx <= 0 ||
            nativeSlots.isEmpty() ||
            !initialRevealReady ||
            !isPlaying ||
            manualScrollActive ||
            isUserDragging
        ) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val forwardEnd = (visualActiveIdx + NATIVE_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
        val backwardStart = (visualActiveIdx - NATIVE_PREWARM_BEHIND_ROWS).coerceAtLeast(0)
        warmPreparedSlotRange(
            start = visualActiveIdx + 1,
            endInclusive = forwardEnd,
            step = 1,
            linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
            warmGlyphs = false,
        )
        warmPreparedSlotRange(
            start = visualActiveIdx - 1,
            endInclusive = backwardStart,
            step = -1,
            linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
            warmGlyphs = false,
        )
        val glyphForwardEnd = (visualActiveIdx + NATIVE_GLYPH_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
        warmPreparedSlotRange(
            start = visualActiveIdx + 1,
            endInclusive = glyphForwardEnd,
            step = 1,
            linesPerFrame = NATIVE_GLYPH_PREWARM_LINES_PER_FRAME,
            warmGlyphs = true,
        )
    }
    val topFadeEnd = 0.16f
    val bottomSolidStop = bottomFadeStart.coerceIn(0.60f, 0.96f)
    val bottomSoftStop = bottomFadeSoftEnd.coerceIn(bottomSolidStop, 0.99f)
    // 渐隐遮罩 Brush 只建一次（之前每帧 draw 都重新分配 colorStops 数组 + Brush）。
    val fadeMaskBrush = remember(topFadeEnd, bottomSolidStop, bottomSoftStop) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.05f to Color.Black.copy(alpha = 0.12f),
                0.11f to Color.Black.copy(alpha = 0.72f),
                topFadeEnd to Color.Black,
                bottomSolidStop to Color.Black,
                bottomSoftStop to Color.Black.copy(alpha = 0.45f),
                1f to Color.Transparent,
            ),
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                containerWidthPx = it.width
                containerHeightPx = it.height
            }
            // 同时裁剪绘制与命中测试：锚点上方的行 offset 为负、布局上会伸出容器顶部，
            // 不裁剪的话这些（视觉上已被遮罩渐隐的）行会拦截容器外的点击 ——
            // 横屏里标题/翻译按钮点不动、反而触发歌词 seek 就是这个原因。
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = fadeMaskBrush,
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            .then(
                if (interactionReady) {
                    Modifier.pointerInput(sessionKey) {
                        detectDragGestures(
                            onDragStart = {
                                isUserDragging = true
                                pendingSeekIdx = -1
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
                                    totalHeight = rowMetrics.totalRenderHeight,
                                    viewportHeight = containerHeightPx.toFloat(),
                                )
                                manualScrollCenterPx = next
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // 渲染窗口按「虚拟行」裁剪（基于 visualActiveIdx），不再依赖每帧 rowY：
        // 用最小行高估算半径（高估行数→多渲染几行，保证滚动时不漏、不空白）。
        for (slotIndex in visibleStartIndex..visibleEndIndex) {
            val slot = nativeSlots[slotIndex]
            when (slot) {
                is NativeLyricSlot.Line -> {
                    val idx = slot.lineIndex
                    val line = lines[idx]
                    key("line", slotIndex, line.startMs, line.text) {
                        val distance = kotlin.math.abs(slotIndex - visualActiveIdx)
                        val rowEnter = nativeRowEnterProgress(calibratedEnterProgress, distance)
                        // Apple Web uses the +250ms lookahead to choose one discrete
                        // `.is-current` synced line. Interlude slots deliberately map
                        // to no current lyric line, so the next lyric does not light up
                        // until the same currentIndex switch reaches its slot.
                        val isCurrentLine = idx == currentLineIdx
                        val isActive = isCurrentLine
                        val hasLineSwitchTransition = effectiveLineSwitchFromIdx != effectiveLineSwitchToIdx
                        val lineTransitionRole = when {
                            hasLineSwitchTransition && idx == effectiveLineSwitchToIdx && effectiveLineSwitchToIdx >= 0 ->
                                NativeLineTransitionRole.Entering
                            hasLineSwitchTransition && idx == effectiveLineSwitchFromIdx && effectiveLineSwitchFromIdx >= 0 ->
                                NativeLineTransitionRole.Leaving
                            else -> NativeLineTransitionRole.None
                        }
                        val lineTransitionProgress = if (lineTransitionRole == NativeLineTransitionRole.None) {
                            1f
                        } else if (lineSwitchPending) {
                            0f
                        } else {
                            lineSwitchProgressState.floatValue
                        }
                        val isManualFocus = manualScrollActive && slotIndex == visualActiveIdx
                        val isFocused = idx == pendingSeekIdx || if (manualScrollActive) {
                            slotIndex == visualActiveIdx
                        } else {
                            idx == currentLineIdx
                        }
                        val hasAppleLineFocus = isCurrentLine || isManualFocus || idx == pendingSeekIdx
                        val isPast = idx < playbackActiveIdx
                        val blurTarget = nativeLineBlur(
                            hasLineFocus = hasAppleLineFocus,
                            isFirstLine = idx == 0,
                            isUserInteracting = isUserDragging || manualScrollActive,
                        )
                        val itemAlignment = if (line.alignment == PipoLyricAlignment.End) {
                            Alignment.TopEnd
                        } else {
                            Alignment.TopStart
                        }
                        val lineWidthPx = sharedLineWidthPx
                        val lineWidthDp = with(density) { lineWidthPx.toDp() }
                        val lineContentWidthPx = lineWidthPx
                        val rowVisibleForClick = interactionReady &&
                            rowEnter > NATIVE_ROW_CLICK_MIN_ALPHA

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding)
                                .graphicsLayer {
                                    translationY = renderTop(slotIndex) + anchorYPx - renderCenterNow()
                                },
                            contentAlignment = itemAlignment,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(lineWidthDp)
                                    .then(
                                        if (rowVisibleForClick) {
                                            Modifier.pointerInput(line.startMs, line.text) {
                                                detectTapGestures(
                                                    onTap = {
                                                        val nowMs = SystemClock.elapsedRealtime()
                                                        if (pendingSeekIdx == idx && nowMs <= pendingSeekUntilMs) {
                                                            pendingSeekIdx = -1
                                                            pendingSeekUntilMs = 0L
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            manualHoldUntilMs = 0L
                                                            isUserDragging = false
                                                            val seekCenter = rowAnchor(slotIndex)
                                                            val resumeCenter = baseForRenderCenter(renderCenterNow())
                                                            manualScrollCenterPx = Float.NaN
                                                            gestureScope.launch {
                                                                scrollSpring.stop()
                                                                scrollSpring.snapTo(resumeCenter)
                                                                scrollSpring.animateTo(
                                                                    targetValue = seekCenter,
                                                                    animationSpec = nativeSeekSpringSpec(),
                                                                )
                                                            }
                                                            onSeekToMs(LyricTiming.audioStartMs(line).coerceAtLeast(0L))
                                                        } else {
                                                            pendingSeekIdx = idx
                                                            pendingSeekUntilMs = nowMs + NATIVE_TAP_CONFIRM_WINDOW_MS
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            manualHoldUntilMs = maxOf(manualHoldUntilMs, pendingSeekUntilMs)
                                                            gestureScope.launch {
                                                                delay(NATIVE_TAP_CONFIRM_WINDOW_MS)
                                                                if (
                                                                    pendingSeekIdx == idx &&
                                                                    SystemClock.elapsedRealtime() >= pendingSeekUntilMs
                                                                ) {
                                                                    pendingSeekIdx = -1
                                                                }
                                                            }
                                                        }
                                                    },
                                                )
                                            }
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
                                    isCurrentLine = isCurrentLine,
                                    isPast = isPast,
                                    timeState = rawPositionState,
                                    clockState = clockState,
                                    fg = fg,
                                    fgUnsung = fgUnsung,
                                    transProgress = transProgress,
                                    enterAlpha = rowEnter,
                                    onMainHeight = { h -> if (mainRowHeights[slotIndex] != h) mainRowHeights[slotIndex] = h },
                                    onTransFullHeight = { h -> if (transFullHeights[slotIndex] != h) transFullHeights[slotIndex] = h },
                                    onTransMaxHeight = { h -> if (transMaxHeights[slotIndex] != h) transMaxHeights[slotIndex] = h },
                                    rowMinHeight = rowMinHeight,
                                    isUserDragging = isUserDragging,
                                    isPlaying = isPlaying,
                                    targetBlur = blurTarget,
                                    isManualFocus = isManualFocus,
                                    fontSize = lyricFontSize,
                                    lineHeight = lyricLineHeight,
                                    fontWeight = lyricFontWeight,
                                    verticalPadding = rowVerticalPadding,
                                    lineWidthPx = lineContentWidthPx,
                                    effectsEnabled = effectsSettled,
                                    lineTransitionProgress = lineTransitionProgress,
                                    lineTransitionRole = lineTransitionRole,
                                    planCache = lyricPlanCache,
                                )
                            }
                        }
                    }
                }

                is NativeLyricSlot.Interlude -> {
                    key("interlude", slotIndex, slot.startMs, slot.endMs) {
                        val distance = kotlin.math.abs(slotIndex - visualActiveIdx)
                        val rowEnter = nativeRowEnterProgress(calibratedEnterProgress, distance)
                        val nextLine = lines[slot.nextLineIndex.coerceIn(lines.indices)]
                        val alignEnd = nextLine.alignment == PipoLyricAlignment.End
                        val itemAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart
                        val lineWidthPx = sharedLineWidthPx
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding)
                                .graphicsLayer {
                                    translationY = renderTop(slotIndex) + anchorYPx - renderCenterNow()
                                },
                            contentAlignment = itemAlignment,
                        ) {
                            Box(
                                modifier = Modifier.width(with(density) { lineWidthPx.toDp() }),
                                contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
                            ) {
                                NativeInterludeRow(
                                    interlude = slot,
                                    isCurrent = slotIndex == playbackActiveSlotIdx && currentLineIdx < 0,
                                    clockState = clockState,
                                    color = fg,
                                    enterAlpha = rowEnter,
                                    onMainHeight = { h -> if (mainRowHeights[slotIndex] != h) mainRowHeights[slotIndex] = h },
                                    fontSize = lyricFontSize,
                                    alignEnd = alignEnd,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeInterludeRow(
    interlude: NativeLyricSlot.Interlude,
    isCurrent: Boolean,
    clockState: State<Float>,
    color: Color,
    enterAlpha: Float,
    onMainHeight: (Int) -> Unit,
    fontSize: TextUnit,
    alignEnd: Boolean,
) {
    val density = LocalDensity.current
    val currentLineLayoutProgress by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = tween(
            durationMillis = NATIVE_LINE_GEOMETRY_SWITCH_MS,
            easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD,
        ),
        label = "nativeInterludeCurrentPadding",
    )
    val fontPx = with(density) { fontSize.toPx() }
    val dotSizePx = nativeInterludeDotSizePx(fontPx)
    val dotGapPx = dotSizePx * NATIVE_INTERLUDE_DOT_GAP_RATIO
    val dotsGroupWidthPx = dotSizePx * NATIVE_INTERLUDE_DOT_COUNT +
        dotGapPx * (NATIVE_INTERLUDE_DOT_COUNT - 1f)
    val dotCanvasSizePx = dotSizePx * NATIVE_INTERLUDE_MAX_SCALE
    val dotsCanvasWidthPx = dotsGroupWidthPx * NATIVE_INTERLUDE_MAX_SCALE
    val marginPx = fontPx * NATIVE_INTERLUDE_MARGIN_EM
    val currentPaddingPx = fontPx * NATIVE_CURRENT_LINE_PADDING_EM * currentLineLayoutProgress
    val expandedHeightPx = (dotCanvasSizePx + marginPx * 2f + currentPaddingPx * 2f)
        .roundToInt()
        .coerceAtLeast(1)
    val dotSizeDp = with(density) { dotSizePx.toDp() }
    val dotCanvasSizeDp = with(density) { dotCanvasSizePx.toDp() }
    val dotGapDp = with(density) { dotGapPx.toDp() }
    val dotsWidthDp = with(density) { dotsCanvasWidthPx.toDp() }

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .graphicsLayer {
                alpha = enterAlpha
            },
        content = {
            NativeInterludeDots(
                interlude = interlude,
                clockState = clockState,
                offsetMs = 0L,
                color = color,
                dotSize = dotSizeDp,
                dotGap = dotGapDp,
                width = dotsWidthDp,
                height = dotCanvasSizeDp,
            )
        },
    ) { measurables, constraints ->
        val dotPlaceable = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val rowHeight = expandedHeightPx
        onMainHeight(rowHeight)
        val width = constraints.maxWidth
        layout(width, rowHeight) {
            val x = if (alignEnd) {
                width - dotPlaceable.width
            } else {
                0
            }.coerceAtLeast(0)
            val y = ((rowHeight - dotPlaceable.height) / 2).coerceAtLeast(0)
            dotPlaceable.place(x, y)
        }
    }
}

@Composable
private fun NativeAmllLyricRow(
    line: PipoLyricLine,
    isActive: Boolean,
    isFocused: Boolean,
    isCurrentLine: Boolean,
    isPast: Boolean,
    timeState: State<Long>,
    clockState: State<Float>,
    fg: Color,
    fgUnsung: Color,
    transProgress: Float,
    enterAlpha: Float,
    onMainHeight: (Int) -> Unit,
    onTransFullHeight: (Int) -> Unit,
    onTransMaxHeight: (Int) -> Unit,
    rowMinHeight: Dp,
    isUserDragging: Boolean,
    isPlaying: Boolean,
    targetBlur: Float,
    isManualFocus: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    verticalPadding: Dp,
    lineWidthPx: Float,
    effectsEnabled: Boolean,
    lineTransitionProgress: Float,
    lineTransitionRole: NativeLineTransitionRole,
    planCache: NativeLyricPlanCache,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 背景人声（大字浮入、参与时间轴）= Companion；小字行 = 罗马音(音译) + 翻译。
    val timedCompanions = remember(line) { line.companionLines.filter { it.role == PipoLyricRole.Companion } }
    // 罗马音排在翻译之前：主词 → 罗马音 → 翻译，对齐 AMLL / Apple 的子行顺序。
    val translations = remember(line) {
        line.companionLines
            .filter { it.role == PipoLyricRole.Translation || it.role == PipoLyricRole.Romaji }
            .sortedBy { if (it.role == PipoLyricRole.Romaji) 0 else 1 }
    }
    val backgroundVocals = remember(line) { timedCompanions }
    // Apple Web: componentDidLoad() 会在当前行上直接 manageAnimations(true)，不会因为
    // 容器刚入场就把这一整行的扫色/慢词锁死。这里仅阻止 true->false 在激活期回落；
    // false->true 仍允许补上，避免开屏或 seek 后当前行完全看不到慢词。
    var rowEffectsEnabled by remember(line) { mutableStateOf(effectsEnabled || isActive) }
    LaunchedEffect(line, effectsEnabled, isActive) {
        if ((effectsEnabled || isActive) && !rowEffectsEnabled) {
            rowEffectsEnabled = true
        } else if (!isActive && rowEffectsEnabled != effectsEnabled) {
            rowEffectsEnabled = effectsEnabled
        }
    }
    val rowBlur by animateFloatAsState(
        targetValue = if (!effectsEnabled) 0f else targetBlur,
        animationSpec = tween(durationMillis = NATIVE_BLUR_TRANSITION_MS, easing = LinearEasing),
        label = "nativeLyricRowBlur",
    )
    val rowBlurEffect = remember(rowBlur, density) {
        val blurPx = with(density) { rowBlur.dp.toPx() }
        if (blurPx > 0.05f) {
            BlurEffect(blurPx, blurPx, TileMode.Decal)
        } else {
            null
        }
    }
    // 唱过的行保持 current 的放大比例，不在切句瞬间缩回 1.0；future 行才回到常规尺寸。
    // 缩放只在 graphicsLayer 做视觉变化，不写入 row metrics，所以不会再把滚动目标向下拉一下。
    val currentLineLayoutProgress by animateFloatAsState(
        targetValue = if (isCurrentLine || isPast) 1f else 0f,
        animationSpec = tween(
            durationMillis = NATIVE_LINE_GEOMETRY_SWITCH_MS,
            easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD,
        ),
        label = "nativeLyricCurrentPadding",
    )
    val pivotX = if (line.alignment == PipoLyricAlignment.End) 1f else 0f
    val itemAlignment = if (line.alignment == PipoLyricAlignment.End) Alignment.End else Alignment.Start
    val textAlign = if (line.alignment == PipoLyricAlignment.End) TextAlign.End else TextAlign.Start
    val effectiveMainFontWeight = nativeAppleLyricFontWeight(line.text, fontWeight)
    val mainTextStyle = nativeLyricTextStyle(fontSize, lineHeight, effectiveMainFontWeight, textAlign)
    val mainPreparedKey = remember(line, lineWidthPx, mainTextStyle, textAlign) {
        nativePreparedLyricKey(line, lineWidthPx, mainTextStyle, textAlign)
    }
    val mainPrepared = planCache.get(mainPreparedKey)
    val displayLine = remember(line, lineWidthPx, mainTextStyle, textMeasurer, mainPrepared) {
        mainPrepared?.displayLine ?: nativeBalancedLyricLine(
            line = nativeAppleDisplayTimedLine(line),
            containerWidthPx = lineWidthPx,
            style = mainTextStyle,
            textMeasurer = textMeasurer,
        ).also { planCache.putDisplay(mainPreparedKey, it) }
    }
    val preparedTimedPlan = mainPrepared?.timedPlan
    // 行距全部放在顶部（= 4×verticalPadding，相邻行间距翻倍至 32dp），底部不留白：
    // 译文紧贴主歌词，且收起时尾部无空白会被 clip 透出。
    val rowTopPadPx = with(LocalDensity.current) { (verticalPadding * 4).roundToPx() }
    val rowMinHeightPx = with(LocalDensity.current) { rowMinHeight.roundToPx() }
    val backgroundVocalTopMargin = with(density) {
        (fontSize.toPx() * NATIVE_BG_MARGIN_TOP_EM).toDp()
    }
    val translationMaxHeightPx = with(density) {
        (fontSize.toPx() * (NATIVE_SUBLINE_MAX_HEIGHT_WEB_PX / NATIVE_APPLE_WEB_LINE_FONT_PX))
            .roundToInt()
            .coerceAtLeast(1)
    }
    val translationBottomGapPx = with(density) {
        (fontSize.toPx() * NATIVE_SUBLINE_BOTTOM_GAP_EM)
            .roundToInt()
            .coerceAtLeast(0)
    }
    val transMaxHeightPx = translationMaxHeightPx * translations.size
    // 主体与译文以单次 Layout 组合：主体高度（含行距、应用最小行高）稳定且不含译文，
    // 译文完整高度与 Apple max-height 上限单独上报；高度曲线用
    // min(full, 50px×progress)，对齐 Apple Web `max-height:[0,"50px"]`。
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val layoutProgress = when (lineTransitionRole) {
                    NativeLineTransitionRole.Entering -> lineTransitionProgress.coerceIn(0f, 1f)
                    else -> currentLineLayoutProgress
                }
                val rowScale = nativeRowScale(layoutProgress)
                // 入场淡入 + 行透明度合并到同一层（原先各占一个 graphicsLayer，每行省 2 层）。
                alpha = enterAlpha
                scaleX = rowScale
                scaleY = rowScale
                // Apple Web's current-line class only transitions color,
                // scale and block padding. The per-word/letter y keyframes
                // carry the lyric lift, so avoid an extra whole-line translate
                // that can fight the scrollTop animation on multi-line rows.
                // 缩放支点 = 行顶，与滚动定位锚点一致：行的纵向定位是把“行顶”对齐到 anchorY
                // （translationY = renderTop + anchorY - renderCenter）。若缩放绕行中心(0.5)，
                // 唱完那行 1.1→1.0 时会顶部下移、底部上收 —— 与整列上滚叠加就成了用户看到的
                // “先往下顶一下再往上滚”。改成绕行顶(0f)缩放后：当前行顶始终钉在锚点，唱完只是
                // 向上收拢、不产生任何向下位移，配合上滚连续流畅。水平仍按对齐边（左/右）作支点。
                transformOrigin = TransformOrigin(pivotX, 0f)
                // blur 改在层内读取（Modifier.blur 会在 composition 读状态：切句后的 260ms
                // 模糊动画期间每帧重组整行；这里读取只更新本层，且少一个独立 blur 层）。
                clip = false
                renderEffect = rowBlurEffect
            },
        content = {
            // slot 0：主体（伴唱 + 主歌词），不含纵向行距；高度稳定。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
        Box(modifier = Modifier.align(itemAlignment)) {
            NativeAmllLyricText(
                line = displayLine,
                sourceLine = line,
                // Apple Web only applies timed gradient/shadow under
                // `.display-synced-line.is-current`. Keep row-level overlap
                // bookkeeping outside the text draw path so non-current
                // overlapping lines cannot keep sweeping/lifting into the next
                // line's TimeGroup.
                isActive = isCurrentLine,
                isFocused = isFocused,
                isPast = isPast,
                timeState = timeState,
                clockState = if (line.chars.isNotEmpty() || isActive) clockState else null,
                isPlaying = isPlaying,
                fg = fg,
                fgUnsung = fgUnsung,
                keepFocusGradient = isManualFocus,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = effectiveMainFontWeight,
                textAlign = textAlign,
                effectsEnabled = rowEffectsEnabled,
                wordTimelineOffsetMs = if (isCurrentLine) NATIVE_SCROLL_FOCUS_LEAD_MS else 0L,
                lineTransitionProgress = lineTransitionProgress,
                lineTransitionRole = lineTransitionRole,
                preparedPlan = preparedTimedPlan,
                preparedKey = mainPreparedKey,
                planCache = planCache,
            )
        }

        backgroundVocals.forEachIndexed { bgIndex, companion ->
            key("bg", companion.startMs, companion.text) {
                NativeAmllCompanionLine(
                    companion = companion,
                    hostCurrentLine = isCurrentLine,
                    itemAlignment = itemAlignment,
                    timeState = timeState,
                    clockState = clockState,
                    fg = fg,
                    fgUnsung = fgUnsung,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    isPlaying = isPlaying,
                    lineWidthPx = lineWidthPx,
                    topMargin = if (bgIndex == 0) backgroundVocalTopMargin else 0.dp,
                    effectsEnabled = rowEffectsEnabled,
                    planCache = planCache,
                )
            }
        }

            }
            // slot 1：译文完整内容（顶部 1dp 贴合主歌词；该 1dp 计入 transFull，progress=0 时一并收起）。
            if (translations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    translations.forEach { translation ->
                        key("sub", translation.role, translation.startMs, translation.text) {
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
            }
        },
    ) { measurables, constraints ->
        // 最小行高内化到主体测量：保证 measuredMainHeight≥rowMinHeight，且主体下方无留白可透出译文。
        // coerce 上界防止容器尚未测量（maxHeight=0）时 minHeight>maxHeight 崩溃。
        val bodyMinHeight = (rowMinHeightPx - rowTopPadPx).coerceIn(0, constraints.maxHeight)
        val bodyPlaceable = measurables[0].measure(constraints.copy(minHeight = bodyMinHeight))
        val transPlaceable = measurables.getOrNull(1)?.measure(constraints)
        // current 行缩放只做图层视觉变化，不再把 padding 写进 row metrics。
        // 否则切句时上一行变矮/下一行变高会先改滚动目标，看起来像先下沉再上滚。
        val measuredMainHeight = bodyPlaceable.height + rowTopPadPx
        onMainHeight(measuredMainHeight)
        val fullTransContent = transPlaceable?.height ?: 0
        val fullTrans = if (fullTransContent > 0) {
            fullTransContent + translationBottomGapPx
        } else {
            0
        }
        onTransFullHeight(fullTrans)
        onTransMaxHeight(if (fullTransContent > 0) transMaxHeightPx + translationBottomGapPx else transMaxHeightPx)
        val collapsedTrans = nativeAppleSublineCollapsedHeight(
            fullHeightPx = fullTrans,
            maxHeightPx = transMaxHeightPx,
            progress = transProgress,
        )
        val width = constraints.maxWidth
        val totalHeight = measuredMainHeight + collapsedTrans
        layout(width, totalHeight) {
            // 顶部留行距；current 几何状态不再影响布局高度和 bodyTop。
            // 译文紧贴主体底部展开，超出 collapsedTrans 的部分由 clipToBounds 收起。
            val bodyTop = rowTopPadPx
            bodyPlaceable.place(0, bodyTop)
            transPlaceable?.place(0, bodyTop + bodyPlaceable.height)
        }
    }
}

@Composable
private fun NativeAmllCompanionLine(
    companion: PipoLyricLine,
    hostCurrentLine: Boolean,
    itemAlignment: Alignment.Horizontal,
    timeState: State<Long>,
    clockState: State<Float>,
    fg: Color,
    fgUnsung: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    isPlaying: Boolean,
    lineWidthPx: Float,
    topMargin: Dp,
    effectsEnabled: Boolean,
    planCache: NativeLyricPlanCache,
) {
    val textMeasurer = rememberTextMeasurer()
    val companionStartMs = remember(companion) { nativeCompanionStartMs(companion) }
    val companionStarted by remember(companion, companionStartMs) {
        derivedStateOf { timeState.value >= companionStartMs }
    }
    // Apple Music 的背景人声不会跟着主行 current 立刻展开；它按自己的 data-delay
    // 到点才 display/animate。Android 也只在副词本身唱到后撑开高度；唱过以后保持
    // target=1，不在 host 行 past 后收回。
    val targetAppear = if (companionStarted) 1f else 0f
    // 出现动画：像 Apple Music 一样淡入 + 上滑 + 撑开列表高度，而非离散闪出。
    // future→current 时 0→1 平滑过渡（高度由 nativeBackgroundVocalReveal 同步撑开）；
    // current→past 恒 1 不触发；effectsEnabled=false（低端/省电）直接取目标值省去每帧高度重测。
    val appearAnim by animateFloatAsState(
        targetValue = targetAppear,
        animationSpec = tween(
            durationMillis = NATIVE_BG_REVEAL_MS,
            easing = NATIVE_BG_REVEAL_EASE,
        ),
        label = "nativeCompanionReveal",
    )
    val appear = if (effectsEnabled) appearAnim else targetAppear
    // 副词不用主行的 250ms lookahead，否则“出现”时 sweep 已经提前跑了 250ms，
    // 视觉上仍像是主词一出来就跟着抢跑。
    val wordTimelineOffsetMs = 0L
    // derivedStateOf：每个位置 tick 只做几次比较，布尔翻转（出现/活跃/唱过边界）才触发重组。
    val companionPast by remember(companion, wordTimelineOffsetMs) {
        derivedStateOf { nativeIsCompanionPast(companion, timeState.value + wordTimelineOffsetMs) }
    }
    val companionTimedActive = hostCurrentLine && companionStarted && !companionPast
    val visualCompanionPast = companionPast && !companionTimedActive
    val companionAlignment = if (companion.alignment == PipoLyricAlignment.End) Alignment.End else itemAlignment
    val companionTextAlign = if (companionAlignment == Alignment.End) TextAlign.End else TextAlign.Start
    val boxAlignment = if (companionAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart
    val companionFontSize = fontSize * NATIVE_BG_FONT_SCALE
    val companionLineHeight = lineHeight * NATIVE_BG_LINE_HEIGHT_SCALE
    val companionFontWeight = nativeAppleLyricFontWeight(companion.text, FontWeight.Bold)
    val companionStyle = nativeLyricTextStyle(
        fontSize = companionFontSize,
        lineHeight = companionLineHeight,
        fontWeight = companionFontWeight,
        textAlign = companionTextAlign,
    )
    val companionPreparedKey = remember(companion, lineWidthPx, companionStyle, companionTextAlign) {
        nativePreparedLyricKey(companion, lineWidthPx, companionStyle, companionTextAlign)
    }
    val companionPrepared = planCache.get(companionPreparedKey)
    val displayCompanion = remember(companion, lineWidthPx, companionStyle, textMeasurer, companionPrepared) {
        companionPrepared?.displayLine ?: nativeBalancedLyricLine(
            line = nativeAppleDisplayTimedLine(companion),
            containerWidthPx = lineWidthPx,
            style = companionStyle,
            textMeasurer = textMeasurer,
        ).also { planCache.putDisplay(companionPreparedKey, it) }
    }
    val preparedTimedPlan = companionPrepared?.timedPlan
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topMargin)
            .nativeBackgroundVocalReveal(appear = appear)
            .graphicsLayer {
                alpha = appear
                // 轻微上滑：未出现时下沉一点，随 appear 浮现到位，配合高度撑开像“被唱出来”。
                translationY = (1f - appear) * companionFontSize.toPx() * NATIVE_BG_REVEAL_SLIDE_EM
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (companionAlignment == Alignment.End) 1f else 0f,
                    pivotFractionY = 0f,
                )
            },
        contentAlignment = boxAlignment,
    ) {
        NativeAmllLyricText(
            line = displayCompanion,
            sourceLine = companion,
            // 背景人声按自己的开始时间进入 timed sweep；唱完后退到静态已唱色但保持可见。
            isActive = companionTimedActive,
            isPast = visualCompanionPast,
            timeState = timeState,
            clockState = if (companion.chars.isNotEmpty()) clockState else null,
            isPlaying = isPlaying,
            fg = fg,
            fgUnsung = fgUnsung,
            fontSize = companionFontSize,
            lineHeight = companionLineHeight,
            fontWeight = companionFontWeight,
            textAlign = companionTextAlign,
            isBackgroundVocal = true,
            effectsEnabled = effectsEnabled,
            wordTimelineOffsetMs = wordTimelineOffsetMs,
            preparedPlan = preparedTimedPlan,
            preparedKey = companionPreparedKey,
            planCache = planCache,
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
    val density = LocalDensity.current
    val isSupplementary = translation.role == PipoLyricRole.Romaji
    val sublineFontScale = if (isSupplementary) {
        NATIVE_SUPPLEMENTARY_FONT_SCALE
    } else {
        NATIVE_SUBLINE_FONT_SCALE
    }
    val sublineLineHeightScale = if (isSupplementary) {
        NATIVE_SUPPLEMENTARY_LINE_HEIGHT_SCALE
    } else {
        NATIVE_SUBLINE_LINE_HEIGHT_SCALE
    }
    val sublineFontSize = fontSize * sublineFontScale
    val sublineLineHeight = lineHeight * sublineLineHeightScale
    val hiddenSlidePx = with(density) {
        fontSize.toPx() * (NATIVE_SUBLINE_HIDDEN_SLIDE_WEB_PX / NATIVE_APPLE_WEB_LINE_FONT_PX)
    }
    val visibleTopMargin = with(density) {
        (sublineFontSize.toPx() * NATIVE_SUBLINE_VISIBLE_MARGIN_TOP_EM).toDp()
    }
    // Apple Web:
    // - secondary: opacity 0 -> .45, y -10 -> 0, font 13px, margin-top .2em.
    // - static-supplementary/pronunciation: opacity 0 -> 1, y -10 -> 0, font 15px, margin-top .2em.
    // Apple 的 inline ruby `.supplementary` 才是 y -20；当前数据里的 Romaji 是整行 x-roman。
    // - .line:lang(zh) drops to 600; Latin stays 700.
    // 高度折叠由外层容器统一处理，这里只做内容层位移与淡入。
    val appear = progress.coerceIn(0f, 1f)
    val sublineFontWeight = nativeAppleLyricFontWeight(translation.text, FontWeight.Bold)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = visibleTopMargin * appear)
            .graphicsLayer {
                alpha = appear
                translationY = -(1f - appear) * hiddenSlidePx
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (itemAlignment == Alignment.End) 1f else 0f,
                    pivotFractionY = 0f,
                )
            },
        contentAlignment = if (itemAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = translation.text,
            color = nativeSublineColor(translation.role, fg),
            style = nativeLyricTextStyle(
                fontSize = sublineFontSize,
                lineHeight = sublineLineHeight,
                fontWeight = sublineFontWeight,
                textAlign = textAlign,
            ),
        )
    }
}

private fun Modifier.nativeBackgroundVocalReveal(
    appear: Float,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val progress = appear.coerceIn(0f, 1f)
    val collapsedHeight = (placeable.height * progress).roundToInt()
    layout(placeable.width, collapsedHeight) {
        placeable.placeRelative(0, 0)
    }
}

@Composable
private fun NativeAmllLyricText(
    line: PipoLyricLine,
    sourceLine: PipoLyricLine = line,
    isActive: Boolean,
    isFocused: Boolean = isActive,
    isPast: Boolean,
    timeState: State<Long>,
    clockState: State<Float>? = null,
    isPlaying: Boolean,
    fg: Color,
    fgUnsung: Color,
    keepFocusGradient: Boolean = false,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    isBackgroundVocal: Boolean = false,
    effectsEnabled: Boolean = true,
    wordTimelineOffsetMs: Long = 0L,
    lineTransitionProgress: Float = 1f,
    lineTransitionRole: NativeLineTransitionRole = NativeLineTransitionRole.None,
    preparedPlan: NativeTimedLyricPlan? = null,
    preparedKey: NativePreparedLyricKey? = null,
    planCache: NativeLyricPlanCache? = null,
) {
    var layout by remember(line.startMs, line.text, preparedPlan) {
        mutableStateOf<TextLayoutResult?>(preparedPlan?.layout)
    }
    var timedPlan by remember(line.startMs, line.text, line.chars, sourceLine.chars, preparedPlan) {
        mutableStateOf(preparedPlan)
    }
    // cacheSize=0：禁用测量器自身的去重缓存。否则一行里相同的单字符（如三个慢词各有的 'e'）会命中
    // 缓存、返回同一个 TextLayoutResult 实例，导致它们共用底层 Paragraph/Paint —— 逐字发光时给某个
    // 'e' 设的 Shadow 会串到其它 'e' 上（“第一个 e 发光，其它 e 跟着发光”的根因）。每字形/每段的复用
    // 已由 plan 的按索引缓存（glyphLayouts / segmentLayouts）保证，这里禁用去重不会带来重复测量。
    val glowMeasurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val style = nativeLyricTextStyle(fontSize, lineHeight, fontWeight, textAlign)
    val lineStartMs = remember(sourceLine) { nativeLineMainStartMs(sourceLine) }
    val lineEndMs = remember(sourceLine) { nativeLineAudioEndMs(sourceLine) }
    val canUseFocusGradient = !isPast || keepFocusGradient
    val lineSungOut by remember(line) {
        derivedStateOf { timeState.value >= lineEndMs }
    }
    // Apple Web 的 gradient/text-shadow selector 只挂在 `.is-current`。
    // Android 不再让 previous line 继续 timed 自绘；否则 +250ms current lookahead
    // 会出现“下一行已开唱，上一行词级上浮还在动”的交叉抖动。
    // 手动拖动到非当前播放行时，视觉焦点应该保留行色渐变；如果继续走 timed 自绘，
    // 它会用真实播放时钟计算这个远处句子的 sweep，结果常常是 0 或已结束，焦点渐变被透明文本路径盖掉。
    val manualStaticFocus = keepFocusGradient && !isActive
    val shouldDrawTimed = line.chars.isNotEmpty() &&
        !manualStaticFocus &&
        isActive
    val timedDrawOffsetMs = if (shouldDrawTimed) wordTimelineOffsetMs else 0L
    val appleLineClockState = rememberNativeAppleLineClockMs(
        lineStartMs = lineStartMs,
        lineKey = "${sourceLine.startMs}:${sourceLine.text}",
        localIsActive = shouldDrawTimed,
        isPlaying = isPlaying,
        rawPositionState = timeState,
        wordTimelineOffsetMs = timedDrawOffsetMs,
    )
    // 静态文本色不再离散跳变（旧的 when 分支在 fgUnsung→fg→已唱色之间瞬切，就是
    // “切句时颜色跳一下/闪一下”的来源之一）。改成 focus / past 两个通道各自 tween、
    // 连续插值：激活增亮、唱过变暗都有 Apple 式渐变；边界值与逐字自绘路径两端严格一致
    // （未来=fgUnsung、唱过=nativeSolidLineColor），LRC 整行歌词从此也有亮度过渡。
    val focusColorAnim by animateFloatAsState(
        targetValue = if ((isActive || isFocused) && canUseFocusGradient) 1f else 0f,
        animationSpec = tween(durationMillis = NATIVE_LINE_COLOR_FADE_MS, easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD),
        label = "nativeLyricFocusColor",
    )
    val pastColorAnim by animateFloatAsState(
        // “已唱”是时间事实而非焦点状态：被上一句压住焦点的短插句（isPast=false 但
        // 音频已结束）也按已唱色渐暗，否则它会以未唱色示人，与静态已唱终态相接时跳色。
        targetValue = if (isPast || lineSungOut) 1f else 0f,
        animationSpec = tween(durationMillis = NATIVE_LINE_COLOR_FADE_MS, easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD),
        label = "nativeLyricPastColor",
    )
    val switchProgress = if (lineTransitionRole != NativeLineTransitionRole.None) {
        lineTransitionProgress.coerceIn(0f, 1f)
    } else {
        1f
    }
    val focusColorProgress = when (lineTransitionRole) {
        NativeLineTransitionRole.Entering -> switchProgress
        NativeLineTransitionRole.Leaving -> 1f - switchProgress
        NativeLineTransitionRole.None -> focusColorAnim
    }
    val pastColorProgress = when (lineTransitionRole) {
        NativeLineTransitionRole.Entering -> 0f
        NativeLineTransitionRole.Leaving -> switchProgress
        NativeLineTransitionRole.None -> pastColorAnim
    }
    val timedPlanReady = timedPlan?.segments?.isNotEmpty() == true
    // 高亮端点取 timed 已唱色（alpha .85）而非纯 fg（alpha 1.0）：当前行靠 Canvas
    // timed 自绘的已唱色就是 .85，一旦它切成 past/失焦走静态文本路径，若静态高亮端点
    // 用满色 1.0，切换那一帧会从 .85 猛跳到 1.0 再渐隐到 .40（“切句闪一下”的根因）。
    // 端点对齐 .85 后，timed → 静态 的衔接无亮度断点，只剩平滑的 .85 → .40 渐暗。
    val staticFallbackColor = lerp(
        lerp(fgUnsung, nativeSolidLineColor(fg), pastColorProgress),
        nativeTimedGradientSungColor(fg),
        focusColorProgress,
    )
    val needsTimedFallback = shouldDrawTimed && !timedPlanReady && isActive
    val timedFallbackProgress = if (needsTimedFallback && lineEndMs > lineStartMs) {
        val timedFallbackPositionMs = appleLineClockState.value.toLong()
        ((timedFallbackPositionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // Apple Web timed syllables switch to a background-clip gradient whose endpoints are fixed at
    // alpha .85 (sung) and .5 (unsung). If Android has not built the TextLayout-driven sweep plan
    // yet, draw the one-frame fallback inside the same alpha range instead of full current-line fg;
    // otherwise the next frame drops from 1.0 -> .85/.5 and reads as a color jump.
    val timedFallbackColor = lerp(
        nativeTimedGradientUnsungColor(fg),
        nativeTimedGradientSungColor(fg),
        timedFallbackProgress,
    )
    val baseColor = when {
        shouldDrawTimed && timedPlanReady -> Color.Transparent
        needsTimedFallback -> timedFallbackColor
        else -> staticFallbackColor
    }

    Text(
        text = line.text,
        color = baseColor,
        style = style,
        // Apple Web renders every timed word as `.syllable { display:inline-block;
        // white-space:pre }`: wrapping only happens between syllable groups. We
        // already insert explicit balanced line breaks at token boundaries, so
        // disable Compose soft wrapping to avoid splitting one timed token into
        // multiple visual segments with independent clips.
        softWrap = false,
        onTextLayout = { result ->
            if (layout !== result) {
                val existingPlan = timedPlan
                layout = result
                if (line.chars.isNotEmpty() && !nativePreparedPlanMatches(existingPlan, result)) {
                    val nextPlan = nativeTimedLyricPlan(
                        layout = result,
                        chars = line.chars,
                        sourceChars = sourceLine.chars,
                        density = density,
                    )
                    timedPlan = nextPlan
                    if (preparedKey != null) {
                        planCache?.putPlan(preparedKey, line, nextPlan)
                    }
                } else if (line.chars.isEmpty()) {
                    timedPlan = null
                    if (preparedKey != null) {
                        planCache?.putPlan(preparedKey, line, null)
                    }
                } else if (existingPlan != null) {
                    timedPlan = existingPlan
                } else {
                    val nextPlan = nativeTimedLyricPlan(
                        layout = result,
                        chars = line.chars,
                        sourceChars = sourceLine.chars,
                        density = density,
                    )
                    timedPlan = nextPlan
                    if (preparedKey != null) {
                        planCache?.putPlan(preparedKey, line, nextPlan)
                    }
                }
            } else if (timedPlan == null && line.chars.isNotEmpty()) {
                val nextPlan = nativeTimedLyricPlan(
                    layout = result,
                    chars = line.chars,
                    sourceChars = sourceLine.chars,
                    density = density,
                )
                timedPlan = nextPlan
                if (preparedKey != null) {
                    planCache?.putPlan(preparedKey, line, nextPlan)
                }
            } else if (timedPlan == null && line.chars.isEmpty() && preparedKey != null) {
                planCache?.putPlan(preparedKey, line, null)
            }
        },
        modifier = if (shouldDrawTimed) {
            Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val plan = timedPlan
                if (result == null || plan == null || plan.segments.isEmpty()) {
                    drawContent()
                } else {
                    val drawPositionMs = nativeRenderPositionMs(appleLineClockState.value)
                    drawNativeTimedLyric(
                        plan = plan,
                        glyphMeasurer = glowMeasurer,
                        positionMs = drawPositionMs,
                        fg = fg,
                        fgUnsung = fgUnsung,
                        isPast = isPast,
                        isBackgroundVocal = isBackgroundVocal,
                        motionScale = if (lineTransitionRole == NativeLineTransitionRole.Entering) {
                            switchProgress
                        } else {
                            1f
                        },
                        lineFocusProgress = if (lineTransitionRole == NativeLineTransitionRole.Entering) {
                            switchProgress
                        } else {
                            1f
                        },
                        // Apple Web 的 gradient/text-shadow selector 只作用于 .is-current。
                        // willAnimate / pre-active focus 只准备行级视觉，不提前跑词级 sweep/glow。
                        effectsEnabled = effectsEnabled,
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
        lineHeight = nativeDescenderSafeLineHeight(fontSize, lineHeight),
        textAlign = textAlign,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
        // Android font metrics without font padding are too tight for descenders in
        // multi-line timed lyrics. The custom syllable renderer clips by TextLayout
        // line boxes; with includeFontPadding=false, g/y/j/p/q can cross that box
        // and get cut horizontally. Keep padding in the actual layout metrics so
        // descenders have legal space without overlapping adjacent line clip bands.
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )
}

private fun nativeDescenderSafeLineHeight(
    fontSize: TextUnit,
    lineHeight: TextUnit,
): TextUnit {
    val minLineHeight = fontSize * NATIVE_DESCENDER_SAFE_LINE_HEIGHT_EM
    return if (
        lineHeight.value.isFinite() &&
        minLineHeight.value.isFinite() &&
        lineHeight.value >= minLineHeight.value
    ) {
        lineHeight
    } else {
        minLineHeight
    }
}

private fun nativeAppleLyricFontWeight(text: String, fallback: FontWeight): FontWeight {
    return when {
        nativeIsCjkText(text) -> FontWeight.SemiBold
        fallback.weight > FontWeight.Bold.weight -> FontWeight.Bold
        else -> fallback
    }
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

private fun nativeAppleDisplayTimedLine(line: PipoLyricLine): PipoLyricLine {
    if (line.chars.isEmpty()) return line
    var changed = false
    val displayChars = line.chars.map { token ->
        val displayText = nativeAppleSyllableDisplayText(token.text)
        if (displayText == token.text) {
            token
        } else {
            changed = true
            token.copy(text = displayText)
        }
    }
    if (!changed) return line
    return line.copy(
        text = displayChars.joinToString(separator = "") { it.text },
        chars = displayChars,
    )
}

private fun nativeAppleSyllableDisplayText(text: String): String {
    val contentEnd = text.indexOfLast { !it.isWhitespace() && !it.isISOControl() } + 1
    if (contentEnd <= 0) return text
    val hasTrailingWhitespace = contentEnd < text.length
    val rawContent = text.substring(0, contentEnd)
    val hasParentheses = rawContent.indexOf('(') >= 0 || rawContent.indexOf(')') >= 0
    if (!hasParentheses && !hasTrailingWhitespace) return text
    return buildString(text.length) {
        rawContent.forEach { ch ->
            if (!nativeAppleStripsSyllableChar(ch)) append(ch)
        }
        // Apple Music Web stores inter-word spacing as `hasTrailingWhitespace` on the
        // outer group and renders it with `::after { margin-right: 0.3ch }`; it is not
        // part of the `.syllable` data-content or letter timeline. A thin space gives
        // Android Text a close layout gap without letting the timing segment include
        // a full normal-space advance.
        if (hasTrailingWhitespace && isNotEmpty()) append(NATIVE_APPLE_TRAILING_WORD_SPACE)
    }
}

private data class NativePreparedLyricKey(
    val startMs: Long,
    val durationMs: Long,
    val textHash: Int,
    val charCount: Int,
    val firstCharStartMs: Long,
    val lastCharStartMs: Long,
    val lastCharDurationMs: Long,
    val widthPx: Int,
    val fontSizeBits: Int,
    val lineHeightBits: Int,
    val fontWeight: Int,
    val textAlign: String,
)

private data class NativePreparedLyricLine(
    val displayLine: PipoLyricLine,
    val timedPlan: NativeTimedLyricPlan?,
    val glyphsWarmed: Boolean,
)

private enum class NativeLineTransitionRole {
    None,
    Entering,
    Leaving,
}

private class NativeLyricPlanCache(
    private val maxEntries: Int,
) {
    private val lines = object : java.util.LinkedHashMap<NativePreparedLyricKey, NativePreparedLyricLine>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<NativePreparedLyricKey, NativePreparedLyricLine>?,
        ): Boolean = size > maxEntries
    }

    fun get(key: NativePreparedLyricKey): NativePreparedLyricLine? = lines[key]

    fun putDisplay(key: NativePreparedLyricKey, displayLine: PipoLyricLine) {
        if (!lines.containsKey(key)) {
            lines[key] = NativePreparedLyricLine(
                displayLine = displayLine,
                timedPlan = null,
                glyphsWarmed = false,
            )
        }
    }

    fun putPlan(
        key: NativePreparedLyricKey,
        displayLine: PipoLyricLine,
        timedPlan: NativeTimedLyricPlan?,
        glyphsWarmed: Boolean = false,
    ) {
        lines[key] = NativePreparedLyricLine(
            displayLine = displayLine,
            timedPlan = timedPlan,
            glyphsWarmed = glyphsWarmed,
        )
    }

    fun isReady(
        key: NativePreparedLyricKey,
        needsTimedPlan: Boolean,
        needsGlyphWarm: Boolean,
    ): Boolean {
        val prepared = lines[key] ?: return false
        if (needsTimedPlan && prepared.timedPlan == null) return false
        if (needsGlyphWarm && !prepared.glyphsWarmed) return false
        return true
    }
}

private fun nativePreparedLyricKey(
    line: PipoLyricLine,
    lineWidthPx: Float,
    style: TextStyle,
    textAlign: TextAlign,
): NativePreparedLyricKey {
    val firstChar = line.chars.firstOrNull()
    val lastChar = line.chars.lastOrNull()
    return NativePreparedLyricKey(
        startMs = line.startMs,
        durationMs = line.durationMs,
        textHash = line.text.hashCode(),
        charCount = line.chars.size,
        firstCharStartMs = firstChar?.startMs ?: Long.MIN_VALUE,
        lastCharStartMs = lastChar?.startMs ?: Long.MIN_VALUE,
        lastCharDurationMs = lastChar?.durationMs ?: Long.MIN_VALUE,
        widthPx = lineWidthPx.roundToInt().coerceAtLeast(1),
        fontSizeBits = style.fontSize.value.toBits(),
        lineHeightBits = style.lineHeight.value.toBits(),
        fontWeight = style.fontWeight?.weight ?: 0,
        textAlign = textAlign.toString(),
    )
}

private fun nativeMeasurePreparedLayout(
    line: PipoLyricLine,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    widthPx: Int,
): TextLayoutResult {
    val safeWidth = widthPx.coerceAtLeast(1)
    return textMeasurer.measure(
        text = AnnotatedString(line.text),
        style = style,
        softWrap = false,
        constraints = Constraints(minWidth = safeWidth, maxWidth = safeWidth),
    )
}

private fun nativePreparedPlanMatches(
    plan: NativeTimedLyricPlan?,
    result: TextLayoutResult,
): Boolean {
    if (plan == null) return false
    return plan.layout.layoutInput.text.text == result.layoutInput.text.text &&
        plan.layout.size == result.size &&
        plan.layout.lineCount == result.lineCount
}

private fun nativeWarmTimedPlan(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
) {
    val text = plan.layout.layoutInput.text.text
    plan.segments.forEachIndexed { segmentIndex, segment ->
        if (plan.segNeedsDescenderSafe.getOrElse(segmentIndex) { false }) {
            nativeSegmentTextLayout(plan, glyphMeasurer, segmentIndex)
        }
        val slowAmount = plan.segSlow.getOrElse(segmentIndex) { 0f }
        if (slowAmount <= 0f) return@forEachIndexed
        for (index in segment.startChar until segment.endChar) {
            if (index !in text.indices || index !in plan.glyphBoxLeft.indices) continue
            if (nativeAppleStripsSyllableChar(text[index]) || text[index].isWhitespace() || text[index].isISOControl()) continue
            if (plan.glyphBoxLeft[index].isNaN()) continue
            nativeSlowGlyphLayout(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                text = text,
                index = index,
            )
        }
    }
}

private fun nativePrewarmLyricLine(
    sourceLine: PipoLyricLine,
    lineWidthPx: Float,
    style: TextStyle,
    textAlign: TextAlign,
    textMeasurer: TextMeasurer,
    glyphMeasurer: TextMeasurer,
    density: Density,
    cache: NativeLyricPlanCache,
    warmGlyphs: Boolean,
): Boolean {
    val key = nativePreparedLyricKey(sourceLine, lineWidthPx, style, textAlign)
    val needsTimedPlan = sourceLine.chars.isNotEmpty()
    val needsGlyphWarm = warmGlyphs && needsTimedPlan
    if (cache.isReady(key, needsTimedPlan = needsTimedPlan, needsGlyphWarm = needsGlyphWarm)) return false
    val prepared = cache.get(key)
    val displayLine = prepared?.displayLine ?: nativeBalancedLyricLine(
        line = nativeAppleDisplayTimedLine(sourceLine),
        containerWidthPx = lineWidthPx,
        style = style,
        textMeasurer = textMeasurer,
    )
    val timedPlan = if (displayLine.chars.isNotEmpty()) {
        prepared?.timedPlan ?: run {
            val layout = nativeMeasurePreparedLayout(
                line = displayLine,
                style = style,
                textMeasurer = textMeasurer,
                widthPx = lineWidthPx.roundToInt(),
            )
            nativeTimedLyricPlan(
                layout = layout,
                chars = displayLine.chars,
                sourceChars = sourceLine.chars,
                density = density,
            )
        }.also { plan ->
            if (warmGlyphs && prepared?.glyphsWarmed != true) {
                nativeWarmTimedPlan(plan, glyphMeasurer)
            }
        }
    } else {
        null
    }
    cache.putPlan(
        key = key,
        displayLine = displayLine,
        timedPlan = timedPlan,
        glyphsWarmed = warmGlyphs && timedPlan != null,
    )
    return true
}

private class NativeTimedLyricPlan(
    val layout: TextLayoutResult,
    val segments: List<NativeLyricSegment>,
    val fontPx: Float,
    val fadeWidth: Float,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
    // 段裁切带的实际上/下沿：只在不碰到相邻行的空间内补字形悬伸余量。
    // 不能让相邻行的 clip band 重叠，否则整段 paragraph 重绘会把别的行也染进来。
    val rowClipTop: FloatArray,
    val rowClipBottom: FloatArray,
    val rowGlowClipTop: FloatArray,
    val rowGlowClipBottom: FloatArray,
    val segClipLeft: FloatArray,
    val segClipRight: FloatArray,
    val segGlowClipLeft: FloatArray,
    val segGlowClipRight: FloatArray,
    val segSlow: FloatArray,
    val segNeedsDescenderSafe: BooleanArray,
    val glyphBoxLeft: FloatArray,
    val glyphBoxRight: FloatArray,
    val glyphStyle: TextStyle,
    val glyphLayouts: Array<TextLayoutResult?>,
    val segmentLayouts: Array<TextLayoutResult?>,
    val segmentInkLeft: FloatArray,
)

// 逐帧绘制要用的所有静态几何（段裁切边界、慢词字形盒、行上下沿、宽度参数）在排版
// 完成时一次算好：draw 每帧跑在 UI 线程，旧实现 per-frame 的布局查询与小对象分配
//（每段 new Bounds/SlowShape、每字 getBoundingBox + 字符串拼 key、filter 列表）
// 是稳定的 GC 压力与 CPU 热源——这正是“歌词页发热/掉帧”的组成部分。
private fun nativeTimedLyricPlan(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    sourceChars: List<PipoLyricChar>,
    density: Density,
): NativeTimedLyricPlan {
    val segments = nativeLyricSegments(layout, chars, sourceChars)
    val style = layout.layoutInput.style
    val fontPx = with(density) { style.fontSize.toPx() }
    val fadeWidth = nativeWordFadeWidth(layout, fontPx)
    val segClipLeft = FloatArray(segments.size)
    val segClipRight = FloatArray(segments.size)
    val segGlowClipLeft = FloatArray(segments.size)
    val segGlowClipRight = FloatArray(segments.size)
    // 悬伸/抗锯齿裁切余量在排版期一次性烘焙进段裁切带，并在函数内夹到词间留白中点，
    // 避免逐帧绘制时再对相邻段无条件加 pad 造成裁切带重叠（上浮错相位重影）。
    val glyphClipPad = fontPx * NATIVE_GLYPH_HORIZONTAL_CLIP_PAD_EM
    val slowGlowClipPad = nativeSlowGlowClipPad(fontPx)
    val slowGlowClipPadX = slowGlowClipPad + glyphClipPad
    segments.forEachIndexed { idx, segment ->
        val bounds = nativeSegmentClipBounds(layout, segments, idx, fadeWidth, glyphClipPad)
        segClipLeft[idx] = bounds.left
        segClipRight[idx] = bounds.right
        segGlowClipLeft[idx] = segment.left - slowGlowClipPadX
        segGlowClipRight[idx] = segment.right + slowGlowClipPadX
    }
    val text = layout.layoutInput.text.text
    val glyphBoxLeft = FloatArray(text.length) { Float.NaN }
    val glyphBoxRight = FloatArray(text.length) { Float.NaN }
    segments.forEach { segment ->
        for (i in segment.startChar until segment.endChar) {
            if (i !in text.indices) continue
            val ch = text[i]
            if (ch.isWhitespace() || ch.isISOControl()) continue
            val rawBox = layout.getBoundingBox(i)
            if (rawBox.right <= rawBox.left) continue
            val box = nativeSlowGlyphVisualBox(
                layout = layout,
                segment = segment,
                index = i,
                fallbackLeft = rawBox.left,
                fallbackRight = rawBox.right,
            ) ?: continue
            glyphBoxLeft[i] = box.left
            glyphBoxRight[i] = box.right
        }
    }
    val rowTopArr = FloatArray(layout.lineCount) { layout.getLineTop(it) }
    val rowBottomArr = FloatArray(layout.lineCount) { layout.getLineBottom(it) }
    // 每行上下沿都只在“不会越过相邻 line box”的范围内补余量。上一版无条件外扩
    // 会让相邻行车道重叠，普通字母也被别的 syllable 重绘染出横向裂缝。
    val vClipPad = fontPx * NATIVE_GLYPH_VERTICAL_CLIP_PAD_EM
    val rowClipTopArr = FloatArray(layout.lineCount) { line ->
        val expanded = rowTopArr[line] - vClipPad
        if (line == 0) {
            expanded
        } else {
            expanded.coerceAtLeast(rowBottomArr[line - 1])
        }
    }
    val rowClipBottomArr = FloatArray(layout.lineCount) { line ->
        val expanded = rowBottomArr[line] + vClipPad
        if (line + 1 >= layout.lineCount) {
            expanded
        } else {
            expanded.coerceAtMost(rowTopArr[line + 1])
        }
    }
    // 慢词光晕只绘制当前字形，不会把相邻行的整段 paragraph 染进来，因此它的纵向 clip
    // 不需要像普通 sweep slice 那样夹在相邻 line box 中间。给 blur 自然衰减的空间，
    // 否则第二行及之后的 glow 会被行边界切出水平硬线。
    val rowGlowClipTopArr = FloatArray(layout.lineCount) { line ->
        rowTopArr[line] - slowGlowClipPad
    }
    val rowGlowClipBottomArr = FloatArray(layout.lineCount) { line ->
        rowBottomArr[line] + slowGlowClipPad
    }
    return NativeTimedLyricPlan(
        layout = layout,
        segments = segments,
        fontPx = fontPx,
        fadeWidth = fadeWidth,
        rowTop = rowTopArr,
        rowBottom = rowBottomArr,
        rowClipTop = rowClipTopArr,
        rowClipBottom = rowClipBottomArr,
        rowGlowClipTop = rowGlowClipTopArr,
        rowGlowClipBottom = rowGlowClipBottomArr,
        segClipLeft = segClipLeft,
        segClipRight = segClipRight,
        segGlowClipLeft = segGlowClipLeft,
        segGlowClipRight = segGlowClipRight,
        segSlow = FloatArray(segments.size) { idx ->
            nativeSlowWordAmount(
                displayToken = segments[idx].timing,
                sourceToken = segments[idx].sourceTiming,
            )
        },
        segNeedsDescenderSafe = BooleanArray(segments.size) { idx ->
            nativeSegmentNeedsDescenderSafeDraw(text = text, segment = segments[idx])
        },
        glyphBoxLeft = glyphBoxLeft,
        glyphBoxRight = glyphBoxRight,
        glyphStyle = nativeTimedGlyphTextStyle(style),
        glyphLayouts = arrayOfNulls(text.length),
        segmentLayouts = arrayOfNulls(segments.size),
        segmentInkLeft = FloatArray(segments.size) { Float.NaN },
    )
}

private fun nativeTimedGlyphTextStyle(style: TextStyle): TextStyle {
    return style.copy(
        textAlign = TextAlign.Start,
        // Segment/glyph layouts are only used as offscreen paint carriers and
        // are baseline-aligned back to the real paragraph. Give those carriers
        // a taller internal line box so descenders survive Paragraph's own
        // bounds before any outer clip is involved.
        lineHeight = style.fontSize * NATIVE_TIMED_GLYPH_LINE_HEIGHT_EM,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )
}

private fun nativeSlowGlowClipPad(fontPx: Float): Float {
    return fontPx / NATIVE_APPLE_WEB_LINE_FONT_PX *
        NATIVE_SLOW_SHADOW_PEAK_WEB_PX *
        NATIVE_SLOW_SHADOW_CLIP_RADIUS_MULTIPLIER
}

private fun DrawScope.drawNativeTimedLyric(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    isPast: Boolean,
    isBackgroundVocal: Boolean,
    motionScale: Float,
    lineFocusProgress: Float,
    effectsEnabled: Boolean,
) {
    val layout = plan.layout
    val segments = plan.segments
    if (segments.isEmpty()) {
        drawText(layout, color = if (isPast) nativeSolidLineColor(fg) else fgUnsung)
        return
    }
    // Apple Web current timed gradient:
    // rgba(gradient-color, .85) -> rgba(gradient-color, .5). 这里不再沿用
    // 全局 fg/fgUnsung 的封面色策略，避免扫色边界在 1.0 -> 0.4x 之间显得硬跳。
    val focusProgress = lineFocusProgress.coerceIn(0f, 1f)
    val activeUnsungTarget = if (isPast) nativeSolidLineColor(fg) else nativeTimedGradientUnsungColor(fg)
    val activeUnsung = lerp(fgUnsung, activeUnsungTarget, focusProgress)
    val activeSung = lerp(fgUnsung, nativeTimedGradientSungColor(fg), focusProgress)
    drawNativeSegmentSweepText(
        plan = plan,
        glyphMeasurer = glyphMeasurer,
        fg = activeSung,
        activeUnsung = activeUnsung,
        isBackgroundVocal = isBackgroundVocal,
        positionMs = positionMs,
        motionScale = motionScale,
        effectsEnabled = effectsEnabled,
    )
}

private fun DrawScope.drawNativeSegmentSweepText(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    fg: Color,
    activeUnsung: Color,
    isBackgroundVocal: Boolean,
    positionMs: Long,
    motionScale: Float,
    effectsEnabled: Boolean,
) {
    val layout = plan.layout
    val segments = plan.segments
    val fontPx = plan.fontPx
    val liftEm = if (isBackgroundVocal) NATIVE_BG_WORD_LIFT_EM else NATIVE_MAIN_WORD_LIFT_EM
    val mScale = motionScale.coerceIn(0f, 1f)
    val sungLiftPx = -fontPx * liftEm * mScale

    var staticBatchActive = false
    var staticBatchKind = 0
    var staticBatchLine = 0
    var staticBatchClipLeft = 0f
    var staticBatchClipTop = 0f
    var staticBatchClipRight = 0f
    var staticBatchClipBottom = 0f
    var staticBatchLiftPx = 0f
    var staticBatchColor = Color.Transparent

    fun flushStaticBatch() {
        if (!staticBatchActive) return
        val liftPx = staticBatchLiftPx
        nativeClipTextRect(
            staticBatchClipLeft,
            staticBatchClipTop + liftPx,
            staticBatchClipRight,
            staticBatchClipBottom + liftPx,
        ) {
            drawText(
                layout,
                color = staticBatchColor,
                topLeft = Offset(0f, liftPx),
            )
        }
        staticBatchActive = false
    }

    fun appendStaticBatch(
        kind: Int,
        line: Int,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
        liftPx: Float,
        color: Color,
    ) {
        if (clipRight <= clipLeft || clipBottom <= clipTop) return
        if (
            staticBatchActive &&
            staticBatchKind == kind &&
            staticBatchLine == line &&
            kotlin.math.abs(staticBatchLiftPx - liftPx) <= 0.01f &&
            staticBatchColor == color
        ) {
            staticBatchClipLeft = minOf(staticBatchClipLeft, clipLeft)
            staticBatchClipTop = minOf(staticBatchClipTop, clipTop)
            staticBatchClipRight = maxOf(staticBatchClipRight, clipRight)
            staticBatchClipBottom = maxOf(staticBatchClipBottom, clipBottom)
        } else {
            flushStaticBatch()
            staticBatchActive = true
            staticBatchKind = kind
            staticBatchLine = line
            staticBatchClipLeft = clipLeft
            staticBatchClipTop = clipTop
            staticBatchClipRight = clipRight
            staticBatchClipBottom = clipBottom
            staticBatchLiftPx = liftPx
            staticBatchColor = color
        }
    }

    segments.forEachIndexed { index, segment ->
        val slowAmount = plan.segSlow[index]
        // Apple Web 在当前行 manageAnimations() 时会先把所有 `.emphasis`
        // 拆成 `.letter`，再给每个 letter 挂 keyframe；未到 data-delay 前只是
        // letterState 的起始值（scale=1/y=0/gradient=-20），不会先以整词路径绘制。
        // Android 也让慢词在整个 current/timed 生命周期都走逐字母路径，避免
        // token.start 那一帧从整词 Text 切到逐字 Text 造成轻微跳动。
        if (effectsEnabled && slowAmount > 0f) {
            flushStaticBatch()
            drawNativeSlowSegmentText(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                segmentIndex = index,
                slowAmount = slowAmount,
                motionPositionMs = positionMs,
                fg = fg,
                activeUnsung = activeUnsung,
                motionScale = motionScale,
            )
            return@forEachIndexed
        }
        val progress = nativeSegmentFillProgress(segment, positionMs)
        val liftT = nativeSegmentLiftProgress(segment, positionMs)
        val sweepProgress = progress.coerceIn(0f, 1f)
        val segmentWidth = (segment.right - segment.left).coerceAtLeast(1f)
        val solidX: Float
        val rampEndX: Float
        val fadeEndX: Float
        if (sweepProgress <= 0f) {
            solidX = Float.NEGATIVE_INFINITY
            rampEndX = Float.NEGATIVE_INFINITY
            fadeEndX = Float.NEGATIVE_INFINITY
        } else {
            solidX = segment.left + segmentWidth *
                (sweepProgress * NATIVE_APPLE_SWEEP_TRAVEL_RATIO - NATIVE_APPLE_SWEEP_LEAD_RATIO)
            rampEndX = segment.left + segmentWidth * (sweepProgress * NATIVE_APPLE_SWEEP_TRAVEL_RATIO)
            fadeEndX = rampEndX.coerceAtMost(segment.right)
        }
        // Apple Web 是每个 word/syllable 自己的渐变时间轴：下一词开唱不会改写前一词的
        // 颜色或位移状态。这里只按本段 progress/lift 分类，避免跨词前沿导致抢跑抖动。
        val kind = when {
            progress <= NATIVE_SWEEP_PROGRESS_EPS && liftT <= 0.001f -> 1
            progress >= 0.999f && liftT >= 0.999f -> 2
            else -> 0
        }
        // Apple Web paints each `.syllable` as an inline-block, but all blocks
        // still live in the same browser line box. Android's isolated per-word
        // TextLayout can land on slightly different font metrics, which shows
        // up as horizontal cracks on multi-line lyrics during lift/scale. Keep
        // one shared paragraph layout for glyph pixels, and only clip/move the
        // current syllable's visual lane.
        val liftPx = if (kind == 2) sungLiftPx else -fontPx * liftEm * liftT * mScale
        // 上/下沿都用补了悬伸余量的 clip band：否则多行普通 syllable 上浮或 descender
        // 越界时会被 line box 边缘切掉，看起来像文字顶部/底部裂开。
        val lineClipTop = plan.rowClipTop[segment.line]
        val lineClipBottom = plan.rowClipBottom[segment.line]
        if (plan.segNeedsDescenderSafe.getOrElse(index) { false }) {
            flushStaticBatch()
            drawNativeDescenderSafeSegmentText(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                segmentIndex = index,
                contentLeft = segment.left,
                contentRight = segment.right,
                solidX = solidX,
                rampEndX = rampEndX,
                fadeEndX = fadeEndX,
                fg = fg,
                activeUnsung = activeUnsung,
                topLeft = Offset(0f, liftPx),
            )
        } else if (kind == 1 || kind == 2) {
            appendStaticBatch(
                kind = kind,
                line = segment.line,
                clipLeft = plan.segClipLeft[index],
                clipTop = lineClipTop,
                clipRight = plan.segClipRight[index],
                clipBottom = lineClipBottom,
                liftPx = liftPx,
                color = if (kind == 2) fg else activeUnsung,
            )
        } else {
            flushStaticBatch()
            drawNativeSweepTextSlice(
                plan = plan,
                contentLeft = segment.left,
                contentRight = segment.right,
                // 段裁切带已在排版期烘焙了悬伸余量并夹到词间留白中点；此处直接使用，
                // 不再无条件加 pad，避免相邻段裁切带重叠导致上浮错相位时的边界重影。
                clipLeft = plan.segClipLeft[index],
                clipTop = lineClipTop,
                clipRight = plan.segClipRight[index],
                clipBottom = lineClipBottom,
                solidX = solidX,
                rampEndX = rampEndX,
                fadeEndX = fadeEndX,
                fg = fg,
                activeUnsung = activeUnsung,
                topLeft = Offset(0f, liftPx),
            )
        }
    }
    flushStaticBatch()
}

private fun nativeSegmentNeedsDescenderSafeDraw(
    text: String,
    segment: NativeLyricSegment,
): Boolean {
    if (segment.timing.text.any(::nativeIsDescenderSafeChar) ||
        segment.sourceTiming.text.any(::nativeIsDescenderSafeChar)
    ) {
        return true
    }
    for (index in segment.startChar until segment.endChar) {
        if (index !in text.indices) continue
        if (nativeIsDescenderSafeChar(text[index])) return true
    }
    return false
}

private fun nativeIsDescenderSafeChar(ch: Char): Boolean {
    return when (ch.lowercaseChar()) {
        'g', 'j', 'y', 'p', 'q' -> true
        else -> false
    }
}

// 含 g/j/y/p/q 的词：整词改走「独立词排版」而非整段裁切。独立排版行高 1.70em、
// overflow=Visible、不套任何垂直裁切带，字形下伸（descender）永远落在排版自身 bounds 内，
// 绝不会被行盒/相邻行裁切线切到——这是“底部裂开”的根治点。
// 关键改进：把与 slice 路径完全一致的横向扫色渐变（solidX/rampEndX/fadeEndX）套到这层独立
// 排版上，而不是整词单色。这样 g/y/j/p/q 既完整、又保留逐字左→右扫光，与相邻普通词同相。
// 渐变坐标换算：独立排版绘制原点在 originX，段坐标 X 对应排版本地坐标 X - originX。
private fun DrawScope.drawNativeDescenderSafeSegmentText(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    segmentIndex: Int,
    contentLeft: Float,
    contentRight: Float,
    solidX: Float,
    rampEndX: Float,
    fadeEndX: Float,
    fg: Color,
    activeUnsung: Color,
    topLeft: Offset,
) {
    if (contentRight <= contentLeft) return
    val segment = plan.segments.getOrNull(segmentIndex) ?: return
    val segmentLayout = nativeSegmentTextLayout(plan, glyphMeasurer, segmentIndex) ?: return
    val inkLeft = plan.segmentInkLeft.getOrNull(segmentIndex)?.takeIf { it.isFinite() } ?: 0f
    val originX = segment.left - inkLeft + topLeft.x
    val y = plan.layout.getLineBaseline(segment.line) -
        segmentLayout.getLineBaseline(0) +
        topLeft.y
    val segmentTopLeft = Offset(originX, y)
    when {
        solidX >= contentRight -> drawText(segmentLayout, color = fg, topLeft = segmentTopLeft)
        fadeEndX <= contentLeft -> drawText(segmentLayout, color = activeUnsung, topLeft = segmentTopLeft)
        rampEndX <= solidX + 0.5f -> {
            val edge = solidX.coerceIn(contentLeft, contentRight)
            val edgeBrush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = edge - originX,
                endX = edge - originX + 1f,
            )
            drawText(segmentLayout, brush = edgeBrush, topLeft = segmentTopLeft, alpha = 1f)
        }
        else -> {
            val brush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = solidX - originX,
                endX = rampEndX - originX,
            )
            drawText(segmentLayout, brush = brush, topLeft = segmentTopLeft, alpha = 1f)
        }
    }
}

// 段级切片绘制：颜色坐标来自当前 word/syllable 自己的 Apple Web 渐变带。
// solidX 是已唱色 stop，fadeEndX 是未唱色 stop；带外两侧用 Clamp 落到纯已唱/纯未唱。
private fun DrawScope.drawNativeSweepTextSlice(
    plan: NativeTimedLyricPlan,
    contentLeft: Float,
    contentRight: Float,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
    solidX: Float,
    rampEndX: Float,
    fadeEndX: Float,
    fg: Color,
    activeUnsung: Color,
    topLeft: Offset,
) {
    if (contentRight <= contentLeft || clipRight <= clipLeft || clipBottom <= clipTop) return
    val drawTopLeft = topLeft
    // 关键：垂直裁切带必须跟随上浮位移一起平移。文本样式是 Trim.None+Center，行内 leading≈0，
    // 上浮 liftPx 时字形顶部本会越过固定的 rowTop[line] 行边界被裁掉（多行时第二行起的行间边界
    // 尤为明显，y/j/g 等会“裂开”）。裁切带与内容同步平移 drawTopLeft.y 后，字形在带内相对位置
    // 不变即不再被切；又因裁切带与内容是刚性平移，相邻行像素仍落在带外，不会产生重影。
    nativeClipTextRect(clipLeft, clipTop + drawTopLeft.y, clipRight, clipBottom + drawTopLeft.y) {
        when {
            solidX >= contentRight -> drawText(plan.layout, color = fg, topLeft = drawTopLeft)
            fadeEndX <= contentLeft -> drawText(plan.layout, color = activeUnsung, topLeft = drawTopLeft)
            rampEndX <= solidX + 0.5f -> {
                val edge = solidX.coerceIn(contentLeft, contentRight)
                val edgeBrush = nativeSweepTransitionBrush(
                    fg = fg,
                    activeUnsung = activeUnsung,
                    startX = edge - drawTopLeft.x,
                    endX = edge - drawTopLeft.x + 1f,
                )
                drawText(plan.layout, brush = edgeBrush, topLeft = drawTopLeft, alpha = 1f)
            }
            else -> {
                val brush = nativeSweepTransitionBrush(
                    fg = fg,
                    activeUnsung = activeUnsung,
                    startX = solidX - drawTopLeft.x,
                    endX = rampEndX - drawTopLeft.x,
                )
                // 单次渐变填充：每个像素只画一次，颜色 = Apple Web 同一条两端渐变
                // 在该 x 的取值，不叠加额外颜色层。Clamp 让
                // 带外两侧自然落在纯亮/纯未唱。
                // alpha 必须显式传 1f：本文本布局的基色是 Transparent（静态路径不可见），
                // drawText(brush) 默认 alpha=NaN 会沿用画笔现有 alpha=0 —— 渐变层因此
                // 从第一版起整层隐形（“分界线”的真正物理根源，模拟器逐像素实锤）。
                drawText(plan.layout, brush = brush, topLeft = drawTopLeft, alpha = 1f)
            }
        }
    }
}

private fun nativeSegmentTextLayout(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    segmentIndex: Int,
): TextLayoutResult? {
    if (segmentIndex !in plan.segmentLayouts.indices) return null
    plan.segmentLayouts[segmentIndex]?.let { return it }
    val segment = plan.segments.getOrNull(segmentIndex) ?: return null
    val text = plan.layout.layoutInput.text.text
    if (segment.startChar !in 0..text.length || segment.endChar !in 0..text.length || segment.startChar >= segment.endChar) {
        return null
    }
    val spanText = text.substring(segment.startChar, segment.endChar)
    if (spanText.isEmpty()) return null
    return runCatching {
        glyphMeasurer.measure(
            text = AnnotatedString(spanText),
            style = plan.glyphStyle,
            softWrap = false,
            overflow = TextOverflow.Visible,
            maxLines = 1,
        )
    }.getOrNull()?.also { spanLayout ->
        plan.segmentLayouts[segmentIndex] = spanLayout
        plan.segmentInkLeft[segmentIndex] = nativeLayoutInkLeft(spanLayout, spanText)
    }
}

private fun nativeLayoutInkLeft(
    layout: TextLayoutResult,
    text: String,
): Float {
    var left = Float.POSITIVE_INFINITY
    for (index in text.indices) {
        if (text[index].isWhitespace() || text[index].isISOControl()) continue
        val box = layout.getBoundingBox(index)
        if (box.right > box.left) {
            left = minOf(left, box.left)
        }
    }
    return if (left.isFinite()) left else 0f
}

// Apple Web sweep: one linear-gradient between active and inactive alpha stops.
private fun nativeSweepTransitionBrush(
    fg: Color,
    activeUnsung: Color,
    startX: Float,
    endX: Float,
): Brush {
    return Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to fg,
            1f to activeUnsung,
        ),
        startX = startX,
        endX = endX.coerceAtLeast(startX + 1f),
        tileMode = TileMode.Clamp,
    )
}

@Composable
private fun NativeInterludeDots(
    interlude: NativeLyricSlot.Interlude,
    clockState: State<Float>,
    offsetMs: Long,
    color: Color,
    dotSize: Dp,
    dotGap: Dp,
    width: Dp,
    height: Dp,
) {
    Canvas(modifier = Modifier.size(width = width, height = height)) {
        val progress = nativeInterludeProgress(
            interlude = interlude,
            positionMs = nativeRenderPositionMs(clockState.value) + offsetMs,
        )
        if (progress.alpha <= 0.001f) return@Canvas
        val dot = dotSize.toPx()
        val gap = dotGap.toPx()
        val radius = dot / 2f
        val groupWidth = dot * NATIVE_INTERLUDE_DOT_COUNT + gap * (NATIVE_INTERLUDE_DOT_COUNT - 1f)
        val startX = ((size.width - groupWidth) / 2f).coerceAtLeast(0f) + radius
        val centerY = size.height / 2f
        val centers = floatArrayOf(
            startX,
            startX + dot + gap,
            startX + (dot + gap) * 2f,
        )
        centers.forEachIndexed { idx, centerX ->
            drawCircle(
                color = color.copy(alpha = progress.dotAlphas[idx] * progress.alpha),
                radius = radius,
                center = Offset(centerX, centerY),
            )
        }
    }
}

private data class NativeInterludeProgress(
    val alpha: Float,
    val dotAlphas: FloatArray,
)

private fun nativeInterludeProgress(
    interlude: NativeLyricSlot.Interlude,
    positionMs: Long,
): NativeInterludeProgress {
    val duration = (interlude.endMs - interlude.startMs).coerceAtLeast(1L).toFloat()
    val current = (positionMs - interlude.startMs).toFloat().coerceIn(0f, duration)
    val remaining = duration - current
    val alpha = if (remaining <= NATIVE_INTERLUDE_ENDING_FADE_MS) {
        (remaining / NATIVE_INTERLUDE_ENDING_FADE_MS).coerceIn(0f, 1f)
    } else {
        1f
    }
    val dotStepMs = (duration / NATIVE_INTERLUDE_DOT_COUNT).coerceAtLeast(1f)
    val dot0 = nativeAppleInterludeDotAlpha(current, dotStepMs, 0)
    val dot1 = nativeAppleInterludeDotAlpha(current, dotStepMs, 1)
    val dot2 = nativeAppleInterludeDotAlpha(current, dotStepMs, 2)
    return NativeInterludeProgress(
        alpha = alpha,
        dotAlphas = floatArrayOf(dot0.coerceIn(0f, 1f), dot1.coerceIn(0f, 1f), dot2.coerceIn(0f, 1f)),
    )
}

private fun nativeAppleInterludeDotAlpha(
    current: Float,
    dotStepMs: Float,
    dotIndex: Int,
): Float {
    val threshold = dotStepMs * dotIndex
    // Apple Web toggles `.dot--current`, sets an inline
    // `transition-duration:${(end-begin)/3}ms`, and only declares
    // `transition-property:opacity`, so the opacity ramp uses CSS's default
    // `ease` timing-function.
    val t = NATIVE_CSS_DEFAULT_EASE.transform(((current - threshold) / dotStepMs).coerceIn(0f, 1f))
    return NATIVE_INTERLUDE_DOT_INACTIVE_ALPHA +
        (1f - NATIVE_INTERLUDE_DOT_INACTIVE_ALPHA) * t
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
    glyphPad: Float,
): NativeSegmentBounds {
    val segment = segments[index]
    val prev = segments.getOrNull(index - 1)?.takeIf { it.line == segment.line }
    val next = segments.getOrNull(index + 1)?.takeIf { it.line == segment.line }
    val lineLeft = layout.getLineLeft(segment.line)
    val lineRight = layout.getLineRight(segment.line)
    // 词间留白处：本段允许向留白内吃 glyphPad 的悬伸/抗锯齿余量，但裁切线绝不越过
    // 留白中点去碰相邻词的墨迹。旧实现把 glyphPad 无脑加在中点两侧，使相邻两段的
    // 裁切带相互重叠 2×pad；当两词上浮相位不同（一个已浮起、一个还在原位）时，
    // 重叠区会把同一字形按两种 y 各画一遍 —— 这就是上浮时“文字左右边界被裁掉一点/
    // 出现重影、浮完才正常”的根因。改为夹到中点：相邻裁切带正好在留白中线相接、
    // 互不重叠，既保留单侧悬伸余量又彻底消除错相位重影。开放边（行首/行尾）仍给
    // 充足的 fadeWidth。
    val left = when {
        prev == null -> lineLeft - fadeWidth
        prev.right < segment.left -> {
            val mid = (prev.right + segment.left) / 2f
            (segment.left - glyphPad).coerceAtLeast(mid)
        }
        else -> segment.left
    }
    val right = when {
        next == null -> lineRight + fadeWidth
        segment.right < next.left -> {
            val mid = (segment.right + next.left) / 2f
            (segment.right + glyphPad).coerceAtMost(mid)
        }
        else -> segment.right
    }
    return NativeSegmentBounds(
        left = left.coerceAtMost(right),
        right = right.coerceAtLeast(left),
    )
}

private fun nativeWordFadeWidth(layout: TextLayoutResult, fontPx: Float): Float {
    val lineHeightPx = (0 until layout.lineCount).maxOfOrNull { line ->
        (layout.getLineBottom(line) - layout.getLineTop(line)).toDouble()
    }?.toFloat()
    return ((lineHeightPx ?: fontPx).coerceAtLeast(fontPx) * NATIVE_WORD_FADE_WIDTH_RATIO)
        .coerceAtLeast(1f)
}

private fun DrawScope.drawNativeSlowSegmentText(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    segmentIndex: Int,
    slowAmount: Float,
    motionPositionMs: Long,
    fg: Color,
    activeUnsung: Color,
    motionScale: Float,
) {
    // Apple Web emphasis 逐字母 keyframe：
    // 1) start -> +500ms：scale 1->1.05, y 0->-2.05px, gradient -20->90, shadow 0->10px/.4
    // 2) +500ms -> +1000ms：scale 1.05->1, y -2.05->-2px, gradient 90->100, shadow 10->4px/0
    // createSyllable 会先 replace(/[()]/g, "") 再拆 .letter；括号不参与 letter 时间轴。
    val layout = plan.layout
    val segment = plan.segments[segmentIndex]
    val fontPx = plan.fontPx
    val lineTop = plan.rowTop[segment.line]
    val lineBottom = plan.rowBottom[segment.line]
    val mScale = motionScale.coerceIn(0f, 1f)

    val text = layout.layoutInput.text.text
    val clipTop = plan.rowGlowClipTop[segment.line]
    val clipBottom = plan.rowGlowClipBottom[segment.line]
    val glyphCenterY = (lineTop + lineBottom) * 0.5f
    val glowClipLeft = plan.segGlowClipLeft[segmentIndex]
    val glowClipRight = plan.segGlowClipRight[segmentIndex]

    val segStartMs = segment.slowStartMs
    val segDurationMs = (segment.slowEndMs - segStartMs)
        .coerceAtLeast(1L).toFloat()
    val empGain = (slowAmount * mScale).coerceIn(0f, 1f)
    val firstPhaseMs = NATIVE_SLOW_LETTER_FIRST_PHASE_MS.toFloat()
    val totalPhaseMs = NATIVE_SLOW_LETTER_TOTAL_MS.toFloat()
    val cssPxToLocal = fontPx / NATIVE_APPLE_WEB_LINE_FONT_PX
    val letterStepMs = segDurationMs / segment.letterUnits.coerceAtLeast(1).toFloat()

    var glyphOrdinal = segment.letterOrdinalStart
    for (i in segment.startChar until segment.endChar) {
        if (i >= plan.glyphBoxLeft.size) break
        if (nativeAppleStripsSyllableChar(text[i])) continue
        val boxLeft = plan.glyphBoxLeft[i]
        if (boxLeft.isNaN()) continue
        val boxRight = plan.glyphBoxRight[i]
        val letterStartMs = segStartMs + letterStepMs * glyphOrdinal
        glyphOrdinal++
        val elapsedMs = motionPositionMs.toFloat() - letterStartMs
        val scaleValue: Float
        val translateYPx: Float
        val gradientPercent: Float
        val shadowBlurPx: Float
        val shadowOpacity: Float
        if (elapsedMs <= 0f) {
            scaleValue = 1f
            translateYPx = 0f
            gradientPercent = -20f
            shadowBlurPx = 0f
            shadowOpacity = 0f
        } else if (elapsedMs < firstPhaseMs) {
            val t = (elapsedMs / firstPhaseMs).coerceIn(0f, 1f)
            scaleValue = 1f + (NATIVE_SLOW_SCALE_PEAK - 1f) * t * empGain
            translateYPx = -NATIVE_SLOW_LIFT_PEAK_WEB_PX * cssPxToLocal * t * empGain
            gradientPercent = (-20f + (90f + 20f) * t).coerceIn(-20f, 100f)
            shadowBlurPx = NATIVE_SLOW_SHADOW_PEAK_WEB_PX * cssPxToLocal * t
            shadowOpacity = (NATIVE_SLOW_SHADOW_PEAK_ALPHA * t * empGain)
                .coerceIn(0f, NATIVE_SLOW_SHADOW_PEAK_ALPHA)
        } else if (elapsedMs < totalPhaseMs) {
            val t = ((elapsedMs - firstPhaseMs) / (totalPhaseMs - firstPhaseMs)).coerceIn(0f, 1f)
            scaleValue = 1f + (NATIVE_SLOW_SCALE_PEAK - 1f) * (1f - t) * empGain
            translateYPx = -(
                NATIVE_SLOW_LIFT_PEAK_WEB_PX +
                    (NATIVE_SLOW_LIFT_SETTLE_WEB_PX - NATIVE_SLOW_LIFT_PEAK_WEB_PX) * t
                ) * cssPxToLocal * empGain
            gradientPercent = (90f + (100f - 90f) * t).coerceIn(-20f, 100f)
            shadowBlurPx = (NATIVE_SLOW_SHADOW_PEAK_WEB_PX +
                (NATIVE_SLOW_SHADOW_SETTLE_WEB_PX - NATIVE_SLOW_SHADOW_PEAK_WEB_PX) * t) * cssPxToLocal
            shadowOpacity = (NATIVE_SLOW_SHADOW_PEAK_ALPHA * (1f - t) * empGain)
                .coerceIn(0f, NATIVE_SLOW_SHADOW_PEAK_ALPHA)
        } else {
            scaleValue = 1f
            translateYPx = -NATIVE_SLOW_LIFT_SETTLE_WEB_PX * cssPxToLocal * empGain
            gradientPercent = 100f
            shadowBlurPx = NATIVE_SLOW_SHADOW_SETTLE_WEB_PX * cssPxToLocal
            shadowOpacity = 0f
        }
        val glyphCenter = (boxLeft + boxRight) * 0.5f
        val glyphWidth = (boxRight - boxLeft).coerceAtLeast(1f)
        val solidX = boxLeft + glyphWidth * (gradientPercent / 100f)
        val rampEndX = boxLeft + glyphWidth *
            ((gradientPercent + NATIVE_APPLE_SWEEP_LEAD_RATIO * 100f) / 100f)
        val fadeEndX = rampEndX.coerceAtMost(boxRight)
        val glyphLayout = nativeSlowGlyphLayout(
            plan = plan,
            glyphMeasurer = glyphMeasurer,
            text = text,
            index = i,
        ) ?: continue
        val glyphBox = glyphLayout.getBoundingBox(0)

        fun DrawScope.drawGlyph(lineTopLeft: Offset) {
            val glyphTopLeft = Offset(
                x = boxLeft - glyphBox.left + lineTopLeft.x,
                // 用 baseline 对齐而非 line-top 对齐：慢词逐字母走“单字符独立布局”，它永远按
                // 首行度量（首行通常 trim 掉顶部 leading）；而整段布局第 2、3 行的 line box 含
                // 完整顶部 leading。按 getLineTop 对齐时首行恰好对得上，但从第二行起单字符字形
                // 贴 line box 顶绘制、比同行快词整体偏上（“一行没事、两三行从第二行起错位”的根因）。
                // baseline 不受 leading/trim 影响，对齐到整段对应行 baseline 即与快词同底；
                // 这里只改 y 参考点，不动单字符布局本身。
                y = layout.getLineBaseline(segment.line) - glyphLayout.getLineBaseline(0) + lineTopLeft.y,
            )
            if (shadowOpacity > 0.004f && shadowBlurPx > 0.2f) {
                drawNativeSlowGlyphGlow(
                    glyphLayout = glyphLayout,
                    topLeft = glyphTopLeft,
                    blurPx = shadowBlurPx,
                    opacity = shadowOpacity,
                    clipTop = clipTop,
                    clipBottom = clipBottom,
                    glowLeft = glowClipLeft,
                    glowRight = glowClipRight,
                )
            }
            drawNativeSlowGlyphSweepText(
                glyphLayout = glyphLayout,
                glyphLeft = boxLeft,
                glyphRight = boxRight,
                topLeft = glyphTopLeft,
                solidX = solidX,
                rampEndX = rampEndX,
                fadeEndX = fadeEndX,
                fg = fg,
                activeUnsung = activeUnsung,
            )
        }

        if (scaleValue <= 1.0005f) {
            drawGlyph(
                lineTopLeft = Offset(0f, translateYPx),
            )
        } else {
            translate(left = 0f, top = translateYPx) {
                scale(
                    scaleX = scaleValue,
                    scaleY = scaleValue,
                    pivot = Offset(glyphCenter, glyphCenterY),
                ) {
                    drawGlyph(
                        lineTopLeft = Offset.Zero,
                    )
                }
            }
        }
    }

}

// 慢词逐字母：每个字母本就是「独立单字排版」（overflow=Visible、行高 1.70em）按 baseline
// 对齐绘制，sweep 文本不套垂直裁切，descender 天然完整。这里对所有字母（含 g/j/y/p/q）
// 一律走同一条横向扫色渐变，不再对下伸字母单列单色分支——保证下伸字母同样有逐字扫光。
private fun DrawScope.drawNativeSlowGlyphSweepText(
    glyphLayout: TextLayoutResult,
    glyphLeft: Float,
    glyphRight: Float,
    topLeft: Offset,
    solidX: Float,
    rampEndX: Float,
    fadeEndX: Float,
    fg: Color,
    activeUnsung: Color,
) {
    if (glyphRight <= glyphLeft) return
    when {
        solidX >= glyphRight -> drawText(glyphLayout, color = fg, topLeft = topLeft)
        fadeEndX <= glyphLeft -> drawText(glyphLayout, color = activeUnsung, topLeft = topLeft)
        else -> {
            val brush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = solidX - topLeft.x,
                endX = rampEndX.coerceAtLeast(solidX + 1f) - topLeft.x,
            )
            drawText(glyphLayout, brush = brush, topLeft = topLeft, alpha = 1f)
        }
    }
}

// “自发光”双层光晕：内圈窄而亮（贴着字形边缘的灼亮感），外圈宽而淡（快速衰减的
// 辉光）。单层大半径阴影只有均匀的雾感、没有亮核，看起来就是字后垫了团模糊背景。
// 文字本体由 sweep 路径绘制，这里只铺光：填充用近零 alpha 让 shadowLayer 单独
// 成像，光形完全来自字形轮廓，所以不会污染相邻字符的笔画。Apple 的
// text-shadow 挂在 `.letter` 上，不会给每个字母外面再套一个贴边硬裁剪；Android
// 也使用词级 glow clip，避免慢词上浮时出现竖向断边。clip 是 paragraph 行坐标下
// 的安全雾化带，不能再叠加单字 baseline 的 topLeft.y；否则第二行起会把 glow 框
// 整体下推，雾背景上下边缘被切成水平硬线。
private fun DrawScope.drawNativeSlowGlyphGlow(
    glyphLayout: TextLayoutResult,
    topLeft: Offset,
    blurPx: Float,
    opacity: Float,
    clipTop: Float,
    clipBottom: Float,
    glowLeft: Float,
    glowRight: Float,
) {
    if (opacity <= 0.004f || blurPx <= 0.2f) return
    if (glowRight <= glowLeft || clipBottom <= clipTop) return
    val carrier = Color.White.copy(alpha = NATIVE_SLOW_GLOW_FILL_ALPHA)
    nativeClipTextRect(glowLeft, clipTop, glowRight, clipBottom) {
        drawText(
            glyphLayout,
            color = carrier,
            topLeft = topLeft,
            shadow = Shadow(
                color = Color.White.copy(alpha = opacity.coerceIn(0f, 1f)),
                offset = Offset.Zero,
                blurRadius = blurPx,
            ),
        )
    }
}

private fun nativeSlowGlyphLayout(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    text: String,
    index: Int,
): TextLayoutResult? {
    if (index !in plan.glyphLayouts.indices || index !in text.indices) return null
    plan.glyphLayouts[index]?.let { return it }
    return runCatching {
        glyphMeasurer.measure(
            text = AnnotatedString(text[index].toString()),
            style = plan.glyphStyle,
            softWrap = false,
            overflow = TextOverflow.Visible,
            maxLines = 1,
        )
    }.getOrNull()?.also { plan.glyphLayouts[index] = it }
}

private inline fun DrawScope.nativeClipTextRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    block: DrawScope.() -> Unit,
) {
    val safeLeft = kotlin.math.floor(left.toDouble()).toFloat()
    val safeTop = kotlin.math.floor(top.toDouble()).toFloat()
    val safeRight = kotlin.math.ceil(right.toDouble()).toFloat()
    val safeBottom = kotlin.math.ceil(bottom.toDouble()).toFloat()
    if (safeRight <= safeLeft || safeBottom <= safeTop) return
    clipRect(safeLeft, safeTop, safeRight, safeBottom, block = block)
}

private fun nativeSmoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun nativeRowScale(positionFocus: Float): Float {
    return 1f + (NATIVE_CURRENT_LINE_SCALE - 1f) *
        positionFocus.coerceIn(0f, NATIVE_LINE_TRANSFORM_PROGRESS_MAX)
}

private data class NativeSlowGlyphVisualBox(
    val left: Float,
    val right: Float,
) {
    val center: Float
        get() = (left + right) * 0.5f
}

private fun nativeSlowGlyphVisualBox(
    layout: TextLayoutResult,
    segment: NativeLyricSegment,
    index: Int,
    fallbackLeft: Float,
    fallbackRight: Float,
): NativeSlowGlyphVisualBox? {
    val textLength = layout.layoutInput.text.text.length
    if (textLength <= 0 || index !in 0 until textLength) return null
    val startX = layout.getHorizontalPosition(index, usePrimaryDirection = true)
    val endX = layout.getHorizontalPosition((index + 1).coerceAtMost(textLength), usePrimaryDirection = true)
    val advanceLeft = minOf(startX, endX).coerceIn(segment.left, segment.right)
    val advanceRight = maxOf(startX, endX).coerceIn(segment.left, segment.right)
    val fallbackClampedLeft = fallbackLeft.coerceIn(segment.left, segment.right)
    val fallbackClampedRight = fallbackRight.coerceIn(segment.left, segment.right)
    val inkLeft = minOf(advanceLeft, fallbackClampedLeft)
    val inkRight = maxOf(advanceRight, fallbackClampedRight)
    if (inkRight - inkLeft >= NATIVE_SLOW_GLYPH_MIN_ADVANCE_PX) {
        return NativeSlowGlyphVisualBox(inkLeft, inkRight)
    }
    return null
}


private data class NativeLyricSegment(
    val timing: PipoLyricChar,
    val sourceTiming: PipoLyricChar,
    val line: Int,
    val left: Float,
    val right: Float,
    val maskStartMs: Long,
    val maskEndMs: Long,
    val slowStartMs: Long,
    val slowEndMs: Long,
    val segmentStartProgress: Float,
    val segmentEndProgress: Float,
    val tokenStartChar: Int,
    val tokenEndChar: Int,
    val startChar: Int,
    val endChar: Int,
    val letterUnits: Int,
    val letterOrdinalStart: Int,
)

private fun nativeLyricSegments(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    sourceChars: List<PipoLyricChar>,
): List<NativeLyricSegment> {
    val text = layout.layoutInput.text.text
    if (text.isEmpty() || chars.isEmpty()) return emptyList()
    val segments = ArrayList<NativeLyricSegment>(chars.size)
    var cursor = 0
    chars.forEachIndexed { index, timing ->
        val sourceTiming = sourceChars.getOrNull(index) ?: timing
        val start = cursor.coerceAtMost(text.length)
        val end = (cursor + timing.text.length).coerceAtMost(text.length)
        cursor = end
        if (start >= end) return@forEachIndexed
        var segStart = start
        while (segStart < end) {
            val line = layout.getLineForOffset(segStart)
            val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true).coerceAtLeast(segStart + 1))
            nativeAddSegment(segments, layout, timing, sourceTiming, segStart, lineEnd, start, end, line)
            segStart = lineEnd
        }
    }
    return segments
}

private fun nativeAddSegment(
    out: MutableList<NativeLyricSegment>,
    layout: TextLayoutResult,
    timing: PipoLyricChar,
    sourceTiming: PipoLyricChar,
    start: Int,
    end: Int,
    tokenStart: Int,
    tokenEnd: Int,
    line: Int,
) {
    val text = layout.layoutInput.text.text
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    for (i in start until end) {
        // 空格不计入段边界：YRC 词 token 常带尾随空格（"we " + "just"），把空格算进
        // 上一段会让相邻段的裁切线贴在下一个字母的原点上——'j' 这类负左边距字形的
        // 钩尖越过裁切线、被按邻段的上浮量绘制，上浮时字形“前角裂开”。只按可见
        // 墨迹算边界后，裁切线落在词间空隙正中，两侧字形的悬伸都够不到。
        if (i in text.indices && (text[i].isWhitespace() || text[i].isISOControl())) continue
        val box = layout.getBoundingBox(i)
        if (box.right <= box.left) continue
        left = minOf(left, box.left)
        right = maxOf(right, box.right)
    }
    if (left.isFinite() && right.isFinite() && right > left) {
        // Apple puts timing on the whole `.syllable` span (`data-delay` + `data-duration`).
        // We may split the same token only for Android line geometry, but every piece must keep
        // the token's full animation clock; otherwise a wrapped/trimmed word gets compressed and
        // looks like it sweeps in a single frame.
        val slowDuration = nativeSlowSyllableDurationMs(sourceTiming).toFloat()
        val slowStartMs = sourceTiming.startMs
        val slowEndMs = (sourceTiming.startMs + slowDuration)
            .toLong()
            .coerceAtMost(sourceTiming.effectiveEndMs())
            .coerceAtLeast(sourceTiming.startMs + 1L)
        out.add(
            NativeLyricSegment(
                timing = timing,
                sourceTiming = sourceTiming,
                line = line,
                left = left,
                right = right,
                maskStartMs = timing.startMs,
                maskEndMs = nativeAppleSyllableEndMs(timing),
                slowStartMs = slowStartMs,
                slowEndMs = slowEndMs,
                segmentStartProgress = 0f,
                segmentEndProgress = 1f,
                tokenStartChar = tokenStart,
                tokenEndChar = tokenEnd,
                startChar = start,
                endChar = end,
                letterUnits = nativeAppleLetterTimelineUnits(timing.text),
                letterOrdinalStart = nativeAppleLetterOrdinalBefore(
                    text = text,
                    start = tokenStart,
                    end = start,
                ),
            ),
        )
    }
}

    // Apple Web ordinary syllable：颜色按 data-delay/data-duration 线性扫色；
    // 位移单独从 delay + 100ms 开始，到 end + 100ms 完成。短 YRC timing 需要兜底时，
    // 上浮不能复用扫色的较长窗口，否则前一个词的轻微上浮会拖进下一个词，形成抖动。
    private fun nativeSegmentLiftProgress(
        segment: NativeLyricSegment,
        positionMs: Long,
    ): Float {
        return nativeRegularWordLiftEase01(
            nativeSegmentTimelineProgress(
                segment = segment,
                positionMs = positionMs - NATIVE_WORD_LIFT_DELAY_MS,
            ),
        )
    }

// Apple Web ordinary syllable keyframe uses ease: 1 here; keep it linear so
// adjacent words do not get phase-shifted by a second easing curve.
private fun nativeRegularWordLiftEase01(progress: Float): Float {
    return progress.coerceIn(0f, 1f)
}

    private fun nativeSegmentFillProgress(
        segment: NativeLyricSegment,
        positionMs: Long,
    ): Float {
        return nativeSegmentTimelineProgress(
            segment = segment,
            positionMs = positionMs,
        )
    }

private fun nativeSegmentTimelineProgress(
    segment: NativeLyricSegment,
    positionMs: Long,
): Float {
    // Apple Web gives every `.syllable` one linear data-delay/data-duration
    // timeline. Store that window in NativeLyricSegment so draw frames do not
    // need to walk back through the token object for every segment.
    val maskStartMs = segment.maskStartMs
    val maskEndMs = segment.maskEndMs
    val tokenProgress = when {
        positionMs <= maskStartMs -> 0f
        positionMs >= maskEndMs -> 1f
        else -> ((positionMs - maskStartMs).toFloat() / (maskEndMs - maskStartMs).toFloat())
            .coerceIn(0f, 1f)
    }
    val start = segment.segmentStartProgress
    val end = segment.segmentEndProgress
    if (tokenProgress <= start) return 0f
    if (tokenProgress >= end) return 1f
    return ((tokenProgress - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
}

private fun nativeAppleSyllableEndMs(token: PipoLyricChar): Long {
    return (token.startMs + token.durationMs.coerceAtLeast(1L)).coerceAtLeast(token.startMs + 1L)
}

private fun nativeSlowSyllableDurationMs(token: PipoLyricChar): Long {
    // Apple Web uses `word.end - word.begin` for emphasis. Android sometimes
    // merges split YRC fragments into one visual word; the effective duration
    // is the closest local equivalent to Apple's full word begin/end.
    return token.effectiveDurationMs()
}

private fun nativeAppleEmphasisContentUnits(text: String): Int {
    // Apple's word object carries trailing spaces as `hasTrailingWhitespace`; `content.length`
    // does not include that visual gap. YRC keeps spaces attached to the token, so strip only
    // trailing whitespace before applying the >=1s && <=7 emphasis gate.
    return text.dropLastWhile { it.isWhitespace() || it.isISOControl() }.length.coerceAtLeast(1)
}

private fun nativeAppleLetterTimelineUnits(text: String): Int {
    // `data-content` is already whitespace-free in Apple Web; exclude attached YRC
    // trailing spaces here so slow-letter wave timing is not divided by an invisible unit.
    return text.count {
        !nativeAppleStripsSyllableChar(it) && !it.isWhitespace() && !it.isISOControl()
    }.coerceAtLeast(1)
}

private fun nativeAppleLetterOrdinalBefore(text: String, start: Int, end: Int): Int {
    val safeStart = start.coerceIn(0, text.length)
    val safeEnd = end.coerceIn(safeStart, text.length)
    var count = 0
    for (index in safeStart until safeEnd) {
        val ch = text[index]
        if (!nativeAppleStripsSyllableChar(ch) && !ch.isWhitespace() && !ch.isISOControl()) {
            count++
        }
    }
    return count
}

private fun nativeAppleStripsSyllableChar(ch: Char): Boolean {
    return ch == '(' || ch == ')'
}

// Apple Web shouldBeEmphasized 用原始 content；但 createSyllable 会把显示文本
// replace(/[()]/g, "") 后再拆 .letter。两条路径分开，避免括号既显示出来，
// 又因为去括号后的长度把慢词阈值算错。
private fun nativeSlowWordAmount(
    displayToken: PipoLyricChar,
    sourceToken: PipoLyricChar,
): Float {
    if (!nativeIsAppleSlowWord(sourceToken)) return 0f
    if (displayToken.text.isEmpty()) return 0f
    return 1f
}

private fun nativeIsAppleSlowWord(token: PipoLyricChar): Boolean {
    return nativeSlowSyllableDurationMs(token) >= NATIVE_SLOW_WORD_MIN_DURATION_MS &&
        nativeAppleEmphasisContentUnits(token.text) <= NATIVE_SLOW_WORD_MAX_UNITS
}

private fun nativeIsCjkText(text: String): Boolean {
    return text.any(::nativeIsCjkChar)
}

private fun nativeIsCjkChar(ch: Char): Boolean {
    return ch in '\u4E00'..'\u9FFF' ||
        ch in '\u3040'..'\u30FF' ||
        ch in '\uAC00'..'\uD7AF'
}

private fun nativeSolidLineColor(fg: Color): Color {
    return fg.copy(alpha = fg.alpha * NATIVE_SOLID_LINE_ALPHA)
}

private fun nativeTimedGradientSungColor(fg: Color): Color {
    return fg.copy(alpha = fg.alpha * NATIVE_GRADIENT_ACTIVE_ALPHA)
}

private fun nativeTimedGradientUnsungColor(fg: Color): Color {
    return fg.copy(alpha = fg.alpha * NATIVE_GRADIENT_INACTIVE_ALPHA)
}

private fun nativeLineBlur(
    hasLineFocus: Boolean,
    isFirstLine: Boolean,
    isUserInteracting: Boolean,
): Float {
    // Apple Web applies `filter: blur(var(--inactive-gaussian-blur, 0))` to
    // non-current synced lines, but its selector excludes the first synced line
    // (`:has(.is-first)`). It also clears blur while the user is manually scrolling.
    return if (hasLineFocus || isFirstLine || isUserInteracting) {
        0f
    } else {
        NATIVE_INACTIVE_GAUSSIAN_BLUR_DP
    }
}

private fun nativeSublineColor(role: PipoLyricRole, fg: Color): Color {
    val alpha = if (role == PipoLyricRole.Romaji) {
        NATIVE_SUPPLEMENTARY_OPACITY
    } else {
        NATIVE_SUBLINE_OPACITY
    }
    return fg.copy(alpha = alpha)
}

private fun nativeHasStaticSubline(line: PipoLyricLine): Boolean {
    return line.companionLines.any {
        it.role == PipoLyricRole.Translation || it.role == PipoLyricRole.Romaji
    }
}

private fun nativeCompanionStartMs(line: PipoLyricLine): Long {
    val charStart = line.chars.minOfOrNull { it.startMs }
    return charStart ?: line.startMs
}

private fun nativeIsCompanionPast(line: PipoLyricLine, positionMs: Long): Boolean {
    return positionMs >= nativeCompanionEndMs(line)
}

private fun nativeCompanionEndMs(line: PipoLyricLine): Long {
    val charEnd = line.chars.maxOfOrNull { it.startMs + it.durationMs.coerceAtLeast(1L) }
    return charEnd ?: (line.startMs + line.durationMs)
}

private data class NativeTimelineSnapshot(
    val targetIndex: Int,
    val targetSlotIndex: Int,
    val currentLineIndex: Int,
    val activeIndices: Set<Int>,
    val latestIndex: Int,
)

private sealed class NativeLyricSlot {
    abstract val startMs: Long
    abstract val endMs: Long

    data class Line(
        val lineIndex: Int,
        override val startMs: Long,
        override val endMs: Long,
    ) : NativeLyricSlot()

    data class Interlude(
        override val startMs: Long,
        override val endMs: Long,
        val anchorLineIndex: Int,
        val nextLineIndex: Int,
    ) : NativeLyricSlot()
}

private data class NativeLyricSlotPlan(
    val slots: List<NativeLyricSlot>,
    val lineToSlot: IntArray,
)

// 时间轴快照由帧时钟驱动，但只在 current/target 离散结果变化时写回 Compose state。
// 行起止时间在这里一次预计算
//（nativeLineAudioEndMs 每次调用都要 chars+companions 拼接/扫描——放在每帧就是
// 主线程的稳定 GC 与 CPU 热源），快照本身在内容不变时复用同一实例，稳态零分配。
private class NativeTimelineCache(lines: List<PipoLyricLine>) {
    val startMs = LongArray(lines.size) { nativeTimelineStartMs(lines, it) }
    val endMs = LongArray(lines.size) { nativeTimelineEndMs(lines, it) }
    val maxActiveSpanMs = LongArray(lines.size) { idx ->
        (endMs[idx] - startMs[idx]).coerceAtLeast(1L)
    }.maxOrNull() ?: 1L
    private val slotPlan = nativeBuildLyricSlots(lines, startMs, endMs)
    val slots: List<NativeLyricSlot> = slotPlan.slots
    val slotStartMs = LongArray(slots.size) { idx -> slots[idx].startMs }
    val lineToSlot: IntArray = slotPlan.lineToSlot
    var activeScratch = IntArray(8)
    var lastSnapshot: NativeTimelineSnapshot? = null
}

private fun nativeTimelineSnapshot(
    lines: List<PipoLyricLine>,
    cache: NativeTimelineCache,
    positionMs: Long,
    targetPositionMs: Long = positionMs,
): NativeTimelineSnapshot {
    if (lines.isEmpty()) return NativeTimelineSnapshot(0, 0, -1, emptySet(), 0)
    val starts = cache.startMs
    val ends = cache.endMs
    val n = starts.size
    val targetSlotIndex = nativeTargetSlotIndex(cache, targetPositionMs)
    val targetSlot = cache.slots[targetSlotIndex]
    val currentLineIndex = when (targetSlot) {
        is NativeLyricSlot.Line -> targetSlot.lineIndex
        is NativeLyricSlot.Interlude -> -1
    }
    val target = when (targetSlot) {
        is NativeLyricSlot.Line -> targetSlot.lineIndex
        is NativeLyricSlot.Interlude -> targetSlot.nextLineIndex
    }.coerceIn(lines.indices)

    // hot 行里取 start 最大者（同 start 保留先出现的，与原 maxByOrNull 一致）。
    // 先按 startMs 二分定位“已经开始的最后一行”，再最多回扫一段可能仍 active 的窗口。
    // 长歌词下原来每帧扫全表是 UI 线程稳定成本；这里不改变 active/current 语义，只换索引方式。
    var cueIndex = -1
    var cueStartMs = Long.MIN_VALUE
    val latestStartedIndex = (nativeUpperBound(starts, positionMs, n) - 1).coerceAtMost(n - 1)
    val minActiveStartMs = positionMs - cache.maxActiveSpanMs
    var idx = latestStartedIndex
    while (idx >= 0 && starts[idx] >= minActiveStartMs) {
        val start = starts[idx]
        if (positionMs >= start && positionMs < ends[idx] && start > cueStartMs) {
            cueIndex = idx
            cueStartMs = start
            break
        }
        idx--
    }
    var activeCount = 0
    if (cueIndex >= 0) {
        val graceStart = cueStartMs - NATIVE_SAME_CUE_START_GRACE_MS
        var activeIdx = nativeLowerBound(starts, graceStart, n)
        while (activeIdx < n && starts[activeIdx] <= cueStartMs) {
            val start = starts[activeIdx]
            if (positionMs >= start && positionMs < ends[activeIdx]) {
                if (activeCount == cache.activeScratch.size) {
                    cache.activeScratch = cache.activeScratch.copyOf(activeCount * 2)
                }
                cache.activeScratch[activeCount++] = activeIdx
            }
            activeIdx++
        }
    }
    val activeMax = if (activeCount > 0) cache.activeScratch[activeCount - 1] else target
    val latest = maxOf(activeMax, target).coerceIn(lines.indices)
    val coercedTarget = target.coerceIn(lines.indices)

    val last = cache.lastSnapshot
    if (last != null &&
        last.targetIndex == coercedTarget &&
        last.targetSlotIndex == targetSlotIndex &&
        last.currentLineIndex == currentLineIndex &&
        last.latestIndex == latest &&
        last.activeIndices.size == activeCount &&
        nativeActiveSetMatches(last.activeIndices, cache.activeScratch, activeCount)
    ) {
        return last
    }
    val active = LinkedHashSet<Int>(maxOf(4, activeCount * 2))
    for (k in 0 until activeCount) {
        active.add(cache.activeScratch[k])
    }
    val snapshot = NativeTimelineSnapshot(
        targetIndex = coercedTarget,
        targetSlotIndex = targetSlotIndex,
        currentLineIndex = currentLineIndex,
        activeIndices = active,
        latestIndex = latest,
    )
    cache.lastSnapshot = snapshot
    return snapshot
}

private fun nativeActiveSetMatches(set: Set<Int>, scratch: IntArray, count: Int): Boolean {
    for (k in 0 until count) {
        if (scratch[k] !in set) return false
    }
    return true
}

private fun nativeBuildLyricSlots(
    lines: List<PipoLyricLine>,
    starts: LongArray,
    ends: LongArray,
): NativeLyricSlotPlan {
    val lineToSlot = IntArray(lines.size) { 0 }
    if (lines.isEmpty()) {
        return NativeLyricSlotPlan(emptyList(), lineToSlot)
    }
    val slots = ArrayList<NativeLyricSlot>(lines.size * 2)
    fun addInterlude(startMs: Long, endMs: Long, anchorLineIndex: Int, nextLineIndex: Int) {
        if (endMs >= startMs) {
            slots.add(
                NativeLyricSlot.Interlude(
                    startMs = startMs.coerceAtLeast(0L),
                    endMs = endMs.coerceAtLeast(startMs),
                    anchorLineIndex = anchorLineIndex,
                    nextLineIndex = nextLineIndex,
                ),
            )
        }
    }

    if (starts[0] > NATIVE_INTERLUDE_MIN_GAP_MS) {
        addInterlude(
            startMs = 0L,
            endMs = starts[0] - 1L,
            anchorLineIndex = -1,
            nextLineIndex = 0,
        )
    }
    for (idx in lines.indices) {
        lineToSlot[idx] = slots.size
        slots.add(
            NativeLyricSlot.Line(
                lineIndex = idx,
                startMs = starts[idx],
                endMs = ends[idx],
            ),
        )
        if (idx < lines.lastIndex) {
            val nextStart = starts[idx + 1]
            val gap = nextStart - ends[idx]
            if (gap > NATIVE_INTERLUDE_MIN_GAP_MS) {
                addInterlude(
                    startMs = ends[idx] + 1L,
                    endMs = nextStart - 1L,
                    anchorLineIndex = idx,
                    nextLineIndex = idx + 1,
                )
            }
        }
    }
    return NativeLyricSlotPlan(slots, lineToSlot)
}

private fun nativeTargetSlotIndex(
    cache: NativeTimelineCache,
    targetPositionMs: Long,
): Int {
    val slots = cache.slots
    if (slots.isEmpty()) return 0
    val targetIndex = nativeUpperBound(cache.slotStartMs, targetPositionMs, slots.size) - 1
    return targetIndex.coerceIn(slots.indices)
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
    val timedChars = line.chars + timedCompanions.flatMap { it.chars }
    val charEnd = timedChars.maxOfOrNull { it.startMs + it.durationMs.coerceAtLeast(1L) }
    val lineEnd = maxOf(
        line.startMs + line.durationMs,
        timedCompanions.maxOfOrNull { it.startMs + it.durationMs } ?: line.startMs,
    )
    return charEnd ?: lineEnd
}

@Composable
private fun rememberNativeAppleLineClockMs(
    lineStartMs: Long,
    lineKey: String,
    localIsActive: Boolean,
    isPlaying: Boolean,
    rawPositionState: State<Long>,
    wordTimelineOffsetMs: Long,
): State<Float> {
    val out = remember(lineKey) { mutableFloatStateOf(lineStartMs.toFloat()) }
    val playingState = rememberUpdatedState(isPlaying)

    LaunchedEffect(lineKey, localIsActive, wordTimelineOffsetMs) {
        var anchorTimeMs = (rawPositionState.value + wordTimelineOffsetMs)
            .coerceAtLeast(lineStartMs)
            .toFloat()
        out.floatValue = anchorTimeMs
        if (!localIsActive) return@LaunchedEffect

        var anchorFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (!playingState.value) {
                anchorTimeMs = out.floatValue
                anchorFrameNanos = frameNanos
                continue
            }

            val localTimeMs = anchorTimeMs + (frameNanos - anchorFrameNanos) / 1_000_000f
            val rawTimeMs = rawPositionState.value.toFloat() + wordTimelineOffsetMs
            if (kotlin.math.abs(rawTimeMs - localTimeMs) > NATIVE_APPLE_LINE_CLOCK_RESET_MS) {
                // Apple Web 的 TimeGroup 用 performance.now() 连续推进；只有 seek/大跳位
                // 这类 >1s 的播放位置变化才重锚，普通播放器 position 抖动不再打进词级动画。
                anchorTimeMs = rawTimeMs.coerceAtLeast(lineStartMs.toFloat())
                anchorFrameNanos = frameNanos
                out.floatValue = anchorTimeMs
            } else {
                out.floatValue = localTimeMs.coerceAtLeast(lineStartMs.toFloat())
            }
        }
    }

    return out
}

@Composable
private fun rememberNativeRawPositionState(
    fallbackPositionMs: Long,
    positionProvider: (() -> Long)?,
    sessionKey: String,
    initialPositionMs: Long,
): State<Long> {
    val providerState = rememberUpdatedState(positionProvider)
    val fallbackState = rememberUpdatedState(fallbackPositionMs.coerceAtLeast(0L))
    val out = remember(sessionKey) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    LaunchedEffect(sessionKey, positionProvider) {
        snapshotFlow {
            providerState.value?.invoke()?.coerceAtLeast(0L) ?: fallbackState.value
        }
            .distinctUntilChanged()
            .collect { nextPositionMs ->
                out.longValue = nextPositionMs
            }
    }
    return out
}

@Composable
private fun rememberNativeLyricClockMs(
    rawPositionState: State<Long>,
    isPlaying: Boolean,
    sessionKey: String,
    initialRawPositionMs: Long,
): State<Float> {
    val initialRaw = initialRawPositionMs.coerceAtLeast(0L)
    val out = remember(sessionKey) { mutableFloatStateOf(initialRaw.toFloat()) }
    // base = 音频位置 − 墙钟（ms，Double 保精度）。视觉时钟 = 墙钟 + base。
    // 旧实现每次报告都重锚 + 0.82 追赶 + 超前冻结：报告抖动直接进画面、平均滞后明显。
    // 现在 base 长期锁死在播放器数据上（每次报告温和校正），短期抖动被摊平 —— 更跟手也更稳。
    val baseMs = remember(sessionKey) { mutableDoubleStateOf(initialRaw - System.nanoTime() / 1e6) }
    val latestRawMs = remember(sessionKey) { mutableFloatStateOf(initialRaw.toFloat()) }
    val resetToken = remember(sessionKey) { mutableLongStateOf(0L) }
    val canExtrapolate = remember(sessionKey) {
        mutableStateOf(initialRaw > NATIVE_CLOCK_START_GUARD_MS)
    }
    val pendingBackwardAlign = remember(sessionKey) { mutableStateOf(false) }

    LaunchedEffect(rawPositionState, isPlaying, sessionKey) {
        snapshotFlow { rawPositionState.value }
            .distinctUntilChanged()
            .collect { rawPositionMs ->
                val nowMs = System.nanoTime() / 1e6
                val nextRaw = rawPositionMs.toFloat().coerceAtLeast(0f)
                val reportedBase = nextRaw - nowMs
                val drift = reportedBase - baseMs.doubleValue
                // 偏差超阈值（seek/卡顿恢复/暂停）直接重锚；否则只朝数据微调一步。
                val shouldReset = !isPlaying ||
                    kotlin.math.abs(drift) > NATIVE_CLOCK_BASE_SNAP_MS ||
                    out.floatValue <= 0.001f
                latestRawMs.floatValue = nextRaw
                canExtrapolate.value = if (shouldReset) {
                    nextRaw > NATIVE_CLOCK_START_GUARD_MS
                } else {
                    canExtrapolate.value || nextRaw > NATIVE_CLOCK_START_GUARD_MS
                }
                if (shouldReset) {
                    pendingBackwardAlign.value = false
                    baseMs.doubleValue = reportedBase
                    resetToken.longValue += 1L
                    // 暂停态的小幅差值交给帧环的暂停回拢缓动（避免一帧覆盖它）；
                    // 大幅差值=真实跳位（暂停中 seek），仍即时对齐。
                    if (isPlaying || kotlin.math.abs(nextRaw - out.floatValue) > NATIVE_CLOCK_PAUSE_EASE_MAX_MS) {
                        out.floatValue = nextRaw
                    }
                } else if (kotlin.math.abs(drift) <= NATIVE_CLOCK_JITTER_BAND_MS) {
                    // 抖动带内的微小偏差：温和吸收，避免锯齿。
                    pendingBackwardAlign.value = false
                    baseMs.doubleValue += drift * NATIVE_CLOCK_BASE_SLEW
                } else if (drift > 0.0) {
                    // 数据权威：前向超出抖动带立刻对齐报告值（帧环限速追赶消化，扫色必须跟声音）。
                    pendingBackwardAlign.value = false
                    baseMs.doubleValue = reportedBase
                } else {
                    // 后向 45~280ms：单报告去抖。源切换/坐标换算偶发的单样本回退不再直接
                    // 打进画面（那是“扫色无故倒退一截”的来源）；连续第二个报告仍要求回退
                    // 才对齐——真实回退只多等一个报告（~33ms），数据权威不变，之后的视觉
                    // 回退仍由帧环按既定档位消化（≤120ms 降速滑行、更大直接对齐）。
                    if (!pendingBackwardAlign.value) {
                        pendingBackwardAlign.value = true
                    } else {
                        pendingBackwardAlign.value = false
                        baseMs.doubleValue = reportedBase
                    }
                }
            }
    }

    LaunchedEffect(isPlaying, sessionKey) {
        if (!isPlaying) {
            // 暂停/rebuffer：平滑时钟可能领先数据一小截。一帧瞬移回数据位置就是
            // “停住瞬间扫色倒退一下”，小幅差值改用短缓动回拢。暂停期间持续监听
            // resetToken（数据效果每次报告都会重锚），暂停中的 seek/重定位同样
            // 经这里评估：小幅缓动、大幅直接对齐——不会停留在过期位置。
            var settledToken = resetToken.longValue - 1L
            while (isActive) {
                if (settledToken != resetToken.longValue) {
                    settledToken = resetToken.longValue
                    val from = out.floatValue
                    val target0 = latestRawMs.floatValue
                    val diff = target0 - from
                    if (kotlin.math.abs(diff) <= NATIVE_CLOCK_PAUSE_EASE_MIN_MS ||
                        kotlin.math.abs(diff) > NATIVE_CLOCK_PAUSE_EASE_MAX_MS
                    ) {
                        out.floatValue = target0
                    } else {
                        val startNanos = withFrameNanos { it }
                        while (isActive) {
                            val now = withFrameNanos { it }
                            if (resetToken.longValue != settledToken &&
                                kotlin.math.abs(latestRawMs.floatValue - target0) > 2f
                            ) {
                                // 回拢途中又跳位 → 跳出，外层按新数据重新评估。
                                break
                            }
                            val t = (((now - startNanos) / 1_000_000f) / NATIVE_CLOCK_PAUSE_EASE_DURATION_MS)
                                .coerceIn(0f, 1f)
                            out.floatValue = from + diff * nativeSmoothStep(t)
                            if (t >= 1f) break
                        }
                        settledToken = resetToken.longValue - 1L
                        continue
                    }
                }
                withFrameNanos { }
            }
            return@LaunchedEffect
        }
        var smoothed = out.floatValue
        var seenResetToken = resetToken.longValue
        var lastFrame = 0L
        while (isActive) {
            withFrameNanos { frame ->
                val dtMs = if (lastFrame == 0L) 16.6f else ((frame - lastFrame) / 1_000_000f).coerceIn(0f, 50f)
                lastFrame = frame
                if (seenResetToken != resetToken.longValue) {
                    seenResetToken = resetToken.longValue
                    smoothed = latestRawMs.floatValue
                }
                if (!canExtrapolate.value) {
                    // 起播保护（位置尚不稳定，0ms 附近还在缓冲）：仍按墙钟 1x 外插——速度从
                    // 第一帧就是正确的，只是封顶在「最新报告 + 小余量」。报告停着不动（还没真正
                    // 出声）时，时钟最多走到余量处冻结等待；出声后立刻贴住数据继续。
                    // 旧实现只在报告到达时跳步，而起播恰是主线程最忙、报告最稀疏的时刻，
                    // 用户看到的就是“开头一小段快进式卡跳的校准过程”。
                    val rawTarget = latestRawMs.floatValue
                    smoothed = when {
                        // 远落后（边放边加载后位置已跑远）→ 一次对齐。
                        rawTarget - smoothed > NATIVE_CLOCK_FRAME_RESET_MS -> rawTarget
                        // 明显回退（起播重定位/回跳 seek）→ 一次对齐，不做慢动作。
                        rawTarget < smoothed - NATIVE_CLOCK_BACKWARD_RESET_MS -> rawTarget
                        // 常规：1x 前进，封顶 raw+余量；轻微回退时冻结在原地等数据追上，不回扫。
                        else -> maxOf(
                            smoothed,
                            minOf(smoothed + dtMs, rawTarget + NATIVE_CLOCK_START_HEADROOM_MS),
                        )
                    }
                    out.floatValue = smoothed
                    return@withFrameNanos
                }
                val target = (frame / 1e6 + baseMs.doubleValue).toFloat().coerceAtLeast(0f)
                val diff = target - smoothed
                smoothed = when {
                    diff >= 0f -> {
                        // 残余前向偏差（target 自身每帧前进 dt，扣除后才是 base 前跳量）。
                        val gap = diff - dtMs
                        if (gap <= NATIVE_CLOCK_FORWARD_FREE_MS || gap > NATIVE_CLOCK_FORWARD_SNAP_MS) {
                            // 稳态（gap≈0）保持逐帧贴住 target；超大前跳（卡顿恢复级）仍即时对齐，
                            // 长时间画面落后于声音比一次跳变更糟。
                            target
                        } else {
                            // 中等前跳（base 越过抖动带的对齐，典型 45~280ms）：限速追赶——
                            // 每帧最多加 STEP_MAX 的额外行程，几帧内贴上。单帧跳变消失，
                            // 音画误差单调递减且峰值不超过原跳变量。这不是被否决过的
                            // “无界慢追赶”：步长有下限（0.45x dt）有上限，收敛 ≤0.2s。
                            val step = minOf(
                                gap,
                                minOf(
                                    NATIVE_CLOCK_FORWARD_STEP_MAX_MS,
                                    maxOf(dtMs * NATIVE_CLOCK_FORWARD_MIN_RATE, gap * NATIVE_CLOCK_FORWARD_FRACTION),
                                ),
                            )
                            smoothed + dtMs + step
                        }
                    }
                    // 明显回退（数据权威向下校正较多）→ 一次性对齐。旧的 0.4x 降速滑行在
                    // 回退几百毫秒时要拖 1-2 秒的“慢动作”，用户感知为速度不对/像在重放。
                    diff < -NATIVE_CLOCK_BACKWARD_SNAP_MS -> target
                    // 轻微超前（≤120ms 的向下校正）→ 降速滑行 ≤0.3s 消化：不回跳、不冻结。
                    else -> smoothed + dtMs * NATIVE_CLOCK_OVERRUN_GLIDE_SPEED
                }
                out.floatValue = smoothed
            }
        }
    }
    return out
}

// 自动播放的切句跟随：用"保留速度、持续收敛"的弹簧，而不是固定时长 tween。
// 固定 tween 追一个每句都前移的目标时，若切句间隔 < 时长就被打断重来、每次只给固定行程，
// 会持续落后、十几句后当前句被推出屏幕（表现为"动画消失"）；弹簧每次从当前速度续接、
// 始终朝最新目标加速收敛，既不会落后累积，也让位移与放大/颜色朝同一目标同步收敛（联动感）。
private fun nativeScrollFollowSpringSpec(): AnimationSpec<Float> {
    return spring(
        dampingRatio = nativeDampingRatio(
            stiffness = NATIVE_SCROLL_FOLLOW_STIFFNESS,
            damping = NATIVE_SCROLL_FOLLOW_DAMPING,
        ),
        stiffness = NATIVE_SCROLL_FOLLOW_STIFFNESS,
        // 亚像素阈值：避免在目标极近时反复发起微动画。
        visibilityThreshold = 0.5f,
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

private class NativeRowMetrics(
    private val mainHeights: IntArray,
    private val transHeights: IntArray,
    private val mainPrefix: FloatArray,
    private val transPrefix: FloatArray,
    private val renderPrefix: FloatArray,
) {
    private val lineCount: Int = mainHeights.size

    val totalRenderHeight: Float
        get() = renderPrefix[lineCount]

    fun rowHeight(index: Int): Int {
        if (lineCount <= 0) return 0
        val safeIndex = index.coerceIn(0, lineCount - 1)
        return mainHeights[safeIndex] + transHeights[safeIndex]
    }

    fun renderTop(index: Int): Float {
        return renderPrefix[index.coerceIn(0, lineCount)]
    }

    fun rowTopBase(index: Int): Float {
        return mainPrefix[index.coerceIn(0, lineCount)]
    }

    fun transOffsetForBase(basePos: Float): Float {
        if (lineCount <= 0 || basePos <= 0f) return 0f
        val totalMainHeight = mainPrefix[lineCount]
        if (basePos >= totalMainHeight) return transPrefix[lineCount]
        val row = (nativeUpperBound(mainPrefix, basePos, lineCount + 1) - 1)
            .coerceIn(0, lineCount - 1)
        val rowStart = mainPrefix[row]
        val rowHeight = mainHeights[row].toFloat()
        val partial = if (basePos > rowStart && rowHeight > 0f) {
            transHeights[row].toFloat() * ((basePos - rowStart) / rowHeight)
        } else {
            0f
        }
        return transPrefix[row] + partial
    }

    fun baseForRenderCenter(renderPos: Float): Float {
        if (lineCount <= 0 || renderPos <= 0f) return 0f
        if (renderPos >= renderPrefix[lineCount]) return mainPrefix[lineCount]
        val row = (nativeUpperBound(renderPrefix, renderPos, lineCount + 1) - 1)
            .coerceIn(0, lineCount - 1)
        val rowRenderStart = renderPrefix[row]
        val rowRenderHeight = (mainHeights[row] + transHeights[row]).toFloat()
        val partial = if (renderPos > rowRenderStart && rowRenderHeight > 0f) {
            mainHeights[row].toFloat() * ((renderPos - rowRenderStart) / rowRenderHeight)
        } else {
            0f
        }
        return mainPrefix[row] + partial
    }

    fun nearestRenderIndex(renderPos: Float): Int {
        if (lineCount <= 1) return 0
        if (renderPos <= renderPrefix[0]) return 0
        if (renderPos >= renderPrefix[lineCount - 1]) return lineCount - 1
        val upper = nativeLowerBound(renderPrefix, renderPos, lineCount)
            .coerceIn(1, lineCount - 1)
        val lower = upper - 1
        val lowerDistance = kotlin.math.abs(renderPos - renderPrefix[lower])
        val upperDistance = kotlin.math.abs(renderPrefix[upper] - renderPos)
        return if (lowerDistance <= upperDistance) lower else upper
    }

}

private fun nativeRowMetrics(
    slots: List<NativeLyricSlot>,
    estimatedRowHeightPx: Int,
    transProgress: Float,
    mainRowHeights: Map<Int, Int>,
    transFullHeights: Map<Int, Int>,
    transMaxHeights: Map<Int, Int>,
): NativeRowMetrics {
    val safeLineCount = slots.size.coerceAtLeast(0)
    val mainHeights = IntArray(safeLineCount)
    val transHeights = IntArray(safeLineCount)
    val mainPrefix = FloatArray(safeLineCount + 1)
    val transPrefix = FloatArray(safeLineCount + 1)
    val renderPrefix = FloatArray(safeLineCount + 1)
    val safeEstimated = estimatedRowHeightPx.coerceAtLeast(1)
    val safeTransProgress = transProgress.coerceAtLeast(0f)
    for (idx in 0 until safeLineCount) {
        val defaultHeight = when (slots[idx]) {
            is NativeLyricSlot.Line -> safeEstimated
            is NativeLyricSlot.Interlude -> 1
        }
        val mainHeight = (mainRowHeights[idx] ?: defaultHeight).coerceAtLeast(1)
        val fullTransHeight = (transFullHeights[idx] ?: 0).coerceAtLeast(0)
        val maxTransHeight = (transMaxHeights[idx] ?: fullTransHeight).coerceAtLeast(0)
        val transHeight = nativeAppleSublineCollapsedHeight(
            fullHeightPx = fullTransHeight,
            maxHeightPx = maxTransHeight,
            progress = safeTransProgress,
        )
        mainHeights[idx] = mainHeight
        transHeights[idx] = transHeight
        mainPrefix[idx + 1] = mainPrefix[idx] + mainHeight
        transPrefix[idx + 1] = transPrefix[idx] + transHeight
        renderPrefix[idx + 1] = renderPrefix[idx] + mainHeight + transHeight
    }
    return NativeRowMetrics(
        mainHeights = mainHeights,
        transHeights = transHeights,
        mainPrefix = mainPrefix,
        transPrefix = transPrefix,
        renderPrefix = renderPrefix,
    )
}

private fun nativeAppleSublineCollapsedHeight(
    fullHeightPx: Int,
    maxHeightPx: Int,
    progress: Float,
): Int {
    val full = fullHeightPx.coerceAtLeast(0)
    if (full <= 0) return 0
    val maxHeight = maxHeightPx.coerceAtLeast(1)
    val animatedMax = (maxHeight * progress.coerceIn(0f, 1f)).roundToInt()
    return minOf(full, animatedMax).coerceAtLeast(0)
}

private fun nativeUpperBound(values: FloatArray, value: Float, size: Int): Int {
    var low = 0
    var high = size.coerceIn(0, values.size)
    while (low < high) {
        val mid = (low + high) ushr 1
        if (value < values[mid]) {
            high = mid
        } else {
            low = mid + 1
        }
    }
    return low
}

private fun nativeUpperBound(values: LongArray, value: Long, size: Int): Int {
    var low = 0
    var high = size.coerceIn(0, values.size)
    while (low < high) {
        val mid = (low + high) ushr 1
        if (value < values[mid]) {
            high = mid
        } else {
            low = mid + 1
        }
    }
    return low
}

private fun nativeLowerBound(values: FloatArray, value: Float, size: Int): Int {
    var low = 0
    var high = size.coerceIn(0, values.size)
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] < value) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low
}

private fun nativeLowerBound(values: LongArray, value: Long, size: Int): Int {
    var low = 0
    var high = size.coerceIn(0, values.size)
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] < value) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low
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
    val visualWidth = if (available <= compactWidthPx) {
        available
    } else {
        available * aspect.coerceIn(0.2f, 1f)
    }
    val maxCurrentScale = 1f + (NATIVE_CURRENT_LINE_SCALE - 1f) *
        NATIVE_LINE_TRANSFORM_PROGRESS_MAX
    val safeScaledWidth = (available - NATIVE_LINE_SCALE_EDGE_GUARD_PX)
        .coerceAtLeast(1f) / maxCurrentScale
    return visualWidth.coerceAtMost(safeScaledWidth).coerceAtLeast(1f)
}

private fun nativeInterludeDotSizePx(fontPx: Float): Float {
    return fontPx * NATIVE_INTERLUDE_DOT_SIZE_EM
}

private fun nativeRowEnterProgress(enterProgress: Float, distance: Int): Float {
    if (enterProgress >= 0.999f) return 1f
    val delay = (distance * 0.035f).coerceAtMost(0.32f)
    val raw = ((enterProgress - delay) / (1f - delay)).coerceIn(0f, 1f)
    return 1f - (1f - raw).let { it * it * it }
}

private fun nativeLyricSessionKey(sessionId: String?, lines: List<PipoLyricLine>): String {
    val first = lines.firstOrNull()
    val last = lines.lastOrNull()
    return "${sessionId.orEmpty()}:${lines.size}:${first?.startMs}:${first?.text.hashCode()}:${last?.startMs}:${last?.text.hashCode()}"
}

private fun nativeRenderPositionMs(clockMs: Float): Long {
    return (clockMs + NATIVE_RENDER_PIPELINE_LEAD_MS).toLong().coerceAtLeast(0L)
}

// CSS default timing-function for `transition: color 0.1s`.
private val NATIVE_CSS_DEFAULT_EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val NATIVE_LINE_SWITCH_EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val NATIVE_SCROLL_EASE_IN_OUT_QUAD = Easing { t ->
    val x = t.coerceIn(0f, 1f)
    if (x < 0.5f) {
        2f * x * x
    } else {
        val y = -2f * x + 2f
        1f - (y * y) / 2f
    }
}
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
private const val NATIVE_CURRENT_LINE_SCALE = 1.10f
private const val NATIVE_CURRENT_LINE_PADDING_EM = 12f / 22f
private const val NATIVE_LINE_TRANSFORM_PROGRESS_MAX = 1f
private const val NATIVE_LINE_GEOMETRY_SWITCH_MS = 350

// 行级"颜色/遮罩"过渡时长：与放大（NATIVE_LINE_GEOMETRY_SWITCH_MS=350）对齐，
// 让激活行进入/离去的增亮、变暗、渐变遮罩与放大同节奏，不再像 100ms 那样"瞬变"。
private const val NATIVE_LINE_COLOR_FADE_MS = 350
private const val NATIVE_INITIAL_REVEAL_MS = 90
private const val NATIVE_LINE_WIDTH_ASPECT = 0.8f
private const val NATIVE_LINE_SCALE_EDGE_GUARD_PX = 2f
private const val NATIVE_COMPACT_WIDTH_DP = 768f
// 容器尚未测量时的兜底窗口行数（少量即可，测量出容器高度后立刻切到固定行数窗口）。
private const val NATIVE_INITIAL_RENDER_RADIUS_LINES = 10
// 渲染窗口在“可视行数”基础上、上下各额外缓冲的固定行数。这段缓冲是“预热区”：
// 让即将进入可视区的行提前完成 text layout / timed plan 构建，把每行较重的首帧构建成本
// 藏在滚动之前——缓冲过小会导致“滚到的行才现场构建 → 卡顿/掉帧”。本值是本设备验证过的流畅基线。
// 它是固定常量、不读实时测量，因此窗口绝不会随行测量振荡。
private const val NATIVE_RENDER_WINDOW_BUFFER_ROWS = 7
// 后续行的离屏计划预热：不把这些行挂进真实渲染树，只提前生成 balanced line / timed plan /
// 慢词 glyph layout。这样后半首新行进入渲染窗口时，尽量拥有和首屏一样的热缓存。
private const val NATIVE_PREWARM_AHEAD_ROWS = 24
private const val NATIVE_PREWARM_BEHIND_ROWS = 6
private const val NATIVE_GLYPH_PREWARM_AHEAD_ROWS = 5
private const val NATIVE_LIGHT_PREWARM_LINES_PER_FRAME = 2
private const val NATIVE_GLYPH_PREWARM_LINES_PER_FRAME = 1
private const val NATIVE_PREPARED_LINE_CACHE_LIMIT = 192
private const val NATIVE_WORD_FADE_WIDTH_RATIO = 1.0f
private const val NATIVE_SWEEP_PROGRESS_EPS = 0.001f
// Apple Web syllable gradient: --gradient-progress 从 -20% 走到 100%，未唱 stop 后移 20%。
private const val NATIVE_APPLE_SWEEP_LEAD_RATIO = 0.20f
private const val NATIVE_APPLE_SWEEP_TRAVEL_RATIO = 1.20f
// Apple Web ordinary syllable 的 y 动画比颜色晚 100ms。
private const val NATIVE_WORD_LIFT_DELAY_MS = 100L
// Apple Web constants: 75px is topOffset; currentIndex lookahead is currentPlaybackMillis + 250ms.
private const val NATIVE_SCROLL_FOCUS_LEAD_MS = 250L
private const val NATIVE_SLOW_GLYPH_MIN_ADVANCE_PX = 0.5f
// Apple Web current .syllable/.letter adds 0.75px horizontal padding/inset on a 22px line.
// Android clips a reused TextLayout slice rather than a DOM glyph box, so keep a small
// antialias/scale guard to avoid r/j-style overhangs being shaved during lift/scale.
private const val NATIVE_GLYPH_HORIZONTAL_CLIP_PAD_EM = 1.25f / 22f
// 多行歌词逐字扫色时，段裁切带的下沿原本贴死 getLineBottom(line)。但字形墨迹（g/y/j 的
// 悬伸）常略微越过该行度量底边，于是裁切带会把这点悬伸切掉——表现为“底部裂开”，且只有
// 下一行的词上浮、它的裁切带向上扩展覆盖到该区域时才被补画回来。直接无条件给每行下沿补一段
// 固定悬伸余量即可彻底盖住。上浮永远是向上的（liftPx ≤ 0），所以上浮时下沿的向下触达反而
// 收缩、绝不会越界露出下一行墨迹造成重影；静止帧即便下探到下一行字顶区域，也是在原位重画同
// 一像素（同未唱色），肉眼无差。
private const val NATIVE_GLYPH_VERTICAL_CLIP_PAD_EM = 0.2f
private const val NATIVE_TIMED_GLYPH_LINE_HEIGHT_EM = 1.70f
private const val NATIVE_DESCENDER_SAFE_LINE_HEIGHT_EM = 1.30f
private const val NATIVE_APPLE_TRAILING_WORD_SPACE = "\u2009"
// ===== 慢词 = Apple Web emphasis letter keyframes =====
// Apple Web shouldBeEmphasized：词长 >= 1s 且文本单位 <= 7。
private const val NATIVE_SLOW_WORD_MIN_DURATION_MS = 1_000L
private const val NATIVE_SLOW_WORD_MAX_UNITS = 7
private const val NATIVE_APPLE_WEB_LINE_FONT_PX = 22f
private const val NATIVE_SLOW_LETTER_FIRST_PHASE_MS = 500L
private const val NATIVE_SLOW_LETTER_TOTAL_MS = 1_000L
private const val NATIVE_SLOW_SCALE_PEAK = 1.05f
private const val NATIVE_SLOW_LIFT_PEAK_WEB_PX = 2.05f
private const val NATIVE_SLOW_LIFT_SETTLE_WEB_PX = 2.0f
private const val NATIVE_SLOW_SHADOW_PEAK_WEB_PX = 10f
private const val NATIVE_SLOW_SHADOW_SETTLE_WEB_PX = 4f
private const val NATIVE_SLOW_SHADOW_PEAK_ALPHA = 0.40f
private const val NATIVE_SLOW_SHADOW_CLIP_RADIUS_MULTIPLIER = 3f
// Low alpha carrier used only to make Skia emit the Apple-style text-shadow glyph mask.
// It stays visually hidden under the gradient text, but avoids the shadow being optimized away.
private const val NATIVE_SLOW_GLOW_FILL_ALPHA = 0.015f
// Apple Web ordinary syllable y: 0 -> -2px on the 22px line font.
private const val NATIVE_MAIN_WORD_LIFT_EM = 2f / 22f
private const val NATIVE_BG_WORD_LIFT_EM = 2f / 22f
// Apple Web: `.display-synced-line.is-duet .line { width: 60% }`.
// Android keeps the row container full-width for hit testing/alignment, then
// reserves the opposite 40% as padding so v1/v2 lines land on the same side as
// Apple's left/right duet columns.
private const val NATIVE_SOLID_LINE_ALPHA = 0.40f
private const val NATIVE_GRADIENT_ACTIVE_ALPHA = 0.85f
private const val NATIVE_GRADIENT_INACTIVE_ALPHA = 0.50f
// Apple Web `.background-vocals { margin-top: 20px }`(22px 字号≈0.91em）在本端 Trim.None 排版下
// 会与主歌词行盒的 descent 叠加，看起来像“整整空了一行”。收紧到约 0.34em，让副词紧贴主歌词下方。
private const val NATIVE_BG_MARGIN_TOP_EM = 7.5f / 22f
// 副词出现动画：淡入 + 上滑 + 撑开列表高度的时长/缓动/位移量。ease-out 让高度撑开收尾平滑、不突兀。
private const val NATIVE_BG_REVEAL_MS = 360
private val NATIVE_BG_REVEAL_EASE = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val NATIVE_BG_REVEAL_SLIDE_EM = 6f / 22f
private const val NATIVE_BG_FONT_SCALE = 14f / 22f
private const val NATIVE_BG_LINE_HEIGHT_SCALE = (14f * 1.2f) / (22f * 1.1818182f)
private const val NATIVE_SUBLINE_OPACITY = 0.45f
private const val NATIVE_SUPPLEMENTARY_OPACITY = 1f
private const val NATIVE_SUBLINE_ANIMATION_MS = 600
// Apple Web revealTranslations: secondary y [-10, 0] CSS px on the 22px line font.
// Static-supplementary/pronunciation uses the same y [-10, 0]; inline ruby supplementary uses -20,
// but Android currently maps x-roman to a whole-line static subline, not per-word ruby.
private const val NATIVE_SUBLINE_HIDDEN_SLIDE_WEB_PX = 10f
private const val NATIVE_SUBLINE_MAX_HEIGHT_WEB_PX = 50f
private const val NATIVE_SUBLINE_VISIBLE_MARGIN_TOP_EM = 0.2f
private const val NATIVE_SUBLINE_BOTTOM_GAP_EM = 0.34f
private const val NATIVE_SUBLINE_FONT_SCALE = 13f / 22f
private const val NATIVE_SUBLINE_LINE_HEIGHT_SCALE = (13f * 1.2f) / (22f * 1.1818182f)
private const val NATIVE_SUPPLEMENTARY_FONT_SCALE = 15f / 22f
private const val NATIVE_SUPPLEMENTARY_LINE_HEIGHT_SCALE = (15f * 1.2f) / (22f * 1.1818182f)
private const val NATIVE_INACTIVE_GAUSSIAN_BLUR_DP = 2f
private const val NATIVE_BLUR_TRANSITION_MS = 250
private const val NATIVE_MANUAL_HOLD_MS = 2_800L
private const val NATIVE_TAP_CONFIRM_WINDOW_MS = 2_800L
private const val NATIVE_INTERLUDE_MIN_GAP_MS = 9_000L
private const val NATIVE_SAME_CUE_START_GRACE_MS = 32L
private const val NATIVE_INTERLUDE_DOT_COUNT = 3f
private const val NATIVE_INTERLUDE_DOT_SIZE_EM = 8.5f / 22f
private const val NATIVE_INTERLUDE_DOT_GAP_RATIO = 0.5f
private const val NATIVE_INTERLUDE_DOT_INACTIVE_ALPHA = 0.30f
private const val NATIVE_INTERLUDE_ENDING_FADE_MS = 500f
private const val NATIVE_INTERLUDE_MAX_SCALE = 1f
private const val NATIVE_INTERLUDE_MARGIN_EM = 0.40f
// Apple Web scrollTop 本身是 350ms，但 current / previous / inactive 行状态不同。
// Android 这里保持整列同相，避免逐行延迟在快切时互相叠加抖动。
private const val NATIVE_MANUAL_TOP_BOUNCE_FRACTION = 0.18f
private const val NATIVE_MANUAL_BOTTOM_BOUNCE_FRACTION = 0.70f
private const val NATIVE_MANUAL_RESTORE_STIFFNESS = 150f
private const val NATIVE_MANUAL_RESTORE_DAMPING = 22f
private const val NATIVE_SEEK_SCROLL_STIFFNESS = 230f
private const val NATIVE_SEEK_SCROLL_DAMPING = 28f
// 切句跟随弹簧：近临界阻尼（无回弹），收敛时长≈原 350ms tween 的观感。
private const val NATIVE_SCROLL_FOLLOW_STIFFNESS = 140f
private const val NATIVE_SCROLL_FOLLOW_DAMPING = 24f
private const val NATIVE_OVERFLOW_PENALTY_MULTIPLIER = 1_000.0
private const val NATIVE_CJK_BREAK_PENALTY_RATIO = 0.15
private const val NATIVE_NORMAL_BREAK_PENALTY_RATIO = 0.50
private const val NATIVE_SPACE_BREAK_REWARD_RATIO = 0.40
private const val NATIVE_PUNCTUATION_BREAK_REWARD_RATIO = 0.60
private const val NATIVE_RENDER_PIPELINE_LEAD_MS = 0f
private const val NATIVE_CLOCK_FRAME_RESET_MS = 1_500f
private const val NATIVE_CLOCK_BACKWARD_RESET_MS = 300f
private const val NATIVE_CLOCK_START_GUARD_MS = 900L

/** 起播保护期内允许时钟超前最新报告的余量：缓冲不出声时最多多走这么多就冻结等待。 */
private const val NATIVE_CLOCK_START_HEADROOM_MS = 120f

/** 回退校正超过该值就一次性对齐（不再慢动作滑行）——保证任何时刻速度都是 1x。 */
private const val NATIVE_CLOCK_BACKWARD_SNAP_MS = 120f
// 数据锚定时钟：|偏差| 超过 SNAP 直接重锚（seek/卡顿）；否则每次报告把 base 拉近 SLEW 比例。
// 视觉轻微超前时按 GLIDE 速度降速滑行等数据追上，不回跳不冻结。
private const val NATIVE_CLOCK_BASE_SNAP_MS = 280.0
private const val NATIVE_CLOCK_JITTER_BAND_MS = 45.0
private const val NATIVE_CLOCK_BASE_SLEW = 0.35
private const val NATIVE_CLOCK_OVERRUN_GLIDE_SPEED = 0.82f
private const val NATIVE_APPLE_LINE_CLOCK_RESET_MS = 1_000f
// 前向限速追赶：gap ≤ FREE 视为稳态噪声直接贴 target；gap > SNAP（卡顿恢复级）即时对齐；
// 中间档每帧额外行程 = clamp(gap×FRACTION, dt×MIN_RATE, STEP_MAX)——单帧前跳消失，收敛 ≤0.2s。
private const val NATIVE_CLOCK_FORWARD_FREE_MS = 3f
private const val NATIVE_CLOCK_FORWARD_SNAP_MS = 300f
private const val NATIVE_CLOCK_FORWARD_STEP_MAX_MS = 14f
private const val NATIVE_CLOCK_FORWARD_MIN_RATE = 0.45f
private const val NATIVE_CLOCK_FORWARD_FRACTION = 0.18f
// 暂停/rebuffer 瞬间的回拢缓动：≤MAX 的视觉超前在 DURATION 内平滑贴回数据位置
//（不让扫色在停住的同一帧倒退一截）；≤MIN 视为已对齐、>MAX 视为真实跳位直接对齐。
private const val NATIVE_CLOCK_PAUSE_EASE_MAX_MS = 160f
private const val NATIVE_CLOCK_PAUSE_EASE_MIN_MS = 8f
private const val NATIVE_CLOCK_PAUSE_EASE_DURATION_MS = 140f
