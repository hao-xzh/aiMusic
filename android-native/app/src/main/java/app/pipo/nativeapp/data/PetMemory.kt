package app.pipo.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val conversation: MutableList<ConversationTurn>,
        val musicReferences: MutableList<MusicReference>,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var userFacts: String,
        var conversationSummary: String,
    )

    data class Utterance(val tsSec: Long, val text: String)
    data class ConversationTurn(val role: String, val text: String, val tsSec: Long)
    data class MusicReference(
        val title: String,
        val artist: String = "",
        val reason: String = "",
        val tsSec: Long = 0L,
    )
    data class ConversationContext(
        val summary: String = "",
        val turns: List<ConversationTurn> = emptyList(),
        val musicReferences: List<MusicReference> = emptyList(),
    )

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
            val conv = mutableListOf<ConversationTurn>()
            val convArr = parsed.optJSONArray("conversation")
            if (convArr != null) {
                for (i in 0 until convArr.length()) {
                    val o = convArr.optJSONObject(i) ?: continue
                    val role = normalizeRole(o.optString("role"))
                    val text = cleanConversationText(o.optString("text"))
                    if (role != null && text.isNotBlank()) {
                        conv.add(ConversationTurn(role, text, o.optLong("ts")))
                    }
                }
            }
            val refs = mutableListOf<MusicReference>()
            val refArr = parsed.optJSONArray("musicReferences")
            if (refArr != null) {
                for (i in 0 until refArr.length()) {
                    val o = refArr.optJSONObject(i) ?: continue
                    cleanMusicReference(
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        reason = o.optString("reason"),
                        tsSec = o.optLong("ts"),
                    )?.let { refs.add(it) }
                }
            }
            memo = Memory(
                version = VERSION,
                utterances = utt,
                conversation = conv.takeLast(MAX_RAW_CONVERSATION_TURNS).toMutableList(),
                musicReferences = refs.takeLast(MAX_MUSIC_REFERENCES).toMutableList(),
                firstSeenAt = parsed.optLong("firstSeenAt"),
                lastSeenAt = parsed.optLong("lastSeenAt"),
                userFacts = parsed.optString("userFacts"),
                conversationSummary = parsed.optString("conversationSummary").take(MAX_SUMMARY_CHARS),
            )
        } else {
            val now = System.currentTimeMillis() / 1000
            memo = Memory(
                version = VERSION,
                utterances = mutableListOf(),
                conversation = mutableListOf(),
                musicReferences = mutableListOf(),
                firstSeenAt = now,
                lastSeenAt = now,
                userFacts = "",
                conversationSummary = "",
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
        val convArr = JSONArray()
        m.conversation.forEach {
            convArr.put(JSONObject().apply {
                put("role", it.role)
                put("text", it.text)
                put("ts", it.tsSec)
            })
        }
        val refArr = JSONArray()
        m.musicReferences.forEach {
            refArr.put(JSONObject().apply {
                put("title", it.title)
                put("artist", it.artist)
                put("reason", it.reason)
                put("ts", it.tsSec)
            })
        }
        val obj = JSONObject().apply {
            put("version", m.version)
            put("utterances", arr)
            put("conversation", convArr)
            put("musicReferences", refArr)
            put("firstSeenAt", m.firstSeenAt)
            put("lastSeenAt", m.lastSeenAt)
            put("userFacts", m.userFacts)
            put("conversationSummary", m.conversationSummary)
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

    fun conversationContext(): ConversationContext {
        val m = load()
        return ConversationContext(
            summary = m.conversationSummary,
            turns = m.conversation.toList(),
            musicReferences = m.musicReferences.toList(),
        )
    }

    suspend fun recordConversationTurn(role: String, text: String) {
        withContext(Dispatchers.IO) {
            recordConversationTurnBlocking(role, text)
        }
    }

    @Synchronized
    private fun recordConversationTurnBlocking(role: String, text: String) {
        val normalizedRole = normalizeRole(role) ?: return
        val cleaned = cleanConversationText(text)
        if (cleaned.isBlank()) return
        val m = load()
        val now = System.currentTimeMillis() / 1000
        m.conversation.add(ConversationTurn(normalizedRole, cleaned, now))
        trimConversation(m)
        m.lastSeenAt = now
        save()
    }

    suspend fun recordMusicReferences(references: List<MusicReference>) {
        if (references.isEmpty()) return
        withContext(Dispatchers.IO) {
            recordMusicReferencesBlocking(references)
        }
    }

    @Synchronized
    private fun recordMusicReferencesBlocking(references: List<MusicReference>) {
        val cleaned = references.mapNotNull {
            cleanMusicReference(it.title, it.artist, it.reason, it.tsSec)
        }
        if (cleaned.isEmpty()) return
        val m = load()
        val now = System.currentTimeMillis() / 1000
        val byKey = LinkedHashMap<String, MusicReference>()
        for (old in m.musicReferences) {
            val key = musicReferenceKey(old)
            if (key.isNotBlank()) byKey[key] = old
        }
        for (ref in cleaned) {
            val stamped = ref.copy(tsSec = if (ref.tsSec > 0L) ref.tsSec else now)
            val key = musicReferenceKey(stamped)
            if (key.isBlank()) continue
            byKey.remove(key)
            byKey[key] = stamped
        }
        val cutoff = now - MUSIC_REFERENCE_TTL_DAYS * 86400L
        val fresh = byKey.values
            .filter { it.tsSec >= cutoff }
            .takeLast(MAX_MUSIC_REFERENCES)
        m.musicReferences.clear()
        m.musicReferences.addAll(fresh)
        m.lastSeenAt = now
        save()
    }

    fun firstSeenAt(): Long = load().firstSeenAt

    fun userFacts(): String = load().userFacts

    fun setUserFacts(text: String) {
        val m = load()
        m.userFacts = text.trim().take(400)
        save()
    }

    fun clear() {
        val now = System.currentTimeMillis() / 1000
        memo = Memory(VERSION, mutableListOf(), mutableListOf(), mutableListOf(), now, now, "", "")
        save()
    }

    /**
     * 只清对话相关记忆（对话流 / 摘要 / 最近原话 / 可执行音乐指代），**保留** userFacts
     * （ABOUT YOU 是用户显式编辑的画像，不该被「清空对话」误删）和 firstSeenAt（相识时间）。
     * 供设置页「清空 AI 对话记忆」调用，与 PetChatStore.clear() 一起把界面与底层都归零。
     */
    @Synchronized
    fun clearConversation() {
        val m = load()
        m.conversation.clear()
        m.musicReferences.clear()
        m.utterances.clear()
        m.conversationSummary = ""
        m.lastSeenAt = System.currentTimeMillis() / 1000
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

    private fun trimConversation(m: Memory) {
        if (m.conversation.size <= MAX_RAW_CONVERSATION_TURNS) return
        val moveCount = (m.conversation.size - RAW_CONVERSATION_KEEP_TURNS)
            .coerceAtLeast(1)
            .coerceAtMost(m.conversation.size)
        val moved = m.conversation.take(moveCount)
        val remaining = m.conversation.drop(moveCount)
        val addition = summarizeConversationTurns(moved)
        m.conversationSummary = compactSummary(m.conversationSummary, addition)
        m.conversation.clear()
        m.conversation.addAll(remaining)
    }

    private fun summarizeConversationTurns(turns: List<ConversationTurn>): String {
        val lines = mutableListOf<String>()
        var pendingUser: String? = null
        for (turn in turns) {
            when (turn.role) {
                ROLE_USER -> pendingUser = turn.text
                ROLE_ASSISTANT -> {
                    val u = pendingUser
                    if (!u.isNullOrBlank()) {
                        lines.add("用户:${u.take(54)} / Pipo:${turn.text.take(54)}")
                        pendingUser = null
                    } else {
                        lines.add("Pipo:${turn.text.take(70)}")
                    }
                }
            }
        }
        if (!pendingUser.isNullOrBlank()) lines.add("用户:${pendingUser.take(70)}")
        return lines.joinToString("\n")
    }

    private fun compactSummary(existing: String, addition: String): String {
        val combined = listOf(existing.trim(), addition.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (combined.length <= MAX_SUMMARY_CHARS) return combined
        return combined
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(MAX_SUMMARY_LINES)
            .joinToString("\n")
            .takeLast(MAX_SUMMARY_CHARS)
            .trim()
    }

    private fun cleanConversationText(text: String): String =
        text.replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CONVERSATION_TEXT_CHARS)

    private fun cleanMusicReference(
        title: String,
        artist: String,
        reason: String,
        tsSec: Long,
    ): MusicReference? {
        val t = cleanConversationText(title).take(80)
        if (t.isBlank() || t == "null") return null
        val a = cleanConversationText(artist).take(80).takeUnless { it == "null" }.orEmpty()
        val r = cleanConversationText(reason).take(80).takeUnless { it == "null" }.orEmpty()
        return MusicReference(title = t, artist = a, reason = r, tsSec = tsSec)
    }

    private fun musicReferenceKey(ref: MusicReference): String =
        (ref.title + "|" + ref.artist).lowercase()
            .replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？()（）\\[\\]【】《》<>&/]+"), "")

    private fun normalizeRole(role: String): String? =
        when (role.trim().lowercase()) {
            ROLE_USER -> ROLE_USER
            ROLE_ASSISTANT -> ROLE_ASSISTANT
            else -> null
        }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        private const val PREFS_NAME = "claudio_pet_memory"
        private const val KEY = "v1"
        private const val VERSION = 1
        private const val MAX_UTTERANCES = 30
        private const val UTTERANCE_TTL_DAYS = 30
        private const val MAX_RAW_CONVERSATION_TURNS = 18
        private const val RAW_CONVERSATION_KEEP_TURNS = 12
        private const val MAX_CONVERSATION_TEXT_CHARS = 220
        private const val MAX_SUMMARY_CHARS = 900
        private const val MAX_SUMMARY_LINES = 12
        private const val MAX_MUSIC_REFERENCES = 16
        private const val MUSIC_REFERENCE_TTL_DAYS = 7
    }
}
