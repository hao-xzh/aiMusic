package android.content

/** Only the Android persistence boundary; AgentTaskStore itself is production code. */
open class Context {
    val applicationContext: Context get() = this
    private val stores = mutableMapOf<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.getOrPut(name) { MemoryPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}

interface SharedPreferences {
    fun getString(key: String, fallback: String?): String?
    fun contains(key: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        fun commit(): Boolean
        fun apply()
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String?>()
    override fun getString(key: String, fallback: String?): String? = values[key] ?: fallback
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
        override fun commit(): Boolean {
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
