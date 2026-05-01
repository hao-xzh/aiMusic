package app.pipo.nativeapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 空实现 —— JNI bridge 不可用 / 启动尚未拿到 account 时使用。
 *
 * 镜像 React 端 list = { kind: "loading" } / "unauth" / "error" 的语义：
 *   - 没账号 → account/playlists 为空
 *   - 没分析过 → tasteProfile/distillState 全 0
 *   - 没设置过 AI key → providers 列表为空
 *
 * 不再有任何"虚构数据"——所有页面在拿到真实数据之前一律走 React 端的"空状态"分支。
 */
class EmptyPipoRepository : PipoRepository {
    private val accountState = MutableStateFlow<PipoAccount?>(null)
    private val playlistState = MutableStateFlow<List<PipoPlaylist>>(emptyList())
    private val distillStateValue = MutableStateFlow(
        DistillState(sourceCount = 0, candidateCount = 0, smoothness = 0f, aiJudgedCount = 0)
    )
    private val settingsState = MutableStateFlow(NativeSettings())
    private val audioCacheStatsState = MutableStateFlow(
        AudioCacheStats(totalBytes = 0, count = 0, maxBytes = 2L * 1024L * 1024L * 1024L)
    )
    // React tauri.ts 里 ProviderId = "deepseek" | "openai" | "xiaomi-mimo"
    // —— 这套是后端固定支持，UI 永远显示这 3 个，hasKey 由用户填写状态决定
    private val aiConfigState = MutableStateFlow(
        AiConfigView(
            activeProvider = "deepseek",
            providers = listOf(
                AiProviderView("deepseek", "DeepSeek", false, null, "deepseek-chat", "https://api.deepseek.com"),
                AiProviderView("openai", "OpenAI", false, null, "gpt-4.1-mini", "https://api.openai.com/v1"),
                AiProviderView("xiaomi-mimo", "MiMo", false, null, "mimo-vl", "https://api.xiaomi.com"),
            ),
        )
    )

    override val account: Flow<PipoAccount?> = accountState.asStateFlow()
    override val playlists: Flow<List<PipoPlaylist>> = playlistState.asStateFlow()
    override val distillState: Flow<DistillState> = distillStateValue.asStateFlow()
    override val settings: Flow<NativeSettings> = settingsState.asStateFlow()
    override val audioCacheStats: Flow<AudioCacheStats> = audioCacheStatsState.asStateFlow()
    override val aiConfig: Flow<AiConfigView> = aiConfigState.asStateFlow()

    override suspend fun refreshAccount() = Unit
    override suspend fun startQrLogin(): QrLoginStart = QrLoginStart(key = "", qrContent = "")
    override suspend fun checkQrLogin(key: String): QrLoginStatus =
        QrLoginStatus(code = -1, message = "Bridge unavailable")

    override suspend fun sendPhoneCaptcha(phone: String, countryCode: Int): CaptchaSentStatus =
        CaptchaSentStatus(code = -1, message = "Bridge unavailable")

    override suspend fun loginWithPhone(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): PhoneLoginStatus = PhoneLoginStatus(code = -1, message = "Bridge unavailable")

    override suspend fun refreshPlaylists() = Unit
    override suspend fun tracksForPlaylist(playlistId: Long): List<NativeTrack> = emptyList()
    override suspend fun searchTracks(query: String, limit: Int): List<NativeTrack> = emptyList()
    override suspend fun songUrls(ids: List<Long>, level: String): List<NativeSongUrl> =
        ids.map { NativeSongUrl(id = it, url = null, bitrate = 0, sizeBytes = 0) }

    override suspend fun lyricsForTrack(trackId: String): List<PipoLyricLine> = emptyList()

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

    override suspend fun audioFeatures(
        trackId: Long,
        url: String,
        cacheBytes: Boolean,
    ): AudioFeatures = AudioFeatures(
        trackId = trackId,
        durationS = 0.0,
        bpm = null,
        bpmConfidence = 0.0,
        rmsDb = 0.0,
        peakDb = 0.0,
        dynamicRangeDb = 0.0,
        introEnergy = 0.0,
        outroEnergy = 0.0,
        spectralCentroidHz = 0.0,
        headSilenceS = 0.0,
        tailSilenceS = 0.0,
    )

    override suspend fun refreshAiConfig() = Unit
    override suspend fun setAiProvider(providerId: String) {
        aiConfigState.value = aiConfigState.value.copy(activeProvider = providerId)
    }
    override suspend fun aiListModels(providerId: String): List<ModelOption> = emptyList()
    override suspend fun aiSetApiKey(providerId: String, key: String) {
        // 即使 JNI bridge 不在，也把 hasKey / keyPreview 状态在本地 toggle，
        // 让 UI 切到"已填 key"的视觉状态。真正的 key 持久化在 bridge 端。
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map { p ->
                if (p.id == providerId) p.copy(
                    hasKey = key.isNotBlank(),
                    keyPreview = if (key.isNotBlank()) "${key.take(4)}…" else null,
                ) else p
            },
        )
    }
    override suspend fun aiClearApiKey(providerId: String) {
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map { p ->
                if (p.id == providerId) p.copy(hasKey = false, keyPreview = null) else p
            },
        )
    }
    override suspend fun aiSetModel(providerId: String, model: String) {
        aiConfigState.value = aiConfigState.value.copy(
            providers = aiConfigState.value.providers.map { p ->
                if (p.id == providerId) p.copy(model = model) else p
            },
        )
    }
    override suspend fun aiPing(): String = ""
    override suspend fun aiChat(
        system: String?,
        user: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String = ""
    override suspend fun aiEmbed(inputs: List<String>): List<FloatArray> = emptyList()
}
