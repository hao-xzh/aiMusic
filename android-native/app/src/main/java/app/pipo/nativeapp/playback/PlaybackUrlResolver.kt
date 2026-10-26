package app.pipo.nativeapp.playback

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.MusicCatalog
import app.pipo.nativeapp.data.MusicProviders
import app.pipo.nativeapp.data.MusicSourceRef
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaybackUrlResolver(
    private val repository: PipoRepository,
    private val music: MusicCatalog,
    private val streamLevelFallbacks: List<String>,
    private val streamUrlTimeoutMs: Long,
) {
    data class ResolvedPlaybackUrl(
        val url: String,
        val cacheKey: String,
        val source: MusicSourceRef? = null,
        val isTrial: Boolean = false,
    ) {
        fun applyTo(track: NativeTrack): NativeTrack {
            val knownSource = source == null || source.key == track.musicSource?.key ||
                track.alternateSources.any { it.key == source.key }
            val alternates = when {
                source == null -> track.alternateSources
                !knownSource -> emptyList()
                else -> (track.alternateSources + listOfNotNull(track.musicSource))
                    .filter { it.key != source.key }.distinctBy(MusicSourceRef::key)
            }
            return track.copy(
                neteaseId = if (source != null) source.neteaseId else track.neteaseId,
                sourceRef = source ?: track.sourceRef,
                recordingId = track.recordingId.takeIf { knownSource },
                alternateSources = alternates,
                streamUrl = url,
                streamCacheKey = cacheKey,
            )
        }
    }

    suspend fun fetchPlayable(id: Long): ResolvedPlaybackUrl? {
        var attemptedLevels = 0
        var trialFallback: ResolvedPlaybackUrl? = null
        for (level in streamLevelFallbacks) {
            attemptedLevels += 1
            val songUrl = withTimeoutOrNull(streamUrlTimeoutMs) {
                try {
                    repository.songUrls(listOf(id), level).firstOrNull { it.id == id }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            val url = songUrl?.url?.takeIf { it.isNotBlank() }
            if (!url.isNullOrBlank()) {
                val resolved = ResolvedPlaybackUrl(
                    url = url,
                    cacheKey = PlaybackCacheKeys.resolvedNetease(
                        id = id,
                        level = level,
                        bitrate = songUrl.bitrate,
                        sizeBytes = songUrl.sizeBytes,
                        isTrial = songUrl.isTrial,
                    ),
                    source = MusicSourceRef(MusicProviders.NETEASE, id.toString()),
                    isTrial = songUrl.isTrial,
                )
                if (!songUrl.isTrial) return resolved
                if (trialFallback == null) trialFallback = resolved
            }
        }
        if (trialFallback != null) return trialFallback
        DiagnosticsLogStore.record(
            area = "playback",
            event = "playable_url_empty",
            fields = mapOf(
                "neteaseId" to id,
                "attemptedLevels" to attemptedLevels,
                "timeoutMs" to streamUrlTimeoutMs,
            ),
        )
        return null
    }

    suspend fun fetchPlayableUrl(id: Long): String? = fetchPlayable(id)?.url

    /** Prefer the sole subscribed provider among explicitly confirmed versions of this recording. */
    suspend fun fetchPlayable(track: NativeTrack): ResolvedPlaybackUrl? {
        val preferred = music.subscriptionPreferredProviderId()
        val sources = buildList {
            track.musicSource?.let(::add)
            track.alternateSources.forEach(::add)
        }.distinctBy(MusicSourceRef::key).sortedBy { if (it.providerId == preferred) 0 else 1 }
        if (sources.isEmpty()) return null

        val resolved = resolveSources(track, sources)
        if (resolved != null && !resolved.isTrial) return resolved
        resolveCloudPlayable(track, sources)?.let { return it }
        return resolved
    }

    private suspend fun resolveCloudPlayable(
        track: NativeTrack,
        attemptedSources: List<MusicSourceRef> = listOfNotNull(track.musicSource),
    ): ResolvedPlaybackUrl? {
        val candidates = try {
            music.cloudPlaybackCandidates(track)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val attemptedKeys = attemptedSources.mapTo(hashSetOf(), MusicSourceRef::key)
        val sources = candidates.mapNotNull(NativeTrack::musicSource)
            .distinctBy(MusicSourceRef::key).filter { it.key !in attemptedKeys }
        if (sources.isEmpty()) return null
        return resolveSources(track, sources)?.takeUnless { it.isTrial }?.also {
            DiagnosticsLogStore.record(
                area = "playback", event = "cloud_full_source_selected",
                fields = mapOf("requestedSource" to track.sourceKey, "cloudSource" to it.source?.key),
            )
        }
    }

    private suspend fun resolveSources(track: NativeTrack, sources: List<MusicSourceRef>): ResolvedPlaybackUrl? {
        var attemptedLevels = 0
        var trialFallback: ResolvedPlaybackUrl? = null
        for (source in sources) {
            val sourceTrack = track.copy(
                neteaseId = source.neteaseId,
                sourceRef = source,
                streamUrl = "",
                streamCacheKey = null,
                alternateSources = emptyList(),
            )
            for (level in streamLevelFallbacks) {
                attemptedLevels += 1
                val audio = withTimeoutOrNull(streamUrlTimeoutMs) {
                    try {
                        music.resolveAudio(sourceTrack, level)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                if (audio?.url?.isNotBlank() == true && audio.cacheKey.isNotBlank()) {
                    val resolved = ResolvedPlaybackUrl(
                        url = audio.url, cacheKey = audio.cacheKey, source = source, isTrial = audio.isTrial,
                    )
                    if (!audio.isTrial) return resolved
                    if (trialFallback == null) trialFallback = resolved
                }
            }
        }
        if (trialFallback != null) return trialFallback
        DiagnosticsLogStore.record(
            area = "playback",
            event = "playable_source_empty",
            fields = mapOf(
                "sourceKey" to track.sourceKey,
                "provider" to track.musicSource?.providerId,
                "attemptedLevels" to attemptedLevels,
                "timeoutMs" to streamUrlTimeoutMs,
            ),
        )
        return null
    }

    suspend fun resolveSinglePlayable(track: NativeTrack): NativeTrack? {
        if (track.streamUrl.isNotBlank() && !hasTrialStream(track) && !shouldPreferAlternate(track)) {
            return track
        }
        val resolved = fetchPlayable(track) ?: return track.takeIf { it.streamUrl.isNotBlank() }
        return track.withResolvedSource(resolved)
    }

    suspend fun resolveFirstPlayable(
        candidates: List<NativeTrack>,
        scanLimit: Int,
    ): NativeTrack? {
        candidates.take(scanLimit).forEach { track ->
            resolveSinglePlayable(track)?.let { return it }
        }
        return null
    }

    suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> {
        val missingIds = queue.mapNotNull { track ->
            if (track.streamUrl.isBlank() && track.musicSource?.providerId == MusicProviders.NETEASE &&
                !shouldPreferAlternate(track)) {
                track.neteaseId
            } else {
                null
            }
        }.distinct()

        val urls = HashMap<Long, ResolvedPlaybackUrl>()
        val trialUrls = HashMap<Long, ResolvedPlaybackUrl>()
        var unresolved = missingIds
        for (level in streamLevelFallbacks) {
            for (chunk in unresolved.chunked(50)) {
                withTimeoutOrNull(streamUrlTimeoutMs) {
                    try {
                        repository.songUrls(chunk, level)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }?.forEach { u ->
                    u.url?.takeIf { it.isNotBlank() }?.let { url ->
                        val resolved = ResolvedPlaybackUrl(
                            url = url,
                            cacheKey = PlaybackCacheKeys.resolvedNetease(
                                id = u.id,
                                level = level,
                                bitrate = u.bitrate,
                                sizeBytes = u.sizeBytes,
                                isTrial = u.isTrial,
                            ),
                            source = MusicSourceRef(MusicProviders.NETEASE, u.id.toString()),
                            isTrial = u.isTrial,
                        )
                        if (u.isTrial) trialUrls.putIfAbsent(u.id, resolved) else urls[u.id] = resolved
                    }
                }
            }
            unresolved = unresolved.filter { it !in urls }
            if (unresolved.isEmpty()) break
        }
        if (unresolved.isNotEmpty()) {
            DiagnosticsLogStore.record(
                area = "playback",
                event = "playable_queue_partial",
                fields = mapOf(
                    "missingCount" to missingIds.size,
                    "resolvedCount" to urls.size,
                    "unresolvedCount" to unresolved.size,
                    "attemptedLevels" to streamLevelFallbacks.size,
                    "timeoutMs" to streamUrlTimeoutMs,
                ),
            )
        }

        val sourceCandidates = queue.mapIndexedNotNull { index, track ->
            val hasBatchResolution = track.neteaseId?.let(urls::containsKey) == true
            if (track.canResolveAudio && (shouldPreferAlternate(track) || hasTrialStream(track) ||
                (track.streamUrl.isBlank() && !hasBatchResolution))
            ) {
                index to track
            } else {
                null
            }
        }
        val sourceResolved = HashMap<Int, NativeTrack>()
        for (chunk in sourceCandidates.chunked(SOURCE_RESOLVE_CONCURRENCY)) {
            coroutineScope {
                chunk.map { (index, track) ->
                    async {
                        // The batch already tried every quality. Search the private cloud before
                        // accepting its trial URL, without repeating those public requests.
                        val resolved = if (track.neteaseId in missingIds && track.alternateSources.isEmpty()) {
                            resolveCloudPlayable(track)?.let { track.withResolvedSource(it) }
                        } else {
                            resolveSinglePlayable(track)
                        }
                        index to (resolved ?: track)
                    }
                }.awaitAll()
            }.forEach { (index, track) ->
                sourceResolved[index] = track
            }
        }

        return queue.mapIndexed { index, track ->
            val resolved = track.neteaseId?.let { urls[it] ?: trialUrls[it] }
            val sourceTrack = sourceResolved[index]
            when {
                sourceTrack?.streamUrl?.isNotBlank() == true -> sourceTrack
                track.streamUrl.isBlank() && resolved != null -> track.withResolvedSource(resolved)
                else -> track
            }
        }
    }

    private fun shouldPreferAlternate(track: NativeTrack): Boolean {
        val preferred = music.subscriptionPreferredProviderId() ?: return false
        return track.musicSource?.providerId != preferred && track.alternateSources.any { it.providerId == preferred }
    }

    private fun hasTrialStream(track: NativeTrack): Boolean = track.streamCacheKey?.endsWith(":trial") == true

    private fun NativeTrack.withResolvedSource(resolved: ResolvedPlaybackUrl): NativeTrack = resolved.applyTo(this)

    private companion object {
        const val SOURCE_RESOLVE_CONCURRENCY = 4
    }
}
