package app.pipo.nativeapp.data

/**
 * 续杯式 Discovery —— 镜像 src/lib/discovery.ts 的三步走流水线。
 *
 *   1) AI 出 seeds（搜索关键词，每个带 rationale）—— 没 AI key 时退化成
 *      "当前 artist + tasteProfile.tags 前 3 个" 的本地启发式
 *   2) netease.search 把每个 seed 搜回真实曲目；聚合 + 去重 + 排除用户已有
 *   3) AI rerank（可选）—— 这一版直接按 seed 顺序 + tag overlap 排序，
 *      AI 接入后可以扩展为 commentOnTrack 风格
 */
class Discovery(
    private val repository: PipoRepository,
) {
    suspend fun fetchMore(
        around: NativeTrack?,
        tags: List<String>,
        excludeIds: Set<Long>,
        wantCount: Int = 10,
    ): List<NativeTrack> {
        // ---- 1) seeds：AI 优先，失败兜底 ----
        val seeds = generateSeedsWithAi(around, tags) ?: buildLocalSeeds(around, tags)
        if (seeds.isEmpty()) return emptyList()

        // ---- 2) netease search 聚合 ----
        val collected = LinkedHashMap<Long, NativeTrack>()
        for (seed in seeds) {
            if (collected.size >= wantCount) break
            val hits = runCatching { repository.searchTracks(seed, limit = 12) }
                .getOrDefault(emptyList())
            for (t in hits) {
                val ne = t.neteaseId ?: continue
                if (excludeIds.contains(ne)) continue
                if (collected.containsKey(ne)) continue
                collected[ne] = t
                if (collected.size >= wantCount) break
            }
        }
        return collected.values.toList()
    }

    /** 走 AI 出 seeds —— 失败 / 空 / AI key 没填 都返回 null，让上层走本地兜底 */
    private suspend fun generateSeedsWithAi(
        around: NativeTrack?,
        tags: List<String>,
    ): List<String>? {
        val artistHint = around?.artist.orEmpty()
        if (artistHint.isBlank() && tags.isEmpty()) return null
        val prompt = buildString {
            append("帮我列 6 个网易云搜索关键词，找跟以下口味相邻的歌。\n")
            if (artistHint.isNotBlank()) append("当前在听：$artistHint - ${around?.title.orEmpty()}\n")
            if (tags.isNotEmpty()) append("我的口味标签：${tags.take(8).joinToString("、")}\n")
            append("\n要求：每行一个关键词；不要解释；不要序号；不要引号；不要空行。")
        }
        val raw = runCatching {
            repository.aiChat(
                system = "你是音乐选曲助理，输出搜索关键词，不要客套。",
                user = prompt,
                temperature = 0.7f,
                maxTokens = 180,
            )
        }.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return null
        val seeds = raw.lineSequence()
            .map { it.trim().trim('"', '“', '”', '`', '·', '·', ' ') }
            .filter { it.isNotEmpty() && it.length < 50 }
            .toList()
            .distinct()
        return seeds.takeIf { it.isNotEmpty() }
    }

    private fun buildLocalSeeds(around: NativeTrack?, tags: List<String>): List<String> {
        val seeds = mutableListOf<String>()
        around?.artist?.takeIf { it.isNotBlank() }?.let { seeds.add(it) }
        for (tag in tags.take(3)) {
            val cleaned = tag.trim()
            if (cleaned.isNotBlank()) seeds.add(cleaned)
        }
        return seeds.distinct()
    }
}

/**
 * 续杯回调 —— 队列在 PlayerViewModel 里检测到 current 后剩 < 阈值时调一次。
 *
 * 默认实现：对 Discovery 的薄包装。pet-agent 接通时可以用 AI 选歌 source 替换。
 */
fun interface ContinuousQueueSource {
    suspend fun fetchMore(excludeIds: Set<Long>): List<NativeTrack>
}
