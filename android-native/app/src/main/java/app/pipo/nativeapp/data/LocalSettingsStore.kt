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
            .putBoolean(KEY_HIDE_AI_PET_ORB, next.hideAiPetOrb)
            .putBoolean(KEY_LYRIC_TRANSLATION, next.lyricTranslation)
            .putBoolean(KEY_AI_NARRATION, next.aiNarration)
            .putString(KEY_PLAYBACK_MODE, next.playbackMode)
            .putString(KEY_USER_FACTS, next.userFacts)
            .putString(KEY_PERSONA_ID, next.personaId)
            .commit()
        state.value = next
    }

    private fun read(): NativeSettings {
        return NativeSettings(
            hideAiPetOrb = prefs.getBoolean(KEY_HIDE_AI_PET_ORB, true),
            lyricTranslation = prefs.getBoolean(KEY_LYRIC_TRANSLATION, false),
            aiNarration = prefs.getBoolean(KEY_AI_NARRATION, false),
            playbackMode = prefs.getString(KEY_PLAYBACK_MODE, "PlaylistLoop").orEmpty()
                .ifBlank { "PlaylistLoop" },
            userFacts = prefs.getString(KEY_USER_FACTS, "").orEmpty(),
            personaId = prefs.getString(KEY_PERSONA_ID, PetPersona.DEFAULT.id).orEmpty()
                .ifBlank { PetPersona.DEFAULT.id },
        )
    }

    private companion object {
        const val KEY_HIDE_AI_PET_ORB = "hide-ai-pet-orb"
        const val KEY_LYRIC_TRANSLATION = "lyric-translation"
        const val KEY_AI_NARRATION = "ai-narration"
        const val KEY_PLAYBACK_MODE = "playback-mode"
        const val KEY_USER_FACTS = "user-facts"
        const val KEY_PERSONA_ID = "persona-id"
    }
}
