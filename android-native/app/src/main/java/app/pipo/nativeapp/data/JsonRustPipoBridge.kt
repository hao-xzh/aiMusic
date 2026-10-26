package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JsonRustPipoBridge(appDataDir: String? = null) : RustPipoBridge {
    init {
        appDataDir?.let {
            callInit(it)
        }
    }

    override suspend fun neteaseAccount(): PipoAccount? {
        val raw = callRaw("netease_account")
        if (raw == "null") return null
        val o = JSONObject(raw)
        return PipoAccount(
            userId = o.getLong("userId"),
            nickname = o.getString("nickname"),
            avatarUrl = o.optStringOrNull("avatarUrl"),
            hasMusicSubscription = o.opt("hasMusicSubscription") as? Boolean,
        )
    }

    override suspend fun neteaseLogout() {
        callRaw("netease_logout")
    }

    override suspend fun neteaseUserPlaylists(userId: Long): List<PipoPlaylist> {
        val arr = callArray("netease_user_playlists", jsonObject("uid" to userId, "limit" to 1000))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PipoPlaylist(
                id = o.getLong("id"),
                name = o.getString("name"),
                trackCount = o.optInt("trackCount", 0),
                coverUrl = o.optStringOrNull("coverImgUrl"),
                userId = o.optLongOrNull("userId"),
                updateTime = o.optLongOrNull("updateTime"),
                specialType = o.optInt("specialType", 0),
            )
        }
    }

    override suspend fun neteasePlaylistSongIds(playlistId: Long): Set<Long> {
        val ids = callArray("netease_playlist_track_ids", jsonObject("id" to playlistId))
        return (0 until ids.length()).mapTo(HashSet()) { ids.getLong(it) }
    }

    override suspend fun neteasePlaylistTracks(playlistId: Long): List<NativeTrack> {
        val o = callObject("netease_playlist_detail", jsonObject("id" to playlistId))
        val tracks = o.optJSONArray("tracks") ?: return emptyList()
        val diagnostics = o.optJSONObject("hydrationDiagnostics")
        DiagnosticsLogStore.record(
            area = "library",
            event = "playlist_detail_hydration",
            fields = mapOf(
                "playlistId" to playlistId,
                "playlistName" to o.optString("name"),
                "trackCount" to o.optInt("trackCount", 0),
                "trackIdsCount" to (o.optJSONArray("trackIds")?.length() ?: 0),
                "tracksCount" to tracks.length(),
                "playlistDetailSource" to diagnostics?.optString("playlistDetailSource"),
                "playlistDetailFallbackError" to diagnostics?.optString("playlistDetailFallbackError"),
                "originalTracksCount" to diagnostics?.optInt("originalTracksCount", -1),
                "songDetailRequested" to diagnostics?.optInt("songDetailRequested", -1),
                "songDetailResolved" to diagnostics?.optInt("songDetailResolved", -1),
                "cloudByIdsRequested" to diagnostics?.optInt("cloudByIdsRequested", -1),
                "cloudByIdsReturned" to diagnostics?.optInt("cloudByIdsReturned", -1),
                "cloudByIdsResolved" to diagnostics?.optInt("cloudByIdsResolved", -1),
                "cloudByIdsError" to diagnostics?.optString("cloudByIdsError"),
                "cloudScanRequested" to diagnostics?.optInt("cloudScanRequested", -1),
                "cloudScanPages" to diagnostics?.optInt("cloudScanPages", -1),
                "cloudScanReturned" to diagnostics?.optInt("cloudScanReturned", -1),
                "cloudScanResolved" to diagnostics?.optInt("cloudScanResolved", -1),
                "cloudScanError" to diagnostics?.optString("cloudScanError"),
                "finalTracksCount" to diagnostics?.optInt("finalTracksCount", -1),
                "missingAfterHydration" to diagnostics?.optInt("missingAfterHydration", -1),
                "missingIdSample" to diagnostics?.optJSONArray("missingIdSample")?.toCompactList(),
            ),
        )
        val parsed = parseTracks(tracks)
        val expected = maxOf(o.optInt("trackCount", 0), o.optJSONArray("trackIds")?.length() ?: 0)
        check(parsed.size >= expected) { "网易云歌单曲目未完整读取（${parsed.size}/$expected），请稍后重试" }
        return parsed
    }

    override suspend fun neteaseSearch(query: String, limit: Int): List<NativeTrack> {
        val tracks = callArray("netease_search", jsonObject("query" to query, "limit" to limit))
        return parseTracks(tracks)
    }

    override suspend fun neteaseSearchPage(query: String, limit: Int, offset: Int): List<NativeTrack> {
        val tracks = callArray(
            "netease_search",
            jsonObject("query" to query, "limit" to limit, "offset" to offset),
        )
        return parseTracks(tracks)
    }

    override suspend fun neteaseDailyRecommendedTracks(): List<NativeTrack> {
        return parseTracks(callArray("netease_daily_recommended_tracks"))
    }

    override suspend fun neteasePersonalFmTracks(): List<NativeTrack> {
        return parseTracks(callArray("netease_personal_fm_tracks"))
    }

    override suspend fun neteaseSimilarTracks(trackId: Long): List<NativeTrack> {
        return parseTracks(callArray("netease_similar_tracks", jsonObject("trackId" to trackId)))
    }

    override suspend fun neteaseUserCloudTracks(): List<NativeTrack> {
        val tracks = callArray("netease_user_cloud_tracks")
        return parseTracks(tracks)
    }

    private fun parseTracks(tracks: JSONArray): List<NativeTrack> {
        return List(tracks.length()) { i ->
            val t = tracks.getJSONObject(i)
            val album = t.optJSONObject("album")
            val artists = t.optJSONArray("artists")
            NativeTrack(
                id = t.getLong("id").toString(),
                neteaseId = t.getLong("id"),
                title = t.optString("name", "Unknown track"),
                artist = artists?.let { arr ->
                    List(arr.length()) { idx -> arr.getJSONObject(idx).optString("name") }
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }.orEmpty().ifBlank { "Unknown artist" },
                album = album?.optString("name").orEmpty().ifBlank { "Unknown album" },
                artworkUrl = album?.optStringOrNull("picUrl"),
                durationMs = t.optLong("durationMs", 0L),
                streamUrl = "",
            )
        }
    }

    private fun JSONArray.toCompactList(limit: Int = 16): List<Any> {
        return List(kotlin.math.min(length(), limit)) { i -> opt(i) }
    }

    override suspend fun neteaseQrStart(): QrLoginStart {
        val o = callObject("netease_qr_start")
        return QrLoginStart(
            key = o.getString("key"),
            qrContent = o.getString("qrContent"),
        )
    }

    override suspend fun neteaseQrCheck(key: String): QrLoginStatus {
        val o = callObject("netease_qr_check", jsonObject("key" to key))
        return QrLoginStatus(
            code = o.getInt("code"),
            message = o.optStringOrNull("message"),
            nickname = o.optStringOrNull("nickname"),
        )
    }

    override suspend fun neteaseCaptchaSent(phone: String, countryCode: Int): CaptchaSentStatus {
        val o = callObject(
            "netease_captcha_sent",
            jsonObject("phone" to phone, "ctcode" to countryCode),
        )
        return CaptchaSentStatus(
            code = o.getInt("code"),
            message = o.optStringOrNull("message"),
        )
    }

    override suspend fun neteaseCaptchaVerify(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): CaptchaSentStatus {
        val o = callObject(
            "netease_captcha_verify",
            jsonObject("phone" to phone, "captcha" to captcha, "ctcode" to countryCode),
        )
        return CaptchaSentStatus(
            code = o.getInt("code"),
            message = o.optStringOrNull("message"),
        )
    }

    override suspend fun neteasePhoneLogin(
        phone: String,
        captcha: String,
        countryCode: Int,
    ): PhoneLoginStatus {
        val o = callObject(
            "netease_phone_login",
            jsonObject("phone" to phone, "captcha" to captcha, "ctcode" to countryCode),
        )
        return PhoneLoginStatus(
            code = o.getInt("code"),
            message = o.optStringOrNull("message"),
            nickname = o.optStringOrNull("nickname"),
        )
    }

    override suspend fun neteaseSongUrls(ids: List<Long>, level: String): List<NativeSongUrl> {
        val arr = callArray(
            "netease_song_urls",
            jsonObject("ids" to JSONArray(ids), "level" to level),
        )
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            NativeSongUrl(
                id = o.getLong("id"),
                url = o.optStringOrNull("url"),
                bitrate = o.optInt("br", o.optInt("bitrate", 0)),
                sizeBytes = o.optLong("size", o.optLong("sizeBytes", 0L)),
                isTrial = o.optJSONObject("freeTrialInfo") != null,
            )
        }
    }

    override suspend fun neteaseSongLyric(trackId: String): List<PipoLyricLine> {
        val raw = callObject("netease_song_lyric", jsonObject("id" to trackId.toLongOrNull()))
        return parseLyricObject(raw)
    }

    override suspend fun neteaseCloudLyric(songId: Long, userId: Long): List<PipoLyricLine> {
        val raw = callObject(
            "netease_cloud_lyric",
            jsonObject("songId" to songId, "userId" to userId),
        )
        return parseLyricObject(raw)
    }

    private fun parseLyricObject(raw: JSONObject): List<PipoLyricLine> {
        val translations = raw.optStringOrNull("translation")
            ?.let { LrcParser.parse(it) }
            .orEmpty()
        raw.optStringOrNull("yrc")?.let { yrc ->
            val parsed = YrcParser.parse(yrc)
            if (parsed.isNotEmpty()) return attachTranslationLines(parsed, translations)
        }
        val lrc = raw.optStringOrNull("lyric") ?: return emptyList()
        val splitLines = splitEmbeddedTranslationLines(
            lines = LrcParser.parse(lrc),
            translationLines = translations,
        )
        val effectiveTranslations = splitLines.embeddedTranslationLines
            .takeIf { it.isNotEmpty() }
            ?: translations
        return attachTranslationLines(splitLines.primaryLines, effectiveTranslations)
    }

    override suspend fun neteaseLikeSong(id: Long, like: Boolean) {
        callRaw("netease_like_song", jsonObject("id" to id, "like" to like))
    }

    override suspend fun neteasePlaylistCreate(name: String): Long {
        val result = callObject("netease_playlist_create", jsonObject("name" to name))
        return result.getLong("id")
    }

    override suspend fun neteasePlaylistModifyTracks(
        playlistId: Long,
        op: String,
        trackIds: List<Long>,
    ) {
        callRaw(
            "netease_playlist_modify_tracks",
            jsonObject(
                "playlistId" to playlistId,
                "op" to op,
                "trackIds" to JSONArray(trackIds),
            ),
        )
    }

    override suspend fun audioCacheClear() {
        callRaw("audio_cache_clear")
    }

    override suspend fun audioCacheStats(): AudioCacheStats {
        val o = callObject("audio_cache_stats")
        return AudioCacheStats(
            totalBytes = o.optLong("totalBytes", o.optLong("total_bytes", 0L)),
            count = o.optInt("count", 0),
            maxBytes = o.optLong("maxBytes", o.optLong("max_bytes", 0L)),
        )
    }

    override suspend fun audioCacheSetMaxMb(mb: Long) {
        callRaw("audio_cache_set_max_mb", jsonObject("mb" to mb))
    }

    override suspend fun audioGetFeatures(trackId: Long, url: String, cacheBytes: Boolean): AudioFeatures {
        val raw = try {
            withTimeout(DEFAULT_CALL_TIMEOUT_MS) {
                awaitAudioFeatures(
                    trackId = trackId,
                    args = jsonObject("trackId" to trackId, "url" to url, "cacheBytes" to cacheBytes),
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw RustBridgeException("audio_get_features", "timeout after ${DEFAULT_CALL_TIMEOUT_MS / 1000}s")
        }
        val o = JSONObject(raw)
        return AudioFeatures(
            trackId = o.getLong("trackId"),
            durationS = o.getDouble("durationS"),
            bpm = o.optDoubleOrNull("bpm"),
            bpmConfidence = o.getDouble("bpmConfidence"),
            firstBeatS = o.optDoubleOrNull("firstBeatS"),
            rmsDb = o.getDouble("rmsDb"),
            peakDb = o.getDouble("peakDb"),
            dynamicRangeDb = o.getDouble("dynamicRangeDb"),
            introEnergy = o.getDouble("introEnergy"),
            outroEnergy = o.getDouble("outroEnergy"),
            introLowEnergy = o.optDouble("introLowEnergy", 0.0),
            outroLowEnergy = o.optDouble("outroLowEnergy", 0.0),
            introVocalDensity = o.optDouble("introVocalDensity", 0.0),
            outroVocalDensity = o.optDouble("outroVocalDensity", 0.0),
            drumEntryS = o.optDoubleOrNull("drumEntryS"),
            vocalEntryS = o.optDoubleOrNull("vocalEntryS"),
            outroStartS = o.optDoubleOrNull("outroStartS"),
            spectralCentroidHz = o.getDouble("spectralCentroidHz"),
            tonalKey = if (o.has("tonalKey") && !o.isNull("tonalKey")) o.optInt("tonalKey") else null,
            tonalConfidence = o.optDouble("tonalConfidence", 0.0),
            headSilenceS = o.optDouble("headSilenceS", 0.0),
            tailSilenceS = o.optDouble("tailSilenceS", 0.0),
        )
    }

    override suspend fun aiSetProvider(providerId: String) {
        callRaw("ai_set_provider", jsonObject("provider" to providerId))
    }

    override suspend fun aiGetConfig(): AiConfigView {
        val o = callObject("ai_get_config")
        val providers = o.optJSONArray("providers") ?: JSONArray()
        return AiConfigView(
            activeProvider = o.optString("activeProvider", o.optString("active_provider", "")),
            providers = List(providers.length()) { i ->
                val p = providers.getJSONObject(i)
                AiProviderView(
                    id = p.getString("id"),
                    label = p.getString("label"),
                    hasKey = p.optBoolean("hasKey", p.optBoolean("has_key", false)),
                    keyPreview = p.optStringOrNull("keyPreview") ?: p.optStringOrNull("key_preview"),
                    model = p.getString("model"),
                    baseUrl = p.optString("baseUrl", p.optString("base_url", "")),
                )
            },
        )
    }

    override suspend fun aiListModels(providerId: String): List<ModelOption> {
        val arr = callArray("ai_list_models", jsonObject("provider" to providerId))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ModelOption(
                id = o.getString("id"),
                label = o.getString("label"),
            )
        }
    }

    override suspend fun aiSetApiKey(providerId: String, key: String) {
        callRaw("ai_set_api_key", jsonObject("provider" to providerId, "key" to key))
    }

    override suspend fun aiClearApiKey(providerId: String) {
        callRaw("ai_clear_api_key", jsonObject("provider" to providerId))
    }

    override suspend fun aiSetModel(providerId: String, model: String) {
        callRaw("ai_set_model", jsonObject("provider" to providerId, "model" to model))
    }

    override suspend fun aiPing(): String {
        return parseJsonString(callRaw("ai_ping"))
    }

    override suspend fun aiChat(
        system: String?,
        user: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        val args = JSONObject()
            .put("user", user)
            .put("system", system)
            .put("temperature", temperature)
            .put("maxTokens", maxTokens)
        return parseJsonString(callRaw("ai_chat", args, timeoutMs = AI_CHAT_CALL_TIMEOUT_MS))
    }

    override suspend fun aiChatTools(
        messagesJson: String,
        toolsJson: String,
        temperature: Float?,
        maxTokens: Int?,
    ): String {
        val args = JSONObject()
            .put("messages", JSONArray(messagesJson))
            .put("tools", JSONArray(toolsJson))
            .put("temperature", temperature)
            .put("maxTokens", maxTokens)
        // 成功时 bridge 原样回传 assistant message JSON 对象（含 tool_calls）。
        // callRaw 只在出现顶层 "error" 字段时抛错；普通 message 不含该字段，原样返回。
        return callRaw("ai_chat_tools", args, timeoutMs = AI_CHAT_CALL_TIMEOUT_MS)
    }

    override suspend fun aiEmbed(inputs: List<String>): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val args = JSONObject().put("inputs", JSONArray(inputs))
        val arr = callArray("ai_embed", args, timeoutMs = AI_EMBED_CALL_TIMEOUT_MS)
        val out = ArrayList<FloatArray>(arr.length())
        for (i in 0 until arr.length()) {
            val vec = arr.optJSONArray(i) ?: continue
            val f = FloatArray(vec.length())
            for (j in 0 until vec.length()) f[j] = vec.optDouble(j).toFloat()
            out.add(f)
        }
        return out
    }

    private suspend fun callObject(command: String, args: JSONObject = JSONObject()): JSONObject {
        return JSONObject(callRaw(command, args))
    }

    private suspend fun callArray(
        command: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): JSONArray {
        return JSONArray(callRaw(command, args, timeoutMs))
    }

    /**
     * 同一首歌的特征只保留一条 JNI 分析；多个调用方共享其成功或失败结果。
     * 单个调用方取消时仍让其他等待者继续，最后一个等待者离开才通知 Rust 停止。
     */
    private suspend fun awaitAudioFeatures(trackId: Long, args: JSONObject): String =
        suspendCancellableCoroutine { continuation ->
            val (request, created) = synchronized(audioFeatureRequests) {
                val existing = audioFeatureRequests[trackId]
                val request = if (existing != null && !existing.finished.get() && !existing.cancelled.get()) {
                    existing
                } else {
                    AudioFeatureRequest(
                        trackId = trackId,
                        requestId = "audio-$trackId-${nextAudioRequestId.incrementAndGet()}",
                        args = JSONObject(args.toString()).put("maxWorkMs", DEFAULT_CALL_TIMEOUT_MS),
                    ).also { created ->
                        audioFeatureRequests[trackId] = created
                    }
                }
                request.waiters.incrementAndGet()
                request to (request !== existing)
            }
            if (created) audioFeatureExecutor.execute { runAudioFeatureRequest(request) }

            continuation.invokeOnCancellation { releaseAudioFeatureWaiter(request) }
            request.result.whenComplete { raw, error ->
                if (!continuation.isActive) return@whenComplete
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(raw)
                }
            }
        }

    private fun runAudioFeatureRequest(request: AudioFeatureRequest) {
        // Register the transition to JNI under the same lock as the last waiter.
        // A queued cancellation must not create a Rust flag for work that never starts.
        val shouldRun = synchronized(audioFeatureRequests) {
            if (request.cancelled.get() || request.waiters.get() == 0) false
            else {
                request.started.set(true)
                true
            }
        }
        if (!shouldRun) {
            request.result.completeExceptionally(RustBridgeException("audio_get_features", "cancelled"))
            request.finished.set(true)
            return
        }
        val outcome = runCatching {
            validateRawResponse(
                "audio_get_features",
                invokeNative(
                    "audio_get_features",
                    request.args.put("requestId", request.requestId).toString(),
                ),
            )
        }
        request.finished.set(true)
        synchronized(audioFeatureRequests) {
            if (audioFeatureRequests[request.trackId] === request) {
                audioFeatureRequests.remove(request.trackId)
            }
        }
        outcome.fold(request.result::complete, request.result::completeExceptionally)
    }

    private fun releaseAudioFeatureWaiter(request: AudioFeatureRequest) {
        val shouldCancel = synchronized(audioFeatureRequests) {
            val remaining = request.waiters.decrementAndGet()
            if (remaining != 0 || request.finished.get() || !request.cancelled.compareAndSet(false, true)) {
                false
            } else {
                if (audioFeatureRequests[request.trackId] === request) {
                    audioFeatureRequests.remove(request.trackId)
                }
                request.started.get()
            }
        }
        if (shouldCancel) {
            // This command only flips an atomic flag under a short registry lock;
            // it performs no I/O. Never queue cancellation behind slow search/AI calls.
            runCatching {
                invokeNative(
                    "audio_cancel_features",
                    jsonObject("requestId" to request.requestId).toString(),
                )
            }
        }
    }

    private suspend fun callRaw(
        command: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): String {
        // 普通 invokeNative 是同步调用，withContext 仍须等 native 返回。
        // 搜索、资料库水合与单曲成员确认分别限制并发/排队；只读取消不占住播放与账号的 bridge 池。
        // 音频分析改由 awaitAudioFeatures 单独调度，并通过专用原子标记传播取消。
        //
        // 默认 30s 是按网易 API 估的(正常 RTT < 2s)。AI chat/tools 的 Rust 硬超时
        // 是 24s；embedding 仍保留 60s。Kotlin 分别多留 3/5s，让 Rust 先收口，
        // 避免 Kotlin 提前取消 JNI 协程后留下仍在网络中的 zombie bridge 线程。
        return try {
            withTimeout(timeoutMs) {
                if (command in CANCELLABLE_CATALOG_READS) awaitCatalogRead(command, args.toString(), catalogReadExecutor)
                else if (command == "netease_playlist_track_ids") awaitCatalogRead(command, args.toString(), membershipReadExecutor)
                else if (command in CANCELLABLE_LIBRARY_READS) awaitCatalogRead(command, args.toString(), libraryReadExecutor)
                else withContext(bridgeDispatcher) {
                    validateRawResponse(command, invokeNative(command, args.toString()))
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 转成 RustBridgeException,跟其它 bridge 错误统一被上层 runCatching 捕获,
            // 不会冒泡成未捕获异常崩 app
            throw RustBridgeException(command, "timeout after ${timeoutMs / 1000}s")
        }
    }

    /** Cancel the waiter/queued read immediately; running JNI reads retain their native
     * HTTP deadlines on bounded executors. Multi-request hydration may finish later,
     * but cannot keep a foreground coroutine (and its library/UI lock) waiting.
     * Account mutations, playback resolution and audio analysis retain their own owners. */
    private suspend fun awaitCatalogRead(command: String, args: String, executor: ThreadPoolExecutor): String =
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask<Unit> {
                if (continuation.isActive) {
                    try {
                        val raw = validateRawResponse(command, invokeNative(command, args))
                        continuation.resume(raw)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            }
            continuation.invokeOnCancellation {
                task.cancel(false)
                executor.remove(task)
            }
            try { executor.execute(task) }
            catch (error: RejectedExecutionException) { continuation.resumeWithException(error) }
        }

    private fun validateRawResponse(command: String, raw: String): String {
        val trimmed = raw.trimStart()
        if (trimmed.isBlank()) {
            throw RustBridgeException(command, "empty response")
        }
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            // 显式 error 字段 → bridge 层传上来的错误(不是网易业务码)
            val message = obj?.optStringOrNull("error")
            if (!message.isNullOrBlank()) {
                throw RustBridgeException(command, message)
            }
        }
        return raw
    }

    private external fun invokeNative(command: String, argsJson: String): String

    private fun callInit(appDataDir: String) {
        invokeNative("bridge_init", jsonObject("appDataDir" to appDataDir).toString())
    }

    companion object {
        private val CANCELLABLE_CATALOG_READS = setOf(
            "netease_search", "netease_daily_recommended_tracks",
            "netease_personal_fm_tracks", "netease_similar_tracks",
        )
        // Library hydration must release the synchronizer's lock when foreground work arrives.
        // Keep running JNI reads bounded and separate from search, playback and account writes.
        private val CANCELLABLE_LIBRARY_READS = setOf(
            "netease_user_playlists", "netease_playlist_detail",
        )
        private val membershipReadExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "pipo-membership-read").apply { isDaemon = true } },
        )
        private val libraryReadExecutor = ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "pipo-library-read").apply { isDaemon = true } },
        )
        private val catalogReadExecutor = ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(12),
            { task -> Thread(task, "pipo-catalog-read").apply { isDaemon = true } },
        )
        private const val DEFAULT_CALL_TIMEOUT_MS = 30_000L

        /** Chat/tools: Rust 硬超时 24s，Kotlin 留 27s 等 Rust 收口。 */
        private const val AI_CHAT_CALL_TIMEOUT_MS = 27_000L
        /** Embedding 可批处理较大输入；Rust 60s，Kotlin 留 65s。 */
        private const val AI_EMBED_CALL_TIMEOUT_MS = 65_000L

        private val audioFeatureExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pipo-audio-analysis").apply { isDaemon = true }
        }

        private val audioFeatureRequests = HashMap<Long, AudioFeatureRequest>()
        private val nextAudioRequestId = AtomicLong(0)

        private const val BRIDGE_PARALLELISM = 6
        private val bridgeExecutor = Executors.newFixedThreadPool(BRIDGE_PARALLELISM) { task ->
            Thread(task, "pipo-rust-bridge").apply { isDaemon = true }
        }
        private val bridgeDispatcher = bridgeExecutor.asCoroutineDispatcher()

        init {
            System.loadLibrary("pipo_native_bridge")
        }
    }

    private class AudioFeatureRequest(
        val trackId: Long,
        val requestId: String,
        val args: JSONObject,
        val result: CompletableFuture<String> = CompletableFuture(),
        val waiters: AtomicInteger = AtomicInteger(),
        val started: AtomicBoolean = AtomicBoolean(),
        val cancelled: AtomicBoolean = AtomicBoolean(),
        val finished: AtomicBoolean = AtomicBoolean(),
    )
}

