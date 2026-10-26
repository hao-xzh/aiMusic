package app.pipo.nativeapp.data

import app.pipo.nativeapp.data.agent.domain.MusicGoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 首页续播只保留账号、已见歌曲与锚点，不依赖页面生命周期。 */
class HomeRecommendationQueueSource(
    private val accountKey: String,
    initialTracks: List<NativeTrack>,
    anchor: NativeTrack? = initialTracks.lastOrNull(),
) : ContinuousQueueSource {
    private val fetchMutex = Mutex()
    private val stateLock = Any()
    private val seenSongKeys = initialTracks.mapTo(HashSet()) { TrackDedupe.songKey(it) }
    private var lastAnchor = anchor
    private var lastAiFailureAtNs: Long? = null

    fun rememberTracks(tracks: List<NativeTrack>) {
        synchronized(stateLock) {
            seenSongKeys.addAll(tracks.map { TrackDedupe.songKey(it) })
            tracks.lastOrNull()?.let { lastAnchor = it }
        }
    }

    override fun permitsDefaultFallback(): Boolean = false

    override fun acceptsResolved(track: NativeTrack): Boolean = ownsCurrentAccount() &&
        !FunctionalMusicFilter.isReligious(track) &&
        !PipoGraph.recommendationFeedbackLog.globallyRejected().contains(track)

    override fun snapshot(): AiQueueContinuationSnapshot = AiQueueContinuationSnapshot(
        goal = MusicGoal(),
        userText = "manual_home_recommendations",
        plannerRaw = "",
        homeAccountKey = accountKey,
    )

    override suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack> = fetchMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!ownsCurrentAccount()) return@withLock emptyList()
        val exclusions = excludeIds.toSet()
        val local = try {
            recall(exclusions, useAi = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        if (!ownsCurrentAccount()) return@withLock emptyList()
        val fresh = rememberFresh(local, exclusions)
        if (fresh.isNotEmpty()) return@withLock fresh.takeIf { ownsCurrentAccount() }.orEmpty()
        val mayUseAi = synchronized(stateLock) {
            lastAiFailureAtNs?.let { System.nanoTime() - it >= AI_FAILURE_BACKOFF_NS } ?: true
        }
        if (!mayUseAi || !ownsCurrentAccount()) return@withLock emptyList()
        val discovered = try {
            recall(exclusions, useAi = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        if (!ownsCurrentAccount()) return@withLock emptyList()
        val more = rememberFresh(discovered, exclusions)
        synchronized(stateLock) {
            lastAiFailureAtNs = if (more.isEmpty()) System.nanoTime() else null
        }
        more.takeIf { ownsCurrentAccount() }.orEmpty()
    }

    private suspend fun recall(excludeIds: Set<Long>, useAi: Boolean): List<NativeTrack> {
        // Copy mutable state before recall so rememberTracks never waits for I/O.
        val (anchor, songKeys) = synchronized(stateLock) { lastAnchor to seenSongKeys.toSet() }
        return PipoGraph.recommendEngine.continueHome(
            excludeIds = excludeIds, anchor = anchor, wantCount = 12,
            useAi = useAi, excludeSongKeys = songKeys,
        )
    }

    private fun rememberFresh(tracks: List<NativeTrack>, excludeIds: Set<Long>): List<NativeTrack> {
        val accepted = tracks.filter { (it.neteaseId == null || it.neteaseId !in excludeIds) && acceptsResolved(it) }
        return synchronized(stateLock) {
            if (!ownsCurrentAccount()) emptyList()
            else accepted.filter { seenSongKeys.add(TrackDedupe.songKey(it)) }.also { more ->
                more.lastOrNull()?.let { lastAnchor = it }
            }
        }
    }

    private fun ownsCurrentAccount(): Boolean = accountKey ==
        (PipoGraph.repository.account.value?.userId?.toString() ?: "guest") +
        (PipoGraph.music.qqTransport.connection.value.account?.id?.let { "-qq-$it" } ?: "")

    companion object {
        private const val AI_FAILURE_BACKOFF_NS = 120_000_000_000L
    }
}
