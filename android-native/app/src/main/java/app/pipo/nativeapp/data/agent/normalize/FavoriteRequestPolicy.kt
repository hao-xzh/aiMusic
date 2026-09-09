package app.pipo.nativeapp.data.agent.normalize

/** Checks the user's own request; the model still chooses whether/which favorite tool to call. */
internal object FavoriteRequestPolicy {
    fun allows(userText: String, like: Boolean): Boolean {
        // Quoted song names/dialogue are data and cannot grant an operation by containing “收藏”.
        val text = userText.lowercase().replace(
            Regex("《[^》]*》|「[^」]*」|“[^”]*”|\"[^\"]*\"|`[^`]*`"), " ",
        )
        val clauses = text.split(Regex("[，。；,;\\n]|然后|并且|但是|但"))
        val prohibition = Regex("(?:不要|别|不必|无需|不用|不许|禁止|不想|不)\\s*(?:再|自动|帮我)?\\s*(?:取消收藏|收藏|点赞|加.{0,4}收藏)|(?:don't|do not)\\s+(?:unlike|like|unfavorite|favorite|favourite|save)")
        val hasProhibition = clauses.any { prohibition.containsMatchIn(it) }
        return clauses.any { clause ->
            val blocked = prohibition.containsMatchIn(clause)
            val remove = Regex("取消收藏|取消.{0,3}喜欢|移出.{0,4}(?:收藏|喜欢)|从.{0,5}收藏.{0,5}(?:删除|移除)|\\bunlike\\b|\\bunfavou?rite\\b|remove.{0,15}favou?rites")
                .containsMatchIn(clause)
            if (blocked) false
            else if (!like) remove
            else if (remove || Regex("不(?:太|是很|怎么)?喜欢|没(?:那么)?喜欢|讨厌").containsMatchIn(clause)) false
            else {
                val explicitSave = Regex("收藏|点赞|赞这|加入.{0,5}喜欢|\\bfavou?rite\\b|\\blike\\b|\\bsave\\b")
                    .containsMatchIn(clause)
                val songReference = Regex("(?:这|那)(?:一)?首|这歌|那歌|当前(?:的)?歌|正在(?:播放|听)|现在(?:播放|听)|它")
                    .containsMatchIn(clause)
                explicitSave || (!hasProhibition && songReference && "喜欢" in clause)
            }
        }
    }
}
