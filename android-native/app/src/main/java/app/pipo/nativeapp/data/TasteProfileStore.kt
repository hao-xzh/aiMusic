package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 口味画像持久化 —— 镜像 src/lib/taste-profile.ts 的 cache.setState/getState。
 *   - 后台 SharedPreferences 存整个 JSON
 *   - 前台 StateFlow 暴露给 Compose 组件 collectAsState
 */
class TasteProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())

    val flow: StateFlow<TasteProfile?> = state

    fun current(): TasteProfile? = state.value

    fun save(profile: TasteProfile) {
        prefs.edit().putString(KEY, TasteProfileSerde.toJson(profile)).apply()
        state.value = profile
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        state.value = null
    }

    private fun load(): TasteProfile? {
        val raw = prefs.getString(KEY, null) ?: return null
        return TasteProfileSerde.fromJson(raw)
    }

    companion object {
        private const val PREFS_NAME = "claudio_taste"
        private const val KEY = "taste_profile_v1"
    }
}
