package app.pipo.nativeapp

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DiagnosticsLogStore {
    private const val DIR_NAME = "diagnostics"
    private const val CURRENT_FILE = "diagnostics-current.ndjson"
    private const val SHARE_DIR_NAME = "diagnostics-share"
    private const val MAX_FILE_BYTES = 1_200_000L
    private const val MAX_ARCHIVE_FILES = 6
    private const val DEFAULT_SNAPSHOT_BYTES = 420_000
    private val MUTED_AREAS = setOf("lyrics_speed", "amll_lyric")
    private val lock = Any()

    // 日志写盘挪到单线程后台 —— 之前 record() 在调用线程同步 appendText + mkdirs + 文件大小
    // 检查;而 record 大量从 Player.Listener 等主线程回调触发,切歌 / 暂停时会出现主线程磁盘
    // IO 卡顿甚至 ANR 隐患。单线程 executor 保持 FIFO,落盘顺序与提交顺序一致(等价旧的锁到达序)。
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pipo-diagnostics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        // 清理 + start 事件都走后台队列;FIFO 保证 reset 先于 start 落盘,且不阻塞冷启动主线程。
        ioExecutor.execute {
            synchronized(lock) { resetEventLogs(app) }
        }
        runCatching {
            record(
                area = "app",
                event = "start",
                fields = mapOf(
                    "version" to appVersion(context),
                    "sdk" to Build.VERSION.SDK_INT,
                    "device" to deviceName(),
                ),
            )
        }
    }

    fun record(area: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        if (area in MUTED_AREAS) return
        val context = appContext ?: return
        // 时间戳在调用时刻取(保证事件按发生时间排序,即便落盘稍晚);fields 在调用线程快照一份,
        // 之后的 JSON 组装 + 写盘全部在后台单线程做,调用方零磁盘 IO。
        val ts = timestamp()
        val snapshot = if (fields.isEmpty()) emptyMap() else HashMap(fields)
        runCatching {
            ioExecutor.execute {
                runCatching {
                    synchronized(lock) {
                        val dir = diagnosticsDir(context).apply { mkdirs() }
                        rotateIfNeeded(dir)
                        val json = JSONObject()
                            .put("ts", ts)
                            .put("area", area.take(40))
                            .put("event", event.take(80))
                        for ((key, value) in snapshot) {
                            json.put(key.take(48), sanitizeValue(key, value))
                        }
                        currentFile(dir).appendText(json.toString() + "\n")
                    }
                }
            }
        }
    }

    /** 等待后台队列里已提交的写入落盘 —— 分享 / 导出诊断前调用,保证快照包含最新事件。 */
    private fun flushPending() {
        val latch = CountDownLatch(1)
        val submitted = runCatching { ioExecutor.execute { latch.countDown() } }.isSuccess
        if (!submitted) return
        runCatching { latch.await(2, TimeUnit.SECONDS) }
    }

    fun snapshotText(context: Context, maxBytes: Int = DEFAULT_SNAPSHOT_BYTES): String {
        val app = context.applicationContext
        flushPending()
        return synchronized(lock) {
            val dir = diagnosticsDir(app).apply { mkdirs() }
            rotateIfNeeded(dir)
            buildString {
                appendLine("Pipo diagnostic log")
                appendLine("generated=${timestamp()}")
                appendLine("app=${appVersion(app)}")
                appendLine("device=${deviceName()}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine()
                appendCrashSection(app)
                val files = dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".ndjson") }
                    ?.sortedBy { it.lastModified() }
                    .orEmpty()
                appendTransitionSection(files)
                appendLine("---- recent events ----")
                val budgetPerFile = (maxBytes / files.size.coerceAtLeast(1)).coerceAtLeast(48_000)
                files.forEach { file ->
                    appendLine("## ${file.name}")
                    append(tailText(file, budgetPerFile))
                    if (!endsWith("\n")) appendLine()
                }
            }
        }
    }

    fun createShareFile(context: Context): File {
        val app = context.applicationContext
        val dir = File(app.cacheDir, SHARE_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
        return File(dir, "pipo-diagnostics-${fileTimestamp()}.txt").apply {
            writeText(snapshotText(app, maxBytes = 820_000))
        }
    }

    private fun resetEventLogs(context: Context) {
        diagnosticsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ndjson") }
            ?.forEach { it.delete() }
        File(context.cacheDir, SHARE_DIR_NAME).deleteRecursively()
    }

    private fun StringBuilder.appendCrashSection(context: Context) {
        val crash = CrashLogStore.latest(context)
        appendLine("---- latest crash ----")
        if (crash == null) {
            appendLine("none")
            appendLine()
            return
        }
        appendLine("time=${crash.time}")
        appendLine("thread=${crash.threadName}")
        appendLine("summary=${crash.summary}")
        val crashFile = File(crash.path)
        if (crashFile.isFile) {
            appendLine()
            append(tailText(crashFile, maxBytes = 64_000))
            if (!endsWith("\n")) appendLine()
        }
        appendLine()
    }

    private fun StringBuilder.appendTransitionSection(files: List<File>) {
        appendLine("---- seamless transitions (recent 20) ----")
        val lines = recentTransitionLines(files, maxEvents = 20)
        if (lines.isEmpty()) {
            appendLine("none")
        } else {
            lines.forEach(::appendLine)
        }
        appendLine()
    }

    private fun recentTransitionLines(files: List<File>, maxEvents: Int): List<String> {
        val events = setOf(
            "queue_commit",
            "transition_prepare_report",
            "transition_summary",
            "stale_transition_cancel",
        )
        val out = ArrayList<String>()
        for (file in files.asReversed()) {
            if (out.size >= maxEvents) break
            val lines = tailText(file, maxBytes = 220_000)
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .toList()
                .asReversed()
            for (line in lines) {
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val event = json.optString("event")
                if (event !in events) continue
                out.add(formatTransitionLine(json))
                if (out.size >= maxEvents) break
            }
        }
        return out.asReversed()
    }

    private fun formatTransitionLine(json: JSONObject): String {
        val fields = listOf(
            "queueVersion",
            "operation",
            "pairKey",
            "mode",
            "success",
            "risk",
            "accepted",
            "reordered",
            "reason",
            "failureReason",
            "handoffGapMs",
            "resumeDriftMs",
            "actualOverlapMs",
            "auxReadyDelayMs",
            "featuresReadyCount",
            "resolvedCount",
        ).mapNotNull { key ->
            json.optCompact(key)?.let { "$key=$it" }
        }.joinToString(" ")
        return listOf(
            json.optCompact("ts").orEmpty(),
            "${json.optCompact("area").orEmpty()}/${json.optCompact("event").orEmpty()}",
            fields,
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun JSONObject.optCompact(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }?.take(120)
    }

    private fun rotateIfNeeded(dir: File) {
        val file = currentFile(dir)
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) {
            trimArchives(dir)
            return
        }
        val archive = File(dir, "diagnostics-${fileTimestamp()}.ndjson")
        file.renameTo(archive)
        trimArchives(dir)
    }

    private fun trimArchives(dir: File) {
        val archives = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("diagnostics-") && it.name.endsWith(".ndjson") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        archives.drop(MAX_ARCHIVE_FILES).forEach { it.delete() }
    }

    private fun sanitizeValue(key: String, value: Any?): Any {
        if (value == null) return JSONObject.NULL
        val lower = key.lowercase(Locale.US)
        if (isSensitiveKey(lower)) {
            return "[redacted]"
        }
        if (lower.contains("url")) return redactUrl(value.toString())
        return when (value) {
            is Number, is Boolean -> value
            else -> value.toString().take(280)
        }
    }

    private fun isSensitiveKey(lowerKey: String): Boolean {
        if (lowerKey.endsWith("count")) return false
        return lowerKey == "key" ||
            lowerKey == "token" ||
            lowerKey.contains("access_token") ||
            lowerKey.contains("refresh_token") ||
            lowerKey.contains("auth_token") ||
            lowerKey.contains("session_token") ||
            lowerKey.contains("cookie") ||
            lowerKey.contains("authorization") ||
            lowerKey.contains("password") ||
            lowerKey.contains("secret") ||
            lowerKey.contains("apikey") ||
            lowerKey.contains("api_key")
    }

    private fun redactUrl(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return "[redacted-url]"
        val hostStart = schemeEnd + 3
        val hostEnd = raw.indexOf('/', hostStart).takeIf { it >= 0 } ?: raw.length
        return raw.take(hostEnd)
    }

    private fun tailText(file: File, maxBytes: Int): String {
        if (!file.exists()) return ""
        val bytes = file.readBytes()
        val start = (bytes.size - maxBytes).coerceAtLeast(0)
        val text = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
        return if (start > 0) "... trimmed ${bytes.size - start} bytes from ${file.name} ...\n$text" else text
    }

    private fun currentFile(dir: File): File = File(dir, CURRENT_FILE)

    private fun diagnosticsDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun appVersion(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            "${info.versionName ?: "unknown"} ($versionCode)"
        }.getOrDefault("unknown")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
