package app.pipo.nativeapp.data.agent.memory

import android.content.Context
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import org.json.JSONArray
import org.json.JSONObject

class AgentLedgerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun recent(limit: Int = 5): List<LedgerEntry> {
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]").orEmpty().ifBlank { "[]" })
        val out = ArrayList<LedgerEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                LedgerEntry(
                    turnId = obj.optString("turnId"),
                    userText = obj.optString("userText"),
                    normalizedPlan = obj.optString("normalizedPlan"),
                    finalReply = obj.optString("finalReply"),
                    success = obj.optBoolean("success"),
                    firstTracks = readTracks(obj.optJSONArray("firstTracks")),
                    validation = obj.optString("validation"),
                    execution = obj.optString("execution"),
                    tsMs = obj.optLong("tsMs"),
                ),
            )
        }
        return out.takeLast(limit)
    }

    @Synchronized
    fun record(
        plan: MusicTurnPlan,
        validation: QueueValidation,
        results: List<ActionExecutionResult>,
        finalReply: String,
    ) {
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]").orEmpty().ifBlank { "[]" })
        val firstTracks = results.flatMap { it.tracks }.take(6)
        val obj = JSONObject()
            .put("turnId", plan.turnId)
            .put("userText", plan.userText.take(240))
            .put("normalizedPlan", describePlan(plan).take(360))
            .put("finalReply", finalReply.take(420))
            .put("success", results.all { it.success })
            .put("validation", validation.messages.joinToString("|").take(240))
            .put("execution", describeExecution(results).take(360))
            .put("firstTracks", writeTracks(firstTracks))
            .put("tsMs", System.currentTimeMillis())
        arr.put(obj)
        val trimmed = JSONArray()
        val start = (arr.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.optJSONObject(i))
        prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    data class LedgerEntry(
        val turnId: String,
        val userText: String,
        val normalizedPlan: String,
        val finalReply: String,
        val success: Boolean,
        val firstTracks: List<String>,
        val validation: String,
        val execution: String,
        val tsMs: Long,
    )

    private fun describePlan(plan: MusicTurnPlan): String =
        plan.actions.joinToString(",") { action ->
            when (action) {
                is PlannedAction.PlayRequest -> "${action.mode}:request:${action.target?.title.orEmpty()}:${action.primaryGoal.primaryArtists.joinToString("/")}"
                is PlannedAction.PlayTracks -> "${action.mode}:tracks:${action.tracks.take(4).joinToString("/") { it.title }}"
                is PlannedAction.PlayPlaylist -> "playlist:${action.name}"
                is PlannedAction.LikeCurrent -> "like:${action.like}"
                is PlannedAction.ModifyPlaylist -> "playlistModify:${action.playlistName}:${action.add}"
                is PlannedAction.SkipCurrent -> "skip"
                is PlannedAction.Say -> "say"
                is PlannedAction.Clarify -> "clarify"
            }
        }

    private fun describeExecution(results: List<ActionExecutionResult>): String =
        results.joinToString(",") { result ->
            "${result.actionId}:${result.type}:${result.success}:accepted=${result.acceptedByPlayer}:started=${result.actuallyStarted}:${result.message.take(60)}"
        }

    private fun writeTracks(tracks: List<NativeTrack>): JSONArray {
        val arr = JSONArray()
        tracks.forEach { track ->
            arr.put(
                JSONObject()
                    .put("title", track.title)
                    .put("artist", track.artist),
            )
        }
        return arr
    }

    private fun readTracks(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            val artist = obj.optString("artist").trim()
            if (title.isNotBlank()) out.add(if (artist.isBlank()) title else "$title - $artist")
        }
        return out
    }

    companion object {
        @Synchronized
        fun markPlaybackStart(
            context: Context,
            requestId: String,
            queueVersion: Long,
            actuallyStarted: Boolean,
            error: String = "",
        ) {
            if (requestId.isBlank()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]").orEmpty().ifBlank { "[]" })
            for (i in arr.length() - 1 downTo 0) {
                val obj = arr.optJSONObject(i) ?: continue
                val execution = obj.optString("execution")
                if (!execution.contains("$requestId:")) continue
                val proof = "playback_start:queueVersion=$queueVersion:started=$actuallyStarted:error=${error.take(80)}"
                obj.put("execution", listOf(execution, proof).filter { it.isNotBlank() }.joinToString("|").take(360))
                if (!actuallyStarted && error.isNotBlank()) {
                    obj.put("success", false)
                }
                prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
                return
            }
        }

        const val PREFS_NAME = "claudio_agent_ledger"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 8
    }
}
