package app.pipo.nativeapp.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.R
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.playback.PlaybackQueueMode
import app.pipo.nativeapp.playback.PlayerViewModel
import kotlinx.coroutines.coroutineScope
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

/** 子页面用的返回回调 —— PipoNativeApp 在 push 进入前提供，返回到 Player root。 */
val LocalOnBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 子页间互跳：DistillScreen 蒸馏完成跳到 TasteScreen 等场景用 */
data class PipoNav(
    val openTaste: () -> Unit,
    val openSettings: () -> Unit,
    val openDistill: () -> Unit,
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
    if (playlists.isEmpty()) {
        ScreenScaffold(title = "我的歌单") {
            EmptyState(
                title = "你还没有歌单",
                subtitle = "等账号同步完，歌单就会出现在这里。",
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
                // 网盘永远显示，没登录或网盘为空时点开能看到提示。放在用户歌单之前。
                add(LibraryPage.CloudDisk(cachedCoverUrl = cloudDiskCover, knownCount = cloudDiskCount))
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
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { libraryPages.size },
    )
    // 列表长度变化(歌单删除 / 网易云端少了一张)时,如果 currentPage 已经越界,
    // pagerState.currentPage 会停留在旧 index → focused 永远 null →
    // 曲目列表静默不更新。强制 scroll 回 0 让 focused 恢复有效。
    LaunchedEffect(libraryPages.size) {
        if (libraryPages.isNotEmpty() && pagerState.currentPage >= libraryPages.size) {
            pagerState.scrollToPage(0)
        }
    }
    val focused = libraryPages.getOrNull(pagerState.currentPage)
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
    var pendingLocateCurrent by remember { mutableStateOf(false) }
    val focusedQueue = (focused as? LibraryPage.CurrentQueue)?.queue
    val focusedPlaylist = (focused as? LibraryPage.PlaylistItem)?.playlist

    // Search and pull-to-refresh state
    var searchQuery by remember { mutableStateOf("") }
    var pullOffset by remember { mutableStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshDragBlockedUntilRelease by remember { mutableStateOf(false) }
    var searchSettledListKey by remember { mutableStateOf(focusedListKey) }
    var locateTargetListKey by remember { mutableStateOf<String?>(null) }

    val searchBarMaxHeight = 44.dp
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    val searchBarMaxHeightPx = with(localDensity) { searchBarMaxHeight.toPx() }
    val refreshThresholdPx = with(localDensity) { 92.dp.toPx() }
    val refreshHoldOffsetPx = with(localDensity) { 38.dp.toPx() }
    val refreshMaxGapPx = with(localDensity) { 54.dp.toPx() }
    val trackTopTolerancePx = with(localDensity) { 6.dp.toPx() }.toInt()
    val searchHeightAnim = remember { Animatable(0f) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val isTrackListAwayFromTop by remember(trackListState, trackTopTolerancePx) {
        derivedStateOf {
            trackListState.firstVisibleItemIndex > 0 ||
                trackListState.firstVisibleItemScrollOffset > trackTopTolerancePx
        }
    }
    val isSwitchingTrackList = focusedListKey != searchSettledListKey
    val shouldShowSearchBar = !isSwitchingTrackList &&
        (isTrackListAwayFromTop || searchQuery.isNotBlank())

    fun closeSearch() {
        searchQuery = ""
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    LaunchedEffect(shouldShowSearchBar) {
        val target = if (shouldShowSearchBar) searchBarMaxHeightPx else 0f
        searchHeightAnim.animateTo(
            target,
            tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
        if (!shouldShowSearchBar) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(isTrackListAwayFromTop) {
        if (isTrackListAwayFromTop) {
            pullOffset = 0f
        } else {
            if (searchQuery.isBlank()) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
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

    val refreshInfiniteTransition = rememberInfiniteTransition(label = "refreshSpinner")
    val refreshRotation by refreshInfiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
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

    val nestedScrollConnection = remember(focusedListKey, focused, libraryPages, pagerState) {
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
                            when (val focusedPage = libraryPages.getOrNull(pagerState.currentPage)) {
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
        val preserveExplicitLocate = pendingLocateCurrent && locateTargetListKey == focusedListKey
        if (!preserveExplicitLocate) {
            pendingLocateCurrent = false
            locateTargetListKey = null
            trackListState.scrollToItem(0)
        }
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
        // searchHeightAnim 不 snap —— shouldShowSearchBar=false 已经在驱动它自然渐隐
        // （原 snapTo(0f) 会抢掉 220ms 的 animateTo，体感像被一刀切）。
        searchSettledListKey = focusedListKey
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
    val currentTrackIndexInVisibleTracks = remember(tracks, currentTrackId) {
        currentTrackId?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
    }
    val isCurrentTrackVisible by remember(trackListState, currentTrackId) {
        derivedStateOf {
            currentTrackId != null &&
                trackListState.layoutInfo.visibleItemsInfo.any { it.key == currentTrackId }
        }
    }
    val shouldShowLocateCurrent = currentTrackId != null &&
        (currentTrackIndexInVisibleTracks < 0 || !isCurrentTrackVisible) &&
        libraryPages.any { it is LibraryPage.CurrentQueue }
    LaunchedEffect(pendingLocateCurrent, tracks, currentTrackId) {
        if (!pendingLocateCurrent) return@LaunchedEffect
        val idx = currentTrackId?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) {
            trackListState.animateScrollToItem(idx)
            pendingLocateCurrent = false
            locateTargetListKey = null
        }
    }

    // ---- 蒸馏状态 + 进度 + 多选模式 ----
    // 蒸馏跑在 app-level coordinator（DistillCoordinator），跟 Composable 进出无关。
    // 这里只用它的 StateFlow 来呈现 UI。即使用户切走，蒸馏仍在后台跑。
    val nav = LocalNav.current
    val coordinator = app.pipo.nativeapp.data.PipoGraph.distillCoordinator
    val distillRunning by coordinator.running.collectAsState()
    var confirmOpen by remember { mutableStateOf(false) }
    // mode = "library" 是 cover-flow 视图；"select" 是多选 / 准备蒸馏
    var mode by remember { mutableStateOf("library") }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }
    val selectedPlaylists = remember(selectedIds.size, playlists) {
        playlists.filter { selectedIds[it.id] == true }
    }
    val selectedTrackEstimate = selectedPlaylists.sumOf { it.trackCount }

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
                            // 蒸馏完后让本地库 cache 也刷新，让 PetAgent 召回拿到最新的曲库
                            app.pipo.nativeapp.data.PipoGraph.library.invalidate()
                        },
                    )
                    mode = "library"
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Bg0),
    ) {
        // 全屏背景：当前焦点歌单封面 blur
        Box(modifier = Modifier.fillMaxSize()) {
            AdaptiveDotField(
                coverUrl = focused?.coverUrl,
                isPlaying = false,
                showDots = false,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 2.dp, bottom = 12.dp),
            ) {
                // header —— title 紧贴返回箭头，跟 ScreenScaffold（设置页）对齐
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onBack = LocalOnBack.current
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        if (onBack != null) {
                            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text("←", color = PipoColors.Ink, style = TextStyle(fontSize = 22.sp))
                            }
                        }
                    }
                    Text(
                        "我的歌单",
                        color = PipoColors.Ink,
                        style = TextStyle(fontSize = 14.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 右侧两个 icon chip：蒸馏 / 画像
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconChip(
                            tint = if (mode == "select") PipoColors.Mint else PipoColors.Ink,
                            onClick = {
                                if (mode == "select" && selectedPlaylists.isNotEmpty()) {
                                    confirmOpen = true
                                } else {
                                    mode = if (mode == "select") "library" else "select"
                                    if (mode == "library") selectedIds.clear()
                                }
                            },
                        ) { SparkIcon(modifier = Modifier.size(20.dp)) }

                        IconChip(
                            tint = PipoColors.Ink,
                            onClick = { nav?.openTaste?.invoke() },
                        ) { ProfileIcon(modifier = Modifier.size(20.dp)) }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (mode == "select") {
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
                            TextButton(onClick = { playlists.forEach { selectedIds[it.id] = true } }) {
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
                        items(items = playlists, key = { it.id }) { p ->
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

                // cover-flow pager
                //   - 焦点 cover 控制在 260dp，给下方曲目列表多留空间
                //   - contentPadding 不对称：左 24dp 让焦点 cover 左边沿恰好对齐下方"歌单标题"的左边沿
                //     右 80dp 让"下一张"以小片露出来作为下一页提示（Apple Music 风）
                //   - pageSpacing 缩小到 8dp，左右两侧的封面更紧凑
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(260.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 80.dp),
                    pageSpacing = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(276.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Black,
                                        0.94f to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) { index ->
                    val page = libraryPages[index]
                    val pageOffset = (
                        (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                    ).coerceIn(-1f, 1f)
                    val absOffset = kotlin.math.abs(pageOffset)
                    val scale = 1f - absOffset * 0.14f
                    val alpha = 1f - absOffset * 0.4f
                    val rotY = pageOffset * 24f
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                                rotationY = rotY
                                cameraDistance = 12f * density
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x16FFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!page.coverUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = page.coverUrl,
                                contentDescription = page.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // 没 cover 也没首曲兜底（多见于刚拉到、tracks 还没回来的网盘
                            // 页和极少数全空歌单）：用 app logo 居中显示，比空白盒子更有
                            // 视觉锚点。
                            Image(
                                painter = painterResource(R.mipmap.ic_launcher_round),
                                contentDescription = page.title,
                                modifier = Modifier
                                    .fillMaxSize(0.42f)
                                    .graphicsLayer { this.alpha = 0.85f },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 焦点歌单标题 —— 左边沿严格对齐 cover 左边沿 24dp
                if (focused != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = focused.title,
                            color = PipoColors.Ink,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "${focused.trackCount} 首",
                        color = PipoColors.TextDim,
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 4.dp),
                    )
                    if (focused is LibraryPage.CurrentQueue) {
                        PlaybackModeStrip(
                            mode = playerState.playbackMode,
                            onMode = playerVm::setPlaybackMode,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
                        )
                    }
                }

                // 曲目列表 —— LazyColumn + weight(1f)，铺到屏幕底部不截断、不限 80 首
                val onBack = LocalOnBack.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp)
                        .nestedScroll(nestedScrollConnection),
                ) {
	                    LazyColumn(
	                        state = trackListState,
	                        modifier = Modifier.fillMaxSize(),
	                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
	                    ) {
	                        if ((refreshGapPx > 0.5f || isRefreshing) && (focused is LibraryPage.PlaylistItem || focused is LibraryPage.CloudDisk)) {
	                            item(key = "__pull_refresh__") {
	                                val refreshGapDp = with(localDensity) { refreshGapPx.toDp() }
	                                val pullProgress = (pullOffset / refreshThresholdPx).coerceIn(0f, 1f)
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
	                        when {
                            // 只有"真没东西可显"才让位给加载文案；切歌单时 cache 命中很快，
                            // 旧 tracks 先撑着，新 tracks 一到直接替换 → 用户看不到"多行→
                            // 一行→多行"那一下 strobe。这是上次"闪"的真正源头。
                            loading && tracks.isEmpty() -> item {
                                Text(
                                    "加载中…",
                                    color = PipoColors.TextDim,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            trackLoadError != null -> item {
                                Column(
                                    modifier = Modifier.padding(start = 24.dp, end = 24.dp),
                                ) {
                                    Text(
                                        trackLoadError.orEmpty(),
                                        color = PipoColors.TextDim,
                                        style = TextStyle(fontSize = 13.sp),
                                    )
                                    TextButton(onClick = { trackLoadRetry += 1 }) {
                                        Text("重试", color = PipoColors.Ink)
                                    }
                                }
                            }
                            tracks.isEmpty() -> item {
                                Text(
                                    if (focused is LibraryPage.CloudDisk) "网盘里还没有歌" else "这张歌单是空的",
                                    color = PipoColors.TextDim,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            filteredTracks.isEmpty() -> item {
                                Text(
                                    "未找到匹配歌曲",
                                    color = PipoColors.TextDim,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            else -> {
                                itemsIndexed(
                                    items = filteredTracks,
                                    key = { index, track -> "${track.id}:$index" },
                                ) { _, t ->
                                    val isCurrent = t.id == currentTrackId
                                    if (focused is LibraryPage.CurrentQueue) {
                                        SwipeToRevealDeleteRow(
                                            track = t,
                                            isCurrentTrack = isCurrent,
                                            isPlaying = playerState.isPlaying,
                                            onDelete = {
                                                playerVm.removeTrack(t.id)
                                            },
                                            onClick = {
                                                playerVm.playCurrentQueueTrack(t.id)
                                                onBack?.invoke()
                                            }
                                        )
                                    } else {
                                        TrackListRow(
                                            track = t,
                                            isCurrentTrack = isCurrent,
                                            isPlaying = playerState.isPlaying,
                                            onClick = {
                                                playerVm.playTrack(t, filteredTracks)
                                                onBack?.invoke()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 搜索框作为覆盖层，不参与 LazyColumn 测量，避免出现/收起时挤压列表导致抖动。
                    if (searchHeightAnim.value > 0f) {
	                        val searchProgress = (searchHeightAnim.value / searchBarMaxHeightPx).coerceIn(0f, 1f)
	                        val searchShape = RoundedCornerShape(14.dp)
	                        Row(
	                            modifier = Modifier
	                                .align(Alignment.TopCenter)
	                                .fillMaxWidth()
	                                .padding(horizontal = 16.dp)
	                                .height(38.dp)
	                                .graphicsLayer {
	                                    alpha = searchProgress
	                                    translationY = with(localDensity) {
	                                        (-10).dp.toPx() * (1f - searchProgress)
	                                    }
	                                }
	                                .clip(searchShape)
	                                .background(Color(0xF238403A))
	                                .border(1.dp, Color.White.copy(alpha = 0.12f), searchShape)
	                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SearchIcon(
                                color = PipoColors.TextDim,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = PipoColors.Ink,
                                    fontSize = 13.sp
                                ),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSearch = { keyboardController?.hide() }
                                ),
	                                modifier = Modifier
	                                    .weight(1f)
	                                    .focusRequester(searchFocusRequester),
	                                cursorBrush = androidx.compose.ui.graphics.SolidColor(PipoColors.Ink),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索当前歌单歌曲",
                                            color = PipoColors.TextDim,
                                            style = TextStyle(fontSize = 13.sp)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { closeSearch() }
                                    .semantics { contentDescription = "关闭搜索" }
                                    .padding(4.dp)
                            ) {
                                CloseIcon(
                                    color = PipoColors.TextDim,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }

	                    if (shouldShowLocateCurrent) {
	                        Box(
	                            modifier = Modifier
	                                .align(Alignment.BottomEnd)
	                                .padding(end = 76.dp, bottom = 20.dp)
	                                .size(38.dp)
	                                .clip(CircleShape)
	                                .background(Color(0xD9424842))
	                                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                .semantics { contentDescription = "定位到当前播放" }
                                .clickable {
                                    scope.launch {
                                        if (currentTrackIndexInVisibleTracks >= 0) {
                                            trackListState.animateScrollToItem(currentTrackIndexInVisibleTracks)
                                        } else {
	                                            val queuePage = libraryPages.indexOfFirst { it is LibraryPage.CurrentQueue }
	                                            if (queuePage >= 0) {
	                                                pendingLocateCurrent = true
	                                                locateTargetListKey = "current"
	                                                pagerState.animateScrollToPage(queuePage)
	                                            }
                                        }
                                    }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            LocateCurrentIcon(color = Color(0xEEF4F6F8), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // 蒸馏全屏遮罩已删除 —— 蒸馏在 DistillCoordinator 后台跑，
            // 进度由 shell 层（PipoNativeApp）统一画一条小浮条
        }
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
    val transition = rememberInfiniteTransition(label = "playlistWave")

    @Composable
    fun barHeight(index: Int, idle: Float): Float {
        val value by transition.animateFloat(
            initialValue = 0.35f + index * 0.14f,
            targetValue = 1f - index * 0.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, delayMillis = index * 110),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$index",
        )
        return if (isPlaying) value.coerceIn(0.25f, 1f) else idle
    }

    Row(
        modifier = modifier.size(width = 18.dp, height = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0.45f, 0.72f, 0.55f).forEachIndexed { idx, idle ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((16f * barHeight(idx, idle)).dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
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
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
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
