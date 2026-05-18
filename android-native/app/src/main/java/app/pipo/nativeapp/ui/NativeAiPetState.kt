package app.pipo.nativeapp.ui

import android.content.Context
import java.time.LocalDate

internal data class PetMessage(val fromUser: Boolean, val text: String)

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

internal const val GREETING_SYSTEM = """你是 Claudio —— TA 熟到不用客气的音乐宠物。
打开 app 时由你说一句**进门招呼**(不是问候,是熟人语气的一句话陈述)。

# 要求
- **短**。≤18 字最好。一句话。
- 从 USER 给的"当下"里挑**一个**锚点(时段 / 天气 / 周几 / 临近周末 / 临近假期),别全报。
  临近周末或假期时优先用——那是 TA 心情会变化的信号。
- 不必问 TA 想听啥——陈述当下也行,反问也行(最多一次)。
- 绝不要客服腔("早上好!""希望您…")。不要双形容词对仗(既…又…)。不要感叹号。

# 输出格式
严格只输出这一句话,不要解释/JSON/markdown/引号。

# 参考样本(不同场景)
周日下午 → "周末还剩半天。"
周五晚上 → "明天就放假了。"
周一上午 → "周一这玩意。"
下雨天 → "下雨了。"
国庆前 3 天 → "假期还有 3 天就到。"
春节当天 → "过年好,听点啥?"
深夜 → "醒着呢。"
普通工作日下午 → "下午好懒。""""

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
