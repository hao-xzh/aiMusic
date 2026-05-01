package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨 session 宠物记忆 —— 镜像 src/lib/pet-memory.ts。
 *
 * 设计原则（和 React 版完全一致，请别加"喂偏好艺人给 AI"这种逻辑——
 * 那是死亡螺旋开端，TS 顶部注释里专门写了原因）：
 *
 *   ❌ 不喂"偏爱/跳过率高"艺人给 AI：把瞬时心情误读成长期排斥，库丰富度被悄悄削平
 *   ✅ 记累积事实 + 最近原话：陪伴痕迹，不是把 TA 框成某种类型
 *   ✅ userFacts：TA 自述比任何推断都准
 */
class PetMemory(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /** 后台 IO 调度器 —— SharedPrefs JSON 构建 + 写盘都不能阻塞主线程 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Memory(
        val version: Int,
        val utterances: MutableList<Utterance>,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var userFacts: String,
    )

    data class Utterance(val tsSec: Long, val text: String)

    @Volatile
    private var memo: Memory? = null

    @Synchronized
    private fun load(): Memory {
        memo?.let { return it }
        val raw = prefs.getString(KEY, null)
        val parsed = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed != null && parsed.optInt("version") == VERSION) {
            val utt = mutableListOf<Utterance>()
            val arr = parsed.optJSONArray("utterances")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    utt.add(Utterance(o.optLong("ts"), o.optString("text")))
                }
            }
            memo = Memory(
                version = VERSION,
                utterances = utt,
                firstSeenAt = parsed.optLong("firstSeenAt"),
                lastSeenAt = parsed.optLong("lastSeenAt"),
                userFacts = parsed.optString("userFacts"),
            )
        } else {
            val now = System.currentTimeMillis() / 1000
            memo = Memory(
                version = VERSION,
                utterances = mutableListOf(),
                firstSeenAt = now,
                lastSeenAt = now,
                userFacts = "",
            )
        }
        return memo!!
    }

    @Synchronized
    private fun save() {
        val m = memo ?: return
        val arr = JSONArray()
        m.utterances.forEach {
            arr.put(JSONObject().apply { put("ts", it.tsSec); put("text", it.text) })
        }
        val obj = JSONObject().apply {
            put("version", m.version)
            put("utterances", arr)
            put("firstSeenAt", m.firstSeenAt)
            put("lastSeenAt", m.lastSeenAt)
            put("userFacts", m.userFacts)
        }
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    /**
     * 用户每次说话调一次。fire-and-forget —— JSON 构建 + 写盘都丢到 IO 调度器，
     * 主线程从 onSend 调用时不会被 SharedPrefs 写阻塞（之前在 30 条 utterance 时能 ~50ms）。
     * 自动过滤无意义短句。
     */
    fun recordUtterance(text: String) {
        val trimmed = text.trim()
        if (trimmed.length <= 2) return
        if (Regex("^(嗯|哦|好|谢谢|你好|您好|早|晚安|hi|hello|thanks|thx)$", RegexOption.IGNORE_CASE).matches(trimmed)) return

        ioScope.launch {
            val m = load()
            val now = System.currentTimeMillis() / 1000
            m.utterances.add(Utterance(now, trimmed.take(80)))
            val cutoff = now - UTTERANCE_TTL_DAYS * 86400L
            val filtered = m.utterances.filter { it.tsSec >= cutoff }.takeLast(MAX_UTTERANCES)
            m.utterances.clear()
            m.utterances.addAll(filtered)
            m.lastSeenAt = now
            save()
        }
    }

    /** 最后一句有意义的话 + 上次时间标签 */
    fun lastUtterance(): Utterance? = load().utterances.lastOrNull()

    fun firstSeenAt(): Long = load().firstSeenAt

    fun userFacts(): String = load().userFacts

    fun setUserFacts(text: String) {
        val m = load()
        m.userFacts = text.trim().take(400)
        save()
    }

    fun clear() {
        val now = System.currentTimeMillis() / 1000
        memo = Memory(VERSION, mutableListOf(), now, now, "")
        save()
    }

    /**
     * AI prompt 用的一行 digest。
     * 镜像 React getMemoryDigest：陪伴痕迹（总播放数 + 上次说过的话）+ 用户自述
     * 故意**不**包含 loveArtists / skipHotArtists —— 见 React 顶部注释
     */
    fun digest(behaviorTotalEvents: Int, tasteSummary: String? = null): String {
        val m = load()
        val parts = mutableListOf<String>()
        if (behaviorTotalEvents > 0) parts.add("听过 $behaviorTotalEvents 首")
        m.utterances.lastOrNull()?.let { last ->
            val ageMin = ((System.currentTimeMillis() / 1000 - last.tsSec) / 60).toInt()
            val ageLabel = when {
                ageMin < 5 -> "刚刚"
                ageMin < 60 -> "$ageMin 分钟前"
                ageMin < 24 * 60 -> "${ageMin / 60} 小时前"
                else -> "${ageMin / (60 * 24)} 天前"
            }
            parts.add("上次说\"${last.text.take(30)}\"($ageLabel)")
        }
        if (m.userFacts.isNotBlank()) parts.add("自述:${m.userFacts.trim()}")
        if (!tasteSummary.isNullOrBlank()) parts.add(tasteSummary.trim())
        return parts.joinToString(" · ")
    }

    companion object {
        private const val PREFS_NAME = "claudio_pet_memory"
        private const val KEY = "v1"
        private const val VERSION = 1
        private const val MAX_UTTERANCES = 30
        private const val UTTERANCE_TTL_DAYS = 30
    }
}
