package app.pipo.nativeapp.playback

import android.content.Context
import android.content.SharedPreferences
import app.pipo.nativeapp.DiagnosticsLogStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object PlaybackSessionClock {
    val sessionId: String = UUID.randomUUID().toString()
    private val queueVersion = AtomicLong(0L)
    private val manualSelectionRevision = AtomicLong(0L)
    private var selectionPreferences: SharedPreferences? = null

    @Synchronized
    fun installContext(context: Context) {
        if (selectionPreferences != null) return
        selectionPreferences = context.getSharedPreferences("playback_selection", Context.MODE_PRIVATE)
        manualSelectionRevision.set(selectionPreferences!!.getLong("revision", 0L))
    }

    /** Only explicit song/playlist selection advances this; natural continuation does not. */
    @Synchronized
    fun noteManualSelection() {
        val revision = manualSelectionRevision.incrementAndGet()
        selectionPreferences?.edit()?.putLong("revision", revision)?.apply()
    }

    fun currentSelectionRevision(): Long = manualSelectionRevision.get()

    fun isSelectionCurrent(revision: Long?): Boolean =
        revision == null || revision == currentSelectionRevision()

    fun bump(reason: String): Long {
        val next = queueVersion.incrementAndGet()
        DiagnosticsLogStore.record(
            area = "playback_session",
            event = "queue_version_bumped",
            fields = mapOf(
                "sessionId" to sessionId,
                "queueVersion" to next,
                "reason" to reason,
            ),
        )
        return next
    }

    fun currentQueueVersion(): Long = queueVersion.get()

    fun isCurrent(version: Long): Boolean = currentQueueVersion() == version
}
