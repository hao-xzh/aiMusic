package app.pipo.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.FunctionalMusicFilter
import app.pipo.nativeapp.data.HomeRecommendationStore
import app.pipo.nativeapp.data.HomeRecommendationCaption
import app.pipo.nativeapp.data.HomeRecommendationQueueSource
import app.pipo.nativeapp.data.MixedTasteLoader
import app.pipo.nativeapp.data.TrackDedupe
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.RecommendationFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var homeShowingPreviousBatch by mutableStateOf(false)
        private set
    var dismissedTrack by mutableStateOf<NativeTrack?>(null)
        private set
    private var dismissedIndex = 0
    var homeLoading by mutableStateOf(true)
        private set
    var homeAppending by mutableStateOf(false)
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
    private var homeAccountKey = "guest"
    private var homeBatchRevision = 0L
    private var homeBatchTracks = emptyList<NativeTrack>()
    private var homeReady = false
    private var refreshDay = ""
    private var lastHomeAttemptNanos: Long? = null
    private fun homeSnapshot() = HomeRecommendationStore.Snapshot(recommendations, homeMessage, refreshDay, homeCaption)

    init {
        viewModelScope.launch {
            PipoGraph.music.library.state.map { library ->
                library.playlists.flatMap { it.tracks }.map { it.sourceKey }.toSet()
            }.distinctUntilChanged().collectLatest { sources ->
                PipoGraph.library.invalidate()
                if (sources.isNotEmpty()) {
                    val owner = homeAccountKey
                    homeJob?.join()
                    if (owner == homeAccountKey && homeReady && recommendations.isEmpty()) {
                        startHomeRefresh(refreshCollection = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            // A first QQ-only account can acquire its taste sample before any playlist
            // detail is opened. Refill an empty home when that background profile arrives.
            val loader = MixedTasteLoader(PipoGraph.music)
            PipoGraph.tasteProfileStore.flow.map { profile ->
                profile?.let { it.sourceAccountKey to it.derivedAt }
            }.distinctUntilChanged().collectLatest { profile ->
                if (profile == null || profile.first != loader.currentAccountKey()) return@collectLatest
                val owner = homeAccountKey
                homeJob?.join()
                if (owner == homeAccountKey && homeReady && recommendations.isEmpty() &&
                    profile.first == loader.currentAccountKey()) {
                    startHomeRefresh(refreshCollection = false)
                }
            }
        }
        viewModelScope.launch {
            // Resolve the account before issuing recommendations for an initial null account.
            try { PipoGraph.music.refreshAccounts(onlyIfNeeded = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError = e.message ?: "账号刷新失败" }
            combine(PipoGraph.repository.account, PipoGraph.music.qqTransport.connection) { account, qq ->
                account?.userId to qq.account?.id
            }.distinctUntilChanged().collectLatest { (userId, qqId) ->
                homeReady = false
                captionJob?.cancel()
                captionRevision++
                captionTrackIds = emptyList()
                homeJob?.cancelAndJoin()
                homeMutex.withLock {
                    accountId = userId
                    lastHomeAttemptNanos = null
                    homeAccountKey = (userId?.toString() ?: "guest") + (qqId?.let { "-qq-$it" } ?: "")
                    homeBatchRevision++
                    recommendations = emptyList()
                    recommendationReasons = emptyMap()
                    homeMessage = ""
                    homeCaption = ""
                    homeShowingPreviousBatch = false
                    homeLoading = true
                    homeAppending = false
                    PipoGraph.library.invalidate()
                    val cached = PipoGraph.homeRecommendationStore.load(homeAccountKey)
                    recommendations = cached.tracks
                    homeBatchTracks = cached.tracks
                    recommendationReasons = emptyMap()
                    homeMessage = cached.message
                    homeCaption = cached.caption
                    captionTrackIds = if (cached.caption.isNotBlank()) cached.tracks.take(24).map { it.id } else emptyList()
                    refreshDay = cached.refreshDay
                    homeShowingPreviousBatch = cached.tracks.isNotEmpty() && refreshDay != LocalDate.now().toString()
                    dismissedTrack = null
                    homeError = null
                    homeLoading = false
                    homeReady = true
                }
                if (shouldRefreshHome()) refreshHome() else {
                    refreshLibrary()
                    describeCurrentBatch()
                }
            }
        }
    }

    /** Re-entry and foregrounding are cheap; only the first visit per local day refreshes. */
    fun ensureHome() {
        refreshLibrary()
        if (homeReady && shouldRefreshHome()) refreshHome()
    }

    private fun shouldRefreshHome(): Boolean =
        (refreshDay != LocalDate.now().toString() || recommendations.isEmpty()) &&
            lastHomeAttemptNanos?.let { System.nanoTime() - it >= 30_000_000_000L } != false

    fun refreshHome() = startHomeRefresh(refreshCollection = true)

    private fun startHomeRefresh(refreshCollection: Boolean) {
        if (!homeReady || homeJob?.isActive == true) return
        homeLoading = true
        homeShowingPreviousBatch = recommendations.isNotEmpty()
        homeAppending = false
        homeError = null
        val owner = homeAccountKey
        val startedAt = System.nanoTime()
        lastHomeAttemptNanos = startedAt
        val refreshId = startedAt.toString()
        DiagnosticsLogStore.record("recommendation", "home_refresh_started",
            mapOf("refreshId" to refreshId, "previousCount" to recommendations.size))
        homeJob = viewModelScope.launch {
            var outcome = "cancelled"
            try {
                homeMutex.withLock {
                    val lockWaitMs = (System.nanoTime() - startedAt) / 1_000_000L
                    val publishedKeys = HashSet<String>()
                    var firstBatchPublished = false
                    suspend fun applyFeed(feed: RecommendationFeed, complete: Boolean) = withContext(Dispatchers.Main.immediate) {
                        if (owner != homeAccountKey || !homeReady) return@withContext
                        val rejected = PipoGraph.recommendationFeedbackLog.globallyRejected()
                        val fresh = feed.tracks.filter { publishedKeys.add(TrackDedupe.songKey(it)) && !rejected.contains(it) }
                        if (fresh.isNotEmpty()) {
                            val first = !firstBatchPublished
                            if (first) {
                                homeBatchRevision++
                                recommendations = fresh
                                homeShowingPreviousBatch = false
                                recommendationReasons = emptyMap()
                                dismissedTrack = null
                                captionJob?.cancel()
                                captionRevision++
                                captionTrackIds = emptyList()
                                homeCaption = ""
                                firstBatchPublished = true
                            } else {
                                recommendations = recommendations + fresh
                            }
                            recommendationReasons = recommendationReasons + feed.reasons.filterKeys { id -> fresh.any { it.id == id } }
                            homeBatchTracks = feed.tracks
                            refreshDay = LocalDate.now().toString()
                            homeLoading = false
                            homeAppending = !complete
                            DiagnosticsLogStore.record("recommendation", if (first) "home_first_batch_ready" else "home_batch_appended", mapOf(
                                "refreshId" to refreshId, "addedCount" to fresh.size,
                                "trackCount" to recommendations.size,
                                "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L))
                        }
                        if (complete) homeMessage = feed.message
                        else if (firstBatchPublished) homeMessage = "结合你的口味，重温熟悉的喜欢，也听见新的发现。"
                        // Persist the first playable batch too, even if later recall is cancelled.
                        if (fresh.isNotEmpty() || complete) PipoGraph.homeRecommendationStore.save(owner, homeSnapshot())
                    }
                    // Keep cached music immediately usable while collection data refreshes.
                    // Ready-data notifications retry an empty feed without starting more I/O.
                    if (refreshCollection) refreshLibrary()
                    DiagnosticsLogStore.record("recommendation", "home_library_refresh_requested", mapOf(
                        "refreshId" to refreshId, "lockWaitMs" to lockWaitMs,
                        "refreshCollection" to refreshCollection,
                        "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L))
                    val feed = PipoGraph.recommendEngine.homeFeed(
                        excludeIds = recommendations.mapNotNull { it.neteaseId }.toSet(), wantCount = 24,
                        excludeSongKeys = recommendations.mapTo(HashSet()) { TrackDedupe.songKey(it) },
                        onProgress = { applyFeed(it, complete = false) },
                        requestId = refreshId,
                    )
                    applyFeed(feed, complete = true)
                    describeCurrentBatch()
                    DiagnosticsLogStore.record("recommendation", "home_feed_ready", mapOf(
                        "refreshId" to refreshId, "trackCount" to feed.tracks.size,
                        "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L))
                    outcome = if (feed.tracks.isNotEmpty()) "success" else "empty"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                outcome = "failed"
                homeError = e.message ?: "推荐暂时无法加载"
            }
            finally {
                homeLoading = false
                homeAppending = false
                DiagnosticsLogStore.record("recommendation", "home_refresh_finished", mapOf(
                    "refreshId" to refreshId, "outcome" to outcome,
                    "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L))
            }
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
        val owner = homeAccountKey
        val reasons = recommendationReasons.toMap()
        captionJob = viewModelScope.launch {
            val caption = captionWriter.write(tracks, reasons) ?: return@launch
            homeMutex.withLock {
                if (!homeReady || owner != homeAccountKey || revision != captionRevision) return@withLock
                homeCaption = caption
                try { PipoGraph.homeRecommendationStore.save(owner, homeSnapshot()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the generated caption visible if only persistence failed. */ }
            }
        }
    }

    /** Reuses the player's near-end, in-flight, backoff and final queue dedupe safeguards. */
    fun homeContinuousSource(): ContinuousQueueSource {
        val owner = homeAccountKey
        val batch = homeBatchRevision
        val initialSongKeys = recommendations.mapTo(HashSet()) { TrackDedupe.songKey(it) }
        val continuation = HomeRecommendationQueueSource(owner, recommendations)
        return object : ContinuousQueueSource {
            override fun snapshot() = continuation.snapshot()
            override fun permitsDefaultFallback() = false
            override fun acceptsResolved(track: NativeTrack) = owner == homeAccountKey && homeReady &&
                continuation.acceptsResolved(track)
            override fun pendingTracks(): List<NativeTrack> {
                if (owner != homeAccountKey || !homeReady || batch != homeBatchRevision) return emptyList()
                val rejected = PipoGraph.recommendationFeedbackLog.globallyRejected()
                return homeBatchTracks.filter { TrackDedupe.songKey(it) !in initialSongKeys && !rejected.contains(it) }
            }
            override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> {
                // The refresh owns recall already. Do not queue a second network pass
                // behind it while its next ready batch can arrive through pendingTracks.
                if (homeJob?.isActive == true) return emptyList()
                return fetchFresh(excludeIds)
            }
            private suspend fun fetchFresh(excludeIds: Set<Long>): List<NativeTrack> =
                withContext(Dispatchers.Main.immediate) {
                    homeMutex.withLock {
                        if (owner != homeAccountKey || !homeReady) return@withLock emptyList()
                        // Refreshing home does not interrupt an already playing batch. Its
                        // source keeps its own anchor/history and cannot write into the new home.
                        val currentBatch = if (batch == homeBatchRevision) recommendations else emptyList()
                        continuation.rememberTracks(currentBatch)
                        val exclusions = excludeIds + currentBatch.mapNotNull { it.neteaseId }
                        val more = continuation.fetchMore(exclusions)
                        if (owner != homeAccountKey || !homeReady) return@withLock emptyList()
                        // Empty results use the player's bounded backoff, never a frame-by-frame retry.
                        check(more.isNotEmpty()) { "暂时没有合适的新歌，稍后继续补充" }
                        if (batch == homeBatchRevision) {
                            recommendations = (recommendations + more).takeLast(200)
                            describeCurrentBatch()
                            PipoGraph.homeRecommendationStore.save(owner, homeSnapshot())
                        }
                        more
                    }
                }
        }
    }

    fun dismissRecommendation(track: NativeTrack) {
        val index = recommendations.indexOfFirst { it.id == track.id }
        if (index < 0) return
        PipoGraph.recommendationFeedbackLog.reject(track, "home_not_interested")
        dismissedTrack = track
        dismissedIndex = index
        recommendations = recommendations.filterNot { it.id == track.id }
        describeCurrentBatch()
        persistHome()
    }

    fun undoDismissal() {
        dismissedTrack?.let { track ->
            PipoGraph.recommendationFeedbackLog.restoreHomeDismissal(track)
            if (recommendations.none { it.id == track.id }) {
                recommendations = recommendations.toMutableList().apply {
                    add(dismissedIndex.coerceIn(0, size), track)
                }
            }
        }
        dismissedTrack = null
        describeCurrentBatch()
        persistHome()
    }

    private fun persistHome() {
        val owner = homeAccountKey
        viewModelScope.launch {
            homeMutex.withLock {
                if (owner == homeAccountKey) {
                    try { PipoGraph.homeRecommendationStore.save(owner, homeSnapshot()) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { homeError = "推荐缓存暂时无法保存" }
                }
            }
        }
    }

    fun refreshLibrary(showRefresh: Boolean = false) {
        if (libraryJob?.isActive == true) {
            if (showRefresh) libraryLoading = true
            return
        }
        libraryJob = viewModelScope.launch {
            libraryLoading = showRefresh
            libraryError = null
            try {
                PipoGraph.music.library.refresh()
                PipoGraph.library.invalidate()
                PipoGraph.distillCoordinator.ensureMixed()
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
            try {
                PipoGraph.music.search(keyword, limit = 50).collect { snapshot ->
                    if (query.trim() == keyword) {
                        results = snapshot.tracks
                        searchLoading = snapshot.loading.isNotEmpty()
                        searchError = snapshot.errors.takeIf { it.isNotEmpty() }?.entries?.joinToString("；") {
                            "${app.pipo.nativeapp.data.MusicProviders.label(it.key)}：${it.value}"
                        }
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { searchError = "暂时无法搜索，请检查网络后重试" }
            finally { searchLoading = false }
        }
    }
}
