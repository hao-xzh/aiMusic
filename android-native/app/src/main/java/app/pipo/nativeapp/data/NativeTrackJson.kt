package app.pipo.nativeapp.data

import org.json.JSONArray
import org.json.JSONObject

/** Shared on-disk representation for NativeTrack snapshots. */
object NativeTrackJson {
    fun encode(track: NativeTrack, includeStreamUrl: Boolean = false): JSONObject = JSONObject().apply {
        put("id", track.id)
        track.neteaseId?.let { put("neteaseId", it) }
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        if (includeStreamUrl) put("streamUrl", track.streamUrl)
        track.artworkUrl?.let { put("artworkUrl", it) }
        put("durationMs", track.durationMs)
        track.sourceRef?.let { put("sourceRef", encodeSource(it)) }
        track.recordingId?.let { put("recordingId", it) }
        if (track.alternateSources.isNotEmpty()) {
            put("alternateSources", JSONArray().also { array ->
                track.alternateSources.forEach { array.put(encodeSource(it)) }
            })
        }
    }

    fun decode(json: JSONObject, includeStreamUrl: Boolean = false): NativeTrack? = try {
        val id = json.optionalString("id") ?: throw IllegalArgumentException("Missing track ID")
        NativeTrack(
            id = id,
            neteaseId = decodeNeteaseId(json),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            streamUrl = if (includeStreamUrl) json.optionalString("streamUrl").orEmpty() else "",
            artworkUrl = json.optionalString("artworkUrl"),
            durationMs = json.optLong("durationMs", 0L),
            sourceRef = json.optJSONObject("sourceRef")?.let(::decodeSource),
            recordingId = json.nullableString("recordingId"),
            alternateSources = decodeSources(json.optJSONArray("alternateSources")),
        )
    } catch (_: Exception) {
        null
    }

    private fun encodeSource(source: MusicSourceRef): JSONObject = JSONObject().apply {
        put("providerId", source.providerId)
        put("mediaId", source.mediaId)
        source.mediaFileId?.let { put("mediaFileId", it) }
        source.numericId?.let { put("numericId", it) }
        put("songType", source.songType)
    }

    private fun decodeSource(json: JSONObject): MusicSourceRef? {
        val providerId = json.optionalString("providerId") ?: return null
        val mediaId = json.optionalString("mediaId") ?: return null
        return try {
            MusicSourceRef(
                providerId = providerId,
                mediaId = mediaId,
                mediaFileId = json.nullableString("mediaFileId"),
                numericId = json.optLongOrNull("numericId"),
                songType = json.optInt("songType", 0),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeSources(array: JSONArray?): List<MusicSourceRef> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::decodeSource)?.let(::add)
            }
        }
    }

    private fun decodeNeteaseId(json: JSONObject): Long? = sequenceOf("neteaseId", "ne")
        .mapNotNull { key ->
            if (json.has(key) && !json.isNull(key)) {
                json.optLong(key, 0L).takeIf { it > 0L }
            } else {
                null
            }
        }
        .firstOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        nullableString(key)?.takeIf { it.isNotBlank() }

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? = when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
