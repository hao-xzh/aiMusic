package app.pipo.nativeapp.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.playback.PlayerViewModel
import app.pipo.nativeapp.playback.orchestrator.*
import kotlinx.coroutines.launch

internal class BrowseSession(context: android.content.Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pipo-native-browse", android.content.Context.MODE_PRIVATE)
    private var libraryGridState by mutableStateOf(preferences.getBoolean("library-grid", false))
    var accountId: Long? = null
    val homeScroll = LazyListState()
    var librarySearchScroll = LazyListState()
    var searchOpen by mutableStateOf(false)
    var searchFocusPending by mutableStateOf(false)
    val libraryScroll = LazyListState()
    var detailScroll = LazyListState()
    var detailQuery by mutableStateOf("")
    var libraryFilter by mutableStateOf("全部")
    var libraryGrid: Boolean
        get() = libraryGridState
        @android.annotation.SuppressLint("ApplySharedPref")
        set(value) {
            if (value == libraryGridState) return
            // Match local settings: persist before publishing, including an immediate app exit.
            preferences.edit().putBoolean("library-grid", value).commit()
            libraryGridState = value
        }
    var librarySort by mutableStateOf("最近更新")
    var tab by mutableStateOf(BrowseTab.Home)
    var playlist by mutableStateOf<PipoPlaylist?>(null)
    var cloud by mutableStateOf(false)
    var detailRevision by mutableStateOf(0)
    fun select(value: BrowseTab) { tab = value; if (value != BrowseTab.Library) closeSearch(); closeDetail() }
    fun openSearch() { select(BrowseTab.Library); librarySearchScroll = LazyListState(); searchOpen = true; searchFocusPending = true }
    fun closeSearch() { searchOpen = false; searchFocusPending = false }
    fun closeDetail() { playlist = null; cloud = false; detailQuery = ""; detailScroll = LazyListState() }
}

@Composable
internal fun BrowseHost(session: BrowseSession, model: BrowseViewModel, player: PlayerViewModel, onAccount: () -> Unit, onLogin: () -> Unit, onPlayer: () -> Unit, onAi: () -> Unit, onCreateVisibility: (Boolean) -> Unit) {
    val repository = PipoGraph.repository
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) model.ensureHome()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session.tab) {
        if (session.tab == BrowseTab.Home) model.ensureHome()
    }
    LaunchedEffect(model.accountId) {
        if (session.accountId != model.accountId) {
            session.closeDetail()
            session.accountId = model.accountId
        }
    }
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val account by repository.account.collectAsState(initial = null)
    val cloudTracks by repository.cloudTracks.collectAsState(initial = emptyList())
    val detailPlaylist = playlists.firstOrNull { it.id == session.playlist?.id } ?: session.playlist
    var create by remember { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<NativeTrack?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val play: (List<NativeTrack>) -> Unit = { tracks ->
        if (tracks.isNotEmpty()) {
            val result = player.playBrowseQueue(
                tracks,
                source = if (session.tab == BrowseTab.Home && session.playlist == null && !session.cloud) model.homeContinuousSource() else null,
            )
            if (result is QueueCommitResult.Success) onPlayer()
            else scope.launch { snackbar.showSnackbar("暂时无法播放，请重试") }
        }
    }
    LaunchedEffect(create) { onCreateVisibility(create) }
    DisposableEffect(Unit) { onDispose { onCreateVisibility(false) } }
    Box(Modifier.fillMaxSize()) {
        if (session.playlist != null || session.cloud) {
            PlaylistBrowseDetail(session, detailPlaylist, session.cloud, session.detailRevision, player.state.currentTrackId, session::closeDetail, play, { actionTrack = it })
        } else when (session.tab) {
            BrowseTab.Home -> HomeBrowseScreen(session, model, playlists, account != null, onAccount, session::openSearch, { session.select(BrowseTab.Library) }, { session.playlist = it; session.tab = BrowseTab.Library }, { session.cloud = true; session.tab = BrowseTab.Library }, onLogin, play, { actionTrack = it })
            BrowseTab.Library -> LibraryBrowseScreen(session, model, playlists, account != null, cloudTracks.size, onAccount, session::openSearch, { session.playlist = it }, { session.cloud = true }, { create = true }, onLogin, play, { actionTrack = it }, onAi)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (create) CreatePlaylistSheet(onDismiss = { create = false }, onCreated = { playlist ->
        create = false
        session.playlist = playlist.copy(userId = account?.userId)
        session.tab = BrowseTab.Library
        model.refreshLibrary()
    })
    actionTrack?.let { track ->
        TrackActionsSheet(track, detailPlaylist, player, { actionTrack = null }, { session.detailRevision++; model.refreshLibrary() },
            onNotInterested = if (session.tab == BrowseTab.Home && session.playlist == null && !session.cloud) {
                { model.dismissRecommendation(track) }
            } else null,
        )
    }
}
