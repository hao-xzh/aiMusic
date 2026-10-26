package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.playback.PlaybackQueueMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistBrowseDetail(session: BrowseSession, playlist: PipoPlaylist?, cloud: Boolean, revision: Int, currentId: String?, onBack: () -> Unit, onPlay: (List<NativeTrack>) -> Unit, onMore: (NativeTrack) -> Unit) {
    val repository = PipoGraph.repository
    val libraryRevision by repository.libraryRevision.collectAsState(initial = 0L)
    var tracks by remember(playlist?.id, cloud) { mutableStateOf(playlist?.let { repository.cachedTracksFor(it.id) }.orEmpty()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var requestedRefreshKey by remember(playlist?.id, cloud) { mutableStateOf<Pair<Int, Int>?>(null) }
    val query = session.detailQuery
    LaunchedEffect(playlist?.id, cloud, revision, retry, libraryRevision) {
        loading = true
        error = null
        val refreshKey = revision to retry
        val forceRefresh = (revision > 0 || retry > 0) && requestedRefreshKey != refreshKey
        requestedRefreshKey = refreshKey
        try { tracks = if (cloud) repository.cloudDiskTracksForBrowse(forceRefresh = forceRefresh) else repository.tracksForPlaylist(requireNotNull(playlist).id, forceRefresh = forceRefresh) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "歌曲加载失败" }
        finally { loading = false }
    }
    val cover = playlist?.coverUrl ?: tracks.firstOrNull()?.artworkUrl
    val visible = remember(tracks, query) { tracks.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) } }
    Box(Modifier.fillMaxSize().background(BrowseBackground)) {
        PlaylistDetailBackdrop(cover, useCoverEdgeColors(cover), cover != null, 420.dp, 0f, darkSurface = true)
        Box(Modifier.fillMaxSize().background(BrowseBackground.copy(alpha = 0.25f)))
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = session.detailScroll, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 22.dp + LocalBrowseBottomInset.current)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = BrowseInk) }
                    IconButton(onClick = { retry++ }) { Icon(Icons.Rounded.Refresh, "刷新歌单", tint = BrowseInk) }
                }
                Spacer(Modifier.height(200.dp))
                Text(if (cloud) "网易云 · 网盘" else "PIPO · 歌单", color = BrowseMuted, fontSize = 13.sp)
                Text(playlist?.name ?: "我的网盘", fontSize = 34.sp, lineHeight = 42.sp, color = BrowseInk, modifier = Modifier.padding(vertical = 10.dp))
                Text(if (loading && tracks.isEmpty()) "正在加载…" else "${tracks.size} 首歌曲", color = BrowseMuted)
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onPlay(tracks) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { PlayGlyph(Color.Black, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("播放", fontSize = 16.sp) }

                }
                BrowseSearchField(query, { session.detailQuery = it }, "在歌单中搜索")
                error?.let { BrowseNotice(it, "重试", { retry++ }) }
                if (!loading && error == null && tracks.isEmpty()) BrowseNotice("歌单还是空的，从歌曲菜单添加音乐。")
                else if (query.isNotBlank() && visible.isEmpty()) BrowseNotice("歌单中没有匹配的歌曲。")
            }
            items(visible, key = { it.id }) { track ->
                BrowseTrackRow(track, { onPlay(listOf(track) + tracks.filterNot { it.id == track.id }) }, { onMore(track) }, currentId == track.id)
            }
        }
    }
    // The app owns Back routing: this detail remains mounted beneath player/AI overlays.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseQueueSheet(player: PlayerViewModel, onDismiss: () -> Unit) {
    val library = PipoGraph.music.library
    val unifiedState by library.state.collectAsState()
    val queue = player.state.queue
    val upcomingStart = (player.state.currentIndex + 1).coerceIn(0, queue.size)
    // 序号只区分同一来源歌曲的重复项；播放推进或删掉前面的歌，不重建后面所有行。
    val queueKeys = remember(queue) {
        val occurrences = mutableMapOf<String, Int>()
        queue.map { track ->
            val sourceKey = track.sourceKey
            val occurrence = occurrences.getOrDefault(sourceKey, 0)
            occurrences[sourceKey] = occurrence + 1
            "queue:$sourceKey:$occurrence"
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var favoriteRequests by remember { mutableStateOf(emptySet<String>()) }
    val feedbackScope = rememberCoroutineScope()
    fun showMessage(message: String) {
        feedbackScope.launch { snackbar.showSnackbar(message) }
    }
    fun isLiked(track: NativeTrack): Boolean? = library.isLiked(track)
    fun toggleLike(track: NativeTrack) {
        if (track.sourceKey in favoriteRequests) return
        val previous = isLiked(track)
        if (previous == null) {
            showMessage("请先连接音乐账号并刷新资料库")
            return
        }
        // 写入由播放器生命周期承接，关闭弹窗或移除这一行不会取消收藏请求。
        favoriteRequests = favoriteRequests + track.sourceKey
        player.viewModelScope.launch {
            try {
                val result = library.like(track, !previous)
                if (result.pendingProviderIds.isNotEmpty()) {
                    showMessage(favoriteWriteMessage(result.confirmedProviderIds, result.pendingProviderIds, !previous))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage("《${track.title}》${if (previous) "取消喜欢" else "收藏"}失败，请重试")
            } finally {
                favoriteRequests = favoriteRequests - track.sourceKey
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF191F26), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 22.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "queue-header", contentType = "queue-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("接下来播放", color = BrowseInk, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        val mode = player.state.playbackMode
                        val modes = listOf(PlaybackQueueMode.OrderOnce, PlaybackQueueMode.PlaylistLoop, PlaybackQueueMode.SingleLoop, PlaybackQueueMode.ShufflePlay, PlaybackQueueMode.AiRadio)
                        val label = when (mode) {
                            PlaybackQueueMode.OrderOnce -> "顺序播放"
                            PlaybackQueueMode.PlaylistLoop -> "列表循环"
                            PlaybackQueueMode.SingleLoop -> "单曲循环"
                            PlaybackQueueMode.ShufflePlay -> "随机播放"
                            PlaybackQueueMode.AiRadio -> "AI 续播"
                        }
                        val icon = when (mode) {
                            PlaybackQueueMode.OrderOnce -> Icons.AutoMirrored.Rounded.PlaylistPlay
                            PlaybackQueueMode.PlaylistLoop -> Icons.Rounded.Repeat
                            PlaybackQueueMode.SingleLoop -> Icons.Rounded.RepeatOne
                            PlaybackQueueMode.ShufflePlay -> Icons.Rounded.Shuffle
                            PlaybackQueueMode.AiRadio -> Icons.Rounded.AutoAwesome
                        }
                        TextButton(onClick = { player.setPlaybackMode(modes[(modes.indexOf(mode) + 1) % modes.size]) }) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label)
                        }
                    }
                    Text("正在播放", color = BrowseMuted, modifier = Modifier.padding(vertical = 14.dp))
                    player.state.queue.getOrNull(player.state.currentIndex)?.let { track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrowseArtwork(track.artworkUrl, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(12.dp)) { Text(track.title, color = BrowseInk); Text(track.artist, color = BrowseMuted, fontSize = 13.sp) }
                            QueueFavoriteButton(track, remember(unifiedState, track) { isLiked(track) }, track.sourceKey in favoriteRequests) { toggleLike(track) }
                            IconButton(onClick = player::toggle, modifier = Modifier.size(48.dp)) { Icon(if (player.state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.state.isPlaying) "暂停" else "播放", tint = PipoColors.Accent, modifier = Modifier.size(24.dp)) }
                        }
                    } ?: BrowseNotice("还没有正在播放的歌曲")
                    Text("待播歌曲", color = BrowseMuted, modifier = Modifier.padding(vertical = 18.dp))
                }
                items(
                    count = queue.size - upcomingStart,
                    key = { queueKeys[upcomingStart + it] },
                    contentType = { "queue-track" },
                ) { index ->
                    val track = queue[upcomingStart + index]
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrowseArtwork(track.artworkUrl, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).clickable { player.playCurrentQueueTrack(track.id) }.padding(horizontal = 12.dp)) { Text(track.title, color = BrowseInk, maxLines = 1); Text(track.artist, color = BrowseMuted, fontSize = 13.sp, maxLines = 1) }
                        QueueFavoriteButton(track, remember(unifiedState, track) { isLiked(track) }, track.sourceKey in favoriteRequests) { toggleLike(track) }
                        IconButton(onClick = { player.removeTrack(track.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.RemoveCircleOutline, "从待播队列移除${track.title}", tint = BrowseMuted, modifier = Modifier.size(24.dp)) }
                    }
                }
                if (upcomingStart == queue.size) item(key = "queue-empty", contentType = "queue-empty") { BrowseNotice("没有更多待播歌曲") }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun QueueFavoriteButton(track: NativeTrack, liked: Boolean?, pending: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = track.musicSource != null && liked != null && !pending,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "${if (liked == true) "取消喜欢" else "喜欢"}${track.title}"
            stateDescription = when (liked) {
                true -> "已喜欢"
                false -> "未喜欢"
                null -> "收藏状态待确认"
            }
        },
    ) {
        HeartGlyph(
            filled = liked == true,
            color = if (liked == true) Color(0xFFFF4D67) else BrowseMuted,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatePlaylistSheet(onDismiss: () -> Unit, onCreated: (PipoPlaylist) -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = BrowseBackground, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("新建歌单", color = BrowseInk, fontSize = 28.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = BrowseMuted) }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("歌单名称") }, singleLine = true, enabled = !busy, shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val id = PipoGraph.repository.createPlaylist(name.trim())
                        onCreated(PipoPlaylist(id, name.trim(), 0))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "创建失败，请重试" }
                    finally { busy = false }
                }
            }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text(if (busy) "正在创建…" else "创建歌单") }
            Text("创建后可从歌曲菜单添加音乐", color = BrowseMuted, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsSheet(track: NativeTrack, unifiedPlaylistId: String?, player: PlayerViewModel, onDismiss: () -> Unit, onChanged: () -> Unit, onNotInterested: (() -> Unit)? = null) {
    val library = PipoGraph.music.library
    val unifiedState by library.state.collectAsState()
    val scope = rememberCoroutineScope()
    val liked = library.isLiked(track)
    val favoriteSources = favoriteSourceLabels(track, unifiedState)
    val sourceLabel = track.musicSource?.providerId?.let(MusicProviders::label) ?: "当前平台"
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(track.sourceKey) { mutableStateOf<String?>(null) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block(); onChanged(); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun changeLike() {
        if (busy || liked == null) return
        busy = true
        message = null
        scope.launch {
            try {
                val result = library.like(track, !liked)
                // The library publishes this song's confirmed membership. A full catalog refresh
                // here would restart unrelated background synchronization after every heart tap.
                if (result.pendingProviderIds.isEmpty()) onDismiss()
                else message = favoriteWriteMessage(result.confirmedProviderIds, result.pendingProviderIds, !liked)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "收藏暂未完成，请稍后重试。" }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color(0xFF191F26), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrowseArtwork(track.artworkUrl, Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(14.dp)) { Text(track.title, color = BrowseInk, fontSize = 24.sp); Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), color = BrowseMuted, fontSize = 13.sp) }
                IconButton(onClick = onDismiss, enabled = !busy) { CloseIcon(BrowseMuted, Modifier.size(22.dp)) }
            }
            message?.let { BrowseNotice(it) }
            if (favoriteSources.isNotEmpty()) BrowseNotice("已收藏来源：${favoriteSources.joinToString(" · ")}")
            if (busy) BrowseNotice("正在处理…")
            if (choosing) {
                TextButton(onClick = { choosing = false }, enabled = !busy) { Text("‹ 返回歌曲操作") }
                Text("添加到歌单", color = BrowseInk, fontSize = 22.sp)
                val writable = unifiedState.playlists.filter { it.bindings.none { binding -> binding.isFavorites } }
                if (writable.isEmpty()) BrowseNotice("没有统一歌单，请先在资料库新建或绑定歌单。")
                writable.forEach { target -> TextButton(onClick = { perform { library.add(target.id, track) } }, enabled = !busy) { Text(target.name, color = BrowseInk) } }
            } else {
                TextButton(onClick = { if (player.queueNext(track)) onDismiss() else message = "暂时无法加入队列" }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = BrowseInk, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(18.dp)); Text("下一首播放", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                TextButton(onClick = { choosing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) { PlaylistAddGlyph(BrowseInk, Modifier.size(24.dp)); Spacer(Modifier.width(18.dp)); Text("添加到歌单", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                TextButton(onClick = {
                    if (liked == null) message = "请先连接音乐账号并刷新资料库"
                    else changeLike()
                }, enabled = !busy && liked != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    HeartGlyph(filled = liked == true, color = when (liked) {
                        true -> Color(0xFFFF4D67)
                        false -> BrowseInk
                        null -> BrowseMuted
                    }, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(18.dp))
                    Text(
                        when (liked) {
                            true -> "取消在${sourceLabel}的收藏"
                            false -> "添加到我喜欢"
                            null -> "正在确认收藏状态…"
                        },
                        color = if (liked == null) BrowseMuted else BrowseInk,
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (liked == true) Text("仅取消当前音源的收藏，不影响另一端。", color = BrowseMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 42.dp, bottom = 6.dp))
                if (onNotInterested != null) {
                    TextButton(onClick = { onNotInterested(); onDismiss() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        Icon(Icons.Rounded.RemoveCircleOutline, null, tint = BrowseInk, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("不感兴趣", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f))
                    }
                }
                if (unifiedPlaylistId != null && unifiedState.playlists.any { it.id == unifiedPlaylistId && it.bindings.none { binding -> binding.isFavorites } }) {
                    HorizontalDivider(color = BrowseMuted.copy(alpha = 0.15f))
                    TextButton(onClick = { perform { library.remove(unifiedPlaylistId, track) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Color(0xFFFF7668)); Spacer(Modifier.width(18.dp)); Text("从歌单移除", color = Color(0xFFFF7668), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                }
            }
            FilledTonalButton(onClick = onDismiss, enabled = !busy, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.06f), contentColor = BrowseInk), modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).heightIn(min = 50.dp)) { Text("取消", color = BrowseInk) }
        }
    }
}

/** Material 3 1.3 uses the device theme for its separate dialog window. */
@Composable
internal fun BrowseSheetSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }
}
