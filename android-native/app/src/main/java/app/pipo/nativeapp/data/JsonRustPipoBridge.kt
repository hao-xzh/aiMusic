package app.pipo.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
        )
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
            )
        }
    }

    override suspend fun neteasePlaylistTracks(playlistId: Long): List<NativeTrack> {
        val o = callObject("netease_playlist_detail", jsonObject("id" to playlistId))
        val tracks = o.optJSONArray("tracks") ?: return emptyList()
        return parseTracks(tracks)
    }

    override suspend fun neteaseSearch(query: String, limit: Int): List<NativeTrack> {
        val tracks = callArray("netease_search", jsonObject("query" to query, "limit" to limit))
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
            )
        }
    }

    override suspend fun neteaseSongLyric(trackId: String): List<PipoLyricLine> {
        val raw = callObject("netease_song_lyric", jsonObject("id" to trackId.toLongOrNull()))
        raw.optStringOrNull("yrc")?.let { yrc ->
            val parsed = YrcParser.parse(yrc)
            if (parsed.isNotEmpty()) return parsed
        }
        val lrc = raw.optStringOrNull("lyric") ?: return emptyList()
        return LrcParser.parse(lrc)
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
        val o = callObject(
            "audio_get_features",
            jsonObject("trackId" to trackId, "url" to url, "cacheBytes" to cacheBytes),
        )
        return AudioFeatures(
            trackId = o.getLong("trackId"),
            durationS = o.getDouble("durationS"),
            bpm = o.optDoubleOrNull("bpm"),
            bpmConfidence = o.getDouble("bpmConfidence"),
            rmsDb = o.getDouble("rmsDb"),
            peakDb = o.getDouble("peakDb"),
            dynamicRangeDb = o.getDouble("dynamicRangeDb"),
            introEnergy = o.getDouble("introEnergy"),
            outroEnergy = o.getDouble("outroEnergy"),
            spectralCentroidHz = o.getDouble("spectralCentroidHz"),
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
        return parseJsonString(callRaw("ai_chat", args))
    }

    override suspend fun aiEmbed(inputs: List<String>): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val args = JSONObject().put("inputs", JSONArray(inputs))
        val arr = callArray("ai_embed", args)
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

    private suspend fun callArray(command: String, args: JSONObject = JSONObject()): JSONArray {
        return JSONArray(callRaw(command, args))
    }

    private suspend fun callRaw(command: String, args: JSONObject = JSONObject()): String {
        return withContext(Dispatchers.IO) {
            invokeNative(command, args.toString()).also { raw ->
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("{")) {
                    val obj = runCatching { JSONObject(raw) }.getOrNull()
                    val message = obj?.optStringOrNull("error")
                    if (!message.isNullOrBlank()) {
                        throw RustBridgeException(command, message)
                    }
                }
            }
        }
    }

    private external fun invokeNative(command: String, argsJson: String): String

    private fun callInit(appDataDir: String) {
        invokeNative("bridge_init", jsonObject("appDataDir" to appDataDir).toString())
    }

    companion object {
        init {
            System.loadLibrary("pipo_native_bridge")
        }
    }
}

class RustBridgeException(command: String, message: String) : RuntimeException("$command: $message")

private object LrcParser {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(lrc: String): List<PipoLyricLine> {
        val points = lrc.lineSequence().flatMap { line ->
            val text = stamp.replace(line, "").trim()
            stamp.findAll(line).mapNotNull { match ->
                val min = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val sec = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val frac = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                PipoLyricLine(
                    startMs = min * 60_000L + sec * 1000L + frac,
                    durationMs = 0L,
                    text = text,
                )
            }
        }.sortedBy { it.startMs }.toList()

        return points.mapIndexed { index, line ->
            val next = points.getOrNull(index + 1)?.startMs
            line.copy(durationMs = ((next ?: (line.startMs + 3200L)) - line.startMs).coerceAtLeast(500L))
        }
    }
}

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
