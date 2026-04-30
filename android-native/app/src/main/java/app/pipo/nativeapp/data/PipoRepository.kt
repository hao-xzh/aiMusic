package app.pipo.nativeapp.data

import app.pipo.nativeapp.model.DemoTracks
import app.pipo.nativeapp.model.NativeTrack
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

data class TasteProfile(
    val energy: Float,
    val warmth: Float,
    val novelty: Float,
    val flow: Float,
    val tags: List<String>,
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
    val tasteProfile: Flow<TasteProfile>
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
}

class DemoPipoRepository : PipoRepository {
    private val accountState = MutableStateFlow<PipoAccount?>(null)
    private val playlistState = MutableStateFlow(
        listOf(
            PipoPlaylist(id = 1, name = "Pipo Radio", trackCount = DemoTracks.queue.size, userId = 0),
            PipoPlaylist(id = 2, name = "Work Flow", trackCount = 42, userId = 0),
            PipoPlaylist(id = 3, name = "Late Night", trackCount = 28, userId = 0),
        ),
    )
    private val tasteProfileState = MutableStateFlow(
        TasteProfile(
            energy = 0.76f,
            warmth = 0.68f,
            novelty = 0.42f,
            flow = 0.83f,
            tags = listOf("dream pop", "city pop", "ambient", "mandarin indie"),
        ),
    )
    private val distillStateValue = MutableStateFlow(
        DistillState(
            sourceCount = 7,
            candidateCount = 128,
            smoothness = 0.86f,
            aiJudgedCount = 36,
        ),
    )
    private val settingsState = MutableStateFlow(
        NativeSettings(
            hideDotPattern = false,
            workdayAutoplay = true,
            pauseDuringMeetings = true,
            lunchRelaxMode = false,
            aiNarration = true,
        ),
    )
    private val audioCacheStatsState = MutableStateFlow(
        AudioCacheStats(
            totalBytes = 320L * 1024L * 1024L,
            count = 18,
            maxBytes = 2048L * 1024L * 1024L,
        ),
    )
    private val aiConfigState = MutableStateFlow(
        AiConfigView(
            activeProvider = "deepseek",
            providers = listOf(
                AiProviderView("deepseek", "DeepSeek", true, "sk-...demo", "deepseek-chat", "https://api.deepseek.com"),
                AiProviderView("openai", "OpenAI", false, null, "gpt-4.1-mini", "https://api.openai.com/v1"),
                AiProviderView("xiaomi-mimo", "MiMo", false, null, "mimo-vl", "https://platform.xiaomimimo.com"),
            ),
        ),
    )

    override val account: Flow<PipoAccount?> = accountState.asStateFlow()
    override val playlists: Flow<List<PipoPlaylist>> = playlistState.asStateFlow()
    override val tasteProfile: Flow<TasteProfile> = tasteProfileState.asStateFlow()
    override val distillState: Flow<DistillState> = distillStateValue.asStateFlow()
    override val settings: Flow<NativeSettings> = settingsState.asStateFlow()
    override val audioCacheStats: Flow<AudioCacheStats> = audioCacheStatsState.asStateFlow()
    override val aiConfig: Flow<AiConfigView> = aiConfigState.asStateFlow()

    override suspend fun refreshAccount() {
        accountState.value = PipoAccount(userId = 0, nickname = "Pipo")
    }

    override suspend fun startQrLogin(): QrLoginStart {
        return QrLoginStart(
            key = "demo",
            qrContent = "pipo-native-demo-login",
        )
    }

    override suspend fun checkQrLogin(key: String): QrLoginStatus {
        return QrLoginStatus(code = 803, message = "Demo login ready", nickname = "Pipo")
    }

    override suspend fun sendPhoneCaptcha(phone: String, countryCode: Int): CaptchaSentStatus {
        return CaptchaSentStatus(code = 200, message = "Demo captcha sent")
    }

    override suspend fun loginWithPhone(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): PhoneLoginStatus {
        accountState.value = PipoAccount(userId = 0, nickname = "Pipo")
        return PhoneLoginStatus(code = 200, message = "Demo login ready", nickname = "Pipo")
    }

    override suspend fun refreshPlaylists() = Unit

    override suspend fun tracksForPlaylist(playlistId: Long): List<NativeTrack> {
        return DemoTracks.queue
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NativeTrack> {
        return DemoTracks.queue.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }.ifEmpty { DemoTracks.queue }.take(limit)
    }

    override suspend fun songUrls(ids: List<Long>, level: String): List<NativeSongUrl> {
        return ids.mapIndexed { index, id ->
            NativeSongUrl(
                id = id,
                url = DemoTracks.queue.getOrNull(index % DemoTracks.queue.size)?.streamUrl,
                bitrate = 320000,
                sizeBytes = 0,
            )
        }
    }

    override suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine> {
        return listOf(
            PipoLyricLine(0, 2800, "Let the next track find the room"),
            PipoLyricLine(2800, 2600, "and leave the noise outside"),
            PipoLyricLine(5400, 3200, "one sample into another"),
            PipoLyricLine(8600, 3000, "no gap, no rush, just motion"),
        )
    }

    override suspend fun updateSettings(settings: NativeSettings) {
        settingsState.value = settings
    }

    override suspend fun refreshAudioCacheStats() = Unit

    override suspend fun setCacheMaxMb(mb: Long) {
        audioCacheStatsState.value = audioCacheStatsState.value.copy(maxBytes = mb * 1024L * 1024L)
    }

    override suspend fun clearAudioCache() {
        audioCacheStatsState.value = audioCacheStatsState.value.copy(totalBytes = 0, count = 0)
    }

    override suspend fun audioFeatures(trackId: Long, url: String, cacheBytes: Boolean): AudioFeatures {
        return AudioFeatures(
            trackId = trackId,
            durationS = 240.0,
            bpm = 96.0,
            bpmConfidence = 0.72,
            rmsDb = -13.0,
            peakDb = -1.0,
            dynamicRangeDb = 12.0,
            introEnergy = 0.42,
            outroEnergy = 0.37,
            spectralCentroidHz = 2400.0,
            headSilenceS = 0.0,
            tailSilenceS = 0.0,
        )
    }

    override suspend fun setAiProvider(providerId: String) {
        aiConfigState.value = aiConfigState.value.copy(activeProvider = providerId)
    }

    override suspend fun refreshAiConfig() = Unit

    override suspend fun aiListModels(providerId: String): List<ModelOption> {
        return listOf(
            ModelOption("deepseek-chat", "DeepSeek Chat"),
            ModelOption("gpt-4.1-mini", "GPT Mini"),
        )
    }

    override suspend fun aiSetApiKey(providerId: String, key: String) {
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map {
                if (it.id == providerId) it.copy(hasKey = key.isNotBlank(), keyPreview = key.take(4) + "...") else it
            },
        )
    }

    override suspend fun aiClearApiKey(providerId: String) {
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map {
                if (it.id == providerId) it.copy(hasKey = false, keyPreview = null) else it
            },
        )
    }

    override suspend fun aiSetModel(providerId: String, model: String) {
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map {
                if (it.id == providerId) it.copy(model = model) else it
            },
        )
    }

    override suspend fun aiPing(): String {
        return "Pipo is awake."
    }

    override suspend fun aiChat(
        system: String?,
        user: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        return "Demo reply: $user"
    }
}
