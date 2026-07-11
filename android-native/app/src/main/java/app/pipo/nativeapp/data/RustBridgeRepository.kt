package app.pipo.nativeapp.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.playback.PipoMediaCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Repository implementation target for the full native build.
 *
 * Repository implementation backed by the Rust/JNI bridge. The demo repository
 * is kept only as a crash-safe fallback while local machines may not have the
 * native library packaged yet.
 *
 * - netease_qr_start / netease_qr_check
 * - netease_account / netease_user_playlists / netease_playlist_detail
 * - netease_song_urls / netease_song_lyric / netease_cloud_lyric
 * - audio_cache_* / audio_get_features
 * - ai_get_config / ai_set_provider / ai_set_api_key / ai_set_model
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RustBridgeRepository(
    private val bridge: RustPipoBridge,
    private val appContext: Context? = null,
    private val fallback: PipoRepository = EmptyPipoRepository(),
) : PipoRepository {
    private val settingsStore = appContext?.let(::LocalSettingsStore)
    private val playlistCache = appContext?.let(::PlaylistCacheStore)
    private val amllSource = appContext?.let(::AmllLyricsSource)
    private val accountState = MutableStateFlow<PipoAccount?>(null)
    private val playlistState = MutableStateFlow<List<PipoPlaylist>>(emptyList())
    /** 网盘曲目 Flow —— init 从磁盘 cache 恢复，cloudDiskTracks 加载后 emit。让
     *  DistillLibrary 把 cover-flow 那一页的 cover / count 跨重挂载持久化。 */
    private val cloudTracksState = MutableStateFlow<List<NativeTrack>>(emptyList())
    private val audioCacheStatsState = MutableStateFlow(AudioCacheStats(0, 0, 0))
    private val aiConfigState = MutableStateFlow(AiConfigView(activeProvider = "", providers = emptyList()))

    /** 当前缓存快照是否过期 —— refreshPlaylists 决定要不要后台 revalidate */
    @Volatile
    private var cacheStale: Boolean = false

    /** 上次缓存对应的 userId —— refreshAccount 拿到当前 userId 后比对，不一致就清缓存换账号 */
    @Volatile
    private var cachedUserId: Long? = null

    /** 曲目内存缓存：playlistId → tracks。冷启动 init 时灌入上次的快照，运行时累加 */
    private val tracksCacheLock = Any()
    private val tracksMemoryCache = mutableMapOf<Long, List<NativeTrack>>()
    private val cloudSearchMatchLock = Any()
    private val cloudSearchMatchCache = mutableMapOf<String, NativeTrack>()

    init {
        // 冷启动 sync 读盘：把上次的 playlist 列表 + 已知 tracks 灌进 in-memory state。
        // 这样 PipoApplication.onCreate 装 Repository 之后，Compose 第一次 collectAsState
        // 直接拿到非空 list，不再有"空白几秒等网"的尴尬。
        val snap = playlistCache?.load()
        if (snap != null) {
            // 不在这里反推 accountState —— 实际 accountState 仍要靠 refreshAccount 走网拿 cookie
            // 验证；只是把上一次的 playlists 显示出来当作 stale-while-revalidate
            playlistState.value = snap.playlists
            synchronized(tracksCacheLock) {
                tracksMemoryCache.putAll(snap.tracks)
            }
            // 网盘也用同一磁盘 cache（sentinel id）——恢复后立刻 emit 到 Flow，让 cover-flow
            // 那一页冷启动就有 cover / count，不用等 LaunchedEffect 触发再回填。
            snap.tracks[CLOUD_DISK_PLAYLIST_ID]?.let { cloudTracksState.value = it }
            cacheStale = snap.isStale
            cachedUserId = snap.userId
        }
    }

    override val account: Flow<PipoAccount?> = accountState.asStateFlow()
    override val playlists: Flow<List<PipoPlaylist>> = playlistState.asStateFlow()
    override val cloudTracks: Flow<List<NativeTrack>> = cloudTracksState.asStateFlow()
    override val distillState: Flow<DistillState> = fallback.distillState
    override val settings: Flow<NativeSettings> = settingsStore?.settings ?: fallback.settings
    override val audioCacheStats: Flow<AudioCacheStats> = audioCacheStatsState.asStateFlow()
    override val aiConfig: Flow<AiConfigView> = aiConfigState.asStateFlow()

    override suspend fun refreshAccount() {
        val acc = try {
            bridge.neteaseAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        accountState.value = acc
        // 换账号 / 退登检查：当前 userId 跟上次缓存不匹配 → 清掉旧用户的歌单 + tracks
        val newUserId = acc?.userId
        val oldUserId = cachedUserId
        if (
            (newUserId == null && (oldUserId != null || playlistState.value.isNotEmpty())) ||
            (newUserId != null && oldUserId != null && newUserId != oldUserId)
        ) {
            clearAccountCaches()
        }
    }

    override suspend fun logout() {
        safe({ bridge.neteaseLogout() }, { Unit })
        accountState.value = null
        clearAccountCaches()
    }

    override suspend fun startQrLogin(): QrLoginStart {
        // 不走 safe() —— 之前 safe 吞掉异常返回空 fallback,UI 拿到空二维码 + 泛文案
        // "Login bridge unavailable",根因(网络 / JNI / cookie / 风控)完全看不到。
        // 这里直接抛,让 LoginScreen.runQrFlow 用 runCatching 拿到异常信息显示。
        try {
            return bridge.neteaseQrStart()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e
        }
    }

    override suspend fun checkQrLogin(key: String): QrLoginStatus {
        try {
            return bridge.neteaseQrCheck(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // checkQrLogin 在轮询里被频繁调,这里返回带异常文案的 status,而不是 throw,
            // 让轮询循环能优雅退出 + 显示信息
            return QrLoginStatus(code = -1, message = "${e.javaClass.simpleName}: ${e.message ?: "bridge error"}")
        }
    }

    override suspend fun sendPhoneCaptcha(phone: String, countryCode: Int): CaptchaSentStatus {
        return safe(
            { bridge.neteaseCaptchaSent(phone, countryCode) },
            { CaptchaSentStatus(code = -1, message = "Captcha bridge unavailable") },
        )
    }

    override suspend fun verifyPhoneCaptcha(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): CaptchaSentStatus {
        return safe(
            { bridge.neteaseCaptchaVerify(phone, captcha, countryCode) },
            { CaptchaSentStatus(code = -1, message = "Captcha bridge unavailable") },
        )
    }

    override suspend fun loginWithPhone(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): PhoneLoginStatus {
        return safe(
            { bridge.neteasePhoneLogin(phone, captcha, countryCode) },
            { PhoneLoginStatus(code = -1, message = "Phone login bridge unavailable") },
        )
    }

    override suspend fun refreshPlaylists() {
        // stale-while-revalidate 的**正确**做法:UI 已经从 init 时的 cache 看到内容了,
        // 这里**总是后台拉网**覆盖。
        //
        // 之前的 `if (haveMemory && !cacheStale) return` 是错的 —— 只要冷启动时缓存
        // 不到 24h,refreshPlaylists 永远短路,网易云那边新建/改名/换封面的歌单
        // app 重启都看不到。用户感受是"歌单始终不更新"。
        val fresh = safe(
            {
                val account = accountState.value ?: bridge.neteaseAccount().also { accountState.value = it }
                account?.let { bridge.neteaseUserPlaylists(it.userId) }.orEmpty()
            },
            { emptyList() },
        )
        if (fresh.isNotEmpty()) {
            // 用 updateTime 对比精准 invalidate:网易云对每张歌单维护 updateTime,
            // 同一 id 的歌单 updateTime 变了 = tracks 也可能变了,旧 cache 不可信。
            // 不变的 → cache 仍可复用,省一次 tracksForPlaylist 网络往返。
            val oldByIdTime = playlistState.value.associate { it.id to it.updateTime }
            val tracksSnapshot = synchronized(tracksCacheLock) {
                for (p in fresh) {
                    val oldTime = oldByIdTime[p.id]
                    if (oldTime != null && p.updateTime != null && oldTime != p.updateTime) {
                        tracksMemoryCache.remove(p.id)
                    }
                }
                // 删掉已经不在用户账号下的歌单(用户在网易云端删了/取关了)。
                // 网盘 sentinel(CLOUD_DISK_PLAYLIST_ID)不是真实歌单，永远不在 freshIds 里，
                // 必须显式保留——否则每次 refresh 都连带把网盘 in-memory + 落盘 cache 误删，
                // 进网盘页只能重新拉网（其它歌单 id 在 freshIds 里所以静默更新正常）。
                val freshIds = fresh.mapTo(HashSet()) { it.id }
                freshIds.add(CLOUD_DISK_PLAYLIST_ID)
                tracksMemoryCache.keys.retainAll(freshIds)
                HashMap(tracksMemoryCache)
            }

            playlistState.value = fresh
            cacheStale = false
            val uid = accountState.value?.userId
            if (uid != null) {
                cachedUserId = uid
                playlistCache?.save(uid, fresh, tracksSnapshot)
            }
        }
    }

    override suspend fun tracksForPlaylist(playlistId: Long, forceRefresh: Boolean): List<NativeTrack> {
        // 网盘 sentinel 不是真实 NetEase 歌单，neteasePlaylistTracks(-1) 拿不到东西——
        // 路由到 cloudDiskTracks（同样命中 tracksMemoryCache[sentinel] 缓存）。让 AI 的
        // play_playlist / get_playlist_tracks 和蒸馏 DistillEngine 都能把"我的网盘"当普通歌单用。
        if (playlistId == CLOUD_DISK_PLAYLIST_ID) return cloudDiskTracks(forceRefresh)
        if (!forceRefresh) {
            synchronized(tracksCacheLock) {
                tracksMemoryCache[playlistId]
            }?.let {
                DiagnosticsLogStore.record(
                    area = "library",
                    event = "playlist_tracks_cache_hit",
                    fields = mapOf("playlistId" to playlistId, "count" to it.size),
                )
                return it
            }
        } else {
            // 手动下拉刷新：先抹掉 in-memory cache 让本次拿到 fresh 数据再重新写回。
            synchronized(tracksCacheLock) { tracksMemoryCache.remove(playlistId) }
            DiagnosticsLogStore.record(
                area = "library",
                event = "playlist_tracks_force_refresh",
                fields = mapOf("playlistId" to playlistId),
            )
        }
        val expectedCount = playlistState.value.firstOrNull { it.id == playlistId }?.trackCount
        val fresh = try {
            bridge.neteasePlaylistTracks(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallbackTracks = runCatching { fallback.tracksForPlaylist(playlistId) }.getOrDefault(emptyList())
            DiagnosticsLogStore.record(
                area = "library",
                event = "playlist_tracks_fetch_failed",
                fields = mapOf(
                    "playlistId" to playlistId,
                    "expectedCount" to expectedCount,
                    "fallbackCount" to fallbackTracks.size,
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            if (fallbackTracks.isNotEmpty()) fallbackTracks else throw e
        }
        if (fresh.isEmpty()) {
            val suspiciousEmpty = expectedCount != null && expectedCount > 0
            DiagnosticsLogStore.record(
                area = "library",
                event = if (suspiciousEmpty) "playlist_tracks_fetch_empty_suspect" else "playlist_tracks_fetch_empty",
                fields = mapOf(
                    "playlistId" to playlistId,
                    "expectedCount" to expectedCount,
                ),
            )
            if (suspiciousEmpty) {
                throw IllegalStateException("Playlist $playlistId expected $expectedCount tracks but detail returned empty")
            }
        }
        if (fresh.isNotEmpty()) {
            DiagnosticsLogStore.record(
                area = "library",
                event = "playlist_tracks_fetch_ok",
                fields = mapOf(
                    "playlistId" to playlistId,
                    "expectedCount" to expectedCount,
                    "count" to fresh.size,
                ),
            )
            val tracksSnapshot = synchronized(tracksCacheLock) {
                tracksMemoryCache[playlistId] = fresh
                HashMap(tracksMemoryCache)
            }
            // 拉到新一张歌单 tracks 后，把整个 in-memory cache 重新落盘
            // （save 内部异步 IO，不阻塞调用方）
            val uid = accountState.value?.userId ?: cachedUserId
            val playlists = playlistState.value
            if (uid != null && playlists.isNotEmpty()) {
                playlistCache?.save(uid, playlists, tracksSnapshot)
            }
        }
        return fresh
    }

    override fun cachedTracksFor(playlistId: Long): List<NativeTrack>? =
        synchronized(tracksCacheLock) { tracksMemoryCache[playlistId] }

    override suspend fun cloudDiskTracks(forceRefresh: Boolean): List<NativeTrack> {
        // 复用 tracksMemoryCache，用 sentinel ID 走和正常 playlist 一样的缓存/落盘链路。
        // PlaylistCacheStore 用 Long 当 key，负值跟正常 playlistId 永远不会冲突。
        val sentinel = CLOUD_DISK_PLAYLIST_ID
        if (!forceRefresh) {
            synchronized(tracksCacheLock) {
                tracksMemoryCache[sentinel]
            }?.let {
                DiagnosticsLogStore.record(
                    area = "library",
                    event = "cloud_disk_tracks_cache_hit",
                    fields = mapOf("count" to it.size),
                )
                val hydrated = hydrateCloudFirstAvailableCover(it)
                if (hydrated != it) {
                    replaceCloudTracks(hydrated)
                } else if (cloudTracksState.value !== it) {
                    // 确保 Flow 跟内存 cache 同步（init 已经灌过一次，这里幂等）
                    cloudTracksState.value = it
                }
                return hydrated
            }
        } else {
            synchronized(tracksCacheLock) { tracksMemoryCache.remove(sentinel) }
            DiagnosticsLogStore.record(area = "library", event = "cloud_disk_tracks_force_refresh")
        }
        val fresh = try {
            bridge.neteaseUserCloudTracks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "library",
                event = "cloud_disk_tracks_fetch_failed",
                fields = mapOf(
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            // 退回 fallback（一般 EmptyPipoRepository 给空 list），让 UI 显示"空网盘"提示
            return runCatching { fallback.cloudDiskTracks(forceRefresh) }.getOrDefault(emptyList())
        }
        val hydratedFresh = hydrateCloudFirstAvailableCover(fresh)
        DiagnosticsLogStore.record(
            area = "library",
            event = "cloud_disk_tracks_fetch_ok",
            fields = mapOf(
                "count" to hydratedFresh.size,
                "availableCover" to hydratedFresh.any { !it.artworkUrl.isNullOrBlank() },
            ),
        )
        if (hydratedFresh.isNotEmpty()) {
            replaceCloudTracks(hydratedFresh)
        }
        return hydratedFresh
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NativeTrack> {
        return safe({ bridge.neteaseSearch(query, limit) }, { fallback.searchTracks(query, limit) })
    }

    override suspend fun songUrls(ids: List<Long>, level: String): List<NativeSongUrl> {
        return safe({ bridge.neteaseSongUrls(ids, level) }, { fallback.songUrls(ids, level) })
    }

    override suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine> {
        val cloudTrack = cachedCloudTrackFor(trackId)
        if (cloudTrack != null) {
            val cloudLines = loadCloudLyricsById(trackId, cloudTrack)
            if (lyricsAreUsable(cloudLines)) return cloudLines
        }

        val primaryLines = loadLyricsById(trackId)
        if (lyricsAreUsable(primaryLines)) return primaryLines

        if (cloudTrack == null) return emptyList()
        DiagnosticsLogStore.record(
            area = "lyrics",
            event = "cloud_lyric_primary_unusable",
            fields = mapOf(
                "trackId" to trackId,
                "title" to cloudTrack.title,
                "artist" to cloudTrack.artist,
                "lineCount" to primaryLines.size,
                "nonBlankLineCount" to primaryLines.count { it.text.isNotBlank() },
            ),
        )
        val matched = resolveCloudSearchMatch(trackId, cloudTrack, allowSameId = false) ?: return emptyList()
        val matchedId = matched.neteaseId ?: return emptyList()
        val matchedLines = loadLyricsById(matchedId.toString())
        if (lyricsAreUsable(matchedLines)) {
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "cloud_lyric_match_loaded",
                fields = mapOf(
                    "trackId" to trackId,
                    "matchedId" to matchedId,
                    "title" to cloudTrack.title,
                    "matchedTitle" to matched.title,
                    "matchedArtist" to matched.artist,
                    "lineCount" to matchedLines.size,
                ),
            )
            return matchedLines
        } else {
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "cloud_lyric_match_empty",
                fields = mapOf(
                    "trackId" to trackId,
                    "matchedId" to matchedId,
                    "title" to cloudTrack.title,
                    "matchedTitle" to matched.title,
                    "matchedArtist" to matched.artist,
                    "lineCount" to matchedLines.size,
                    "nonBlankLineCount" to matchedLines.count { it.text.isNotBlank() },
                ),
            )
        }
        return emptyList()
    }

    private suspend fun loadLyricsById(trackId: String): List<PipoLyricLine> {
        // 优先走 AMLL TTML 数据库（字级时间戳，质量明显高于 netease yrc）；
        // 命中失败（404 / 网络错误 / 非数字 trackId / 解析空）才回落到 Rust bridge。
        // AmllLyricsSource 内部已经做了本地永久缓存 + 404 哨兵，重复播同一首不会反复打网络。
        // 两条来源统一过 LyricCredits：开头的作词/作曲/制作人信息行不当歌词展示，
        // 留出的前奏空档由歌词列的间奏三点指示接管（对齐 Apple Music）。
        amllSource?.lyricsForTrack(trackId)?.takeIf { it.isNotEmpty() }
            ?.let { return LyricCredits.stripLeading(it) }
        return LyricCredits.stripLeading(
            safe({ bridge.neteaseSongLyric(trackId) }, { fallback.lyricsForTrack(trackId) }),
        )
    }

    private suspend fun loadCloudLyricsById(trackId: String, track: NativeTrack): List<PipoLyricLine> {
        val songId = track.neteaseId ?: trackId.toLongOrNull()
        if (songId == null) {
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "cloud_lyric_missing_song_id",
                fields = mapOf(
                    "trackId" to trackId,
                    "title" to track.title,
                    "artist" to track.artist,
                ),
            )
            return emptyList()
        }

        val userId = currentUserIdForCloudLyric()
        if (userId == null) {
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "cloud_lyric_missing_user",
                fields = mapOf(
                    "trackId" to trackId,
                    "songId" to songId,
                    "title" to track.title,
                    "artist" to track.artist,
                ),
            )
            return emptyList()
        }

        val lines = try {
            bridge.neteaseCloudLyric(songId, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "lyrics",
                event = "cloud_lyric_load_failed",
                fields = mapOf(
                    "trackId" to trackId,
                    "songId" to songId,
                    "userId" to userId,
                    "title" to track.title,
                    "artist" to track.artist,
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            emptyList()
        }
        val stripped = LyricCredits.stripLeading(lines)
        DiagnosticsLogStore.record(
            area = "lyrics",
            event = "cloud_lyric_loaded",
            fields = mapOf(
                "trackId" to trackId,
                "songId" to songId,
                "userId" to userId,
                "title" to track.title,
                "artist" to track.artist,
                "lineCount" to stripped.size,
                "nonBlankLineCount" to stripped.count { it.text.isNotBlank() },
                "usable" to lyricsAreUsable(stripped),
            ),
        )
        return stripped
    }

    private suspend fun currentUserIdForCloudLyric(): Long? {
        accountState.value?.userId?.let { return it }
        cachedUserId?.let { return it }
        val account = try {
            bridge.neteaseAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        accountState.value = account
        return account?.userId?.also { cachedUserId = it }
    }

    private fun lyricsAreUsable(lines: List<PipoLyricLine>): Boolean {
        if (lines.isEmpty()) return false
        val texts = lines.map { it.text.trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return false
        return texts.any { text -> normalizeLyricPlaceholder(text) !in NO_LYRIC_PLACEHOLDERS }
    }

    private suspend fun hydrateCloudFirstAvailableCover(tracks: List<NativeTrack>): List<NativeTrack> {
        if (tracks.isEmpty() || tracks.any { !it.artworkUrl.isNullOrBlank() }) return tracks
        tracks.take(CLOUD_COVER_MATCH_SCAN_LIMIT).forEachIndexed { index, track ->
            val matched = resolveCloudSearchMatch(track.id, track) ?: return@forEachIndexed
            val cover = matched.artworkUrl?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            DiagnosticsLogStore.record(
                area = "library",
                event = "cloud_cover_matched",
                fields = mapOf(
                    "trackId" to track.id,
                    "matchedId" to matched.neteaseId,
                    "index" to index,
                    "title" to track.title,
                    "matchedTitle" to matched.title,
                    "matchedArtist" to matched.artist,
                ),
            )
            return tracks.toMutableList().also { it[index] = track.copy(artworkUrl = cover) }
        }
        return tracks
    }

    private fun replaceCloudTracks(tracks: List<NativeTrack>) {
        val tracksSnapshot = synchronized(tracksCacheLock) {
            tracksMemoryCache[CLOUD_DISK_PLAYLIST_ID] = tracks
            HashMap(tracksMemoryCache)
        }
        cloudTracksState.value = tracks
        val uid = accountState.value?.userId ?: cachedUserId
        val playlists = playlistState.value
        if (uid != null && playlists.isNotEmpty()) {
            playlistCache?.save(uid, playlists, tracksSnapshot)
        }
    }

    private fun cachedCloudTrackFor(trackId: String): NativeTrack? =
        synchronized(tracksCacheLock) {
            tracksMemoryCache[CLOUD_DISK_PLAYLIST_ID]
                ?.firstOrNull { it.id == trackId || it.neteaseId?.toString() == trackId }
        }

    private suspend fun resolveCloudSearchMatch(
        trackId: String,
        track: NativeTrack,
        allowSameId: Boolean = true,
    ): NativeTrack? {
        val cacheKey = "$trackId:${if (allowSameId) "any" else "other"}"
        synchronized(cloudSearchMatchLock) {
            if (cloudSearchMatchCache.containsKey(cacheKey)) return cloudSearchMatchCache[cacheKey]
        }
        val query = buildCloudMatchQuery(track) ?: return null
        val hits = safe({ bridge.neteaseSearch(query, 10) }, { fallback.searchTracks(query, 10) })
        val matched = hits
            .asSequence()
            .filter { candidate -> allowSameId || candidate.neteaseId?.toString() != trackId }
            .mapNotNull { candidate ->
                cloudMatchScore(track, candidate)?.let { score -> score to candidate }
            }
            .maxByOrNull { it.first }
            ?.second
        DiagnosticsLogStore.record(
            area = "library",
            event = if (matched != null) "cloud_track_match_found" else "cloud_track_match_missing",
            fields = mapOf(
                "trackId" to trackId,
                "query" to query,
                "title" to track.title,
                "artist" to track.artist,
                "allowSameId" to allowSameId,
                "matchedId" to matched?.neteaseId,
                "matchedTitle" to matched?.title,
                "matchedArtist" to matched?.artist,
            ),
        )
        if (matched != null) {
            synchronized(cloudSearchMatchLock) { cloudSearchMatchCache[cacheKey] = matched }
        }
        return matched
    }

    private fun buildCloudMatchQuery(track: NativeTrack): String? {
        val title = track.title
            .takeIf { !isUnknownValue(it) }
            ?.let(::cleanCloudSearchTerm)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = track.artist
            .takeIf { !isUnknownValue(it) }
            ?.let(::cleanCloudSearchTerm)
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull(title, artist).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun cloudMatchScore(source: NativeTrack, candidate: NativeTrack): Int? {
        val sourceTitle = normalizeTrackText(source.title)
        val candidateTitle = normalizeTrackText(candidate.title)
        if (sourceTitle.isBlank() || candidateTitle.isBlank()) return null
        val titleScore = when {
            sourceTitle == candidateTitle -> 100
            sourceTitle.length >= 2 && candidateTitle.contains(sourceTitle) -> 82
            candidateTitle.length >= 2 && sourceTitle.contains(candidateTitle) -> 82
            else -> return null
        }

        val sourceArtists = artistKeys(source.artist)
        val candidateArtists = artistKeys(candidate.artist)
        val artistMatches = sourceArtists.isNotEmpty() &&
            candidateArtists.isNotEmpty() &&
            sourceArtists.any { sourceArtist ->
                candidateArtists.any { candidateArtist ->
                    sourceArtist == candidateArtist ||
                        sourceArtist.contains(candidateArtist) ||
                        candidateArtist.contains(sourceArtist)
                }
            }
        val titleContainsCandidateArtist = candidateArtists.any { sourceTitle.contains(it) }
        if (sourceArtists.isNotEmpty() && !artistMatches && !titleContainsCandidateArtist) return null

        val durationScore = if (
            source.durationMs > 0L &&
            candidate.durationMs > 0L &&
            kotlin.math.abs(source.durationMs - candidate.durationMs) <= 3_000L
        ) {
            8
        } else {
            0
        }
        return titleScore + (if (artistMatches || titleContainsCandidateArtist) 20 else 0) + durationScore
    }

    private fun normalizeTrackText(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\.(mp3|flac|wav|m4a|aac|ogg)$"), "")
            .replace(Regex("[（(【\\[].*?[）)】\\]]"), "")
            .replace(Regex("[\\s_\\-—–·•《》<>\"'“”‘’，,。.!！?？:：/\\\\]+"), "")
            .trim()

    private fun cleanCloudSearchTerm(value: String): String =
        value
            .trim()
            .replace(Regex("\\.(mp3|flac|wav|m4a|aac|ogg)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_\\-—–]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeLyricPlaceholder(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}，。！？、；：,.!?;:]+"), "")
            .trim()

    private fun artistKeys(value: String): List<String> =
        value
            .takeIf { !isUnknownValue(it) }
            ?.split(Regex("[,，、/&＋+;；\\s]+"))
            ?.map(::normalizeTrackText)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

    private fun isUnknownValue(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.isBlank() ||
            normalized == "unknown artist" ||
            normalized == "unknown track" ||
            normalized == "unknown album" ||
            normalized == "未知歌手" ||
            normalized == "未知曲目" ||
            normalized == "未知专辑"
    }

    override suspend fun likeSong(id: Long, like: Boolean) {
        // 写操作不能走 safe()：那个 helper 会把所有非 Cancellation 异常吞掉去跑 fallback
        // (EmptyPipoRepository.likeSong = Unit) —— 结果就是 AI 说"加心"接口失败时
        // 上层 runCatching 永远看到 Success，没反馈、没诊断，用户感知就是"没反应"。
        // 改成异常透传给 NativeAiPet 的 runCatching 触发失败消息，并打 diagnostic 让日志能看到。
        try {
            bridge.neteaseLikeSong(id, like)
            DiagnosticsLogStore.record(
                area = "library",
                event = "like_song_ok",
                fields = mapOf("id" to id, "like" to like),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "library",
                event = "like_song_failed",
                fields = mapOf(
                    "id" to id,
                    "like" to like,
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            throw e
        }
    }

    override suspend fun playlistModifyTracks(
        playlistId: Long,
        op: String,
        trackIds: List<Long>,
    ) {
        // 同 likeSong：写操作要让失败可见 —— 不再用 safe 吞错。
        try {
            bridge.neteasePlaylistModifyTracks(playlistId, op, trackIds)
            DiagnosticsLogStore.record(
                area = "library",
                event = "playlist_modify_ok",
                fields = mapOf("playlistId" to playlistId, "op" to op, "count" to trackIds.size),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogStore.record(
                area = "library",
                event = "playlist_modify_failed",
                fields = mapOf(
                    "playlistId" to playlistId,
                    "op" to op,
                    "count" to trackIds.size,
                    "errorType" to e::class.java.simpleName,
                    "message" to e.message,
                ),
            )
            throw e
        }
    }

    override suspend fun updateSettings(settings: NativeSettings) {
        settingsStore?.update(settings) ?: fallback.updateSettings(settings)
    }

    override suspend fun refreshAudioCacheStats() {
        val bridgeStats = safe(
            { bridge.audioCacheStats() },
            { AudioCacheStats(totalBytes = 0, count = 0, maxBytes = 2L * 1024L * 1024L * 1024L) },
        )
        val mediaStats = appContext?.let { PipoMediaCache.stats(it) }
        audioCacheStatsState.value = if (mediaStats == null) {
            bridgeStats
        } else {
            AudioCacheStats(
                totalBytes = bridgeStats.totalBytes + mediaStats.totalBytes,
                count = bridgeStats.count + mediaStats.count,
                maxBytes = bridgeStats.maxBytes + mediaStats.maxBytes,
            )
        }
    }

    override suspend fun setCacheMaxMb(mb: Long) {
        val perCacheMb = (mb / 2L).coerceAtLeast(64L)
        appContext?.let { PipoMediaCache.setMaxBytes(it, perCacheMb * 1024L * 1024L) }
        safe({ bridge.audioCacheSetMaxMb(perCacheMb) }, { Unit })
        refreshAudioCacheStats()
    }

    override suspend fun clearAudioCache() {
        appContext?.let { PipoMediaCache.clear(it) }
        safe({ bridge.audioCacheClear() }, { Unit })
        refreshAudioCacheStats()
    }

    override suspend fun audioFeatures(trackId: Long, url: String, cacheBytes: Boolean): AudioFeatures {
        return bridge.audioGetFeatures(trackId, url, cacheBytes)
    }

    override suspend fun setAiProvider(providerId: String) {
        safe({ bridge.aiSetProvider(providerId) }, { Unit })
        refreshAiConfig()
    }

    override suspend fun refreshAiConfig() {
        aiConfigState.value = safe(
            { bridge.aiGetConfig() },
            {
                AiConfigView(
                    activeProvider = "deepseek",
                    providers = listOf(
                        AiProviderView("deepseek", "DeepSeek", false, null, "deepseek-chat", "https://api.deepseek.com"),
                        AiProviderView("openai", "OpenAI", false, null, "gpt-5.5", "https://api.openai.com/v1"),
                        AiProviderView("xiaomi-mimo", "MiMo", false, null, "mimo-vl", "https://platform.xiaomimimo.com"),
                    ),
                )
            },
        )
    }

    override suspend fun aiListModels(providerId: String): List<ModelOption> {
        return safe({ bridge.aiListModels(providerId) }, { fallback.aiListModels(providerId) })
    }

    override suspend fun aiSetApiKey(providerId: String, key: String) {
        safe({ bridge.aiSetApiKey(providerId, key) }, { Unit })
        // 立即从 bridge 拉一次最新 config，让 UI 看到 hasKey / keyPreview 更新
        refreshAiConfig()
    }

    override suspend fun aiClearApiKey(providerId: String) {
        safe({ bridge.aiClearApiKey(providerId) }, { Unit })
    }

    override suspend fun aiSetModel(providerId: String, model: String) {
        safe({ bridge.aiSetModel(providerId, model) }, { Unit })
    }

    override suspend fun aiPing(): String {
        return safe({ bridge.aiPing() }, { "AI bridge unavailable." })
    }

    override suspend fun aiChat(
        system: String?,
        user: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        return bridgeAiCall("ai_chat") { bridge.aiChat(system, user, temperature, maxTokens) }
    }

    override suspend fun aiChatTools(
        messagesJson: String,
        toolsJson: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        return bridgeAiCall("ai_chat_tools") {
            bridge.aiChatTools(messagesJson, toolsJson, temperature, maxTokens)
        }
    }

    override suspend fun aiEmbed(inputs: List<String>): List<FloatArray> {
        return safe({ bridge.aiEmbed(inputs) }, { fallback.aiEmbed(inputs) })
    }

    private fun clearAccountCaches() {
        playlistState.value = emptyList()
        cloudTracksState.value = emptyList()
        synchronized(tracksCacheLock) {
            tracksMemoryCache.clear()
        }
        playlistCache?.clear()
        cachedUserId = null
        cacheStale = false
    }

    private suspend fun <T> safe(call: suspend () -> T, fallbackCall: suspend () -> T): T {
        return try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallbackCall()
        }
    }

    /** AI bridge 失败不能退成空字符串/演示结果，否则 Agent 会误判为模型正常返回。 */
    private suspend fun <T> bridgeAiCall(stage: String, call: suspend () -> T): T {
        val startedAt = System.currentTimeMillis()
        return try {
            call().also {
                DiagnosticsLogStore.record(
                    area = "ai_agent",
                    event = "bridge_ai_stage",
                    fields = mapOf("stage" to stage, "elapsedMs" to (System.currentTimeMillis() - startedAt), "success" to true),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticsLogStore.record(
                area = "ai_agent",
                event = "bridge_ai_stage",
                fields = mapOf(
                    "stage" to stage,
                    "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    "success" to false,
                    "errorType" to e::class.java.simpleName,
                    "providerError" to Regex("\\b[45]\\d\\d\\b").find(e.message.orEmpty())?.value.orEmpty(),
                ),
            )
            throw e
        }
    }

}

/**
 * "我的网盘" 在 tracksMemoryCache / PlaylistCacheStore 里复用的 sentinel id。
 * 用负数永远跟真实 NetEase playlistId（>0）不冲突。提到顶层让 UI 层不用依赖
 * RustBridgeRepository 具体实现，直接 `import app.pipo.nativeapp.data.CLOUD_DISK_PLAYLIST_ID`。
 */
const val CLOUD_DISK_PLAYLIST_ID: Long = -1L

private const val CLOUD_COVER_MATCH_SCAN_LIMIT = 8

private val NO_LYRIC_PLACEHOLDERS = setOf(
    "暂无歌词",
    "暂无歌词敬请期待",
    "此歌曲暂无歌词",
    "此歌曲为没有填词的纯音乐请欣赏",
    "纯音乐请欣赏",
    "instrumental",
    "nolyric",
    "nolyrics",
)

interface RustPipoBridge {
    suspend fun neteaseAccount(): PipoAccount?
    suspend fun neteaseLogout()
    suspend fun neteaseUserPlaylists(userId: Long): List<PipoPlaylist>
    suspend fun neteasePlaylistTracks(playlistId: Long): List<NativeTrack>
    /** 拉用户网盘里全部上传歌曲。和正常歌单一样可以喂给 tracksForPlaylist 用。 */
    suspend fun neteaseUserCloudTracks(): List<NativeTrack>
    suspend fun neteaseSearch(query: String, limit: Int): List<NativeTrack>
    suspend fun neteaseQrStart(): QrLoginStart
    suspend fun neteaseQrCheck(key: String): QrLoginStatus
    suspend fun neteaseCaptchaSent(phone: String, countryCode: Int): CaptchaSentStatus
    suspend fun neteaseCaptchaVerify(phone: String, captcha: String, countryCode: Int): CaptchaSentStatus
    suspend fun neteasePhoneLogin(phone: String, captcha: String, countryCode: Int): PhoneLoginStatus
    suspend fun neteaseSongUrls(ids: List<Long>, level: String): List<NativeSongUrl>
    suspend fun neteaseSongLyric(trackId: String): List<PipoLyricLine>
    suspend fun neteaseCloudLyric(songId: Long, userId: Long): List<PipoLyricLine>
    suspend fun neteaseLikeSong(id: Long, like: Boolean)
    suspend fun neteasePlaylistModifyTracks(playlistId: Long, op: String, trackIds: List<Long>)
    suspend fun audioCacheStats(): AudioCacheStats
    suspend fun audioCacheSetMaxMb(mb: Long)
    suspend fun audioCacheClear()
    suspend fun audioGetFeatures(trackId: Long, url: String, cacheBytes: Boolean): AudioFeatures
    suspend fun aiSetProvider(providerId: String)
    suspend fun aiGetConfig(): AiConfigView
    suspend fun aiListModels(providerId: String): List<ModelOption>
    suspend fun aiSetApiKey(providerId: String, key: String)
    suspend fun aiClearApiKey(providerId: String)
    suspend fun aiSetModel(providerId: String, model: String)
    suspend fun aiPing(): String
    suspend fun aiChat(system: String?, user: String, temperature: Float?, maxTokens: Int?): String
    suspend fun aiChatTools(messagesJson: String, toolsJson: String, temperature: Float?, maxTokens: Int?): String
    suspend fun aiEmbed(inputs: List<String>): List<FloatArray>
}
