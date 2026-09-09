package app.pipo.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.FunctionalMusicFilter
import app.pipo.nativeapp.data.HomeRecommendationStore
import app.pipo.nativeapp.data.HomeRecommendationCaption
import app.pipo.nativeapp.data.TrackDedupe
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Screen snapshots outlive tab/player navigation and never commit a playback queue. */
internal class BrowseViewModel : ViewModel() {
    var accountId by mutableStateOf<Long?>(null)
        private set
    var recommendations by mutableStateOf<List<NativeTrack>>(emptyList())
        private set
    var recommendationReasons by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var homeMessage by mutableStateOf("")
        private set
    var homeCaption by mutableStateOf("")
        private set
    var dismissedTrack by mutableStateOf<NativeTrack?>(null)
        private set
    var homeLoading by mutableStateOf(true)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<NativeTrack>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var submitted by mutableStateOf(false)
        private set
    var libraryError by mutableStateOf<String?>(null)
        private set
    var libraryLoading by mutableStateOf(false)
        private set
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var captionJob: Job? = null
    private var captionRevision = 0L
    private var captionTrackIds = emptyList<String>()
    private val captionWriter = HomeRecommendationCaption(PipoGraph.repository)
    private var libraryJob: Job? = null

    private val homeMutex = Mutex()
    private var homeReady = false
    private var refreshDay = ""
    private fun homeSnapshot() = HomeRecommendationStore.Snapshot(recommendations, homeMessage, refreshDay, homeCaption)

    init {
        viewModelScope.launch {
            // Resolve the account before issuing recommendations for an initial null account.
            try { PipoGraph.repository.refreshAccount() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError = e.message ?: "账号刷新失败" }
            PipoGraph.repository.account.map { it?.userId }.distinctUntilChanged().collectLatest { userId ->
                homeReady = false
                captionJob?.cancel()
                captionRevision++
                captionTrackIds = emptyList()
                homeJob?.cancelAndJoin()
                homeMutex.withLock {
                    accountId = userId
                    recommendations = emptyList()
                    recommendationReasons = emptyMap()
                    homeMessage = ""
                    homeCaption = ""
                    homeLoading = true
                    PipoGraph.library.invalidate()
                    val cached = PipoGraph.homeRecommendationStore.load(userId)
                    recommendations = cached.tracks
                    recommendationReasons = emptyMap()
                    homeMessage = cached.message
                    homeCaption = cached.caption
                    captionTrackIds = if (cached.caption.isNotBlank()) cached.tracks.take(24).map { it.id } else emptyList()
                    refreshDay = cached.refreshDay
                    dismissedTrack = null
                    homeError = null
                    homeLoading = false
                    homeReady = true
                }
                if (refreshDay != LocalDate.now().toString()) refreshHome() else {
                    refreshLibrary()
                    describeCurrentBatch()
                }
            }
        }
    }

    /** Re-entry and foregrounding are cheap; only the first visit per local day refreshes. */
    fun ensureHome() {
        if (homeReady && refreshDay != LocalDate.now().toString()) refreshHome()
    }

