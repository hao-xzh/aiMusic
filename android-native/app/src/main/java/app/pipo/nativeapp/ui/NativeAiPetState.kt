package app.pipo.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import app.pipo.nativeapp.data.PetMemory
import java.time.LocalDate

internal data class PetMessage(
    val fromUser: Boolean,
    val text: String,
    /** 非空时这条消息渲染成结果卡片(放歌/收藏/加歌单等),而不是纯文字气泡。 */
    val card: PetResultCard? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** Durable agent task id, used to make foreground callback + task observer delivery idempotent. */
    val taskId: String? = null,
    /** Durable submission order shared by the user turn, reply and result cards. */
    val conversationSequence: Long? = null,
    /** Order among result cards produced by the same durable task. */
    val resultCardIndex: Int? = null,
)

/**
 * 助手写动作的可视化结果,进对话流。映射 AgentRuntime 的真实执行结果 —— 比纯文字"已加心"更像 AI 产品。
 */
internal sealed interface PetResultCard {
    /** 放歌:封面缩略 + 数量 + 艺人。[insert]=插一首,[similar]=配同款。 */
    data class Play(
        val count: Int,
        val artists: String,
        val covers: List<String?>,
        val insert: Boolean,
        val similar: Boolean,
    ) : PetResultCard

    /** like / unlike / 进出歌单 / 切歌 等单行动作 chip。[ok]=false 渲染成失败态。 */
    data class Action(val icon: PetActionIcon, val label: String, val ok: Boolean = true) : PetResultCard
}

/**
 * 动作 chip 用的语义图标。UI 据此画矢量图标（Glyphs.kt），
 * 不再用 emoji 文本字符 —— 那种字符基线/边距随系统字体漂移，对不齐也不居中。
 */
internal enum class PetActionIcon { Skip, Like, Unlike, PlaylistAdd, PlaylistRemove, Error }

/**
 * AI 宠物的轻量上下文状态。播放链路保持沉默，只保留用户主动聊天的最近一句。
 */
internal object PetBubbleState {
    @Volatile var lastUserContext: String? = null

    @Synchronized
    fun resetForNewQueue() {
        lastUserContext = null
    }
}

internal object PetBubbleStateAccessor {
    fun resetForNewQueue() = PetBubbleState.resetForNewQueue()
}

/**
 * 进程级保留的 AI 对话流。NativeAiPet 只在 Player root 条件挂载（离开播放页 / 进沉浸式歌词 /
 * 横屏都会卸载它），若把 messages 放在 composable 的 remember 里，一离开就被丢弃、回来变空白
 * —— 而底层 PetMemory 一直持久化着对话。把列表提到这里：只要进程没被杀就一直在（导航来回、
 * 看歌词、横竖屏切换全保留，含放歌 / 收藏的结果卡片）；进程被杀后由 [hydrateOnce] 从 PetMemory
 * 冷启动回填最近文本轮次，和「AI 重启后还记得你」对齐。
 */
internal object PetChatStore {
    val messages: SnapshotStateList<PetMessage> = mutableStateListOf()

    @Volatile
    private var hydrated = false

    /** Changes on persistent clear, so an already-running observer stops exposing old tasks. */
    var conversationEpoch by mutableStateOf(0L)
        private set

    /**
     * 冷启动只回填一次：把 PetMemory 持久化的最近对话轮次还原成文字气泡。
     * 进程存活期间反复进入播放页不会重复回填（幂等），所以不会覆盖本会话已累积的卡片。
     * 卡片不入 PetMemory，由任务状态观察器恢复；更早的轮次只在摘要里，也不重建。
     */
    @Synchronized
    fun hydrateOnce(
        context: PetMemory.ConversationContext,
        taskSequenceById: Map<String, Long> = emptyMap(),
    ) {
        if (hydrated) return
        if (context.conversationEpoch < conversationEpoch) return
        hydrated = true
        conversationEpoch = context.conversationEpoch
        for (t in context.turns) {
            if (t.text.isBlank()) continue
            val fromUser = t.role == PetMemory.ROLE_USER
            val alreadyPresent = if (t.taskId.isNotBlank()) {
                messages.any { it.taskId == t.taskId && it.fromUser == fromUser && it.card == null }
            } else {
                messages.any { it.taskId == null && it.fromUser == fromUser && it.text == t.text }
            }
            if (!alreadyPresent) {
                messages.add(
                    PetMessage(
                        fromUser = fromUser,
                        text = t.text,
                        createdAtMillis = t.tsSec * 1000L,
                        taskId = t.taskId.ifBlank { null },
                        conversationSequence = t.taskSequence.takeIf { it > 0L }
                            ?: taskSequenceById[t.taskId]?.takeIf { it > 0L },
                    ),
                )
            }
        }
        sortMessages()
    }

    /** 清空对话流（配合 PetMemory.clearConversation()）并切到下一持久会话。 */
    @Synchronized
    fun clear(nextConversationEpoch: Long) {
        if (nextConversationEpoch < conversationEpoch) return
        messages.clear()
        conversationEpoch = nextConversationEpoch
        hydrated = true
    }

    fun isCurrentConversation(taskConversationEpoch: Long): Boolean = taskConversationEpoch == conversationEpoch

    @Synchronized
    fun sortMessages() {
        messages.sortWith { left, right ->
            val leftSequence = left.conversationSequence ?: left.createdAtMillis
            val rightSequence = right.conversationSequence ?: right.createdAtMillis
            val sequenceComparison = leftSequence.compareTo(rightSequence)
            if (sequenceComparison != 0) return@sortWith sequenceComparison
            val partComparison = messagePart(left).compareTo(messagePart(right))
            if (partComparison != 0) return@sortWith partComparison
            val cardComparison = (left.resultCardIndex ?: 0).compareTo(right.resultCardIndex ?: 0)
            if (cardComparison != 0) return@sortWith cardComparison
            left.createdAtMillis.compareTo(right.createdAtMillis)
        }
    }

    private fun messagePart(message: PetMessage): Int = when {
        message.fromUser -> 0
        message.card == null -> 1
        else -> 2
    }
}

internal val EMPTY_HINTS = listOf("在。说吧。", "醒着呢。", "嗯？", "想听啥。", "随便说。", "说点。", "嗯。")

/** 空态可点的建议提示(直接发送)—— 覆盖放歌 / 回忆 / 类似 / 场景几类高频意图。 */
internal val SUGGESTED_PROMPTS = listOf("放点燃的", "我刚才听啥", "来点类似的", "陪我熬夜")

private const val GREET_PREFS = "claudio_pet"
private const val GREET_KEY = "last_greet_date"

internal fun shouldGreetToday(context: Context): Boolean {
    val prefs = context.getSharedPreferences(GREET_PREFS, Context.MODE_PRIVATE)
    val today = LocalDate.now().toString()
    return prefs.getString(GREET_KEY, null) != today
}

internal fun markGreeted(context: Context) {
    val prefs = context.getSharedPreferences(GREET_PREFS, Context.MODE_PRIVATE)
    prefs.edit().putString(GREET_KEY, LocalDate.now().toString()).apply()
}
