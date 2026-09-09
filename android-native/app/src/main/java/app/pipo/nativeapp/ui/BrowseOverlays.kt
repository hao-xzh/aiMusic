package app.pipo.nativeapp.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.playback.PlaybackQueueMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistBrowseDetail(session: BrowseSession, playlist: PipoPlaylist?, cloud: Boolean, revision: Int, currentId: String?, onBack: () -> Unit, onPlay: (List<NativeTrack>) -> Unit, onMore: (NativeTrack) -> Unit) {
    val repository = PipoGraph.repository
    var tracks by remember(playlist?.id, cloud) { mutableStateOf(playlist?.let { repository.cachedTracksFor(it.id) }.orEmpty()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val query = session.detailQuery
    LaunchedEffect(playlist?.id, cloud, revision, retry) {
        loading = true
        error = null
        try { tracks = if (cloud) repository.cloudDiskTracksForBrowse(forceRefresh = revision > 0 || retry > 0) else repository.tracksForPlaylist(requireNotNull(playlist).id, forceRefresh = revision > 0 || retry > 0) }
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
                Text(playlist?.name ?: "我的网盘", fontSize = 34.sp, color = BrowseInk, modifier = Modifier.padding(vertical = 10.dp))
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
    BackHandler(onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseQueueSheet(player: PlayerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF191F26), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 22.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("接下来播放", color = BrowseInk, fontSize = 24.sp, modifier = Modifier.weight(1f))
                    val mode = player.state.playbackMode
                    val modes = listOf(PlaybackQueueMode.OrderOnce, PlaybackQueueMode.PlaylistLoop, PlaybackQueueMode.SingleLoop, PlaybackQueueMode.ShufflePlay)
                    val label = when (mode) {
                        PlaybackQueueMode.OrderOnce -> "顺序播放"
                        PlaybackQueueMode.PlaylistLoop -> "列表循环"
                        PlaybackQueueMode.SingleLoop -> "单曲循环"
                        PlaybackQueueMode.ShufflePlay -> "随机播放"
                        PlaybackQueueMode.AiRadio -> "AI 连播"
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
                        IconButton(onClick = player::toggle) { Icon(if (player.state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.state.isPlaying) "暂停" else "播放", tint = PipoColors.Accent) }
                    }
                } ?: BrowseNotice("还没有正在播放的歌曲")
                Text("待播歌曲", color = BrowseMuted, modifier = Modifier.padding(vertical = 18.dp))
            }
            val upcoming = player.state.queue.drop(player.state.currentIndex + 1)
            items(upcoming, key = { it.id }) { track ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrowseArtwork(track.artworkUrl, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).clickable { player.playCurrentQueueTrack(track.id) }.padding(horizontal = 12.dp)) { Text(track.title, color = BrowseInk, maxLines = 1); Text(track.artist, color = BrowseMuted, fontSize = 13.sp, maxLines = 1) }
                    IconButton(onClick = { player.removeTrack(track.id) }) { Icon(Icons.Rounded.RemoveCircleOutline, "从待播队列移除${track.title}", tint = BrowseMuted) }
                }
            }
            if (upcoming.isEmpty()) item { BrowseNotice("没有更多待播歌曲") }
        }
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
internal fun TrackActionsSheet(track: NativeTrack, playlist: PipoPlaylist?, player: PlayerViewModel, onDismiss: () -> Unit, onChanged: () -> Unit, onNotInterested: (() -> Unit)? = null) {
    val repository = PipoGraph.repository
    val account by repository.account.collectAsState(initial = null)
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
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
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color(0xFF191F26), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrowseArtwork(track.artworkUrl, Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(14.dp)) { Text(track.title, color = BrowseInk, fontSize = 24.sp); Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), color = BrowseMuted, fontSize = 13.sp) }
                IconButton(onClick = onDismiss, enabled = !busy) { CloseIcon(BrowseMuted, Modifier.size(22.dp)) }
            }
            message?.let { BrowseNotice(it) }
            if (busy) BrowseNotice("正在处理…")
            if (choosing) {
                TextButton(onClick = { choosing = false }, enabled = !busy) { Text("‹ 返回歌曲操作") }
                Text("添加到歌单", color = BrowseInk, fontSize = 22.sp)
                val writable = playlists.filter { account != null && it.userId == account?.userId }
                if (writable.isEmpty()) BrowseNotice("没有可添加的自建歌单，请先在资料库新建歌单。")
                writable.forEach { target -> TextButton(onClick = { perform { repository.playlistModifyTracks(target.id, "add", listOf(requireNotNull(track.neteaseId))); repository.refreshPlaylists() } }, enabled = !busy) { Text(target.name, color = BrowseInk) } }
            } else {
                TextButton(onClick = { if (player.queueNext(track)) onDismiss() else message = "暂时无法加入队列" }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = BrowseInk, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(18.dp)); Text("下一首播放", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                TextButton(onClick = { if (account == null) message = "请先在设置中登录网易云" else choosing = true }, enabled = !busy && track.neteaseId != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) { PlaylistAddGlyph(BrowseInk, Modifier.size(24.dp)); Spacer(Modifier.width(18.dp)); Text("添加到歌单", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                TextButton(onClick = { if (account == null) message = "请先在设置中登录网易云" else perform { repository.likeSong(requireNotNull(track.neteaseId), true); repository.refreshPlaylists() } }, enabled = !busy && track.neteaseId != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) { HeartGlyph(false, BrowseInk, Modifier.size(24.dp)); Spacer(Modifier.width(18.dp)); Text("喜欢这首歌", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                if (onNotInterested != null) {
                    TextButton(onClick = { onNotInterested(); onDismiss() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        Icon(Icons.Rounded.RemoveCircleOutline, null, tint = BrowseInk, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("不感兴趣", color = BrowseInk, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f))
                    }
                }
                if (playlist != null && account != null && playlist.userId == account?.userId && track.neteaseId != null) {
                    HorizontalDivider(color = BrowseMuted.copy(alpha = 0.15f))
                    TextButton(onClick = { perform { repository.playlistModifyTracks(playlist.id, "del", listOf(track.neteaseId)); repository.refreshPlaylists() } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentPadding = PaddingValues(vertical = 8.dp)) { Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Color(0xFFFF7668)); Spacer(Modifier.width(18.dp)); Text("从歌单移除", color = Color(0xFFFF7668), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, modifier = Modifier.weight(1f)) }
                }
            }
            FilledTonalButton(onClick = onDismiss, enabled = !busy, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.06f), contentColor = BrowseInk), modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).heightIn(min = 50.dp)) { Text("取消", color = BrowseInk) }
        }
    }
}

/** Material 3 1.3 uses the device theme for its separate dialog window. */
@Composable
private fun BrowseSheetSystemBars() {
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
