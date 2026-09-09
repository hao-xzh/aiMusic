package app.pipo.nativeapp.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Account-scoped home feed. Signed playback URLs are always resolved again. */
class HomeRecommendationStore(context: Context) {
    data class Snapshot(
        val tracks: List<NativeTrack> = emptyList(), val message: String = "", val refreshDay: String = "",
        val caption: String = "",
    )
    private val directory = context.applicationContext.filesDir
    private val mutex = Mutex()
    private fun file(userId: Long?) = AtomicFile(File(directory, "home-recommendations-${userId ?: "guest"}.json"))

    suspend fun load(userId: Long?): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(file(userId).openRead().bufferedReader().use { it.readText() })
                if (json.optInt("discoveryPolicyVersion") != 3) return@runCatching Snapshot()
                val array = json.optJSONArray("tracks") ?: JSONArray()
                val tracks = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    NativeTrack(id = id, neteaseId = item.optLong("neteaseId").takeIf { it > 0 },
                        title = item.optString("title"), artist = item.optString("artist"), album = item.optString("album"),
                        streamUrl = "", artworkUrl = item.optString("artworkUrl").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("durationMs"))
                }
                Snapshot(tracks.filterNot { FunctionalMusicFilter.isReligious(it) },
                    json.optString("message"), json.optString("refreshDay"), json.optString("caption"))
            }.getOrDefault(Snapshot())
        }
    }

    suspend fun save(userId: Long?, snapshot: Snapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tracks = JSONArray()
            snapshot.tracks.takeLast(200).forEach { track ->
                tracks.put(JSONObject().put("id", track.id).put("neteaseId", track.neteaseId)
                    .put("title", track.title).put("artist", track.artist).put("album", track.album)
                    .put("artworkUrl", track.artworkUrl).put("durationMs", track.durationMs))
            }
            val bytes = JSONObject().put("discoveryPolicyVersion", 3).put("tracks", tracks).put("message", snapshot.message)
                .put("refreshDay", snapshot.refreshDay).put("caption", snapshot.caption).toString().toByteArray()
            val target = file(userId)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) }
            catch (error: Exception) { target.failWrite(stream); throw error }
        }
    }
}
