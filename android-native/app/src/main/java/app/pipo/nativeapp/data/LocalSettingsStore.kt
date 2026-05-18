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
            .putBoolean(KEY_HIDE_AI_PET_ORB, next.hideAiPetOrb)
            .putBoolean(KEY_LYRIC_TRANSLATION, next.lyricTranslation)
            .putBoolean(KEY_SMART_SESSION_PLANNER, next.smartSessionPlanner)
            .putBoolean(KEY_WORKDAY_AUTOPLAY, next.workdayAutoplay)
            .putBoolean(KEY_LUNCH_RELAX, next.lunchRelaxMode)
            .putBoolean(KEY_LATE_NIGHT_CALM, next.lateNightCalmMode)
            .putString(KEY_PROMPTED_RADIO_RULE, next.promptedRadioRule)
            .putBoolean(KEY_AI_NARRATION, next.aiNarration)
            .putString(KEY_PLAYBACK_MODE, next.playbackMode)
            .putString(KEY_USER_FACTS, next.userFacts)
            .commit()
        state.value = next
    }

    private fun read(): NativeSettings {
        return NativeSettings(
            hideDotPattern = prefs.getBoolean(KEY_HIDE_DOTS, false),
            hideAiPetOrb = prefs.getBoolean(KEY_HIDE_AI_PET_ORB, true),
            lyricTranslation = prefs.getBoolean(KEY_LYRIC_TRANSLATION, false),
            smartSessionPlanner = prefs.getBoolean(KEY_SMART_SESSION_PLANNER, true),
            workdayAutoplay = prefs.getBoolean(KEY_WORKDAY_AUTOPLAY, true),
            lunchRelaxMode = prefs.getBoolean(KEY_LUNCH_RELAX, false),
            lateNightCalmMode = prefs.getBoolean(KEY_LATE_NIGHT_CALM, true),
            promptedRadioRule = prefs.getString(KEY_PROMPTED_RADIO_RULE, "").orEmpty(),
            aiNarration = prefs.getBoolean(KEY_AI_NARRATION, false),
            playbackMode = prefs.getString(KEY_PLAYBACK_MODE, "PlaylistLoop").orEmpty()
                .ifBlank { "PlaylistLoop" },
            userFacts = prefs.getString(KEY_USER_FACTS, "").orEmpty(),
        )
    }

    private companion object {
        const val KEY_HIDE_DOTS = "hide-dot-pattern"
        const val KEY_HIDE_AI_PET_ORB = "hide-ai-pet-orb"
        const val KEY_LYRIC_TRANSLATION = "lyric-translation"
        const val KEY_SMART_SESSION_PLANNER = "smart-session-planner"
        const val KEY_WORKDAY_AUTOPLAY = "workday-autoplay"
        const val KEY_LUNCH_RELAX = "lunch-relax-mode"
        const val KEY_LATE_NIGHT_CALM = "late-night-calm-mode"
        const val KEY_PROMPTED_RADIO_RULE = "prompted-radio-rule"
        const val KEY_AI_NARRATION = "ai-narration"
        const val KEY_PLAYBACK_MODE = "playback-mode"
        const val KEY_USER_FACTS = "user-facts"
    }
}
