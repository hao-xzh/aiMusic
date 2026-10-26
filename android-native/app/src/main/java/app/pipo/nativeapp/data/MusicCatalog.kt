package app.pipo.nativeapp.data

import android.content.Context
import app.pipo.nativeapp.data.library.UnifiedMusicLibrary
import app.pipo.nativeapp.data.qqnative.QqNativeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class MusicSearchSnapshot(
    val tracks: List<NativeTrack> = emptyList(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val nextOffset: Int? = null,
)

/** Android provider registry. A second catalog is queried only when the preferred one has no acceptable version. */
class MusicCatalog(context: Context, private val repository: PipoRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val qqTransport = QqNativeTransport(context.applicationContext)
    val providers: List<MusicProvider> = listOf(NetEaseMusicProvider(repository, scope), QqMusicProvider(qqTransport))
    val library = UnifiedMusicLibrary(context.applicationContext, providers, scope)
    private val accountRefreshMutex = Mutex()
    private val _accountsReady = MutableStateFlow(false)
    val accountsReady = _accountsReady.asStateFlow()
    private val searchCache = ConcurrentHashMap<String, Pair<Long, MusicPage<NativeTrack>>>()
    private val inFlightSearchMutex = Mutex()
    private val inFlightSearches = mutableMapOf<String, InFlightSearch>()
    private val cloudLibrary = NetEaseCloudLibrary(repository)

    private class InFlightSearch(
        val request: Deferred<MusicPage<NativeTrack>>,
        var waiters: Int,
    )

    private data class AcquiredSearch(
        val cached: MusicPage<NativeTrack>? = null,
        val inFlight: InFlightSearch? = null,
    )

    fun provider(id: String): MusicProvider = providers.firstOrNull { it.id == id }
        ?: throw MusicProviderException(id, MusicProviderError.UNSUPPORTED, "未接入此音乐平台")

    /** A runtime preference only: never overwrite the user's saved default. */
    suspend fun preferredProviderId(): String {
        val saved = repository.settings.first().preferredSearchProviderId
        return subscriptionPreferredProviderId() ?: saved
    }

    fun subscriptionPreferredProviderId(): String? {
        val available = providers.filter { it.capabilities.search && it.capabilities.playback }
            .map { it.id to it.connection.value }.filter { it.second.configured }
        val subscribed = available.singleOrNull { it.second.account?.hasMusicSubscription == true }
            ?: return null
        // An unverified signed-in account may also be subscribed. Do not treat it as a non-member.
        if (available.any { (id, connection) ->
                id != subscribed.first && connection.account != null &&
                    connection.account.hasMusicSubscription == null
            }) return null
        return subscribed.first
    }

    fun search(
        query: String,
        limit: Int = 50,
        offset: Int = 0,
        acceptCandidate: (NativeTrack) -> Boolean = { true },
    ): Flow<MusicSearchSnapshot> = flow {
        val preferred = preferredProviderId()
        val active = providers.filter { it.capabilities.search && it.connection.value.configured }
            .sortedBy { if (it.id == preferred) 0 else 1 }
        val errors = linkedMapOf<String, String>()
        var nextOffset: Int? = null
        for (source in active) {
            emit(MusicSearchSnapshot(loading = setOf(source.id)))
            val page = try {
                searchPage(source, query, limit, offset)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                errors[source.id] = "请求超时，请稍后重试"
                MusicPage<NativeTrack>(emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errors[source.id] = "暂时无法搜索，请稍后重试"
                MusicPage<NativeTrack>(emptyList())
            }
            // Eligibility filtering must not make a full source page look exhausted.
            // Even an entirely excluded page can have fresh songs on its next page.
            if (nextOffset == null) nextOffset = page.nextOffset
            val accepted = page.items.filter { SearchVersionPolicy.accepts(it, query) && acceptCandidate(it) }
                .map(library::annotate)
            if (accepted.isNotEmpty()) {
                emit(MusicSearchSnapshot(tracks = RecordingMatcher.displayTracks(accepted), nextOffset = page.nextOffset))
                return@flow
            }
        }
        if (repository.account.value != null) {
            emit(MusicSearchSnapshot(loading = setOf(MusicProviders.NETEASE)))
            val cloudTracks = try {
                cloudLibrary.search(query)
                    .filter { SearchVersionPolicy.accepts(it, query) && acceptCandidate(it) }
                    .map(library::annotate)
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                errors[MusicProviders.NETEASE] = "云盘搜索超时，请稍后重试"
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errors[MusicProviders.NETEASE] = "暂时无法搜索云盘，请稍后重试"
                emptyList()
            }
            val cloudPage = RecordingMatcher.displayTracks(cloudTracks).drop(offset).take(limit)
            if (cloudPage.isNotEmpty()) {
                emit(MusicSearchSnapshot(
                    tracks = cloudPage,
                    nextOffset = nextOffset ?: (offset + cloudPage.size).takeIf { it < cloudTracks.size },
                ))
                return@flow
            }
        }
        emit(MusicSearchSnapshot(errors = errors, nextOffset = nextOffset))
    }

    /**
     * A same-provider search is safe to share only when the existing cache identity
     * matches exactly. The original query still goes to the provider unchanged.
     */
    private suspend fun searchPage(
        source: MusicProvider,
        query: String,
        limit: Int,
        offset: Int,
    ): MusicPage<NativeTrack> {
        val accountSignature = source.connection.value.account?.id.orEmpty()
        val key = "${source.id}:$accountSignature:$limit:$offset:$query"
        cachedSearch(key)?.let { return it }

        val acquired = inFlightSearchMutex.withLock {
            cachedSearch(key)?.let { return@withLock AcquiredSearch(cached = it) }
            inFlightSearches[key]?.let {
                it.waiters += 1
                return@withLock AcquiredSearch(inFlight = it)
            }
            val request = scope.async(start = CoroutineStart.LAZY) {
                val page = withTimeout(4_000L) { source.search(query, limit, offset) }
                // A request begun for a previous account must neither complete a new
                // account's waiters nor populate its cache entry.
                check(source.connection.value.account?.id.orEmpty() == accountSignature) {
                    "search_account_changed"
                }
                if (searchCache.size > 80) searchCache.clear()
                searchCache[key] = System.currentTimeMillis() to page
                page
            }
            InFlightSearch(request, waiters = 1).also {
                inFlightSearches[key] = it
                request.start()
            }.let { AcquiredSearch(inFlight = it) }
        }
        acquired.cached?.let { return it }
        val inFlight = requireNotNull(acquired.inFlight)
        var discardInFlight = false
        return try {
            inFlight.request.await().also {
                // The shared request can finish while this waiter is suspended. Do
                // not let an account switch in that gap surface old-account results.
                check(source.connection.value.account?.id.orEmpty() == accountSignature) {
                    "search_account_changed"
                }
            }
        } catch (error: CancellationException) {
            // A provider timeout cancels the shared request without cancelling this
            // caller. Drop that completed failure so a subsequent request can retry.
            discardInFlight = currentCoroutineContext().isActive
            throw error
        } catch (error: Exception) {
            discardInFlight = true
            throw error
        } finally {
            // Releasing a bounded in-memory reference must survive collector
            // cancellation; it never waits for the provider request to finish.
            withContext(NonCancellable) {
                releaseSearch(key, inFlight, discardInFlight)
            }
        }
    }

    private fun cachedSearch(key: String): MusicPage<NativeTrack>? =
        searchCache[key]?.takeIf { System.currentTimeMillis() - it.first < 180_000L }?.second

    private suspend fun releaseSearch(
        key: String,
        inFlight: InFlightSearch,
        discardInFlight: Boolean,
    ) {
        val cancelRequest = inFlightSearchMutex.withLock {
            if (inFlightSearches[key] !== inFlight) return@withLock false
            inFlight.waiters -= 1
            if (!discardInFlight && inFlight.waiters > 0) return@withLock false
            inFlightSearches.remove(key)
            !inFlight.request.isCompleted
        }
        if (cancelRequest) inFlight.request.cancel()
    }

    /** Each source gets a bounded budget, so a preferred-source timeout cannot cancel the fallback. */
    suspend fun searchCandidates(
        query: String,
        limit: Int,
        offset: Int = 0,
        acceptCandidate: (NativeTrack) -> Boolean = { true },
    ): List<NativeTrack> = searchCandidatesPage(query, limit, offset, acceptCandidate).items

    suspend fun searchCandidatesPage(
        query: String,
        limit: Int,
        offset: Int = 0,
        acceptCandidate: (NativeTrack) -> Boolean = { true },
    ): MusicPage<NativeTrack> {
        val snapshot = search(query, limit, offset, acceptCandidate).first { it.loading.isEmpty() }
        if (snapshot.tracks.isEmpty() && snapshot.errors.isNotEmpty())
            throw IllegalStateException(snapshot.errors.entries.joinToString("；") { "${MusicProviders.label(it.key)}：${it.value}" })
        return MusicPage(snapshot.tracks.take(limit), nextOffset = snapshot.nextOffset)
    }

    suspend fun resolveAudio(track: NativeTrack, quality: String): MusicAudioSource? {
        val source = track.musicSource ?: return null
        return provider(source.providerId).resolveAudio(source, quality)
    }

    suspend fun cloudPlaybackCandidates(track: NativeTrack): List<NativeTrack> =
        cloudLibrary.playbackCandidates(track)

    suspend fun refreshAccounts(onlyIfNeeded: Boolean = false) = accountRefreshMutex.withLock {
        if (onlyIfNeeded && _accountsReady.value) return@withLock
        val restored = supervisorScope {
            providers.map { source -> async {
                try { source.refreshAccount(); true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            } }.awaitAll().all { it }
        }
        // Initial null connections are not evidence of logout/account switching.
        if (restored) _accountsReady.value = true
    }
}
