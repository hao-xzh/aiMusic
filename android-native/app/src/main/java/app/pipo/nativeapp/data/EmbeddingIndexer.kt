package app.pipo.nativeapp.data

/**
 * 把曲库的语义档案 embeddingText 喂给 OpenAI text-embedding-3-small，
 * 拿到 1536 维向量持久化到 EmbeddingStore。
 *
 *   - 只针对已经被 SemanticIndexer 标过且 sourceLlm=true 的曲目（规则版的标签太薄不值得 embed）
 *   - 每批 32 条 input 上限 —— OpenAI 单次最多 2048，但回包字节数和延迟随 batch 线性增，32 是经验甜区
 *   - 自动跳过：sourceHash 匹配 → 已有 embedding 复用
 *
 * 用户当前 provider 不支持 embedding（DeepSeek / Xiaomi MiMo）时，aiEmbed 会抛错；
 * 调用方 catch 后会让 SemanticRecall fallback 回 lexical 匹配。
 */
class EmbeddingIndexer(
    private val repository: PipoRepository,
    private val store: EmbeddingStore,
    private val semanticStore: TrackSemanticStore,
) {
    data class Progress(val total: Int, val done: Int, val skipped: Int, val failed: Int)

    suspend fun indexAll(
        tracks: List<NativeTrack>,
        batch: Int = 32,
        onProgress: ((Progress) -> Unit)? = null,
    ): Progress {
        var done = 0
        var skipped = 0
        var failed = 0
        val total = tracks.size

        // 先收集需要 embed 的：有 LLM 标过的语义档案 + 没 cache 或 sourceHash 不匹配
        val pending = ArrayList<Pair<String, String>>()  // trackId, embeddingText
        for (t in tracks) {
            val sem = semanticStore.get(t.id)
            if (sem == null || !sem.sourceLlm || sem.embeddingText.isBlank()) {
                skipped++; done++
                onProgress?.invoke(Progress(total, done, skipped, failed))
                continue
            }
            val hash = store.computeSourceHash(sem.embeddingText)
            if (store.has(t.id, hash)) {
                skipped++; done++
                onProgress?.invoke(Progress(total, done, skipped, failed))
                continue
            }
            pending.add(t.id to sem.embeddingText)
        }

        // 分批跑
        for (slice in pending.chunked(batch)) {
            val texts = slice.map { it.second }
            val vectors = runCatching { repository.aiEmbed(texts) }.getOrNull()
            if (vectors == null || vectors.size != texts.size) {
                failed += slice.size; done += slice.size
                onProgress?.invoke(Progress(total, done, skipped, failed))
                continue
            }
            val entries = slice.mapIndexedNotNull { i, (trackId, text) ->
                val v = vectors.getOrNull(i) ?: return@mapIndexedNotNull null
                if (v.isEmpty()) return@mapIndexedNotNull null
                Triple(trackId, store.computeSourceHash(text), v)
            }
            if (entries.isNotEmpty()) store.putAll(entries)
            done += slice.size
            onProgress?.invoke(Progress(total, done, skipped, failed))
        }
        return Progress(total, done, skipped, failed)
    }

    /** 单条按需 embed —— 用于查询字符串（用户自然语言搜索） */
    suspend fun embedQuery(text: String): FloatArray? {
        val v = runCatching { repository.aiEmbed(listOf(text)) }.getOrNull()
        return v?.firstOrNull()?.takeIf { it.isNotEmpty() }
    }
}
