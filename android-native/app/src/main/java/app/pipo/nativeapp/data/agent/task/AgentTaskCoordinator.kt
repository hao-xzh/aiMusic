package app.pipo.nativeapp.data.agent.task

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PetMemory
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.agent.domain.TurnOutcome
import app.pipo.nativeapp.data.agent.runtime.AgentTurnExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AgentTaskGateway {
    suspend fun execute(task: AgentTask): TurnOutcome
}

/** Process-level owner: UI composition may disappear, but this scope and durable queue do not. */
class AgentTaskCoordinator(private val context: Context) {
    val store = AgentTaskStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var gateway: AgentTaskGateway? = null
    private val detachedGateway: AgentTaskGateway by lazy { BackgroundAgentTaskGateway(context) }

    fun registerGateway(value: AgentTaskGateway) { gateway = value; scheduleRecovery() }
    fun unregisterGateway(value: AgentTaskGateway) { if (gateway === value) gateway = null }

    fun submit(userText: String, contextJson: String = "", onFinished: (Result<TurnOutcome>) -> Unit = {}): AgentTask {
        val retriedTask = if (isRetryOnlyRequest(userText)) store.retryLatestFailed() else null
        val task = retriedTask ?: store.enqueue(userText, contextJson)
        if (retriedTask?.error == AgentTaskStore.INTERRUPTED_EXECUTION_RESULT_UNKNOWN) {
            DiagnosticsLogStore.record(
                "ai_agent_task",
                "manual_retry_blocked_unknown_result",
                mapOf("taskId" to task.id),
            )
            scope.launch {
                onFinished(Result.failure(AgentTurnExecutionException(false, task.error)))
            }
            return task
        }
        DiagnosticsLogStore.record(
            "ai_agent_task",
            if (retriedTask == null) "enqueued" else "manual_retry_enqueued",
            mapOf("taskId" to task.id, "contextBytes" to task.contextJson.length),
        )
        scope.launch {
            // Persist the user's side as soon as the durable task exists. taskId makes
            // this idempotent with process-recovery / retry execution below.
            runCatching {
                PipoGraph.petMemory.recordConversationTurn(
                    PetMemory.ROLE_USER,
                    task.userText,
                    task.id,
                )
            }
            execute(task.id, onFinished)
        }
        scheduleRecovery()
        return task
    }

    suspend fun executePersisted(taskId: String): Boolean = execute(taskId) {}

    private suspend fun execute(taskId: String, onFinished: (Result<TurnOutcome>) -> Unit): Boolean = mutex.withLock {
        val task = store.claim(taskId) ?: return@withLock false
        if (task.status == AgentTaskStatus.SUCCEEDED) return@withLock true
        if (task.status == AgentTaskStatus.FAILED) return@withLock false
        val activeGateway = gateway ?: detachedGateway
        val startedAt = System.currentTimeMillis()
        // WorkManager may win the race with submit's IO coroutine, or recreate the
        // process entirely. Ensure the question is durable before doing expensive work.
        runCatching {
            PipoGraph.petMemory.recordConversationTurn(
                PetMemory.ROLE_USER,
                task.userText,
                task.id,
            )
        }
        DiagnosticsLogStore.record(
            "ai_agent_task",
            "started",
            mapOf("taskId" to task.id, "attempt" to task.attempts, "gateway" to if (gateway == null) "background" else "ui"),
        )
        return@withLock try {
            val outcome = activeGateway.execute(task)
            store.succeed(task.id, outcome.reply)
            // Conversation memory belongs to the durable task owner, not the page.
            // This also persists results produced after the UI or process has gone away.
            runCatching {
                PipoGraph.petMemory.recordConversationTurn(
                    PetMemory.ROLE_ASSISTANT,
                    outcome.reply,
                    task.id,
                )
            }
            runCatching { PipoGraph.petMemory.recordMusicReferences(outcome.musicReferences) }
            DiagnosticsLogStore.record(
                "ai_agent_task",
                "succeeded",
                mapOf("taskId" to task.id, "attempt" to task.attempts, "elapsedMs" to (System.currentTimeMillis() - startedAt)),
            )
            onFinished(Result.success(outcome))
            true
        } catch (error: Throwable) {
            if (error is CancellationException) {
                store.retry(task.id, "cancelled")
                throw error
            }
            val terminal = task.attempts >= MAX_ATTEMPTS ||
                (error is AgentTurnExecutionException && !error.retryable)
            if (terminal) {
                store.fail(task.id, error.message ?: error::class.java.simpleName)
                onFinished(Result.failure(error))
            } else {
                // A recoverable failure remains pending. Do not tell the page that the
                // request is terminal while WorkManager is about to retry it.
                store.retry(task.id, error.message ?: error::class.java.simpleName)
            }
            DiagnosticsLogStore.record(
                "ai_agent_task",
                if (terminal) "failed" else "retry_queued",
                mapOf(
                    "taskId" to task.id,
                    "attempt" to task.attempts,
                    "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    "errorType" to error::class.java.simpleName,
                ),
            )
            false
        }
    }

    fun scheduleRecovery() {
        if (store.pending().isEmpty()) return
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Always leave a successor behind. KEEP can lose a just-enqueued turn when the
        // currently running Worker took its pending snapshot a moment earlier.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object { private const val UNIQUE_WORK = "claudio-agent-task-recovery"; private const val MAX_ATTEMPTS = 3 }
}
