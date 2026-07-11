package app.pipo.nativeapp.data.agent.task

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pipo.nativeapp.data.PipoGraph

/** Retries durable tasks after process death through the detached MediaController gateway. */
class AgentTaskWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val coordinator = runCatching { PipoGraph.agentTasks }.getOrNull() ?: return Result.retry()
        val pending = coordinator.store.pending()
        if (pending.isEmpty()) return Result.success()
        for (task in pending) coordinator.executePersisted(task.id)
        // One successful task must not hide another task that was re-queued in the same pass.
        return if (coordinator.store.pending().isEmpty()) Result.success() else Result.retry()
    }
}
