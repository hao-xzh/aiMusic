package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoPlaylist
import coil.compose.AsyncImage

internal val BrowseBackground = Color(0xFF070B12)
internal val BrowseInk = Color(0xFFF3F5F7)
internal val BrowseMuted = Color(0xFF939DA8)
private const val HomeDefaultArtworkUrl = "file:///android_asset/artwork/login-music-classics.png"

@Composable
internal fun BrowseBrand(onAccount: () -> Unit, onSearch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("PIPO", fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
            color = BrowseInk, modifier = Modifier.weight(1f))
        if (onSearch != null) IconButton(onClick = onSearch, modifier = Modifier.semantics { contentDescription = "搜索" }) { SearchIcon(BrowseInk, Modifier.size(24.dp)) }
        IconButton(onClick = onAccount, modifier = Modifier.semantics { contentDescription = "账号与设置" }) { ProfileIcon(BrowseInk, Modifier.size(26.dp)) }
    }
}

@Composable
internal fun BrowseNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(text, color = BrowseMuted, fontSize = 14.sp)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = BrowseInk) }
    }
}

@Composable
internal fun BrowseArtwork(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF14202C)), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Icon(Icons.Rounded.MusicNote, null, tint = BrowseMuted)
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun BrowseTrackRow(track: NativeTrack, onPlay: () -> Unit, onMore: () -> Unit, current: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        BrowseArtwork(track.artworkUrl, Modifier.size(46.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(track.title, color = if (current) PipoColors.Accent else BrowseInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = BrowseMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreHoriz, "${track.title}的歌曲操作", tint = BrowseMuted) }
    }
}

@Composable
internal fun BrowsePlaylistRow(playlist: PipoPlaylist, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        BrowseArtwork(playlist.coverUrl, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(playlist.name, color = BrowseInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.trackCount} 首", color = BrowseMuted, fontSize = 13.sp)
        }
        Icon(Icons.Rounded.ChevronRight, "打开歌单", tint = BrowseMuted)
    }
}

