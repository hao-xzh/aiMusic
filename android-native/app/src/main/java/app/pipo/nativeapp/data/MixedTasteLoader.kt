package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class TasteLibrarySlice(
    val playlistKey: String,
    val playlistName: String,
    val providerId: String,
    val tracks: List<NativeTrack>,
)

data class MixedTasteInput(
    val accountKey: String,
    val providerIds: List<String>,
    val slices: List<TasteLibrarySlice>,
    val complete: Boolean,
    val sourceHash: String,
)

/** Reads every page of every active provider playlist for a complete mixed-library distillation. */
class MixedTasteLoader(private val music: MusicCatalog) {
    fun currentAccountKey(): String = music.providers.mapNotNull { provider ->
        provider.connection.value.account?.id?.takeIf { it.isNotBlank() }?.let { account ->
            "${provider.id}:$account"
        }
    }.sorted().joinToString("|")

    fun currentProviderIds(): List<String> = music.providers.mapNotNull { provider ->
        provider.connection.value.account?.id?.takeIf {
            it.isNotBlank() && provider.capabilities.library
        }?.let { provider.id }
    }.sorted()

    suspend fun load(): MixedTasteInput = coroutineScope {
        val accountKey = currentAccountKey()
        require(accountKey.isNotBlank()) { "请先登录至少一个音乐平台" }
        val active = music.providers.filter { provider ->
            provider.connection.value.account?.id?.isNotBlank() == true && provider.capabilities.library
        }.sortedBy { it.id }
        require(active.isNotEmpty()) { "没有可读取资料库的已登录音乐平台" }

        val providerSemaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)
        val reads = active.map { provider ->
            async {
                providerSemaphore.withPermit {
                    readProvider(provider, accountKey)
                }
            }
        }.awaitAll()
        ensureCurrent(accountKey)

