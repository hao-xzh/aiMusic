package app.pipo.nativeapp.ui

import android.content.Context
import java.time.LocalDate

internal data class PetMessage(val fromUser: Boolean, val text: String)

/**
 * AI 宠物的单曲点评控制状态。它必须是 process-wide，而不是 Composable remember 状态：
 * NativeAiPet 在多个路由里会重新 compose，同一首歌是否评过需要跨路由保持。
 */
internal object PetBubbleState {
    @Volatile var lastCommentedKey: String? = null
    @Volatile var previousTrack: Pair<String, String>? = null
    @Volatile var positionInQueue: Int = 0
    @Volatile var lastUserContext: String? = null
    val recentChanges: MutableList<Long> = mutableListOf()
    @Volatile var cooldownUntil: Long = 0L

    @Synchronized
    fun resetForNewQueue() {
        lastCommentedKey = null
        previousTrack = null
        positionInQueue = 0
        recentChanges.clear()
        cooldownUntil = 0L
    }
}

internal object PetBubbleStateAccessor {
    fun resetForNewQueue() = PetBubbleState.resetForNewQueue()
}

internal val EMPTY_HINTS = listOf("在。说吧。", "醒着呢。", "嗯？", "想听啥。", "随便说。", "说点。", "嗯。")

internal const val TRACK_COMMENT_SYSTEM = """你是 Claudio,一只幽默抽象的音乐宠物。
每当一首歌开始播,你说一句**为什么放这首给 TA**。

# 调性
- 短。一句话。能短就短,5-8 字最佳,绝不超过 16 字。
- 不是介绍歌,是说"为什么它适合这一刻"。
- 抽象比喻 OK,但要跟 TA 的话 / 这首歌的特征 / 当下时刻接得上。
- 把当下时段/天气/临近周末或假期当作锚点之一,但只挑最相关的一个,别一句话报全。
- 不要客服词("为您""推荐"),不要感叹号,不要 emoji。

# 输出格式
直接输出这一句话本身——不要 JSON、不要前缀、不要引号、不要解释。

# 示例(注意时间/天气/假期可以是锚点)
TA："今天好累",播 Coldplay → "拿这个把电量充回去。"
周五晚上,播 city pop → "周末已经在门口。"
下雨,播 ambient → "雨配这个,正好。"
再 3 天国庆,播 funk → "假期心情先到。"
TA："我刚分手",播 The Killers → "猛的,开场。"
深夜,播 lo-fi → "适合熬。"
周一上午,播 indie folk → "周一不该这么吵。"
接歌,前激情本慢 → "降一点速。"
接歌,同艺人连排 → "再多听 TA 一首。"
开场无 context,播 indie folk → "这首是入口。""""

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