@Composable
internal fun HomeBrowseScreen(
    session: BrowseSession, model: BrowseViewModel, playlists: List<PipoPlaylist>, signedIn: Boolean,
    onAccount: () -> Unit, onSearch: () -> Unit, onLibrary: () -> Unit,
    onPlaylist: (PipoPlaylist) -> Unit, onCloud: () -> Unit, onLogin: () -> Unit,
    onPlay: (List<NativeTrack>) -> Unit, onMore: (NativeTrack) -> Unit,
) {
    val cover = model.recommendations.firstOrNull()?.artworkUrl?.takeIf { it.isNotBlank() }
        ?: playlists.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
        ?: HomeDefaultArtworkUrl
    val edges = useCoverEdgeColors(cover)
    Box(Modifier.fillMaxSize().background(BrowseBackground)) {
        PlaylistDetailBackdrop(cover, edges, true, 390.dp, 0f, darkSurface = true, fallbackCoverUrl = HomeDefaultArtworkUrl)
        Box(Modifier.fillMaxSize().background(BrowseBackground.copy(alpha = 0.22f)))
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = session.homeScroll, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp + LocalBrowseBottomInset.current)) {
            item { BrowseBrand(onAccount, onSearch) }
            item {
                Spacer(Modifier.height(140.dp))
                Text("听点不一样。", color = BrowseInk, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(model.homeCaption.ifBlank {
                    when {
                        model.recommendations.isNotEmpty() -> "这一批，慢慢听。"
                        model.homeLoading -> "正在寻找与你合拍的音乐"
                        else -> "下一首喜欢，等你发现。"
                    }
                }, color = BrowseMuted, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                Column {
                    Text("此刻，为你", color = BrowseMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.recommendations.firstOrNull()?.title ?: "听见新的喜欢", color = BrowseInk, fontSize = 22.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        FilledIconButton(onClick = { onPlay(model.recommendations) }, enabled = model.recommendations.isNotEmpty(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrowseInk, contentColor = Color.Black)) {
                            Icon(Icons.Rounded.PlayArrow, "播放此刻推荐")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("为你推荐", color = BrowseInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (model.homeLoading) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = BrowseMuted, strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在寻找…", color = BrowseMuted, fontSize = 14.sp)
                        }
                    } else if (model.homeError == null) {
                        TextButton(onClick = model::refreshHome, contentPadding = PaddingValues(vertical = 8.dp)) {
                            Text(if (model.recommendations.isEmpty()) "寻找推荐" else "换一批", color = BrowseMuted)
                            Spacer(Modifier.width(6.dp))
                            Text("✦", color = BrowseMuted, fontSize = 14.sp)
                        }
                    }
                }
                if (!model.homeLoading) model.homeError?.let { BrowseNotice(it, "重试", model::refreshHome) }
                if (model.recommendations.isEmpty() && !model.homeLoading && model.homeError == null && model.homeMessage.isNotBlank()) {
                    BrowseNotice(model.homeMessage)
                }
                model.dismissedTrack?.let { BrowseNotice("已减少推荐《${it.title}》", "撤销", model::undoDismissal) }
            }
            items(model.recommendations, key = { it.id }) { track ->
                Column(Modifier.padding(bottom = 10.dp)) {
                    BrowseTrackRow(track, {
                        val index = model.recommendations.indexOfFirst { it.id == track.id }
                        onPlay(model.recommendations.drop(index.coerceAtLeast(0)) + model.recommendations.take(index.coerceAtLeast(0)))
                    }, { onMore(track) })

                }
            }
            item {
                if (!signedIn) BrowseNotice("登录网易云，让推荐更了解你的收藏。", "登录", onLogin)
            }
        }
    }
}

@Composable
internal fun LibraryBrowseScreen(session: BrowseSession, model: BrowseViewModel, playlists: List<PipoPlaylist>, signedIn: Boolean, cloudCount: Int, onAccount: () -> Unit, onSearch: () -> Unit, onPlaylist: (PipoPlaylist) -> Unit, onCloud: () -> Unit, onCreate: () -> Unit, onLogin: () -> Unit, onPlay: (List<NativeTrack>) -> Unit, onMore: (NativeTrack) -> Unit, onAi: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val submit: () -> Unit = { model.search(); keyboard?.hide(); focusManager.clearFocus() }
    val closeSearch: () -> Unit = { session.closeSearch(); model.editQuery(""); keyboard?.hide(); focusManager.clearFocus() }
    val filter = session.libraryFilter
    val grid = session.libraryGrid
    val sort = session.librarySort
    var sortOpen by remember { mutableStateOf(false) }
    val ordered = remember(playlists, sort) { when (sort) { "名称" -> playlists.sortedBy { it.name }; "曲目数" -> playlists.sortedByDescending { it.trackCount }; else -> playlists.sortedByDescending { it.updateTime ?: 0L } } }
    LazyColumn(Modifier.fillMaxSize().background(BrowseBackground).statusBarsPadding(), state = if (session.searchOpen) session.librarySearchScroll else session.libraryScroll, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp + LocalBrowseBottomInset.current)) {
        item {
            BrowseBrand(onAccount, onSearch)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("资料库", color = BrowseInk, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (session.searchOpen) TextButton(onClick = closeSearch) { Text("取消", color = BrowseMuted) }
                else TextButton(onClick = if (signedIn) onCreate else onLogin) { Icon(Icons.Rounded.Add, null); Text("新建", color = BrowseInk) }
            }
            if (session.searchOpen) {
                val requester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(session.searchFocusPending) {
                    if (session.searchFocusPending) { requester.requestFocus(); session.searchFocusPending = false }
                }
                Spacer(Modifier.height(14.dp))
                BrowseSearchField(model.query, model::editQuery, "搜索歌曲或歌手名", submit,
                    modifier = Modifier.focusRequester(requester))
                if (model.searchLoading) BrowseNotice("正在搜索…")
                model.searchError?.let { BrowseNotice(it, "重试", model::search) }
                if (!model.submitted) {
                    TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus(); onAi() }) {
                        Text("还没想好听什么？让 PIPO 帮你选歌", color = BrowseMuted, fontSize = 13.sp)
                    }
                } else if (!model.searchLoading && model.searchError == null && model.results.isEmpty()) {
                    BrowseNotice("没有找到歌曲，试试其他关键词。")
                }
                if (model.results.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("歌曲结果", color = BrowseMuted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onPlay(model.results) }) { Text("播放全部 ›", color = BrowseInk) }
                    }
                }
                return@item
            }
            Text("你的歌单，都在这里。", color = BrowseMuted, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "歌单", "网盘").forEach { value ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { session.libraryFilter = value }, modifier = Modifier.semantics { selected = value == filter }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(value, color = if (value == filter) BrowseInk else BrowseMuted, fontSize = 16.sp) }
                        Box(Modifier.width(28.dp).height(2.dp).background(if (value == filter) BrowseInk else Color.Transparent, RoundedCornerShape(1.dp)))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { session.libraryGrid = true }, modifier = Modifier.semantics { selected = grid }) { Icon(Icons.Rounded.GridView, "网格视图", tint = if (grid) BrowseInk else BrowseMuted.copy(alpha = 0.55f)) }
                IconButton(onClick = { session.libraryGrid = false }, modifier = Modifier.semantics { selected = !grid }) { ListIcon(if (!grid) BrowseInk else BrowseMuted.copy(alpha = 0.55f), Modifier.size(24.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortOpen = !sortOpen }) {
                        Text(sort, color = BrowseMuted)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (sortOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = BrowseMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        listOf("最近更新", "名称", "曲目数").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { session.librarySort = value; sortOpen = false }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = model::refreshLibrary, enabled = !model.libraryLoading) { Text(if (model.libraryLoading) "刷新中…" else "刷新", color = BrowseMuted) }
            }
            if (!signedIn) BrowseNotice("登录后查看你的歌单和网盘。", "登录网易云", onLogin)
            model.libraryError?.let { BrowseNotice(it, "重试", model::refreshLibrary) }
        }
        if (session.searchOpen) {
            items(model.results, key = { it.id }) { track ->
                BrowseTrackRow(track, { onPlay(listOf(track)) }, { onMore(track) })
            }
            return@LazyColumn
        }
        if (signedIn && filter != "网盘") {
            if (grid) items(ordered.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { playlist ->
                        Column(Modifier.weight(1f).clickable { onPlaylist(playlist) }) {
                            BrowseArtwork(playlist.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(playlist.name, color = BrowseInk, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                            Text("${playlist.trackCount} 首", color = BrowseMuted, fontSize = 13.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            } else items(ordered, key = { it.id }) { BrowsePlaylistRow(it) { onPlaylist(it) } }
            if (ordered.isEmpty() && !model.libraryLoading) item { BrowseNotice("还没有歌单。", "新建歌单", onCreate) }
        }
        if (signedIn && filter != "歌单") item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onCloud).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null, tint = BrowseMuted, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) { Text("我的网盘", color = BrowseInk); Text(if (cloudCount > 0) "$cloudCount 首" else "网易云网盘", color = BrowseMuted, fontSize = 13.sp) }
                Icon(Icons.Rounded.ChevronRight, null, tint = BrowseMuted)
            }
        }
    }
}