    fun refreshHome() {
        if (!homeReady || homeJob?.isActive == true) return
        homeLoading = true
        homeError = null
        val owner = accountId
        homeJob = viewModelScope.launch {
            try {
                homeMutex.withLock {
                    // Persist the attempt before the network call, including failed/empty attempts.
                    refreshDay = LocalDate.now().toString()
                    PipoGraph.homeRecommendationStore.save(owner, homeSnapshot())
                    // Refresh collection membership before allocating saved/new slots.
                    PipoGraph.repository.refreshPlaylistsForBrowse()
                    PipoGraph.library.invalidate()
                    val feed = PipoGraph.recommendEngine.homeFeed(
                        excludeIds = recommendations.mapNotNull { it.neteaseId }.toSet(), wantCount = 24,
                        excludeSongKeys = recommendations.mapTo(HashSet()) { TrackDedupe.songKey(it) },
                    )
                    if (feed.tracks.isNotEmpty()) {
                        recommendations = feed.tracks
                        recommendationReasons = feed.reasons
                        describeCurrentBatch()
                    }
                    homeMessage = feed.message
                    PipoGraph.homeRecommendationStore.save(owner, homeSnapshot())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { homeError = e.message ?: "推荐暂时无法加载" }
            finally { homeLoading = false }
        }
    }

    /** Caption work never holds up playable recommendations or the next refresh. */
    private fun describeCurrentBatch() {
        val tracks = recommendations.take(24)
        val ids = tracks.map { it.id }
        if (ids == captionTrackIds) return
        captionTrackIds = ids
        val revision = ++captionRevision
        captionJob?.cancel()
        homeCaption = ""
        if (tracks.isEmpty()) return
        val owner = accountId
        val reasons = recommendationReasons.toMap()
        captionJob = viewModelScope.launch {
            val caption = captionWriter.write(tracks, reasons) ?: return@launch
            homeMutex.withLock {
                if (!homeReady || owner != accountId || revision != captionRevision) return@withLock
                homeCaption = caption
                try { PipoGraph.homeRecommendationStore.save(owner, homeSnapshot()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the generated caption visible if only persistence failed. */ }
            }
        }
    }

    /** Reuses the player's near-end, in-flight, backoff and final queue dedupe safeguards. */
    fun homeContinuousSource(): ContinuousQueueSource {
        val owner = accountId
        val seenSongKeys = recommendations.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        var discoveryAttempt: Set<Long>? = null
        return object : ContinuousQueueSource {
            override fun permitsDefaultFallback() = false
            override fun acceptsResolved(track: NativeTrack) = owner == accountId && homeReady &&
                !FunctionalMusicFilter.isReligious(track)
            override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> = homeMutex.withLock {
                if (owner != accountId || !homeReady) return@withLock emptyList()
                seenSongKeys.addAll(recommendations.map { TrackDedupe.songKey(it) })
                val exclusions = excludeIds + recommendations.mapNotNull { it.neteaseId }
                val candidates = PipoGraph.recommendEngine.homeFeed(
                    anchor = recommendations.lastOrNull(),
                    excludeIds = exclusions,
                    wantCount = 24, useAi = false,
                    excludeSongKeys = seenSongKeys,
                ).tracks.filter { TrackDedupe.songKey(it) !in seenSongKeys }
                if (owner != accountId || !homeReady) return@withLock emptyList()
                // Only discover with AI once for an unchanged exhausted queue, never on every retry.
                val fresh = if (candidates.isEmpty() && discoveryAttempt != exclusions) {
                    discoveryAttempt = exclusions
                    PipoGraph.recommendEngine.homeFeed(exclusions, wantCount = 24, excludeSongKeys = seenSongKeys).tracks
                } else candidates
                if (owner != accountId || !homeReady) return@withLock emptyList()
                val more = fresh.filter { seenSongKeys.add(TrackDedupe.songKey(it)) }
                // Empty results use the player's bounded backoff, never a frame-by-frame retry.
                check(more.isNotEmpty()) { "暂时没有合适的新歌，稍后继续补充" }
                recommendations = (recommendations + more).takeLast(200)
                describeCurrentBatch()
                PipoGraph.homeRecommendationStore.save(owner, homeSnapshot())
                more
            }
        }
    }

    fun dismissRecommendation(track: NativeTrack) {
        PipoGraph.recommendationFeedbackLog.reject(track, "home_not_interested")
        dismissedTrack = track
        recommendations = recommendations.filterNot { it.id == track.id }
        describeCurrentBatch()
        persistHome()
    }

    fun undoDismissal() {
        dismissedTrack?.let { track ->
            PipoGraph.recommendationFeedbackLog.restoreHomeDismissal(track)
            recommendations = (recommendations + track).distinctBy { it.id }
        }
        dismissedTrack = null
        describeCurrentBatch()
        persistHome()
    }

    private fun persistHome() {
        val owner = accountId
        viewModelScope.launch {
            homeMutex.withLock {
                if (owner == accountId) {
                    try { PipoGraph.homeRecommendationStore.save(owner, homeSnapshot()) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { homeError = "推荐缓存暂时无法保存" }
                }
            }
        }
    }

    fun refreshLibrary() {
        if (libraryJob?.isActive == true) return
        libraryJob = viewModelScope.launch {
            libraryLoading = true
            libraryError = null
            try {
                PipoGraph.repository.refreshPlaylistsForBrowse()
                PipoGraph.library.invalidate()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError = e.message ?: "资料库刷新失败" }
            finally { libraryLoading = false }
        }
    }

    fun editQuery(value: String) {
        searchJob?.cancel()
        query = value
        results = emptyList()
        submitted = false
        searchLoading = false
        searchError = null
    }

    fun search() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            submitted = true
            searchLoading = true
            searchError = null
            try { results = PipoGraph.repository.searchTracks(keyword, limit = 100) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchError = e.message ?: "搜索失败，请重试" }
            finally { searchLoading = false }
        }
    }
}
