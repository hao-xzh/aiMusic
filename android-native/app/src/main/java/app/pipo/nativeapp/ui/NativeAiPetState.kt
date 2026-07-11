package app.pipo.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
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

    /**
     * 冷启动只回填一次：把 PetMemory 持久化的最近对话轮次还原成文字气泡。
     * 进程存活期间反复进入播放页不会重复回填（幂等），所以不会覆盖本会话已累积的卡片。
     * 卡片（放歌 / 收藏结果）不入 PetMemory，跨重启不还原；更早的轮次只在摘要里，也不重建。
     */
    @Synchronized
    fun hydrateOnce(turns: List<PetMemory.ConversationTurn>) {
        if (hydrated) return
        hydrated = true
        if (messages.isNotEmpty()) return
        for (t in turns) {
            if (t.text.isBlank()) continue
            messages.add(PetMessage(fromUser = t.role == PetMemory.ROLE_USER, text = t.text))
        }
    }

    /** 清空对话流（配合 PetMemory.clearConversation()）。清空后标记已水合，避免又被回填。 */
    @Synchronized
    fun clear() {
        messages.clear()
        hydrated = true
    }

    @Synchronized
    fun syncLatestPlayCardCount(queueCount: Int) {
        if (queueCount <= 0 || messages.isEmpty()) return
        val index = messages.indexOfLast { it.card is PetResultCard.Play }
        if (index != messages.lastIndex) return
        val message = messages[index]
        val card = message.card as? PetResultCard.Play ?: return
        if (card.insert || card.count == queueCount) return
        if (System.currentTimeMillis() - message.createdAtMillis > PLAY_CARD_QUEUE_SYNC_WINDOW_MS) return
        messages[index] = message.copy(card = card.copy(count = queueCount))
    }

    private const val PLAY_CARD_QUEUE_SYNC_WINDOW_MS = 20_000L
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
