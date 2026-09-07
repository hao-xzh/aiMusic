package app.pipo.nativeapp.data.agent.memory

import app.pipo.nativeapp.data.agent.domain.*
import app.pipo.nativeapp.data.agent.runtime.AgentListeningRequestCodec

/** Only SharedPreferences persistence is replaced; no real user history is touched. */
class AgentLedgerStore {
    private val entries = mutableListOf<LedgerEntry>()
    private var listeningRequest = ""
    fun recent(limit: Int = 5): List<LedgerEntry> = entries.takeLast(limit.coerceAtLeast(0))
    fun record(plan: MusicTurnPlan, validation: QueueValidation,
               results: List<ActionExecutionResult>, finalReply: String) {
        val outcome = when {
            results.isNotEmpty() && results.all { it.success } && validation.passed -> Outcome.SUCCESS
            results.any { it.success } -> Outcome.PARTIAL_FAILURE
            else -> Outcome.FAILURE
        }
        entries.add(LedgerEntry(plan.turnId, plan.userText, "", finalReply,
            outcome == Outcome.SUCCESS, outcome, results.flatMap { it.tracks }.map { it.title },
            validation.messages.joinToString("|"), "", System.currentTimeMillis()))
    }
    fun activeListeningRequest(): String = AgentListeningRequestCodec.canonicalize(listeningRequest).orEmpty()
    fun saveListeningRequest(requestJson: String): Boolean {
        val canonical = AgentListeningRequestCodec.canonicalize(requestJson) ?: return false
        listeningRequest = canonical
        return true
    }
    data class LedgerEntry(val turnId: String, val userText: String, val normalizedPlan: String,
        val finalReply: String, val success: Boolean, val outcome: Outcome, val firstTracks: List<String>,
        val validation: String, val execution: String, val tsMs: Long)
    enum class Outcome { SUCCESS, PARTIAL_FAILURE, FAILURE }
}
