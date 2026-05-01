package app.pipo.nativeapp.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.playback.PipoMediaCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * Repository implementation target for the full native build.
 *
 * Repository implementation backed by the Rust/JNI bridge. The demo repository
 * is kept only as a crash-safe fallback while local machines may not have the
 * native library packaged yet.
 *
 * - netease_qr_start / netease_qr_check
 * - netease_account / netease_user_playlists / netease_playlist_detail
 * - netease_song_urls / netease_song_lyric
 * - audio_cache_* / audio_get_features
 * - ai_get_config / ai_set_provider / ai_set_api_key / ai_set_model
 */
@OptIn(UnstableApi::class)
class RustBridgeRepository(
    private val bridge: RustPipoBridge,
    private val appContext: Context? = null,
    private val fallback: PipoRepository = EmptyPipoRepository(),
) : PipoRepository {
    private val settingsStore = appContext?.let(::LocalSettingsStore)
    private val playlistCache = appContext?.let(::PlaylistCacheStore)
    private val accountState = MutableStateFlow<PipoAccount?>(null)
    private val playlistState = MutableStateFlow<List<PipoPlaylist>>(emptyList())
    private val audioCacheStatsState = MutableStateFlow(AudioCacheStats(0, 0, 0))
    private val aiConfigState = MutableStateFlow(AiConfigView(activeProvider = "", providers = emptyList()))

    /** 当前缓存快照是否过期 —— refreshPlaylists 决定要不要后台 revalidate */
    @Volatile
    private var cacheStale: Boolean = false

    /** 上次缓存对应的 userId —— refreshAccount 拿到当前 userId 后比对，不一致就清缓存换账号 */
    @Volatile
    private var cachedUserId: Long? = null

    /** 曲目内存缓存：playlistId → tracks。冷启动 init 时灌入上次的快照，运行时累加 */
    private val tracksMemoryCache = mutableMapOf<Long, List<NativeTrack>>()

    init {
        // 冷启动 sync 读盘：把上次的 playlist 列表 + 已知 tracks 灌进 in-memory state。
        // 这样 PipoApplication.onCreate 装 Repository 之后，Compose 第一次 collectAsState
        // 直接拿到非空 list，不再有"空白几秒等网"的尴尬。
        val snap = playlistCache?.load()
        if (snap != null) {
            // 不在这里反推 accountState —— 实际 accountState 仍要靠 refreshAccount 走网拿 cookie
            // 验证；只是把上一次的 playlists 显示出来当作 stale-while-revalidate
            playlistState.value = snap.playlists
            tracksMemoryCache.putAll(snap.tracks)
            cacheStale = snap.isStale
            cachedUserId = snap.userId
        }
    }

    override val account: Flow<PipoAccount?> = accountState.asStateFlow()
    override val playlists: Flow<List<PipoPlaylist>> = playlistState.asStateFlow()
    override val distillState: Flow<DistillState> = fallback.distillState
    override val settings: Flow<NativeSettings> = settingsStore?.settings ?: fallback.settings
    override val audioCacheStats: Flow<AudioCacheStats> = audioCacheStatsState.asStateFlow()
    override val aiConfig: Flow<AiConfigView> = aiConfigState.asStateFlow()

    override suspend fun refreshAccount() {
        val acc = safe({ bridge.neteaseAccount() }, { null })
        accountState.value = acc
        // 换账号 / 退登检查：当前 userId 跟上次缓存不匹配 → 清掉旧用户的歌单 + tracks
        val newUserId = acc?.userId
        val oldUserId = cachedUserId
        if (newUserId != null && oldUserId != null && newUserId != oldUserId) {
            playlistState.value = emptyList()
            tracksMemoryCache.clear()
            playlistCache?.clear()
            cachedUserId = null
            cacheStale = false
        }
    }

    override suspend fun startQrLogin(): QrLoginStart {
        return safe({ bridge.neteaseQrStart() }, { fallback.startQrLogin() })
    }

    override suspend fun checkQrLogin(key: String): QrLoginStatus {
        return safe({ bridge.neteaseQrCheck(key) }, { QrLoginStatus(code = -1, message = "Login bridge unavailable") })
    }

    override suspend fun sendPhoneCaptcha(phone: String, countryCode: Int): CaptchaSentStatus {
        return safe(
            { bridge.neteaseCaptchaSent(phone, countryCode) },
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
        // stale-while-revalidate：
        //   - 已有非空缓存 + 不过期 → 跳过网络
        //   - 已有非空缓存 + 过期 → 立刻显示旧的，同时后台拉新覆盖
        //   - 无缓存 → 阻塞拉网
        val haveMemory = playlistState.value.isNotEmpty()
        if (haveMemory && !cacheStale) return

        val fresh = safe(
            {
                val account = accountState.value ?: bridge.neteaseAccount().also { accountState.value = it }
                account?.let { bridge.neteaseUserPlaylists(it.userId) }.orEmpty()
            },
            { emptyList() },
        )
        if (fresh.isNotEmpty()) {
            playlistState.value = fresh
            cacheStale = false
            val uid = accountState.value?.userId
            if (uid != null) {
                cachedUserId = uid
                playlistCache?.save(uid, fresh, tracksMemoryCache)
            }
        }
    }

    override suspend fun tracksForPlaylist(playlistId: Long): List<NativeTrack> {
        tracksMemoryCache[playlistId]?.let { return it }
        val fresh = safe(
            { bridge.neteasePlaylistTracks(playlistId) },
            { fallback.tracksForPlaylist(playlistId) },
        )
        if (fresh.isNotEmpty()) {
            tracksMemoryCache[playlistId] = fresh
            // 拉到新一张歌单 tracks 后，把整个 in-memory cache 重新落盘
            // （save 内部异步 IO，不阻塞调用方）
            val uid = accountState.value?.userId ?: cachedUserId
            val playlists = playlistState.value
            if (uid != null && playlists.isNotEmpty()) {
                playlistCache?.save(uid, playlists, tracksMemoryCache)
            }
        }
        return fresh
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NativeTrack> {
        return safe({ bridge.neteaseSearch(query, limit) }, { fallback.searchTracks(query, limit) })
    }

    override suspend fun songUrls(ids: List<Long>, level: String): List<NativeSongUrl> {
        return safe({ bridge.neteaseSongUrls(ids, level) }, { fallback.songUrls(ids, level) })
    }

    override suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine> {
        return safe({ bridge.neteaseSongLyric(trackId) }, { fallback.lyricsForTrack(trackId) })
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
                maxBytes = max(bridgeStats.maxBytes, mediaStats.maxBytes),
            )
        }
    }

    override suspend fun setCacheMaxMb(mb: Long) {
        appContext?.let { PipoMediaCache.setMaxBytes(it, mb * 1024L * 1024L) }
        safe({ bridge.audioCacheSetMaxMb(mb) }, { Unit })
        refreshAudioCacheStats()
    }

    override suspend fun clearAudioCache() {
        appContext?.let { PipoMediaCache.clear(it) }
        safe({ bridge.audioCacheClear() }, { Unit })
        refreshAudioCacheStats()
    }

    override suspend fun audioFeatures(trackId: Long, url: String, cacheBytes: Boolean): AudioFeatures {
        return safe({ bridge.audioGetFeatures(trackId, url, cacheBytes) }, { fallback.audioFeatures(trackId, url, cacheBytes) })
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
                        AiProviderView("openai", "OpenAI", false, null, "gpt-4.1-mini", "https://api.openai.com/v1"),
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
        return safe({ bridge.aiChat(system, user, temperature, maxTokens) }, { fallback.aiChat(system, user, temperature, maxTokens) })
    }

    override suspend fun aiEmbed(inputs: List<String>): List<FloatArray> {
        return safe({ bridge.aiEmbed(inputs) }, { fallback.aiEmbed(inputs) })
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
}

interface RustPipoBridge {
    suspend fun neteaseAccount(): PipoAccount?
    suspend fun neteaseUserPlaylists(userId: Long): List<PipoPlaylist>
    suspend fun neteasePlaylistTracks(playlistId: Long): List<NativeTrack>
    suspend fun neteaseSearch(query: String, limit: Int): List<NativeTrack>
    suspend fun neteaseQrStart(): QrLoginStart
    suspend fun neteaseQrCheck(key: String): QrLoginStatus
    suspend fun neteaseCaptchaSent(phone: String, countryCode: Int): CaptchaSentStatus
    suspend fun neteasePhoneLogin(phone: String, captcha: String, countryCode: Int): PhoneLoginStatus
    suspend fun neteaseSongUrls(ids: List<Long>, level: String): List<NativeSongUrl>
    suspend fun neteaseSongLyric(trackId: String): List<PipoLyricLine>
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
    suspend fun aiEmbed(inputs: List<String>): List<FloatArray>
}
