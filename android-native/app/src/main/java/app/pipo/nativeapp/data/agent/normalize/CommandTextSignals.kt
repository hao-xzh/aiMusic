package app.pipo.nativeapp.data.agent.normalize

import app.pipo.nativeapp.data.agent.domain.ArtistScope
import app.pipo.nativeapp.data.agent.domain.TrackPlacement
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.intent.MusicDescriptorLexicon

data class PlaylistScopedRequest(
    val playlistName: String,
    val target: TrackRequirement? = null,
    val primaryArtists: List<String> = emptyList(),
)

object CommandTextSignals {
    private const val CLOUD_PLAYLIST_NAME = "我的网盘"

    fun noInterrupt(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("不要打断", "别打断", "不打断", "别切", "不要切", "听完这首", "下一首插", "插到下一首")
            .any { it in key }
    }

    fun isRepair(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("呢", "怎么没有", "怎么没", "只有", "不是说", "刚才", "上一轮", "没放")
            .any { it in key }
    }

    fun dislikesCurrent(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("这首不要了", "不要这首", "这首不想听", "这首不要", "这首不行", "不喜欢这首", "取消收藏")
            .any { it in key }
    }

    fun wantsSkipCurrent(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("这首不要了", "不要这首了", "这首不想听", "这首不要", "这首不行")
            .any { it in key }
    }

