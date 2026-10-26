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
    private fun file(accountKey: String) = AtomicFile(File(directory, "home-recommendations-${java.net.URLEncoder.encode(accountKey, "UTF-8")}.json"))

    suspend fun load(accountKey: String): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(file(accountKey).openRead().bufferedReader().use { it.readText() })
                if (json.optInt("discoveryPolicyVersion") != 4) return@runCatching Snapshot()
                val array = json.optJSONArray("tracks") ?: JSONArray()
                val tracks = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    NativeTrackJson.decode(item)
                }
                Snapshot(tracks.filterNot { FunctionalMusicFilter.isReligious(it) },
                    json.optString("message"), json.optString("refreshDay"), json.optString("caption"))
            }.getOrDefault(Snapshot())
        }
    }

    suspend fun save(accountKey: String, snapshot: Snapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val tracks = JSONArray()
            snapshot.tracks.takeLast(200).forEach { track ->
                tracks.put(NativeTrackJson.encode(track))
            }
            val bytes = JSONObject().put("discoveryPolicyVersion", 4).put("tracks", tracks).put("message", snapshot.message)
                .put("refreshDay", snapshot.refreshDay).put("caption", snapshot.caption).toString().toByteArray()
            val target = file(accountKey)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) }
            catch (error: Exception) { target.failWrite(stream); throw error }
        }
    }
}
