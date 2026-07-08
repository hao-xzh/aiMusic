package app.pipo.nativeapp.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.pipo.nativeapp.R
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.playback.PlaybackQueueMode
import app.pipo.nativeapp.playback.PlayerViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import kotlin.math.absoluteValue

/** 子页面用的返回回调 —— PipoNativeApp 在 push 进入前提供，返回到 Player root。 */
val LocalOnBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 子页间互跳：DistillScreen 蒸馏完成跳到 TasteScreen 等场景用 */
data class PipoNav(
    val openTaste: () -> Unit,
    val openSettings: () -> Unit,
    val openDistill: () -> Unit,
    val openLogin: () -> Unit,
)
val LocalNav = staticCompositionLocalOf<PipoNav?> { null }

@Composable
fun DistillScreen(repository: PipoRepository = PipoGraph.repository) {
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val account by repository.account.collectAsState(initial = null)
    LaunchedEffect(Unit) {
        repository.refreshAccount()
        repository.refreshPlaylists()
    }
    if (account == null) {
        ScreenScaffold(title = "我的歌单") {
            EmptyState(
                title = "还没登录",
                subtitle = "去设置页扫码或用手机号登录网易云。",
            )
        }
        return
    }
    DistillLibrary(playlists = playlists, repository = repository)
}

private sealed class LibraryPage(
    val title: String,
    val trackCount: Int,
    val coverUrl: String?,
) {
    data class CurrentQueue(
        val queue: List<NativeTrack>,
        val currentIndex: Int,
        val artworkUrl: String?,
    ) : LibraryPage(
        title = "当前播放列表",
        trackCount = queue.size,
        coverUrl = artworkUrl
            ?: queue.getOrNull(currentIndex)?.artworkUrl
            ?: queue.firstOrNull()?.artworkUrl,
    )

    data class PlaylistItem(
        val playlist: PipoPlaylist,
        /** 兜底：当 playlist 自己没有 coverUrl 时拿第一首歌的 artworkUrl 显示，
         *  和"我的网盘"页是同一套思路。一般会在曲目加载完成后回填。 */
        val firstTrackCover: String? = null,
    ) : LibraryPage(
        title = playlist.name,
        trackCount = playlist.trackCount,
        coverUrl = playlist.coverUrl?.takeIf { it.isNotBlank() } ?: firstTrackCover,
    )

    /**
     * "我的网盘"：用户在网易云网盘里上传的全部歌曲。
     * - trackCount 加载完才填，所以初始用 0；UI 拿到 tracks 之后用 size 显示
     * - coverUrl 用网盘里第一首歌的封面（要等 tracks 拿到才能填，初始 null）
     */
    data class CloudDisk(
        val cachedCoverUrl: String?,
        val knownCount: Int,
    ) : LibraryPage(
        title = "我的网盘",
        trackCount = knownCount,
        coverUrl = cachedCoverUrl,
    )
}

private sealed class DistillBrowseMode {
    object List : DistillBrowseMode()
    data class Detail(val key: String) : DistillBrowseMode()
    object SelectForDistill : DistillBrowseMode()
}

private fun LibraryPage.stableKey(): String = when (this) {
    is LibraryPage.CurrentQueue -> "current"
    is LibraryPage.PlaylistItem -> "playlist:${playlist.id}"
    is LibraryPage.CloudDisk -> "cloud"
}

private fun LibraryPage.subtitle(): String = when (this) {
    is LibraryPage.CurrentQueue -> "当前播放 · $trackCount 首"
    is LibraryPage.CloudDisk -> if (trackCount > 0) "网易云网盘 · $trackCount 首" else "网易云网盘"
    is LibraryPage.PlaylistItem -> "歌单 · $trackCount 首"
}

private data class CoverTransitionBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}