    fun includedTrackTitle(text: String): String? {
        val patterns = listOf(
            Regex("(?:包含|包括|带上|要有|有)[^《「“\"]{0,18}[《「“\"]([^》」”\"]{1,32})[》」”\"]"),
            Regex("(?:加一首|加首|插一首|插首|顺便加|再加一首|再加|加个|插个)[^《「“\"]{0,8}[《「“\"]([^》」”\"]{1,32})[》」”\"]"),
            Regex("(?:包含|包括|带上|要有|有)\\s*([^，。,.!！?？]{1,18})\\s*的\\s*([^，。,.!！?？]{1,32})"),
            Regex("(?:加一首|加首|插一首|插首|顺便加|再加一首|再加|加个|插个)\\s*([^，。,.!！?？、]{1,32})"),
            Regex("(?:包含|包括|带上|要有|有)\\s*([^，。,.!！?？]{1,32})"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val title = sanitizeTitle(match.groupValues.lastOrNull().orEmpty())
            if (title.isNotBlank()) return title
        }
        return null
    }

    fun includedTrackRequirement(text: String): TrackRequirement? {
        val placement = includedPlacement(text)
        val index = includedIndex(text)
        val artistPattern = Regex(
            "(?:包含|包括|带上|要有|有|加一首|加首|插一首|插首|顺便加|再加一首|再加|加个|插个)\\s*([^，。,.!！?？]{1,24})\\s*的\\s*([^，。,.!！?？]{1,32})",
        )
        artistPattern.find(text)?.let { match ->
            val artist = match.groupValues.getOrNull(1).orEmpty().trim()
            val title = sanitizeTitle(match.groupValues.getOrNull(2).orEmpty())
            if (artist.isNotBlank() && title.isNotBlank()) {
                return TrackRequirement(title = title, artist = artist, placement = placement, index = index)
            }
        }
        return includedTrackTitle(text)?.let { TrackRequirement(title = it, placement = placement, index = index) }
    }

    private fun includedPlacement(text: String): TrackPlacement {
        val key = normalizeCommandText(text)
        return when {
            listOf("中间加", "中间插", "中间放", "放中间", "插中间").any { it in key } -> TrackPlacement.Middle
            Regex("第([0-9]{1,2}|[一二两三四五六七八九十]{1,4})首").containsMatchIn(key) -> TrackPlacement.AtIndex
            listOf("当前这首后", "这首后", "放完这首", "等这首完").any { it in key } -> TrackPlacement.AfterCurrent
            else -> TrackPlacement.MustInclude
        }
    }

    private fun includedIndex(text: String): Int? {
        val raw = Regex("第([0-9]{1,2}|[一二两三四五六七八九十]{1,4})首")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return parseSmallCount(raw)?.minus(1)?.coerceAtLeast(0)
    }

    fun explicitTrackList(text: String): List<TrackRequirement> {
        if (playlistScopedRequest(text) != null) return emptyList()
        val key = normalizeCommandText(text)
        if (listOf("歌单", "播放列表", "云盘", "网盘", "类似", "风格", "那种", "为主", "主打").any { it in key }) {
            return emptyList()
        }
        if (listOf("加一首", "加首", "插一首", "插首", "顺便加", "包含", "包括", "带上", "要有").any { it in key }) {
            return emptyList()
        }
        val quoted = Regex("[《「“\"]([^》」”\"]{1,40})[》」”\"]")
            .findAll(text)
            .mapNotNull { sanitizeTitle(it.groupValues.getOrNull(1).orEmpty()).takeIf { title -> title.isNotBlank() } }
            .toList()
        if (quoted.size >= 2) return quoted.toTrackRequirements()

        if (Regex("[^，。,.!！?？]{1,40}(?:和|跟|与|、)[^，。,.!！?？]{1,40}的歌").containsMatchIn(text)) {
            return emptyList()
        }
        val raw = Regex("(?:只听|只放|就听|播放|放|听|想听|来听|来点|播)\\s*([^。.!！?？]{1,120})")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return emptyList()
        if (!Regex("(?:、|，|,|和|跟|与)").containsMatchIn(raw)) {
            return emptyList()
        }
        val rawSegments = raw
            .replace(Regex("(这几首|这几首歌|这些歌|这些)$"), "")
            .split(Regex("\\s*(?:、|，|,|和|跟|与)\\s*"))
        val hasTrackListCue = listOf("这几首", "这几首歌", "这些歌", "歌名", "歌曲").any { it in key } ||
            Regex("^\\s*(只听|只放|就听)").containsMatchIn(text)
        val hasArtistTitleSegment = rawSegments.any {
            Regex("^[^，。,.!！?？的]{1,24}的[^，。,.!！?？的]{1,40}$").matches(sanitizeTitle(it))
        }
        if (!hasTrackListCue && !hasArtistTitleSegment) return emptyList()
        val titles = rawSegments.mapNotNull { segmentTrackRequirement(it) }
        return if (titles.size >= 2) dedupeTrackRequirements(titles) else emptyList()
    }

    private fun segmentTrackRequirement(segment: String): TrackRequirement? {
        val cleaned = sanitizeTitle(segment)
            .takeIf { it.isNotBlank() && it.length <= 40 }
            ?: return null
        val cleanedKey = normalizeForMatch(cleaned)
        if (cleanedKey in setOf("歌", "歌曲", "音乐", "几首", "一些") ||
            listOf("的歌", "歌手", "风格", "类似", "那种").any { it in cleanedKey }
        ) {
            return null
        }
        Regex("^([^，。,.!！?？的]{1,24})的([^，。,.!！?？的]{1,40})$")
            .find(cleaned)
            ?.let { match ->
                val artist = sanitizeArtistHint(match.groupValues.getOrNull(1).orEmpty())
                val title = sanitizeTitle(match.groupValues.getOrNull(2).orEmpty())
                if (!artist.isNullOrBlank() && title.isNotBlank() && normalizeForMatch(title) !in setOf("歌", "歌曲", "音乐")) {
                    return TrackRequirement(title = title, artist = artist)
                }
            }
        return TrackRequirement(title = cleaned)
    }

    fun explicitDesiredCount(text: String): Int? {
        val patterns = listOf(
            Regex("(?:来|排|放|听|播放|给我|换|找)\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,4})\\s*首"),
            Regex("^\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,4})\\s*首"),
        )
        for (pattern in patterns) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
            val parsed = parseSmallCount(raw)
            if (parsed != null) return parsed.coerceIn(1, 60)
        }
        return null
    }

