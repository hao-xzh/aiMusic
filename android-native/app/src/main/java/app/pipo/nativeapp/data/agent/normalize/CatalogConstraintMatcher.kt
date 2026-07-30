package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.CatalogConstraint

/**
 * 具名目录实体的最后一道保真判断。
 *
 * “这是一个具名作品”以及它的跨语言别名由 LLM 从完整语境理解；这里不猜作品类型，
 * 只核对真实搜索结果的 album 是否含有模型声明的目录锚点。
 * Artist 不能作为作品归属证据：否则《Hamilton》会误收 Anthony Hamilton；title 也不能，
 * 否则蹭作品名的同名歌曲会被放行。
 */
object CatalogConstraintMatcher {
    private val genericAnchors = setOf(
        "音乐", "歌曲", "歌", "专辑", "原声", "原声带", "音乐剧", "电影", "影视", "游戏", "动漫", "作品", "系列",
        "music", "song", "songs", "album", "soundtrack", "ost", "musical", "movie", "film", "game", "anime", "originalcastrecording",
    )

    fun matches(track: NativeTrack, constraint: CatalogConstraint): Boolean =
        !constraint.isActive || matches(track, constraint.matchTerms)

    fun matches(track: NativeTrack, anchors: List<String>): Boolean {
        val terms = anchors
            .map { raw ->
                MatchTerm(
                    normalized = CommandTextSignals.normalizeForMatch(raw),
                    allowsSubstring = CatalogConstraint.isStrongMetadataAlias(raw),
                )
            }
            .filter { it.normalized.length >= 2 && it.normalized !in genericAnchors }
            .distinctBy { it.normalized }
        if (terms.isEmpty()) return false

        val album = CommandTextSignals.normalizeForMatch(track.album)
        if (album.isBlank()) return false
        return terms.any { term ->
            album == term.normalized ||
                term.allowsSubstring && album.contains(term.normalized)
        }
    }

    fun coverage(tracks: List<NativeTrack>, constraint: CatalogConstraint): Pair<Int, Int> {
        if (!constraint.isActive) return tracks.size to tracks.size
        return tracks.count { matches(it, constraint) } to tracks.size
    }

    private data class MatchTerm(
        val normalized: String,
        val allowsSubstring: Boolean,
    )
}
