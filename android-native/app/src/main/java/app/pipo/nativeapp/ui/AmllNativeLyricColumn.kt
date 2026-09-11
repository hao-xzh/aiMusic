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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
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
import androidx.compose.ui.text.font.Font
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.R
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.PipoLyricAlignment
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.PipoLyricTimingPart
import app.pipo.nativeapp.data.effectiveDurationMs
import app.pipo.nativeapp.data.effectiveEndMs
import app.pipo.nativeapp.data.timingPartsForProgress
import app.pipo.nativeapp.playback.LyricPlaybackPositionProvider
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
    isLyricsLoading: Boolean = false,
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
    // 移动端保留独立的字号、滚动锚点和明暗参数；横竖屏共用连续的行焦点缩放。
    useMobileAppleProfile: Boolean = false,
    // 按真实可用宽度排满后再换行；横屏显式开启，避免平衡排版为了两行等长
    // 而在第一行仍有空间时提前折行。移动端 Apple profile 始终自然换行。
    naturalSyllableWrap: Boolean = false,
) {
    val sessionKey = remember(sessionId, lines) { nativeLyricSessionKey(sessionId, lines) }
    // 生产歌词页通过 provider 惰性读取播放器位置，positionMs 参数通常保持默认 0。
    // 切歌后歌词异步回来会重建 session；若仍从参数 0 初始化，就会先创建一条 0ms
    // 的视觉时钟，再在下一次 snapshotFlow 报告时跳到真实位置。直接从 provider
    // 取得本 session 的起点，避免“歌词加载约一秒后重新接管一次”的启动校准。
    val initialPositionMs = remember(sessionKey) {
        (positionProvider?.invoke() ?: positionMs).coerceAtLeast(0L)
    }
    val rawPositionState = rememberNativeRawPositionState(
        fallbackPositionMs = positionMs,
        positionProvider = positionProvider,
        sessionKey = sessionKey,
        initialPositionMs = initialPositionMs,
    )
    val clockState = rememberNativeLyricClockMs(
        rawPositionState = rawPositionState,
        positionProvider = positionProvider,
        isPlaying = isPlaying,
        sessionKey = sessionKey,
        initialRawPositionMs = initialPositionMs,
    )
    // 整列只保留一个 Apple TimeGroup 帧时钟。旧实现会为每个仍在唱/正在退场的行
    // 各启动一条 withFrameNanos 循环；重叠歌词和慢词一多，同一帧会被重复推进、重复写 State。
    // 所有逐词绘制共用下方同一套时钟规则，只收敛执行数量，不改变歌词源 timing 或切句参数。
    val timeGroupClockState = clockState
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()
    val lyricPlanCache = remember(sessionKey) {
        NativeLyricPlanCache(maxEntries = NATIVE_PREPARED_LINE_CACHE_LIMIT)
    }
    val prewarmTextMeasurer = rememberTextMeasurer()
    val prewarmGlyphMeasurer = rememberTextMeasurer(cacheSize = 128)

    if (lines.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = if (isLyricsLoading) "歌词加载中…" else "暂无歌词",
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
    // main prefix 保持稳定，切句滚动 始终只追主词行顶。
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
    // scrollPosition 全程工作在此坐标系。
    fun rowTopBase(index: Int): Float = rowMetrics.rowTopBase(index)
    fun rowAnchor(index: Int): Float {
        val safeIndex = index.coerceIn(nativeSlots.indices)
        return rowTopBase(safeIndex)
    }
    // 基准坐标位置 → 需叠加的渲染附加量（已出现副词 + 译文）：跨越某行时
    // 按主词行内进度连续计入。这样附加高度只改变真实 render 位置，
    // 不会重定向切句滚动 的主词基准目标。
    fun transOffsetForBase(basePos: Float): Float = rowMetrics.transOffsetForBase(basePos)
    // 渲染坐标 → 基准坐标：手动拖动以 1:1 手感工作在渲染坐标，松手时换算回基准交给滚动动画。
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
    val scrollPosition = remember(sessionKey) {
        Animatable(initialScrollCenter)
    }
    val scrollHistory = remember(sessionKey) {
        NativeScrollMotionHistory(SystemClock.uptimeMillis(), initialScrollCenter)
    }
    val scrollVelocity = remember(sessionKey) { FloatArray(1) }
    val playbackDiscontinuity = (positionProvider as? LyricPlaybackPositionProvider)?.discontinuitySequence
    var lastScrollDiscontinuity by remember(sessionKey) { mutableStateOf(playbackDiscontinuity) }
    var scrollFrame by remember(sessionKey) { mutableLongStateOf(0L) }
    var staggerEnabled by remember(sessionKey) { mutableStateOf(false) }
    var staggerFirstSlot by remember(sessionKey) { mutableIntStateOf(0) }
    fun recordScrollFrame() {
        scrollHistory.record(SystemClock.uptimeMillis(), scrollPosition.value)
        scrollFrame += 1L
    }
    var lastScrollTargetIdx by remember(sessionKey) { mutableStateOf(playbackActiveSlotIdx) }
    var initialLayoutSettled by remember(sessionKey) { mutableStateOf(false) }
    var lastLayoutAnchorIdx by remember(sessionKey) { mutableStateOf(layoutAnchorSlotIdx) }
    var lastLayoutAnchorCenter by remember(sessionKey) { mutableFloatStateOf(Float.NaN) }
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
    // 渲染中心（含译文偏移）：scrollPosition 工作在基准坐标、手动拖动用 1:1 渲染坐标。
    // 关键性能约束：只在 layout(offset) / draw(graphicsLayer) / 事件回调里调用本函数，
    // 绝不要在 composition 顶层读 —— 否则 滚动/拖动每帧都会触发整列重组（不流畅根源）。
    fun renderCenterNow(): Float = manualScrollCenterPx.let { m ->
        if (m.isFinite()) m else scrollPosition.value + transOffsetForBase(scrollPosition.value)
    }
    fun rowScrollCenterNow(slotIndex: Int): Float {
        if (!staggerEnabled || manualScrollActive || !isPlaying) return renderCenterNow()
        // 一帧一次采样；下方各行只延迟读取同一条轨迹，不创建各自的动画控制器。
        @Suppress("UNUSED_VARIABLE") val frame = scrollFrame
        val rank = (slotIndex - staggerFirstSlot).coerceAtLeast(0)
        var delayMs = 0f
        var stepMs = 50f
        repeat(rank.coerceAtMost(8)) {
            delayMs += stepMs
            stepMs /= 1.05f
        }
        val center = scrollHistory.centerAt(
            scrollHistory.latestTimeMs - delayMs.coerceAtMost(300f).toLong(),
        )
        return center + transOffsetForBase(center)
    }
    // 首帧定位独立成 effect：容器与首屏行测量完成后，把滚动锚到当前目标并解锁入场。
    // 关键：把"新行进入渲染窗口被测量(measuredRowCount 变化)"从下面的跟随 effect 的重启 key 里剥离。
    // 否则每测量一行就重启一次跟随 effect、把切句滚动的 tween 打断重来、永远到不了目标，
    // 滚动持续落后于当前句，十几行后当前句被推出屏幕 → 看起来"动画消失了"。
    LaunchedEffect(sessionKey, containerHeightPx, measuredRowCount) {
        if (initialLayoutSettled) return@LaunchedEffect
        if (containerHeightPx <= 0 || measuredRowCount <= 0) return@LaunchedEffect
        scrollPosition.snapTo(scrollTargetCenter)
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
        manualHoldUntilMs,
        playbackDiscontinuity,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (manualScrollActive || isUserDragging || manualHoldUntilMs > now) {
            staggerEnabled = false
            scrollVelocity[0] = 0f
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
        val positionJumped = playbackDiscontinuity != lastScrollDiscontinuity
        lastScrollDiscontinuity = playbackDiscontinuity
        if (positionJumped) {
            staggerEnabled = false
            scrollVelocity[0] = 0f
        }
        val previousLayoutAnchorIdx = lastLayoutAnchorIdx
        val previousLayoutAnchorCenter = lastLayoutAnchorCenter
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
                scrollPosition.snapTo(scrollPosition.value + layoutDelta)
                scrollHistory.shift(layoutDelta)
            }
        }
        val targetIdx = playbackActiveSlotIdx.coerceIn(nativeSlots.indices)
        val targetRowHeight = rowHeight(targetIdx).toFloat()
        val targetRowTop = rowTopBase(targetIdx) + anchorYPx - scrollPosition.value
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
            staggerEnabled = false
            scrollPosition.snapTo(scrollTargetCenter)
            return@LaunchedEffect
        }
        if (!isPlaying) {
            staggerEnabled = false
            scrollPosition.stop()
            return@LaunchedEffect
        }
        if (playbackActiveSlotIdx != previousIdx) {
            val forward = playbackActiveSlotIdx > previousIdx && !positionJumped
            if (forward && !staggerEnabled) {
                val center = renderCenterNow()
                staggerFirstSlot = nativeSlots.indices.firstOrNull {
                    renderTop(it) + rowHeight(it) + anchorYPx > center
                } ?: playbackActiveSlotIdx
                scrollHistory.reset(SystemClock.uptimeMillis(), scrollPosition.value)
            }
            staggerEnabled = forward
        }
        scrollPosition.animateTo(
            targetValue = scrollTargetCenter,
            // 连续短句重定向时保留速度，避免每次重启 ease-in 的减速顿点。
            animationSpec = spring(dampingRatio = 1f, stiffness = 140f, visibilityThreshold = 0.2f),
            initialVelocity = scrollVelocity[0],
        ) {
            scrollVelocity[0] = velocity
            recordScrollFrame()
        }
        scrollVelocity[0] = 0f
        if (staggerEnabled) {
            val tailEnd = SystemClock.uptimeMillis() + 300L
            while (isActive && SystemClock.uptimeMillis() < tailEnd) {
                withFrameNanos { recordScrollFrame() }
            }
            staggerEnabled = false
        }
    }

    LaunchedEffect(manualHoldUntilMs, isUserDragging, scrollTargetCenter, playbackActiveSlotIdx) {
        val waitMs = manualHoldUntilMs - SystemClock.elapsedRealtime()
        if (waitMs > 0L || isUserDragging) {
            delay(waitMs.coerceAtLeast(0L))
        }
        if (!isUserDragging && manualHoldUntilMs <= SystemClock.elapsedRealtime() && manualScrollCenterPx.isFinite()) {
            // manualScrollCenterPx 是渲染坐标，换算回基准坐标后交给滚动动画 复位。
            val startCenter = baseForRenderCenter(manualScrollCenterPx)
            manualScrollCenterPx = Float.NaN
            manualVisualSlotIdx = -1
            scrollPosition.snapTo(startCenter)
            scrollPosition.animateTo(
                targetValue = scrollTargetCenter,
                animationSpec = nativeScrollFollowAnimationSpec(),
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

    suspend fun warmPreparedLyricLine(
        line: PipoLyricLine,
        warmGlyphs: Boolean,
        budget: NativeLyricPrewarmBudget,
    ): Boolean {
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
            budget = budget,
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
                    budget = budget,
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
        budget: NativeLyricPrewarmBudget,
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
                if (warmPreparedLyricLine(line, warmGlyphs = warmGlyphs, budget = budget)) {
                    warmedThisFrame += 1
                    if (warmedThisFrame >= safeLinesPerFrame) {
                        warmedThisFrame = 0
                        budget.nextFrame()
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
            val budget = NativeLyricPrewarmBudget()
            val forwardEnd = (center + NATIVE_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
            val backwardStart = (center - NATIVE_PREWARM_BEHIND_ROWS).coerceAtLeast(0)
            // 先准备即将切入的几句及其字形，再处理远处排版；不让远端预取
            // 抢占下一句首个动画帧所需的工作。
            val glyphForwardEnd = (center + NATIVE_GLYPH_PREWARM_AHEAD_ROWS).coerceAtMost(lastSlotIndex)
            val stillCurrentAfterGlyphs = warmPreparedSlotRange(
                start = center + 1,
                endInclusive = glyphForwardEnd,
                step = 1,
                linesPerFrame = NATIVE_GLYPH_PREWARM_LINES_PER_FRAME,
                warmGlyphs = true,
                budget = budget,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterGlyphs) continue
            val stillCurrentAfterForward = warmPreparedSlotRange(
                start = glyphForwardEnd + 1,
                endInclusive = forwardEnd,
                step = 1,
                linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
                warmGlyphs = false,
                budget = budget,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterForward) continue
            val stillCurrentAfterBackward = warmPreparedSlotRange(
                start = center - 1,
                endInclusive = backwardStart,
                step = -1,
                linesPerFrame = NATIVE_LIGHT_PREWARM_LINES_PER_FRAME,
                warmGlyphs = false,
                budget = budget,
                shouldContinue = { prewarmCenterState.value == center },
            )
            if (!stillCurrentAfterBackward) continue
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
                                    scrollPosition.stop()
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
                        // current 决定滚动与行样式；真实演唱区间保留重叠歌词的逐词时间轴。
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
                        val focusAnchor = rowAnchor(slotIndex)
                        // 用相邻行的真实间距归一化，长句/多行歌词也能连续交接焦点。
                        val previousFocusAnchor = rowAnchor((slotIndex - 1).coerceAtLeast(0))
                        val nextFocusAnchor = rowAnchor((slotIndex + 1).coerceAtMost(lastSlotIndex))
                        // 保留点按确认态的焦点过渡；自动切句使用下方的滚动进度。
                        val selectedFocusProgress = animateFloatAsState(
                            targetValue = if (isFocused) 1f else 0f,
                            animationSpec = tween(NATIVE_LINE_COLOR_FADE_MS, easing = NATIVE_LINE_SWITCH_EASE),
                            label = "nativeLyricSelectedFocus",
                        )
                        // 大小和颜色共用滚动焦点进度，不在行还没上浮到位时提前播完。
                        // 只在 layer/draw 阶段读取，保持固定字形与逐行错峰，不逐帧重排。
                        val rowFocusProvider: () -> Float = {
                            if (pendingSeekIdx >= 0) {
                                selectedFocusProgress.value
                            } else {
                                val center = if (manualScrollCenterPx.isFinite()) {
                                    baseForRenderCenter(manualScrollCenterPx)
                                } else {
                                    scrollPosition.value
                                }
                                val span = if (center < focusAnchor) {
                                    focusAnchor - previousFocusAnchor
                                } else {
                                    nextFocusAnchor - focusAnchor
                                }
                                val t = (1f - kotlin.math.abs(focusAnchor - center) / span.coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                t * t * (3f - 2f * t)
                            }
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
                                                            val seekTargetMs =
                                                                LyricTiming.audioStartMs(line).coerceAtLeast(0L)
                                                            // 两段确认窗口内，原本的“未来行”可能已经变成当前行。
                                                            // 此时再跳到行首会让播放器和歌词一起倒退；当前行只恢复
                                                            // 自动跟随，不再向后 seek。未来行跳过前奏、旧行回听不受影响。
                                                            val shouldSeekPlayer = idx != currentLineIdx ||
                                                                seekTargetMs > rawPositionState.value
                                                            pendingSeekIdx = -1
                                                            pendingSeekUntilMs = 0L
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            manualHoldUntilMs = 0L
                                                            isUserDragging = false
                                                            val seekCenter = rowAnchor(slotIndex)
                                                            val resumeCenter = baseForRenderCenter(renderCenterNow())
                                                            manualScrollCenterPx = Float.NaN
                                                            manualVisualSlotIdx = -1
                                                            gestureScope.launch {
                                                                scrollPosition.stop()
                                                                scrollPosition.snapTo(resumeCenter)
                                                                scrollPosition.animateTo(
                                                                    targetValue = seekCenter,
                                                                    animationSpec = nativeScrollFollowAnimationSpec(),
                                                                )
                                                            }
                                                            if (shouldSeekPlayer) {
                                                                onSeekToMs(seekTargetMs)
                                                            }
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
                                    rowScaleProvider = rowFocusProvider,
                                    // 切句近邻与重叠人声跟随同一焦点渐变，远处静态行不逐帧重绘。
                                    lineColorFocusProvider = rowFocusProvider.takeIf {
                                        distance <= NATIVE_LINE_COLOR_RENDER_RADIUS_SLOTS || isActive
                                    },
                                    useMobileAppleProfile = useMobileAppleProfile,
                                    useNaturalSyllableWrap = useNaturalSyllableWrap,
                                    hasCompanionAppeared = { companionIndex, companion ->
                                        appearedCompanions[
                                            NativeCompanionAppearanceKey(
                                                slotIndex = slotIndex,
                                                companionIndex = companionIndex,
                                                startMs = nativeLineMainStartMs(companion),
                                                textHash = companion.text.hashCode(),
                                            )
                                        ] == true
                                    },
                                    onCompanionAppeared = { companionIndex, companion ->
                                        appearedCompanions[
                                            NativeCompanionAppearanceKey(
                                                slotIndex = slotIndex,
                                                companionIndex = companionIndex,
                                                startMs = nativeLineMainStartMs(companion),
                                                textHash = companion.text.hashCode(),
                                            )
                                        ] = true
                                    },
                                    onCompanionAppearanceReset = { companionIndex, companion ->
                                        appearedCompanions.remove(
                                            NativeCompanionAppearanceKey(
                                                slotIndex = slotIndex,
                                                companionIndex = companionIndex,
                                                startMs = nativeLineMainStartMs(companion),
                                                textHash = companion.text.hashCode(),
                                            )
                                        )
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
    onCompanionAppearanceReset: (Int, PipoLyricLine) -> Unit,
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
                // 左/右边缘固定，整行焦点缩放与音节自身的上浮分别作用在父子层。
                transformOrigin = TransformOrigin(pivotX, 0.5f)
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
            // 主词进入 mainPrefix，副词只进入 render extra，绝不改 scrollPosition 的目标。
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
                            key("bg", bgIndex, companion.startMs, companion.text) {
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
                                    supplementaryProgress = transProgress,
                                    suspendTimedDraw = isUserInteracting,
                                    useMobileAppleProfile = useMobileAppleProfile,
                                    useNaturalSyllableWrap = useNaturalSyllableWrap,
                                    persistedAppeared = hasCompanionAppeared(bgIndex, companion),
                                    onFirstAppearance = { onCompanionAppeared(bgIndex, companion) },
                                    onAppearanceReset = { onCompanionAppearanceReset(bgIndex, companion) },
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

            // slot 1：译文按真实换行高度参与行布局。Apple Web 的 50px 只适合单行副文本；
            // Android 窄屏上的长译文会换成多行，因此展开上限至少要覆盖完整测量高度，
            // 否则超出的文字仍会绘制到下一句区域，而 rowMetrics 却没有为它预留空间。
            if (translations.isNotEmpty()) {
                Layout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds(),
                    content = {
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
                    },
                ) { translationMeasurables, translationConstraints ->
                    val translationPlaceable = translationMeasurables.single().measure(
                        translationConstraints.copy(minHeight = 0),
                    )
                    val fullContentHeight = translationPlaceable.height
                    val fullHeight = if (fullContentHeight > 0) {
                        fullContentHeight + translationBottomGapPx
                    } else {
                        0
                    }
                    val revealMaxHeight = if (fullHeight > 0) {
                        maxOf(transMaxHeightPx + translationBottomGapPx, fullHeight)
                    } else {
                        0
                    }
                    onTransFullHeight(fullHeight)
                    onTransMaxHeight(revealMaxHeight)
                    val visibleHeight = nativeAppleSublineCollapsedHeight(
                        fullHeightPx = fullHeight,
                        maxHeightPx = revealMaxHeight,
                        progress = transProgress,
                    )
                    layout(translationPlaceable.width, visibleHeight) {
                        translationPlaceable.place(0, 0)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val bodyPlaceable = measurables[0].measure(constraints)
        val transPlaceable = measurables.getOrNull(1)?.measure(constraints)
        if (transPlaceable == null) {
            onTransFullHeight(0)
            onTransMaxHeight(0)
        }
        val width = constraints.maxWidth
        val totalHeight = bodyPlaceable.height + (transPlaceable?.height ?: 0)
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
    supplementaryProgress: Float,
    suspendTimedDraw: Boolean,
    useMobileAppleProfile: Boolean,
    useNaturalSyllableWrap: Boolean,
    persistedAppeared: Boolean,
    onFirstAppearance: () -> Unit,
    onAppearanceReset: () -> Unit,
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
    // 锚定旧坐标时改变。
    val revealNow = companionStarted && !suspendTimedDraw
    // 首次挂载时若播放时钟已经越过 start，animateFloatAsState 会把 1f 当作初值，
    // 400ms tween 实际一帧都不会播。未出现过的副词先确保 0f 被提交一帧，
    // 再锁存到可见；已出现过的窗口重挂仍直接保持 1f，不重播、不重复改行高。
    var firstRevealArmed by remember(companionStartMs, companion.text) {
        mutableStateOf(persistedAppeared && companionStarted)
    }
    LaunchedEffect(companionStarted, revealNow, persistedAppeared) {
        when {
            // seek / 单曲循环回到副词 start 之前：撤销 session 锁存，
            // 否则它会提前常驻，下次越过 timing 也不再播 reveal。
            !companionStarted -> {
                firstRevealArmed = false
                if (persistedAppeared) onAppearanceReset()
            }
            persistedAppeared && !firstRevealArmed -> firstRevealArmed = true
            revealNow && !firstRevealArmed -> {
                withFrameNanos { }
                firstRevealArmed = true
                onFirstAppearance()
            }
        }
    }
    val companionVisible = companionStarted && (persistedAppeared || firstRevealArmed)
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
    // 高度单独用更短的曲线展开：副词文本仍按原有 400ms 淡入/上滑，但周围歌词
    // 会像译文展开一样连续让位，并且比 350ms 的主歌词跟随更快完成。
    val heightRevealAnim = animateFloatAsState(
        targetValue = targetAppear,
        animationSpec = tween(
            durationMillis = NATIVE_BG_HEIGHT_REVEAL_MS,
            easing = NATIVE_BG_REVEAL_EASE,
        ),
        label = "nativeCompanionHeightReveal",
    )
    // 副词不用主行的 250ms lookahead：显示和 sweep 都从它自己的真实 timing 起步。
    val wordTimelineOffsetMs = 0L
    val companionTimedActive = companionVisible && hostLineActive
    val visualCompanionPast = companionVisible && !hostLineActive
    val companionAlignment = if (companion.alignment == PipoLyricAlignment.End) Alignment.End else itemAlignment
    val companionTextAlign = if (companionAlignment == Alignment.End) TextAlign.End else TextAlign.Start
    val boxAlignment = if (companionAlignment == Alignment.End) Alignment.CenterEnd else Alignment.CenterStart
    val companionSupplementaries = remember(companion) {
        companion.companionLines
            .filter { it.role == PipoLyricRole.Translation || it.role == PipoLyricRole.Romaji }
            .sortedBy { if (it.role == PipoLyricRole.Romaji) 0 else 1 }
    }
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
            // clip 必须包在高度 layout 外层；否则虽然上报高度在增长，完整副词仍会越界画出。
            .clipToBounds()
            .nativeBackgroundVocalReveal(progress = heightRevealAnim)
            .padding(top = topMargin)
            .graphicsLayer {
                // reveal 是副词自己的入场语义，不能在行特效尚未 settle 时绕过。
                // animateFloatAsState 本来一直在运行，这里始终读它可避免 false -> true 直接跳到 1f。
                val appear = appearAnim.value
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = companionAlignment,
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
            // AMLL 允许 x-bg 自身带翻译 / 音译；它们跟副词一起浮现，
            // 而不是误挂到主唱的小字行或在解析层被丢掉。
            if (companionSupplementaries.isNotEmpty()) {
                val progress = supplementaryProgress.coerceIn(0f, 1f)
                Layout(
                    // 这里的 clip 只服务于“翻译开关的高度展开”，不参与逐词位移；
                    // 否则 Layout 虽然报告 0 高，子节点仍可以绘制到父边界外，关闭翻译时会漏出。
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds(),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            companionSupplementaries.forEach { supplementary ->
                                NativeAmllTranslationLine(
                                    translation = supplementary,
                                    progress = progress,
                                    itemAlignment = companionAlignment,
                                    textAlign = companionTextAlign,
                                    fg = fg,
                                    fontSize = companionFontSize,
                                    lineHeight = companionLineHeight,
                                    useMobileAppleProfile = useMobileAppleProfile,
                                )
                            }
                        }
                    },
                ) { measurables, constraints ->
                    val placeable = measurables.single().measure(constraints)
                    val visibleHeight = (placeable.height * progress).roundToInt()
                    layout(placeable.width, visibleHeight) {
                        placeable.place(0, 0)
                    }
                }
            }
        }
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
    progress: State<Float>,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val visibleHeight = (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
    layout(placeable.width, visibleHeight) {
        if (visibleHeight > 0) placeable.placeRelative(0, 0)
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
    // 正文/光效显式设置 paint 状态后，相同字形可以安全复用同一份测量和栅格。
    val glowMeasurer = rememberTextMeasurer(cacheSize = 128)
    val rasterCache = remember(planCache) { planCache?.rasters ?: NativeLyricRasterCache() }
    val density = LocalDensity.current
    val style = nativeLyricTextStyle(fontSize, lineHeight, fontWeight, textAlign)
    val lineStartMs = remember(sourceLine) { nativeLineMainStartMs(sourceLine) }
    val lineEndMs = remember(sourceLine) { nativeLineAudioEndMs(sourceLine) }
    // 重叠人声保留各自词级时间轴；自动行色跟随滚动焦点，手动焦点使用短过渡。
    val canUseFocusGradient = !isPast || keepFocusGradient || isFocused
    val lineSungOut by remember(line) {
        derivedStateOf { timeState.value >= lineEndMs }
    }
    val focusTargetActive = isFocused && canUseFocusGradient
    val focusColorAnim = animateFloatAsState(
        targetValue = if (focusTargetActive) 1f else 0f,
        animationSpec = tween(
            // Apple line/token color transition 双向都是 0.1s。
            durationMillis = NATIVE_LINE_COLOR_FADE_MS,
            easing = NATIVE_CSS_DEFAULT_EASE,
        ),
        label = "nativeLyricFocusColor",
    )
    val hasUnfinishedTimedMotion by remember(timedPlan, clockState, timeState, wordTimelineOffsetMs) {
        derivedStateOf {
            val plan = timedPlan ?: return@derivedStateOf false
            val position = nativeRenderPositionMs(
                (clockState?.value ?: timeState.value.toFloat()) + wordTimelineOffsetMs,
            )
            position >= plan.animationStartMs && position < plan.animationEndMs
        }
    }
    // 长音尾程按媒体时刻收尾，暂停时也暂停，不能被固定 350ms 的退场计时截断。
    // 手动拖动到非当前播放行时，视觉焦点应该保留行色渐变；如果继续走 timed 自绘，
    // 它会用真实播放时钟计算这个远处句子的 sweep，结果常常是 0 或已结束，焦点渐变被透明文本路径盖掉。
    val manualStaticFocus = !isActive && (keepFocusGradient || isFocused)
    // 静态文本色不再离散跳变（旧的 when 分支在 fgUnsung→fg→已唱色之间瞬切，就是
    // “切句时颜色跳一下/闪一下”的来源之一）。past 仍是独立的时间事实；
    // LRC 与 timed 行使用同一行色过渡，切句时旧行亮→暗、新行暗→亮。
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
    val animatedLineColorProvider = lineColorFocusProvider?.takeUnless { manualStaticFocus }
    val useTimedRenderer = line.chars.isNotEmpty() &&
        timedPlanReady &&
        // 待唱行进入切句邻域后就预先使用同一 timed Canvas。否则
        // current 翻转的那一帧会从整行 drawText 换成 segment/glyph 自绘，
        // 两种抗锯齿栅格的细微差异会被看成颜色闪一下。
        (timedRendererMounted || isActive || animatedLineColorProvider != null) &&
        !manualStaticFocus &&
        !suspendTimedDraw
    // 自动播放的行色 provider 只在 draw 阶段读取滚动焦点，不会逐帧重组 Text。
    // 手动/点击选中非当前行时则保留
    // focusColorAnim，避免点击确认态被远处的空间位置压暗。
    // provider 存在时从首帧就锁定在 spatial draw 路径，不等 layout 回调后
    // 再从布尔颜色 Text 切换过来。极端的新挂载帧若 layout 尚未可用，
    // 宁可保持透明到同帧测量完成，也不先用错误的亮/暗端点闪一帧。
    val useAnimatedStaticRenderer = !useTimedRenderer && animatedLineColorProvider != null
    val fallbackFocusProgress = if (useTimedRenderer || useAnimatedStaticRenderer) 0f else focusColorAnim.value
    val fallbackPastProgress = if (useTimedRenderer || useAnimatedStaticRenderer) 0f else pastColorAnim.value
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
        needsTimedFallback && !useAnimatedStaticRenderer && lineEndMs > lineStartMs
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
        useTimedRenderer || useAnimatedStaticRenderer -> Color.Transparent
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
            if (layout !== result) layout = result
            if (line.chars.isEmpty()) {
                timedPlan = null
                if (preparedKey != null) planCache?.putPlan(preparedKey, line, null)
            } else if (!nativePreparedPlanMatches(timedPlan, result)) {
                val nextPlan = nativeTimedLyricPlan(
                    layout = result,
                    chars = line.chars,
                    sourceChars = sourceLine.chars,
                    density = density,
                    rasterCache = rasterCache,
                )
                timedPlan = nextPlan
                if (preparedKey != null) planCache?.putPlan(preparedKey, line, nextPlan)
            }
        },
        modifier = when {
            useTimedRenderer -> Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val plan = timedPlan
                if (result == null || plan == null || plan.segments.isEmpty()) {
                    drawContent()
                } else {
                    // 行色在 draw 阶段读取；扫色与逐字位移只使用共享媒体时刻。
                    val focusProgress = animatedLineColorProvider
                        ?.invoke()
                        ?.coerceIn(0f, NATIVE_LINE_TRANSFORM_PROGRESS_MAX)
                        ?: focusColorAnim.value
                    val pastProgress = pastColorAnim.value
                    if (!isActive &&
                        focusProgress <= NATIVE_SWEEP_PROGRESS_EPS &&
                        !hasUnfinishedTimedMotion
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
                            topLeft = Offset(0f, if (linePast) -plan.cssPx * NATIVE_WORD_LIFT_DP else 0f),
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
                            lineFocusProgress = focusProgress,
                            // 保持同一 Canvas 节点时也复用静态路径的 past tween。
                            linePastProgress = pastProgress,
                            useMobileAppleProfile = useMobileAppleProfile,
                            effectsEnabled = effectsEnabled,
                        )
                    }
                }
            }
            useAnimatedStaticRenderer -> Modifier.fillMaxWidth().drawWithContent {
                val result = layout
                val focusProvider = animatedLineColorProvider
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

internal data class NativeLyricTypography(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
)

internal fun nativeLyricTypography(contentWidth: Dp, viewportHeight: Dp): NativeLyricTypography {
    // 34 是已核实的 Apple Web 移动歌词基准；28–34 的范围是本项目的窗口适配，
    // 并非 Apple 原生 App 公布的尺寸。按歌词栏而非整屏定字号，横竖屏共用。
    // 宽度留约十个全角字、高度留六个 em；sp 继续响应系统字体大小设置。
    val fontSize = minOf(contentWidth.value / 10f, viewportHeight.value / 6f)
        .coerceIn(28f, 34f).sp
    return NativeLyricTypography(fontSize, fontSize * NATIVE_MOBILE_LINE_HEIGHT_RATIO)
}

// Inter 4.1 Display 与已确认的字体样张一致；中文等缺失字形由 Android 系统回退。
// 同一字体族供预排版、逐字测量和最终绘制使用，避免换字体后扫色与字形错位。
private val nativeLyricFontFamily = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private fun nativeLyricTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
): TextStyle {
    return TextStyle(
        fontSize = fontSize,
        fontFamily = nativeLyricFontFamily,
        fontWeight = fontWeight,
        // Compose 1.7 的 AndroidTextPaint.setShadow(null) 不会清除上一笔阴影。
        // 光晕与正文复用 TextLayoutResult 时必须显式 None，否则正文再次带上光晕，
        // 且停止绘制光晕后仍会残留上一帧的 shadow，形成第二层颜色。
        shadow = Shadow.None,
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
    // Apple Web 仅 .line:lang(zh) 为 600，默认 700，并非所有 CJK 都降重。
    // 歌词模型没有语言标签：含汉字且没有假名/谚文时采用中文回退。
    val defaultWeight = if (fallback.weight <= FontWeight.Bold.weight) fallback else FontWeight.Bold
    var hasHan = false
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN -> hasHan = true
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> return defaultWeight
            else -> Unit
        }
        index += Character.charCount(codePoint)
    }
    return if (hasHan && defaultWeight.weight > FontWeight.SemiBold.weight) {
        FontWeight.SemiBold
    } else {
        defaultWeight
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
    val timedCharsHash: Int,
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

private data class NativeLyricRaster(val image: ImageBitmap, val padding: Float)

// 缓存的是固定字形的白色 alpha 载体，颜色、位移、缩放都不进入 key。
// 与歌词 session 同寿命，8 MiB 总上限；不会逐帧栅格化，也不保存整首歌的动画帧。
private class NativeLyricRasterCache {
    val layerPaint = Paint()
    private val entries = object : android.util.LruCache<TextLayoutResult, NativeLyricRaster>(8 * 1024 * 1024) {
        override fun sizeOf(key: TextLayoutResult, value: NativeLyricRaster): Int =
            value.image.width * value.image.height * 4
    }

    fun get(layout: TextLayoutResult): NativeLyricRaster? {
        entries.get(layout)?.let { return it }
        val padding = kotlin.math.ceil(layout.multiParagraph.height * 0.12f).toInt().coerceAtLeast(2)
        val width = layout.size.width + padding * 2
        val height = layout.size.height + padding * 2
        // 异常超长 token 继续使用正文绘制，不分配超大纹理。
        if (width <= 0 || height <= 0 || width.toLong() * height > 1_048_576L) return null
        val image = ImageBitmap(width, height)
        val canvas = GraphicsCanvas(image)
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.multiParagraph.paint(canvas, color = Color.White, shadow = Shadow.None)
        val raster = NativeLyricRaster(image, padding.toFloat())
        entries.put(layout, raster)
        return raster
    }
}

private fun DrawScope.drawNativeStableText(
    rasters: NativeLyricRasterCache,
    layout: TextLayoutResult,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    topLeft: Offset,
    alpha: Float = 1f,
) {
    val raster = rasters.get(layout)
    if (raster == null) {
        if (brush == null) drawText(layout, color = color, topLeft = topLeft, alpha = alpha, shadow = Shadow.None)
        else drawText(layout, brush = brush, topLeft = topLeft, alpha = alpha, shadow = Shadow.None)
        return
    }
    // 浮点位移放在 canvas transform，不能转成 IntOffset；双线性采样固定字形，
    // 避免 1.00→1.05 缩放时 Android 每帧重做 font hinting 造成笔画/字距抖动。
    translate(topLeft.x, topLeft.y) {
        val origin = Offset(-raster.padding, -raster.padding)
        val size = IntSize(raster.image.width, raster.image.height)
        if (brush != null) drawContext.canvas.saveLayer(Rect(origin, Size(size.width.toFloat(), size.height.toFloat())), rasters.layerPaint)
        try {
            translate(origin.x, origin.y) {
                drawImage(
                    image = raster.image,
                    srcSize = size,
                    dstSize = size,
                    alpha = alpha,
                    colorFilter = if (brush == null) ColorFilter.tint(color) else null,
                    filterQuality = FilterQuality.Low,
                )
            }
            if (brush != null) {
                drawRect(
                    brush = brush,
                    topLeft = origin,
                    size = Size(size.width.toFloat(), size.height.toFloat()),
                    blendMode = BlendMode.SrcIn,
                )
            }
        } finally {
            if (brush != null) drawContext.canvas.restore()
        }
    }
}

private class NativeLyricPlanCache(
    private val maxEntries: Int,
) {
    val rasters = NativeLyricRasterCache()
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
        timedCharsHash = line.chars.hashCode(),
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

// 预热只占用一小段主线程时间；长句按字形让出帧，动画曲线和绘制精度保持原样。
private class NativeLyricPrewarmBudget {
    private var sliceStartedNanos = System.nanoTime()

    suspend fun afterWork() {
        if (System.nanoTime() - sliceStartedNanos >= 2_000_000L) nextFrame()
    }

    suspend fun nextFrame() {
        withFrameNanos { }
        sliceStartedNanos = System.nanoTime()
    }
}

private suspend fun nativeWarmTimedPlan(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    budget: NativeLyricPrewarmBudget,
) {
    val text = plan.layout.layoutInput.text.text
    plan.segments.forEachIndexed { segmentIndex, segment ->
        // 普通词在进入扫色/上浮前就准备好独立 layout，避免首个运动帧临时测量造成顿挫。
        // 每帧仍只预热一行，且结果跟随既有 prepared-plan LRU 生命周期。
        val segmentLayout = nativeSegmentTextLayout(plan, glyphMeasurer, segmentIndex)
        budget.afterWork()
        val slowAmount = plan.segSlow.getOrElse(segmentIndex) { 0f }
        if (slowAmount <= 0f) {
            segmentLayout?.let(plan.rasters::get)
            budget.afterWork()
            return@forEachIndexed
        }
        for (index in segment.startChar until segment.endChar) {
            if (index !in text.indices || index !in plan.glyphBoxLeft.indices) continue
            if (nativeAppleStripsSyllableChar(text[index]) || text[index].isWhitespace() || text[index].isISOControl()) continue
            if (plan.glyphBoxLeft[index].isNaN()) continue
            nativeSlowGlyphLayout(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                text = text,
                index = index,
            )?.let(plan.rasters::get)
            budget.afterWork()
        }
    }
}

private suspend fun nativePrewarmLyricLine(
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
    budget: NativeLyricPrewarmBudget,
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
                rasterCache = cache.rasters,
            )
        }
    } else {
        null
    }
    cache.putPlan(
        key = key,
        displayLine = displayLine,
        timedPlan = timedPlan,
        glyphsWarmed = prepared?.glyphsWarmed == true,
    )
    // 在第一次让出帧前发布 layout/plan；待唱行此时进入窗口也可直接复用，
    // 不会因 glyph 仍在分批预热而同步重建同一份排版。
    budget.afterWork()
    if (needsGlyphWarm && timedPlan != null && prepared?.glyphsWarmed != true) {
        nativeWarmTimedPlan(timedPlan, glyphMeasurer, budget)
        cache.putPlan(key, displayLine, timedPlan, glyphsWarmed = true)
    }
    return true
}

private class NativeTimedLyricPlan(
    val rasters: NativeLyricRasterCache,
    val layout: TextLayoutResult,
    val segments: List<NativeLyricSegment>,
    val cssPx: Float,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
    val rowGlowClipTop: FloatArray,
    val rowGlowClipBottom: FloatArray,
    val segGlowClipLeft: FloatArray,
    val segGlowClipRight: FloatArray,
    val segSlow: FloatArray,
    val glyphBoxLeft: FloatArray,
    val glyphBoxRight: FloatArray,
    val glyphSharedInkLeft: FloatArray,
    val glyphStyle: TextStyle,
    val glyphLayouts: Array<TextLayoutResult?>,
    val segmentLayouts: Array<TextLayoutResult?>,
    val segmentInkLeft: FloatArray,
) {
    val glyphInkLeft = FloatArray(glyphLayouts.size)
    val glyphBaseline = FloatArray(glyphLayouts.size)
    val hanSweeps = nativeContinuousHanSweeps(layout.layoutInput.text.text, segments, segSlow, cssPx)
    val slowSweepGeometry = nativeSlowSweepGeometry(segments)
    val animationStartMs: Long = segments.minOfOrNull { it.maskStartMs } ?: 0L
    val animationEndMs: Long = segments.indices.maxOfOrNull { index ->
        val segment = segments[index]
        if (segSlow[index] > 0f) {
            val units = segment.letterUnits.coerceAtLeast(1)
            val duration = nativeSlowMotionDurationMs(segment).toDouble()
            val lastLetterDelay = duration *
                (units - 1) / units * NATIVE_SLOW_LETTER_STAGGER_RATIO
            maxOf(
                segment.maskEndMs,
                segment.slowStartMs +
                    (lastLetterDelay + duration * NATIVE_SLOW_FLOAT_DURATION_RATIO).toLong(),
            )
        } else {
            maxOf(
                segment.maskEndMs,
                segment.wordStartMs + nativeOrdinaryMotionDurationMs(segment),
            )
        }
    } ?: 0L
}

private data class NativeSlowSweepGeometry(val originX: Float, val width: Float)

// 一个慢词只移动一条色带。软换行只改变绘制位置，不重新开始扫色；字母宽窄也
// 不再改变色带速度。不同 timing part 仍保留各自真实的发音窗口。
private fun nativeSlowSweepGeometry(segments: List<NativeLyricSegment>): Array<NativeSlowSweepGeometry> {
    val result = Array(segments.size) { NativeSlowSweepGeometry(0f, 1f) }
    var start = 0
    while (start < segments.size) {
        val first = segments[start]
        var end = start + 1
        while (end < segments.size &&
            segments[end].tokenStartChar == first.tokenStartChar &&
            segments[end].tokenEndChar == first.tokenEndChar
        ) end++
        var width = 0f
        for (index in start until end) width += (segments[index].right - segments[index].left).coerceAtLeast(1f)
        var offset = 0f
        for (index in start until end) {
            val segment = segments[index]
            result[index] = NativeSlowSweepGeometry(segment.left - offset, width)
            offset += (segment.right - segment.left).coerceAtLeast(1f)
        }
        start = end
    }
    return result
}

// 紧邻汉字共用一个色带前沿。控制点绑定原始发音时间，单调插值保留字间速度，
// 避免每个字从自身左侧重新开始，也不引入独立的逐字动画控制器。
private class NativeContinuousHanSweep(
    private val times: LongArray,
    private val edges: FloatArray,
    val fadeWidth: Float,
) {
    private val slopes = FloatArray(times.size).also { result ->
        val speeds = FloatArray(times.size - 1) { index ->
            (edges[index + 1] - edges[index]) / (times[index + 1] - times[index]).toFloat()
        }
        // 连续发音段的两端落到静止，避免色带到达边界后从非零速度瞬间硬停。
        // 段内仍共享速度；控制点和真实起唱时间保持不变。
        result[0] = 0f
        result[result.lastIndex] = 0f
        for (index in 1 until result.lastIndex) {
            val before = speeds[index - 1]
            val after = speeds[index]
            val beforeDuration = (times[index] - times[index - 1]).toFloat()
            val afterDuration = (times[index + 1] - times[index]).toFloat()
            val beforeWeight = 2f * afterDuration + beforeDuration
            val afterWeight = afterDuration + 2f * beforeDuration
            result[index] = if (before > 0f && after > 0f) {
                (beforeWeight + afterWeight) / (beforeWeight / before + afterWeight / after)
            } else 0f
        }
    }

    fun edgeAt(positionMs: Long): Float {
        if (positionMs <= times.first()) return edges.first()
        if (positionMs >= times.last()) return edges.last()
        var left = 0
        var right = times.lastIndex
        while (right - left > 1) {
            val middle = (left + right) / 2
            if (times[middle] <= positionMs) left = middle else right = middle
        }
        val duration = (times[right] - times[left]).toFloat()
        val t = (positionMs - times[left]).toFloat() / duration
        val t2 = t * t
        val t3 = t2 * t
        return (
            (2f * t3 - 3f * t2 + 1f) * edges[left] +
                (t3 - 2f * t2 + t) * duration * slopes[left] +
                (-2f * t3 + 3f * t2) * edges[right] +
                (t3 - t2) * duration * slopes[right]
            ).coerceIn(edges[left], edges[right])
    }
}

private fun nativeContinuousHanSweeps(
    text: String,
    segments: List<NativeLyricSegment>,
    slowAmounts: FloatArray,
    cssPx: Float,
): Array<NativeContinuousHanSweep?> {
    val result = arrayOfNulls<NativeContinuousHanSweep>(segments.size)
    val hanCounts = IntArray(segments.size) { index ->
        val segment = segments[index]
        if (slowAmounts[index] > 0f) return@IntArray 0
        var cursor = segment.startChar
        var count = 0
        while (cursor < segment.endChar) {
            val codePoint = text.codePointAt(cursor)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return@IntArray 0
            cursor += Character.charCount(codePoint)
            count++
        }
        count
    }
    var start = 0
    while (start < segments.size) {
        if (hanCounts[start] == 0) {
            start++
            continue
        }
        var end = start + 1
        while (end < segments.size && hanCounts[end] > 0) {
            val previous = segments[end - 1]
            val next = segments[end]
            val gapMs = next.maskStartMs - previous.maskEndMs
            // 只桥接同一排的紧邻发音；标点、换行、重叠时间和明确停顿各自保留。
            if (previous.line != next.line || previous.endChar != next.startChar ||
                next.left <= previous.left || next.left - previous.right > cssPx * 2f ||
                next.maskStartMs <= previous.maskStartMs || gapMs !in 0L..80L
            ) break
            end++
        }
        val count = end - start
        if (count > 1) {
            var totalWidth = 0f
            var totalHan = 0
            for (index in start until end) {
                totalWidth += segments[index].right - segments[index].left
                totalHan += hanCounts[index]
            }
            val fadeWidth = maxOf(totalWidth / totalHan * 0.25f, cssPx * 4f)
            val times = LongArray(count + 1) { index ->
                if (index < count) segments[start + index].maskStartMs else segments[end - 1].maskEndMs
            }
            val edges = FloatArray(count + 1) { index ->
                if (index < count) segments[start + index].left - fadeWidth else segments[end - 1].right
            }
            val sweep = NativeContinuousHanSweep(times, edges, fadeWidth)
            for (index in start until end) result[index] = sweep
        }
        start = end
    }
    return result
}

// 逐帧绘制要用的所有静态几何（段裁切边界、慢词字形盒、行上下沿、宽度参数）在排版
// 完成时一次算好：draw 每帧跑在 UI 线程，旧实现 per-frame 的布局查询与小对象分配
//（每段 new Bounds/SlowShape、每字 getBoundingBox + 字符串拼 key、filter 列表）
// 是稳定的 GC 压力与 CPU 热源——这正是“歌词页发热/掉帧”的组成部分。
private fun nativeTimedLyricPlan(
    layout: TextLayoutResult,
    chars: List<PipoLyricChar>,
    sourceChars: List<PipoLyricChar>,
    density: Density,
    rasterCache: NativeLyricRasterCache,
): NativeTimedLyricPlan {
    val segments = nativeLyricSegments(layout, chars, sourceChars)
    val style = layout.layoutInput.style
    val fontPx = with(density) { style.fontSize.toPx() }
    val cssPx = with(density) { 1.dp.toPx() }
    val segGlowClipLeft = FloatArray(segments.size)
    val segGlowClipRight = FloatArray(segments.size)
    // 只有光晕需要外围余量；正文由自身固定字形载体约束。
    val glyphClipPad = fontPx * NATIVE_GLYPH_HORIZONTAL_CLIP_PAD_EM
    val slowGlowClipPad = nativeSlowGlowClipPad(density)
    val slowGlowClipPadX = slowGlowClipPad + glyphClipPad
    segments.forEachIndexed { idx, segment ->
        segGlowClipLeft[idx] = segment.left - slowGlowClipPadX
        segGlowClipRight[idx] = segment.right + slowGlowClipPadX
    }
    val text = layout.layoutInput.text.text
    val glyphBoxLeft = FloatArray(text.length) { Float.NaN }
    val glyphBoxRight = FloatArray(text.length) { Float.NaN }
    val glyphSharedInkLeft = FloatArray(text.length) { Float.NaN }
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
            glyphSharedInkLeft[i] = rawBox.left
        }
    }
    val rowTopArr = FloatArray(layout.lineCount) { layout.getLineTop(it) }
    val rowBottomArr = FloatArray(layout.lineCount) { layout.getLineBottom(it) }
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
    return NativeTimedLyricPlan(
        rasters = rasterCache,
        layout = layout,
        segments = segments,
        cssPx = cssPx,
        rowTop = rowTopArr,
        rowBottom = rowBottomArr,
        rowGlowClipTop = rowGlowClipTopArr,
        rowGlowClipBottom = rowGlowClipBottomArr,
        segGlowClipLeft = segGlowClipLeft,
        segGlowClipRight = segGlowClipRight,
        segSlow = segSlow,
        glyphBoxLeft = glyphBoxLeft,
        glyphBoxRight = glyphBoxRight,
        glyphSharedInkLeft = glyphSharedInkLeft,
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
        glowVisibility = focusProgress,
        effectsEnabled = effectsEnabled,
    )
}

private fun DrawScope.drawNativeSegmentSweepText(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    fg: Color,
    activeUnsung: Color,
    positionMs: Long,
    glowVisibility: Float,
    effectsEnabled: Boolean,
) {
    val segments = plan.segments
    val wordLiftPx = plan.cssPx * NATIVE_WORD_LIFT_DP
    var sampledSweep: NativeContinuousHanSweep? = null
    var sweepEdge = 0f

    // 每个 timing segment 的未唱、扫色、唱完都使用同一字形载体，
    // 不再在整句裁片与独立字形之间切换，避免上浮前后基线/字距跳动。
    segments.forEachIndexed { index, segment ->
        if (effectsEnabled && plan.segSlow[index] > 0f) {
            drawNativeSlowSegmentText(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                segmentIndex = index,
                slowAmount = plan.segSlow[index],
                motionPositionMs = positionMs,
                fg = fg,
                activeUnsung = activeUnsung,
                glowVisibility = glowVisibility,
            )
        } else {
            val width = (segment.right - segment.left).coerceAtLeast(1f)
            val sweep = plan.hanSweeps[index]
            if (sweep != null && sweep !== sampledSweep) {
                sampledSweep = sweep
                sweepEdge = sweep.edgeAt(positionMs)
            }
            val solidX = if (sweep != null) sweepEdge else segment.left + width * (
                nativeSegmentFillProgress(segment, positionMs) * NATIVE_APPLE_SWEEP_TRAVEL_RATIO -
                    NATIVE_APPLE_SWEEP_LEAD_RATIO
                )
            val fadeWidth = sweep?.fadeWidth ?: (width * NATIVE_APPLE_SWEEP_LEAD_RATIO)
            val rampEndX = solidX + fadeWidth
            val liftProgress = nativeOrdinarySegmentLiftProgress(segment, positionMs)
            drawNativeIsolatedSegmentText(
                plan = plan,
                glyphMeasurer = glyphMeasurer,
                segmentIndex = index,
                contentLeft = segment.left,
                contentRight = segment.right,
                solidX = solidX,
                rampEndX = rampEndX,
                fadeEndX = rampEndX.coerceAtMost(segment.right),
                fg = fg,
                activeUnsung = activeUnsung,
                topLeft = Offset(0f, -wordLiftPx * liftProgress),
            )
        }
    }
}

// 未唱/扫色/唱完复用同一独立排版和固定栅格，并按原句 baseline 对齐。
// 渐变坐标换算为排版本地 X；只有 transform 改变屏幕位置。
private fun DrawScope.drawNativeIsolatedSegmentText(
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
    // 位置仍由整句 Paragraph 度量；各状态都复用同一独立 TextLayout 的固定载体。
    // Latin glyph 常有正/负 side bearing，segment.left 是 advance + ink 的外包框，
    // 不能作为两套排版的共同原点；否则切入/退出动画时会水平跳 1px 左右。
    // 用原 Paragraph 的真实 ink left 对齐独立 layout 的 ink left，混排字体也保持原位。
    val originX = segment.sharedInkLeft - inkLeft + topLeft.x
    val y = plan.layout.getLineBaseline(segment.line) -
        segmentLayout.getLineBaseline(0) +
        topLeft.y
    val segmentTopLeft = Offset(originX, y)
    when {
        solidX >= contentRight -> drawNativeStableText(plan.rasters, segmentLayout, color = fg, topLeft = segmentTopLeft)
        fadeEndX <= contentLeft -> drawNativeStableText(plan.rasters, segmentLayout, color = activeUnsung, topLeft = segmentTopLeft)
        rampEndX <= solidX + 0.5f -> {
            val edge = solidX.coerceIn(contentLeft, contentRight)
            val edgeBrush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = edge - originX,
                endX = edge - originX + 1f,
            )
            drawNativeStableText(plan.rasters, segmentLayout, brush = edgeBrush, topLeft = segmentTopLeft, alpha = 1f)
        }
        else -> {
            val brush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = solidX - originX,
                endX = rampEndX - originX,
            )
            drawNativeStableText(plan.rasters, segmentLayout, brush = brush, topLeft = segmentTopLeft, alpha = 1f)
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

// 扫色只改变同色文字的透明度，色带宽度由音节/字母自己的 advance 决定。
private fun nativeSweepTransitionBrush(
    fg: Color,
    activeUnsung: Color,
    startX: Float,
    endX: Float,
): Brush = Brush.horizontalGradient(
    colorStops = arrayOf(0f to fg, 1f to activeUnsung),
    startX = startX,
    endX = endX.coerceAtLeast(startX + 1f),
    tileMode = TileMode.Clamp,
)

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

private fun DrawScope.drawNativeSlowSegmentText(
    plan: NativeTimedLyricPlan,
    glyphMeasurer: TextMeasurer,
    segmentIndex: Int,
    slowAmount: Float,
    motionPositionMs: Long,
    fg: Color,
    activeUnsung: Color,
    glowVisibility: Float,
) {
    // Apple Web 使用固定 1s 的逐字强调；这里参考 AMLL 的长音包络与 additive float，
    // 让长词持续运动、相邻字母充分重叠。两条运动均由同一个媒体位置解析求值，
    // 直接合成矩阵，不启动独立时钟，也不改变歌词源的发音/扫色时序。
    val layout = plan.layout
    val segment = plan.segments[segmentIndex]
    val lineTop = plan.rowTop[segment.line]
    val lineBottom = plan.rowBottom[segment.line]

    val text = layout.layoutInput.text.text
    val clipTop = plan.rowGlowClipTop[segment.line]
    val clipBottom = plan.rowGlowClipBottom[segment.line]
    val glyphCenterY = (lineTop + lineBottom) * 0.5f
    val glowClipLeft = plan.segGlowClipLeft[segmentIndex]
    val glowClipRight = plan.segGlowClipRight[segmentIndex]
    val lineBaseline = layout.getLineBaseline(segment.line)

    val segStartMs = segment.slowStartMs
    val motionDurationMs = nativeSlowMotionDurationMs(segment)
    val empGain = slowAmount.coerceIn(0f, 1f)
    // 这些是 CSS 固定 px keyframe，不应随移动端字号从 22 放到 34 后再同比放大。
    val cssPxToLocal = plan.cssPx
    val letterStepMs = motionDurationMs /
        segment.letterUnits.coerceAtLeast(1).toFloat() * NATIVE_SLOW_LETTER_STAGGER_RATIO
    val baseLift = nativeSegmentLiftProgress(segment, motionPositionMs)
    // 颜色与普通词共用 20% 过渡带和真实发音进度，形变错峰不改扫色速度。
    val sweep = plan.slowSweepGeometry[segmentIndex]
    val fillProgress = nativeSegmentFillProgress(segment, motionPositionMs)
    val fadeWidth = sweep.width * NATIVE_APPLE_SWEEP_LEAD_RATIO
    val solidX = sweep.originX + sweep.width * (
        fillProgress * NATIVE_APPLE_SWEEP_TRAVEL_RATIO - NATIVE_APPLE_SWEEP_LEAD_RATIO
    )
    val rampEndX = solidX + fadeWidth
    var glyphOrdinal = segment.letterOrdinalStart
    for (i in segment.startChar until segment.endChar) {
        if (i >= plan.glyphBoxLeft.size) break
        if (nativeAppleStripsSyllableChar(text[i])) continue
        val boxLeft = plan.glyphBoxLeft[i]
        if (boxLeft.isNaN()) continue
        val boxRight = plan.glyphBoxRight[i]
        val sharedInkLeft = plan.glyphSharedInkLeft.getOrNull(i)
            ?.takeIf { it.isFinite() }
            ?: boxLeft
        val currentGlyphOrdinal = glyphOrdinal
        glyphOrdinal++
        val motionLetterStartMs = segStartMs.toFloat() + letterStepMs * currentGlyphOrdinal
        // 光效和形变保留平滑尾程，扫色独立读取同一媒体时刻的真实发音进度。
        val elapsedMs = motionPositionMs.toFloat() - motionLetterStartMs
        val phase = (elapsedMs / motionDurationMs).coerceIn(0f, 1f)
        val floatPhase = (elapsedMs / (motionDurationMs * NATIVE_SLOW_FLOAT_DURATION_RATIO))
            .coerceIn(0f, 1f)
        val glyphLayout = nativeSlowGlyphLayout(
            plan = plan,
            glyphMeasurer = glyphMeasurer,
            text = text,
            index = i,
        ) ?: continue
        val glyphOrigin = Offset(
            x = sharedInkLeft - plan.glyphInkLeft[i],
            y = lineBaseline - plan.glyphBaseline[i],
        )
        // 未开始/已收尾的字复用原字形栅格，跳过逐字矩阵、渐变和光晕计算。
        // 保留同一个字形载体和原始 baseline，切入动态分支时不会换排版或跳位置。
        if (phase <= 0f && rampEndX <= boxLeft) {
            drawNativeStableText(
                plan.rasters,
                glyphLayout,
                color = activeUnsung,
                topLeft = glyphOrigin + Offset(0f, -cssPxToLocal * NATIVE_SLOW_LIFT_SETTLE_WEB_PX * baseLift),
            )
            continue
        }
        if (floatPhase >= 1f && solidX >= boxRight) {
            drawNativeStableText(
                plan.rasters,
                glyphLayout,
                color = fg,
                topLeft = glyphOrigin + Offset(0f, -cssPxToLocal * empGain * NATIVE_SLOW_LIFT_SETTLE_WEB_PX),
            )
            continue
        }
        val emphasis = (if (phase < 0.5f) {
            NATIVE_SLOW_ATTACK_EASE.transform(phase * 2f)
        } else {
            1f - NATIVE_SLOW_RELEASE_EASE.transform((phase - 0.5f) * 2f)
        }) * empGain
        val floatLift = kotlin.math.sin(floatPhase * kotlin.math.PI).toFloat()
        val motionEmPx = cssPxToLocal * NATIVE_APPLE_WEB_LINE_FONT_PX
        val letterScaleValue = 1f + (NATIVE_SLOW_SCALE_PEAK - 1f) * emphasis
        val letterTranslateXPx = motionEmPx * NATIVE_SLOW_SPREAD_EM * emphasis *
            (currentGlyphOrdinal - (segment.letterUnits - 1) * 0.5f)
        val letterTranslateYPx = -cssPxToLocal * NATIVE_SLOW_LIFT_SETTLE_WEB_PX * baseLift -
            motionEmPx * (NATIVE_SLOW_FLOAT_EM * floatLift * empGain + NATIVE_SLOW_SHAPE_LIFT_EM * emphasis)
        // 固定 blur 半径，只连续改变光晕透明度，避免每帧以新半径重建阴影。
        val shadowBlurPx = cssPxToLocal * NATIVE_SLOW_SHADOW_PEAK_WEB_PX
        val shadowOpacity = NATIVE_SLOW_SHADOW_PEAK_ALPHA * emphasis
        val glyphCenter = (boxLeft + boxRight) * 0.5f
        val fadeEndX = rampEndX.coerceAtMost(boxRight)
        fun DrawScope.drawGlyph(lineTopLeft: Offset) {
            val glyphTopLeft = Offset(
                // 与普通 segment 一样，逐字母排版也锚定回原 Paragraph 的 ink left。
                // 尤其是中英文混排时，不能用包含 advance side bearing 的 boxLeft；
                // 否则 1.05 scale 进入/退出前后会看成字母左右抖动。
                x = glyphOrigin.x + lineTopLeft.x,
                // 用 baseline 对齐而非 line-top 对齐：慢词逐字母走“单字符独立布局”，它永远按
                // 首行度量（首行通常 trim 掉顶部 leading）；而整段布局第 2、3 行的 line box 含
                // 完整顶部 leading。按 getLineTop 对齐时首行恰好对得上，但从第二行起单字符字形
                // 贴 line box 顶绘制、比同行快词整体偏上（“一行没事、两三行从第二行起错位”的根因）。
                // baseline 不受 leading/trim 影响，对齐到整段对应行 baseline 即与快词同底；
                // 这里只改 y 参考点，不动单字符布局本身。
                y = glyphOrigin.y + lineTopLeft.y,
            )
            if (shadowOpacity * glowVisibility > 0.004f && shadowBlurPx > 0.2f) {
                drawNativeSlowGlyphGlow(
                    glyphLayout = glyphLayout,
                    topLeft = glyphTopLeft,
                    blurPx = shadowBlurPx,
                    opacity = shadowOpacity * glowVisibility,
                    clipTop = clipTop,
                    clipBottom = clipBottom,
                    glowLeft = glowClipLeft,
                    glowRight = glowClipRight,
                )
            }
            drawNativeSlowGlyphSweepText(
                rasters = plan.rasters,
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

        translate(left = letterTranslateXPx, top = letterTranslateYPx) {
            scale(
                scaleX = letterScaleValue,
                scaleY = letterScaleValue,
                pivot = Offset(glyphCenter, glyphCenterY),
            ) {
                drawGlyph(lineTopLeft = Offset.Zero)
            }
        }
    }

}

// 慢词逐字母：每个字母本就是「独立单字排版」（overflow=Visible、行高 1.70em）按 baseline
// 对齐绘制，sweep 文本不套垂直裁切，descender 天然完整。这里对所有字母（含 g/j/y/p/q）
// 一律走同一条横向扫色渐变，不再对下伸字母单列单色分支——保证下伸字母同样有逐字扫光。
private fun DrawScope.drawNativeSlowGlyphSweepText(
    rasters: NativeLyricRasterCache,
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
        solidX >= glyphRight -> drawNativeStableText(rasters, glyphLayout, color = fg, topLeft = topLeft)
        fadeEndX <= glyphLeft -> drawNativeStableText(rasters, glyphLayout, color = activeUnsung, topLeft = topLeft)
        else -> {
            val brush = nativeSweepTransitionBrush(
                fg = fg,
                activeUnsung = activeUnsung,
                startX = solidX - topLeft.x,
                endX = rampEndX.coerceAtLeast(solidX + 1f) - topLeft.x,
            )
            drawNativeStableText(rasters, glyphLayout, brush = brush, topLeft = topLeft, alpha = 1f)
        }
    }
}

// Apple 的单层白色 text-shadow。这里只画光晕，正文使用不带阴影的固定字形栅格，
// 不会把本次 shadow 状态带进正文。安全范围按行坐标计算，不再叠加字母 baseline。
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
    }.getOrNull()?.also {
        plan.glyphLayouts[index] = it
        plan.glyphInkLeft[index] = it.getBoundingBox(0).left
        plan.glyphBaseline[index] = it.getLineBaseline(0)
    }
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
    val sharedInkLeft: Float,
    val maskStartMs: Long,
    val maskEndMs: Long,
    val wordStartMs: Long,
    val wordEndMs: Long,
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

private data class NativePronunciationSlice(
    val timing: PipoLyricChar,
    val sourceTiming: PipoLyricChar,
    val startOffset: Int,
    val endOffset: Int,
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
        val pronunciationSlices = nativePronunciationSlices(
            timing = timing,
            sourceTiming = sourceTiming,
        )
        pronunciationSlices.forEach { slice ->
            val sliceStart = (start + slice.startOffset).coerceIn(start, end)
            val sliceEnd = (start + slice.endOffset).coerceIn(sliceStart, end)
            var segStart = sliceStart
            while (segStart < sliceEnd) {
                val line = layout.getLineForOffset(segStart)
                val lineEnd = minOf(
                    sliceEnd,
                    layout.getLineEnd(line, visibleEnd = true).coerceAtLeast(segStart + 1),
                )
                nativeAddSegment(
                    out = segments,
                    layout = layout,
                    timing = slice.timing,
                    sourceTiming = slice.sourceTiming,
                    wordStartMs = sourceTiming.startMs,
                    wordEndMs = sourceTiming.effectiveEndMs(),
                    start = segStart,
                    end = lineEnd,
                    tokenStart = sliceStart,
                    tokenEnd = sliceEnd,
                    line = line,
                )
                segStart = lineEnd
            }
        }
    }
    return segments
}

/**
 * Parser 为了排版会把无空格的英文 span 合回一个单词，但原 span 的发音起止仍保存在
 * timingParts。这里把可靠且能与显示文本精确对齐的 parts 恢复成动画片段；显示字符串
 * 不拆，因此单词宽度、换行和字距完全不变。无法精确映射时保守回退到原 token。
 */
private fun nativePronunciationSlices(
    timing: PipoLyricChar,
    sourceTiming: PipoLyricChar,
): List<NativePronunciationSlice> {
    fun fallback(): List<NativePronunciationSlice> {
        return listOf(
            NativePronunciationSlice(
                timing = timing,
                sourceTiming = sourceTiming,
                startOffset = 0,
                endOffset = timing.text.length,
            ),
        )
    }

    val parts = sourceTiming.timingPartsForProgress()
    if (parts.size <= 1) return fallback()
    var previousStartMs = Long.MIN_VALUE
    val displayParts = ArrayList<String>(parts.size)
    for (part in parts) {
        if (part.text.isEmpty() || part.durationMs <= 0L || part.startMs < previousStartMs) {
            return fallback()
        }
        previousStartMs = part.startMs
        displayParts.add(nativeAppleSyllableDisplayText(part.text))
    }
    if (displayParts.joinToString(separator = "") != timing.text) return fallback()

    val slices = ArrayList<NativePronunciationSlice>(parts.size)
    var offset = 0
    var pendingStartOffset: Int? = null
    val pendingParts = ArrayList<PipoLyricTimingPart>()

    fun appendSlice(
        startOffset: Int,
        endOffset: Int,
        includedParts: List<PipoLyricTimingPart>,
    ) {
        if (endOffset <= startOffset || includedParts.isEmpty()) return
        val sliceDisplayText = timing.text.substring(startOffset, endOffset)
        val sliceSourceText = includedParts.joinToString(separator = "") { it.text }
        val sliceStartMs = includedParts.minOf { it.startMs }
        val sliceEndMs = includedParts.maxOf { part ->
            part.startMs + part.durationMs.coerceAtLeast(1L)
        }.coerceAtLeast(sliceStartMs + 1L)
        slices.add(
            NativePronunciationSlice(
                timing = PipoLyricChar(
                    startMs = sliceStartMs,
                    durationMs = sliceEndMs - sliceStartMs,
                    text = sliceDisplayText,
                    timingParts = includedParts,
                ),
                sourceTiming = PipoLyricChar(
                    startMs = sliceStartMs,
                    durationMs = sliceEndMs - sliceStartMs,
                    text = sliceSourceText,
                    timingParts = includedParts,
                ),
                startOffset = startOffset,
                endOffset = endOffset,
            ),
        )
    }

    parts.forEachIndexed { index, part ->
        val displayPart = displayParts[index]
        val partStartOffset = offset
        val partEndOffset = offset + displayPart.length
        offset = partEndOffset
        val hasPronunciation = displayPart.any(::nativeIsPronunciationChar)
        when {
            hasPronunciation -> {
                val startOffset = pendingStartOffset ?: partStartOffset
                val included = if (pendingParts.isEmpty()) {
                    listOf(part)
                } else {
                    pendingParts.toList() + part
                }
                appendSlice(startOffset, partEndOffset, included)
                pendingStartOffset = null
                pendingParts.clear()
            }
            slices.isNotEmpty() -> {
                val previous = slices.removeAt(slices.lastIndex)
                val included = previous.sourceTiming.timingPartsForProgress() + part
                appendSlice(previous.startOffset, partEndOffset, included)
            }
            else -> {
                if (pendingStartOffset == null) pendingStartOffset = partStartOffset
                pendingParts.add(part)
            }
        }
    }
    if (pendingParts.isNotEmpty() && slices.isNotEmpty()) {
        val previous = slices.removeAt(slices.lastIndex)
        val included = previous.sourceTiming.timingPartsForProgress() + pendingParts
        appendSlice(previous.startOffset, timing.text.length, included)
    }
    return slices.takeIf {
        it.isNotEmpty() &&
            it.first().startOffset == 0 &&
            it.last().endOffset == timing.text.length
    } ?: fallback()
}

private fun nativeAddSegment(
    out: MutableList<NativeLyricSegment>,
    layout: TextLayoutResult,
    timing: PipoLyricChar,
    sourceTiming: PipoLyricChar,
    wordStartMs: Long,
    wordEndMs: Long,
    start: Int,
    end: Int,
    tokenStart: Int,
    tokenEnd: Int,
    line: Int,
) {
    val text = layout.layoutInput.text.text
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var sharedInkLeft = Float.POSITIVE_INFINITY
    for (i in start until end) {
        // Apple 的 gradient 宽度来自整个 `.syllable` inline box，不是 glyph ink bounds。
        // Android 旧版只合并 getBoundingBox() 墨迹，窄字/标点的 20% 过渡带会缩成硬切。
        // 这里使用每个字符的 advance 区间，并把负悬伸墨迹并入安全边界；尾随空白仍
        // 不属于 syllable（Apple 把词间距放在 outer group 的 ::after）。
        if (i in text.indices && (text[i].isWhitespace() || text[i].isISOControl())) continue
        val advanceStart = layout.getHorizontalPosition(i, usePrimaryDirection = true)
        val advanceEnd = layout.getHorizontalPosition((i + 1).coerceAtMost(text.length), usePrimaryDirection = true)
        val box = layout.getBoundingBox(i)
        if (box.right > box.left) {
            sharedInkLeft = minOf(sharedInkLeft, box.left)
        }
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
                sharedInkLeft = if (sharedInkLeft.isFinite()) sharedInkLeft else left,
                maskStartMs = timing.startMs,
                maskEndMs = nativeAppleSyllableEndMs(timing),
                wordStartMs = wordStartMs,
                wordEndMs = wordEndMs,
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

private fun nativeIsPronunciationChar(ch: Char): Boolean {
    return ch.isLetterOrDigit() || nativeIsCjkChar(ch)
}

// 普通词参考 AMLL float：至少 1s 的 ease-out 上浮，快速短词在停顿期间仍有运动
// 尾程，后续词接力时不会整句反复起停。拆开的 timing parts 共享完整词的运动窗口，
// 颜色依旧由各片段真实起止求值；不增加播放延迟或提前高亮。
private fun nativeOrdinaryMotionDurationMs(segment: NativeLyricSegment): Long =
    (segment.wordEndMs - segment.wordStartMs).coerceAtLeast(NATIVE_ORDINARY_LIFT_MIN_DURATION_MS)

private fun nativeOrdinarySegmentLiftProgress(segment: NativeLyricSegment, positionMs: Long): Float =
    NATIVE_ORDINARY_LIFT_EASE.transform(
        ((positionMs - segment.wordStartMs).toFloat() / nativeOrdinaryMotionDurationMs(segment).toFloat())
            .coerceIn(0f, 1f),
    )

// 慢词的基础落点沿用已有 600ms 包络，额外形变和浮动由慢词自身时长决定。
private fun nativeSegmentLiftProgress(
    segment: NativeLyricSegment,
    positionMs: Long,
): Float {
    return nativeSmoothLiftProgress(
        (positionMs - segment.wordStartMs).toFloat() /
            NATIVE_WORD_LIFT_DURATION_MS.toFloat(),
    )
}

private fun nativeSmoothLiftProgress(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
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
    timelineEndMs: Long = segment.maskEndMs,
): Float {
    // Apple Web gives every `.syllable` one linear data-delay/data-duration
    // timeline. Store that window in NativeLyricSegment so draw frames do not
    // need to walk back through the token object for every segment.
    val maskStartMs = segment.maskStartMs
    val maskEndMs = timelineEndMs.coerceAtLeast(maskStartMs + 1L)
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

private fun nativeSlowMotionDurationMs(segment: NativeLyricSegment): Float =
    (segment.slowEndMs - segment.slowStartMs).coerceAtLeast(NATIVE_SLOW_WORD_MIN_DURATION_MS).toFloat()

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
    positionProvider: (() -> Long)?,
    isPlaying: Boolean,
    sessionKey: String,
    initialRawPositionMs: Long,
): State<Float> {
    val out = remember(sessionKey) {
        mutableFloatStateOf(initialRawPositionMs.coerceAtLeast(0L).toFloat())
    }
    val anchor = remember(sessionKey) { NativeLyricClockAnchor() }
    val currentProvider = rememberUpdatedState(positionProvider)
    LaunchedEffect(rawPositionState, isPlaying, sessionKey) {
        fun readPositionMs(): Float =
            (currentProvider.value?.invoke() ?: rawPositionState.value).coerceAtLeast(0L).toFloat()
        fun source() = currentProvider.value as? LyricPlaybackPositionProvider
        fun speed(): Float = source()?.playbackSpeed?.takeIf { it.isFinite() && it > 0f } ?: 1f

        fun alignToSource() {
            val mediaMs = readPositionMs()
            val discontinuity = source()?.discontinuitySequence
            out.floatValue = if (!anchor.initialized || discontinuity != anchor.discontinuity ||
                source() == null || !isPlaying
            ) {
                mediaMs
            } else {
                // 恢复播放不重播已画出的文字；暂停和显式跳转则对齐实际停点。
                maxOf(out.floatValue, mediaMs)
            }
            anchor.initialized = true
            anchor.discontinuity = discontinuity
        }

        alignToSource()
        if (!isPlaying) {
            snapshotFlow { rawPositionState.value to source()?.discontinuitySequence }.collect {
                alignToSource()
            }
        } else {
            var lastFrameNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    val mediaMs = readPositionMs()
                    val playbackSource = source()
                    val discontinuity = playbackSource?.discontinuitySequence
                    val dtMs = (frameNanos - lastFrameNanos) / 1_000_000f
                    val rate = speed()
                    val predicted = out.floatValue + dtMs * rate
                    val error = mediaMs - predicted
                    val correctedPositionMs = when {
                        discontinuity != anchor.discontinuity -> mediaMs
                        // 实时 provider 已按真实倍率推进，不再叠加第二条预测时钟。
                        // 数据源短暂回调位置时保持已画出的进度，等媒体追上后立即同速，
                        // 避免只降速 5% 导致百毫秒的领先持续数秒。
                        playbackSource != null -> maxOf(out.floatValue, mediaMs)
                        kotlin.math.abs(error) > 350f -> mediaMs
                        lastFrameNanos == 0L || dtMs > 250f -> maxOf(out.floatValue, mediaMs)
                        error > 40f -> mediaMs
                        else -> {
                            // 静态预览等没有实时 provider 的入口仍对位置快照插值。
                            val correction = error * (dtMs / 160f).coerceAtMost(1f)
                            maxOf(out.floatValue, predicted + correction)
                        }
                    }
                    if (lastFrameNanos != 0L && kotlin.math.abs(error) > 80f &&
                        frameNanos >= anchor.nextDriftReportNanos
                    ) {
                        anchor.nextDriftReportNanos = frameNanos + 5_000_000_000L
                        app.pipo.nativeapp.DiagnosticsLogStore.record(
                            area = "lyrics",
                            event = "clock_correction",
                            fields = mapOf(
                                "session" to sessionKey, "mediaMs" to mediaMs.toLong(),
                                "visualMs" to out.floatValue.toLong(), "errorMs" to error.toLong(),
                                "clockSource" to if (playbackSource != null) "player" else "snapshot",
                                "correctedVisualMs" to correctedPositionMs.toLong(),
                                "residualErrorMs" to (mediaMs - correctedPositionMs).toLong(),
                                "playbackSpeed" to rate, "frameMs" to dtMs,
                                "discontinuity" to (discontinuity != anchor.discontinuity),
                            ),
                        )
                    }
                    out.floatValue = correctedPositionMs
                    lastFrameNanos = frameNanos
                    anchor.discontinuity = discontinuity
                }
            }
        }
    }
    return out
}

private class NativeLyricClockAnchor {
    var initialized = false
    var discontinuity: Long? = null
    var nextDriftReportNanos = 0L
}

// 手动回位/歌词 seek 使用官网 350ms easeInOutQuad；自动切句使用可连续重定向的共享 spring。
private fun nativeScrollFollowAnimationSpec(): AnimationSpec<Float> = tween(
    durationMillis = NATIVE_SCROLL_FOLLOW_MS,
    easing = NATIVE_SCROLL_EASE_IN_OUT_QUAD,
)

private class NativeScrollMotionHistory(
    initialTimeMs: Long,
    initialCenter: Float,
) {
    private val times = LongArray(180)
    private val centers = FloatArray(180)
    private var head = 0
    private var count = 0

    val latestTimeMs: Long
        get() = times[sampleIndex(count - 1)]

    private fun sampleIndex(offset: Int): Int = (head + offset) % times.size

    init {
        reset(initialTimeMs, initialCenter)
    }

    fun reset(timeMs: Long, center: Float) {
        head = 0
        count = 1
        times[0] = timeMs
        centers[0] = center
    }

    fun shift(delta: Float) {
        for (i in 0 until count) centers[sampleIndex(i)] += delta
    }

    fun record(timeMs: Long, center: Float) {
        val lastIndex = sampleIndex(count - 1)
        if (timeMs <= times[lastIndex]) {
            centers[lastIndex] = center
            return
        }
        if (count == times.size) {
            head = (head + 1) % times.size
            count--
        }
        val nextIndex = sampleIndex(count)
        times[nextIndex] = timeMs
        centers[nextIndex] = center
        count++
        val cutoffMs = timeMs - 2_000L
        while (count > 2 && times[sampleIndex(1)] <= cutoffMs) {
            head = (head + 1) % times.size
            count--
        }
    }

    fun centerAt(timeMs: Long): Float {
        if (timeMs <= times[head]) return centers[head]
        if (timeMs >= latestTimeMs) return centers[sampleIndex(count - 1)]
        var low = 1
        var high = count - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[sampleIndex(mid)] < timeMs) low = mid + 1 else high = mid
        }
        val previous = sampleIndex(low - 1)
        val next = sampleIndex(low)
        val durationMs = (times[next] - times[previous]).coerceAtLeast(1L)
        val fraction = ((timeMs - times[previous]).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        return centers[previous] + (centers[next] - centers[previous]) * fraction
    }
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
        // 但不进入 mainPrefix，所以出现时不会改 rowAnchor/scrollPosition target。
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
private const val NATIVE_SWEEP_PROGRESS_EPS = 0.001f
// Apple Web syllable gradient: --gradient-progress 从 -20% 走到 100%，未唱 stop 后移 20%。
private const val NATIVE_APPLE_SWEEP_LEAD_RATIO = 0.20f
private const val NATIVE_APPLE_SWEEP_TRAVEL_RATIO = 1.20f
// Apple Web constants: 75px is topOffset; currentIndex lookahead is currentPlaybackMillis + 250ms.
private const val NATIVE_SCROLL_FOCUS_LEAD_MS = 250L
private const val NATIVE_SLOW_GLYPH_MIN_ADVANCE_PX = 0.5f
// Apple Web current .syllable/.letter adds 0.75px horizontal padding/inset on a 22px line.
// 共享 paragraph 的静态批绘仍需少量横向抗锯齿余量；运动词使用独立 layout，不再靠它保护。
private const val NATIVE_GLYPH_HORIZONTAL_CLIP_PAD_EM = 1.25f / 22f
// 静态批绘在不越过相邻 line box 的前提下留少量纵向抗锯齿余量。
// g/y/j/p/q 和所有运动词都走独立 layout，因此不再依赖扩大 clip 来保住 descender。
private const val NATIVE_TIMED_GLYPH_LINE_HEIGHT_EM = 1.70f
private const val NATIVE_DESCENDER_SAFE_LINE_HEIGHT_EM = NATIVE_MOBILE_LINE_HEIGHT_RATIO
private const val NATIVE_APPLE_TRAILING_WORD_SPACE = "\u2009"
// ===== 慢词：Apple Web 触发范围 + AMLL 长音运动结构 =====
// Apple Web shouldBeEmphasized：词长 >= 1s 且文本单位 <= 7。
private const val NATIVE_SLOW_WORD_MIN_DURATION_MS = 1_000L
private const val NATIVE_SLOW_WORD_MAX_UNITS = 7
private const val NATIVE_APPLE_WEB_LINE_FONT_PX = 22f
// https://github.com/amll-dev/applemusic-like-lyrics/blob/5c0959686d18ca185b81811added3cd31299569f/packages/core/src/lyric-player/dom/animation/emphasize/index.ts
// 保留现有 1.05 峰值与 2px 落点，不采用末词放大或提前 400ms 起动。
private const val NATIVE_SLOW_LETTER_STAGGER_RATIO = 0.4f
private const val NATIVE_SLOW_FLOAT_DURATION_RATIO = 1.4f
private val NATIVE_SLOW_ATTACK_EASE = CubicBezierEasing(0.2f, 0.4f, 0.58f, 1f)
private val NATIVE_SLOW_RELEASE_EASE = CubicBezierEasing(0.3f, 0f, 0.58f, 1f)
private const val NATIVE_SLOW_FLOAT_EM = 0.05f
private const val NATIVE_SLOW_SPREAD_EM = 0.015f
private const val NATIVE_SLOW_SHAPE_LIFT_EM = 0.0125f
private const val NATIVE_SLOW_SCALE_PEAK = 1.05f
private const val NATIVE_SLOW_LIFT_SETTLE_WEB_PX = 2.0f
private const val NATIVE_SLOW_SHADOW_PEAK_WEB_PX = 10f
private const val NATIVE_SLOW_SHADOW_PEAK_ALPHA = 0.40f
private const val NATIVE_SLOW_SHADOW_CLIP_RADIUS_MULTIPLIER = 3f
// Low alpha carrier used only to make Skia emit the Apple-style text-shadow glyph mask.
// It stays visually hidden under the gradient text, but avoids the shadow being optimized away.
private const val NATIVE_SLOW_GLOW_FILL_ALPHA = 0.015f
// Apple Web ordinary syllable y 固定 0 -> -2px，不随 22/34px 字号同比放大。
private const val NATIVE_WORD_LIFT_DP = 2f
private const val NATIVE_WORD_LIFT_DURATION_MS = 600L
// https://github.com/amll-dev/applemusic-like-lyrics/blob/5c0959686d18ca185b81811added3cd31299569f/packages/core/src/lyric-player/dom/animation/float/index.ts
private const val NATIVE_ORDINARY_LIFT_MIN_DURATION_MS = 1_000L
private val NATIVE_ORDINARY_LIFT_EASE = CubicBezierEasing(0f, 0f, 0.58f, 1f)
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
// 副词出现：高度先快速连续展开，视觉再用淡入 + 上滑缓和显现。
// 高度必须快于主歌词 350ms 跟随，避免副词入场拖着整列歌词缓慢位移。
private const val NATIVE_BG_HEIGHT_REVEAL_MS = 220
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
private const val NATIVE_MANUAL_TOP_BOUNCE_FRACTION = 0.18f
private const val NATIVE_MANUAL_BOTTOM_BOUNCE_FRACTION = 0.70f
private const val NATIVE_SCROLL_FOLLOW_MS = 350
private const val NATIVE_OVERFLOW_PENALTY_MULTIPLIER = 1_000.0
private const val NATIVE_CJK_BREAK_PENALTY_RATIO = 0.15
private const val NATIVE_NORMAL_BREAK_PENALTY_RATIO = 0.50
private const val NATIVE_SPACE_BREAK_REWARD_RATIO = 0.40
private const val NATIVE_PUNCTUATION_BREAK_REWARD_RATIO = 0.60
private const val NATIVE_RENDER_PIPELINE_LEAD_MS = 0f