    fun insertNextTrack(text: String): TrackRequirement? {
        val patterns = listOf(
            Regex("(?:下一首插|插到下一首|放完接|等这首完(?:了)?放)\\s*([^，。,.!！?？]{1,32})"),
            Regex("(?:下一首|接下来)[^《「“\"]{0,12}[《「“\"]([^》」”\"]{1,32})[》」”\"]"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val title = sanitizeTitle(match.groupValues.lastOrNull().orEmpty())
            if (title.isNotBlank()) return TrackRequirement(title = title, placement = TrackPlacement.Next)
        }
        return null
    }

    fun connectiveLeadTrack(text: String): TrackRequirement? {
        val patterns = listOf(
            Regex("(?:播放|放|听|先放)\\s*([^，。,.!！?？]{1,40})\\s*(?:，|,)?\\s*(?:然后)?(?:接|再接|后面接|接几首)"),
            Regex("^\\s*[《「“\"]([^》」”\"]{1,40})[》」”\"]\\s*(?:接|再接|后面接)\\s*([^，。,.!！?？]{1,40})"),
            Regex("^\\s*([^，。,.!！?？]{2,40})\\s*(?:接|再接|后面接)\\s*([^，。,.!！?？]{1,40})"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val title = sanitizeTitle(match.groupValues.getOrNull(1).orEmpty())
            if (title.isNotBlank()) return TrackRequirement(title = title, placement = TrackPlacement.Now)
        }
        return null
    }

    fun artistTrackTarget(text: String): TrackRequirement? {
        val patterns = listOf(
            Regex("(?:播放|放|听|来一首|直接放)\\s*([^，。,.!！?？]{1,24})\\s*的\\s*[《「“\"]([^》」”\"]{1,32})[》」”\"]"),
            Regex("(?:播放|放|听|来一首|直接放)\\s*([^，。,.!！?？]{1,24})\\s*的\\s*([^，。,.!！?？]{1,32})"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val artist = match.groupValues.getOrNull(1).orEmpty().trim()
            val title = sanitizeTitle(match.groupValues.getOrNull(2).orEmpty())
            if (artist.isNotBlank() && title.isNotBlank() &&
                !isGenericMusicNoun(title) &&
                !isCatalogDescriptor(title) &&
                !isCatalogDescriptor(artist)
            ) {
                return TrackRequirement(title = title, artist = artist, placement = TrackPlacement.Now)
            }
        }
        return null
    }

    fun closerTrackTitle(text: String): String? {
        val patterns = listOf(
            Regex("(?:最后|末尾|结尾)[^《「“\"]{0,16}[《「“\"]([^》」”\"]{1,32})[》」”\"]"),
            Regex("(?:最后|末尾|结尾)\\s*([^，。,.!！?？]{1,28})(?:收一下|收住|压住|结束|结尾)?"),
            Regex("([^，。,.!！?？]{1,28})(?:收一下|收住)$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val title = sanitizeTitle(match.groupValues.lastOrNull().orEmpty())
            if (title.isNotBlank()) return title
        }
        return null
    }

    fun primaryArtistHint(text: String): String? = primaryArtistHints(text).firstOrNull()

    fun primaryArtistHints(text: String): List<String> {
        playlistScopedRequest(text)?.primaryArtists?.takeIf { it.isNotEmpty() }?.let { return it }
        artistTrackTarget(text)?.artist?.trim()?.takeIf { it.isNotBlank() }?.let {
            return splitArtistHints(it).ifEmpty { listOf(it) }
        }
        rawPrimaryArtistHint(text)?.let { return splitArtistHints(it).ifEmpty { listOf(it) } }
        return repairArtistHint(text)?.let { splitArtistHints(it).ifEmpty { listOf(it) } }.orEmpty()
    }

    private fun rawPrimaryArtistHint(text: String): String? {
        val patterns = listOf(
            Regex("(?:想听|听点|听|放点|放|播放|先放|来点|来些|播)\\s*([^，。,.!！?？]{1,24})\\s*的\\s*(?:经典|老歌|金曲|热门|好听|流行|摇滚|民谣|爵士|电子|嘻哈|安静|舒缓|放松|嗨|高能|燃|动感)?\\s*(?:歌|歌曲|音乐)"),
            Regex("(?:想听|听点|听|放点|放|播放|先放|来点|来些|播)\\s*([^，。,.!！?？]{1,36})"),
            Regex("^\\s*(?:类似|像|接近|来点类似|放点类似)\\s*([^，。,.!！?？的]{2,24})"),
            Regex("^\\s*([^，。,.!！?？]{2,24})\\s*(?:的歌|那种|这种|风格|为主|主打)"),
        )
        for (pattern in patterns) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
            val trimmed = sanitizeArtistHint(raw)
            if (trimmed != null) return trimmed
        }
        return null
    }

    fun artistScope(text: String): ArtistScope {
        val key = normalizeCommandText(text)
        val focusCue = listOf(
            "为主", "主打", "混一点", "混点", "夹一点", "带一点", "穿插一点",
            "穿插点", "加点类似", "加一点类似", "为主混", "主打混",
        ).any { it in key }
        if (focusCue) return ArtistScope.Focus

        val similarCue = listOf(
            "类似", "那种", "这种风格", "风格", "像", "同味", "同类型", "差不多", "接近",
        ).any { it in key }
        if (similarCue) return ArtistScope.Similar

        return if (primaryArtistHints(text).isNotEmpty()) ArtistScope.Strict else ArtistScope.Focus
    }

    fun genericSimilarRequest(text: String): Boolean {
        val key = normalizeCommandText(text)
        return looksLikeSimilarRequest(text) &&
            primaryArtistHints(text).isEmpty() &&
            artistTrackTarget(text) == null &&
            connectiveLeadTrack(text) == null &&
            explicitTrackList(text).isEmpty() &&
            listOf("类似歌曲", "类似的歌", "类似音乐", "继续播放类似", "继续类似", "接着类似", "继续放类似")
                .any { it in key }
    }

    fun genericCatalogRequest(text: String): Boolean =
        isCatalogDescriptor(text) &&
            primaryArtistHints(text).isEmpty() &&
            artistTrackTarget(text) == null &&
            explicitTrackList(text).isEmpty()

    fun isCatalogDescriptor(value: String): Boolean {
        val key = normalizeCommandText(value)
        if (key.isBlank()) return false
        if (MusicDescriptorLexicon.isDescriptorOnly(key)) return true
        val stripped = key
            .replace(Regex("^(一些|一点|几首|几首歌|歌曲|音乐)"), "")
            .replace(Regex("(一点|一些|的歌|的音乐|的歌曲|的|歌曲|音乐|歌)$"), "")
        if (key in setOf("歌", "歌曲", "音乐", "一些歌", "一些歌曲", "几首歌", "类似歌曲")) return true
        val languageCue = listOf("华语", "中文", "国语", "粤语", "英文", "日语", "韩语").any { it in key }
        val styleCue = listOf(
            "经典", "老歌", "金曲", "怀旧", "流行", "摇滚", "民谣", "爵士", "电子", "嘻哈",
            "夜晚", "深夜", "热门", "好听",
        ).any { it in key }
        val compactStyleOnly = stripped in setOf(
            "经典", "老歌", "金曲", "怀旧", "流行", "安静", "夜晚", "深夜", "舒缓", "放松",
            "热门", "好听", "华语经典", "中文经典",
        )
        val musicNoun = listOf("歌", "歌曲", "音乐", "曲").any { it in key }
        return compactStyleOnly || ((languageCue || styleCue) && musicNoun)
    }

    private fun sanitizeArtistHint(raw: String): String? {
        val trimmed = raw
            .replace(Regex("^(一些|一点|几首|几首歌|歌曲|音乐)"), "")
            .split("加一首", "加首", "插一首", "插首", "顺便", "包含", "包括", "带上", "要有", "最后", "末尾", "结尾")
            .firstOrNull()
            .orEmpty()
            .split("为主", "主打", "混一点", "混点", "夹一点", "带一点", "穿插一点", "穿插点", "加点", "加一点")
            .firstOrNull()
            .orEmpty()
            .split("类似", "那种", "这种", "风格", "的然后", "的歌", "一点", "别太", "不太")
            .firstOrNull()
            .orEmpty()
            .replace(Regex("^([0-9]{1,2}|[一二两三四五六七八九十]{1,4})\\s*首"), "")
            .trim()
        val key = normalizeForMatch(trimmed)
        if (key.isBlank()) return null
        if (key in setOf("这首", "那首", "这个", "那个", "它", "歌", "歌曲", "音乐", "歌手", "歌单", "播放列表")) return null
        if (listOf("什么", "随便", "推荐").any { it in key }) return null
        if (isCatalogDescriptor(trimmed)) return null
        return trimmed.takeIf { it.length in 2..24 }
    }

    private fun repairArtistHint(text: String): String? {
        val raw = Regex("^\\s*([^，。,.!！?？]{2,24})\\s*呢\\s*[?？]?$").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null
        val compact = normalizeForMatch(raw)
        if (compact in setOf("这首", "那首", "这个", "那个", "它", "刚才", "刚刚", "怎么")) return null
        if (listOf("歌", "首", "啥", "什么").any { it in compact }) return null
        return raw
    }

    fun excludeTerms(text: String): List<String> {
        val out = mutableListOf<String>()
        Regex("(?:不要|别|不想听)([^，。,.!！?？]{1,18})").findAll(text).forEach {
            it.groupValues.getOrNull(1)
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let(out::add)
        }
        val key = normalizeCommandText(text)
        if (listOf("不那么苦", "别太苦", "不要太苦", "没那么苦").any { it in key }) out.add("苦")
        if (listOf("别太炸", "不要太炸", "没那么炸").any { it in key }) out.add("炸")
        return out.distinct()
    }

    fun languageIncludes(text: String, excludeTerms: List<String> = excludeTerms(text)): List<String> {
        val key = normalizeCommandText(text)
        val excluded = excludeTerms.mapNotNull(::languageKey).toSet()
        val out = mutableListOf<String>()
        fun addIfMentioned(label: String, vararg aliases: String) {
            if (aliases.any { it in key } && label !in excluded) out.add(label)
        }
        addIfMentioned("cantonese", "粤语", "cantonese")
        addIfMentioned("mandarin", "国语", "普通话", "华语", "中文", "中文歌", "mandarin")
        addIfMentioned("cantonese", "华语", "中文", "中文歌")
        addIfMentioned("english", "英文", "英语", "english")
        addIfMentioned("japanese", "日语", "japanese")
        addIfMentioned("korean", "韩语", "korean")
        return out.distinct()
    }

    fun languageExcludes(text: String): List<String> =
        excludeTerms(text).mapNotNull(::languageKey).distinct()

    fun languageKey(value: String): String? {
        val key = normalizeCommandText(value)
        return when {
            "粤语" in key || "cantonese" in key -> "cantonese"
            "国语" in key || "普通话" in key || "mandarin" in key -> "mandarin"
            "英文" in key || "英语" in key || "english" in key -> "english"
            "日语" in key || "japanese" in key -> "japanese"
            "韩语" in key || "korean" in key -> "korean"
            else -> null
        }
    }

    fun energyHint(text: String): String {
        val key = normalizeCommandText(text)
        return when {
            listOf("低能量", "低能", "安静", "轻柔", "深夜", "想睡", "放松", "降一点", "别太吵", "不要太吵").any { it in key } -> "low"
            listOf("高能量", "高能", "燃", "嗨", "动感", "派对", "提神", "拉满").any { it in key } -> "high"
            listOf("中等", "稳一点", "别太闹", "别太炸", "不要太炸").any { it in key } -> "mid"
            else -> "any"
        }
    }

    fun genreHints(text: String): List<String> {
        val key = normalizeCommandText(text)
        val out = mutableListOf<String>()
        fun add(term: String, vararg cues: String) {
            if (cues.any { it in key }) out.add(term)
        }
        add("folk", "民谣")
        add("rock", "摇滚")
        add("pop", "流行")
        add("rnb", "r&b", "rnb")
        add("jazz", "爵士", "jazz")
        add("electronic", "电子")
        add("hiphop", "说唱", "嘻哈", "rap", "hiphop")
        add("citypop", "citypop", "city pop")
        add("indie", "独立")
        add("ballad", "抒情")
        return out.distinct()
    }

    fun currentStyleRequest(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("当前风格", "这种风格", "这个风格", "当前这种", "这种感觉", "这个感觉", "像这首", "刚才那个感觉", "刚才那个风格")
            .any { it in key }
    }

    fun styleQuestion(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("什么风格", "啥风格", "哪种风格", "是什么类型", "什么类型", "这首歌是什么感觉", "这首是什么感觉")
            .any { it in key }
    }

    fun wantsMoreFromStyle(text: String): Boolean {
        val key = normalizeCommandText(text)
        return currentStyleRequest(text) &&
            listOf("多来", "再来", "继续", "来几首", "接着", "排几首", "多放").any { it in key }
    }

    fun enableContinuation(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("自动续播", "播完继续", "续同要求", "继续按这个要求", "排完继续", "ai续播", "ai 自动续播")
            .any { it in key }
    }

    fun disableContinuation(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("不要续", "别续", "不续播", "播完停", "只播这几首", "播完停止", "只听这几首")
            .any { it in key }
    }

    fun looksLikeSimilarRequest(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("类似", "同味", "同类型", "那种", "风格", "像", "接几首", "再放几首", "来几首", "换点")
            .any { it in key }
    }

    fun existingPlaylistQuery(text: String): String? {
        if (playlistScopedRequest(text) != null) return null
        cloudPlaylistQuery(text)?.let { return it }
        val key = normalizeCommandText(text)
        if (listOf("包含", "包括", "带上", "要有", "排一组", "换一组", "换一个播放列表").any { it in key }) {
            return null
        }
        val patterns = listOf(
            Regex("(?:打开|播放|放|听)\\s*(?:我的|已有|收藏|网易云)?\\s*[《「“\"]([^》」”\"]{1,40})[》」”\"]\\s*(?:歌单|播放列表)?"),
            Regex("(?:打开|播放|放|听)\\s*(?:我的|已有|收藏|网易云)?\\s*([^，。,.!！?？]{1,40}?)(?:歌单|播放列表)"),
            Regex("(?:我的|已有|收藏|网易云)\\s*([^，。,.!！?？]{1,40}?)(?:歌单|播放列表)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val query = match.groupValues.getOrNull(1).orEmpty()
                .replace(Regex("^(这个|那个|这张|那张)"), "")
                .trim()
            if (query.isNotBlank()) return query
        }
        return null
    }

    fun playlistScopedRequest(text: String): PlaylistScopedRequest? {
        artistFirstPlaylistScopedRequest(text)?.let { return it }
        val parts = playlistScopedParts(text) ?: return null
        val playlistName = sanitizePlaylistName(parts.first)
        if (playlistName.isBlank()) return null
        val tail = parts.second.trim().trimStart('的', '里')
        val (artists, target) = parseScopedTail(tail)
        return PlaylistScopedRequest(
            playlistName = playlistName,
            target = target,
            primaryArtists = artists,
        )
    }

    fun cloudPlaylistQuery(text: String): String? {
        val key = normalizeCommandText(text)
        val mentionsCloud = listOf("我的网盘", "网盘", "云盘").any { it in key }
        val wantsPlay = listOf("打开", "播放", "放", "听", "来点", "排一组").any { it in key }
        return if (mentionsCloud && wantsPlay) CLOUD_PLAYLIST_NAME else null
    }

    fun isCloudPlaylistName(query: String): Boolean {
        val key = normalizeForMatch(query)
        return key in setOf("我的网盘", "网盘", "云盘", "云盘歌单", "网盘歌单", "我的云盘")
    }

    fun looksLikeReplaceRequest(text: String): Boolean {
        val key = normalizeCommandText(text)
        return listOf("想听", "听点", "放点", "播放列表", "歌单", "排一组", "换一组", "来点", "包含", "带上", "最后", "不要", "别", "不想听")
            .any { it in key } || isCatalogDescriptor(text) || primaryArtistHints(text).isNotEmpty() || connectiveLeadTrack(text) != null
    }

    fun normalizeCommandText(value: String): String =
        value.lowercase()
            .replace(Regex("[\\s，。,.!！?？、~～:：；;]+"), "")
            .trim()

    fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace(Regex("[\\s'\"`·・\\-－—_,，。.、!?！？()（）\\[\\]【】《》<>&/]+"), "")
            .trim()

    private fun sanitizeTitle(raw: String): String =
        raw.trim()
            .trim('《', '》', '「', '」', '“', '”', '"', '\'', ' ')
            .replace(Regex("^(那首|这首|一首|歌曲|歌名)"), "")
            .replace(Regex("(这首歌|那首歌|歌曲)$"), "")
            .replace(Regex("(到|进)?(播放列表|播放队列|队列|歌单|下一首|中间)$"), "")
            .trim()

    private fun isGenericMusicNoun(value: String): Boolean {
        val key = normalizeForMatch(value)
        return key in setOf("歌", "歌曲", "音乐", "曲", "一些歌", "几首歌")
    }

    private fun playlistScopedParts(text: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*(我的网盘|网盘|云盘)(?:歌单|播放列表)?\\s*(?:里|里面|中的?|内|才有的)\\s*(.*)$"),
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*(?:我的|已有|收藏|网易云)?\\s*[《「“\"]([^》」”\"]{1,40})[》」”\"]\\s*(?:歌单|播放列表)?\\s*(?:里|里面|中的?|内)\\s*(.*)$"),
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*(?:我的|已有|收藏|网易云)?\\s*([^，。,.!！?？]{1,40}?)(?:歌单|播放列表)\\s*(?:里|里面|中的?|内|才有的)\\s*(.*)$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val playlist = match.groupValues.getOrNull(1).orEmpty()
            val tail = match.groupValues.getOrNull(2).orEmpty()
            if (playlist.isNotBlank()) return playlist to tail
        }
        return null
    }

    private fun artistFirstPlaylistScopedRequest(text: String): PlaylistScopedRequest? {
        val patterns = listOf(
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*([^，。,.!！?？]{1,32})\\s*(?:在|从)\\s*(我的网盘|网盘|云盘)(?:歌单|播放列表)?\\s*(?:里|里面|中的?|内)?\\s*(?:的)?(?:歌|歌曲|音乐)?\\s*$"),
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*([^，。,.!！?？]{1,32})\\s*(?:在|从)\\s*[《「“\"]([^》」”\"]{1,40})[》」”\"]\\s*(?:歌单|播放列表)?\\s*(?:里|里面|中的?|内)?\\s*(?:的)?(?:歌|歌曲|音乐)?\\s*$"),
            Regex("(?:打开|播放|放|听|来点|排一组)?\\s*([^，。,.!！?？]{1,32})\\s*(?:在|从)\\s*(?:我的|已有|收藏|网易云)?\\s*([^，。,.!！?？]{1,40}?)(?:歌单|播放列表)\\s*(?:里|里面|中的?|内)?\\s*(?:的)?(?:歌|歌曲|音乐)?\\s*$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val artists = splitArtistHints(match.groupValues.getOrNull(1).orEmpty())
            val playlistName = sanitizePlaylistName(match.groupValues.getOrNull(2).orEmpty())
            if (artists.isNotEmpty() && playlistName.isNotBlank()) {
                return PlaylistScopedRequest(playlistName = playlistName, primaryArtists = artists)
            }
        }
        return null
    }

    private fun parseScopedTail(rawTail: String): Pair<List<String>, TrackRequirement?> {
        val tail = rawTail.trim()
            .trimStart('的')
            .replace(Regex("^(只放|播放|放|听|来点|来些|里面的|里的)"), "")
            .trim()
        val key = normalizeForMatch(tail)
        if (key.isBlank() || key in setOf("歌", "歌曲", "音乐", "歌儿")) return emptyList<String>() to null

        Regex("^([^，。,.!！?？]{1,32})\\s*的\\s*[《「“\"]([^》」”\"]{1,40})[》」”\"]").find(tail)?.let { match ->
            val artists = splitArtistHints(match.groupValues.getOrNull(1).orEmpty())
            val title = sanitizeTitle(match.groupValues.getOrNull(2).orEmpty())
            if (title.isNotBlank()) return artists to TrackRequirement(title = title, artist = artists.firstOrNull())
        }
        Regex("^([^，。,.!！?？]{1,32})\\s*的\\s*([^，。,.!！?？]{1,40})").find(tail)?.let { match ->
            val artists = splitArtistHints(match.groupValues.getOrNull(1).orEmpty())
            val title = sanitizeTitle(match.groupValues.getOrNull(2).orEmpty())
            if (artists.isNotEmpty() && title.isNotBlank() && normalizeForMatch(title) !in setOf("歌", "歌曲", "音乐")) {
                return artists to TrackRequirement(title = title, artist = artists.firstOrNull())
            }
        }
        Regex("^([^，。,.!！?？]{1,32})\\s*的(?:歌|歌曲|音乐)$").find(tail)?.let { match ->
            return splitArtistHints(match.groupValues.getOrNull(1).orEmpty()) to null
        }
        Regex("[《「“\"]([^》」”\"]{1,40})[》」”\"]").find(tail)?.let { match ->
            val title = sanitizeTitle(match.groupValues.getOrNull(1).orEmpty())
            if (title.isNotBlank()) return emptyList<String>() to TrackRequirement(title = title)
        }
        val title = sanitizeTitle(tail)
        return emptyList<String>() to title
            .takeIf { normalizeForMatch(it) !in setOf("歌", "歌曲", "音乐") }
            ?.let { TrackRequirement(title = it) }
    }

    private fun sanitizePlaylistName(raw: String): String {
        val trimmed = raw.trim()
            .trim('《', '》', '「', '」', '“', '”', '"', '\'', ' ')
            .replace(Regex("^(这个|那个|这张|那张|我的|已有|收藏|网易云)"), "")
            .replace(Regex("(歌单|播放列表)$"), "")
            .trim()
        return if (isCloudPlaylistName(trimmed)) CLOUD_PLAYLIST_NAME else trimmed
    }

    private fun splitArtistHints(raw: String): List<String> {
        val cleaned = sanitizeArtistHint(raw) ?: return emptyList()
        return cleaned
            .split(Regex("\\s*(?:、|，|,|/|＆|&|和|跟|与|以及|及)\\s*"))
            .mapNotNull(::sanitizeArtistHint)
            .distinctBy(::normalizeForMatch)
    }

    private fun List<String>.toTrackRequirements(): List<TrackRequirement> {
        val seen = HashSet<String>()
        return mapNotNull { title ->
            val key = normalizeForMatch(title)
            if (key.isBlank() || !seen.add(key)) null else TrackRequirement(title = title)
        }
    }

    private fun dedupeTrackRequirements(requirements: List<TrackRequirement>): List<TrackRequirement> {
        val seen = HashSet<String>()
        return requirements.filter { requirement ->
            val key = "${normalizeForMatch(requirement.artist.orEmpty())}:${normalizeForMatch(requirement.title)}"
            requirement.title.isNotBlank() && seen.add(key)
        }
    }

    private fun parseSmallCount(raw: String): Int? {
        raw.toIntOrNull()?.let { return it.takeIf { value -> value > 0 } }
        if (raw.isBlank()) return null
        val digit = mapOf(
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9,
        )
        if (raw == "十") return 10
        val tenIndex = raw.indexOf('十')
        if (tenIndex >= 0) {
            val tens = raw.substring(0, tenIndex).firstOrNull()?.let { digit[it] } ?: 1
            val ones = raw.substring(tenIndex + 1).firstOrNull()?.let { digit[it] } ?: 0
            return (tens * 10 + ones).takeIf { it > 0 }
        }
        return raw.firstOrNull()?.let { digit[it] }
    }
}
