package app.pipo.nativeapp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

data class CrashLogEntry(
    val time: String,
    val threadName: String,
    val summary: String,
    val path: String,
)

object CrashLogStore {
    private const val PREFS = "pipo-crash-log"
    private const val KEY_TIME = "time"
    private const val KEY_THREAD = "thread"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_PATH = "path"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
    }

    fun latest(context: Context): CrashLogEntry? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getString(KEY_TIME, null) ?: return null
        return CrashLogEntry(
            time = time,
            threadName = prefs.getString(KEY_THREAD, "").orEmpty(),
            summary = prefs.getString(KEY_SUMMARY, "").orEmpty(),
            path = prefs.getString(KEY_PATH, "").orEmpty(),
        )
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        crashDir(appContext).deleteRecursively()
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val time = timestamp()
        val dir = crashDir(context).apply { mkdirs() }
        val file = File(dir, "latest-crash.txt")
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use { printer -> throwable.printStackTrace(printer) }
        }.toString()
        val summary = throwableSummary(throwable)
        file.writeText(
            buildString {
                appendLine("time=$time")
                appendLine("thread=${thread.name}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("summary=$summary")
                appendLine()
                append(trace)
            },
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TIME, time)
            .putString(KEY_THREAD, thread.name)
            .putString(KEY_SUMMARY, summary)
            .putString(KEY_PATH, file.absolutePath)
            .apply()
    }

    private fun crashDir(context: Context): File = File(context.filesDir, "crashes")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun throwableSummary(throwable: Throwable): String {
        val name = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }
}
