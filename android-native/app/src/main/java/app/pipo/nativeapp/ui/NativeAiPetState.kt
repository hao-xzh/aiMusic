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
