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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import app.pipo.nativeapp.data.MusicProviders
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
        IconButton(onClick = onAccount, modifier = Modifier.semantics { contentDescription = "设置" }) { Icon(Icons.Rounded.Settings, contentDescription = null, tint = BrowseInk, modifier = Modifier.size(26.dp)) }
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
internal fun BrowseTrackRow(track: NativeTrack, onPlay: () -> Unit, onMore: () -> Unit, current: Boolean = false, showSource: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        BrowseArtwork(track.artworkUrl, Modifier.size(46.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(track.title, color = if (current) PipoColors.Accent else BrowseInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.musicSource?.providerId?.let(app.pipo.nativeapp.data.MusicProviders::label).takeIf { showSource })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                color = BrowseMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
                Text(when {
                    model.homeShowingPreviousBatch && model.homeLoading -> "正在更新推荐，当前仍是上一批。"
                    model.homeShowingPreviousBatch -> "暂时保留上一批推荐，稍后可以再换一批。"
                    else -> model.homeCaption.ifBlank { when {
                        model.recommendations.isNotEmpty() -> "这一批，慢慢听。"
                        model.homeLoading -> "正在寻找与你合拍的音乐"
                        else -> "下一首喜欢，等你发现。"
                    } }
                }, color = BrowseMuted, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                Column {
                    Text(if (model.homeShowingPreviousBatch) "上一批推荐" else "此刻，为你", color = BrowseMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.recommendations.firstOrNull()?.title ?: "听见新的喜欢", color = BrowseInk, fontSize = 22.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        FilledIconButton(onClick = { onPlay(model.recommendations) }, enabled = model.recommendations.isNotEmpty(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrowseInk, contentColor = Color.Black)) {
                            Icon(Icons.Rounded.PlayArrow, if (model.homeShowingPreviousBatch) "播放上一批推荐" else "播放此刻推荐")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("为你推荐", color = BrowseInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (model.homeLoading || model.homeAppending) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = BrowseMuted, strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (model.homeAppending) "后台补充…" else "正在寻找…", color = BrowseMuted, fontSize = 14.sp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBrowseScreen(session: BrowseSession, model: BrowseViewModel, signedIn: Boolean, qqSignedIn: Boolean, cloudCount: Int, unifiedLibrary: app.pipo.nativeapp.data.library.UnifiedLibraryState, manualLibraryRefreshing: Boolean, onAccount: () -> Unit, onSearch: () -> Unit, onCloud: () -> Unit, onCreate: () -> Unit, onUnifiedPlaylist: (String) -> Unit, onCreateUnified: () -> Unit, onBindUnified: () -> Unit, onRefreshUnified: () -> Unit, onLogin: () -> Unit, onPlay: (List<NativeTrack>) -> Unit, onMore: (NativeTrack) -> Unit, onAi: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val submit: () -> Unit = { model.search(); keyboard?.hide(); focusManager.clearFocus() }
    val closeSearch: () -> Unit = { session.closeSearch(); model.editQuery(""); keyboard?.hide(); focusManager.clearFocus() }
    val filter = session.libraryFilter
    var createMenuOpen by remember(filter, session.searchOpen) { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = !session.searchOpen && if (filter == "网盘") model.libraryLoading else manualLibraryRefreshing,
        onRefresh = {
            if (!session.searchOpen) {
                if (filter == "网盘") model.refreshLibrary(showRefresh = true) else onRefreshUnified()
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(Modifier.fillMaxSize().background(BrowseBackground).statusBarsPadding(), state = if (session.searchOpen) session.librarySearchScroll else session.libraryScroll, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp + LocalBrowseBottomInset.current)) {
        item {
            BrowseBrand(onAccount, onSearch)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("资料库", color = BrowseInk, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (session.searchOpen) TextButton(onClick = closeSearch) { Text("取消", color = BrowseMuted) }
                else Box {
                    TextButton(onClick = {
                        if (filter == "网盘") { if (signedIn) onCreate() else onLogin() }
                        else createMenuOpen = true
                    }) { Icon(Icons.Rounded.Add, null); Text("新建", color = BrowseInk) }
                    DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("新建歌单") }, onClick = { createMenuOpen = false; onCreateUnified() })
                        if (signedIn && qqSignedIn) DropdownMenuItem(text = { Text("绑定歌单") }, onClick = { createMenuOpen = false; onBindUnified() })
                    }
                }
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
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("全部", "网易云", "QQ音乐", "网盘").forEach { value ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { session.libraryFilter = value }, modifier = Modifier.semantics { selected = value == filter }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(value, color = if (value == filter) BrowseInk else BrowseMuted, fontSize = 16.sp) }
                        Box(Modifier.width(28.dp).height(2.dp).background(if (value == filter) BrowseInk else Color.Transparent, RoundedCornerShape(1.dp)))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!signedIn && filter == "网盘") BrowseNotice("登录后查看网易云网盘。", "登录网易云", onLogin)
            if (filter == "网盘" && model.libraryError != null) BrowseNotice("网盘暂时无法加载，请稍后重试。", "重试", { model.refreshLibrary(showRefresh = true) })
        }
        if (session.searchOpen) {
            items(model.results, key = { it.id }) { track ->
                BrowseTrackRow(track, { onPlay(listOf(track)) }, { onMore(track) }, showSource = true)
            }
            return@LazyColumn
        }
        if (filter != "网盘") {
            unifiedLibraryItems(unifiedLibrary, if (filter == "QQ音乐") MusicProviders.QQ else if (filter == "网易云") MusicProviders.NETEASE else null, signedIn, qqSignedIn, onUnifiedPlaylist, onRefreshUnified, onLogin)
        }
        if (signedIn && filter == "网盘") item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onCloud).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, null, tint = BrowseMuted, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) { Text("我的网盘", color = BrowseInk); Text(if (cloudCount > 0) "$cloudCount 首" else "网易云网盘", color = BrowseMuted, fontSize = 13.sp) }
                Icon(Icons.Rounded.ChevronRight, null, tint = BrowseMuted)
            }
        }
    }
    }
}
