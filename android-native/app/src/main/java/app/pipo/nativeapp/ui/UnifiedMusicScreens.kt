package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.data.library.PendingLibraryMatch
import app.pipo.nativeapp.data.library.UnifiedLibraryState
import app.pipo.nativeapp.data.library.UnifiedMusicLibrary
import app.pipo.nativeapp.data.library.UnifiedPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal fun LazyListScope.unifiedLibraryItems(
    state: UnifiedLibraryState,
    sourceFilter: String?,
    neteaseConnected: Boolean,
    qqConnected: Boolean,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
) {
    val playlists = state.playlists.filter { playlist -> sourceFilter == null || playlist.bindings.any { it.providerId == sourceFilter } }
    val notices = state.errors.filter { (key, _) ->
        sourceFilter == null || key == sourceFilter || key in setOf("automatic", "library") || state.playlists.any { playlist ->
            playlist.bindings.any { binding -> binding.key == key && binding.providerId == sourceFilter }
        }
    }
    notices.forEach { (key, message) ->
        val provider = state.playlists.asSequence().flatMap { it.bindings.asSequence() }
            .firstOrNull { it.key == key }?.providerId ?: key
        item(key = "library-notice:$key", contentType = "library-notice") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (provider in setOf("automatic", "library")) "资料库" else MusicProviders.label(provider)}：$message", color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !state.syncing) { Text("重试") }
            }
        }
    }
    if (playlists.isEmpty() && !state.syncing && notices.isEmpty()) {
        val connected = when (sourceFilter) {
            MusicProviders.NETEASE -> neteaseConnected
            MusicProviders.QQ -> qqConnected
            else -> neteaseConnected || qqConnected
        }
        item(key = "library-empty", contentType = "library-notice") {
            if (connected) BrowseNotice("暂无歌单")
            else BrowseNotice("连接音乐账号后查看歌单。", "连接账号", onLogin)
        }
    }
    // 每个歌单独立复用、测量，避免把整个资料库当成一个不可回收的长列表项。
    items(playlists, key = { "library-playlist:${it.id}" }, contentType = { "library-playlist" }) { playlist ->
        UnifiedPlaylistRow(playlist, state.playlistErrors[playlist.id].orEmpty().isNotEmpty()) { onOpen(playlist.id) }
    }
}

