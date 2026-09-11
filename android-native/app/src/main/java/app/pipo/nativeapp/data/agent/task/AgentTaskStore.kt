package app.pipo.nativeapp.data.agent.task

import android.content.Context
import app.pipo.nativeapp.data.agent.domain.AgentUiCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** SharedPreferences backed queue. A task is durable before any LLM/tool work starts. */
class AgentTaskStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _tasks = MutableStateFlow(readAll())
    val tasks: StateFlow<List<AgentTask>> = _tasks

    @Synchronized
    fun enqueue(userText: String, contextJson: String = "", conversationEpoch: Long = 0L): AgentTask {
        val now = System.currentTimeMillis()
        // Never cut a serialized JSON snapshot in the middle: an invalid snapshot makes
        // process-death recovery fail before the agent can even inspect the request.
        val tasks = readAll()
        val sequence = maxOf(
            now,
            prefs.getLong(KEY_LAST_CONVERSATION_SEQUENCE, 0L),
            tasks.maxOfOrNull { it.conversationSequence.takeIf { sequence -> sequence > 0L } ?: it.createdAt } ?: 0L,
        ) + 1L
        val task = AgentTask(UUID.randomUUID().toString(), userText, contextJson, AgentTaskStatus.QUEUED, 0, "", "", now, now, conversationEpoch, sequence)
        write(tasks + task, sequence)
        return task
    }

    @Synchronized
    fun claim(id: String): AgentTask? {
        val current = readAll().firstOrNull { it.id == id } ?: return null
        if (current.status == AgentTaskStatus.SUCCEEDED || current.status == AgentTaskStatus.FAILED) return current
        if (current.status == AgentTaskStatus.RUNNING) {
            // A new coordinator can only observe RUNNING after the prior process stopped
            // between a side effect and its terminal receipt. The completed subset is
            // unknowable, so replaying this task could repeat playback or mutations.
            val interrupted = current.copy(
                status = AgentTaskStatus.FAILED,
                error = INTERRUPTED_EXECUTION_RESULT_UNKNOWN,
                resultReply = INTERRUPTED_EXECUTION_REPLY,
                updatedAt = System.currentTimeMillis(),
            )
            write(readAll().map { if (it.id == id) interrupted else it })
            return interrupted
        }
        val claimed = current.copy(status = AgentTaskStatus.RUNNING, attempts = current.attempts + 1, updatedAt = System.currentTimeMillis())
        write(readAll().map { if (it.id == id) claimed else it })
        return claimed
    }

    @Synchronized
    fun succeed(id: String, reply: String, cards: List<AgentUiCard>) {
        update(id) {
            it.copy(
                contextJson = "",
                status = AgentTaskStatus.SUCCEEDED,
                resultReply = reply.take(2000),
                resultCards = cards,
                error = "",
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    @Synchronized
    fun retry(id: String, error: String) {
        update(id) { it.copy(status = AgentTaskStatus.QUEUED, error = error.take(500), updatedAt = System.currentTimeMillis()) }
    }

    @Synchronized
    fun retryLatestFailed(conversationEpoch: Long): AgentTask? {
        val tasks = readAll()
        // Submission order is conversation order; a late background failure must not
        // overtake a newer user request just because its updatedAt is more recent.
        val failed = tasks.lastOrNull()
            ?.takeIf { it.status == AgentTaskStatus.FAILED && it.conversationEpoch == conversationEpoch }
            ?: return null
        if (failed.error == INTERRUPTED_EXECUTION_RESULT_UNKNOWN) return failed
        val retried = failed.copy(
            status = AgentTaskStatus.QUEUED,
            attempts = 0,
            error = "",
            resultReply = "",
            resultCards = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )
        write(tasks.map { if (it.id == failed.id) retried else it })
        return retried
    }

    @Synchronized
    fun fail(id: String, error: String, reply: String = "这次请求没能完成，请重试。") {
        update(id) {
            it.copy(
                status = AgentTaskStatus.FAILED,
                error = error.take(500),
                resultReply = reply.take(2000),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun pending(): List<AgentTask> = readAll().filter { it.status == AgentTaskStatus.QUEUED || it.status == AgentTaskStatus.RUNNING }

    private fun update(id: String, transform: (AgentTask) -> AgentTask) = write(readAll().map { if (it.id == id) transform(it) else it })

    private fun readAll(): List<AgentTask> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    AgentTask(
                        id = o.optString("id"),
                        userText = o.optString("text"),
                        contextJson = o.optString("context"),
                        status = AgentTaskStatus.from(o.optString("status")),
                        attempts = o.optInt("attempts"),
                        error = o.optString("error"),
                        resultReply = o.optString("reply"),
                        createdAt = o.optLong("created"),
                        updatedAt = o.optLong("updated"),
                        conversationEpoch = o.optLong("conversationEpoch"),
                        conversationSequence = o.optLong("conversationSequence"),
                        resultCards = o.optJSONArray("cards").toAgentUiCards(),
                    ),
                )
            }
        }.takeLast(MAX_TASKS)
    }

    private fun write(tasks: List<AgentTask>, latestConversationSequence: Long = prefs.getLong(KEY_LAST_CONVERSATION_SEQUENCE, 0L)) {
        val arr = JSONArray()
        tasks.takeLast(MAX_TASKS).forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("text", t.userText)
                    .put("context", t.contextJson)
                    .put("status", t.status.name)
                    .put("attempts", t.attempts)
                    .put("error", t.error)
                    .put("reply", t.resultReply)
                    .put("cards", JSONArray().apply { t.resultCards.forEach { put(it.toJson()) } })
                    .put("created", t.createdAt)
                    .put("updated", t.updatedAt)
                    .put("conversationEpoch", t.conversationEpoch)
                    .put("conversationSequence", t.conversationSequence),
            )
        }
        // commit keeps the enqueue durable before WorkManager is scheduled.
        prefs.edit()
            .putString(KEY, arr.toString())
            .putLong(
                KEY_LAST_CONVERSATION_SEQUENCE,
                maxOf(
                    latestConversationSequence,
                    tasks.maxOfOrNull { it.conversationSequence.takeIf { sequence -> sequence > 0L } ?: it.createdAt } ?: 0L,
                ),
            )
            .commit()
        _tasks.value = tasks.takeLast(MAX_TASKS)
    }

    companion object {
        const val INTERRUPTED_EXECUTION_RESULT_UNKNOWN = "interrupted_execution_result_unknown"
        const val INTERRUPTED_EXECUTION_REPLY = "上次请求被中断，可能已有部分操作完成。请先查看当前队列，再明确要继续的操作。"

        private const val PREFS = "claudio_agent_tasks"
        private const val KEY = "queue"
        private const val KEY_LAST_CONVERSATION_SEQUENCE = "last_conversation_sequence"
        private const val MAX_TASKS = 32
    }
}

