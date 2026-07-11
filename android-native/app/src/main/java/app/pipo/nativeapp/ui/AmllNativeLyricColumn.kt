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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
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
import app.pipo.nativeapp.data.PipoLyricTimingPart
import app.pipo.nativeapp.data.effectiveDurationMs
import app.pipo.nativeapp.data.effectiveEndMs
import app.pipo.nativeapp.data.timingPartsForProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 原生 AMLL 风歌词渲染器。
 *
 * 关键取舍：
 * - 不再用 LazyColumn 滚动歌词；所有行以绝对位移排布。自动切句使用近临界阻尼跟随，
 *   保留约 350ms 的 Apple 观感，同时在快句重定向时延续速度、避免累计落后。
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
    topFadeTransparentEnd: Float = 0.08f,
    topFadePartialEnd: Float = 0.14f,
    topFadeSolidEnd: Float? = null,
    lineWidthAspect: Float = NATIVE_LINE_WIDTH_ASPECT,
    enterProgress: Float = 1f,
    // 固定歌词锚点的纵向偏移：正值=整体下移。横屏传 60dp 下移，竖屏默认 0 不变。
    anchorBiasDp: Dp = 0.dp,
    // 可选的当前行顶部上限：竖屏歌词页用它把上一行刚好推出顶部裁剪区，
    // 比固定负偏移更能适配不同屏幕高度。
    anchorTopCapDp: Dp? = null,
    // 顶部硬裁剪安全区：防止上一句是两行/有发光边缘时，在顶部残留一两像素。
    topHardClipDp: Dp = 0.dp,
    // 竖屏歌词页专用：active 上方的旧行直接不渲染，避免多行旧歌词残留。
    hideRowsAboveAnchor: Boolean = false,
    // Apple Music 移动端拥有独立于桌面/横屏的排版与颜色 token：当前行不缩放、
    // 34px 视觉字号、25%+55px 的滚动锚点，以及更强的明暗对比。
    useMobileAppleProfile: Boolean = false,
    // 按真实可用宽度排满后再换行；横屏显式开启，避免平衡排版为了两行等长
    // 而在第一行仍有空间时提前折行。移动端 Apple profile 始终自然换行。
    naturalSyllableWrap: Boolean = false,
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
    // 整列只保留一个 Apple TimeGroup 帧时钟。旧实现会为每个仍在唱/正在退场的行
    // 各启动一条 withFrameNanos 循环；重叠歌词和慢词一多，同一帧会被重复推进、重复写 State。
    // 这个共享时钟沿用原来的重锚规则，只收敛执行数量，不改变逐词 timing 或切句参数。
    val timeGroupClockState = clockState
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
    // 副词是运行时才展开的“渲染附加高度”，不能混入 mainRowHeights。
    // main prefix 保持稳定，切句 spring 始终只追主词行顶。
    val companionRowHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    // “已经出现”提升到 session/slot 层保存：行被虚拟窗口卸载后再挂载，
    // 仍与保留的 companionRowHeights 一致，不会因 seek 回退制造幽灵空隙。
    val appearedCompanions = remember(sessionKey) {
        mutableStateMapOf<NativeCompanionAppearanceKey, Boolean>()
    }
    val transFullHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    val transMaxHeights = remember(sessionKey) { mutableStateMapOf<Int, Int>() }
    val estimatedRowHeightPx = with(density) { rowMinHeight.toPx().toInt().coerceAtLeast(1) }
    val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
    val compactWidthPx = with(density) { NATIVE_COMPACT_WIDTH_DP.dp.toPx() }
    val timelineCache = remember(lines) { NativeTimelineCache(lines) }
    val nativeSlots = timelineCache.slots
    val lineToSlot = timelineCache.lineToSlot
    val slotCount = nativeSlots.size
    val currentLineScale = if (useMobileAppleProfile) {
        NATIVE_MOBILE_CURRENT_LINE_SCALE
    } else {
        NATIVE_DESKTOP_CURRENT_LINE_SCALE
    }
    val useNaturalSyllableWrap = useMobileAppleProfile || naturalSyllableWrap

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
                companionRowHeights = companionRowHeights,
                transFullHeights = transFullHeights,
                transMaxHeights = transMaxHeights,
            )
        }
    }

    fun rowHeight(index: Int): Int = rowMetrics.rowHeight(index)
    fun renderTop(index: Int): Float = rowMetrics.renderTop(index)
    // 基准坐标：剔除副词运行时高度与译文展开，仅由主词高度累加，
    // scrollSpring 全程工作在此坐标系。
    fun rowTopBase(index: Int): Float = rowMetrics.rowTopBase(index)
    fun rowAnchor(index: Int): Float {
        val safeIndex = index.coerceIn(nativeSlots.indices)
        return rowTopBase(safeIndex)
    }
    // 基准坐标位置 → 需叠加的渲染附加量（已出现副词 + 译文）：跨越某行时
    // 按主词行内进度连续计入。这样附加高度只改变真实 render 位置，
    // 不会重定向切句 spring 的主词基准目标。
    fun transOffsetForBase(basePos: Float): Float = rowMetrics.transOffsetForBase(basePos)
    // 渲染坐标 → 基准坐标：手动拖动以 1:1 手感工作在渲染坐标，松手时换算回基准交给 spring。
    fun baseForRenderCenter(renderPos: Float): Float = rowMetrics.baseForRenderCenter(renderPos)

    val initialTimelineSnapshot = remember(sessionKey, lines) {
        nativeTimelineSnapshot(
            lines = lines,
            cache = timelineCache,
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
    val singingAnchorIdx = (timelineSnapshot.activeIndices.minOrNull() ?: playbackActiveIdx)
        .coerceIn(lines.indices)
    val layoutAnchorSlotIdx = if (currentLineIdx < 0) {
        playbackActiveSlotIdx
    } else {
        slotForLine(singingAnchorIdx)
    }
    val anchorBiasPx = with(density) { anchorBiasDp.toPx() }
    val baseAnchorYPx = if (useMobileAppleProfile) {
        // Apple Web: topSpacer = hostHeight * .25，current 行再落在 scrollTopMargin(55px) 之后。
        containerHeightPx * NATIVE_MOBILE_OFFSET_RATIO +
            with(density) { NATIVE_MOBILE_SCROLL_TOP_MARGIN_DP.dp.toPx() } +
            anchorBiasPx
    } else {
        val activeLineTopLiftPx = estimatedRowHeightPx.toFloat() * NATIVE_ACTIVE_LINE_TOP_UPSHIFT_ROWS
        val activeLineExtraLiftPx = with(density) { NATIVE_ACTIVE_LINE_EXTRA_UPSHIFT_DP.dp.toPx() }
        containerHeightPx * NATIVE_ALIGN_POSITION -
            activeLineTopLiftPx -
            activeLineExtraLiftPx +
            anchorBiasPx
    }
    val anchorTopCapPx = anchorTopCapDp?.let { with(density) { it.toPx() } }
    val anchorYPx = if (anchorTopCapPx != null) {
        minOf(baseAnchorYPx, anchorTopCapPx)
    } else {
        baseAnchorYPx
    }

    val initialActiveLyricIndex = if (positionProvider != null) {
        initialTimelineSnapshot.targetIndex
    } else {
        activeLyricIndex
    }
    val initialScrollSlotIdx = slotForLine(initialActiveLyricIndex)
    val initialScrollCenter = rowAnchor(initialScrollSlotIdx)
    val scrollSpring = remember(sessionKey) {
        Animatable(initialScrollCenter)
    }
    var lastScrollTargetIdx by remember(sessionKey) { mutableStateOf(playbackActiveSlotIdx) }
    var initialLayoutSettled by remember(sessionKey) { mutableStateOf(false) }
    var lastLayoutAnchorIdx by remember(sessionKey) { mutableStateOf(layoutAnchorSlotIdx) }
    var lastLayoutAnchorCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    // 原始 Apple 式切句的真相层：下方行不是各自启动一个独立 tween，而是
    // 共享同一条 scroll spring，只按行距做小幅级联。这两个值只在目标句切换时写入，
    // 帧内进度仍直接来自 scrollSpring，不会被某一行的动画生命周期短路。
    var motionFromCenter by remember(sessionKey) { mutableFloatStateOf(initialScrollCenter) }
    var motionTargetSlotIdx by remember(sessionKey) { mutableIntStateOf(initialScrollSlotIdx) }
    val scrollTargetCenter = rowAnchor(playbackActiveSlotIdx)
    val layoutAnchorCenter = rowAnchor(layoutAnchorSlotIdx)
    val measuredRowCount = mainRowHeights.size
    var manualScrollCenterPx by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
    var manualVisualSlotIdx by remember(sessionKey) { mutableIntStateOf(-1) }
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
    fun rowScrollCenterNow(slotIndex: Int): Float {
        val manualCenter = manualScrollCenterPx
        if (manualCenter.isFinite()) return manualCenter
        val springCenter = scrollSpring.value
        val fromCenter = motionFromCenter
        val targetSlot = motionTargetSlotIdx.coerceIn(nativeSlots.indices)
        val targetCenter = rowAnchor(targetSlot)
        val travel = targetCenter - fromCenter
        if (kotlin.math.abs(travel) < 1f) {
            return springCenter + transOffsetForBase(springCenter)
        }
        val globalProgress = ((springCenter - fromCenter) / travel).coerceIn(0f, 1f)
        val rowCascadeOrder = when (val slot = nativeSlots[slotIndex.coerceIn(nativeSlots.indices)]) {
            is NativeLyricSlot.Line -> slot.lineIndex
            is NativeLyricSlot.Interlude -> slot.nextLineIndex
        }
        val targetCascadeOrder = when (val slot = nativeSlots[targetSlot]) {
            is NativeLyricSlot.Line -> slot.lineIndex
            is NativeLyricSlot.Interlude -> slot.nextLineIndex
        }
        // Interlude 是布局 slot，不是一句歌词；级联延迟继续按 lyric line 距离计数，
        // 否则每个长间奏都会额外多加一档 4.5% 延迟。
        val rowsBelow = (rowCascadeOrder - targetCascadeOrder).coerceAtLeast(0)
        val delayFraction = (rowsBelow * NATIVE_SCROLL_STAGGER_PER_ROW)
            .coerceAtMost(NATIVE_SCROLL_STAGGER_MAX)
        val rowProgress = if (delayFraction <= 0f) {
            globalProgress
        } else {
            ((globalProgress - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)
        }
        val rowCenter = fromCenter + travel * rowProgress
        return rowCenter + transOffsetForBase(rowCenter)
    }
    // 首帧定位独立成 effect：容器与首屏行测量完成后，把滚动锚到当前目标并解锁入场。
    // 关键：把"新行进入渲染窗口被测量(measuredRowCount 变化)"从下面的跟随 effect 的重启 key 里剥离。
    // 否则每测量一行就重启一次跟随 effect、把切句滚动的 tween 打断重来、永远到不了目标，
    // 滚动持续落后于当前句，十几行后当前句被推出屏幕 → 看起来"动画消失了"。
    LaunchedEffect(sessionKey, containerHeightPx, measuredRowCount) {
        if (initialLayoutSettled) return@LaunchedEffect
        if (containerHeightPx <= 0 || measuredRowCount <= 0) return@LaunchedEffect
        scrollSpring.snapTo(scrollTargetCenter)
        motionFromCenter = scrollTargetCenter
        motionTargetSlotIdx = playbackActiveSlotIdx
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
            lastLayoutAnchorIdx = layoutAnchorSlotIdx
            lastLayoutAnchorCenter = layoutAnchorCenter
            return@LaunchedEffect
        }
        if (!initialLayoutSettled) {
            // 首帧定位由上面的 effect 负责；未定位前只记录基准、不做跟随。
            lastScrollTargetIdx = playbackActiveSlotIdx
            lastLayoutAnchorIdx = layoutAnchorSlotIdx
            lastLayoutAnchorCenter = layoutAnchorCenter
            return@LaunchedEffect
        }
        val previousIdx = lastScrollTargetIdx
        val previousLayoutAnchorIdx = lastLayoutAnchorIdx
        val previousLayoutAnchorCenter = lastLayoutAnchorCenter
        if (previousIdx != playbackActiveSlotIdx) {
            motionFromCenter = scrollSpring.value
            motionTargetSlotIdx = playbackActiveSlotIdx
        }
        lastScrollTargetIdx = playbackActiveSlotIdx
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
                motionFromCenter += layoutDelta
            }
        }
        val targetIdx = playbackActiveSlotIdx.coerceIn(nativeSlots.indices)
        val targetRowHeight = rowHeight(targetIdx).toFloat()
        val targetRowTop = rowTopBase(targetIdx) + anchorYPx - scrollSpring.value
        // 只有跨越整个渲染窗口的远距离跳转（例如 seek）才瞬移；正常相邻切句即使目标行
        // 因长句暂时落在 viewport 外，也必须继续动画。旧逻辑只看 viewport，会从某个长句
        // 开始连续 snap，用户看到的就是“播放到后面切句动画消失”。
        val targetLineInvisible = containerHeightPx > 0 &&
            (targetRowTop + targetRowHeight < 0f || targetRowTop > containerHeightPx.toFloat())
        val animatedSlotRadius = if (containerHeightPx > 0) {
            containerHeightPx / estimatedRowHeightPx.coerceAtLeast(1) + NATIVE_RENDER_WINDOW_BUFFER_ROWS
        } else {
            NATIVE_INITIAL_RENDER_RADIUS_LINES
        }
        val crossesUncomposedRange = kotlin.math.abs(targetIdx - previousIdx) > animatedSlotRadius
        if (targetLineInvisible && crossesUncomposedRange) {
            scrollSpring.snapTo(scrollTargetCenter)
            motionFromCenter = scrollTargetCenter
            motionTargetSlotIdx = playbackActiveSlotIdx
            return@LaunchedEffect
        }
        if (!isPlaying) {
            scrollSpring.stop()
            motionFromCenter = scrollSpring.value
            motionTargetSlotIdx = playbackActiveSlotIdx
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
            motionFromCenter = startCenter
            motionTargetSlotIdx = playbackActiveSlotIdx
            manualScrollCenterPx = Float.NaN
            manualVisualSlotIdx = -1
            scrollSpring.snapTo(startCenter)
            scrollSpring.animateTo(
                targetValue = scrollTargetCenter,
                animationSpec = nativeManualRestoreSpringSpec(),
            )
        }
    }

    val lastSlotIndex = nativeSlots.lastIndex.coerceAtLeast(0)
    // derivedStateOf：拖动中每帧重算就近行，但只在「行号真正变化」时才通知下游（渲染窗口/焦点），
    // 把手动滚动的重组从「每帧」降到「每越过一行」；自动播放时它等于 targetIndex（切句才变）。
    val visualActiveIdx by remember(sessionKey) {
        derivedStateOf {
            val pa = timelineSnapshot.targetSlotIndex.coerceIn(nativeSlots.indices)
            val m = manualScrollCenterPx
            if (!m.isFinite()) {
                pa
            } else {
                manualVisualSlotIdx
                    .takeIf { it in 0..lastSlotIndex }
                    ?: rowMetrics.nearestRenderIndex(m)
            }
        }
    }

    // 固定“总行数”而不是固定上下半径：旧实现开头只挂载 current..+radius，越往后逐渐
    // 变成 -radius..+radius，Compose 行数和模糊图层接近翻倍，正好表现为前几句顺、后面差。
    // current 上方只保留少量离场行，其余预算留给下方待唱行；临近尾部再把空余预算回填到上方。
    val baseRenderWindowRows = (if (containerHeightPx > 0) {
        containerHeightPx / estimatedRowHeightPx.coerceAtLeast(1) + NATIVE_RENDER_WINDOW_BUFFER_ROWS
    } else {
        NATIVE_INITIAL_RENDER_RADIUS_LINES
    } + 1).coerceAtMost(nativeSlots.size)
    val earliestActiveSlot = if (manualScrollActive || isUserDragging) {
        visualActiveIdx
    } else {
        timelineSnapshot.activeIndices.minOfOrNull(::slotForLine) ?: visualActiveIdx
    }
    // 只为当前 viewport 附近的并唱行扩容；异常超长 duration 可能让一个很早的行长期留在
    // activeIndices，若不设上限会把连续窗口一路拉成整首歌。超出上限的行本来已远离屏幕，
    // 当它再次成为 current 时 visualActiveIdx 会回到它，仍会正常组合和播放动画。
    val activeBehindRows = (visualActiveIdx - earliestActiveSlot)
        .coerceIn(0, NATIVE_RENDER_WINDOW_MAX_ACTIVE_BEHIND_ROWS)
    val renderBehindRows = maxOf(NATIVE_RENDER_WINDOW_BEHIND_ROWS, activeBehindRows)
    // 正常顺播总数保持 base 不变；只有真实的多句重叠跨度更大时才按需扩一小段，
    // 同时至少留下几行待唱预算，不能为了优化把仍在唱的旧句从 composition 裁掉。
    val renderWindowRows = maxOf(
        baseRenderWindowRows,
        renderBehindRows + NATIVE_RENDER_WINDOW_MIN_AHEAD_ROWS + 1,
    ).coerceAtMost(nativeSlots.size)
    val preferredWindowStart = (visualActiveIdx - renderBehindRows).coerceAtLeast(0)
    val visibleEndIndex = (preferredWindowStart + renderWindowRows - 1).coerceAtMost(lastSlotIndex)
    val visibleStartIndex = (visibleEndIndex - renderWindowRows + 1).coerceAtLeast(0)
    val previousLyricSlotIdx = if (hideRowsAboveAnchor && currentLineIdx > 0) {
        slotForLine(currentLineIdx - 1)
    } else {
        -1
    }
    fun isHiddenAboveAnchorSlot(slotIndex: Int): Boolean {
        if (slotIndex == previousLyricSlotIdx) return false
        return hideRowsAboveAnchor &&
            !manualScrollActive &&
            !isUserDragging &&
            slotIndex < layoutAnchorSlotIdx
    }
    val initialWindowMeasured = if (containerHeightPx > 0 && nativeSlots.isNotEmpty()) {
        var ready = true
        for (slotIndex in visibleStartIndex..visibleEndIndex) {
            if (isHiddenAboveAnchorSlot(slotIndex)) continue
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
        currentLineScale = currentLineScale,
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
            useNaturalSyllableWrap = useNaturalSyllableWrap,
        )
        val hostAlignEnd = line.alignment == PipoLyricAlignment.End
        line.companionLines
            .filter { it.role == PipoLyricRole.Companion }
            .forEach { companion ->
                val companionAlignEnd = hostAlignEnd || companion.alignment == PipoLyricAlignment.End
                val companionTextAlign = if (companionAlignEnd) TextAlign.End else TextAlign.Start
                val companionFontSize = lyricFontSize * if (useMobileAppleProfile) {
                    NATIVE_MOBILE_BG_FONT_SCALE
                } else {
                    NATIVE_BG_FONT_SCALE
                }
                val companionLineHeight = lyricLineHeight * if (useMobileAppleProfile) {
                    NATIVE_MOBILE_BG_LINE_HEIGHT_SCALE
                } else {
                    NATIVE_BG_LINE_HEIGHT_SCALE
                }
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
                    useNaturalSyllableWrap = useNaturalSyllableWrap,
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
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        if (step == 0 || nativeSlots.isEmpty()) return true
        val safeLinesPerFrame = linesPerFrame.coerceAtLeast(1)
        var warmedThisFrame = 0
        var slotIndex = start
        while (
            slotIndex in 0..lastSlotIndex &&
            if (step > 0) slotIndex <= endInclusive else slotIndex >= endInclusive
        ) {
            if (!shouldContinue()) return false
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
        return true
    }

    val prewarmCenterState = rememberUpdatedState(visualActiveIdx)
    LaunchedEffect(
        sessionKey,
        sharedLineWidthPx.roundToInt(),
        lyricFontSize,
        lyricLineHeight,
        lyricFontWeight,
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
        // 只创建一个本曲 prewarm worker。旧 effect 把 visualActiveIdx 放在 key 里，
        // 每次切句都会取消正在预热的 20 帧工作；快句下永远做不完，新行只能
        // 在切句动画首帧同步 TextMeasurer。现在完成当前批次后立即追最新中心，
        // 不因句子变化取消已做的工作。
        var warmedCenter = -1
        while (isActive) {
            val center = prewarmCenterState.value.coerceIn(0, lastSlotIndex)
            if (center == warmedCenter) {
                withFrameNanos { }
                continue
            }
            withFrameNanos { }
            val forwardEnd = (center + NATIVE_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
            val backwardStart = (center - NATIVE_PREWARM_BEHIND_ROWS).coerceAtLeast(0)
            val stillCurrentAfterForward = warmPreparedSlotRange(
                start = center + 1,
                endInclusive = forwardEnd,
                step = 1,
                linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
                warmGlyphs = false,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterForward) continue
            val stillCurrentAfterBackward = warmPreparedSlotRange(
                start = center - 1,
                endInclusive = backwardStart,
                step = -1,
                linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
                warmGlyphs = false,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterBackward) continue
            val glyphForwardEnd = (center + NATIVE_GLYPH_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
            val stillCurrentAfterGlyphs = warmPreparedSlotRange(
                start = center + 1,
                endInclusive = glyphForwardEnd,
                step = 1,
                linesPerFrame = NATIVE_GLYPH_PREWARM_LINES_PER_FRAME,
                warmGlyphs = true,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterGlyphs) continue
            warmedCenter = center
        }
    }
    val topFadeSolidStop = (topFadeSolidEnd ?: if (showTranslation) 0.22f else 0.20f)
        .coerceIn(0f, 0.36f)
    val topFadePartialStop = topFadePartialEnd.coerceIn(0f, topFadeSolidStop)
    val topFadeTransparentStop = topFadeTransparentEnd.coerceIn(0f, topFadePartialStop)
    val topHardClipPx = if (useMobileAppleProfile) 0f else with(density) { topHardClipDp.toPx() }
    val bottomSolidStop = bottomFadeStart.coerceIn(0.60f, 0.96f)
    val bottomSoftStop = bottomFadeSoftEnd.coerceIn(bottomSolidStop, 0.99f)
    val mobileTopMaskStop = if (containerHeightPx > 0) {
        (with(density) { NATIVE_MOBILE_TOP_MASK_DP.dp.toPx() } / containerHeightPx.toFloat())
            .coerceIn(0.001f, 0.48f)
    } else {
        NATIVE_MOBILE_TOP_MASK_FALLBACK_RATIO
    }
    // 渐隐遮罩 Brush 只建一次（之前每帧 draw 都重新分配 colorStops 数组 + Brush）。
    val fadeMaskBrush = remember(
        useMobileAppleProfile,
        mobileTopMaskStop,
        topFadeTransparentStop,
        topFadePartialStop,
        topFadeSolidStop,
        bottomSolidStop,
        bottomSoftStop,
    ) {
        if (useMobileAppleProfile) {
            // Apple mobile: linear-gradient(180deg, transparent, #000 40px,
            // #000 50%, transparent)。旧版 8dp 硬裁会让上一句突然断掉。
            return@remember Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    mobileTopMaskStop to Color.Black,
                    0.5f to Color.Black,
                    1f to Color.Transparent,
                ),
            )
        }
        val topStops = if (topFadeSolidStop <= 0.001f) {
            arrayOf(0f to Color.Black)
        } else if (topFadeTransparentStop <= 0.001f) {
            arrayOf(
                0f to Color.Transparent,
                topFadePartialStop to Color.Black.copy(alpha = 0.38f),
                topFadeSolidStop to Color.Black,
            )
        } else {
            arrayOf(
                0f to Color.Transparent,
                topFadeTransparentStop to Color.Transparent,
                topFadePartialStop to Color.Black.copy(alpha = 0.38f),
                topFadeSolidStop to Color.Black,
            )
        }
        Brush.verticalGradient(
            colorStops = topStops + arrayOf(
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
                if (topHardClipPx > 0.5f) {
                    drawRect(
                        color = Color.Transparent,
                        size = androidx.compose.ui.geometry.Size(size.width, topHardClipPx),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
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
                                val startCenter = renderCenterNow()
                                manualScrollCenterPx = startCenter
                                manualVisualSlotIdx = rowMetrics.nearestRenderIndex(startCenter)
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
                                // Keep the hold timer edge-triggered; refreshing it on every move
                                // forces the lyric column back through composition during a drag.
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
                                if (!manualScrollCenterPx.isFinite() || kotlin.math.abs(next - manualScrollCenterPx) > 0.5f) {
                                    manualScrollCenterPx = next
                                }
                                val nextVisualSlot = rowMetrics.nearestRenderIndex(next)
                                if (manualVisualSlotIdx != nextVisualSlot) {
                                    manualVisualSlotIdx = nextVisualSlot
                                }
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
            if (isHiddenAboveAnchorSlot(slotIndex)) continue
            val slot = nativeSlots[slotIndex]
            when (slot) {
                is NativeLyricSlot.Line -> {
                    val idx = slot.lineIndex
                    val line = lines[idx]
                    key("line", slotIndex, line.startMs, line.text) {
                        val distance = kotlin.math.abs(slotIndex - visualActiveIdx)
                        val rowEnter = nativeRowEnterProgress(calibratedEnterProgress, distance)
                        // `.is-current` 负责滚动锚点；interval-active 只负责保留各自逐词
                        // timing / 慢词 motion。整行颜色不再直接读 current 布尔值，而是与下方
                        // rowScaleProvider 共用滚动 spring 的连续位置：A/B 重叠时旧行时钟不中断，
                        // 新旧行也会随原切句动画做暗↔亮交叉过渡。
                        val isCurrentLine = idx == currentLineIdx
                        // current/scroll 使用 Apple 的 +250ms lookahead；“是否还在唱”必须读取
                        // 真实播放时钟，否则结尾会提前 250ms 熄灭。derivedState 只在布尔边界
                        // 翻转时触发该行重组，不会让 60fps 时钟重组整列。
                        val isSingingLine by remember(idx, timelineCache) {
                            derivedStateOf {
                                val actualPositionMs = clockState.value.toLong()
                                timelineCache.startMs[idx] <= actualPositionMs &&
                                    timelineCache.endMs[idx] >= actualPositionMs
                            }
                        }
                        val isActive = isSingingLine || isCurrentLine
                        val isManualFocus = manualScrollActive && slotIndex == visualActiveIdx
                        val isManualInteraction = isUserDragging || manualScrollActive
                        val isFocused = idx == pendingSeekIdx || if (manualScrollActive) {
                            slotIndex == visualActiveIdx
                        } else {
                            idx == currentLineIdx
                        }
                        val hasAppleLineFocus = isActive || isManualFocus || idx == pendingSeekIdx
                        val isPast = !isActive &&
                            timelineCache.isPast(idx, timelineSnapshot.pastCount)
                        // 移动端 Apple token 的 inactive blur 默认就是 0；关闭每个旧行的实时
                        // RenderEffect，避免切句同时创建/销毁多层 blur 造成闪帧和后半首掉帧。
                        val blurTarget = if (useMobileAppleProfile) {
                            0f
                        } else {
                            nativeLineBlur(
                                hasLineFocus = hasAppleLineFocus,
                                isFirstLine = idx == 0,
                                isUserInteracting = isManualInteraction,
                            )
                        }
                        val itemAlignment = if (line.alignment == PipoLyricAlignment.End) {
                            Alignment.TopEnd
                        } else {
                            Alignment.TopStart
                        }
                        val lineWidthPx = sharedLineWidthPx
                        val lineWidthDp = with(density) { lineWidthPx.toDp() }
                        val lineContentWidthPx = lineWidthPx
                        val rowScaleAnchor = rowAnchor(slotIndex)
                        val rowScaleSpanPx = estimatedRowHeightPx.toFloat() * NATIVE_ROW_SCALE_FOCUS_SPAN_ROWS
                        // 大小变化重新变成“行到滚动焦点的距离”的连续函数。该 lambda 只在
                        // graphicsLayer 阶段读 spring，不会触发整行重组；快句重定向时也会自然
                        // 接续当前速度，不再有独立 100ms tween 提前播完后“看不到”的窗口。
                        val rowScaleProvider: () -> Float = {
                            nativeScaleFocus(
                                rowAnchor = rowScaleAnchor,
                                focusAnchor = focusCenterBaseNow(),
                                spanPx = rowScaleSpanPx,
                            )
                        }
                        val rowVisibleForClick = interactionReady &&
                            rowEnter > NATIVE_ROW_CLICK_MIN_ALPHA

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding)
                                .graphicsLayer {
                                    translationY = renderTop(slotIndex) + anchorYPx - rowScrollCenterNow(slotIndex)
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
                                                            motionFromCenter = resumeCenter
                                                            motionTargetSlotIdx = slotIndex
                                                            manualScrollCenterPx = Float.NaN
                                                            manualVisualSlotIdx = -1
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
                                    isPast = isPast,
                                    timeState = rawPositionState,
                                    clockState = clockState,
                                    timeGroupClockState = timeGroupClockState,
                                    fg = fg,
                                    fgUnsung = fgUnsung,
                                    transProgress = transProgress,
                                    enterAlpha = rowEnter,
                                    onMainHeight = { h -> if (mainRowHeights[slotIndex] != h) mainRowHeights[slotIndex] = h },
                                    onCompanionHeight = { h ->
                                        if (companionRowHeights[slotIndex] != h) companionRowHeights[slotIndex] = h
                                    },
                                    onTransFullHeight = { h -> if (transFullHeights[slotIndex] != h) transFullHeights[slotIndex] = h },
                                    onTransMaxHeight = { h -> if (transMaxHeights[slotIndex] != h) transMaxHeights[slotIndex] = h },
                                    rowMinHeight = rowMinHeight,
                                    targetBlur = blurTarget,
                                    isManualFocus = isManualFocus,
                                    isUserInteracting = isManualInteraction,
                                    fontSize = lyricFontSize,
                                    lineHeight = lyricLineHeight,
                                    fontWeight = lyricFontWeight,
                                    verticalPadding = rowVerticalPadding,
                                    lineWidthPx = lineContentWidthPx,
                                    effectsEnabled = effectsSettled,
                                    currentLineScale = currentLineScale,
                                    rowScaleProvider = rowScaleProvider,
                                    // 常规切句只让锚点前后近邻行观察 spring 并重绘颜色；
                                    // 半径 2 覆盖两句之间夹一个 interlude slot。仍在演唱的重叠行
                                    // 例外保留 provider，使 A→B→A 返回早期长句时也能从暗色跟着
                                    // 滚动 spring 恢复到亮色，而不会回落到 100ms 布尔 tween。
                                    lineColorFocusProvider = rowScaleProvider.takeIf {
                                        distance <= NATIVE_LINE_COLOR_RENDER_RADIUS_SLOTS || isActive
                                    },
                                    useMobileAppleProfile = useMobileAppleProfile,
                                    useNaturalSyllableWrap = useNaturalSyllableWrap,
                                    hasCompanionAppeared = { companionIndex, companion ->
                                        appearedCompanions[
                                            NativeCompanionAppearanceKey(
                                                slotIndex = slotIndex,
                                                companionIndex = companionIndex,
                                                startMs = companion.startMs,
                                                textHash = companion.text.hashCode(),
                                            )
                                        ] == true
                                    },
                                    onCompanionAppeared = { companionIndex, companion ->
                                        appearedCompanions[
                                            NativeCompanionAppearanceKey(
                                                slotIndex = slotIndex,
                                                companionIndex = companionIndex,
                                                startMs = companion.startMs,
                                                textHash = companion.text.hashCode(),
                                            )
                                        ] = true
                                    },
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
                                    translationY = renderTop(slotIndex) + anchorYPx - rowScrollCenterNow(slotIndex)
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
                                    isPast = slotIndex < playbackActiveSlotIdx,
                                    clockState = clockState,
                                    color = fg,
                                    enterAlpha = rowEnter,
                                    onMainHeight = { h -> if (mainRowHeights[slotIndex] != h) mainRowHeights[slotIndex] = h },
                                    fontSize = lyricFontSize,
                                    alignEnd = alignEnd,
                                    currentLinePaddingEm = if (useMobileAppleProfile) {
                                        0f
                                    } else {
                                        NATIVE_DESKTOP_CURRENT_LINE_PADDING_EM
                                    },
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
    isPast: Boolean,
    clockState: State<Float>,
    color: Color,
    enterAlpha: Float,
    onMainHeight: (Int) -> Unit,
    fontSize: TextUnit,
    alignEnd: Boolean,
    currentLinePaddingEm: Float,
) {
    val density = LocalDensity.current
    val currentLineLayoutProgress by animateFloatAsState(
        targetValue = if (isCurrent || isPast) 1f else 0f,
        animationSpec = tween(
            durationMillis = NATIVE_LINE_GEOMETRY_SWITCH_MS,
            easing = NATIVE_CSS_DEFAULT_EASE,
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
    val topMarginPx = fontPx * NATIVE_INTERLUDE_TOP_MARGIN_EM
    val currentPaddingPx = fontPx * currentLinePaddingEm * currentLineLayoutProgress
    val expandedHeightPx = (topMarginPx + dotCanvasSizePx + currentPaddingPx * 2f)
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
                alignEnd = alignEnd,
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
            val y = (topMarginPx + currentPaddingPx).roundToInt().coerceAtLeast(0)
            dotPlaceable.place(x, y)
        }
    }
}

@Composable
private fun NativeAmllLyricRow(
    line: PipoLyricLine,
    isActive: Boolean,
    isFocused: Boolean,
    isPast: Boolean,
    timeState: State<Long>,
    clockState: State<Float>,
    timeGroupClockState: State<Float>,
    fg: Color,
    fgUnsung: Color,
    transProgress: Float,
    enterAlpha: Float,
    onMainHeight: (Int) -> Unit,
    onCompanionHeight: (Int) -> Unit,
    onTransFullHeight: (Int) -> Unit,
    onTransMaxHeight: (Int) -> Unit,
    rowMinHeight: Dp,
    targetBlur: Float,
    isManualFocus: Boolean,
    isUserInteracting: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    verticalPadding: Dp,
    lineWidthPx: Float,
    effectsEnabled: Boolean,
    currentLineScale: Float,
    rowScaleProvider: () -> Float,
    lineColorFocusProvider: (() -> Float)?,
    useMobileAppleProfile: Boolean,
    useNaturalSyllableWrap: Boolean,
    hasCompanionAppeared: (Int, PipoLyricLine) -> Boolean,
    onCompanionAppeared: (Int, PipoLyricLine) -> Unit,
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
    val rowBlur = animateFloatAsState(
        targetValue = if (!effectsEnabled) 0f else targetBlur,
        animationSpec = tween(durationMillis = NATIVE_BLUR_TRANSITION_MS, easing = LinearEasing),
        label = "nativeLyricRowBlur",
    )
    val pivotX = if (line.alignment == PipoLyricAlignment.End) 1f else 0f
    val itemAlignment = if (line.alignment == PipoLyricAlignment.End) Alignment.End else Alignment.Start
    val textAlign = if (line.alignment == PipoLyricAlignment.End) TextAlign.End else TextAlign.Start
    val effectiveMainFontWeight = nativeAppleLyricFontWeight(line.text, fontWeight)
    val mainTextStyle = nativeLyricTextStyle(fontSize, lineHeight, effectiveMainFontWeight, textAlign)
    val mainPreparedKey = remember(line, lineWidthPx, mainTextStyle, textAlign, useNaturalSyllableWrap) {
        nativePreparedLyricKey(line, lineWidthPx, mainTextStyle, textAlign, useNaturalSyllableWrap)
    }
    val mainPrepared = planCache.get(mainPreparedKey)
    val displayLine = remember(
        line,
        lineWidthPx,
        mainTextStyle,
        textMeasurer,
        mainPrepared,
        useNaturalSyllableWrap,
    ) {
        mainPrepared?.displayLine ?: nativeDisplayLyricLine(
            line = nativeAppleDisplayTimedLine(line),
            containerWidthPx = lineWidthPx,
            style = mainTextStyle,
            textMeasurer = textMeasurer,
            useNaturalSyllableWrap = useNaturalSyllableWrap,
        ).also { planCache.putDisplay(mainPreparedKey, it) }
    }
    val preparedTimedPlan = mainPrepared?.timedPlan
    // 行距全部放在顶部（=4×verticalPadding；移动端 7.75×4=31dp），底部不留白：
    // 译文紧贴主歌词，且收起时尾部无空白会被 clip 透出。
    val rowTopPadPx = with(LocalDensity.current) { (verticalPadding * 4).roundToPx() }
    val rowMinHeightPx = with(LocalDensity.current) { rowMinHeight.roundToPx() }
    val backgroundVocalTopMargin = if (useMobileAppleProfile) {
        NATIVE_MOBILE_BG_MARGIN_TOP_DP.dp
    } else with(density) {
        (fontSize.toPx() * NATIVE_BG_MARGIN_TOP_EM).toDp()
    }
    val translationMaxHeightPx = with(density) {
        (if (useMobileAppleProfile) {
            NATIVE_SUBLINE_MAX_HEIGHT_WEB_PX.dp.toPx()
        } else {
            fontSize.toPx() * (NATIVE_SUBLINE_MAX_HEIGHT_WEB_PX / NATIVE_APPLE_WEB_LINE_FONT_PX)
        })
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
                val rowScale = nativeRowScale(
                    rowScaleProvider().coerceIn(0f, NATIVE_LINE_TRANSFORM_PROGRESS_MAX),
                    currentLineScale,
                )
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
                // 桌面 current scale 回落时若绕中心缩放，会顶部下移、底部上收 —— 与整列上滚叠加就成
                // 用户看到的
                // “先往下顶一下再往上滚”。改成绕行顶(0f)缩放后：当前行顶始终钉在锚点，唱完只是
                // 向上收拢、不产生任何向下位移，配合上滚连续流畅。水平仍按对齐边（左/右）作支点。
                transformOrigin = TransformOrigin(pivotX, 0f)
                // blur 改在层内读取（Modifier.blur 会在 composition 读状态：切句后的 260ms
                // 模糊动画期间每帧重组整行；这里读取只更新本层，且少一个独立 blur 层）。
                clip = false
                val blurPx = with(density) { rowBlur.value.dp.toPx() }
                renderEffect = if (blurPx > 0.05f) {
                    BlurEffect(blurPx, blurPx, TileMode.Decal)
                } else {
                    null
                }
            },
        content = {
            // slot 0：主词基准块 + 运行时副词块。二者在同一测量帧分开上报：
            // 主词进入 mainPrefix，副词只进入 render extra，绝不改 scrollSpring 的目标。
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Box(modifier = Modifier.align(itemAlignment)) {
                            NativeAmllLyricText(
                                line = displayLine,
                                sourceLine = line,
                                // 所有时间区间仍 active 的句子都保留各自 TimeGroup；重叠主唱/对唱不会因
                                // 另一句成为滚动 current 而提前停止扫色、上浮或慢词光晕。
                                isActive = isActive,
                                isFocused = isFocused,
                                isPast = isPast,
                                timeState = timeState,
                                clockState = if (line.chars.isNotEmpty() || isActive) timeGroupClockState else null,
                                fg = fg,
                                fgUnsung = fgUnsung,
                                keepFocusGradient = isManualFocus,
                                lineColorFocusProvider = lineColorFocusProvider,
                                suspendTimedDraw = isUserInteracting,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = effectiveMainFontWeight,
                                textAlign = textAlign,
                                effectsEnabled = rowEffectsEnabled,
                                // +250ms 只用来提前选中下一句并启动滚动；词级 TimeGroup 必须继续读
                                // 真实音频时间。否则句子已经提前 250ms 切换后，扫色又叠加 250ms，
                                // 短句的动效会在真正开唱前就播完。
                                wordTimelineOffsetMs = 0L,
                                useMobileAppleProfile = useMobileAppleProfile,
                                preparedPlan = preparedTimedPlan,
                                preparedKey = mainPreparedKey,
                                planCache = planCache,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        backgroundVocals.forEachIndexed { bgIndex, companion ->
                            key("bg", companion.startMs, companion.text) {
                                NativeAmllCompanionLine(
                                    companion = companion,
                                    hostLineActive = isActive,
                                    itemAlignment = itemAlignment,
                                    timeState = timeState,
                                    clockState = clockState,
                                    timeGroupClockState = timeGroupClockState,
                                    fg = fg,
                                    fgUnsung = fgUnsung,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    lineWidthPx = lineWidthPx,
                                    topMargin = if (bgIndex == 0) backgroundVocalTopMargin else 0.dp,
                                    lineColorFocusProvider = lineColorFocusProvider,
                                    effectsEnabled = rowEffectsEnabled,
                                    suspendTimedDraw = isUserInteracting,
                                    useMobileAppleProfile = useMobileAppleProfile,
                                    useNaturalSyllableWrap = useNaturalSyllableWrap,
                                    persistedAppeared = hasCompanionAppeared(bgIndex, companion),
                                    onFirstAppearance = { onCompanionAppeared(bgIndex, companion) },
                                    planCache = planCache,
                                )
                            }
                        }
                    }
                },
            ) { bodyMeasurables, bodyConstraints ->
                val mainMaxHeight = (bodyConstraints.maxHeight - rowTopPadPx).coerceAtLeast(0)
                val mainMinHeight = (rowMinHeightPx - rowTopPadPx).coerceIn(0, mainMaxHeight)
                val mainPlaceable = bodyMeasurables[0].measure(
                    bodyConstraints.copy(minHeight = mainMinHeight, maxHeight = mainMaxHeight),
                )
                val baseMainHeight = rowTopPadPx + mainPlaceable.height
                val companionMaxHeight = (bodyConstraints.maxHeight - baseMainHeight).coerceAtLeast(0)
                val companionPlaceable = bodyMeasurables[1].measure(
                    bodyConstraints.copy(minHeight = 0, maxHeight = companionMaxHeight),
                )
                val companionHeight = companionPlaceable.height
                onMainHeight(baseMainHeight)
                onCompanionHeight(companionHeight)
                layout(bodyConstraints.maxWidth, baseMainHeight + companionHeight) {
                    mainPlaceable.place(0, rowTopPadPx)
                    companionPlaceable.place(0, baseMainHeight)
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
                                useMobileAppleProfile = useMobileAppleProfile,
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val bodyPlaceable = measurables[0].measure(constraints)
        val transPlaceable = measurables.getOrNull(1)?.measure(constraints)
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
        val totalHeight = bodyPlaceable.height + collapsedTrans
        layout(width, totalHeight) {
            bodyPlaceable.place(0, 0)
            transPlaceable?.place(0, bodyPlaceable.height)
        }
    }
}

@Composable
private fun NativeAmllCompanionLine(
    companion: PipoLyricLine,
    hostLineActive: Boolean,
    itemAlignment: Alignment.Horizontal,
    timeState: State<Long>,
    clockState: State<Float>,
    timeGroupClockState: State<Float>,
    fg: Color,
    fgUnsung: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    lineWidthPx: Float,
    topMargin: Dp,
    lineColorFocusProvider: (() -> Float)?,
    effectsEnabled: Boolean,
    suspendTimedDraw: Boolean,
    useMobileAppleProfile: Boolean,
    useNaturalSyllableWrap: Boolean,
    persistedAppeared: Boolean,
    onFirstAppearance: () -> Unit,
    planCache: NativeLyricPlanCache,
) {
    val textMeasurer = rememberTextMeasurer()
    val companionStartMs = remember(companion) { nativeLineMainStartMs(companion) }
    val companionStarted by remember(companionStartMs, clockState) {
        // 使用帧外插时钟，只在越过开始边界时通知 Compose；不会受播放器低频 position tick 拖晚。
        derivedStateOf { clockState.value >= companionStartMs.toFloat() }
    }
    // 未到自己的 timing 不占位。第一次出现后锁存到该歌词行的整个生命周期：
    // 自己唱完或 host 切走都不折叠高度，副词与主词作为同一行块一起滚出屏幕，
    // 避免离场时又改 renderPrefix、叠到切句动画上。
    // 手动拖动期间若刚好越过副词 timing，等松手再展开，避免 render 总高在手指
    // 锚定旧坐标时改变；自动播放同一 composition 直接显示，不额外落后一帧。
    val revealNow = companionStarted && !suspendTimedDraw
    val companionVisible = persistedAppeared || revealNow
    LaunchedEffect(revealNow, persistedAppeared) {
        if (revealNow && !persistedAppeared) onFirstAppearance()
    }
    val targetAppear = if (companionVisible) 1f else 0f
    // alpha/translate 只在 RenderNode 图层读动画值，不让每一帧回到 composition。
    val appearAnim = animateFloatAsState(
        targetValue = targetAppear,
        animationSpec = tween(
            durationMillis = if (companionVisible) NATIVE_BG_REVEAL_MS else NATIVE_SCROLL_FOLLOW_MS,
            easing = if (companionVisible) NATIVE_BG_REVEAL_EASE else NATIVE_SCROLL_EASE_IN_OUT_QUAD,
        ),
        label = "nativeCompanionReveal",
    )
    val keepLayoutVisible = companionVisible
    // 副词不用主行的 250ms lookahead：显示和 sweep 都从它自己的真实 timing 起步。
    val wordTimelineOffsetMs = 0L
    val companionTimedActive = companionVisible && hostLineActive
    val visualCompanionPast = companionVisible && !hostLineActive
    val companionAlignment = if (companion.alignment == PipoLyricAlignment.End) Alignment.End else itemAlignment
    val companionTextAlign = if (companionAlignment == Alignment.End) TextAlign.End else TextAlign.Start
    val boxAlignment = if (companionAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart
    val companionFontSize = fontSize * if (useMobileAppleProfile) {
        NATIVE_MOBILE_BG_FONT_SCALE
    } else {
        NATIVE_BG_FONT_SCALE
    }
    val companionLineHeight = lineHeight * if (useMobileAppleProfile) {
        NATIVE_MOBILE_BG_LINE_HEIGHT_SCALE
    } else {
        NATIVE_BG_LINE_HEIGHT_SCALE
    }
    val companionFontWeight = nativeAppleLyricFontWeight(companion.text, FontWeight.Bold)
    val companionStyle = nativeLyricTextStyle(
        fontSize = companionFontSize,
        lineHeight = companionLineHeight,
        fontWeight = companionFontWeight,
        textAlign = companionTextAlign,
    )
    val companionPreparedKey = remember(
        companion,
        lineWidthPx,
        companionStyle,
        companionTextAlign,
        useNaturalSyllableWrap,
    ) {
        nativePreparedLyricKey(companion, lineWidthPx, companionStyle, companionTextAlign, useNaturalSyllableWrap)
    }
    val companionPrepared = planCache.get(companionPreparedKey)
    val displayCompanion = remember(
        companion,
        lineWidthPx,
        companionStyle,
        textMeasurer,
        companionPrepared,
        useNaturalSyllableWrap,
    ) {
        companionPrepared?.displayLine ?: nativeDisplayLyricLine(
            line = nativeAppleDisplayTimedLine(companion),
            containerWidthPx = lineWidthPx,
            style = companionStyle,
            textMeasurer = textMeasurer,
            useNaturalSyllableWrap = useNaturalSyllableWrap,
        ).also { planCache.putDisplay(companionPreparedKey, it) }
    }
    val preparedTimedPlan = companionPrepared?.timedPlan
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topMargin)
            .nativeBackgroundVocalReveal(expanded = keepLayoutVisible)
            .graphicsLayer {
                val appear = if (effectsEnabled) appearAnim.value else targetAppear
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
            // 到自己的 timing 才进入 timed timeline；自身扫完后保持显示，
            // host 离开只切到 past 色态，不淡出、不折叠高度。
            isActive = companionTimedActive,
            isPast = visualCompanionPast,
            timeState = timeState,
            clockState = if (companion.chars.isNotEmpty()) timeGroupClockState else null,
            fg = fg,
            fgUnsung = fgUnsung,
            lineColorFocusProvider = lineColorFocusProvider,
            fontSize = companionFontSize,
            lineHeight = companionLineHeight,
            fontWeight = companionFontWeight,
            textAlign = companionTextAlign,
            isBackgroundVocal = true,
            effectsEnabled = effectsEnabled,
            suspendTimedDraw = suspendTimedDraw,
            wordTimelineOffsetMs = wordTimelineOffsetMs,
            useMobileAppleProfile = useMobileAppleProfile,
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
    useMobileAppleProfile: Boolean,
) {
    val density = LocalDensity.current
    val isSupplementary = translation.role == PipoLyricRole.Romaji
    val sublineFontScale = if (isSupplementary) {
        if (useMobileAppleProfile) NATIVE_MOBILE_SUPPLEMENTARY_FONT_SCALE else NATIVE_SUPPLEMENTARY_FONT_SCALE
    } else {
        if (useMobileAppleProfile) NATIVE_MOBILE_SUBLINE_FONT_SCALE else NATIVE_SUBLINE_FONT_SCALE
    }
    val sublineLineHeightScale = if (isSupplementary) {
        if (useMobileAppleProfile) {
            NATIVE_MOBILE_SUPPLEMENTARY_LINE_HEIGHT_SCALE
        } else {
            NATIVE_SUPPLEMENTARY_LINE_HEIGHT_SCALE
        }
    } else {
        if (useMobileAppleProfile) NATIVE_MOBILE_SUBLINE_LINE_HEIGHT_SCALE else NATIVE_SUBLINE_LINE_HEIGHT_SCALE
    }
    val sublineFontSize = fontSize * sublineFontScale
    val sublineLineHeight = lineHeight * sublineLineHeightScale
    val hiddenSlidePx = with(density) {
        if (useMobileAppleProfile) {
            NATIVE_SUBLINE_HIDDEN_SLIDE_WEB_PX.dp.toPx()
        } else {
            fontSize.toPx() * (NATIVE_SUBLINE_HIDDEN_SLIDE_WEB_PX / NATIVE_APPLE_WEB_LINE_FONT_PX)
        }
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
    expanded: Boolean,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val visibleHeight = if (expanded) placeable.height else 0
    layout(placeable.width, visibleHeight) {
        if (expanded) placeable.placeRelative(0, 0)
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
    fg: Color,
    fgUnsung: Color,
    keepFocusGradient: Boolean = false,
    lineColorFocusProvider: (() -> Float)? = null,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    isBackgroundVocal: Boolean = false,
    effectsEnabled: Boolean = true,
    suspendTimedDraw: Boolean = false,
    wordTimelineOffsetMs: Long = 0L,
    useMobileAppleProfile: Boolean = false,
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
    // 词级 isActive 可能因重叠 timing 在新 current 出现后继续为 true；它只能控制
    // sweep/慢词时钟，不能再控制整行颜色。自动播放时行级颜色在 draw 阶段直接
    // 读取滚动 spring 的空间焦点；isFocused 的 100ms 动画只留给手动/点击选中回退。
    val canUseFocusGradient = !isPast || keepFocusGradient || isFocused
    val lineSungOut by remember(line) {
        derivedStateOf { timeState.value >= lineEndMs }
    }
    val focusTargetActive = isFocused && canUseFocusGradient
    val focusColorAnim = animateFloatAsState(
        targetValue = if (focusTargetActive) 1f else 0f,
        animationSpec = tween(
            // Apple line/token color transition 双向都是 0.1s；滚动/大小仍走各自 spring。
            durationMillis = NATIVE_LINE_COLOR_FADE_MS,
            easing = NATIVE_CSS_DEFAULT_EASE,
        ),
        label = "nativeLyricFocusColor",
    )
    // 慢词上浮、逐字形变的退场仍保留原来的 350ms 收束，避免为了修颜色
    // 改掉用户已经确认的切句运动细节。重叠演唱行也继续跑自己的真实 timing。
    val motionTargetActive = (isActive || isFocused) && canUseFocusGradient
    val motionFocusAnim = animateFloatAsState(
        targetValue = if (motionTargetActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (motionTargetActive) NATIVE_LINE_COLOR_FADE_MS else NATIVE_SCROLL_FOLLOW_MS,
            easing = if (motionTargetActive) NATIVE_CSS_DEFAULT_EASE else NATIVE_SCROLL_EASE_IN_OUT_QUAD,
        ),
        label = "nativeLyricMotionFocus",
    )
    // Timed draw 覆盖全部仍在唱的重叠行；唱完离场后继续保留到 focus 350ms 收束完成，
    // 让全唱完的扫色/上浮终态跟着整行滚动退场，不在 current 切换点硬切成静态文字。
    // 手动拖动到非当前播放行时，视觉焦点应该保留行色渐变；如果继续走 timed 自绘，
    // 它会用真实播放时钟计算这个远处句子的 sweep，结果常常是 0 或已结束，焦点渐变被透明文本路径盖掉。
    val manualStaticFocus = !isActive && (keepFocusGradient || isFocused)
    // 静态文本色不再离散跳变（旧的 when 分支在 fgUnsung→fg→已唱色之间瞬切，就是
    // “切句时颜色跳一下/闪一下”的来源之一）。past 仍是独立的时间事实；
    // focus 在自动切句时改为与原滚动/缩放共用一条 spring，所以 LRC 与 timed 行都是
    // 从旧行亮→暗、新行暗→亮，而不是在 current 翻转时立即换色。
    val linePast = isPast || (!isActive && !isBackgroundVocal && lineSungOut)
    val pastTargetActive = linePast
    val pastColorAnim = animateFloatAsState(
        // “已唱”是时间事实而非焦点状态：被上一句压住焦点的短插句（isPast=false 但
        // 音频已结束）也按已唱色渐暗，否则它会以未唱色示人，与静态已唱终态相接时跳色。
        // 背景人声例外：它自己的 timing 可以早于 host 主句结束；扫完后保持 sung 色，
        // 直到 host current 真正切换（isPast=true）才随整行淡出，不能自身结束就熄灭。
        targetValue = if (pastTargetActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = NATIVE_LINE_COLOR_FADE_MS,
            easing = NATIVE_CSS_DEFAULT_EASE,
        ),
        label = "nativeLyricPastColor",
    )
    val timedPlanReady = timedPlan?.segments?.isNotEmpty() == true
    // 一旦某行进入过 timed Canvas，就在它留在 composition 的期间保持同一绘制路径。
    // 旧实现会在 focus 动画归零的那一帧把 Canvas 切回 Text；两条抗锯齿/裁切路径的
    // 栅格结果略有差异，肉眼就是切句后偶发“闪一下”。这里不改变颜色/缩放曲线，
    // 只避免退场末帧替换 renderer；静止旧行也不会继续读取共享帧时钟。
    var timedRendererMounted by remember(sourceLine.startMs, sourceLine.text) {
        mutableStateOf(isActive || isPast)
    }
    LaunchedEffect(sourceLine.startMs, sourceLine.text, isActive) {
        if (isActive) timedRendererMounted = true
    }
    val spatialLineColorProvider = lineColorFocusProvider?.takeUnless { manualStaticFocus }
    val useTimedRenderer = line.chars.isNotEmpty() &&
        timedPlanReady &&
        // 待唱行进入切句邻域后就预先使用同一 timed Canvas。否则
        // current 翻转的那一帧会从整行 drawText 换成 segment/glyph 自绘，
        // 两种抗锯齿栅格的细微差异会被看成颜色闪一下。
        (timedRendererMounted || isActive || spatialLineColorProvider != null) &&
        !manualStaticFocus &&
        !suspendTimedDraw
    // 自动播放的颜色进度与 rowScale 共用同一个空间 provider。它只在 draw
    // 阶段读 spring，不会为颜色每帧重组 Text；手动/点击选中非当前行时则保留
    // focusColorAnim，避免点击确认态被远处的空间位置压暗。
    // provider 存在时从首帧就锁定在 spatial draw 路径，不等 layout 回调后
    // 再从布尔颜色 Text 切换过来。极端的新挂载帧若 layout 尚未可用，
    // 宁可保持透明到同帧测量完成，也不先用错误的亮/暗端点闪一帧。
    val useSpatialStaticRenderer = !useTimedRenderer && spatialLineColorProvider != null
    val fallbackFocusProgress = if (useTimedRenderer || useSpatialStaticRenderer) 0f else focusColorAnim.value
    val fallbackPastProgress = if (useTimedRenderer || useSpatialStaticRenderer) 0f else pastColorAnim.value
    val inactiveLineColor = nativeInactiveLineColor(
        fg = fg,
        legacyUnsung = fgUnsung,
        useMobileAppleProfile = useMobileAppleProfile,
    )
    val pastLineColor = nativePastLineColor(
        fg = fg,
        useMobileAppleProfile = useMobileAppleProfile,
    )
    val currentLineColor = nativeCurrentLineColor(
        fg = fg,
        isBackgroundVocal = isBackgroundVocal,
        useMobileAppleProfile = useMobileAppleProfile,
    )
    // 移动端 plain current=.92、inactive=.175；timed 主唱与背景人声共用 1/.35，
    // 桌面/横屏继续沿用原有颜色端点。
    val staticFallbackColor = lerp(
        lerp(inactiveLineColor, pastLineColor, fallbackPastProgress),
        currentLineColor,
        fallbackFocusProgress,
    )
    val needsTimedFallback = line.chars.isNotEmpty() &&
        !timedPlanReady &&
        !manualStaticFocus &&
        !suspendTimedDraw &&
        isActive
    val timedFallbackProgress = if (
        needsTimedFallback && !useSpatialStaticRenderer && lineEndMs > lineStartMs
    ) {
        val timedFallbackPositionMs = (
            (clockState?.value ?: timeState.value.toFloat()) + wordTimelineOffsetMs
            ).toLong()
        ((timedFallbackPositionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // TextLayout sweep plan 尚未建好时，也使用当前 profile 的同一组 gradient 端点；
    // 避免 fallback 首帧与下一帧 Canvas 扫色发生亮度跳变。
    val timedFallbackTarget = lerp(
        nativeTimedGradientUnsungColor(
            fg = fg,
            isBackgroundVocal = isBackgroundVocal,
            useMobileAppleProfile = useMobileAppleProfile,
        ),
        nativeTimedGradientSungColor(
            fg = fg,
            isBackgroundVocal = isBackgroundVocal,
            useMobileAppleProfile = useMobileAppleProfile,
        ),
        timedFallbackProgress,
    )
    val timedFallbackColor = lerp(inactiveLineColor, timedFallbackTarget, fallbackFocusProgress)
    val baseColor = when {
        useTimedRenderer || useSpatialStaticRenderer -> Color.Transparent
        needsTimedFallback -> timedFallbackColor
        else -> staticFallbackColor
    }

    Text(
        text = line.text,
        color = baseColor,
        style = style,
        // Apple Web renders every timed word as `.syllable { display:inline-block;
        // white-space:pre }`: wrapping only happens between syllable groups. We
        // already insert explicit token-boundary line breaks (mobile=natural greedy,
        // desktop=balanced), so
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
        modifier = when {
            useTimedRenderer -> Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val plan = timedPlan
                if (result == null || plan == null || plan.segments.isEmpty()) {
                    drawContent()
                } else {
                    // 关键：不读 current 切换后可能直接初始化为 1 的 tween。此处与
                    // graphicsLayer 中的行缩放读同一个 spring 位置，即使本行刚刚进入
                    // composition，也会从它相对旧锚点的暗色开始，再连续过渡到亮色。
                    val focusProgress = spatialLineColorProvider
                        ?.invoke()
                        ?.coerceIn(0f, NATIVE_LINE_TRANSFORM_PROGRESS_MAX)
                        ?: focusColorAnim.value
                    val motionProgress = motionFocusAnim.value
                    val pastProgress = pastColorAnim.value
                    if (!isActive &&
                        focusProgress <= NATIVE_SWEEP_PROGRESS_EPS &&
                        motionProgress <= NATIVE_SWEEP_PROGRESS_EPS
                    ) {
                        // 保持同一 Canvas 节点避免抗锯齿闪换，但退场完成后降级为单次
                        // 静态 drawText。旧路径会让窗口内所有 past timed 行继续遍历 segment/慢词
                        // glyph，在滚动图层刷新时白白叠加 CPU/GPU 工作。
                        drawText(
                            plan.layout,
                            color = lerp(
                                inactiveLineColor,
                                pastLineColor,
                                pastProgress.coerceIn(0f, 1f),
                            ),
                        )
                    } else {
                        val clockMs = clockState?.value ?: timeState.value.toFloat()
                        val drawPositionMs = nativeRenderPositionMs(clockMs + wordTimelineOffsetMs)
                        drawNativeTimedLyric(
                            plan = plan,
                            glyphMeasurer = glowMeasurer,
                            positionMs = drawPositionMs,
                            fg = fg,
                            fgUnsung = fgUnsung,
                            isBackgroundVocal = isBackgroundVocal,
                            // 行颜色跟随切句 spring；逐词上浮/慢词形变仍使用
                            // 自己的 motion 进度，不改原来已确认的运动手感。
                            motionScale = motionProgress,
                            lineFocusProgress = focusProgress,
                            // 保持同一 Canvas 节点时也复用静态路径的 past tween。
                            linePastProgress = pastProgress,
                            useMobileAppleProfile = useMobileAppleProfile,
                            effectsEnabled = effectsEnabled,
                        )
                    }
                }
            }
            useSpatialStaticRenderer -> Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val focusProvider = spatialLineColorProvider
                if (result == null) {
                    drawContent()
                } else {
                    val focusProgress = focusProvider()
                        .coerceIn(0f, NATIVE_LINE_TRANSFORM_PROGRESS_MAX)
                    val restingColor = lerp(
                        inactiveLineColor,
                        pastLineColor,
                        pastColorAnim.value.coerceIn(0f, 1f),
                    )
                    // timed plan 尚未 ready 的首帧仍用实时播放位置求扫色端点；
                    // 普通 LRC 直接以 currentLineColor 为亮色端点。两者都在同一
                    // draw 节点内插值，不会在切句过程替换 Text/Canvas 导致闪一帧。
                    val focusTargetColor = if (line.chars.isNotEmpty() && lineEndMs > lineStartMs) {
                        val clockMs = clockState?.value ?: timeState.value.toFloat()
                        val positionMs = clockMs + wordTimelineOffsetMs
                        val timedProgress = (
                            (positionMs - lineStartMs.toFloat()) /
                                (lineEndMs - lineStartMs).toFloat()
                            ).coerceIn(0f, 1f)
                        lerp(
                            nativeTimedGradientUnsungColor(
                                fg = fg,
                                isBackgroundVocal = isBackgroundVocal,
                                useMobileAppleProfile = useMobileAppleProfile,
                            ),
                            nativeTimedGradientSungColor(
                                fg = fg,
                                isBackgroundVocal = isBackgroundVocal,
                                useMobileAppleProfile = useMobileAppleProfile,
                            ),
                            timedProgress,
                        )
                    } else {
                        currentLineColor
                    }
                    drawText(
                        result,
                        color = lerp(restingColor, focusTargetColor, focusProgress),
                    )
                }
            }
            else -> Modifier.fillMaxWidth()
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
        fontFamily = FontFamily.SansSerif,
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
        // Apple Lyrics 的 system stack 在 CJK 行上会降到约 600，拉丁行保持 700；
        // 不让横屏/沙盒传入的 ExtraBold 把歌词压得比 Apple Music 更重。
        nativeIsCjkText(text) -> FontWeight.SemiBold
        else -> FontWeight.Bold
    }
}

private data class NativeLineBalanceChild(
    val text: String,
    val widthPx: Float,
    val isSpace: Boolean,
)

private fun nativeDisplayLyricLine(
    line: PipoLyricLine,
    containerWidthPx: Float,
    style: TextStyle,
    textMeasurer: TextMeasurer,
    useNaturalSyllableWrap: Boolean,
): PipoLyricLine {
    return if (useNaturalSyllableWrap) {
        nativeGreedyLyricLine(line, containerWidthPx, style, textMeasurer)
    } else {
        nativeBalancedLyricLine(line, containerWidthPx, style, textMeasurer)
    }
}

// Apple mobile 把每个 syllable 作为 inline-block，浏览器按可用宽度自然贪心换行；
// 不会为了让两行等长而回头重排。显式插入 token 边界换行可保持 Compose 的 timed
// segment 不被软换行拆开，同时让行高与 Apple 的 scrollTop 距离一致。
private fun nativeGreedyLyricLine(
    line: PipoLyricLine,
    containerWidthPx: Float,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): PipoLyricLine {
    val safeWidth = containerWidthPx.coerceAtLeast(1f)
    if (line.text.isBlank() || line.text.contains('\n')) return line
    val children = if (line.chars.isNotEmpty()) {
        line.chars.mapNotNull { char ->
            char.text.takeIf { it.isNotEmpty() }?.let { nativeBalanceChild(it, style, textMeasurer) }
        }
    } else {
        nativePlainBalanceChildren(line.text, style, textMeasurer)
    }
    if (children.size <= 1 || children.sumOf { it.widthPx.toDouble() } <= safeWidth) return line

    val breaks = ArrayList<Int>()
    var rowWidth = 0f
    children.forEachIndexed { index, child ->
        if (index > 0 && rowWidth > 0f && rowWidth + child.widthPx > safeWidth) {
            breaks.add(index)
            rowWidth = child.widthPx
        } else {
            rowWidth += child.widthPx
        }
    }
    if (breaks.isEmpty()) return line
    return if (line.chars.isNotEmpty()) {
        nativeApplyDynamicLineBreaks(line, breaks)
    } else {
        line.copy(text = nativeTextWithBalancedBreaks(children, breaks))
    }
}

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

private data class NativeCompanionAppearanceKey(
    val slotIndex: Int,
    val companionIndex: Int,
    val startMs: Long,
    val textHash: Int,
)

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
    val naturalSyllableWrap: Boolean,
)

private data class NativePreparedLyricLine(
    val displayLine: PipoLyricLine,
    val timedPlan: NativeTimedLyricPlan?,
    val glyphsWarmed: Boolean,
)

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
    naturalSyllableWrap: Boolean,
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
        naturalSyllableWrap = naturalSyllableWrap,
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
    useNaturalSyllableWrap: Boolean,
): Boolean {
    val key = nativePreparedLyricKey(
        sourceLine,
        lineWidthPx,
        style,
        textAlign,
        useNaturalSyllableWrap,
    )
    val needsTimedPlan = sourceLine.chars.isNotEmpty()
    val needsGlyphWarm = warmGlyphs && needsTimedPlan
    if (cache.isReady(key, needsTimedPlan = needsTimedPlan, needsGlyphWarm = needsGlyphWarm)) return false
    val prepared = cache.get(key)
    val displayLine = prepared?.displayLine ?: nativeDisplayLyricLine(
        line = nativeAppleDisplayTimedLine(sourceLine),
        containerWidthPx = lineWidthPx,
        style = style,
        textMeasurer = textMeasurer,
        useNaturalSyllableWrap = useNaturalSyllableWrap,
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
    val cssPx: Float,
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
    val segSlowLetterTimingParts: Array<List<PipoLyricTimingPart>?>,
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
    val cssPx = with(density) { 1.dp.toPx() }
    val fadeWidth = nativeWordFadeWidth(layout, fontPx)
    val segClipLeft = FloatArray(segments.size)
    val segClipRight = FloatArray(segments.size)
    val segGlowClipLeft = FloatArray(segments.size)
    val segGlowClipRight = FloatArray(segments.size)
    // 悬伸/抗锯齿裁切余量在排版期一次性烘焙进段裁切带，并在函数内夹到词间留白中点，
    // 避免逐帧绘制时再对相邻段无条件加 pad 造成裁切带重叠（上浮错相位重影）。
    val glyphClipPad = fontPx * NATIVE_GLYPH_HORIZONTAL_CLIP_PAD_EM
    val slowGlowClipPad = nativeSlowGlowClipPad(density)
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
    val segSlow = FloatArray(segments.size) { idx ->
        nativeSlowWordAmount(
            displayToken = segments[idx].timing,
            sourceToken = segments[idx].sourceTiming,
        )
    }
    // timingPartsForProgress() 会 filter/sorted；慢词逐帧调用会形成稳定分配和 GC。
    // 这组数据只取决于歌词源，在 plan 构建/预热时算一次，绘制结果完全不变。
    val segSlowLetterTimingParts = Array<List<PipoLyricTimingPart>?>(segments.size) { idx ->
        if (segSlow[idx] > 0f) nativeSlowLetterTimingParts(segments[idx].sourceTiming) else null
    }
    return NativeTimedLyricPlan(
        layout = layout,
        segments = segments,
        fontPx = fontPx,
        cssPx = cssPx,
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
        segSlow = segSlow,
        segSlowLetterTimingParts = segSlowLetterTimingParts,
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

private fun nativeSlowGlowClipPad(density: Density): Float {
    return with(density) {
        (NATIVE_SLOW_SHADOW_PEAK_WEB_PX * NATIVE_SLOW_SHADOW_CLIP_RADIUS_MULTIPLIER).dp.toPx()
    }
}

private fun DrawScope.drawNativeTimedLyric(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    positionMs: Long,
    fg: Color,
    fgUnsung: Color,
    isBackgroundVocal: Boolean,
    motionScale: Float,
    lineFocusProgress: Float,
    linePastProgress: Float,
    useMobileAppleProfile: Boolean,
    effectsEnabled: Boolean,
) {
    val layout = plan.layout
    val segments = plan.segments
    val inactiveLineColor = nativeInactiveLineColor(fg, fgUnsung, useMobileAppleProfile)
    val restingLineColor = lerp(
        inactiveLineColor,
        nativePastLineColor(fg, useMobileAppleProfile),
        linePastProgress.coerceIn(0f, 1f),
    )
    if (segments.isEmpty()) {
        val target = nativeCurrentLineColor(fg, isBackgroundVocal, useMobileAppleProfile)
        drawText(layout, color = lerp(restingLineColor, target, lineFocusProgress.coerceIn(0f, 1f)))
        return
    }
    // 移动端主词与副词共用 1/.35 的扫色端点；副词只在字号和自身出现时机上区分。
    val focusProgress = lineFocusProgress.coerceIn(0f, 1f)
    // isPast 翻转不能直接替换 endpoint；否则最后一个尚未唱完的 token 会从
    // unsung 色瞬间跳到 past 色。整行只通过 focus/past 两条 100ms 进度连续过渡。
    val activeUnsungTarget = nativeTimedGradientUnsungColor(
        fg,
        isBackgroundVocal,
        useMobileAppleProfile,
    )
    val activeUnsung = lerp(restingLineColor, activeUnsungTarget, focusProgress)
    val activeSung = lerp(
        restingLineColor,
        nativeTimedGradientSungColor(fg, isBackgroundVocal, useMobileAppleProfile),
        focusProgress,
    )
    drawNativeSegmentSweepText(
        plan = plan,
        glyphMeasurer = glyphMeasurer,
        fg = activeSung,
        activeUnsung = activeUnsung,
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
    positionMs: Long,
    motionScale: Float,
    effectsEnabled: Boolean,
) {
    val layout = plan.layout
    val segments = plan.segments
    val wordLiftPx = plan.cssPx * NATIVE_WORD_LIFT_DP
    val mScale = motionScale.coerceIn(0f, 1f)
    val sungLiftPx = -wordLiftPx * mScale

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
        // Apple Web 在当前行 manageAnimations() 时会先把 `.emphasis` 拆成 `.letter`：
        // 相邻字母的 gradient / shadow / 轻微 shape 包络错相重叠，形成连续接力。
        // Android 保留同一单层字形路径，避免整词统一 zoom 或额外 overlay 抖动。
        if (effectsEnabled && slowAmount > 0f) {
            flushStaticBatch()
            drawNativeSlowSegmentText(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                segmentIndex = index,
                slowAmount = slowAmount,
                letterTimingParts = plan.segSlowLetterTimingParts[index],
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
        val liftPx = if (kind == 2) sungLiftPx else -wordLiftPx * liftT * mScale
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
    alignEnd: Boolean,
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
        val startX = if (alignEnd) {
            size.width - groupWidth + radius
        } else {
            radius
        }
        val centerY = size.height / 2f
        val centers = floatArrayOf(
            startX,
            startX + dot + gap,
            startX + (dot + gap) * 2f,
        )
        val pivot = if (alignEnd) Offset(size.width, centerY) else Offset(0f, centerY)
        scale(progress.scale, pivot = pivot) {
            centers.forEachIndexed { idx, centerX ->
                drawCircle(
                    color = color.copy(alpha = progress.dotAlphas[idx] * progress.alpha),
                    radius = radius,
                    center = Offset(centerX, centerY),
                )
            }
        }
    }
}

private data class NativeInterludeProgress(
    val alpha: Float,
    val scale: Float,
    val dotAlphas: FloatArray,
)

private fun nativeInterludeProgress(
    interlude: NativeLyricSlot.Interlude,
    positionMs: Long,
): NativeInterludeProgress {
    val duration = (interlude.endMs - interlude.startMs).coerceAtLeast(1L).toFloat()
    val rawCurrent = (positionMs - interlude.startMs).toFloat()
    val current = rawCurrent.coerceIn(0f, duration)
    val alpha = if (rawCurrent >= duration) {
        0f
    } else {
        1f - nativeAppleInterludeEndCollapse01(current, duration)
    }
    val dotStepMs = (duration / NATIVE_INTERLUDE_DOT_COUNT).coerceAtLeast(1f)
    val dot0 = nativeAppleInterludeDotAlpha(current, dotStepMs, 0)
    val dot1 = nativeAppleInterludeDotAlpha(current, dotStepMs, 1)
    val dot2 = nativeAppleInterludeDotAlpha(current, dotStepMs, 2)
    return NativeInterludeProgress(
        alpha = alpha,
        scale = nativeAppleInterludeScale(
            current = current,
            duration = duration,
            isCurrent = rawCurrent >= 0f && rawCurrent < duration,
        ),
        dotAlphas = floatArrayOf(dot0.coerceIn(0f, 1f), dot1.coerceIn(0f, 1f), dot2.coerceIn(0f, 1f)),
    )
}

private fun nativeAppleInterludeScale(
    current: Float,
    duration: Float,
    isCurrent: Boolean,
): Float {
    if (!isCurrent) return 1f
    val remaining = duration - current
    // Apple Music interlude preview: current runs a slow heartbeat; the ending
    // swells from the heartbeat's exact boundary scale, then collapses right as
    // Apple's +250ms current-line lookahead hands focus to the next lyric.
    return if (remaining < NATIVE_INTERLUDE_ENDING_MS) {
        val endingElapsed = (NATIVE_INTERLUDE_ENDING_MS - remaining).coerceAtLeast(0f)
        if (endingElapsed <= NATIVE_INTERLUDE_END_GROW_MS) {
            val t = NATIVE_CSS_EASE_IN.transform((endingElapsed / NATIVE_INTERLUDE_END_GROW_MS).coerceIn(0f, 1f))
            val startScale = nativeAppleInterludeHeartbeatScale(
                (duration - NATIVE_INTERLUDE_ENDING_MS).coerceAtLeast(0f),
            )
            startScale + (NATIVE_INTERLUDE_END_SCALE_PEAK - startScale) * t
        } else {
            val collapseRaw = ((endingElapsed - NATIVE_INTERLUDE_END_GROW_MS) / NATIVE_INTERLUDE_END_COLLAPSE_MS)
                .coerceIn(0f, 1f)
            val t = 1f - (1f - collapseRaw) * (1f - collapseRaw)
            NATIVE_INTERLUDE_END_SCALE_PEAK * (1f - t)
        }
    } else {
        nativeAppleInterludeHeartbeatScale(current)
    }.coerceIn(0f, NATIVE_INTERLUDE_MAX_SCALE)
}

private fun nativeAppleInterludeHeartbeatScale(current: Float): Float {
    val phase = nativeCyclicPhase(current, NATIVE_INTERLUDE_HEARTBEAT_MS)
    return if (phase <= 0.5f) {
        val t = NATIVE_CSS_EASE_IN.transform((phase / 0.5f).coerceIn(0f, 1f))
        1f + (NATIVE_INTERLUDE_HEARTBEAT_PEAK_SCALE - 1f) * t
    } else {
        val t = NATIVE_CSS_EASE_IN.transform(((phase - 0.5f) / 0.5f).coerceIn(0f, 1f))
        NATIVE_INTERLUDE_HEARTBEAT_PEAK_SCALE -
            (NATIVE_INTERLUDE_HEARTBEAT_PEAK_SCALE - 1f) * t
    }
}

private fun nativeCyclicPhase(elapsedMs: Float, periodMs: Float): Float {
    if (periodMs <= 0f) return 1f
    return ((elapsedMs % periodMs) / periodMs).coerceIn(0f, 1f)
}

private fun nativeAppleInterludeEndCollapse01(current: Float, duration: Float): Float {
    val endingElapsed = (current - (duration - NATIVE_INTERLUDE_ENDING_MS)).coerceAtLeast(0f)
    val collapseRaw = ((endingElapsed - NATIVE_INTERLUDE_END_GROW_MS) / NATIVE_INTERLUDE_END_COLLAPSE_MS)
        .coerceIn(0f, 1f)
    return 1f - (1f - collapseRaw) * (1f - collapseRaw)
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
    letterTimingParts: List<PipoLyricTimingPart>?,
    motionPositionMs: Long,
    fg: Color,
    activeUnsung: Color,
    motionScale: Float,
) {
    // Apple Web emphasis 的真实运行态是每个 `.letter` 独立 keyframe：相邻字母约半秒
    // 错相重叠，前一个回落时后一个已经抬起。这里也按 glyph-local pivot 绘制；但移动端
    // 34sp 比 Web 22px 大，scale 按绝对视觉增量归一，避免 5% 在大字号上读成逐字弹跳。
    val layout = plan.layout
    val segment = plan.segments[segmentIndex]
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
    // 这些是 CSS 固定 px keyframe，不应随移动端字号从 22 放到 34 后再同比放大。
    val cssPxToLocal = plan.cssPx
    val logicalFontPx = (plan.fontPx / cssPxToLocal.coerceAtLeast(0.001f)).coerceAtLeast(1f)
    val letterScaleNormalization = (NATIVE_APPLE_WEB_LINE_FONT_PX / logicalFontPx).coerceIn(0f, 1f)
    val letterStepMs = segDurationMs / segment.letterUnits.coerceAtLeast(1).toFloat()
    var glyphOrdinal = segment.letterOrdinalStart
    for (i in segment.startChar until segment.endChar) {
        if (i >= plan.glyphBoxLeft.size) break
        if (nativeAppleStripsSyllableChar(text[i])) continue
        val boxLeft = plan.glyphBoxLeft[i]
        if (boxLeft.isNaN()) continue
        val boxRight = plan.glyphBoxRight[i]
        val letterStartMs = nativeSlowLetterStartMs(
            segment = segment,
            timingParts = letterTimingParts,
            glyphOrdinal = glyphOrdinal,
            fallbackStepMs = letterStepMs,
        )
        glyphOrdinal++
        val letterElapsedMs = motionPositionMs.toFloat() - letterStartMs
        val letterScaleValue: Float
        val letterTranslateYPx: Float
        if (letterElapsedMs <= 0f) {
            letterScaleValue = 1f
            letterTranslateYPx = 0f
        } else if (letterElapsedMs < firstPhaseMs) {
            val shapeT = nativeSmoothStep((letterElapsedMs / firstPhaseMs).coerceIn(0f, 1f))
            letterScaleValue = 1f +
                (NATIVE_SLOW_SCALE_PEAK - 1f) * shapeT * empGain * letterScaleNormalization
            letterTranslateYPx = -NATIVE_SLOW_LIFT_PEAK_WEB_PX * cssPxToLocal * shapeT * empGain
        } else if (letterElapsedMs < totalPhaseMs) {
            val shapeT = nativeSmoothStep(
                ((letterElapsedMs - firstPhaseMs) / (totalPhaseMs - firstPhaseMs)).coerceIn(0f, 1f),
            )
            letterScaleValue = 1f +
                (NATIVE_SLOW_SCALE_PEAK - 1f) * (1f - shapeT) * empGain * letterScaleNormalization
            letterTranslateYPx = -(
                NATIVE_SLOW_LIFT_PEAK_WEB_PX +
                    (NATIVE_SLOW_LIFT_SETTLE_WEB_PX - NATIVE_SLOW_LIFT_PEAK_WEB_PX) * shapeT
                ) * cssPxToLocal * empGain
        } else {
            letterScaleValue = 1f
            letterTranslateYPx = -NATIVE_SLOW_LIFT_SETTLE_WEB_PX * cssPxToLocal * empGain
        }
        val gradientPercent: Float
        val shadowBlurPx: Float
        val shadowOpacity: Float
        if (letterElapsedMs <= 0f) {
            gradientPercent = -20f
            shadowBlurPx = 0f
            shadowOpacity = 0f
        } else if (letterElapsedMs < firstPhaseMs) {
            val t = (letterElapsedMs / firstPhaseMs).coerceIn(0f, 1f)
            gradientPercent = (-20f + (90f + 20f) * t).coerceIn(-20f, 100f)
            shadowBlurPx = NATIVE_SLOW_SHADOW_PEAK_WEB_PX * cssPxToLocal * t
            shadowOpacity = (NATIVE_SLOW_SHADOW_PEAK_ALPHA * t * empGain)
                .coerceIn(0f, NATIVE_SLOW_SHADOW_PEAK_ALPHA)
        } else if (letterElapsedMs < totalPhaseMs) {
            val t = ((letterElapsedMs - firstPhaseMs) / (totalPhaseMs - firstPhaseMs)).coerceIn(0f, 1f)
            gradientPercent = (90f + (100f - 90f) * t).coerceIn(-20f, 100f)
            shadowBlurPx = (NATIVE_SLOW_SHADOW_PEAK_WEB_PX +
                (NATIVE_SLOW_SHADOW_SETTLE_WEB_PX - NATIVE_SLOW_SHADOW_PEAK_WEB_PX) * t) * cssPxToLocal
            shadowOpacity = (NATIVE_SLOW_SHADOW_PEAK_ALPHA * (1f - t) * empGain)
                .coerceIn(0f, NATIVE_SLOW_SHADOW_PEAK_ALPHA)
        } else {
            gradientPercent = 100f
            shadowBlurPx = NATIVE_SLOW_SHADOW_SETTLE_WEB_PX * cssPxToLocal
            shadowOpacity = 0f
        }
        val glyphCenter = (boxLeft + boxRight) * 0.5f
        val glyphWidth = (boxRight - boxLeft).coerceAtLeast(1f)
        // 普通 syllable 的 sweep 过渡带按整段宽度计算；慢词如果按单个 glyph
        // 宽度计算，20% fade 会窄到近似硬切，颜色变化就会出现明显分界线。
        // 这里保留逐字母时间轴，但过渡带复用所在词段的宽度，并用同一套
        // progress -> solid/ramp 公式保证 -20% 时全未唱、100% 时全已唱。
        val segmentWidth = (segment.right - segment.left).coerceAtLeast(glyphWidth)
        val sweepFadeWidth = (segmentWidth * NATIVE_APPLE_SWEEP_LEAD_RATIO)
            .coerceAtLeast(glyphWidth * NATIVE_APPLE_SWEEP_LEAD_RATIO)
            .coerceAtLeast(1f)
        val sweepProgress = ((gradientPercent + NATIVE_APPLE_SWEEP_LEAD_RATIO * 100f) /
            (NATIVE_APPLE_SWEEP_TRAVEL_RATIO * 100f)).coerceIn(0f, 1f)
        val solidX = boxLeft + sweepProgress * (glyphWidth + sweepFadeWidth) - sweepFadeWidth
        val rampEndX = solidX + sweepFadeWidth
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

        if (letterScaleValue <= 1.0005f) {
            drawGlyph(
                lineTopLeft = Offset(0f, letterTranslateYPx),
            )
        } else {
            translate(left = 0f, top = letterTranslateYPx) {
                scale(
                    scaleX = letterScaleValue,
                    scaleY = letterScaleValue,
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

// 行级缩放的原始 Apple 路径：只有最贴近滚动焦点的行取得完整放大，
// 上下两侧都随距离平滑回落。它直接依赖 scrollSpring 的连续位置，不依赖
// isActive/isPast 这类会被重叠 timing 延长的布尔状态。
private fun nativeScaleFocus(
    rowAnchor: Float,
    focusAnchor: Float,
    spanPx: Float,
): Float {
    val span = spanPx.coerceAtLeast(1f)
    val raw = (1f - kotlin.math.abs(rowAnchor - focusAnchor) / span).coerceIn(0f, 1f)
    return nativeSmoothStep(raw)
}

private fun nativeRowScale(positionFocus: Float, currentLineScale: Float): Float {
    return 1f + (currentLineScale - 1f) *
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
        // Apple 的 gradient 宽度来自整个 `.syllable` inline box，不是 glyph ink bounds。
        // Android 旧版只合并 getBoundingBox() 墨迹，窄字/标点的 20% 过渡带会缩成硬切。
        // 这里使用每个字符的 advance 区间，并把负悬伸墨迹并入安全边界；尾随空白仍
        // 不属于 syllable（Apple 把词间距放在 outer group 的 ::after）。
        if (i in text.indices && (text[i].isWhitespace() || text[i].isISOControl())) continue
        val advanceStart = layout.getHorizontalPosition(i, usePrimaryDirection = true)
        val advanceEnd = layout.getHorizontalPosition((i + 1).coerceAtMost(text.length), usePrimaryDirection = true)
        val box = layout.getBoundingBox(i)
        val glyphLeft = minOf(advanceStart, advanceEnd, box.left)
        val glyphRight = maxOf(advanceStart, advanceEnd, box.right)
        if (glyphRight <= glyphLeft) continue
        left = minOf(left, glyphLeft)
        right = maxOf(right, glyphRight)
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

private fun nativeSlowLetterTimingParts(token: PipoLyricChar): List<PipoLyricTimingPart>? {
    val parts = token.timingPartsForProgress()
    if (parts.size <= 1) return null
    val tokenUnits = nativeAppleLetterTimelineUnits(token.text)
    val partUnits = parts.sumOf { part -> nativeAppleLetterTimelineUnits(part.text) }
    if (partUnits < (tokenUnits * 0.75f).toInt().coerceAtLeast(1)) return null

    var ordered = true
    var previousStart = Long.MIN_VALUE
    var previousEnd: Long? = null
    var maxGap = 0L
    parts.forEachIndexed { index, part ->
        if (part.startMs < previousStart) ordered = false
        val end = part.startMs + nativeTimingPartEffectiveDurationMs(parts, index)
        previousEnd?.let { maxGap = maxOf(maxGap, part.startMs - it) }
        previousStart = part.startMs
        previousEnd = end
    }
    if (!ordered) return null

    val tokenEnd = token.startMs + token.durationMs.coerceAtLeast(1L)
    val firstStart = parts.first().startMs
    val lastEnd = previousEnd ?: tokenEnd
    val coverage = (lastEnd - firstStart).coerceAtLeast(1L).toFloat() /
        token.durationMs.coerceAtLeast(1L).toFloat()
    val reliable =
        firstStart >= token.startMs - NATIVE_SLOW_TIMING_PART_START_SLOP_MS &&
            lastEnd <= tokenEnd + NATIVE_SLOW_TIMING_PART_END_SLOP_MS &&
            coverage in NATIVE_SLOW_TIMING_PART_MIN_COVERAGE..NATIVE_SLOW_TIMING_PART_MAX_COVERAGE &&
            maxGap <= maxOf(NATIVE_SLOW_TIMING_PART_MAX_GAP_MS, token.durationMs / 2L)
    return parts.takeIf { reliable }
}

private fun nativeSlowLetterStartMs(
    segment: NativeLyricSegment,
    timingParts: List<PipoLyricTimingPart>?,
    glyphOrdinal: Int,
    fallbackStepMs: Float,
): Float {
    if (timingParts.isNullOrEmpty()) {
        return segment.slowStartMs + fallbackStepMs * glyphOrdinal
    }

    var consumedUnits = 0
    timingParts.forEachIndexed { index, part ->
        val partUnits = nativeAppleLetterTimelineUnits(part.text)
        if (glyphOrdinal < consumedUnits + partUnits) {
            val localOrdinal = glyphOrdinal - consumedUnits
            val partStepMs = nativeTimingPartEffectiveDurationMs(timingParts, index).toFloat() /
                partUnits.coerceAtLeast(1).toFloat()
            return part.startMs + partStepMs * localOrdinal
        }
        consumedUnits += partUnits
    }
    return segment.slowStartMs + fallbackStepMs * glyphOrdinal
}

private fun nativeTimingPartEffectiveDurationMs(
    parts: List<PipoLyricTimingPart>,
    index: Int,
): Long {
    val part = parts[index]
    val rawDuration = part.durationMs.coerceAtLeast(1L)
    val nextStartMs = parts.getOrNull(index + 1)?.startMs
    return nextStartMs
        ?.let { (it - part.startMs).coerceAtLeast(1L).coerceAtMost(rawDuration) }
        ?: rawDuration
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

private fun nativeInactiveLineColor(
    fg: Color,
    legacyUnsung: Color,
    useMobileAppleProfile: Boolean,
): Color {
    return if (useMobileAppleProfile) {
        fg.copy(alpha = fg.alpha * NATIVE_MOBILE_INACTIVE_ALPHA)
    } else {
        legacyUnsung
    }
}

private fun nativePastLineColor(
    fg: Color,
    useMobileAppleProfile: Boolean,
): Color {
    return if (useMobileAppleProfile) {
        fg.copy(alpha = fg.alpha * NATIVE_MOBILE_INACTIVE_ALPHA)
    } else {
        nativeSolidLineColor(fg)
    }
}

private fun nativeCurrentLineColor(
    fg: Color,
    isBackgroundVocal: Boolean,
    useMobileAppleProfile: Boolean,
): Color {
    val alpha = when {
        !useMobileAppleProfile -> NATIVE_GRADIENT_ACTIVE_ALPHA
        isBackgroundVocal -> NATIVE_MOBILE_CURRENT_ALPHA
        else -> NATIVE_MOBILE_CURRENT_ALPHA
    }
    return fg.copy(alpha = fg.alpha * alpha)
}

private fun nativeTimedGradientSungColor(
    fg: Color,
    isBackgroundVocal: Boolean,
    useMobileAppleProfile: Boolean,
): Color {
    val alpha = when {
        !useMobileAppleProfile -> NATIVE_GRADIENT_ACTIVE_ALPHA
        isBackgroundVocal -> NATIVE_MOBILE_SUNG_ALPHA
        else -> NATIVE_MOBILE_SUNG_ALPHA
    }
    return fg.copy(alpha = fg.alpha * alpha)
}

private fun nativeTimedGradientUnsungColor(
    fg: Color,
    isBackgroundVocal: Boolean,
    useMobileAppleProfile: Boolean,
): Color {
    val alpha = when {
        !useMobileAppleProfile -> NATIVE_GRADIENT_INACTIVE_ALPHA
        isBackgroundVocal -> NATIVE_MOBILE_UNSUNG_ALPHA
        else -> NATIVE_MOBILE_UNSUNG_ALPHA
    }
    return fg.copy(alpha = fg.alpha * alpha)
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

private data class NativeTimelineSnapshot(
    val targetIndex: Int,
    val targetSlotIndex: Int,
    val currentLineIndex: Int,
    val activeIndices: Set<Int>,
    val pastCount: Int,
    val activeRevision: Long,
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
    private val sortedStartOrder = lines.indices
        .sortedWith(compareBy<Int> { startMs[it] }.thenBy { it })
        .toIntArray()
    private val sortedStartMs = LongArray(lines.size) { rank -> startMs[sortedStartOrder[rank]] }
    private val sortedEndOrder = lines.indices
        .sortedWith(compareBy<Int> { endMs[it] }.thenBy { it })
        .toIntArray()
    private val sortedEndMs = LongArray(lines.size) { rank -> endMs[sortedEndOrder[rank]] }
    private val endRank = IntArray(lines.size).also { ranks ->
        sortedEndOrder.forEachIndexed { rank, lineIndex -> ranks[lineIndex] = rank }
    }
    private val slotPlan = nativeBuildLyricSlots(lines, startMs, endMs)
    val slots: List<NativeLyricSlot> = slotPlan.slots
    val slotStartMs = LongArray(slots.size) { idx -> slots[idx].startMs }
    val lineToSlot: IntArray = slotPlan.lineToSlot
    val activeLines = java.util.TreeSet<Int>()
    var activeRevision: Long = 0L
        private set
    var pastCount: Int = 0
        private set
    private var startedCount = 0
    private var lastTargetPositionMs = Long.MIN_VALUE
    var lastSnapshot: NativeTimelineSnapshot? = null

    // 正常播放只跨过极少 start/end 事件，更新成本 O(events * log(active))；
    // seek/回退也反向撤销跨过的事件。不会因为一条超长歌词把每一帧退化为全表扫描。
    fun updateActiveLines(targetPositionMs: Long) {
        if (targetPositionMs >= lastTargetPositionMs) {
            while (startedCount < sortedStartMs.size && sortedStartMs[startedCount] <= targetPositionMs) {
                val lineIndex = sortedStartOrder[startedCount]
                startedCount++
                if (endMs[lineIndex] >= targetPositionMs) {
                    if (activeLines.add(lineIndex)) activeRevision++
                }
            }
            while (pastCount < sortedEndMs.size && sortedEndMs[pastCount] < targetPositionMs) {
                if (activeLines.remove(sortedEndOrder[pastCount])) activeRevision++
                pastCount++
            }
        } else {
            while (startedCount > 0 && sortedStartMs[startedCount - 1] > targetPositionMs) {
                startedCount--
                if (activeLines.remove(sortedStartOrder[startedCount])) activeRevision++
            }
            while (pastCount > 0 && sortedEndMs[pastCount - 1] >= targetPositionMs) {
                pastCount--
                val lineIndex = sortedEndOrder[pastCount]
                if (startMs[lineIndex] <= targetPositionMs && endMs[lineIndex] >= targetPositionMs) {
                    if (activeLines.add(lineIndex)) activeRevision++
                }
            }
        }
        lastTargetPositionMs = targetPositionMs
    }

    fun isPast(lineIndex: Int, pastCount: Int): Boolean {
        return lineIndex in endRank.indices && endRank[lineIndex] < pastCount
    }
}

private fun nativeTimelineSnapshot(
    lines: List<PipoLyricLine>,
    cache: NativeTimelineCache,
    targetPositionMs: Long,
): NativeTimelineSnapshot {
    if (lines.isEmpty()) return NativeTimelineSnapshot(0, 0, -1, emptySet(), 0, 0L)
    val fallbackSlotIndex = nativeTargetSlotIndex(cache, targetPositionMs)
    val fallbackSlot = cache.slots[fallbackSlotIndex]

    // Apple Web 不是“最后一个已开始的索引”状态机。它先用 playback+250ms 找出
    // begin <= ledTime <= end 的全部行，再以源顺序中最后一行作为 `.is-current`。
    // 这使 A[0,10s] 被 B[5,6s] 插入时能稳定走 A -> B -> A，而不是一旦到 B 就永不回头。
    cache.updateActiveLines(targetPositionMs)
    val activeCount = cache.activeLines.size
    val currentLineIndex = if (activeCount > 0) {
        cache.activeLines.last()
    } else {
        when (fallbackSlot) {
            is NativeLyricSlot.Line -> if (targetPositionMs < fallbackSlot.startMs) {
                -1
            } else {
                fallbackSlot.lineIndex
            }
            is NativeLyricSlot.Interlude -> -1
        }
    }
    val targetSlotIndex = if (currentLineIndex >= 0) {
        cache.lineToSlot[currentLineIndex].coerceIn(cache.slots.indices)
    } else {
        fallbackSlotIndex
    }
    val target = if (currentLineIndex >= 0) {
        currentLineIndex
    } else {
        when (fallbackSlot) {
            is NativeLyricSlot.Line -> fallbackSlot.lineIndex
            is NativeLyricSlot.Interlude -> fallbackSlot.nextLineIndex
        }
    }.coerceIn(lines.indices)
    // end 与 ledTime 相等的最后一帧仍属于 active；只有 end < ledTime 才是 past。
    val pastCount = cache.pastCount

    val last = cache.lastSnapshot
    if (last != null &&
        last.targetIndex == target &&
        last.targetSlotIndex == targetSlotIndex &&
        last.currentLineIndex == currentLineIndex &&
        last.pastCount == pastCount &&
        last.activeRevision == cache.activeRevision
    ) {
        return last
    }
    val active = LinkedHashSet<Int>(cache.activeLines)
    val snapshot = NativeTimelineSnapshot(
        targetIndex = target,
        targetSlotIndex = targetSlotIndex,
        currentLineIndex = currentLineIndex,
        activeIndices = active,
        pastCount = pastCount,
        activeRevision = cache.activeRevision,
    )
    cache.lastSnapshot = snapshot
    return snapshot
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

// 自动播放的切句跟随必须在快句重定向时保留速度。固定 350ms tween 每次都会从当前位置
// 重新计时，连续短句会逐步落后，最后触发 snap，看起来像“后半首动画消失”。近临界阻尼
// spring 无回摆、观感约 350ms，但能持续收敛到最新目标；行颜色和大小直接
// 读这条 spring 的连续位置，快句重定向时也不会被独立 tween 重启或短路。
private fun nativeScrollFollowSpringSpec(): AnimationSpec<Float> {
    return spring(
        dampingRatio = nativeDampingRatio(
            stiffness = NATIVE_SCROLL_FOLLOW_STIFFNESS,
            damping = NATIVE_SCROLL_FOLLOW_DAMPING,
        ),
        stiffness = NATIVE_SCROLL_FOLLOW_STIFFNESS,
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
    companionRowHeights: Map<Int, Int>,
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
        val companionHeight = (companionRowHeights[idx] ?: 0).coerceAtLeast(0)
        val translationHeight = nativeAppleSublineCollapsedHeight(
            fullHeightPx = fullTransHeight,
            maxHeightPx = maxTransHeight,
            progress = safeTransProgress,
        )
        // 副词与译文都是“渲染附加高度”：参与 renderPrefix/手动滚动边界，
        // 但不进入 mainPrefix，所以出现时不会改 rowAnchor/scrollSpring target。
        val transHeight = companionHeight + translationHeight
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
    currentLineScale: Float,
): Float {
    val available = (containerWidthPx.toFloat() - horizontalPaddingPx * 2f).coerceAtLeast(1f)
    val visualWidth = if (available <= compactWidthPx) {
        available
    } else {
        available * aspect.coerceIn(0.2f, 1f)
    }
    val maxCurrentScale = 1f + (currentLineScale - 1f) *
        NATIVE_LINE_TRANSFORM_PROGRESS_MAX
    // 歌词外层已有 horizontalPadding，行缩放可以向对齐边另一侧的留白自然扩展。
    // 旧算法只用 available/maxScale，等于在已经扣过 48dp 后又预留一遍 5%，
    // 导致换行算法看不到屏幕上真实可用的那块空间。
    val safeScaledWidth = (available + horizontalPaddingPx - NATIVE_LINE_SCALE_EDGE_GUARD_PX)
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
private val NATIVE_CSS_EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)
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
private const val NATIVE_MOBILE_OFFSET_RATIO = 0.25f
private const val NATIVE_MOBILE_SCROLL_TOP_MARGIN_DP = 55f
private const val NATIVE_MOBILE_TOP_MASK_DP = 40f
private const val NATIVE_MOBILE_TOP_MASK_FALLBACK_RATIO = 0.05f
private const val NATIVE_MOBILE_LINE_HEIGHT_RATIO = 1.2059624f
private const val NATIVE_EFFECTS_ENABLE_ENTER_PROGRESS = 0.985f
private const val NATIVE_ROW_CLICK_ENTER_PROGRESS = 0.995f
private const val NATIVE_ROW_CLICK_MIN_ALPHA = 0.05f
private const val NATIVE_MOBILE_CURRENT_LINE_SCALE = 1.05f
private const val NATIVE_DESKTOP_CURRENT_LINE_SCALE = 1.05f
private const val NATIVE_DESKTOP_CURRENT_LINE_PADDING_EM = 12f / 22f
private const val NATIVE_LINE_TRANSFORM_PROGRESS_MAX = 1f
private const val NATIVE_ROW_SCALE_FOCUS_SPAN_ROWS = 1.15f
// 颜色只有焦点前后两个 slot 可能取得非 0 进度；同时覆盖夹一个 interlude slot 的切句。
private const val NATIVE_LINE_COLOR_RENDER_RADIUS_SLOTS = 2
private const val NATIVE_LINE_GEOMETRY_SWITCH_MS = 100

// Apple `.line`：color 为 100ms CSS ease，transform/padding 为 100ms ease-in-out。
private const val NATIVE_LINE_COLOR_FADE_MS = 100
private const val NATIVE_INITIAL_REVEAL_MS = 90
private const val NATIVE_LINE_WIDTH_ASPECT = 0.8f
private const val NATIVE_LINE_SCALE_EDGE_GUARD_PX = 2f
private const val NATIVE_COMPACT_WIDTH_DP = 768f
// 容器尚未测量时的兜底窗口行数（少量即可，测量出容器高度后立刻切到固定行数窗口）。
private const val NATIVE_INITIAL_RENDER_RADIUS_LINES = 10
// 固定总窗口在“可视行数”之外追加的待唱缓冲；让即将进入可视区的行提前完成 layout，
// 但不再同时给上下两侧各加一份，避免播放到中段后挂载行数翻倍。
private const val NATIVE_RENDER_WINDOW_BUFFER_ROWS = 7
// 当前行上方只保留完整退场所需的少量 slot；总窗口大小固定，其余预算留给待唱行。
private const val NATIVE_RENDER_WINDOW_BEHIND_ROWS = 7
private const val NATIVE_RENDER_WINDOW_MIN_AHEAD_ROWS = 4
private const val NATIVE_RENDER_WINDOW_MAX_ACTIVE_BEHIND_ROWS = 12
// 后续行的离屏计划预热：不把这些行挂进真实渲染树，只提前生成 balanced line / timed plan /
// 慢词 glyph layout。这样后半首新行进入渲染窗口时，尽量拥有和首屏一样的热缓存。
private const val NATIVE_PREWARM_AHEAD_ROWS = 24
private const val NATIVE_PREWARM_BEHIND_ROWS = 6
private const val NATIVE_GLYPH_PREWARM_AHEAD_ROWS = 5
private const val NATIVE_LIGHT_PREWARM_LINES_PER_FRAME = 2
private const val NATIVE_GLYPH_PREWARM_LINES_PER_FRAME = 1
// 覆盖固定渲染窗口与前后预热区即可，避免把整首歌的 Paragraph/glyph layout 常驻内存。
private const val NATIVE_PREPARED_LINE_CACHE_LIMIT = 96
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
private const val NATIVE_DESCENDER_SAFE_LINE_HEIGHT_EM = NATIVE_MOBILE_LINE_HEIGHT_RATIO
private const val NATIVE_APPLE_TRAILING_WORD_SPACE = "\u2009"
// ===== 慢词 = Apple Web emphasis letter keyframes =====
// Apple Web shouldBeEmphasized：词长 >= 1s 且文本单位 <= 7。
private const val NATIVE_SLOW_WORD_MIN_DURATION_MS = 1_000L
private const val NATIVE_SLOW_WORD_MAX_UNITS = 7
private const val NATIVE_SLOW_TIMING_PART_START_SLOP_MS = 90L
private const val NATIVE_SLOW_TIMING_PART_END_SLOP_MS = 180L
private const val NATIVE_SLOW_TIMING_PART_MAX_GAP_MS = 280L
private const val NATIVE_SLOW_TIMING_PART_MIN_COVERAGE = 0.35f
private const val NATIVE_SLOW_TIMING_PART_MAX_COVERAGE = 1.40f
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
// Apple Web ordinary syllable y 固定 0 -> -2px，不随 22/34px 字号同比放大。
private const val NATIVE_WORD_LIFT_DP = 2f
// Apple Web: `.display-synced-line.is-duet .line { width: 60% }`.
// Android keeps the row container full-width for hit testing/alignment, then
// reserves the opposite 40% as padding so v1/v2 lines land on the same side as
// Apple's left/right duet columns.
private const val NATIVE_SOLID_LINE_ALPHA = 0.40f
private const val NATIVE_GRADIENT_ACTIVE_ALPHA = 0.85f
private const val NATIVE_GRADIENT_INACTIVE_ALPHA = 0.50f
private const val NATIVE_MOBILE_CURRENT_ALPHA = 0.92f
private const val NATIVE_MOBILE_SUNG_ALPHA = 1f
private const val NATIVE_MOBILE_UNSUNG_ALPHA = 0.35f
private const val NATIVE_MOBILE_INACTIVE_ALPHA = 0.175f
// 主词与副词保持普通段间距；移动端不再使用过宽的 20dp 空档。
private const val NATIVE_BG_MARGIN_TOP_EM = 7.5f / 22f
// 副词出现：高度只在 timing 边界写入独立 render extra；视觉用淡入 + 上滑缓和显现。
private const val NATIVE_BG_REVEAL_MS = 400
private val NATIVE_BG_REVEAL_EASE = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val NATIVE_BG_REVEAL_SLIDE_EM = 6f / 22f
private const val NATIVE_BG_FONT_SCALE = 14f / 22f
private const val NATIVE_BG_LINE_HEIGHT_SCALE = (14f * 1.2f) / (22f * 1.1818182f)
private const val NATIVE_MOBILE_BG_FONT_SCALE = 22f / 34f
private const val NATIVE_MOBILE_BG_LINE_HEIGHT_SCALE = (22f * 1.2f) / (34f * NATIVE_MOBILE_LINE_HEIGHT_RATIO)
private const val NATIVE_MOBILE_BG_MARGIN_TOP_DP = 8f
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
private const val NATIVE_MOBILE_SUBLINE_FONT_SCALE = 0.54f
private const val NATIVE_MOBILE_SUBLINE_LINE_HEIGHT_SCALE =
    (34f * NATIVE_MOBILE_SUBLINE_FONT_SCALE * 1.2f) / (34f * NATIVE_MOBILE_LINE_HEIGHT_RATIO)
private const val NATIVE_MOBILE_SUPPLEMENTARY_FONT_SCALE = 0.64f
private const val NATIVE_MOBILE_SUPPLEMENTARY_LINE_HEIGHT_SCALE =
    (34f * NATIVE_MOBILE_SUPPLEMENTARY_FONT_SCALE * 1.2f) / (34f * NATIVE_MOBILE_LINE_HEIGHT_RATIO)
private const val NATIVE_INACTIVE_GAUSSIAN_BLUR_DP = 2f
private const val NATIVE_BLUR_TRANSITION_MS = 250
// Web 的 4.5s 对触屏偏迟；Android 拖动结束 3s 后恢复，既留出选句时间也不让歌词停太久。
private const val NATIVE_MANUAL_HOLD_MS = 3_000L
private const val NATIVE_TAP_CONFIRM_WINDOW_MS = 2_800L
private const val NATIVE_INTERLUDE_MIN_GAP_MS = 9_000L
private const val NATIVE_INTERLUDE_DOT_COUNT = 3f
private const val NATIVE_INTERLUDE_DOT_SIZE_EM = 8.5f / 22f
private const val NATIVE_INTERLUDE_DOT_GAP_RATIO = 0.5f
private const val NATIVE_INTERLUDE_DOT_INACTIVE_ALPHA = 0.30f
private const val NATIVE_INTERLUDE_HEARTBEAT_MS = 5_000f
private const val NATIVE_INTERLUDE_HEARTBEAT_PEAK_SCALE = 1.20f
private const val NATIVE_INTERLUDE_ENDING_MS = 1_500f
private const val NATIVE_INTERLUDE_NEXT_LINE_LEAD_MS = 250f
private const val NATIVE_INTERLUDE_END_COLLAPSE_MS = 120f
private const val NATIVE_INTERLUDE_END_GROW_MS =
    NATIVE_INTERLUDE_ENDING_MS - NATIVE_INTERLUDE_NEXT_LINE_LEAD_MS - NATIVE_INTERLUDE_END_COLLAPSE_MS
private const val NATIVE_INTERLUDE_END_SCALE_PEAK = 1.40f
private const val NATIVE_INTERLUDE_MAX_SCALE = NATIVE_INTERLUDE_END_SCALE_PEAK
// Apple fullscreen lyrics use about 30px top margin on a 28px line font.
private const val NATIVE_INTERLUDE_TOP_MARGIN_EM = 30f / 28f
// Apple Web scrollTop 本身是 350ms，但 current / previous / inactive 行状态不同。
// Android 这里保持整列同相，避免逐行延迟在快切时互相叠加抖动。
private const val NATIVE_MANUAL_TOP_BOUNCE_FRACTION = 0.18f
private const val NATIVE_MANUAL_BOTTOM_BOUNCE_FRACTION = 0.70f
private const val NATIVE_MANUAL_RESTORE_STIFFNESS = 150f
private const val NATIVE_MANUAL_RESTORE_DAMPING = 22f
private const val NATIVE_SEEK_SCROLL_STIFFNESS = 230f
private const val NATIVE_SEEK_SCROLL_DAMPING = 28f
// 原始切句级联：目标句下方的行按程比小幅延后，不新建独立动画。
private const val NATIVE_SCROLL_STAGGER_PER_ROW = 0.045f
private const val NATIVE_SCROLL_STAGGER_MAX = 0.18f
private const val NATIVE_SCROLL_FOLLOW_MS = 350
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
