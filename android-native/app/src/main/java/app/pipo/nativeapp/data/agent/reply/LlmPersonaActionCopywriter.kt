package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona

/**
 * 用 LLM 按「设置里选的人格」写执行后的回复，摆脱模板的千篇一律。
 *
 * 事实全部来自 [ReplyFacts]（真实执行结果），LLM 只负责用人格口吻把结果说出来。
 * [ReplyGrounder] 会再用 [ReplyVerifier] 校验它没有谎报/编造歌名，校验不过就回落到
 * [GroundedReplyTemplates]。所以这一层只加「自然 + 人格」，不牺牲言行一致。
 */
class LlmPersonaActionCopywriter(
    private val aiChat: suspend (system: String, user: String) -> String,
    private val templates: GroundedReplyTemplates = GroundedReplyTemplates(),
) : PersonaActionCopywriter {

    override suspend fun write(facts: ReplyFacts, persona: PetPersona): String {
        val raw = runCatching { aiChat(systemPrompt(persona), factSheet(facts)) }.getOrNull().orEmpty()
        return sanitize(raw).ifBlank { templates.grounded(facts, persona) }
    }

    private fun systemPrompt(persona: PetPersona): String {
        val voice = when (persona) {
            PetPersona.TOXIC -> "毒舌但懂歌，像熟人 DJ，嘴欠不刻薄，偶尔损一句。"
            PetPersona.FRIENDLY -> "温和会接情绪，像照顾人但不黏的朋友。"
            PetPersona.COLD -> "高冷极简，少话但准，冷面音乐总监，能一句不两句。"
            PetPersona.KITTY -> "黏一点会撒娇，句尾偶尔（不是每次）带‘喵/唔’，别每句都带。"
            PetPersona.JIANGHU -> "仗义老炮儿，偶尔‘走着/得劲/拉满’，点到为止不堆。"
        }
        return """
你是 Pipo，一只音乐宠物，正用「${persona.label}」人格跟用户说话。系统刚刚已经真实执行了一个音乐操作，你只负责用人格口吻把结果转告用户。

人格风格：$voice

硬规则（违反就废）：
1. 只能说 FACTS 里给的真实信息（歌名/歌手/动作/是否成功），绝不编造没给的歌名、歌手、歌单。
2. 成功=false 时绝不能说“放了/切了/收了/排好了/打开了”，要如实说没成或没找到，别硬装完成。
3. 队列类成功用“接上了/排上了/待会儿给你放”这种说法；除非 FACTS 明说已切歌，别说“已经在放/切过去了”。
4. 不要报菜名式罗列整列队列，最多点开场那首或关键一两首。
5. ≤2 句、口语、自然，别像模板或客服。不要感叹号，不要“亲/宝/主人”，不要双形容词对仗（既…又…）。
6. 只输出这一句话本身，不要解释、不要 JSON、不要整句加引号。
""".trimIndent()
    }

    private fun factSheet(facts: ReplyFacts): String = buildString {
        appendLine("用户原话：${facts.userText.take(120)}")
        appendLine("动作：${facts.actionType}")
        appendLine("成功：${facts.success}")
        if (facts.requiredArtist.isNotBlank()) {
            appendLine("主歌手：${facts.requiredArtist}（范围 ${facts.artistScope}）")
        }
        if (facts.firstTrackTitle.isNotBlank()) {
            appendLine("开场歌：${facts.firstTrackArtist.ifBlank { "?" }} - ${facts.firstTrackTitle}")
        }
        if (facts.queueCount > 0) appendLine("队列长度：${facts.queueCount}")
        val sample = facts.includedTitles.filter { it.isNotBlank() }.distinct().take(5)
        if (sample.isNotEmpty()) appendLine("队列里有（可点一两首，别全报）：${sample.joinToString("、")}")
        if (facts.closerTitle.isNotBlank()) appendLine("结尾收住：${facts.closerTitle}")
        if (facts.insertedTitle.isNotBlank()) {
            appendLine("插到下一首：${facts.insertedArtist.ifBlank { "?" }} - ${facts.insertedTitle}")
        }
        if (facts.likedTitle.isNotBlank()) appendLine("收藏的歌：${facts.likedTitle}")
        if (facts.playlistName.isNotBlank()) appendLine("歌单：${facts.playlistName}")
        if (facts.warnings.isNotEmpty()) appendLine("没满足/注意：${facts.warnings.joinToString("；").take(160)}")
        if (!facts.success && facts.errorMessage.isNotBlank()) {
            appendLine("失败原因：${facts.errorMessage.take(120)}")
        }
        append("按上面的人格，用 ≤2 句把这个真实结果说给用户。")
    }

    /** 去掉模型偶尔加的引号/换行，并兜底长度。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .removePrefix("```").removeSuffix("```")
            .trim()
            .trim('「', '」', '『', '』', '“', '”', '"', '\'')
            .replace(Regex("\\s*\\n\\s*"), " ")
            .take(140)
            .trim()
}
