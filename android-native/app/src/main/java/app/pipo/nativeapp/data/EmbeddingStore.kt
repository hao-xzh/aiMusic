package app.pipo.nativeapp.data

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 单曲 embedding 向量存储。
 *
 *   - 内存层 ConcurrentHashMap<trackId, FloatArray>
 *   - 持久层：单一二进制文件 `embeddings_v1.bin`
 *     格式：[u32 magic][u32 version][u32 dim][u32 entryCount][...entries...]
 *     每个 entry：[u16 trackIdLen][trackId bytes][hash sourceHash u64][dim*float32]
 *   - SharedPrefs 太小不合适：5000 首 × 1536 维 × 4 bytes = 30 MB
 *
 * sourceHash：embeddingText 内容的简单 hash，用来检测语义档案变了 → 旧 embedding 失效
 */
class EmbeddingStore(context: Context) {

    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val memory = ConcurrentHashMap<String, FloatArray>()
    private val sourceHashes = ConcurrentHashMap<String, Long>()

    @Volatile
    var dim: Int = 0
        private set

    init {
        loadAll()
    }

    fun get(trackId: String): FloatArray? = memory[trackId]
    fun has(trackId: String, sourceHash: Long): Boolean =
        sourceHashes[trackId] == sourceHash && memory.containsKey(trackId)
    fun count(): Int = memory.size

    /** 批量写入。dim 第一次写入时锁定；后续不一致条目跳过。 */
    @Synchronized
    fun putAll(entries: List<Triple<String, Long, FloatArray>>) {
        if (entries.isEmpty()) return
        if (dim == 0) dim = entries.first().third.size
        for ((id, hash, vec) in entries) {
            if (vec.size != dim) continue
            memory[id] = vec
            sourceHashes[id] = hash
        }
        persist()
    }

    fun computeSourceHash(text: String): Long {
        // FNV-1a 64bit
        var h = 0xcbf29ce484222325uL.toLong()
        for (c in text) {
            h = h xor (c.code.toLong())
            h = (h * 0x100000001b3L)
        }
        return h
    }

    @Synchronized
    private fun persist() {
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(0)
            // header
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC)
            header.putInt(VERSION)
            header.putInt(dim)
            header.putInt(memory.size)
            raf.write(header.array())

            // entries
            for ((id, vec) in memory) {
                val hash = sourceHashes[id] ?: 0L
                val idBytes = id.toByteArray(Charsets.UTF_8)
                val buf = ByteBuffer
                    .allocate(2 + idBytes.size + 8 + dim * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                buf.putShort(idBytes.size.toShort())
                buf.put(idBytes)
                buf.putLong(hash)
                for (f in vec) buf.putFloat(f)
                raf.write(buf.array())
            }
        } finally {
            raf.close()
        }
    }

    private fun loadAll() {
        if (!file.exists() || file.length() < 16) return
        try {
            val raf = RandomAccessFile(file, "r")
            try {
                val headerBytes = ByteArray(16)
                raf.readFully(headerBytes)
                val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = header.int
                if (magic != MAGIC) return
                val version = header.int
                if (version != VERSION) return
                dim = header.int
                val count = header.int
                if (dim <= 0 || count < 0) return
                repeat(count) {
                    val idLenBytes = ByteArray(2)
                    raf.readFully(idLenBytes)
                    val idLen = ByteBuffer.wrap(idLenBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val idBytes = ByteArray(idLen)
                    raf.readFully(idBytes)
                    val id = String(idBytes, Charsets.UTF_8)
                    val hashBytes = ByteArray(8)
                    raf.readFully(hashBytes)
                    val hash = ByteBuffer.wrap(hashBytes).order(ByteOrder.LITTLE_ENDIAN).long
                    val vecBytes = ByteArray(dim * 4)
                    raf.readFully(vecBytes)
                    val vec = FloatArray(dim)
                    val vb = ByteBuffer.wrap(vecBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until dim) vec[i] = vb.float
                    memory[id] = vec
                    sourceHashes[id] = hash
                }
            } finally {
                raf.close()
            }
        } catch (_: Exception) {
            // 坏文件直接清掉，不让坏数据传染下次启动
            runCatching { file.delete() }
            memory.clear()
            sourceHashes.clear()
            dim = 0
        }
    }

    /**
     * cosine similarity，归一化向量与否都能用，最终返回 [-1, 1]。
     * 这里**不假设**输入归一化 —— OpenAI text-embedding-3 系列默认是归一化的，
     * 但保险起见还是除以模长，单次几十个 multiplications，可以忽略。
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            dot += x * y; na += x * x; nb += y * y
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }

    companion object {
        private const val FILE_NAME = "embeddings_v1.bin"
        private const val MAGIC = 0x504D4245  // "EMBP"
        private const val VERSION = 1
    }
}
