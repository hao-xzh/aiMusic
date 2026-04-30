package app.pipo.nativeapp.data

package app.pipo.nativeapp.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.model.NativeTrack
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
    private val fallback: PipoRepository = DemoPipoRepository(),
) : PipoRepository {
    private val settingsStore = appContext?.let(::LocalSettingsStore)
    private val accountState = MutableStateFlow<PipoAccount?>(null)
    private val playlistState = MutableStateFlow<List<PipoPlaylist>>(emptyList())
    private val audioCacheStatsState = MutableStateFlow(AudioCacheStats(0, 0, 0))
    private val aiConfigState = MutableStateFlow(AiConfigView(activeProvider = "", providers = emptyList()))

    override val account: Flow<PipoAccount?> = accountState.asStateFlow()
    override val playlists: Flow<List<PipoPlaylist>> = playlistState.asStateFlow()
    override val tasteProfile: Flow<TasteProfile> = fallback.tasteProfile
    override val distillState: Flow<DistillState> = fallback.distillState
    override val settings: Flow<NativeSettings> = settingsStore?.settings ?: fallback.settings
    override val audioCacheStats: Flow<AudioCacheStats> = audioCacheStatsState.asStateFlow()
    override val aiConfig: Flow<AiConfigView> = aiConfigState.asStateFlow()

    override suspend fun refreshAccount() {
        accountState.value = safe({ bridge.neteaseAccount() }, { null })
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
        playlistState.value = safe(
            {
                val account = accountState.value ?: bridge.neteaseAccount().also { accountState.value = it }
                account?.let { bridge.neteaseUserPlaylists(it.userId) }.orEmpty()
            },
            { emptyList() },
        )
    }

    override suspend fun tracksForPlaylist(playlistId: Long): List<NativeTrack> {
        return safe({ bridge.neteasePlaylistTracks(playlistId) }, { fallback.tracksForPlaylist(playlistId) })
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
}
