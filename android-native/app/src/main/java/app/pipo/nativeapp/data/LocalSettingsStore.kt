package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pipo-native-settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<NativeSettings> = state.asStateFlow()

    @android.annotation.SuppressLint("ApplySharedPref")
    fun update(next: NativeSettings) {
        // 用 commit() 同步写盘后再更新 StateFlow:之前 apply() 是异步队列,
        // "改 toggle → 立刻杀进程" 的 ~100ms 窗口里,内存 state.value 已是新值
        // 但 disk 没刷,下次启动 read() 拿到旧值 → 设置回滚。
        // 设置页改 toggle 是低频操作,commit 阻塞 ~10-30ms 用户感知不明显;
        // SuppressLint 是因为 Lint 默认劝用 apply,这里有意选 commit。
        prefs.edit()
            .putBoolean(KEY_HIDE_DOTS, next.hideDotPattern)
            .putBoolean(KEY_WORKDAY_AUTOPLAY, next.workdayAutoplay)
            .putBoolean(KEY_PAUSE_MEETINGS, next.pauseDuringMeetings)
            .putBoolean(KEY_LUNCH_RELAX, next.lunchRelaxMode)
            .putBoolean(KEY_AI_NARRATION, next.aiNarration)
            .putString(KEY_USER_FACTS, next.userFacts)
            .commit()
        state.value = next
    }

    private fun read(): NativeSettings {
        return NativeSettings(
            hideDotPattern = prefs.getBoolean(KEY_HIDE_DOTS, false),
            workdayAutoplay = prefs.getBoolean(KEY_WORKDAY_AUTOPLAY, true),
            pauseDuringMeetings = prefs.getBoolean(KEY_PAUSE_MEETINGS, true),
            lunchRelaxMode = prefs.getBoolean(KEY_LUNCH_RELAX, false),
            aiNarration = prefs.getBoolean(KEY_AI_NARRATION, true),
            userFacts = prefs.getString(KEY_USER_FACTS, "").orEmpty(),
        )
    }

    private companion object {
        const val KEY_HIDE_DOTS = "hide-dot-pattern"
        const val KEY_WORKDAY_AUTOPLAY = "workday-autoplay"
        const val KEY_PAUSE_MEETINGS = "pause-during-meetings"
        const val KEY_LUNCH_RELAX = "lunch-relax-mode"
        const val KEY_AI_NARRATION = "ai-narration"
        const val KEY_USER_FACTS = "user-facts"
    }
}
