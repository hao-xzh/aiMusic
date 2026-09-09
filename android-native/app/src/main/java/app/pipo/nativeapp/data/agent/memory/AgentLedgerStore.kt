package app.pipo.nativeapp.data.agent.memory

import android.content.Context
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import app.pipo.nativeapp.data.agent.runtime.AgentListeningRequestCodec
import app.pipo.nativeapp.data.agent.runtime.AgentLedgerPayloadCodec
import app.pipo.nativeapp.data.agent.runtime.AgentContextBuilder
import org.json.JSONArray
import org.json.JSONObject

class AgentLedgerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Only a failed, actually resolved favorite action may create this record. */
    data class PendingFavorite(
        val originTurnId: String, val accountUserId: Long, val track: NativeTrack,
        val like: Boolean, val createdAtMs: Long,
    )

    @Synchronized
    fun pendingFavorite(): PendingFavorite? = runCatching {
        val obj = JSONObject(prefs.getString(KEY_PENDING_FAVORITE, null) ?: return@runCatching null)
        val accountId = obj.getLong("accountUserId").takeIf { it > 0 } ?: return@runCatching null
        val neteaseId = obj.getLong("neteaseId").takeIf { it > 0 } ?: return@runCatching null
        val turnId = obj.getString("originTurnId").takeIf { it.isNotBlank() } ?: return@runCatching null
        PendingFavorite(turnId, accountId,
            NativeTrack(obj.getString("trackId"), neteaseId, obj.getString("title"), obj.getString("artist"), "", ""),
            obj.getBoolean("like"), obj.getLong("createdAtMs"))
    }.getOrNull()

    @Synchronized
    fun saveFailedFavorite(turnId: String, accountUserId: Long, track: NativeTrack, like: Boolean) {
        val id = track.neteaseId?.takeIf { it > 0 } ?: return
        if (accountUserId <= 0) return
        val obj = JSONObject().put("originTurnId", turnId).put("accountUserId", accountUserId)
            .put("trackId", track.id).put("neteaseId", id)
            .put("title", track.title.take(160)).put("artist", track.artist.take(160))
            .put("like", like).put("createdAtMs", System.currentTimeMillis())
        prefs.edit().putString(KEY_PENDING_FAVORITE, obj.toString()).apply()
    }

    @Synchronized
    fun clearFavoriteRetry() { prefs.edit().remove(KEY_PENDING_FAVORITE).apply() }

    @Synchronized
    fun recent(limit: Int = 5): List<LedgerEntry> {
        val arr = readEntries()
        val out = ArrayList<LedgerEntry>()
        val start = (arr.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (i in start until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val success = obj.optBoolean("success", false)
            out.add(
                LedgerEntry(
                    turnId = bounded(obj.optString("turnId"), MAX_TURN_ID_CHARS),
                    userText = bounded(obj.optString("userText"), MAX_USER_TEXT_CHARS),
                    normalizedPlan = bounded(obj.optString("normalizedPlan"), MAX_PLAN_CHARS),
                    finalReply = bounded(obj.optString("finalReply"), MAX_REPLY_CHARS),
                    success = success,
                    outcome = parseOutcome(obj.optString("outcome"), success),
                    firstTracks = readTracks(obj.optJSONArray("firstTracks")),
                    validation = bounded(obj.optString("validation"), MAX_VALIDATION_CHARS),
                    execution = bounded(obj.optString("execution"), MAX_EXECUTION_CHARS),
                    tsMs = obj.optLong("tsMs"),
                ),
            )
        }
        return out.takeLast(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun record(
        plan: MusicTurnPlan,
        validation: QueueValidation,
        results: List<ActionExecutionResult>,
        finalReply: String,
    ) {
        val arr = readEntries()
        val firstTracks = results.flatMap { it.tracks }.take(6)
        val outcome = outcomeFor(validation, results)
        val obj = JSONObject()
            .put("turnId", bounded(plan.turnId, MAX_TURN_ID_CHARS))
            .put("userText", bounded(plan.userText, MAX_USER_TEXT_CHARS))
            .put("normalizedPlan", bounded(describePlan(plan), MAX_PLAN_CHARS))
            .put("finalReply", bounded(finalReply, MAX_REPLY_CHARS))
            .put("success", outcome == Outcome.SUCCESS)
            .put("outcome", outcome.name)
            .put("validation", bounded(validation.messages.joinToString("|"), MAX_VALIDATION_CHARS))
            .put("execution", bounded(describeExecution(results), MAX_EXECUTION_CHARS))
            .put("firstTracks", writeTracks(firstTracks))
            .put("tsMs", System.currentTimeMillis())
        arr.put(obj)
        val trimmed = JSONArray()
        val start = (arr.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.optJSONObject(i))
        prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    /** A canonical, goal-only request that can safely be carried to a later successful queue turn. */
    @Synchronized
    fun activeListeningRequest(): String =
        AgentListeningRequestCodec.canonicalize(prefs.getString(KEY_ACTIVE_LISTENING_REQUEST, "").orEmpty()).orEmpty()

    /**
     * Stores only the codec's MusicGoal-shaped subset. Invalid, oversized, secret-like or
     * instruction-bearing data is rejected and never replaces the last valid request.
     */
    @Synchronized
    fun saveListeningRequest(requestJson: String): Boolean {
        val canonical = AgentListeningRequestCodec.canonicalize(requestJson) ?: return false
        prefs.edit().putString(KEY_ACTIVE_LISTENING_REQUEST, canonical).apply()
        return true
    }

    data class LedgerEntry(
        val turnId: String,
        val userText: String,
        val normalizedPlan: String,
        val finalReply: String,
        val success: Boolean,
        val outcome: Outcome,
        val firstTracks: List<String>,
        val validation: String,
        val execution: String,
        val tsMs: Long,
    )

    enum class Outcome {
        SUCCESS,
        PARTIAL_FAILURE,
        FAILURE,
    }

    private fun describePlan(plan: MusicTurnPlan): String =
        plan.actions.joinToString(",") { action ->
            when (action) {
                is PlannedAction.PlayRequest -> "${action.mode}:request:${action.target?.title.orEmpty()}:${action.primaryGoal.primaryArtists.joinToString("/")}"
                is PlannedAction.PlayTracks -> "${action.mode}:tracks:${action.tracks.take(4).joinToString("/") { it.title }}"
                is PlannedAction.PlayPlaylist -> "playlist:${action.name}"
                is PlannedAction.LikeCurrent -> "like:${action.like}"
                is PlannedAction.LikeTrack -> "likeTrack:${action.target.artist.orEmpty()}-${action.target.title}:${action.like}"
                is PlannedAction.ModifyPlaylist -> "playlistModify:${action.playlistName}:${action.add}"
                is PlannedAction.CreatePlaylist -> "playlistCreate:${action.playlistName}:${action.tracks.size}"
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
                    .put("title", bounded(track.title, MAX_TRACK_FIELD_CHARS))
                    .put("artist", bounded(track.artist, MAX_TRACK_FIELD_CHARS)),
            )
        }
        return arr
    }

    private fun readTracks(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), MAX_TRACKS_PER_ENTRY)) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = bounded(obj.optString("title"), MAX_TRACK_FIELD_CHARS)
            val artist = bounded(obj.optString("artist"), MAX_TRACK_FIELD_CHARS)
            if (title.isNotBlank()) out.add(if (artist.isBlank()) title else "$title - $artist")
        }
        return out
    }

    private fun readEntries(): JSONArray =
        AgentLedgerPayloadCodec.entriesFrom(prefs.getString(KEY_ENTRIES, "[]"))

    private fun outcomeFor(validation: QueueValidation, results: List<ActionExecutionResult>): Outcome {
        val successful = results.count { it.success }
        return when {
            successful == results.size && successful > 0 && validation.passed -> Outcome.SUCCESS
            successful > 0 -> Outcome.PARTIAL_FAILURE
            else -> Outcome.FAILURE
        }
    }

    private fun parseOutcome(raw: String, success: Boolean): Outcome =
        runCatching { Outcome.valueOf(raw) }.getOrElse { if (success) Outcome.SUCCESS else Outcome.FAILURE }

    private fun bounded(value: String, maxChars: Int): String =
        AgentContextBuilder.sanitizeData(value, maxChars)

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
            val arr = AgentLedgerPayloadCodec.entriesFrom(prefs.getString(KEY_ENTRIES, "[]"))
            for (i in arr.length() - 1 downTo 0) {
                val obj = arr.optJSONObject(i) ?: continue
                val execution = obj.optString("execution")
                if (!execution.contains("$requestId:")) continue
                val proof = "playback_start:queueVersion=$queueVersion:started=$actuallyStarted:error=${error.take(80)}"
                obj.put("execution", listOf(execution, proof).filter { it.isNotBlank() }.joinToString("|").take(MAX_EXECUTION_CHARS))
                if (!actuallyStarted && error.isNotBlank()) {
                    obj.put("success", false)
                    obj.put("outcome", Outcome.FAILURE.name)
                }
                prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
                return
            }
        }

        const val PREFS_NAME = "claudio_agent_ledger"
        const val KEY_ENTRIES = "entries"
        const val KEY_ACTIVE_LISTENING_REQUEST = "active_listening_request"
        private const val KEY_PENDING_FAVORITE = "pending_favorite_v1"
        const val MAX_ENTRIES = 8
        private const val MAX_TURN_ID_CHARS = 100
        private const val MAX_USER_TEXT_CHARS = 240
        private const val MAX_PLAN_CHARS = 360
        private const val MAX_REPLY_CHARS = 420
        private const val MAX_VALIDATION_CHARS = 240
        private const val MAX_EXECUTION_CHARS = 360
        private const val MAX_TRACK_FIELD_CHARS = 160
        private const val MAX_TRACKS_PER_ENTRY = 6
    }
}
