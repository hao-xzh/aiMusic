package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoPlaylist
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 把 AI 从用户原话中提取出的具名歌曲，安全地导入一个真实网易云歌单。
 *
 * 关键约束：
 * - 先把所有歌解析成功，再创建歌单，避免留下正常路径下的半成品；
 * - 同名歌手歧义时不猜，要求用户补歌手；
 * - 只按歌名（歌手可选）批量添加，不承诺或改写歌曲顺序；
 * - 同名歌单只有内容完全一致才视为已完成；内容不同绝不推断为半成品或自动改写。
 */
class PlaylistImportService(
    private val repository: PipoRepository,
) {
    private val searchSemaphore = Semaphore(SEARCH_CONCURRENCY)

    suspend fun create(
        actionId: String,
        playlistName: String,
        requestedTracks: List<TrackRequirement>,
    ): ActionExecutionResult {
        val cleanName = playlistName.trim()
        val requests = requestedTracks
            .map { it.copy(title = it.title.trim(), artist = it.artist?.trim()?.takeIf(String::isNotBlank)) }
            .filter { it.title.isNotBlank() }

        validationFailure(actionId, cleanName, requests)?.let { return it }

        val account = try {
            repository.refreshAccount()
            repository.account.first()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure("account_check_failed", cleanName, error)
            runCatching { repository.account.first() }.getOrNull()
        }
        if (account == null) {
            return failure(actionId, cleanName, "请先登录网易云账号，再让我创建歌单。")
        }

        val resolutions = coroutineScope {
            requests.map { target -> async { resolveTrack(target) } }.map { it.await() }
        }
        val unresolved = resolutions.filterNot { it is TrackResolution.Match }
        if (unresolved.isNotEmpty()) {
            val details = unresolved.take(6).joinToString("；") { resolutionMessage(it) }
            val suffix = if (unresolved.size > 6) "；另有 ${unresolved.size - 6} 首未匹配" else ""
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "playlist_import_resolution_blocked",
                fields = mapOf(
                    "playlistName" to cleanName,
                    "requestedCount" to requests.size,
                    "unresolvedCount" to unresolved.size,
                ),
            )
            return failure(
                actionId,
                cleanName,
                "还不能创建「$cleanName」：$details$suffix。请补充准确歌手或歌名后再试。",
            )
        }

        val resolvedTracks = resolutions.map { (it as TrackResolution.Match).track }
        val trackIds = resolvedTracks.mapNotNull { it.neteaseId }
        if (trackIds.size != resolvedTracks.size) {
            return failure(actionId, cleanName, "有歌曲缺少网易云曲目 ID，歌单没有创建。")
        }
        val duplicateResolved = resolvedTracks
            .zip(trackIds)
            .groupBy { it.second }
            .values
            .firstOrNull { it.size > 1 }
            ?.firstOrNull()
            ?.first
        if (duplicateResolved != null) {
            return failure(
                actionId,
                cleanName,
                "清单中的不同写法匹配到了同一首「${duplicateResolved.title}」；网易云歌单不能重复放入同一曲目，请先确认清单。",
            )
        }

        val playlists = loadPlaylists()
        val existing = playlists.firstOrNull { playlist ->
            playlist.userId == account.userId &&
                playlist.name.trim().equals(cleanName, ignoreCase = true)
        }
        if (existing != null) {
            return inspectExisting(actionId, existing, cleanName, resolvedTracks, trackIds)
        }

        val playlistId = try {
            repository.createPlaylist(cleanName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure("create_failed", cleanName, error)
            return failure(actionId, cleanName, "创建「$cleanName」失败，请确认网易云登录状态后重试。")
        }
        return populatePlaylist(
            actionId = actionId,
            playlistId = playlistId,
            playlistName = cleanName,
            resolvedTracks = resolvedTracks,
            requestedTrackIds = trackIds,
        )
    }

    private fun validationFailure(
        actionId: String,
        playlistName: String,
        requests: List<TrackRequirement>,
    ): ActionExecutionResult? {
        if (playlistName.isBlank()) return failure(actionId, playlistName, "歌单名称不能为空。")
        if (requests.isEmpty()) return failure(actionId, playlistName, "请至少给我一首明确的歌名。")
        if (requests.size > MAX_TRACKS) {
            return failure(actionId, playlistName, "一次最多导入 $MAX_TRACKS 首歌，请分成两个歌单或缩短清单。")
        }
        val duplicate = requests
            .groupBy { requestKey(it) }
            .entries
            .firstOrNull { it.key.isNotBlank() && it.value.size > 1 }
            ?.value
            ?.firstOrNull()
        if (duplicate != null) {
            return failure(
                actionId,
                playlistName,
                "清单里重复出现了「${trackRequestLabel(duplicate)}」；网易云歌单不能按重复项保留两次，请先确认清单。",
            )
        }
        return null
    }

    private suspend fun resolveTrack(target: TrackRequirement): TrackResolution = searchSemaphore.withPermit {
        val primaryQuery = listOf(target.title, target.artist.orEmpty())
            .filter(String::isNotBlank)
            .joinToString(" ")
        val primary = search(primaryQuery)
        val candidates = if (hasUsableTitleCandidate(primary, target)) {
            primary
        } else if (!target.artist.isNullOrBlank()) {
            (primary + search(target.title)).distinctBy(::trackIdentity)
        } else {
            primary
        }
        selectCandidate(target, candidates)
    }

    private suspend fun search(query: String): List<NativeTrack> {
        if (query.isBlank()) return emptyList()
        return try {
            repository.searchTracks(query, SEARCH_LIMIT)
                .filter { it.neteaseId != null && it.title.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "playlist_import_search_failed",
                fields = mapOf("query" to query.take(100), "errorType" to error::class.java.simpleName),
            )
            emptyList()
        }
    }

    private fun selectCandidate(target: TrackRequirement, candidates: List<NativeTrack>): TrackResolution {
        val wantedTitle = CommandTextSignals.normalizeForMatch(target.title)
        val exactTitle = candidates.filter {
            CommandTextSignals.normalizeForMatch(it.title) == wantedTitle
        }
        val artistFiltered = target.artist?.let { artist ->
            exactTitle.filter { artistMatches(it.artist, artist) }
        } ?: exactTitle
        if (artistFiltered.isEmpty()) return TrackResolution.Missing(target)

        if (target.artist.isNullOrBlank()) {
            val distinctArtists = artistFiltered
                .map { it.artist.trim() }
                .filter(String::isNotBlank)
                .distinctBy(CommandTextSignals::normalizeForMatch)
            if (distinctArtists.size > 1) {
                return TrackResolution.Ambiguous(target, distinctArtists.take(3))
            }
        }
        return TrackResolution.Match(
            target = target,
            track = artistFiltered.minByOrNull(::variantWeight) ?: return TrackResolution.Missing(target),
        )
    }

    private fun hasUsableTitleCandidate(candidates: List<NativeTrack>, target: TrackRequirement): Boolean {
        val wantedTitle = CommandTextSignals.normalizeForMatch(target.title)
        return candidates.any { track ->
            CommandTextSignals.normalizeForMatch(track.title) == wantedTitle &&
                (target.artist.isNullOrBlank() || artistMatches(track.artist, target.artist))
        }
    }

    private suspend fun inspectExisting(
        actionId: String,
        playlist: PipoPlaylist,
        playlistName: String,
        resolvedTracks: List<NativeTrack>,
        requestedTrackIds: List<Long>,
    ): ActionExecutionResult {
        val existingTracks = try {
            repository.tracksForPlaylist(playlist.id, forceRefresh = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure("existing_playlist_load_failed", playlistName, error)
            return failure(actionId, playlistName, "已存在同名歌单「$playlistName」，但暂时无法核对内容；没有重复创建。")
        }
        val existingIds = existingTracks.mapNotNull { it.neteaseId }
        if (sameTrackSet(existingIds, requestedTrackIds)) {
            return success(
                actionId,
                playlistName,
                resolvedTracks,
                "「$playlistName」已经存在，${requestedTrackIds.size} 首指定歌曲都已添加，没有重复创建。",
            )
        }
        return failure(
            actionId,
            playlistName,
            "已存在同名歌单「$playlistName」，而且内容不同；为避免覆盖你的歌单，我没有修改或重复创建。请换一个歌单名。",
        )
    }

    private suspend fun populatePlaylist(
        actionId: String,
        playlistId: Long,
        playlistName: String,
        resolvedTracks: List<NativeTrack>,
        requestedTrackIds: List<Long>,
    ): ActionExecutionResult {
        try {
            repository.playlistModifyTracks(playlistId, "add", requestedTrackIds)
            repository.refreshPlaylists()

            var remoteTracks = fetchPlaylistTracks(playlistId)
            if (!sameTrackSet(remoteTracks.mapNotNull { it.neteaseId }, requestedTrackIds)) {
                val remoteIds = remoteTracks.mapNotNull { it.neteaseId }.toSet()
                val missingIds = requestedTrackIds.filterNot(remoteIds::contains)
                if (missingIds.isNotEmpty()) {
                    repository.playlistModifyTracks(playlistId, "add", missingIds)
                    repository.refreshPlaylists()
                }
                delay(VERIFY_RETRY_DELAY_MS)
                remoteTracks = fetchPlaylistTracks(playlistId)
            }
            val remoteIds = remoteTracks.mapNotNull { it.neteaseId }
            if (!sameTrackSet(remoteIds, requestedTrackIds)) {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "playlist_import_verify_failed",
                    fields = mapOf(
                        "playlistId" to playlistId,
                        "playlistName" to playlistName,
                        "expectedCount" to requestedTrackIds.size,
                        "actualCount" to remoteIds.size,
                    ),
                )
                return failure(
                    actionId,
                    playlistName,
                    "「$playlistName」已经建立，但服务器回读到的指定歌曲还不完整（${remoteIds.size}/${requestedTrackIds.size}）。为避免误改同名歌单，我不会自动续写；请删除这份不完整歌单，或换个名称后重试。",
                    tracks = remoteTracks,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure("populate_failed", playlistName, error, playlistId)
            return failure(
                actionId,
                playlistName,
                "「$playlistName」已经建立，但导入歌曲时失败了。为避免误改同名歌单，我不会自动续写；请删除这份不完整歌单，或换个名称后重试。",
            )
        }

        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "playlist_import_succeeded",
            fields = mapOf(
                "playlistId" to playlistId,
                "playlistName" to playlistName,
                "trackCount" to requestedTrackIds.size,
            ),
        )
        return success(
            actionId,
            playlistName,
            resolvedTracks,
            "已创建「$playlistName」，添加 ${requestedTrackIds.size} 首指定歌曲。",
        )
    }

    private fun sameTrackSet(left: List<Long>, right: List<Long>): Boolean =
        left.size == right.size && left.toSet() == right.toSet()

    private suspend fun fetchPlaylistTracks(playlistId: Long): List<NativeTrack> =
        try {
            repository.tracksForPlaylist(playlistId, forceRefresh = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun loadPlaylists(): List<PipoPlaylist> {
        return try {
            repository.refreshPlaylists()
            repository.playlists.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            repository.playlists.first()
        }
    }

    private fun success(
        actionId: String,
        playlistName: String,
        tracks: List<NativeTrack>,
        message: String,
    ) = ActionExecutionResult(
        actionId = actionId,
        type = "playlist_create",
        success = true,
        message = message,
        tracks = tracks,
        playlistName = playlistName,
    )

    private fun failure(
        actionId: String,
        playlistName: String,
        message: String,
        tracks: List<NativeTrack> = emptyList(),
    ) = ActionExecutionResult(
        actionId = actionId,
        type = "playlist_create",
        success = false,
        message = message,
        tracks = tracks,
        playlistName = playlistName.takeIf(String::isNotBlank),
        acceptedByPlayer = false,
        errorMessage = message,
    )

    private fun resolutionMessage(resolution: TrackResolution): String = when (resolution) {
        is TrackResolution.Match -> trackRequestLabel(resolution.target)
        is TrackResolution.Missing -> "没找到「${trackRequestLabel(resolution.target)}」"
        is TrackResolution.Ambiguous ->
            "「${resolution.target.title}」有多个歌手版本（${resolution.artists.joinToString("、")}）"
    }

    private fun requestKey(target: TrackRequirement): String = listOf(
        CommandTextSignals.normalizeForMatch(target.title),
        CommandTextSignals.normalizeForMatch(target.artist.orEmpty()),
    ).joinToString("|")

    private fun trackRequestLabel(target: TrackRequirement): String =
        listOf(target.title, target.artist.orEmpty()).filter(String::isNotBlank).joinToString(" - ")

    private fun artistMatches(candidateRaw: String, wantedRaw: String?): Boolean {
        if (wantedRaw.isNullOrBlank()) return true
        val candidate = CommandTextSignals.normalizeForMatch(candidateRaw)
        val wanted = CommandTextSignals.normalizeForMatch(wantedRaw)
        return candidate.isNotBlank() && wanted.isNotBlank() &&
            (candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate))
    }

    private fun variantWeight(track: NativeTrack): Int {
        val lower = track.title.lowercase()
        var weight = track.title.length
        if ("live" in lower || "现场" in lower || "演唱会" in lower) weight += 1_000
        if ("伴奏" in lower || "instrumental" in lower || "karaoke" in lower) weight += 1_000
        if ("cover" in lower || "翻唱" in lower) weight += 800
        if ("remix" in lower || "混音" in lower) weight += 700
        return weight
    }

    private fun trackIdentity(track: NativeTrack): String =
        track.neteaseId?.toString() ?: track.id

    private fun recordFailure(
        event: String,
        playlistName: String,
        error: Throwable,
        playlistId: Long? = null,
    ) {
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "playlist_import_$event",
            fields = mapOf(
                "playlistName" to playlistName,
                "playlistId" to playlistId,
                "errorType" to error::class.java.simpleName,
                "errorMessage" to error.message.orEmpty().take(180),
            ),
        )
    }

    private sealed interface TrackResolution {
        val target: TrackRequirement

        data class Match(
            override val target: TrackRequirement,
            val track: NativeTrack,
        ) : TrackResolution

        data class Missing(
            override val target: TrackRequirement,
        ) : TrackResolution

        data class Ambiguous(
            override val target: TrackRequirement,
            val artists: List<String>,
        ) : TrackResolution
    }

    private companion object {
        const val MAX_TRACKS = 50
        const val SEARCH_LIMIT = 18
        const val SEARCH_CONCURRENCY = 5
        const val VERIFY_RETRY_DELAY_MS = 450L
    }
}
