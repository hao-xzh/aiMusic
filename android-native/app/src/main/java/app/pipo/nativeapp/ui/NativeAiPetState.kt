package app.pipo.nativeapp.ui

import android.content.Context
import java.time.LocalDate

internal data class PetMessage(
    val fromUser: Boolean,
    val text: String,
    /** 非空时这条消息渲染成结果卡片(放歌/收藏/加歌单等),而不是纯文字气泡。 */
    val card: PetResultCard? = null,
)

/**
 * 助手写动作的可视化结果,进对话流。映射 PetAgent.AgentAction —— 比纯文字"已加心"更像 AI 产品。
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
    data class Action(val glyph: String, val label: String, val ok: Boolean = true) : PetResultCard
}

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
