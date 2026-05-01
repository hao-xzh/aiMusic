package app.pipo.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * 单曲语义索引器 —— 镜像 src/lib/semantic-indexer.ts。
 *
 * 输入：NativeTrack 列表 + 选项（要不要叫 LLM）
 * 输出：写入 PipoGraph.trackSemanticStore，副作用为主，返回每首的 profile
 *
 * 流程：
 *   1. 优先 cache hit
 *   2. miss 走 buildRuleBasedProfile（metadata 启发式）
 *   3. allowAi=true 时再叫一次 LLM 升级（lyrics + audioFeatures + metadata），
 *      LLM 失败就 fallback 用规则版（依旧落盘）
 *
 * 并发：默认 2，避免 DeepSeek QPS 拉爆 + 用户单 device IP 被限流。
 */
class SemanticIndexer(
    private val repository: PipoRepository,
    private val store: TrackSemanticStore,
    private val featuresStore: AudioFeaturesStore,
) {
    data class Progress(val total: Int, val done: Int, val skipped: Int, val failed: Int)

    suspend fun indexAll(
        tracks: List<NativeTrack>,
        allowAi: Boolean,
        onlyMissing: Boolean = true,
        concurrency: Int = 2,
        onProgress: ((Progress) -> Unit)? = null,
    ): Progress {
        var done = 0
        var skipped = 0
        var failed = 0
        val total = tracks.size

        val pending = if (onlyMissing) {
            tracks.filter { needsIndex(it) }
        } else tracks

        // onlyMissing 跳过的部分计入 skipped/done
        val preSkipped = total - pending.size
        skipped += preSkipped
        done += preSkipped
        onProgress?.invoke(Progress(total, done, skipped, failed))

        if (!allowAi) {
            // React 端也是这样：不允许 AI 就完全跳过 LLM 调用，但仍然写入规则版
            pending.forEach { track ->
                val rule = runCatching { buildRuleBasedProfile(track) }.getOrNull()
                if (rule != null) store.put(rule) else failed++
                done++
                onProgress?.invoke(Progress(total, done, skipped, failed))
            }
            return Progress(total, done, skipped, failed)
        }

        val sem = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            pending.map { track ->
                async {
                    sem.withPermit {
                        val ok = runCatching { indexOne(track) }.isSuccess
                        synchronized(this@SemanticIndexer) {
                            if (!ok) failed++
                            done++
                            onProgress?.invoke(Progress(total, done, skipped, failed))
                        }
                    }
                }
            }.forEach { it.await() }
        }
        return Progress(total, done, skipped, failed)
    }

    suspend fun indexOne(track: NativeTrack): TrackSemanticProfile {
        val cached = store.get(track.id)
        if (cached != null && cached.sourceLlm) return cached

        val features = featuresStore.get(track.id)
        val lyricsSample = loadLyricsSample(track)

        val base = buildRuleBasedProfile(track, features, lyricsSample)

        return runCatching {
            val llm = callSemanticLlm(track, features, lyricsSample)
            val profile = mergeLlmIntoBase(base, llm)
            store.put(profile)
            profile
        }.getOrElse {
            // LLM 失败：落盘规则版（标 sourceLlm=false）
            store.put(base)
            base
        }
    }

    fun needsIndex(track: NativeTrack): Boolean {
        val cached = store.get(track.id) ?: return true
        if (cached.version != 1) return true
        if (!cached.sourceLlm) return true
        return false
    }

    // ---------- 加载歌词样本 ----------
    private suspend fun loadLyricsSample(track: NativeTrack): String {
        if (track.neteaseId == null) return ""
        return runCatching {
            val lines = repository.lyricsForTrack(track.id)
            if (lines.isEmpty()) "" else lines
                .take(20)
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" / ")
                .take(900)
        }.getOrDefault("")
    }

    // ---------- LLM 调用 ----------
    private suspend fun callSemanticLlm(
        track: NativeTrack,
        features: AudioFeatures?,
        lyricsSample: String,
    ): JSONObject {
        val artistsList = track.artist.split("/", "&", ",", " feat.", "feat.", " feat ").map { it.trim() }.filter { it.isNotEmpty() }
        val input = JSONObject().apply {
            put("title", track.title)
            put("artists", org.json.JSONArray(artistsList))
            put("album", track.album)
            put("lyricsSample", lyricsSample)
            if (features != null) {
                val energy = (features.introEnergy + features.outroEnergy) / 2.0
                put("audioHints", JSONObject().apply {
                    features.bpm?.let { put("bpm", it) }
                    put("energy", energy)
                })
            }
        }
        val user = buildString {
            append("请为这首歌生成单曲语义档案，只描述歌曲本身，不要写用户口味。\n")
            append("输入：\n")
            append(input.toString())
            append("\n要求：\n")
            append("1) 不确定就 unknown 并降低 confidence。\n")
            append("2) genre 最多 3 个，subGenre 最多 4 个，标签用短英文小写，如 r&b / soul / pop / hip-hop。\n")
            append("3) moods/scenes/textures 用短标签，如 chill/night/coding/smooth/warm。\n")
            append("4) negativeTags 表示不适合的场景或气质，如 noisy/aggressive/party/cheesy。\n")
            append("5) embeddingText 要混合中文和英文标签，适合自然语言检索。\n")
            append("严格只输出 JSON。")
        }
        val systemArtists = artistsList.joinToString(" / ").ifBlank { "未知" }
        val raw = repository.aiChat(
            system = "你是 Claudio 的单曲语义标注器。只输出 JSON，不要解释。当前歌曲：${track.title} - $systemArtists",
            user = user,
            temperature = 0.2f,
            maxTokens = 900,
        )
        val cleaned = run {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a >= 0 && b > a) raw.substring(a, b + 1) else raw
        }
        return JSONObject(cleaned)  // 抛错时上层 fallback
    }

    private fun mergeLlmIntoBase(base: TrackSemanticProfile, llm: JSONObject): TrackSemanticProfile {
        val lang = llm.optJSONObject("language")
        val region = llm.optJSONObject("region")
        val style = llm.optJSONObject("style")
        val vibe = llm.optJSONObject("vibe")
        val vocal = llm.optJSONObject("vocal")
        return base.copy(
            language = TrackLanguage.from(lang?.optString("primary")) .takeIf { it != TrackLanguage.Unknown } ?: base.language,
            languageConfidence = lang?.optDouble("confidence", base.languageConfidence) ?: base.languageConfidence,
            region = TrackRegion.from(region?.optString("primary")).takeIf { it != TrackRegion.Unknown } ?: base.region,
            regionConfidence = region?.optDouble("confidence", base.regionConfidence) ?: base.regionConfidence,
            genres = stringList(style, "genres").take(3).ifEmpty { base.genres },
            subGenres = stringList(style, "subGenres").take(4),
            styleAnchors = stringList(style, "styleAnchors").take(4),
            moods = stringList(vibe, "moods").take(6).ifEmpty { base.moods },
            scenes = stringList(vibe, "scenes").take(6).ifEmpty { base.scenes },
            textures = stringList(vibe, "textures").take(6).ifEmpty { base.textures },
            energyWords = stringList(vibe, "energyWords").take(4).ifEmpty { base.energyWords },
            tempoFeel = stringList(vibe, "tempoFeel").take(4).ifEmpty { base.tempoFeel },
            vocalType = VocalType.from(vocal?.optString("type")).takeIf { it != VocalType.Unknown } ?: base.vocalType,
            vocalDelivery = stringList(vocal, "delivery").take(4),
            negativeTags = stringList(llm, "negativeTags").take(8),
            summary = llm.optString("summary").ifBlank { base.summary },
            embeddingText = llm.optString("embeddingText").ifBlank { base.embeddingText },
            confidence = llm.optDouble("confidence", base.confidence),
            sourceLyrics = base.sourceLyrics,
            sourceAudio = base.sourceAudio,
            sourceLlm = true,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun stringList(obj: JSONObject?, key: String): List<String> {
        if (obj == null) return emptyList()
        val arr = obj.optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim().lowercase()
            if (s.isNotEmpty()) out.add(s)
        }
        return out.distinct()
    }

    // ---------- 规则启发式版 ----------

    fun buildRuleBasedProfile(
        track: NativeTrack,
        features: AudioFeatures? = null,
        lyricsSample: String = "",
    ): TrackSemanticProfile {
        val artists = track.artist.split("/", "&", ",", " feat.", "feat.", " feat ")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val metaText = "${track.title} ${track.artist} ${track.album}"
        val (lang, langConf) = detectLanguage(metaText, lyricsSample)
        val (region, regionConf) = inferRegion(lang, metaText)
        val genres = inferGenres(metaText)
        val subGenres = inferSubGenres(metaText, genres)
        val vocalType = inferVocalType(metaText, lang)
        val energyVal = features?.let { (it.introEnergy + it.outroEnergy) / 2.0 }
        val energyWords = inferEnergyWords(energyVal)
        val moods = inferMoods(metaText, genres, energyVal)
        val scenes = inferScenes(metaText, genres, energyVal)
        val textures = inferTextures(metaText, genres, energyVal)
        val decade = inferDecade(track.album)
        val styleAnchors = inferStyleAnchors(artists, genres, subGenres)
        val negativeTags = inferNegativeTags(genres, energyVal)
        val tempoFeel = inferTempoFeel(features?.bpm)
        val summary = "${lang.key}/${region.key} ${genres.joinToString(",")} ${moods.take(2).joinToString(",")} ${scenes.take(2).joinToString(",")} ${vocalType.key}".trim()
        val embedding = (listOf(track.title, track.artist, track.album, lang.key, region.key)
            + genres + subGenres + styleAnchors + moods + scenes + textures + energyWords
            + listOf(vocalType.key, summary) + negativeTags.map { "不适合$it" })
            .filter { it.isNotBlank() }
            .joinToString(" ")

        return TrackSemanticProfile(
            trackId = track.id,
            title = track.title,
            artists = artists,
            album = track.album.takeIf { it.isNotBlank() },
            year = decadeToYear(decade),
            language = lang,
            languageConfidence = langConf,
            region = region,
            regionConfidence = regionConf,
            genres = genres,
            subGenres = subGenres,
            styleAnchors = styleAnchors,
            moods = moods,
            scenes = scenes,
            textures = textures,
            energyWords = energyWords,
            tempoFeel = tempoFeel,
            vocalType = vocalType,
            vocalDelivery = inferDelivery(genres, energyVal),
            decade = decade,
            bpm = features?.bpm,
            energy = energyVal,
            negativeTags = negativeTags,
            summary = summary,
            embeddingText = embedding,
            confidence = 0.52,
            sourceMetadata = true,
            sourceLyrics = lyricsSample.isNotBlank(),
            sourceAudio = features != null,
            sourceLlm = false,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun detectLanguage(metaText: String, lyricsSample: String): Pair<TrackLanguage, Double> {
        val text = "$lyricsSample $metaText"
        val chinese = Regex("[\\u4e00-\\u9fff]").findAll(text).count()
        val kana = Regex("[\\u3040-\\u30ff]").findAll(text).count()
        val hangul = Regex("[\\uac00-\\ud7af]").findAll(text).count()
        val latin = Regex("[a-zA-Z]").findAll(text).count()
        return when {
            kana > 20 -> TrackLanguage.Japanese to 0.85
            hangul > 20 -> TrackLanguage.Korean to 0.85
            chinese > 12 && latin > chinese * 0.6 -> TrackLanguage.Mixed to 0.68
            chinese > latin * 0.45 -> TrackLanguage.Mandarin to 0.72
            latin > chinese * 2 && latin > 8 -> TrackLanguage.English to 0.68
            else -> TrackLanguage.Unknown to 0.35
        }
    }

    private fun inferRegion(language: TrackLanguage, metaText: String): Pair<TrackRegion, Double> = when (language) {
        TrackLanguage.English -> TrackRegion.Western to 0.68
        TrackLanguage.Mandarin, TrackLanguage.Cantonese, TrackLanguage.Mixed -> TrackRegion.Chinese to 0.7
        TrackLanguage.Japanese, TrackLanguage.Korean -> TrackRegion.JapaneseKorean to 0.72
        else -> TrackRegion.Unknown to 0.35
    }

    private fun inferGenres(metaText: String): List<String> {
        val t = metaText.lowercase()
        val genres = mutableListOf<String>()
        if (Regex("(r&b|rnb|节奏布鲁斯|neo soul|neosoul|soul)").containsMatchIn(t)) genres += "r&b"
        if (Regex("(soul|灵魂|neo soul|neosoul)").containsMatchIn(t)) genres += "soul"
        if (Regex("(hip.?hop|rap|说唱)").containsMatchIn(t)) genres += "hip-hop"
        if (Regex("(jazz|爵士|bossa|swing)").containsMatchIn(t)) genres += "jazz"
        if (Regex("(folk|民谣|acoustic|unplugged)").containsMatchIn(t)) genres += "folk"
        if (Regex("(edm|dance|club|remix|电子|techno|house|trance)").containsMatchIn(t)) genres += "electronic"
        if (Regex("(rock|摇滚|indie rock|britpop)").containsMatchIn(t)) genres += "rock"
        if (genres.isEmpty()) genres += "pop"
        return genres.distinct().take(3)
    }

    private fun inferSubGenres(metaText: String, genres: List<String>): List<String> {
        val t = metaText.lowercase()
        val out = mutableListOf<String>()
        if ("r&b" in genres) {
            if (Regex("(alternative|alt r&b|另类)").containsMatchIn(t)) out += "alternative r&b"
            if (Regex("(neo soul|neosoul)").containsMatchIn(t)) out += "neo soul"
            out += "late-night r&b"
        }
        if (Regex("city pop|城市流行").containsMatchIn(t)) out += "city pop"
        return out.distinct().take(4)
    }

    private fun inferVocalType(metaText: String, lang: TrackLanguage): VocalType {
        val t = metaText.lowercase()
        if (Regex("instrumental|纯音乐|伴奏").containsMatchIn(t) || lang == TrackLanguage.Instrumental) return VocalType.Instrumental
        if (Regex("feat\\.|with|duet|合唱|/|&").containsMatchIn(t)) return VocalType.Duet
        return VocalType.Unknown
    }

    private fun inferEnergyWords(energy: Double?): List<String> {
        if (energy == null) return emptyList()
        return when {
            energy < 0.22 -> listOf("low")
            energy < 0.42 -> listOf("mid-low")
            energy < 0.62 -> listOf("mid")
            else -> listOf("high")
        }
    }

    private fun inferMoods(metaText: String, genres: List<String>, energy: Double?): List<String> {
        val t = metaText.lowercase()
        val out = mutableListOf<String>()
        if ("r&b" in genres) out += listOf("chill", "sensual", "smooth")
        if (Regex("night|moon|雨|rain|深夜|lonely|blue").containsMatchIn(t)) out += listOf("melancholic", "night")
        if (energy != null && energy < 0.35) out += "calm"
        if (energy != null && energy > 0.62) out += "energetic"
        return out.distinct().take(6)
    }

    private fun inferScenes(metaText: String, genres: List<String>, energy: Double?): List<String> {
        val t = metaText.lowercase()
        val out = mutableListOf<String>()
        if ("r&b" in genres) out += listOf("night", "city walk")
        if (Regex("drive|road|car|开车").containsMatchIn(t)) out += "driving"
        if (Regex("rain|雨").containsMatchIn(t)) out += "rainy day"
        if (energy != null && energy < 0.38) out += listOf("coding", "focus")
        return out.distinct().take(6)
    }

    private fun inferTextures(metaText: String, genres: List<String>, energy: Double?): List<String> {
        val out = mutableListOf<String>()
        if ("r&b" in genres) out += listOf("smooth", "warm")
        if (energy != null && energy < 0.35) out += listOf("soft", "minimal")
        if (Regex("acoustic|民谣|unplugged").containsMatchIn(metaText.lowercase())) out += "acoustic"
        return out.distinct().take(6)
    }

    private fun inferTempoFeel(bpm: Double?): List<String> {
        if (bpm == null) return emptyList()
        return when {
            bpm < 82 -> listOf("slow")
            bpm < 116 -> listOf("medium")
            else -> listOf("fast")
        }
    }

    private fun inferDelivery(genres: List<String>, energy: Double?): List<String> {
        val out = mutableListOf<String>()
        if ("r&b" in genres) out += listOf("soft", "breathy")
        if ("hip-hop" in genres) out += "rap"
        if (energy != null && energy < 0.35) out += "gentle"
        return out.distinct().take(4)
    }

    private fun inferNegativeTags(genres: List<String>, energy: Double?): List<String> {
        val out = mutableListOf<String>()
        if (energy != null && energy > 0.62) out += listOf("noisy", "aggressive")
        if ("electronic" in genres) out += "party"
        if ("hip-hop" in genres) out += "rap-heavy"
        return out.distinct().take(6)
    }

    private fun inferStyleAnchors(artists: List<String>, genres: List<String>, subGenres: List<String>): List<String> {
        val first = artists.firstOrNull()
        val out = subGenres.toMutableList()
        if (first != null && "r&b" in genres) out += "$first-like"
        return out.distinct().take(4)
    }

    private fun inferDecade(text: String): String? {
        val m = Regex("\\b(19[7-9]\\d|20[0-2]\\d)\\b").find(text) ?: return null
        val y = m.value.toIntOrNull() ?: return null
        return when {
            y < 1980 -> "1970s"
            y < 1990 -> "1980s"
            y < 2000 -> "1990s"
            y < 2010 -> "2000s"
            y < 2020 -> "2010s"
            else -> "2020s"
        }
    }

    private fun decadeToYear(decade: String?): Int? = decade?.takeIf { it != "unknown" }?.take(4)?.toIntOrNull()
}
