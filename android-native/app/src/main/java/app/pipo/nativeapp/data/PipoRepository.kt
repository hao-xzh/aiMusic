package app.pipo.nativeapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PipoAccount(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String? = null,
)

data class PipoPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val coverUrl: String? = null,
    val userId: Long? = null,
    val updateTime: Long? = null,
)

data class QrLoginStart(
    val key: String,
    val qrContent: String,
)

data class QrLoginStatus(
    val code: Int,
    val message: String? = null,
    val nickname: String? = null,
)

data class CaptchaSentStatus(
    val code: Int,
    val message: String? = null,
)

data class PhoneLoginStatus(
    val code: Int,
    val message: String? = null,
    val nickname: String? = null,
)

data class NativeSongUrl(
    val id: Long,
    val url: String?,
    val bitrate: Int,
    val sizeBytes: Long,
)

data class AudioCacheStats(
    val totalBytes: Long,
    val count: Int,
    val maxBytes: Long,
) {
    val totalMb: Long get() = totalBytes / 1024L / 1024L
    val maxMb: Long get() = maxBytes / 1024L / 1024L
}

data class AiProviderView(
    val id: String,
    val label: String,
    val hasKey: Boolean,
    val keyPreview: String?,
    val model: String,
    val baseUrl: String,
)

data class AiConfigView(
    val activeProvider: String,
    val providers: List<AiProviderView>,
)

data class ModelOption(
    val id: String,
    val label: String,
)

data class AudioFeatures(
    val trackId: Long,
    val durationS: Double,
    val bpm: Double?,
    val bpmConfidence: Double,
    val rmsDb: Double,
    val peakDb: Double,
    val dynamicRangeDb: Double,
    val introEnergy: Double,
    val outroEnergy: Double,
    val spectralCentroidHz: Double,
    val headSilenceS: Double,
    val tailSilenceS: Double,
)

data class PipoLyricLine(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val chars: List<PipoLyricChar> = emptyList(),
)

data class PipoLyricChar(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

data class DistillState(
    val sourceCount: Int,
    val candidateCount: Int,
    val smoothness: Float,
    val aiJudgedCount: Int,
)

data class NativeSettings(
    val hideDotPattern: Boolean = false,
    val workdayAutoplay: Boolean = true,
    val pauseDuringMeetings: Boolean = true,
    val lunchRelaxMode: Boolean = false,
    val aiNarration: Boolean = true,
    val userFacts: String = "",
)

interface PipoRepository {
    val account: Flow<PipoAccount?>
    val playlists: Flow<List<PipoPlaylist>>
    val distillState: Flow<DistillState>
    val settings: Flow<NativeSettings>
    val audioCacheStats: Flow<AudioCacheStats>
    val aiConfig: Flow<AiConfigView>

    suspend fun refreshAccount()
    suspend fun startQrLogin(): QrLoginStart
    suspend fun checkQrLogin(key: String): QrLoginStatus
    suspend fun sendPhoneCaptcha(phone: String, countryCode: Int = 86): CaptchaSentStatus
    suspend fun loginWithPhone(phone: String, captcha: String, countryCode: Int = 86): PhoneLoginStatus
    suspend fun refreshPlaylists()
    suspend fun tracksForPlaylist(playlistId: Long): List<NativeTrack>
    suspend fun searchTracks(query: String, limit: Int = 30): List<NativeTrack>
    suspend fun songUrls(ids: List<Long>, level: String = "lossless"): List<NativeSongUrl>
    suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine>
    suspend fun updateSettings(settings: NativeSettings)
    suspend fun refreshAudioCacheStats()
    suspend fun setCacheMaxMb(mb: Long)
    suspend fun clearAudioCache()
    suspend fun audioFeatures(trackId: Long, url: String, cacheBytes: Boolean = true): AudioFeatures
    suspend fun refreshAiConfig()
    suspend fun setAiProvider(providerId: String)
    suspend fun aiListModels(providerId: String): List<ModelOption>
    suspend fun aiSetApiKey(providerId: String, key: String)
    suspend fun aiClearApiKey(providerId: String)
    suspend fun aiSetModel(providerId: String, model: String)
    suspend fun aiPing(): String
    suspend fun aiChat(system: String? = null, user: String, temperature: Float? = null, maxTokens: Int? = null): String
    /**
     * 拿一组字符串的 embedding 向量。
     * provider 必须支持 embedding（当前 OpenAI text-embedding-3-small 走这条路），
     * 不支持时抛错；调用方应该预先 try/fallback 到 lexical。
     */
    suspend fun aiEmbed(inputs: List<String>): List<FloatArray>
}

