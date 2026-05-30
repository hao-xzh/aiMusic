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

enum class PipoLyricTiming {
    Line,
    Word,
}

enum class PipoLyricRole {
    Primary,
    Companion,
    Translation,
}

/**
 * 行级横向对齐 —— 主要用于 AMLL 对唱：ttm:agent="v1" 行靠左（Start），
 * 其它演唱者（v2/v3...）整行靠右（End），呈现 Apple Music / AMLL 官方播放器的对唱排版。
 * 默认 Start，YRC / LRC / 主唱内容均按原来左对齐展示。
 */
enum class PipoLyricAlignment {
    Start,
    End,
}

data class PipoLyricLine(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val chars: List<PipoLyricChar> = emptyList(),
    val timing: PipoLyricTiming = if (chars.isEmpty()) PipoLyricTiming.Line else PipoLyricTiming.Word,
    val companionLines: List<PipoLyricLine> = emptyList(),
    val role: PipoLyricRole = PipoLyricRole.Primary,
    val alignment: PipoLyricAlignment = PipoLyricAlignment.Start,
)

data class PipoLyricChar(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val timingParts: List<PipoLyricTimingPart> = emptyList(),
)

data class PipoLyricTimingPart(
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
    val hideAiPetOrb: Boolean = true,
    val lyricTranslation: Boolean = false,
    val aiNarration: Boolean = false,
    val playbackMode: String = "PlaylistLoop",
    val userFacts: String = "",
    val personaId: String = PetPersona.DEFAULT.id,
)

interface PipoRepository {
    val account: Flow<PipoAccount?>
    val playlists: Flow<List<PipoPlaylist>>
    /**
     * "我的网盘"曲目 Flow。冷启动时从磁盘 cache 同步恢复；[cloudDiskTracks] 加载完会 emit
     * 新值。让 UI 把 cover / count 派生过来，跨 DistillLibrary 重挂载不丢，对齐正常
     * 歌单的体验（其 cover 也是从 [playlists] Flow 拿到）。
     */
    val cloudTracks: Flow<List<NativeTrack>>
    val distillState: Flow<DistillState>
    val settings: Flow<NativeSettings>
    val audioCacheStats: Flow<AudioCacheStats>
    val aiConfig: Flow<AiConfigView>

    suspend fun refreshAccount()
    suspend fun logout()
    suspend fun startQrLogin(): QrLoginStart
    suspend fun checkQrLogin(key: String): QrLoginStatus
    suspend fun sendPhoneCaptcha(phone: String, countryCode: Int = 86): CaptchaSentStatus
    suspend fun loginWithPhone(phone: String, captcha: String, countryCode: Int = 86): PhoneLoginStatus
    suspend fun refreshPlaylists()
    suspend fun tracksForPlaylist(playlistId: Long, forceRefresh: Boolean = false): List<NativeTrack>
    /**
     * 加载"我的网盘"全部上传歌曲。和 [tracksForPlaylist] 行为对齐：
     * 第一次命中网，后续走内存 / 磁盘缓存；forceRefresh=true 触发重抓。
     * 加载完会 emit 到 [cloudTracks] Flow 让 cover-flow tile 跨 UI 重挂载持久。
     */
    suspend fun cloudDiskTracks(forceRefresh: Boolean = false): List<NativeTrack>
    /**
     * 同步读已加载到内存的歌单 / 网盘曲目（命中时返回，否则 null），用于 DistillLibrary
     * 重新挂载时把 tracks 初始值从 cache 直接灌进去，避免 `loading=true && tracks.isEmpty()`
     * 触发"加载中…"那一帧 strobe。对齐"正常歌单也不要闪"。
     */
    fun cachedTracksFor(playlistId: Long): List<NativeTrack>?
    suspend fun searchTracks(query: String, limit: Int = 30): List<NativeTrack>
    suspend fun songUrls(ids: List<Long>, level: String = "lossless"): List<NativeSongUrl>
    suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine>
    /** 收藏 / 取消收藏单曲（写到网易云"我喜欢的音乐"红心歌单） */
    suspend fun likeSong(id: Long, like: Boolean)
    /** 歌单加 / 删歌；op = "add" | "del" */
    suspend fun playlistModifyTracks(playlistId: Long, op: String, trackIds: List<Long>)
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
     * 原生 tool-calling 版 chat。Kotlin 侧驱动多轮工具循环，本方法只发一轮。
     * [messagesJson] / [toolsJson] 是 OpenAI 兼容格式的 JSON 数组文本（schema 归调用方所有）。
     * 返回 assistant message 的原始 JSON 文本（含 content + tool_calls，可能 content 为 null）。
     */
    suspend fun aiChatTools(messagesJson: String, toolsJson: String, temperature: Float? = null, maxTokens: Int? = null): String
    /**
     * 拿一组字符串的 embedding 向量。
     * provider 必须支持 embedding（当前 OpenAI text-embedding-3-small 走这条路），
     * 不支持时抛错；调用方应该预先 try/fallback 到 lexical。
     */
    suspend fun aiEmbed(inputs: List<String>): List<FloatArray>
}
