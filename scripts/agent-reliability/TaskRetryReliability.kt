import android.content.Context
import app.pipo.nativeapp.data.agent.task.AgentTaskStore
import app.pipo.nativeapp.data.agent.task.AgentTaskStatus
import app.pipo.nativeapp.data.agent.task.isRetryOnlyRequest

object TaskRetryReliability {
    fun runCases(report: (String, String, Boolean, String, Any?) -> Unit) {
        val context = Context()
        val store = AgentTaskStore(context)
        val original = store.enqueue("来一点安静的中文歌，第一首要易烊千玺的粉雾海", "{\"currentTrack\":\"original\"}")
        store.claim(original.id)
        store.fail(original.id, "timeout")
        val reloaded = AgentTaskStore(context)
        val retry = reloaded.retryLatestFailed()
        report("retry_original_full_request", "失败后再试试", retry?.id == original.id && retry.userText == original.userText &&
            retry.contextJson == original.contextJson && retry.status == AgentTaskStatus.QUEUED && retry.attempts == 0,
            "reload retains original request, context and task id", retry)
        report("retry_pending_not_requeued", "重复点击重试", reloaded.retryLatestFailed() == null,
            "pending task is not reopened", reloaded.tasks.value)
        reloaded.succeed(original.id, "已排好12首")
        val succeededClaim = reloaded.claim(original.id)
        report("retry_success_not_replayed", "成功后再试试", succeededClaim?.status == AgentTaskStatus.SUCCEEDED &&
            reloaded.retryLatestFailed() == null,
            "successful task side effects cannot be replayed as failed retry", succeededClaim)
        val interrupted = reloaded.enqueue("播放并收藏当前歌曲", "{\"currentTrack\":\"interrupted\"}")
        reloaded.claim(interrupted.id)
        val afterRebuild = AgentTaskStore(context)
        val interruptedClaim = afterRebuild.claim(interrupted.id)
        report("interrupted_running_fails_without_replay", "进程重建后恢复执行", interruptedClaim?.let { claim ->
            claim.status == AgentTaskStatus.FAILED &&
                claim.error == AgentTaskStore.INTERRUPTED_EXECUTION_RESULT_UNKNOWN &&
                claim.contextJson == interrupted.contextJson &&
                claim.userText == interrupted.userText &&
                claim.resultReply == AgentTaskStore.INTERRUPTED_EXECUTION_REPLY
        } == true,
            "running task with unknown side-effect outcome becomes a durable failed task", interruptedClaim)
        report("retry_unknown_interrupted_not_requeued", "中断后再试试", afterRebuild.retryLatestFailed()?.let { task ->
            task.id == interrupted.id && task.status == AgentTaskStatus.FAILED &&
                task.error == AgentTaskStore.INTERRUPTED_EXECUTION_RESULT_UNKNOWN
        } == true,
            "retry-only request leaves unknown interrupted task terminal", afterRebuild.tasks.value)
        val older = reloaded.enqueue("旧请求")
        val newer = reloaded.enqueue("新请求")
        reloaded.succeed(newer.id, "新请求成功")
        reloaded.fail(older.id, "late background failure")
        report("retry_does_not_revive_older_failure", "新请求成功，旧任务晚到失败", reloaded.retryLatestFailed() == null,
            "latest user request determines retry target, not background timestamp", reloaded.tasks.value)
        report("retry_text_is_narrow", "再试试 vs 再试试周杰伦的歌", isRetryOnlyRequest("再试试！") &&
            isRetryOnlyRequest("重试一下") && !isRetryOnlyRequest("再试试周杰伦的歌") && !isRetryOnlyRequest("重新播放晴天"),
            "contentful instruction stays a new task", null)
        report("retry_no_failed_task", "空记录重试", AgentTaskStore(Context()).retryLatestFailed() == null,
            "no fabricated original request", null)
    }
}
