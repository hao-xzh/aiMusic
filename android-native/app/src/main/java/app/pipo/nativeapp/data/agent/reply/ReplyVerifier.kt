package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult

class ReplyVerifier {
    fun verify(reply: String, results: List<ActionExecutionResult>, facts: ReplyFacts): Boolean {
        val compact = reply.replace(Regex("\\s+"), "")
        if (compact.isBlank()) return false
        if (results.any { !it.success } || !facts.success) {
            val successWords = listOf("已放", "放了", "切了", "插了", "收藏了", "加好了", "打开了", "排好了", "专场")
            if (successWords.any { it in compact }) return false
        }
        if ("下一首" in compact && results.none { it.type == "insert_next" && it.success }) return false
        if (listOf("已经播放", "已播放", "开始播放", "开播", "切过去", "直接切").any { it in compact } &&
            !facts.actuallyStarted
        ) {
            return false
        }
        if (facts.artistScope == "Strict" &&
            listOf("混一点", "混点", "顺便加点别的", "夹一点", "穿插点别的").any { it in compact }
        ) {
            return false
        }
        if ("专场" in compact && (facts.artistScope != "Strict" || !facts.validationPassed || !facts.success)) {
            return false
        }
        if (listOf("顺序按接歌", "接歌重新排", "接歌顺过").any { it in compact } &&
            !facts.reorderedForSeamless
        ) {
            return false
        }
        val mentionedQuoted = Regex("《([^》]{1,60})》").findAll(reply).map { it.groupValues[1] }.toList()
        if (mentionedQuoted.isNotEmpty()) {
            val actualTitles = (
                facts.includedTitles +
                    listOf(
                        facts.firstTrackTitle,
                        facts.insertedTitle,
                        facts.likedTitle,
                        facts.closerTitle,
                    )
                ).map { it.trim() }.filter { it.isNotBlank() }
            val allReal = mentionedQuoted.all { quoted ->
                actualTitles.any { title -> title == quoted || title.contains(quoted) || quoted.contains(title) }
            }
            if (!allReal) return false
        }
        return true
    }
}
