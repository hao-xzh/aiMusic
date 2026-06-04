package app.pipo.nativeapp.playback

import app.pipo.nativeapp.DiagnosticsLogStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object PlaybackSessionClock {
    val sessionId: String = UUID.randomUUID().toString()
    private val queueVersion = AtomicLong(0L)

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