@Composable
private fun UnifiedPlaylistRow(playlist: UnifiedPlaylist, needsRetry: Boolean, onOpen: () -> Unit) {
    val sourceText = playlist.bindings.map { MusicProviders.label(it.providerId) }.distinct().joinToString(" · ")
    val coverUrl = playlist.bindings.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
        ?: playlist.tracks.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
    val countText = when {
        playlist.tracks.isNotEmpty() -> "${playlist.tracks.size} 首"
        playlist.bindings.size == 1 -> "${playlist.bindings.single().trackCount} 首"
        playlist.bindings.any { it.trackCount > 0 } -> "点击查看"
        else -> "0 首"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        BrowseArtwork(coverUrl, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(playlist.name, color = BrowseInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$sourceText · $countText" + if (needsRetry) " · 待重试" else "", color = BrowseMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, "打开歌单", tint = BrowseMuted)
    }
}

@Composable
internal fun UnifiedPlaylistDetail(
    playlist: UnifiedPlaylist?,
    state: UnifiedLibraryState,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<NativeTrack>) -> Unit,
    onMore: (NativeTrack) -> Unit,
) {
    val library = PipoGraph.music.library
    val scope = rememberCoroutineScope()
    var busy by remember(playlist?.id) { mutableStateOf(false) }
    var message by remember(playlist?.id) { mutableStateOf<String?>(null) }
    var menuOpen by remember(playlist?.id) { mutableStateOf(false) }
    var syncDetailsOpen by remember(playlist?.id) { mutableStateOf(false) }
    fun perform(block: suspend () -> Unit) {
        if (busy || playlist == null) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "歌单操作暂未完成，请稍后重试。" }
            finally { busy = false }
        }
    }
    fun syncDetail() {
        perform {
            val id = requireNotNull(playlist).id
            library.sync(id)
            PipoGraph.distillCoordinator.ensureMixed(force = true)
        }
    }
    LaunchedEffect(playlist?.id) {
        val id = playlist?.id ?: return@LaunchedEffect
        // Keep the existing list visible while refreshing its remote contents in the background.
        busy = true
        message = null
        try {
            library.sync(id)
            PipoGraph.distillCoordinator.ensureMixed()
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "曲目暂未读取完成，请稍后重试。" }
        finally { busy = false }
    }
    val tracks = playlist?.tracks.orEmpty()
    val coverUrl = playlist?.bindings?.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
        ?: tracks.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
    val pending = state.pendingMatches.filter { it.playlistId == playlist?.id }
    val syncErrors = state.playlistErrors[playlist?.id].orEmpty()
    Box(Modifier.fillMaxSize().background(BrowseBackground)) {
        PlaylistDetailBackdrop(coverUrl, useCoverEdgeColors(coverUrl), coverUrl != null, 420.dp, 0f, darkSurface = true)
        Box(Modifier.fillMaxSize().background(BrowseBackground.copy(alpha = 0.25f)))
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 22.dp + LocalBrowseBottomInset.current)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = BrowseInk) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "歌单操作", tint = BrowseInk) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("刷新曲目") }, enabled = !busy, onClick = { menuOpen = false; syncDetail() })
                            DropdownMenuItem(text = { Text("同步详情") }, onClick = { menuOpen = false; syncDetailsOpen = true })
                        }
                    }
                }
                Spacer(Modifier.height(200.dp))
                Text(playlist?.name ?: "歌单", color = BrowseInk, fontSize = 34.sp, lineHeight = 42.sp, modifier = Modifier.padding(vertical = 10.dp))
                Text(playlist?.bindings?.map { MusicProviders.label(it.providerId) }?.distinct()?.joinToString(" · ").orEmpty(), color = BrowseMuted, fontSize = 14.sp)
                Text(if (tracks.isEmpty() && busy) "正在读取歌曲…" else "${tracks.size} 首歌曲", color = BrowseMuted, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                    Button(onClick = { onPlay(tracks) }, enabled = tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("播放")
                    }
                }
                if (tracks.isEmpty() && !busy) {
                    if (message != null || syncErrors.isNotEmpty()) BrowseNotice("暂时无法读取歌曲。", "重试", ::syncDetail)
                    else BrowseNotice("暂无歌曲")
                }
            }
            items(tracks, key = { it.sourceKey }) { track ->
                BrowseTrackRow(track, { onPlay(listOf(track) + tracks.filterNot { it.sourceKey == track.sourceKey }) }, { onMore(track) }, current = currentId == track.id, showSource = true)
            }
        }
    }
    if (syncDetailsOpen) {
        PlaylistSyncSheet(
            pending = pending, errors = (listOfNotNull(message) + syncErrors).distinct(), busy = busy,
            onDismiss = { syncDetailsOpen = false }, onRetry = ::syncDetail,
            onConfirm = { match, candidate -> perform { library.confirmMatch(match.id, candidate) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSyncSheet(
    pending: List<PendingLibraryMatch>, errors: List<String>, busy: Boolean,
    onDismiss: () -> Unit, onRetry: () -> Unit,
    onConfirm: (PendingLibraryMatch, NativeTrack) -> Unit,
) {
    val actionable = pending.filter { it.candidates.isNotEmpty() }
    val unmatchedCount = pending.size - actionable.size
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BrowseBackground,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("同步详情", color = BrowseInk, fontSize = 26.sp)
                if (busy) Text("正在处理…", color = BrowseMuted, modifier = Modifier.padding(top = 12.dp))
                errors.forEach { BrowseNotice(it) }
                if (errors.isNotEmpty()) TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
                if (unmatchedCount > 0) Text("$unmatchedCount 首暂未找到对应版本，原平台歌曲仍可正常播放。", color = BrowseMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                if (actionable.isNotEmpty()) Text("待确认版本（${actionable.size}）", color = BrowseInk, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
                if (pending.isEmpty() && errors.isEmpty() && !busy) Text("暂无需要处理的事项。", color = BrowseMuted, modifier = Modifier.padding(top = 12.dp))
            }
            items(actionable, key = { it.id }) { match ->
                PendingMatchRow(match, busy) { candidate -> onConfirm(match, candidate) }
            }
        }
    }
}

@Composable
private fun PendingMatchRow(match: PendingLibraryMatch, busy: Boolean, onConfirm: (NativeTrack) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(PipoDimens.SurfaceCornerDp)).padding(14.dp)) {
        Text("${match.track.title} · ${match.track.artist}", color = BrowseInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("请选择 ${MusicProviders.label(match.targetProviderId)} 中对应的录音版本", color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (match.candidates.isEmpty()) Text("暂无可确认候选，稍后同步后重试。", color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        match.candidates.forEach { candidate ->
            TextButton(onClick = { onConfirm(candidate) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, color = BrowseInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(candidate.artist, candidate.album).filter { it.isNotBlank() }.joinToString(" · "), color = BrowseMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("确认", color = PipoColors.Mint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateUnifiedPlaylistSheet(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val library = PipoGraph.music.library
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf(MusicProviders.NETEASE, MusicProviders.QQ)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = BrowseBackground, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("新建歌单", color = BrowseInk, fontSize = 28.sp)
            Text("将在所选平台各创建一张同名歌单；选择两边时会自动绑定。", color = BrowseMuted, fontSize = 14.sp)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("歌单名称") }, singleLine = true, enabled = !busy, shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp))
            listOf(MusicProviders.NETEASE, MusicProviders.QQ).forEach { provider ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = selected.toggle(provider) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = provider in selected, onCheckedChange = { selected = selected.toggle(provider) })
                    Text(MusicProviders.label(provider), color = BrowseInk)
                }
            }
            error?.let { BrowseNotice(it) }
            Button(onClick = {
                busy = true
                error = null
                scope.launch {
                    try { library.create(name.trim(), selected); onCreated(); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "创建失败，请重试" }
                    finally { busy = false }
                }
            }, enabled = name.isNotBlank() && selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text(if (busy) "正在创建…" else if (selected.size > 1) "创建并绑定" else "创建歌单") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BindUnifiedPlaylistSheet(onDismiss: () -> Unit, onBound: () -> Unit) {
    val catalog = PipoGraph.music
    val library = catalog.library
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var selected by remember { mutableStateOf<Map<String, MusicPlaylist>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var providerErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        loading = true
        val connected = catalog.providers.filter { it.connection.value.account != null }
        providerErrors = library.state.value.errors.filterKeys { key -> connected.any { it.id == key } }
        val cached = library.state.value.playlists.flatMap { it.bindings }.distinctBy { it.key }
        playlists = cached
        try {
            connected.forEach { provider ->
                if (cached.any { it.providerId == provider.id }) return@forEach
                try {
                    val result = withTimeout(BIND_CATALOG_TIMEOUT_MS) { provider.playlistCatalog() }
                    val loaded = result.playlists.filter { it.providerId == provider.id }
                    playlists = (playlists.filterNot { it.providerId == provider.id } + loaded).distinctBy { it.key }
                    if (!result.complete || result.warning != null) {
                        providerErrors = providerErrors + (provider.id to (result.warning ?: "歌单目录未完整读取"))
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    providerErrors = providerErrors + (provider.id to "读取歌单超时")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    providerErrors = providerErrors + (provider.id to (e.message ?: "读取歌单失败"))
                }
            }
        } finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = BrowseBackground, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { BottomSheetDefaults.DragHandle(color = BrowseMuted) }) {
        BrowseSheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("绑定歌单", color = BrowseInk, fontSize = 28.sp)
            Text("选择两家要同步的歌单。", color = BrowseMuted, fontSize = 14.sp)
            if (loading) BrowseNotice("正在读取已连接平台的歌单…")
            listOf(MusicProviders.NETEASE, MusicProviders.QQ).forEach { provider ->
                Text(MusicProviders.label(provider), color = BrowseInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                val choices = playlists.filter { it.providerId == provider && !it.isFavorites }
                providerErrors[provider]?.let { BrowseNotice(it) }
                if (!loading && choices.isEmpty() && provider !in providerErrors) Text("暂无可绑定歌单。", color = BrowseMuted, fontSize = 13.sp)
                choices.forEach { playlist ->
                    val checked = selected[provider]?.key == playlist.key
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = selected + (provider to playlist) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = checked, onClick = { selected = selected + (provider to playlist) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(playlist.name, color = BrowseInk)
                            Text("${playlist.trackCount} 首", color = BrowseMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            error?.let { BrowseNotice(it) }
            Button(onClick = {
                val first = selected[MusicProviders.NETEASE] ?: return@Button
                val second = selected[MusicProviders.QQ] ?: return@Button
                busy = true
                error = null
                scope.launch {
                    try { library.bind(first.key, second.key); onBound(); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "绑定失败，请重试" }
                    finally { busy = false }
                }
            }, enabled = !loading && selected.size == 2 && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text(if (busy) "正在绑定…" else "绑定歌单") }
        }
    }
}

private const val BIND_CATALOG_TIMEOUT_MS = 20_000L

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

internal fun favoriteWriteMessage(confirmed: List<String>, pending: List<String>, liked: Boolean): String = listOfNotNull(
    confirmed.takeIf { it.isNotEmpty() }?.joinToString("、", transform = MusicProviders::label)?.let {
        if (liked) "${it}已收藏" else "${it}已取消收藏"
    },
    pending.takeIf { it.isNotEmpty() }?.joinToString("、", transform = MusicProviders::label)?.let { "${it}待处理" },
).joinToString("；")

internal fun favoriteSourceLabels(track: NativeTrack, state: UnifiedLibraryState): List<String> {
    val favorites = state.playlists.filter { it.bindings.any { binding -> binding.isFavorites } }
    val recordingId = PipoGraph.music.library.annotate(track).recordingId
    return favorites.filter { playlist -> playlist.tracks.any { candidate ->
        candidate.sourceKey == track.sourceKey ||
            (!recordingId.isNullOrBlank() && recordingId == candidate.recordingId)
    } }.flatMap { it.bindings }
        .map { MusicProviders.label(it.providerId) }.distinct()
}
