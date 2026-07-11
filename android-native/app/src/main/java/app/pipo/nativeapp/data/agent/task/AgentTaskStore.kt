package app.pipo.nativeapp.data.agent.task

import android.content.Context
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
    fun enqueue(userText: String, contextJson: String = ""): AgentTask {
        val now = System.currentTimeMillis()
        // Never cut a serialized JSON snapshot in the middle: an invalid snapshot makes
        // process-death recovery fail before the agent can even inspect the request.
        val task = AgentTask(UUID.randomUUID().toString(), userText, contextJson, AgentTaskStatus.QUEUED, 0, "", "", now, now)
        write(readAll() + task)
        return task
    }

    @Synchronized
    fun claim(id: String): AgentTask? {
        val current = readAll().firstOrNull { it.id == id } ?: return null
        if (current.status == AgentTaskStatus.SUCCEEDED || current.status == AgentTaskStatus.FAILED) return current
        val claimed = current.copy(status = AgentTaskStatus.RUNNING, attempts = current.attempts + 1, updatedAt = System.currentTimeMillis())
        write(readAll().map { if (it.id == id) claimed else it })
        return claimed
    }

    @Synchronized
    fun succeed(id: String, reply: String) {
        update(id) {
            it.copy(
                contextJson = "",
                status = AgentTaskStatus.SUCCEEDED,
                resultReply = reply.take(2000),
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
    fun fail(id: String, error: String, reply: String = "这次请求没能完成，请检查网络或 AI 配置后重试。") {
        update(id) {
            it.copy(
                contextJson = "",
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
                add(AgentTask(o.optString("id"), o.optString("text"), o.optString("context"), AgentTaskStatus.from(o.optString("status")), o.optInt("attempts"), o.optString("error"), o.optString("reply"), o.optLong("created"), o.optLong("updated")))
            }
        }.takeLast(MAX_TASKS)
    }

    private fun write(tasks: List<AgentTask>) {
        val arr = JSONArray()
        tasks.takeLast(MAX_TASKS).forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("text", t.userText).put("context", t.contextJson).put("status", t.status.name).put("attempts", t.attempts).put("error", t.error).put("reply", t.resultReply).put("created", t.createdAt).put("updated", t.updatedAt))
        }
        // commit keeps the enqueue durable before WorkManager is scheduled.
        prefs.edit().putString(KEY, arr.toString()).commit()
        _tasks.value = tasks.takeLast(MAX_TASKS)
    }

    companion object { private const val PREFS = "claudio_agent_tasks"; private const val KEY = "queue"; private const val MAX_TASKS = 32 }
}

data class AgentTask(val id: String, val userText: String, val contextJson: String = "", val status: AgentTaskStatus, val attempts: Int, val error: String, val resultReply: String, val createdAt: Long, val updatedAt: Long)
enum class AgentTaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED; companion object { fun from(raw: String) = entries.firstOrNull { it.name == raw } ?: QUEUED } }
