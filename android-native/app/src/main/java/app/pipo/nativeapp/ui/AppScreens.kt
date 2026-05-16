package app.pipo.nativeapp.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.playback.PlaybackQueueMode
import app.pipo.nativeapp.playback.PlayerViewModel
import kotlinx.coroutines.launch

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
    ) : LibraryPage(
        title = playlist.name,
        trackCount = playlist.trackCount,
        coverUrl = playlist.coverUrl,
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
    val libraryPages = remember(
        playlists,
        playerState.queue,
        playerState.currentIndex,
        playerState.artworkUrl,
    ) {
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
            addAll(playlists.map { LibraryPage.PlaylistItem(it) })
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
    var tracks by remember { mutableStateOf<List<NativeTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val trackListState = rememberLazyListState()
    var pendingLocateCurrent by remember { mutableStateOf(false) }
    val focusedListKey = when (val page = focused) {
        is LibraryPage.CurrentQueue -> "current"
        is LibraryPage.PlaylistItem -> "playlist:${page.playlist.id}"
        null -> "none"
    }
    val focusedQueue = (focused as? LibraryPage.CurrentQueue)?.queue
    val focusedPlaylist = (focused as? LibraryPage.PlaylistItem)?.playlist
    val trackListTransitionAlpha = remember { Animatable(1f) }
    LaunchedEffect(focusedListKey) {
        if (!pendingLocateCurrent) {
            trackListState.scrollToItem(0)
        }
        trackListTransitionAlpha.snapTo(0.88f)
        trackListTransitionAlpha.animateTo(1f, tween(durationMillis = 160))
    }
    LaunchedEffect(focusedListKey, focusedQueue, focusedPlaylist) {
        when (val page = focused) {
            is LibraryPage.CurrentQueue -> {
                loading = false
                tracks = page.queue
            }
            is LibraryPage.PlaylistItem -> {
                loading = true
                tracks = runCatching { repository.tracksForPlaylist(page.playlist.id) }.getOrDefault(emptyList())
                loading = false
            }
            null -> {
                loading = false
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
        }
    }

    // ---- 蒸馏状态 + 进度 + 多选模式 ----
    // 蒸馏跑在 app-level coordinator（DistillCoordinator），跟 Composable 进出无关。
    // 这里只用它的 StateFlow 来呈现 UI。即使用户切走，蒸馏仍在后台跑。
    val scope = rememberCoroutineScope()
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
                    .padding(top = 6.dp, bottom = 60.dp),
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

                Spacer(modifier = Modifier.height(14.dp))

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
                //   - 焦点 cover 加大到 280dp（之前 220dp）
                //   - contentPadding 不对称：左 24dp 让焦点 cover 左边沿恰好对齐下方"歌单标题"的左边沿
                //     右 80dp 让"下一张"以小片露出来作为下一页提示（Apple Music 风）
                //   - pageSpacing 缩小到 8dp，左右两侧的封面更紧凑
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(280.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 80.dp),
                    pageSpacing = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
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
                            .size(280.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                                rotationY = rotY
                                cameraDistance = 12f * density
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x16FFFFFF)),
                    ) {
                        if (!page.coverUrl.isNullOrBlank()) {
                            coil.compose.AsyncImage(
                                model = page.coverUrl,
                                contentDescription = page.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 焦点歌单标题 —— 左边沿严格对齐 cover 左边沿 24dp
                if (focused != null) {
                    Text(
                        text = focused.title,
                        color = PipoColors.Ink,
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        .padding(top = 12.dp),
                ) {
                    LazyColumn(
                        state = trackListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = trackListTransitionAlpha.value },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                    ) {
                        when {
                            loading -> item {
                                Text(
                                    "加载中…",
                                    color = PipoColors.TextDim,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            tracks.isEmpty() -> item {
                                Text(
                                    "这张歌单是空的",
                                    color = PipoColors.TextDim,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                            }
                            else -> {
                                items(
                                    items = tracks,
                                    key = { it.id },
                                ) { t ->
                                    TrackListRow(
                                        track = t,
                                        isCurrentTrack = t.id == currentTrackId,
                                        isPlaying = playerState.isPlaying,
                                        onClick = {
                                            playerVm.playTrack(t, tracks)
                                            onBack?.invoke()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (shouldShowLocateCurrent) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 18.dp, bottom = 18.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PipoColors.Mint.copy(alpha = 0.18f))
                                .semantics { contentDescription = "定位到当前播放" }
                                .clickable {
                                    scope.launch {
                                        if (currentTrackIndexInVisibleTracks >= 0) {
                                            trackListState.animateScrollToItem(currentTrackIndexInVisibleTracks)
                                        } else {
                                            val queuePage = libraryPages.indexOfFirst { it is LibraryPage.CurrentQueue }
                                            if (queuePage >= 0) {
                                                pendingLocateCurrent = true
                                                pagerState.animateScrollToPage(queuePage)
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            LocateCurrentIcon(color = PipoColors.Ink, modifier = Modifier.size(21.dp))
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
            label = "列表循环",
            selected = mode == PlaybackQueueMode.PlaylistLoop,
            onClick = { onMode(PlaybackQueueMode.PlaylistLoop) },
        ) { tint ->
            RepeatModeIcon(color = tint, modifier = Modifier.size(19.dp))
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
