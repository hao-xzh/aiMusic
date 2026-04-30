package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pipo-native-settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<NativeSettings> = state.asStateFlow()

    fun update(next: NativeSettings) {
        prefs.edit()
            .putBoolean(KEY_HIDE_DOTS, next.hideDotPattern)
            .putBoolean(KEY_WORKDAY_AUTOPLAY, next.workdayAutoplay)
            .putBoolean(KEY_PAUSE_MEETINGS, next.pauseDuringMeetings)
            .putBoolean(KEY_LUNCH_RELAX, next.lunchRelaxMode)
            .putBoolean(KEY_AI_NARRATION, next.aiNarration)
            .putString(KEY_USER_FACTS, next.userFacts)
            .apply()
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