        val slices = reads.flatMap { it.slices }
        val providerIds = active.map { it.id }
        return@coroutineScope MixedTasteInput(
            accountKey = accountKey,
            providerIds = providerIds,
            slices = slices,
            complete = reads.all { it.complete },
            sourceHash = sourceHash(accountKey, providerIds, slices),
        )
    }

    private suspend fun readProvider(provider: MusicProvider, accountKey: String): ProviderRead {
        val gate = ProviderRequestGate()
        val catalog = try {
            request(accountKey, gate) { provider.playlistCatalog() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(provider.id, "catalog", error.javaClass.simpleName)
            recordSourceLoaded(provider.id, emptyList(), complete = false)
            return ProviderRead(emptyList(), complete = false)
        }
        if (catalog == null) {
            recordFailure(provider.id, "catalog", "timeout")
            recordSourceLoaded(provider.id, emptyList(), complete = false)
            return ProviderRead(emptyList(), complete = false)
        }

        var complete = catalog.complete
        val slices = mutableListOf<TasteLibrarySlice>()
        for (playlist in catalog.playlists.sortedBy { it.key }) {
            ensureCurrent(accountKey)
            val read = readPlaylist(provider, playlist, accountKey, gate)
            slices += TasteLibrarySlice(
                playlistKey = playlist.key,
                playlistName = playlist.name,
                providerId = provider.id,
                tracks = read.tracks,
            )
            complete = complete && read.complete
        }
        recordSourceLoaded(provider.id, slices, complete)
        return ProviderRead(slices, complete)
    }

    private suspend fun readPlaylist(
        provider: MusicProvider,
        playlist: MusicPlaylist,
        accountKey: String,
        gate: ProviderRequestGate,
    ): PlaylistRead {
        val tracks = mutableListOf<NativeTrack>()
        val visitedOffsets = mutableSetOf<Int>()
        val pageSignatures = mutableSetOf<String>()
        var expectedTotal: Int? = null
        var offset = 0
        var complete = true

        while (true) {
            if (!visitedOffsets.add(offset)) {
                complete = false
                break
            }
            val page = try {
                request(accountKey, gate) { provider.playlistTracks(playlist, PAGE_SIZE, offset) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(provider.id, "page", error.javaClass.simpleName)
                return PlaylistRead(tracks, complete = false)
            }
            if (page == null) {
                recordFailure(provider.id, "page", "timeout")
                return PlaylistRead(tracks, complete = false)
            }

            val pageTracks = page.items.map(music.library::annotate)
            val declaredTotal = page.total
            if (declaredTotal != null) {
                if (expectedTotal != null && expectedTotal != declaredTotal) complete = false
                expectedTotal = expectedTotal ?: declaredTotal
                if (declaredTotal < offset + pageTracks.size) complete = false
            }
            if (pageTracks.isEmpty()) {
                // A confirmed empty playlist is valid; an empty page in a nonempty walk is not.
                if (offset != 0 || page.nextOffset != null || declaredTotal != 0 || playlist.trackCount > 0) {
                    complete = false
                }
                break
            }

            val signature = pageTracks.joinToString("\u0000") { it.sourceKey }
            if (!pageSignatures.add(signature)) {
                complete = false
                break
            }
            tracks += pageTracks

            val nextOffset = page.nextOffset
                ?: declaredTotal?.takeIf { it > offset + pageTracks.size }?.let { offset + pageTracks.size }
                ?: break
            if (nextOffset <= offset) {
                complete = false
                break
            }
            offset = nextOffset
        }

        val requiredTrackCount = maxOf(expectedTotal ?: 0, playlist.trackCount)
        if (tracks.size < requiredTrackCount) complete = false
        return PlaylistRead(tracks, complete)
    }

    private suspend fun <T> request(
        accountKey: String,
        gate: ProviderRequestGate,
        block: suspend () -> T,
    ): T? {
        ensureCurrent(accountKey)
        val waitMillis = (REQUEST_GAP_MS - (System.currentTimeMillis() - gate.lastFinishedAtMillis))
            .coerceAtLeast(0L)
        if (waitMillis > 0L) delay(waitMillis)
        ensureCurrent(accountKey)
        val result = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { block() }
        } finally {
            gate.lastFinishedAtMillis = System.currentTimeMillis()
        }
        ensureCurrent(accountKey)
        return result
    }

    private fun ensureCurrent(expectedAccountKey: String) {
        if (currentAccountKey() != expectedAccountKey) throw CancellationException("音乐账号已切换")
    }

    private fun recordFailure(providerId: String, stage: String, errorType: String) {
        DiagnosticsLogStore.record(
            "mixed_taste_loader",
            "source_failed",
            mapOf("source" to providerId, "stage" to stage, "error_type" to errorType),
        )
    }

    private fun recordSourceLoaded(providerId: String, slices: List<TasteLibrarySlice>, complete: Boolean) {
        DiagnosticsLogStore.record(
            "mixed_taste_loader",
            "source_loaded",
            mapOf(
                "source" to providerId,
                "playlists" to slices.size,
                "source_tracks" to slices.flatMap { it.tracks }.map { it.sourceKey }.distinct().size,
                "complete" to complete,
            ),
        )
    }

    private fun sourceHash(
        accountKey: String,
        providerIds: List<String>,
        slices: List<TasteLibrarySlice>,
    ): String {
        val contents = buildString {
            appendField("account", accountKey)
            providerIds.sorted().forEach { appendField("provider", it) }
            slices.sortedBy { it.playlistKey }.forEach { slice ->
                appendField("platform", slice.providerId)
                appendField("playlistKey", slice.playlistKey)
                appendField("playlistName", slice.playlistName)
                slice.tracks.sortedWith(compareBy<NativeTrack> { it.sourceKey }.thenBy { it.title }
                    .thenBy { it.artist }.thenBy { it.album }.thenBy { it.durationMs }).forEach { track ->
                    appendField("sourceKey", track.sourceKey)
                    appendField("title", track.title)
                    appendField("artist", track.artist)
                    appendField("album", track.album)
                    appendField("duration", track.durationMs.toString())
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(contents.toByteArray(Charsets.UTF_8))
        return "mixed-v3:" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(name).append(':').append(value.length).append(':').append(value).append('\n')
    }

    private data class ProviderRead(val slices: List<TasteLibrarySlice>, val complete: Boolean)

    private data class PlaylistRead(val tracks: List<NativeTrack>, val complete: Boolean)

    private class ProviderRequestGate(var lastFinishedAtMillis: Long = 0L)

    private companion object {
        const val MAX_CONCURRENT_PROVIDERS = 2
        const val PAGE_SIZE = 200
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val REQUEST_GAP_MS = 250L
    }
}
