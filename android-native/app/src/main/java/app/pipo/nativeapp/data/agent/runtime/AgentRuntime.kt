package app.pipo.nativeapp.data.agent.runtime

import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.TurnOutcome
import app.pipo.nativeapp.data.agent.domain.TurnTrace
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder
import app.pipo.nativeapp.data.agent.resolve.MusicResolver

/** Signals the durable task layer whether a tool-loop failure should be attempted again. */
class AgentTurnExecutionException(
    val retryable: Boolean,
    reason: String,
) : IllegalStateException(reason)

/**
 * 安卓音乐 Agent 的唯一入口。
 *
 * 设计原则（见 `音乐Agent根本优化方案.md`）：用户的每一句话都由 LLM 理解、由 LLM 用工具操作。
 * 这里不再有「确定性 Planner + regex 兜底」那条与模型抢理解的旧管线——只剩一条 LLM 驱动的
 * 工具循环。代码只提供工具（搜索 / 起草队列 / 提交播放 / 收藏 / 改歌单）和事实校验
 * （队列校验 + 回复校验），不替模型决定意图，也不在模型之外硬执行播放。
 *
 * `resolver` / `queuePlanner` / `replyGrounder` 都是工具循环背后的「引擎」：
 * `draft_queue` 调 resolver+queuePlanner 起草并校验，`commit_queue` 才真正交给播放器，
 * 回复统一过 replyGrounder（内部已接 ReplyVerifier）。
 */
class AgentRuntime(
    private val repository: PipoRepository,
    private val ledger: AgentLedgerStore,
    private val resolver: MusicResolver = MusicResolver(repository),
    private val queuePlanner: AgentQueuePlanner = AgentQueuePlanner(),
    // 动作回复必须基于已执行事实；本地模板已经过 ReplyVerifier，不再为“说做完了”额外
    // 发起第三次 LLM 请求。这样播放成功的常规请求只需一次工具规划调用。
    private val replyGrounder: ReplyGrounder = ReplyGrounder(),
) {
    private val toolLoop by lazy {
        AgentToolLoop(
            repository = repository,
            ledger = ledger,
            resolver = resolver,
            queuePlanner = queuePlanner,
            replyGrounder = replyGrounder,
        )
    }

    suspend fun handle(
        input: AgentTurnInput,
        executor: AgentActionExecutor,
    ): TurnOutcome = toolLoop.run(input, executor) ?: aiToolLoopUnavailable(input)

    /**
     * 工具链彻底没跑通时的诚实失败：不播放、不假装成功，只如实说没跑通。
     * 这是「言行一致」的一部分，不是代码兜底执行。
     */
    private fun aiToolLoopUnavailable(input: AgentTurnInput): TurnOutcome {
        val reply = "刚才 AI 工具链没跑通，我没有执行播放操作。你再发一次，我会重新判断。"
        return TurnOutcome(
            reply = reply,
            cards = emptyList(),
            trace = TurnTrace(
                turnId = "tool_loop_unavailable",
                plannerRaw = "tool_loop_unavailable:no_local_fallback",
                normalizedPlan = "",
                resolution = "",
                queuePlan = "",
                validation = "ai_tool_loop_unavailable",
                execution = "say:false:accepted=false:err=ai_tool_loop_unavailable",
                finalReply = reply,
            ),
            musicReferences = input.musicReferences,
        )
    }
}