@Composable
private fun DistillLibraryHeaderBar(
    browseMode: DistillBrowseMode,
    focusedTitle: String,
    titleAlpha: Float,
    tint: Color,
    navBarColor: Color,
    isDetailMode: Boolean,
    canGoBack: Boolean,
    onBackClick: () -> Unit,
    onDistillClick: () -> Unit,
    onTasteClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(navBarColor)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (canGoBack || browseMode !is DistillBrowseMode.List) {
                    TextButton(
                        onClick = onBackClick,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("←", color = tint, style = TextStyle(fontSize = 22.sp))
                    }
                }
            }
            val headerTitle = when (browseMode) {
                is DistillBrowseMode.List -> "我的歌单"
                is DistillBrowseMode.SelectForDistill -> "选择歌单"
                is DistillBrowseMode.Detail -> focusedTitle
            }
            val headerTitleAlpha = if (browseMode is DistillBrowseMode.Detail) titleAlpha else 1f
            Text(
                headerTitle,
                color = tint.copy(alpha = headerTitleAlpha),
                style = TextStyle(fontSize = 14.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconChip(
                    tint = if (browseMode is DistillBrowseMode.SelectForDistill) PipoColors.Mint else tint,
                    background = if (isDetailMode) Color.Transparent else Color(0x14FFFFFF),
                    onClick = onDistillClick,
                ) {
                    SparkIcon(
                        color = if (browseMode is DistillBrowseMode.SelectForDistill) PipoColors.Mint else tint,
                        modifier = Modifier.size(20.dp),
                    )
                }

                IconChip(
                    tint = tint,
                    background = if (isDetailMode) Color.Transparent else Color(0x14FFFFFF),
                    onClick = onTasteClick,
                ) {
                    ProfileIcon(color = tint, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DistillLibrary(
    playlists: List<PipoPlaylist>,
    repository: PipoRepository,
) {
    val playerVm: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val playerState = playerVm.state
    // 网盘曲目走 repository.cloudTracks Flow —— 冷启动从磁盘 cache 恢复立刻有值，跨
    // DistillLibrary 重挂载不丢，cover-flow 那页 cover/count 始终对得上。initial 也
    // 直接吃同步 cache，避免 collectAsState 第一帧 emptyList 把 tile 抖一下。
    val cloudTracksFlow by repository.cloudTracks.collectAsState(
        initial = repository.cachedTracksFor(app.pipo.nativeapp.data.CLOUD_DISK_PLAYLIST_ID) ?: emptyList(),
    )
    val cloudDiskCover = remember(cloudTracksFlow) {
        cloudTracksFlow.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
    }
    val cloudDiskCount = cloudTracksFlow.size
    LaunchedEffect(cloudDiskCover, cloudDiskCount, repository) {
        if (cloudDiskCover == null) {
            runCatching { repository.cloudDiskTracks() }
        }
    }
    // 没有自带 coverUrl 的歌单（少数）用第一首歌的 artworkUrl 兜底；用 mutableStateMap +
    // derivedStateOf 让 buildList 在 map 写入时自动重算（普通 remember 不订阅 map 内变化）。
    val firstTrackCovers = remember { mutableStateMapOf<Long, String>() }
    val libraryPages by remember(
        playlists,
        playerState.queue,
        playerState.currentIndex,
        playerState.artworkUrl,
        cloudDiskCover,
        cloudDiskCount,
    ) {
        derivedStateOf {
            buildList {
                if (playerState.queue.isNotEmpty()) {
                    add(
                        LibraryPage.CurrentQueue(
                            queue = playerState.queue,
                            currentIndex = playerState.currentIndex,
                            artworkUrl = playerState.artworkUrl,
                        ),
                    )
                }
                // 有真实歌单时保留"我的网盘"入口；没有歌单时只在已缓存/已拉到网盘曲目后展示。
                // 这样账号既无歌单、也无当前队列/网盘缓存时，列表页能进入真正空态。
                if (cloudDiskCount > 0 || playlists.isNotEmpty()) {
                    add(LibraryPage.CloudDisk(cachedCoverUrl = cloudDiskCover, knownCount = cloudDiskCount))
                }
                addAll(
                    playlists.map {
                        LibraryPage.PlaylistItem(
                            playlist = it,
                            firstTrackCover = firstTrackCovers[it.id],
                        )
                    },
                )
            }
        }
    }
    var browseMode by remember { mutableStateOf<DistillBrowseMode>(DistillBrowseMode.List) }
    var browseTransitionLocked by remember { mutableStateOf(false) }
    var focusedPageKey by remember { mutableStateOf<String?>(null) }
    val libraryPageKeys = remember(libraryPages) { libraryPages.map { it.stableKey() } }
    val detailDissolveProgress = remember { Animatable(1f) }
    val detailListAlpha = remember { Animatable(1f) }
    var detailHeroBounds by remember { mutableStateOf<CoverTransitionBounds?>(null) }
    val listCoverBoundsByKey = remember { mutableStateMapOf<String, CoverTransitionBounds>() }
    LaunchedEffect(
        browseMode,
        libraryPageKeys.joinToString(separator = "|"),
    ) {
        val detailKey = (browseMode as? DistillBrowseMode.Detail)?.key
        when {
            libraryPageKeys.isEmpty() -> {
                focusedPageKey = null
                if (detailKey != null) {
                    browseMode = DistillBrowseMode.List
                }
            }
            detailKey != null && detailKey in libraryPageKeys -> {
                focusedPageKey = detailKey
            }
            detailKey != null -> {
                focusedPageKey = libraryPageKeys.first()
                browseMode = DistillBrowseMode.List
                detailDissolveProgress.snapTo(1f)
                detailListAlpha.snapTo(1f)
            }
            focusedPageKey in libraryPageKeys -> Unit
            else -> {
                focusedPageKey = libraryPageKeys.first()
            }
        }
    }
    val focused = libraryPages.firstOrNull { it.stableKey() == focusedPageKey } ?: libraryPages.firstOrNull()
    val focusedListKey = when (val page = focused) {
        is LibraryPage.CurrentQueue -> "current"
        is LibraryPage.PlaylistItem -> "playlist:${page.playlist.id}"
        is LibraryPage.CloudDisk -> "cloud"
        null -> "none"
    }
    // tracks 初始值同步从 repository in-memory cache 灌：进 / 切歌单 / 进网盘的瞬间就有
    // 内容渲染，重入不闪"加载中"。LaunchedEffect 仍会跑一次拿最新数据（cache 命中是同步的，
    // 等同 no-op），未命中 cache 走原本的网络流程；初始 loading 状态也按 cache 命中与否
    // 预设——cache miss 直接进 loading=true，免得先闪一帧"空"再切到"加载中"。
    val cachedInitial: List<NativeTrack>? = remember(focusedListKey) {
        when (val page = focused) {
            is LibraryPage.CurrentQueue -> page.queue
            is LibraryPage.PlaylistItem -> repository.cachedTracksFor(page.playlist.id)
            is LibraryPage.CloudDisk ->
                repository.cachedTracksFor(app.pipo.nativeapp.data.CLOUD_DISK_PLAYLIST_ID)
            null -> emptyList()
        }
    }
    var tracks by remember(focusedListKey) { mutableStateOf(cachedInitial ?: emptyList()) }
    var loading by remember(focusedListKey) { mutableStateOf(cachedInitial == null) }
    var trackLoadError by remember { mutableStateOf<String?>(null) }
    var trackLoadRetry by remember { mutableStateOf(0) }
    val trackListState = rememberLazyListState()
    val focusedQueue = (focused as? LibraryPage.CurrentQueue)?.queue
    val focusedPlaylist = (focused as? LibraryPage.PlaylistItem)?.playlist
    val isDetailMode = browseMode is DistillBrowseMode.Detail
    val detailCoverUrl = focused?.coverUrl
    val focusedCoverEdges = useCoverEdgeColors(detailCoverUrl)
    val detailEdges = if (isDetailMode) focusedCoverEdges else EdgeColors(null, null, null)
    val detailSurfaceColor = appleMusicPureSurfaceColor(detailEdges)
    val detailTopColor = appleMusicPureTopColor(detailEdges, fallback = detailSurfaceColor)
    val detailHeroTextColor = appleMusicDissolveBridgeColor(detailEdges, fallback = detailSurfaceColor)
    val detailTone = toneForColor(detailSurfaceColor)
    val detailFg = if (isDetailMode) pickFg(detailTone) else PipoColors.Ink
    val detailDim = if (isDetailMode) pickFgDim(detailTone) else PipoColors.TextDim
    val detailDivider = detailFg.copy(alpha = if (detailTone == Tone.Dark) 0.13f else 0.16f)
    val detailTopTint = if (isDetailMode) {
        pickFg(toneForColor(detailTopColor))
    } else {
        PipoColors.Ink
    }
    val detailHeroTone = toneForColor(detailHeroTextColor)
    val detailHeroFg = if (isDetailMode) pickFg(detailHeroTone) else PipoColors.Ink
    val detailHeroDim = if (isDetailMode) pickFgDim(detailHeroTone) else PipoColors.TextDim
    val detailNavTint = if (isDetailMode) {
        pickFg(toneForColor(detailSurfaceColor))
    } else {
        PipoColors.Ink
    }
    val detailHeroHeight = playlistDetailHeroHeight()

    // Search and pull-to-refresh state
    var searchQuery by remember { mutableStateOf("") }
    var pullOffset by remember { mutableStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshDragBlockedUntilRelease by remember { mutableStateOf(false) }

    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    val detailTopCoverOffsetPx by remember(trackListState, isDetailMode, detailHeroHeight, localDensity) {
        derivedStateOf {
            if (!isDetailMode) {
                0f
            } else if (trackListState.firstVisibleItemIndex == 0) {
                -trackListState.firstVisibleItemScrollOffset.toFloat()
            } else {
                -with(localDensity) { detailHeroHeight.toPx() }
            }
        }
    }
    val navTitleVisible by remember(trackListState, isDetailMode, focused?.stableKey(), detailHeroHeight, localDensity) {
        derivedStateOf {
            isDetailMode &&
                (trackListState.firstVisibleItemIndex > 0 ||
                    trackListState.firstVisibleItemScrollOffset > with(localDensity) {
                        (detailHeroHeight - 136.dp).toPx()
                    })
        }
    }
    val navTitleAlpha by animateFloatAsState(
        targetValue = if (navTitleVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "playlistNavTitleAlpha",
    )
    val navBarColor by animateColorAsState(
        targetValue = if (navTitleVisible) detailSurfaceColor.copy(alpha = 0.86f) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "playlistNavBarColor",
    )
    val detailHeaderTint by animateColorAsState(
        targetValue = if (navTitleVisible) detailNavTint else detailTopTint,
        animationSpec = tween(durationMillis = 160),
        label = "playlistHeaderTint",
    )
    val refreshThresholdPx = with(localDensity) { 92.dp.toPx() }
    val refreshHoldOffsetPx = with(localDensity) { 38.dp.toPx() }
    val refreshMaxGapPx = with(localDensity) { 54.dp.toPx() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    fun closeSearch() {
        searchQuery = ""
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) {
            tracks
        } else {
            tracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val refreshGapPx by animateFloatAsState(
        targetValue = when {
            pullOffset > 0f -> (pullOffset * 0.62f).coerceAtMost(refreshMaxGapPx)
            isRefreshing -> refreshHoldOffsetPx
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "refreshGap",
    )

    val nestedScrollConnection = remember(focusedListKey, focused, libraryPages) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (source == NestedScrollSource.UserInput && delta < 0) { // scrolling up
                    // 1. If we are currently pulling down (pullOffset > 0)
                    if (pullOffset > 0f) {
                        val consumed = pullOffset.coerceAtMost(-delta)
                        pullOffset -= consumed
                        return Offset(0f, -consumed)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                val isManualPull = source == NestedScrollSource.UserInput
                if (isManualPull && consumed.y > 0f && pullOffset <= 0f) {
                    // If this drag had to scroll the list back to top first, do not treat the
                    // same gesture as a refresh confirmation. Release, then pull again.
                    refreshDragBlockedUntilRelease = true
                }
                if (isManualPull &&
                    delta > 0 &&
                    (focused is LibraryPage.PlaylistItem || focused is LibraryPage.CloudDisk) &&
                    trackListState.firstVisibleItemIndex == 0 &&
                    trackListState.firstVisibleItemScrollOffset == 0 &&
                    !isRefreshing &&
                    searchQuery.isBlank() &&
                    !refreshDragBlockedUntilRelease
                ) {
                    val oldPull = pullOffset
                    pullOffset = (pullOffset + delta).coerceAtMost(refreshThresholdPx)
                    val added = pullOffset - oldPull
                    return Offset(0f, added)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                refreshDragBlockedUntilRelease = false
                if (pullOffset > 0f && !isRefreshing) {
                    val refreshable = focused is LibraryPage.PlaylistItem || focused is LibraryPage.CloudDisk
                    if (refreshable && pullOffset >= refreshThresholdPx) {
                        // Trigger refresh
                        isRefreshing = true
                        pullOffset = 0f
                        scope.launch {
                            when (val focusedPage = focused) {
                                is LibraryPage.PlaylistItem -> {
                                    runCatching {
                                        // 并行刷 playlist 元数据(trackCount/cover/name),
                                        // 让顶部"X 首"数字和列表保持一致;元数据失败不影响 tracks。
                                        coroutineScope {
                                            launch { runCatching { repository.refreshPlaylists() } }
                                            repository.tracksForPlaylist(focusedPage.playlist.id, forceRefresh = true)
                                        }
                                    }.onSuccess { refreshedTracks ->
                                        tracks = refreshedTracks
                                    }
                                }
                                is LibraryPage.CloudDisk -> {
                                    runCatching {
                                        repository.cloudDiskTracks(forceRefresh = true)
                                    }.onSuccess { refreshedTracks ->
                                        // cover/count 自动从 cloudTracks Flow 派生，不用再手动设
                                        tracks = refreshedTracks
                                    }
                                }
                                else -> Unit
                            }
                            isRefreshing = false
                        }
                    } else {
                        pullOffset = 0f
                    }
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(focusedListKey) {
        trackListState.scrollToItem(0)
        // 不再做任何 alpha 过场。试过两种都被用户判为"闪"：
        //   1) snapTo(0.88) + animateTo(1, 160ms): snap 那一帧瞬变 → 小闪
        //   2) animateTo(0) → animateTo(1): 列表完全不可见再出现 → 大闪
        // 干脆让 LazyColumn 自己换内容，背景 blur / 标题 / cover-flow 不动，整体感觉
        // 就是"歌单切了，曲目跟着换"，没有任何附加的明暗过场可被察觉成"闪"。
        searchQuery = ""
        focusManager.clearFocus()
        keyboardController?.hide()
        pullOffset = 0f
        refreshDragBlockedUntilRelease = false
    }
    LaunchedEffect(focusedListKey, focusedQueue, focusedPlaylist, trackLoadRetry) {
        // 关键原则：cache 已经把 tracks 灌成非空时，整个 LaunchedEffect 走"静默 verify"——
        // 不再翻 loading=true，失败也不擦掉已有内容、不报 trackLoadError，只在拿到新数据时
        // 替换 tracks。这样重入 / 切回有缓存的歌单完全 0 闪烁、0 网络抖动感知。
        // tracks 为空（首次进 / 真的没缓存）才走 loading=true → 网请求 → 报错的常规流程。
        when (val page = focused) {
            is LibraryPage.CurrentQueue -> {
                loading = false
                trackLoadError = null
                tracks = page.queue
            }
            is LibraryPage.PlaylistItem -> {
                val hadCache = tracks.isNotEmpty()
                if (!hadCache) {
                    loading = true
                    trackLoadError = null
                }
                val result = runCatching { repository.tracksForPlaylist(page.playlist.id) }
                result.fold(
                    onSuccess = { fresh ->
                        tracks = fresh
                        loading = false
                        // 没自带 cover 的歌单回填首曲 artworkUrl 作 cover-flow 兜底
                        if (page.playlist.coverUrl.isNullOrBlank()) {
                            fresh.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
                                ?.let { firstTrackCovers[page.playlist.id] = it }
                        }
                    },
                    onFailure = {
                        if (!hadCache) {
                            trackLoadError = "这张歌单刚才没拉下来"
                            tracks = emptyList()
                        }
                        // hadCache=true 时静默吞错，保留旧 tracks 让用户继续看
                        loading = false
                    },
                )
            }
            is LibraryPage.CloudDisk -> {
                val hadCache = tracks.isNotEmpty()
                if (!hadCache) {
                    loading = true
                    trackLoadError = null
                }
                val result = runCatching { repository.cloudDiskTracks() }
                result.fold(
                    onSuccess = { fresh ->
                        tracks = fresh
                        loading = false
                        // cover/count 由 cloudTracks Flow 自动派生到 cloudDiskCover/Count
                    },
                    onFailure = {
                        if (!hadCache) {
                            trackLoadError = "网盘列表刚才没拉下来"
                            tracks = emptyList()
                        }
                        loading = false
                    },
                )
            }
            null -> {
                loading = false
                trackLoadError = null
                tracks = emptyList()
            }
        }
    }
    val currentTrackId = playerState.queue.getOrNull(playerState.currentIndex)?.id

    // ---- 蒸馏状态 + 进度 + 多选模式 ----
    // 蒸馏跑在 app-level coordinator（DistillCoordinator），跟 Composable 进出无关。
    // 这里只用它的 StateFlow 来呈现 UI。即使用户切走，蒸馏仍在后台跑。
    val nav = LocalNav.current
    val coordinator = app.pipo.nativeapp.data.PipoGraph.distillCoordinator
    var confirmOpen by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }
    // 可蒸馏列表 = "我的网盘"（有曲目时）+ 真实歌单。网盘是 CloudDisk 特殊页、不在
    // repository.playlists 里，这里合成一条 sentinel id 的 PipoPlaylist 让它能被勾选。
    // tracksForPlaylist(sentinel) 已路由到 cloudDiskTracks，所以蒸馏拿它和普通歌单一样。
    // 没勾就不进 selectedPlaylists → 自然不参与分析（符合"没选就不分析网盘"）。
    val distillablePlaylists = remember(playlists, cloudDiskCount, cloudDiskCover) {
        if (cloudDiskCount > 0) {
            listOf(
                PipoPlaylist(
                    id = app.pipo.nativeapp.data.CLOUD_DISK_PLAYLIST_ID,
                    name = "我的网盘",
                    trackCount = cloudDiskCount,
                    coverUrl = cloudDiskCover,
                ),
            ) + playlists
        } else {
            playlists
        }
    }
    val selectedPlaylists = remember(selectedIds.size, distillablePlaylists) {
        distillablePlaylists.filter { selectedIds[it.id] == true }
    }
    val selectedTrackEstimate = selectedPlaylists.sumOf { it.trackCount }
    LaunchedEffect(browseMode) {
        if (browseMode is DistillBrowseMode.Detail) {
            detailDissolveProgress.snapTo(0f)
            detailListAlpha.snapTo(0f)
            coroutineScope {
                launch {
                    detailDissolveProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 620, easing = PipoMotion.FlipEase),
                    )
                }
                launch {
                    detailListAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 680, easing = PipoMotion.FlipEase),
                    )
                }
            }
        } else {
            detailDissolveProgress.snapTo(1f)
            detailListAlpha.snapTo(1f)
        }
    }

    fun backToList() {
        if (browseTransitionLocked) return
        browseTransitionLocked = true
        selectedIds.clear()
        focusManager.clearFocus()
        keyboardController?.hide()
        scope.launch {
            try {
                detailDissolveProgress.stop()
                detailListAlpha.stop()
                coroutineScope {
                    launch {
                        detailListAlpha.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 110, easing = PipoMotion.CloseEase),
                        )
                    }
                    launch {
                        detailDissolveProgress.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 150, easing = PipoMotion.CloseEase),
                        )
                    }
                }
                browseMode = DistillBrowseMode.List
                detailHeroBounds = null
                detailDissolveProgress.snapTo(1f)
                detailListAlpha.snapTo(1f)
                delay(660)
            } finally {
                browseTransitionLocked = false
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = browseMode !is DistillBrowseMode.List) {
        backToList()
    }

    if (confirmOpen && selectedPlaylists.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("蒸馏 ${selectedPlaylists.size} 张歌单？", color = PipoColors.Ink) },
            text = {
                Text(
                    "总曲目约 $selectedTrackEstimate 首，sample ~200 首喂 AI 写口味画像。约 30 秒。会覆盖之前的画像。后台跑，期间可以正常用 app。",
                    color = PipoColors.TextDim,
                    style = TextStyle(fontSize = 13.sp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    val playlistsToDistill = selectedPlaylists.toList()
                    coordinator.start(
                        playlists = playlistsToDistill,
                        onComplete = {
                            // 蒸馏完后让本地库 cache 也刷新，让 AgentRuntime 召回拿到最新的曲库
                            app.pipo.nativeapp.data.PipoGraph.library.invalidate()
                        },
                    )
                    browseMode = DistillBrowseMode.List
                    selectedIds.clear()
                    // 不再 navigate 到 TasteScreen —— 后台跑期间用户该停在哪由 TA 自己决定
                }) { Text("开始", color = PipoColors.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("取消", color = PipoColors.TextDim) }
            },
            containerColor = PipoColors.Bg1,
        )
    }

    val shellBack = LocalOnBack.current
    val detailDissolveReveal = if (isDetailMode) detailDissolveProgress.value.coerceIn(0f, 1f) else 1f
    val detailListReveal = if (isDetailMode) detailListAlpha.value.coerceIn(0f, 1f) else 1f
    val onHeaderBack: () -> Unit = {
        if (browseMode is DistillBrowseMode.List) {
            shellBack?.invoke()
        } else {
            backToList()
        }
    }
    val onHeaderDistill: () -> Unit = {
        if (browseTransitionLocked) {
            Unit
        } else if (browseMode is DistillBrowseMode.SelectForDistill && selectedPlaylists.isNotEmpty()) {
            confirmOpen = true
        } else {
            browseMode = if (browseMode is DistillBrowseMode.SelectForDistill) {
                selectedIds.clear()
                DistillBrowseMode.List
            } else {
                DistillBrowseMode.SelectForDistill
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0),
    ) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = browseMode,
                transitionSpec = {
                    fadeIn(tween(durationMillis = 120, easing = PipoMotion.FlipEase)) togetherWith
                        fadeOut(tween(durationMillis = 90, easing = PipoMotion.CloseEase))
                },
                label = "libraryBrowseMode",
            ) { renderedBrowseMode ->
                val renderedIsDetailMode = renderedBrowseMode is DistillBrowseMode.Detail
                val animatedVisibilityScope = this@AnimatedContent
                val sharedTransitionScope = this@SharedTransitionLayout
                // 全屏背景：当前焦点歌单封面 blur
                Box(modifier = Modifier.fillMaxSize()) {
                    if (renderedIsDetailMode) {
                        PlaylistDetailBackdrop(
                            coverUrl = detailCoverUrl,
                            edges = detailEdges,
                            showTopCover = false,
                            topCoverHeight = detailHeroHeight,
                            topCoverOffsetPx = detailTopCoverOffsetPx,
                        )
                    } else {
                        AdaptiveDotField(coverUrl = if (renderedBrowseMode is DistillBrowseMode.List) focused?.coverUrl else null)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 12.dp),
                    ) {
                        if (!renderedIsDetailMode) {
                            DistillLibraryHeaderBar(
                                browseMode = renderedBrowseMode,
                                focusedTitle = focused?.title.orEmpty(),
                                titleAlpha = navTitleAlpha,
                                tint = detailHeaderTint,
                                navBarColor = navBarColor,
                                isDetailMode = false,
                                canGoBack = shellBack != null,
                                onBackClick = onHeaderBack,
                                onDistillClick = onHeaderDistill,
                                onTasteClick = { nav?.openTaste?.invoke() },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                val pageBack = shellBack

                if (renderedBrowseMode is DistillBrowseMode.SelectForDistill) {
                    // 多选模式：歌单 + checkbox 列表 + 底部"开始 N 张"操作
                    Row(
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "选择歌单蒸馏",
                            color = PipoColors.Ink,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedPlaylists.isNotEmpty()) {
                            TextButton(onClick = { selectedIds.clear() }) {
                                Text("全清", color = PipoColors.TextDim, style = TextStyle(fontSize = 12.sp))
                            }
                        } else {
                            TextButton(onClick = { distillablePlaylists.forEach { selectedIds[it.id] = true } }) {
                                Text("全选", color = PipoColors.TextDim, style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
                    ) {
                        itemsIndexed(
                            items = distillablePlaylists,
                            key = { index, p -> "${p.id}:$index" },
                        ) { _, p ->
                            val checked = selectedIds[p.id] == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedIds[p.id] = !checked }
                                    .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // cover thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x16FFFFFF)),
                                ) {
                                    if (!p.coverUrl.isNullOrBlank()) {
                                        coil.compose.AsyncImage(
                                            model = p.coverUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        p.name,
                                        color = PipoColors.Ink,
                                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${p.trackCount} 首",
                                        color = PipoColors.TextDim,
                                        style = TextStyle(fontSize = 11.sp),
                                    )
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selectedIds[p.id] = it },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = PipoColors.Mint,
                                        uncheckedColor = Color(0x55FFFFFF),
                                        checkmarkColor = PipoColors.Bg0,
                                    ),
                                )
                            }
                        }
                    }
                    return@Column  // 跳过下方 cover-flow / 歌单标题 / 曲目列表
                }

                if (renderedBrowseMode is DistillBrowseMode.List) {
                    LibraryListContent(
                        pages = libraryPages,
                        focusedKey = focusedPageKey,
                        canPlayFocused = tracks.isNotEmpty(),
                        loadingFocused = loading && tracks.isEmpty(),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onFocusedPage = { page ->
                            focusedPageKey = page.stableKey()
                        },
                        onCoverBounds = { pageKey, bounds ->
                            listCoverBoundsByKey[pageKey] = bounds
                        },
                        onOpenPage = { page, coverBounds ->
                            val pageKey = page.stableKey()
                            if (!browseTransitionLocked && browseMode is DistillBrowseMode.List && pageKey in libraryPageKeys) {
                                browseTransitionLocked = true
                                scope.launch {
                                    try {
                                        focusedPageKey = pageKey
                                        detailHeroBounds = null
                                        detailDissolveProgress.snapTo(0f)
                                        detailListAlpha.snapTo(0f)
                                        browseMode = DistillBrowseMode.Detail(pageKey)
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        delay(760)
                                    } finally {
                                        browseTransitionLocked = false
                                    }
                                }
                            }
                        },
                        onPlayPage = { page ->
                            scope.launch {
                                val pageTracks = when (page) {
                                    is LibraryPage.CurrentQueue -> page.queue
                                    is LibraryPage.PlaylistItem -> if (page.stableKey() == focusedListKey && tracks.isNotEmpty()) {
                                        tracks
                                    } else {
                                        repository.tracksForPlaylist(page.playlist.id)
                                    }
                                    is LibraryPage.CloudDisk -> if (page.stableKey() == focusedListKey && tracks.isNotEmpty()) {
                                        tracks
                                    } else {
                                        repository.cloudDiskTracks()
                                    }
                                }
                                val first = pageTracks.firstOrNull() ?: return@launch
                                if (page is LibraryPage.CurrentQueue) {
                                    playerVm.playCurrentQueueTrack(first.id)
                                } else {
                                    playerVm.playTrack(first, pageTracks)
                                }
                                pageBack?.invoke()
                            }
                        },
                    )
                    return@Column
                }

                val onBack = LocalOnBack.current
                // 曲目列表 —— LazyColumn + weight(1f)，铺到屏幕底部不截断、不限 80 首
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(nestedScrollConnection),
                ) {
	                    LazyColumn(
	                        state = trackListState,
	                        modifier = Modifier.fillMaxSize(),
	                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
	                    ) {
	                        if ((refreshGapPx > 0.5f || isRefreshing) && (focused is LibraryPage.PlaylistItem || focused is LibraryPage.CloudDisk)) {
	                            item(key = "__pull_refresh__") {
	                                val refreshGapDp = with(localDensity) { refreshGapPx.toDp() }
	                                val pullProgress = (pullOffset / refreshThresholdPx).coerceIn(0f, 1f)
	                                val refreshRotation = if (isRefreshing) {
	                                    val refreshInfiniteTransition = rememberInfiniteTransition(label = "refreshSpinner")
	                                    val rotation by refreshInfiniteTransition.animateFloat(
	                                        initialValue = 0f,
	                                        targetValue = 360f,
	                                        animationSpec = infiniteRepeatable(
	                                            animation = tween(1400, easing = LinearEasing),
	                                            repeatMode = RepeatMode.Restart
	                                        ),
	                                        label = "rotation"
	                                    )
	                                    rotation
	                                } else {
	                                    0f
	                                }
	                                val sweepAngle = if (isRefreshing) 260f else (pullProgress * 280f)
	                                val rotation = if (isRefreshing) refreshRotation else (-90f + pullProgress * 150f)
	                                val indicatorAlpha = if (isRefreshing) 0.92f else (0.24f + pullProgress * 0.68f)
	                                val indicatorScale = if (isRefreshing) 1f else (0.74f + pullProgress * 0.26f)
	                                Box(
	                                    modifier = Modifier
	                                        .fillMaxWidth()
	                                        .height(refreshGapDp),
	                                    contentAlignment = Alignment.Center,
	                                ) {
	                                    Canvas(
	                                        modifier = Modifier
	                                            .size(20.dp)
	                                            .graphicsLayer {
	                                                alpha = indicatorAlpha
	                                                scaleX = indicatorScale
	                                                scaleY = indicatorScale
	                                                rotationZ = rotation
	                                            }
	                                    ) {
	                                        val strokeWidthPx = 1.6.dp.toPx()
	                                        val diameter = size.minDimension - strokeWidthPx
	                                        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
	                                        val topLeft = androidx.compose.ui.geometry.Offset(
	                                            (size.width - diameter) / 2f,
	                                            (size.height - diameter) / 2f,
	                                        )
	                                        drawArc(
	                                            color = Color.White.copy(alpha = 0.18f),
	                                            startAngle = 0f,
	                                            sweepAngle = 360f,
	                                            useCenter = false,
	                                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
	                                            size = arcSize,
	                                            topLeft = topLeft,
	                                        )
	                                        drawArc(
	                                            color = Color(0xEEF4F6F8),
	                                            startAngle = 0f,
	                                            sweepAngle = sweepAngle,
	                                            useCenter = false,
	                                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
	                                            size = arcSize,
	                                            topLeft = topLeft,
	                                        )
	                                    }
	                                }
		                            }
		                        }
		                        focused?.let { page ->
		                            item(key = "__playlist_detail_hero__") {
		                                LibraryDetailHero(
		                                    page = page,
		                                    trackCount = if (page is LibraryPage.CloudDisk && tracks.isNotEmpty()) {
		                                        tracks.size
		                                    } else {
		                                        page.trackCount
		                                    },
		                                    canPlay = tracks.isNotEmpty(),
		                                    coverAlpha = 1f,
		                                    playbackMode = playerState.playbackMode,
		                                    fg = detailHeroFg,
		                                    dim = detailHeroDim,
		                                    heroHeight = detailHeroHeight,
                                                    chromeAlpha = detailDissolveReveal,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
		                                    onCoverBounds = { detailHeroBounds = it },
		                                    onCyclePlaybackMode = {
		                                        playerVm.setPlaybackMode(nextPlaybackQueueMode(playerState.playbackMode))
		                                    },
		                                    onPlayAll = {
		                                        val first = tracks.firstOrNull() ?: return@LibraryDetailHero
		                                        if (page is LibraryPage.CurrentQueue) {
		                                            playerVm.playCurrentQueueTrack(first.id)
		                                        } else {
		                                            playerVm.playTrack(first, tracks)
		                                        }
		                                        onBack?.invoke()
		                                    },
		                                )
		                            }
		                        }
			                        when {
                            // 只有"真没东西可显"才让位给加载文案；切歌单时 cache 命中很快，
                            // 旧 tracks 先撑着，新 tracks 一到直接替换 → 用户看不到"多行→
                            // 一行→多行"那一下 strobe。这是上次"闪"的真正源头。
                            loading && tracks.isEmpty() -> item {
	                                Text(
	                                    "加载中…",
	                                    color = detailDim,
	                                    style = TextStyle(fontSize = 13.sp),
	                                    modifier = Modifier
                                            .padding(start = 24.dp)
                                            .graphicsLayer { this.alpha = detailListReveal },
	                                )
                            }
                            trackLoadError != null -> item {
                                Column(
                                    modifier = Modifier
                                        .padding(start = 24.dp, end = 24.dp)
                                        .graphicsLayer { this.alpha = detailListReveal },
                                ) {
	                                    Text(
	                                        trackLoadError.orEmpty(),
	                                        color = detailDim,
	                                        style = TextStyle(fontSize = 13.sp),
	                                    )
	                                    TextButton(onClick = { trackLoadRetry += 1 }) {
	                                        Text("重试", color = detailFg)
	                                    }
                                }
                            }
                            tracks.isEmpty() -> item {
	                                Text(
	                                    if (focused is LibraryPage.CloudDisk) "网盘里还没有歌" else "这张歌单是空的",
	                                    color = detailDim,
	                                    style = TextStyle(fontSize = 13.sp),
	                                    modifier = Modifier
                                            .padding(start = 24.dp)
                                            .graphicsLayer { this.alpha = detailListReveal },
	                                )
                            }
                            filteredTracks.isEmpty() -> item {
	                                Text(
	                                    "未找到匹配歌曲",
	                                    color = detailDim,
	                                    style = TextStyle(fontSize = 13.sp),
	                                    modifier = Modifier
                                            .padding(start = 24.dp)
                                            .graphicsLayer { this.alpha = detailListReveal },
	                                )
                            }
                            else -> {
                                itemsIndexed(
                                    items = filteredTracks,
                                    key = { index, track -> "${track.id}:$index" },
		                                ) { index, t ->
		                                    val isCurrent = t.id == currentTrackId
                                            AppleMusicTrackListRow(
                                                index = index + 1,
                                                track = t,
                                                isCurrentTrack = isCurrent,
                                                isPlaying = playerState.isPlaying,
                                                fg = detailFg,
                                                dim = detailDim,
                                                divider = detailDivider,
                                                contentAlpha = detailListReveal,
                                                showDelete = focused is LibraryPage.CurrentQueue,
                                                onDelete = if (focused is LibraryPage.CurrentQueue) {
                                                    { playerVm.removeTrack(t.id) }
                                                } else {
                                                    null
                                                },
                                                onClick = {
                                                    if (focused is LibraryPage.CurrentQueue) {
                                                        playerVm.playCurrentQueueTrack(t.id)
                                                    } else {
                                                        playerVm.playTrack(t, filteredTracks)
                                                    }
                                                    onBack?.invoke()
                                                },
                                            )
	                                }
                            }
                        }
                    }

	                }
	            }

                    // 蒸馏全屏遮罩已删除 —— 蒸馏在 DistillCoordinator 后台跑，
                    // 进度由 shell 层（PipoNativeApp）统一画一条小浮条
                    if (renderedIsDetailMode) {
                DistillLibraryHeaderBar(
                    browseMode = renderedBrowseMode,
                    focusedTitle = focused?.title.orEmpty(),
                    titleAlpha = navTitleAlpha,
                    tint = detailHeaderTint,
                    navBarColor = navBarColor,
                    isDetailMode = true,
                    canGoBack = shellBack != null,
                    onBackClick = onHeaderBack,
                    onDistillClick = onHeaderDistill,
                    onTasteClick = { nav?.openTaste?.invoke() },
                )
                    }
            }
        }
    }
}
}


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ColumnScope.LibraryListContent(
    pages: List<LibraryPage>,
    focusedKey: String?,
    canPlayFocused: Boolean,
    loadingFocused: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onFocusedPage: (LibraryPage) -> Unit,
    onCoverBounds: (String, CoverTransitionBounds) -> Unit,
    onOpenPage: (LibraryPage, CoverTransitionBounds?) -> Unit,
    onPlayPage: (LibraryPage) -> Unit,
) {
    if (pages.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = "你还没有歌单",
                subtitle = "当前播放列表、网盘或网易云歌单同步后会出现在这里。",
            )
        }
        return
    }

    LibraryCoverWheel(
        pages = pages,
        focusedKey = focusedKey,
        canPlayFocused = canPlayFocused,
        loadingFocused = loadingFocused,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        onFocusedPage = onFocusedPage,
        onCoverBounds = onCoverBounds,
        onOpenPage = onOpenPage,
        onPlayPage = onPlayPage,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryCoverWheel(
    pages: List<LibraryPage>,
    focusedKey: String?,
    canPlayFocused: Boolean,
    loadingFocused: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onFocusedPage: (LibraryPage) -> Unit,
    onCoverBounds: (String, CoverTransitionBounds) -> Unit,
    onOpenPage: (LibraryPage, CoverTransitionBounds?) -> Unit,
    onPlayPage: (LibraryPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialPage = pages.indexOfFirst { it.stableKey() == focusedKey }.takeIf { it >= 0 } ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pages.size },
    )
    val coverBoundsByKey = remember { mutableStateMapOf<String, CoverTransitionBounds>() }
    val activePage = pages.getOrNull(pagerState.currentPage)
    val activeKey = activePage?.stableKey()
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusedKey, pages.joinToString(separator = "|") { it.stableKey() }) {
        val target = pages.indexOfFirst { it.stableKey() == focusedKey }
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage, pages.joinToString(separator = "|") { it.stableKey() }) {
        pages.getOrNull(pagerState.currentPage)?.let(onFocusedPage)
    }

    fun openActivePage() {
        val page = activePage ?: return
        onOpenPage(page, coverBoundsByKey[page.stableKey()])
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .padding(bottom = 10.dp),
    ) {
        val sidePeek = if (maxWidth >= 390.dp) 34.dp else 30.dp
        val maxCoverByWidth = (maxWidth - sidePeek * 2).coerceAtLeast(240.dp)
        val coverSize = minOf(maxCoverByWidth, maxHeight * 0.60f, 370.dp)
            .coerceAtLeast(minOf(maxCoverByWidth, 286.dp))
        val pagerHeight = coverSize + 58.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = sidePeek),
                pageSpacing = 6.dp,
                verticalAlignment = Alignment.CenterVertically,
            ) { index ->
                val page = pages[index]
                val pageKey = page.stableKey()
                val pageOffset = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
                val focus = 1f - pageOffset
                val direction = when {
                    index < pagerState.currentPage -> -1f
                    index > pagerState.currentPage -> 1f
                    pagerState.currentPageOffsetFraction < 0f -> 1f
                    pagerState.currentPageOffsetFraction > 0f -> -1f
                    else -> 0f
                }
                var coverBounds by remember(pageKey) { mutableStateOf<CoverTransitionBounds?>(null) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pagerHeight)
                        .zIndex(focus)
                        .graphicsLayer {
                            val scale = lerpFloat(0.76f, 1f, focus)
                            scaleX = scale
                            scaleY = scale
                            alpha = lerpFloat(0.46f, 1f, focus)
                            translationY = lerpFloat(26f, 0f, focus) * density
                            rotationY = direction * lerpFloat(28f, 0f, focus)
                            rotationZ = direction * lerpFloat(3.5f, 0f, focus)
                            cameraDistance = 18f * density
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .sharedPlaylistCoverElement(
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                pageKey = pageKey,
                            )
                            .size(coverSize)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x16000000))
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = lerpFloat(0.04f, 0.16f, focus)),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onOpenPage(page, coverBounds) }
                            .onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInRoot()
                                val bounds = CoverTransitionBounds(
                                    left = position.x,
                                    top = position.y,
                                    width = coordinates.size.width.toFloat(),
                                    height = coordinates.size.height.toFloat(),
                                )
                                coverBounds = bounds
                                coverBoundsByKey[pageKey] = bounds
                                onCoverBounds(pageKey, bounds)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!page.coverUrl.isNullOrBlank()) {
                            StableCoverImage(
                                url = page.coverUrl,
                                title = page.title,
                                modifier = Modifier.fillMaxSize(),
                                maxDecodeSizePx = 960,
                            )
                        } else {
                            Image(
                                painter = painterResource(R.mipmap.ic_launcher_round),
                                contentDescription = page.title,
                                modifier = Modifier
                                    .fillMaxSize(0.42f)
                                    .graphicsLayer { alpha = 0.76f },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            0.72f to Color.Transparent,
                                            1f to Color.Black.copy(alpha = 0.18f),
                                        ),
                                    ),
                                ),
                        )
                        LibraryCoverThickness(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            alpha = lerpFloat(0.08f, 0.30f, focus),
                        )
                        LibraryCoverGloss(
                            modifier = Modifier.fillMaxSize(),
                            alpha = lerpFloat(0.03f, 0.12f, focus),
                        )
                    }
                }
            }

            val infoAlpha by animateFloatAsState(
                targetValue = if (activePage == null) 0f else 1f,
                animationSpec = tween(durationMillis = 180, easing = PipoMotion.FlipEase),
                label = "libraryWheelInfoAlpha",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .graphicsLayer { alpha = infoAlpha },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = activePage?.title.orEmpty(),
                    color = PipoColors.Ink,
                    style = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 66.dp)
                        .clickable { openActivePage() },
                )
                Text(
                    text = activePage?.subtitle().orEmpty(),
                    color = PipoColors.TextMuted,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val playEnabled = activePage != null && activeKey == focusedKey && canPlayFocused && !loadingFocused
                Box(
                    modifier = Modifier
                        .padding(top = 22.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (playEnabled) Color(0xEE05060A) else Color(0x6605060A))
                        .clickable(enabled = playEnabled) { activePage?.let(onPlayPage) }
                        .padding(horizontal = 44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlayGlyph(
                            color = if (playEnabled) Color.White else Color.White.copy(alpha = 0.42f),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = if (loadingFocused && activeKey == focusedKey) "加载中" else "播放",
                            color = if (playEnabled) Color.White else Color.White.copy(alpha = 0.42f),
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                LibraryWheelRail(
                    pages = pages,
                    currentPage = pagerState.currentPage,
                    pageOffsetFraction = pagerState.currentPageOffsetFraction,
                    onSelect = { index ->
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryCoverThickness(
    modifier: Modifier,
    alpha: Float,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(12.dp)
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = alpha * 0.36f),
                        1f to Color.Black.copy(alpha = alpha),
                    ),
                ),
            ),
    )
}

@Composable
private fun LibraryCoverGloss(
    modifier: Modifier,
    alpha: Float,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = alpha),
                    0.34f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = alpha * 0.55f),
                ),
                start = Offset(0f, 0f),
                end = Offset(560f, 620f),
            ),
        ),
    )
}

