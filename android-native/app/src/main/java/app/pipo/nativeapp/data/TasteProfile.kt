package app.pipo.nativeapp.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 口味画像 —— 镜像 src/lib/taste-profile.ts TasteProfile 结构。
 *
 * 跟 React 唯一差别：丢掉 acoustics（Symphonia 聚合还没移植到 Kotlin 端），
 * 其他字段一一对应，跨 React/Android 端可以共享同一份 JSON。
 */
data class TasteProfile(
    val version: Int = 1,
    val derivedAt: Long,
    val sourcePlaylistCount: Int,
    val sampledTrackCount: Int,
    val totalTrackCount: Int,
    val sourcePlaylistIds: List<Long>,
    val sourceHash: String,
    // ---- AI 写出 ----
    val genres: List<GenreTag>,
    val eras: List<EraSlice>,
    val moods: List<String>,
    val topArtists: List<ArtistAffinity>,
    val culturalContext: List<String>,
    val taglines: List<String>,
    val summary: String,
)

data class GenreTag(val tag: String, val weight: Float, val examples: List<String>)
data class EraSlice(val label: String, val weight: Float)
data class ArtistAffinity(val name: String, val affinity: Float)

object TasteProfileSerde {
    fun toJson(p: TasteProfile): String {
        val obj = JSONObject()
        obj.put("version", p.version)
        obj.put("derivedAt", p.derivedAt)
        obj.put("sourcePlaylistCount", p.sourcePlaylistCount)
        obj.put("sampledTrackCount", p.sampledTrackCount)
        obj.put("totalTrackCount", p.totalTrackCount)
        obj.put("sourcePlaylistIds", JSONArray().apply { p.sourcePlaylistIds.forEach { put(it) } })
        obj.put("sourceHash", p.sourceHash)
        obj.put("genres", JSONArray().apply {
            p.genres.forEach { g ->
                put(JSONObject().apply {
                    put("tag", g.tag)
                    put("weight", g.weight.toDouble())
                    put("examples", JSONArray().apply { g.examples.forEach { put(it) } })
                })
            }
        })
        obj.put("eras", JSONArray().apply {
            p.eras.forEach { e ->
                put(JSONObject().apply {
                    put("label", e.label)
                    put("weight", e.weight.toDouble())
                })
            }
        })
        obj.put("moods", JSONArray().apply { p.moods.forEach { put(it) } })
        obj.put("topArtists", JSONArray().apply {
            p.topArtists.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name)
                    put("affinity", a.affinity.toDouble())
                })
            }
        })
        obj.put("culturalContext", JSONArray().apply { p.culturalContext.forEach { put(it) } })
        obj.put("taglines", JSONArray().apply { p.taglines.forEach { put(it) } })
        obj.put("summary", p.summary)
        return obj.toString()
    }

    fun fromJson(raw: String): TasteProfile? {
        return try {
            val obj = JSONObject(raw)
            if (obj.optInt("version", 0) != 1) return null
            TasteProfile(
                version = 1,
                derivedAt = obj.optLong("derivedAt"),
                sourcePlaylistCount = obj.optInt("sourcePlaylistCount"),
                sampledTrackCount = obj.optInt("sampledTrackCount"),
                totalTrackCount = obj.optInt("totalTrackCount"),
                sourcePlaylistIds = readLongArray(obj.optJSONArray("sourcePlaylistIds")),
                sourceHash = obj.optString("sourceHash"),
                genres = readGenres(obj.optJSONArray("genres")),
                eras = readEras(obj.optJSONArray("eras")),
                moods = readStringArray(obj.optJSONArray("moods")),
                topArtists = readArtists(obj.optJSONArray("topArtists")),
                culturalContext = readStringArray(obj.optJSONArray("culturalContext")),
                taglines = readStringArray(obj.optJSONArray("taglines")),
                summary = obj.optString("summary"),
            )
        } catch (_: Exception) { null }
    }

    fun parseAiBody(raw: String): ParsedAiBody? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val obj = JSONObject(raw.substring(s, e + 1))
            val summary = obj.optString("summary").trim()
            if (summary.isEmpty()) return null
            ParsedAiBody(
                genres = readGenres(obj.optJSONArray("genres")),
                eras = readEras(obj.optJSONArray("eras")),
                moods = readStringArray(obj.optJSONArray("moods")).take(8),
                topArtists = readArtists(obj.optJSONArray("topArtists")),
                culturalContext = readStringArray(obj.optJSONArray("culturalContext")).take(6),
                taglines = readStringArray(obj.optJSONArray("taglines")).take(4),
                summary = summary,
            )
        } catch (_: Exception) { null }
    }

    data class ParsedAiBody(
        val genres: List<GenreTag>,
        val eras: List<EraSlice>,
        val moods: List<String>,
        val topArtists: List<ArtistAffinity>,
        val culturalContext: List<String>,
        val taglines: List<String>,
        val summary: String,
    )

    private fun readGenres(arr: JSONArray?): List<GenreTag> {
        if (arr == null) return emptyList()
        val out = mutableListOf<GenreTag>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) out.add(GenreTag(v.trim(), 0.5f, emptyList()))
                is JSONObject -> {
                    val tag = v.optString("tag").trim()
                    if (tag.isNotEmpty()) out.add(
                        GenreTag(
                            tag = tag,
                            weight = v.optDouble("weight", 0.5).toFloat().coerceIn(0f, 1f),
                            examples = readStringArray(v.optJSONArray("examples")).take(4),
                        )
                    )
                }
            }
        }
        return out.take(12)
    }

    private fun readEras(arr: JSONArray?): List<EraSlice> {
        if (arr == null) return emptyList()
        val out = mutableListOf<EraSlice>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) out.add(EraSlice(v.trim(), 0.5f))
                is JSONObject -> {
                    val label = v.optString("label").trim()
                    if (label.isNotEmpty()) out.add(
                        EraSlice(label, v.optDouble("weight", 0.5).toFloat().coerceIn(0f, 1f))
                    )
                }
            }
        }
        return out.take(8)
    }

    private fun readArtists(arr: JSONArray?): List<ArtistAffinity> {
        if (arr == null) return emptyList()
        val out = mutableListOf<ArtistAffinity>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) out.add(ArtistAffinity(v.trim(), 0.5f))
                is JSONObject -> {
                    val name = v.optString("name").trim()
                    if (name.isNotEmpty()) out.add(
                        ArtistAffinity(name, v.optDouble("affinity", 0.5).toFloat().coerceIn(0f, 1f))
                    )
                }
            }
        }
        return out.take(14)
    }

    private fun readStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun readLongArray(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optLong(it) }
    }
}