class RustBridgeException(command: String, message: String) : RuntimeException("$command: $message")

internal object LrcParser {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val offsetTag = Regex("""\[(?:offset|offsetMs)\s*:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<PipoLyricLine> {
        val offsetMs = offsetTag.find(lrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val points = lrc.lineSequence().flatMap { line ->
            val text = offsetTag.replace(stamp.replace(line, ""), "").trim()
            stamp.findAll(line).mapNotNull { match ->
                if (!isRenderableLrcText(text)) return@mapNotNull null
                val min = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val sec = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val frac = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                PipoLyricLine(
                    startMs = (min * 60_000L + sec * 1000L + frac + offsetMs).coerceAtLeast(0L),
                    durationMs = 0L,
                    text = text,
                    timing = PipoLyricTiming.Line,
                )
            }
        }.sortedBy { it.startMs }.toList()

        return points.mapIndexed { index, line ->
            val next = points.getOrNull(index + 1)?.startMs
            line.copy(durationMs = ((next ?: (line.startMs + 3200L)) - line.startMs).coerceAtLeast(500L))
        }
    }

    private fun isRenderableLrcText(text: String): Boolean {
        val compact = text.trim()
        if (compact.isEmpty()) return false
        return compact.any { ch ->
            ch.isLetterOrDigit() || ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af'
        }
    }
}

private data class EmbeddedTranslationSplit(
    val primaryLines: List<PipoLyricLine>,
    val embeddedTranslationLines: List<PipoLyricLine> = emptyList(),
)

private fun splitEmbeddedTranslationLines(
    lines: List<PipoLyricLine>,
    translationLines: List<PipoLyricLine>,
): EmbeddedTranslationSplit {
    if (
        lines.size < MIN_EMBEDDED_TRANSLATION_LINES * 2 ||
        translationLines.size < MIN_EMBEDDED_TRANSLATION_LINES
    ) {
        return EmbeddedTranslationSplit(lines)
    }

    val translationBucket = dominantLanguageBucket(translationLines) ?: return EmbeddedTranslationSplit(lines)
    val translationBucketCount = translationLines.count { languageBucketForText(it.text) == translationBucket }
    if (translationBucketCount < MIN_EMBEDDED_TRANSLATION_LINES) return EmbeddedTranslationSplit(lines)

    val primaryBuckets = lines.map { languageBucketForText(it.text) }
    val embeddedIndices = primaryBuckets
        .mapIndexedNotNull { index, bucket -> if (bucket == translationBucket) index else null }
    val embeddedCount = embeddedIndices.size
    val otherRenderableCount = primaryBuckets.count { it != null && it != translationBucket }
    if (embeddedCount < MIN_EMBEDDED_TRANSLATION_LINES || otherRenderableCount < MIN_EMBEDDED_TRANSLATION_LINES) {
        return EmbeddedTranslationSplit(lines)
    }

    val embeddedRatio = embeddedCount.toFloat() / lines.size.toFloat()
    if (embeddedRatio !in EMBEDDED_TRANSLATION_MIN_RATIO..EMBEDDED_TRANSLATION_MAX_RATIO) {
        return EmbeddedTranslationSplit(lines)
    }

    val allowedDelta = maxOf(
        MIN_EMBEDDED_TRANSLATION_COUNT_DELTA,
        (translationBucketCount * EMBEDDED_TRANSLATION_COUNT_DELTA_RATIO).toInt(),
    )
    if (kotlin.math.abs(embeddedCount - translationBucketCount) > allowedDelta) {
        return EmbeddedTranslationSplit(lines)
    }

    val drop = embeddedIndices.toHashSet()
    return EmbeddedTranslationSplit(
        primaryLines = lines.filterIndexed { index, _ -> index !in drop },
        embeddedTranslationLines = lines.filterIndexed { index, _ -> index in drop },
    )
}

private fun dominantLanguageBucket(lines: List<PipoLyricLine>): LyricLanguageBucket? {
    return lines
        .asSequence()
        .mapNotNull { languageBucketForText(it.text) }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
}

private fun languageBucketForText(text: String): LyricLanguageBucket? {
    var latin = 0
    var cjk = 0
    var kana = 0
    var hangul = 0
    for (ch in text) {
        when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch.isDigit() -> latin += 1
            ch in '\u3040'..'\u30ff' -> kana += 1
            ch in '\uac00'..'\ud7af' -> hangul += 1
            ch in '\u4e00'..'\u9fff' -> cjk += 1
        }
    }
    val buckets = listOf(
        LyricLanguageBucket.Latin to latin,
        LyricLanguageBucket.Cjk to cjk,
        LyricLanguageBucket.Kana to kana,
        LyricLanguageBucket.Hangul to hangul,
    )
    val (bucket, count) = buckets.maxBy { it.second }
    return if (count >= MIN_LANGUAGE_BUCKET_CHARS) bucket else null
}

private enum class LyricLanguageBucket {
    Latin,
    Cjk,
    Kana,
    Hangul,
}

private fun attachTranslationLines(
    primaryLines: List<PipoLyricLine>,
    translationLines: List<PipoLyricLine>,
): List<PipoLyricLine> {
    if (primaryLines.isEmpty() || translationLines.isEmpty()) return primaryLines
    val attached = List(primaryLines.size) { mutableListOf<PipoLyricLine>() }
    var minHostIndex = 0
    translationLines
        .asSequence()
        .filter { it.text.isNotBlank() }
        .forEach { translation ->
            val hostIndex = findTranslationHostIndex(translation, primaryLines, minHostIndex)
            if (hostIndex >= 0) {
                val normalizedTranslation = translation.copy(
                    text = translation.text.trim(),
                    chars = emptyList(),
                    timing = PipoLyricTiming.Line,
                    companionLines = emptyList(),
                    role = PipoLyricRole.Translation,
                )
                if (attached[hostIndex].none { it.text == normalizedTranslation.text }) {
                    attached[hostIndex].add(normalizedTranslation)
                    minHostIndex = (hostIndex + 1).coerceAtMost(primaryLines.lastIndex)
                }
            }
        }

    return primaryLines.mapIndexed { index, line ->
        val translations = attached[index].take(MAX_TRANSLATION_LINES_PER_PRIMARY)
        if (translations.isEmpty()) line else line.copy(companionLines = line.companionLines + translations)
    }
}

private fun findTranslationHostIndex(
    translation: PipoLyricLine,
    primaryLines: List<PipoLyricLine>,
    minIndex: Int,
): Int {
    val translationStart = translation.startMs
    var bestIndex = -1
    var bestScore = Long.MIN_VALUE
    primaryLines.forEachIndexed { index, primary ->
        if (index < minIndex) return@forEachIndexed
        val primaryStart = primary.startMs
        val nextPrimaryStart = primaryLines.getOrNull(index + 1)?.startMs
        val primaryEnd = nextPrimaryStart ?: (primaryStart + primary.durationMs.coerceAtLeast(1_200L))
        val inHostWindow = translationStart >= primaryStart - TRANSLATION_HOST_LEAD_MS &&
            translationStart < primaryEnd + TRANSLATION_HOST_TAIL_MS
        val startDistance = kotlin.math.abs(translationStart - primaryStart)
        if (!inHostWindow && startDistance > TRANSLATION_MAX_START_DISTANCE_MS) return@forEachIndexed

        val score = if (inHostWindow) {
            TRANSLATION_HOST_WINDOW_SCORE - startDistance
        } else {
            -startDistance
        }
        if (score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return bestIndex
}

private const val MAX_TRANSLATION_LINES_PER_PRIMARY = 1
private const val TRANSLATION_HOST_LEAD_MS = 700L
private const val TRANSLATION_HOST_TAIL_MS = 350L
private const val TRANSLATION_MAX_START_DISTANCE_MS = 1_650L
private const val TRANSLATION_HOST_WINDOW_SCORE = 10_000L
private const val MIN_EMBEDDED_TRANSLATION_LINES = 4
private const val MIN_LANGUAGE_BUCKET_CHARS = 2
private const val MIN_EMBEDDED_TRANSLATION_COUNT_DELTA = 4
private const val EMBEDDED_TRANSLATION_COUNT_DELTA_RATIO = 0.35f
private const val EMBEDDED_TRANSLATION_MIN_RATIO = 0.25f
private const val EMBEDDED_TRANSLATION_MAX_RATIO = 0.75f

private fun jsonObject(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    pairs.forEach { (key, value) -> o.put(key, value) }
    return o
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (isNull(name)) return null
    return optDouble(name)
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (isNull(name) || !has(name)) return null
    return optLong(name)
}

private fun parseJsonString(raw: String): String {
    return if (raw.trimStart().startsWith("\"")) {
        JSONArray("[$raw]").getString(0)
    } else {
        raw
    }
}