@Composable
private fun LibraryWheelRail(
    pages: List<LibraryPage>,
    currentPage: Int,
    pageOffsetFraction: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return

    val slotWidth = 43.dp
    val railScrollState = rememberScrollState()
    val localDensity = LocalDensity.current
    val activePosition = (currentPage + pageOffsetFraction)
        .coerceIn(0f, pages.lastIndex.toFloat())

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 28.dp)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val pageCount = pages.size.coerceAtLeast(1)
        val inactiveThumbSize = 24.dp
        val activeThumbSize = 34.dp
        val viewportWidth = minOf(maxWidth, slotWidth * 7f)
        LaunchedEffect(currentPage, pageCount, railScrollState.maxValue, viewportWidth) {
            if (railScrollState.maxValue > 0) {
                val targetScroll = with(localDensity) {
                    val viewportPx = viewportWidth.roundToPx()
                    val slotPx = slotWidth.roundToPx()
                    val activeCenterPx = currentPage.coerceIn(0, pages.lastIndex) * slotPx + slotPx / 2
                    (activeCenterPx - viewportPx / 2).coerceIn(0, railScrollState.maxValue)
                }
                if ((railScrollState.value - targetScroll).absoluteValue > 1) {
                    railScrollState.animateScrollTo(targetScroll)
                }
            }
        }
        Box(
            modifier = Modifier
                .width(viewportWidth)
                .height(54.dp)
                .align(Alignment.Center)
                .clipToBounds(),
        ) {
            Row(
                modifier = Modifier
                    .height(54.dp)
                    .align(Alignment.CenterStart)
                    .horizontalScroll(railScrollState),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (index in pages.indices) {
                    val page = pages[index]
                    val focus = (1f - (index - activePosition).absoluteValue).coerceIn(0f, 1f)
                    val rotationDirection = when {
                        index < activePosition -> -1f
                        index > activePosition -> 1f
                        else -> 0f
                    }
                    val animatedSize by animateDpAsState(
                        targetValue = lerpFloat(inactiveThumbSize.value, activeThumbSize.value, focus).dp,
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbSize",
                    )
                    val animatedAlpha by animateFloatAsState(
                        targetValue = lerpFloat(0.54f, 1f, focus),
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbAlpha",
                    )
                    val animatedRotation by animateFloatAsState(
                        targetValue = rotationDirection * lerpFloat(6f, 0f, focus),
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbRotation",
                    )
                    val animatedLift by animateFloatAsState(
                        targetValue = lerpFloat(4f, -3f, focus),
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbLift",
                    )
                    val animatedBorderAlpha by animateFloatAsState(
                        targetValue = lerpFloat(0.12f, 0.30f, focus),
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbBorder",
                    )
                    val animatedSideAlpha by animateFloatAsState(
                        targetValue = lerpFloat(0.18f, 0.26f, focus),
                        animationSpec = tween(durationMillis = 220, easing = PipoMotion.FlipEase),
                        label = "railThumbSide",
                    )
                    Box(
                        modifier = Modifier
                            .width(slotWidth)
                            .height(54.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.Center)
                                .background(PipoColors.Ink.copy(alpha = 0.12f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(animatedSize)
                                .graphicsLayer {
                                    this.alpha = animatedAlpha
                                    rotationZ = animatedRotation
                                    translationY = animatedLift * localDensity.density
                                }
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x22000000))
                                .border(
                                    width = 1.dp,
                                    color = PipoColors.Ink.copy(alpha = animatedBorderAlpha),
                                    shape = RoundedCornerShape(5.dp),
                                )
                                .clickable { onSelect(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!page.coverUrl.isNullOrBlank()) {
                                StableCoverImage(
                                    url = page.coverUrl,
                                    title = page.title,
                                    modifier = Modifier.fillMaxSize(),
                                    maxDecodeSizePx = 160,
                                )
                            } else {
                                Image(
                                    painter = painterResource(R.mipmap.ic_launcher_round),
                                    contentDescription = page.title,
                                    modifier = Modifier
                                        .fillMaxSize(0.52f)
                                        .graphicsLayer { this.alpha = 0.78f },
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(4.dp)
                                    .background(Color.Black.copy(alpha = animatedSideAlpha)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedPlaylistCoverElement(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    pageKey: String,
): Modifier = with(sharedTransitionScope) {
    sharedBounds(
        sharedContentState = rememberSharedContentState(key = "playlist-cover:$pageKey"),
        animatedVisibilityScope = animatedVisibilityScope,
        enter = fadeIn(tween(durationMillis = 460, easing = PipoMotion.FlipEase)),
        exit = fadeOut(tween(durationMillis = 180, easing = PipoMotion.CloseEase)),
        boundsTransform = BoundsTransform { _, _ ->
            tween(durationMillis = 620, easing = PipoMotion.FlipEase)
        },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryDetailHero(
    page: LibraryPage,
    trackCount: Int,
    canPlay: Boolean,
    coverAlpha: Float = 1f,
    playbackMode: PlaybackQueueMode,
    fg: Color,
    dim: Color,
    heroHeight: androidx.compose.ui.unit.Dp,
    chromeAlpha: Float = 1f,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onCoverBounds: (CoverTransitionBounds) -> Unit = {},
    onCyclePlaybackMode: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                onCoverBounds(
                    CoverTransitionBounds(
                        left = position.x,
                        top = position.y,
                        width = coordinates.size.width.toFloat(),
                        height = coordinates.size.height.toFloat(),
                    ),
                )
            },
    ) {
        if (coverAlpha > 0.001f) {
            PlaylistHeroCoverImage(
                coverUrl = page.coverUrl,
                title = page.title,
                alpha = coverAlpha,
                modifier = Modifier.sharedPlaylistCoverElement(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    pageKey = page.stableKey(),
                ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 20.dp)
                .graphicsLayer {
                    val reveal = chromeAlpha.coerceIn(0f, 1f)
                    this.alpha = reveal
                    translationY = (1f - reveal) * 14.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                color = fg,
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${trackCount.coerceAtLeast(0)} 首",
                color = dim,
                style = TextStyle(fontSize = 13.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PlaybackModeRoundButton(
                    mode = playbackMode,
                    tint = fg,
                    onClick = onCyclePlaybackMode,
                )
                Box(
                    modifier = Modifier
                        .height(54.dp)
                        .width(178.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (canPlay) Color(0xEE05060A) else Color(0x6605060A))
                        .clickable(enabled = canPlay, onClick = onPlayAll)
                        .padding(horizontal = 36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlayGlyph(
                            color = if (canPlay) Color.White else Color.White.copy(alpha = 0.42f),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "播放",
                            color = if (canPlay) Color.White else Color.White.copy(alpha = 0.42f),
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                Spacer(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer { alpha = 0f },
                )
            }
        }
    }
}

@Composable
private fun playlistDetailHeroHeight(): androidx.compose.ui.unit.Dp {
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    return (screenHeight * 0.56f).coerceIn(392.dp, 520.dp)
}

@Composable
private fun PlaylistHeroCoverImage(
    coverUrl: String?,
    title: String,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha.coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .playlistDetailCoverDissolveMask(),
    ) {
        if (!coverUrl.isNullOrBlank()) {
            StableCoverImage(
                url = coverUrl,
                title = title,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.025f
                        scaleY = 1.025f
                    },
                maxDecodeSizePx = 960,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                PipoColors.Accent.copy(alpha = 0.18f),
                                PipoColors.Bg1,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(0.22f),
                )
            }
        }
    }
}

private fun Modifier.playlistDetailCoverDissolveMask(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color.Black,
                0.48f to Color.Black,
                0.64f to Color.Black.copy(alpha = 0.92f),
                0.80f to Color.Black.copy(alpha = 0.54f),
                0.94f to Color.Black.copy(alpha = 0.13f),
                1.00f to Color.Transparent,
            ),
        ),
        blendMode = BlendMode.DstIn,
    )
}

private fun nextPlaybackQueueMode(mode: PlaybackQueueMode): PlaybackQueueMode = when (mode) {
    PlaybackQueueMode.ShufflePlay -> PlaybackQueueMode.OrderOnce
    PlaybackQueueMode.OrderOnce -> PlaybackQueueMode.AiRadio
    PlaybackQueueMode.AiRadio -> PlaybackQueueMode.ShufflePlay
}

private fun playbackModeLabel(mode: PlaybackQueueMode): String = when (mode) {
    PlaybackQueueMode.ShufflePlay -> "随机播放"
    PlaybackQueueMode.OrderOnce -> "顺序播放"
    PlaybackQueueMode.AiRadio -> "AI 电台"
}

@Composable
private fun PlaybackModeRoundButton(
    mode: PlaybackQueueMode,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${playbackModeLabel(mode)}，点击切换播放顺序" },
        contentAlignment = Alignment.Center,
    ) {
        PlaybackModeGlyph(mode = mode, color = tint, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun PlaybackModeGlyph(
    mode: PlaybackQueueMode,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        PlaybackQueueMode.ShufflePlay -> Icon(
            imageVector = Icons.Rounded.Shuffle,
            contentDescription = null,
            tint = color,
            modifier = modifier,
        )
        PlaybackQueueMode.OrderOnce -> OrderModeIcon(color = color, modifier = modifier)
        PlaybackQueueMode.AiRadio -> SparkIcon(color = color, modifier = modifier)
    }
}

@Composable
private fun StableCoverImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    maxDecodeSizePx: Int = 960,
) {
    val context = LocalContext.current
    val model = remember(context, url, maxDecodeSizePx) {
        coil.request.ImageRequest.Builder(context)
            .data(url)
            .size(maxDecodeSizePx, maxDecodeSizePx)
            .memoryCacheKey("cover:$maxDecodeSizePx:$url")
            .diskCacheKey(url)
            .crossfade(false)
            .build()
    }
    coil.compose.AsyncImage(
        model = model,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun LibraryCoverThumb(
    url: String?,
    title: String,
    sizeDp: Int,
    cornerDp: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(Color(0x16FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_round),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize(0.50f)
                    .graphicsLayer { alpha = 0.76f },
            )
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(PipoColors.GlassFill)
            .border(1.dp, PipoColors.GlassStroke, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(color = PipoColors.TextDim, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(10.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = PipoColors.Ink, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PipoColors.Ink),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = "搜索歌单",
                        color = PipoColors.TextDim,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
                innerTextField()
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onClear() }
                    .semantics { contentDescription = "清除搜索" }
                    .padding(4.dp),
            ) {
                CloseIcon(color = PipoColors.TextDim, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun AppleMusicTrackListRow(
    index: Int,
    track: NativeTrack,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    fg: Color,
    dim: Color,
    divider: Color,
    contentAlpha: Float = 1f,
    showDelete: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val rowEnabled = contentAlpha > 0.96f
    val rowBackground by animateColorAsState(
        targetValue = if (isCurrentTrack) fg.copy(alpha = 0.07f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "appleTrackRowBackground",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrentTrack) fg else fg.copy(alpha = 0.92f),
        animationSpec = tween(durationMillis = 180),
        label = "appleTrackTitleColor",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = contentAlpha.coerceIn(0f, 1f) }
            .background(rowBackground)
            .clickable(enabled = rowEnabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(start = 14.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isCurrentTrack) {
                    if (isPlaying) {
                        PlayingWaveIndicator(
                            isPlaying = true,
                            color = fg.copy(alpha = 0.86f),
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(fg.copy(alpha = 0.72f)),
                        )
                    }
                }
            }
            Text(
                text = index.toString(),
                color = dim,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
                modifier = Modifier.width(38.dp),
                maxLines = 1,
            )
            Text(
                text = track.title,
                color = titleColor,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "播放歌曲" }
                        .clickable(enabled = rowEnabled, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayGlyph(color = dim, modifier = Modifier.size(18.dp))
                }
                if (showDelete && onDelete != null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = "删除歌曲" }
                            .clickable(enabled = rowEnabled, onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        TrashGlyph(color = dim, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 72.dp, end = 22.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(divider),
        )
    }
}

@Composable
private fun TrashGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.09f
        val thinStroke = strokeWidth * 0.76f
        val cap = StrokeCap.Round
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.30f, h * 0.34f), Offset(w * 0.70f, h * 0.34f), strokeWidth, cap = cap)
        drawLine(color, Offset(w * 0.42f, h * 0.24f), Offset(w * 0.58f, h * 0.24f), strokeWidth, cap = cap)
        drawLine(color, Offset(w * 0.36f, h * 0.43f), Offset(w * 0.40f, h * 0.79f), strokeWidth, cap = cap)
        drawLine(color, Offset(w * 0.64f, h * 0.43f), Offset(w * 0.60f, h * 0.79f), strokeWidth, cap = cap)
        drawLine(color, Offset(w * 0.42f, h * 0.80f), Offset(w * 0.58f, h * 0.80f), strokeWidth, cap = cap)
        drawLine(color, Offset(w * 0.47f, h * 0.47f), Offset(w * 0.48f, h * 0.71f), thinStroke, cap = cap)
        drawLine(color, Offset(w * 0.53f, h * 0.47f), Offset(w * 0.52f, h * 0.71f), thinStroke, cap = cap)
    }
}

@Composable
private fun TrackListRow(
    track: NativeTrack,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val rowBackground by animateColorAsState(
        targetValue = if (isCurrentTrack) PipoColors.Mint.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "trackRowBackground",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrentTrack) PipoColors.Mint else PipoColors.Ink,
        animationSpec = tween(durationMillis = 180),
        label = "trackTitleColor",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = titleColor,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                color = PipoColors.TextDim,
                style = TextStyle(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = isCurrentTrack,
                animationSpec = tween(durationMillis = 160),
                label = "trackNowIndicator",
            ) { isCurrent ->
                if (isCurrent) {
                    PlayingWaveIndicator(
                        isPlaying = isPlaying,
                        color = PipoColors.Mint,
                    )
                } else {
                    PlayGlyph(
                        color = PipoColors.Ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeToRevealDeleteRow(
    track: NativeTrack,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deleteButtonWidth = 72.dp
    val deleteButtonWidthPx = with(density) { deleteButtonWidth.toPx() }

    val swipeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(track.id) {
        swipeOffset.snapTo(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .clipToBounds(),
    ) {
        // 删除按钮跟随 row 一起平移：row 不滑动时按钮停在屏幕外，
        // 这样底层 cover 背景能完整透出来，跟其他歌单列表视觉一致。
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(deleteButtonWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = swipeOffset.value + deleteButtonWidthPx
                }
                .background(Color(0xFFE53935))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "删除",
                color = Color.White,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = swipeOffset.value
                }
                .pointerInput(track.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val target = if (swipeOffset.value < -deleteButtonWidthPx / 2f) {
                                -deleteButtonWidthPx
                            } else {
                                0f
                            }
                            scope.launch {
                                swipeOffset.animateTo(
                                    target,
                                    spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = (swipeOffset.value + dragAmount).coerceIn(-deleteButtonWidthPx, 0f)
                            scope.launch {
                                swipeOffset.snapTo(newOffset)
                            }
                        }
                    )
                }
        ) {
            TrackListRow(
                track = track,
                isCurrentTrack = isCurrentTrack,
                isPlaying = isPlaying,
                onClick = onClick
            )
        }
    }
}

/**
 * 歌单内搜索框 —— 作为曲目列表的第一项随内容滚动（iOS 风），不再做悬浮覆盖层。
 * 视觉走项目统一的 glass token（GlassFill + GlassStroke），跟其它沉浸式控件一致。
 */
@Composable
private fun TrackSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(PipoColors.GlassFill)
            .border(1.dp, PipoColors.GlassStroke, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(
            color = PipoColors.TextDim,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = PipoColors.Ink, fontSize = 13.sp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearch() },
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PipoColors.Ink),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = "搜索当前歌单歌曲",
                        color = PipoColors.TextDim,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
                innerTextField()
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onClear() }
                    .semantics { contentDescription = "清除搜索" }
                    .padding(4.dp),
            ) {
                CloseIcon(
                    color = PipoColors.TextDim,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaybackModeStrip(
    mode: PlaybackQueueMode,
    onMode: (PlaybackQueueMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackModeChip(
            label = "随机播放",
            selected = mode == PlaybackQueueMode.ShufflePlay,
            onClick = { onMode(PlaybackQueueMode.ShufflePlay) },
        ) { tint ->
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
        }
        PlaybackModeChip(
            label = "顺序播放",
            selected = mode == PlaybackQueueMode.OrderOnce,
            onClick = { onMode(PlaybackQueueMode.OrderOnce) },
        ) { tint ->
            OrderModeIcon(color = tint, modifier = Modifier.size(19.dp))
        }
        PlaybackModeChip(
            label = "AI 电台",
            selected = mode == PlaybackQueueMode.AiRadio,
            onClick = { onMode(PlaybackQueueMode.AiRadio) },
        ) { tint ->
            SparkIcon(color = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PlaybackModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) PipoColors.Mint else PipoColors.TextDim,
        animationSpec = tween(durationMillis = 160),
        label = "playbackModeTint",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PipoColors.Mint.copy(alpha = 0.18f) else Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

@Composable
private fun PlayingWaveIndicator(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val idles = listOf(0.45f, 0.72f, 0.55f)
    Row(
        modifier = modifier.size(width = 18.dp, height = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlaying) {
            // 仅在播放时建立无限动画。暂停态原本也只显示静止 idle 高度,这里直接走 else 分支,
            // 不再让 infiniteTransition 在暂停时空转(旧实现里 transition 一直跑、3 个 animateFloat
            // 每帧重组该指示器,结果却被 if(isPlaying) 丢弃)。可见效果(播放=律动,暂停=静止)不变。
            val transition = rememberInfiniteTransition(label = "playlistWave")
            idles.forEachIndexed { index, _ ->
                val value by transition.animateFloat(
                    initialValue = 0.35f + index * 0.14f,
                    targetValue = 1f - index * 0.10f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, delayMillis = index * 110),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bar$index",
                )
                WaveBar(color = color, heightFraction = value.coerceIn(0.25f, 1f))
            }
        } else {
            idles.forEach { idle -> WaveBar(color = color, heightFraction = idle) }
        }
    }
}

@Composable
private fun WaveBar(color: Color, heightFraction: Float) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height((16f * heightFraction).dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

private fun stageLabel(p: app.pipo.nativeapp.data.DistillProgress): String = when (p) {
    is app.pipo.nativeapp.data.DistillProgress.LoadingTracks ->
        "拉曲目  ${p.done} / ${p.total}"
    app.pipo.nativeapp.data.DistillProgress.Sampling -> "分层采样中…"
    app.pipo.nativeapp.data.DistillProgress.CallingAi -> "AI 蒸馏中（约 30 秒）…"
    is app.pipo.nativeapp.data.DistillProgress.TaggingTracks ->
        "单曲打标  ${p.done} / ${p.total}"
    is app.pipo.nativeapp.data.DistillProgress.EmbeddingTracks ->
        "向量索引  ${p.done} / ${p.total}"
    app.pipo.nativeapp.data.DistillProgress.Done -> "完成"
}

@Composable
private fun IconChip(
    tint: Color,
    background: Color = Color(0x14FFFFFF),
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
        ) { content() }
    }
}

/**
 * 子页面通用 scaffold —— 镜像 src/app/settings/page.tsx 的 36px 1fr 36px header 结构。
 *   - 左 36dp BackButton（由 LocalOnBack 提供）
 *   - 中 1fr 标题（textAlign center）
 *   - 右 36dp 占位（让标题中点真正落在屏幕中线）
 */
@Composable
internal fun ScreenScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    // header 钉在顶部（不滚动），下面 content 单独 scroll —— 跟歌单页一致
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0)
            .statusBarsPadding(),
    ) {
        // 36 / 1fr / 36 网格 header（固定在顶部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onBack = LocalOnBack.current
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
                    }
                }
            }
            Text(
                text = title,
                color = PipoColors.Ink,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.size(36.dp))
        }

        // 内容滚动区
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 60.dp),
            content = content,
        )
    }
}

@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = PipoColors.TextDim,
            style = TextStyle(fontSize = 13.sp),
        )
    }
}