data class AgentTask(
    val id: String,
    val userText: String,
    val contextJson: String = "",
    val status: AgentTaskStatus,
    val attempts: Int,
    val error: String,
    val resultReply: String,
    val createdAt: Long,
    val updatedAt: Long,
    val conversationEpoch: Long = 0L,
    val conversationSequence: Long = 0L,
    val resultCards: List<AgentUiCard> = emptyList(),
)
enum class AgentTaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED; companion object { fun from(raw: String) = entries.firstOrNull { it.name == raw } ?: QUEUED } }

private fun AgentUiCard.toJson(): JSONObject = JSONObject()
    .put("kind", kind.name)
    .put("label", label)
    .put("ok", ok)
    .put("count", count)
    .put("artists", artists)
    .put("covers", JSONArray().apply { covers.forEach { put(it) } })
    .put("insert", insert)
    .put("similar", similar)

private fun JSONArray?.toAgentUiCards(): List<AgentUiCard> = buildList {
    val source = this@toAgentUiCards ?: return@buildList
    for (index in 0 until source.length()) {
        val item = source.optJSONObject(index) ?: continue
        val kind = runCatching { AgentUiCard.Kind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
        val covers = item.optJSONArray("covers")?.let { coverArray ->
            buildList {
                for (coverIndex in 0 until coverArray.length()) {
                    add(if (coverArray.isNull(coverIndex)) null else coverArray.optString(coverIndex))
                }
            }
        }.orEmpty()
        add(
            AgentUiCard(
                kind = kind,
                label = item.optString("label"),
                ok = item.optBoolean("ok", true),
                count = item.optInt("count"),
                artists = item.optString("artists"),
                covers = covers,
                insert = item.optBoolean("insert"),
                similar = item.optBoolean("similar"),
            ),
        )
    }
}

internal fun isRetryOnlyRequest(userText: String): Boolean {
    val normalized = userText.trim().replace(Regex("[\\s，。！!？?、]+"), "")
    return normalized in setOf("再试", "再试试", "再试一下", "再试一次", "重试", "重试一下", "重试一次", "重新试", "重新试一下", "重新试一次")
}
