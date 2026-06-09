package app.pipo.nativeapp.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.LyricTiming
import app.pipo.nativeapp.data.PipoLyricAlignment
import app.pipo.nativeapp.data.PipoLyricChar
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.data.PipoLyricRole
import app.pipo.nativeapp.data.PipoLyricTiming
import app.pipo.nativeapp.data.PipoLyricTimingPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun LyricSandboxScreen(
    initialPositionMs: Long = 0L,
    isPlaying: Boolean = true,
    probeEnabled: Boolean = false,
) {
    val lines = remember { sandboxLyricLines() }
    val totalDurationMs = remember(lines) {
        (lines.maxOfOrNull(::sandboxAudioEndMs) ?: 0L) + 1_600L
    }
    val probePositionsMs = remember(probeEnabled) {
        if (probeEnabled) NATIVE_SANDBOX_PROBE_POSITIONS_MS else LongArray(0)
    }
    val initialLoopPositionMs = remember(initialPositionMs, totalDurationMs) {
        val requestedPositionMs = probePositionsMs.firstOrNull() ?: initialPositionMs
        requestedPositionMs.coerceAtLeast(0L) % totalDurationMs.coerceAtLeast(1L)
    }
    var positionMs by remember(initialLoopPositionMs) { mutableLongStateOf(initialLoopPositionMs) }
    var basePositionMs by remember(initialLoopPositionMs) { mutableLongStateOf(initialLoopPositionMs) }
    var baseElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(totalDurationMs, isPlaying, probeEnabled) {
        while (isActive) {
            if (probeEnabled) {
                delay(NATIVE_SANDBOX_RAW_TICK_MS)
                continue
            } else if (isPlaying) {
                val elapsedMs = (SystemClock.elapsedRealtime() - baseElapsedMs).coerceAtLeast(0L)
                positionMs = (basePositionMs + elapsedMs) % totalDurationMs
            } else {
                positionMs = basePositionMs % totalDurationMs
            }
            delay(NATIVE_SANDBOX_RAW_TICK_MS)
        }
    }

    LaunchedEffect(probeEnabled, totalDurationMs) {
        if (!probeEnabled || probePositionsMs.isEmpty()) return@LaunchedEffect
        val duration = totalDurationMs.coerceAtLeast(1L)
        while (isActive) {
            probePositionsMs.forEach { targetMs ->
                val safeTargetMs = targetMs.coerceAtLeast(0L) % duration
                basePositionMs = safeTargetMs
                baseElapsedMs = SystemClock.elapsedRealtime()
                positionMs = safeTargetMs
                delay(NATIVE_SANDBOX_PROBE_HOLD_MS)
            }
        }
    }

    val lyricClock = remember(positionMs, lines) {
        LyricTiming.resolve(positionMs = positionMs, lines = lines)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF071316),
                        Color(0xFF102626),
                        Color(0xFF050606),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "AMLL native lyric sandbox",
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "loop ${(positionMs / 1000f).formatOne()}s",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        CompositionLocalProvider(LocalLyricAccent provides Color(0xFF68E0D2)) {
            AppleMusicLyricColumn(
                lines = lines,
                sessionId = "lyric-sandbox",
                activeLyricIndex = lyricClock.activeIndex,
                positionMs = lyricClock.positionMs,
                isPlaying = isPlaying,
                fg = Color.White,
                fgDim = Color.White.copy(alpha = 0.42f),
                fgUnsung = Color.White.copy(alpha = 0.42f),
                showTranslation = true,
                onSeekToMs = { targetMs ->
                    basePositionMs = targetMs.coerceAtLeast(0L)
                    baseElapsedMs = SystemClock.elapsedRealtime()
                    positionMs = basePositionMs
                },
                horizontalPadding = 24.dp,
                rowMinHeight = 62.dp,
                rowVerticalPadding = 8.dp,
                lyricFontSize = 30.sp,
                lyricLineHeight = 43.sp,
                lyricFontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 150.dp, bottom = 24.dp)
                    .align(Alignment.TopStart),
            )
        }
    }
}

private fun sandboxLyricLines(): List<PipoLyricLine> {
    return listOf(
        sandboxLine(
            startMs = 700L,
            words = listOf(
                "Early" to 560L,
                "in" to 240L,
                "the" to 240L,
                "morning" to 920L,
                "I" to 220L,
                "put" to 360L,
                "breakfast" to 980L,
                "at" to 240L,
                "your" to 360L,
                "table" to 1_320L,
            ),
            translation = "清晨我把早餐摆在你的桌前",
        ),
        sandboxLine(
            startMs = 6_300L,
            words = listOf(
                "I'm" to 420L,
                "convinced" to 900L,
                "I" to 180L,
                "know" to 520L,
                "the" to 240L,
                "problem" to 1_450L,
                "you" to 300L,
                "don't" to 480L,
                "love" to 760L,
                "me" to 360L,
                "the" to 260L,
                "same" to 1_500L,
            ),
            translation = "我确信问题出在你不再同样爱我",
            companion = sandboxLine(
                startMs = 7_950L,
                words = listOf(
                    "(problem)" to 980L,
                    "(same)" to 1_120L,
                ),
                role = PipoLyricRole.Companion,
            ),
        ),
        sandboxLine(
            startMs = 14_500L,
            words = listOf(
                "You're" to 480L,
                "just" to 360L,
                "going" to 780L,
                "through" to 520L,
                "the" to 220L,
                "motions" to 1_280L,
                "and" to 260L,
                "you're" to 420L,
                "not" to 380L,
                "being" to 620L,
                "fair" to 1_700L,
            ),
            translation = "你只是在敷衍，这样对我不公平",
        ),
        sandboxLine(
            startMs = 21_300L,
            words = listOf(
                "I've" to 360L,
                "got" to 320L,
                "my" to 280L,
                "pride" to 1_240L,
                "I" to 220L,
                "will" to 320L,
                "not" to 420L,
                "cry" to 1_450L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "我也有我的自尊，我不会哭",
        ),
        sandboxLine(
            startMs = 26_600L,
            words = listOf(
                "Still" to 480L,
                "I" to 200L,
                "can't" to 560L,
                "help" to 520L,
                "but" to 320L,
                "care" to 1_760L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "但我还是忍不住在意",
            companion = sandboxLine(
                startMs = 29_350L,
                words = listOf(
                    "no'" to 430L,
                    "no'" to 430L,
                    "no" to 1_120L,
                ),
                role = PipoLyricRole.Companion,
                alignment = PipoLyricAlignment.End,
            ),
        ),
        sandboxLine(
            startMs = 38_200L,
            words = listOf(
                "I'm" to 360L,
                "not" to 360L,
                "the" to 240L,
                "kind" to 520L,
                "of" to 260L,
                "girl" to 760L,
                "that" to 420L,
                "you" to 300L,
                "can" to 300L,
                "let" to 300L,
                "down" to 980L,
                "and" to 260L,
                "think" to 520L,
                "that" to 360L,
                "everything's" to 1_100L,
                "ok" to 1_650L,
            ),
            translation = "也不是那种任你伤害还假装没事的女孩",
        ),
        sandboxLine(
            startMs = 48_200L,
            words = listOf(
                "You" to 320L,
                "can" to 300L,
                "keep" to 520L,
                "pretending" to 1_420L,
                "that" to 340L,
                "we're" to 420L,
                "fine" to 1_300L,
            ),
            translation = "你可以继续假装我们都很好",
        ),
        sandboxLine(
            startMs = 48_200L,
            words = listOf(
                "I" to 240L,
                "can" to 320L,
                "hear" to 560L,
                "the" to 240L,
                "truth" to 1_520L,
                "between" to 920L,
                "lines" to 1_160L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "我听得出字句之间的真相",
        ),
        sandboxLine(
            startMs = 55_200L,
            words = listOf(
                "Stay" to 580L,
            ),
            translation = "停一下",
        ),
        sandboxLine(
            startMs = 55_900L,
            words = listOf(
                "don't" to 300L,
                "go" to 280L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "别走",
        ),
        sandboxLine(
            startMs = 56_560L,
            words = listOf(
                "right" to 300L,
                "here" to 360L,
            ),
            translation = "就在这里",
        ),
        sandboxLine(
            startMs = 57_320L,
            words = listOf(
                "with" to 260L,
                "me" to 460L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "陪着我",
        ),
    )
}

private fun sandboxLine(
    startMs: Long,
    words: List<Pair<String, Long>>,
    role: PipoLyricRole = PipoLyricRole.Primary,
    alignment: PipoLyricAlignment = PipoLyricAlignment.Start,
    translation: String? = null,
    companion: PipoLyricLine? = null,
): PipoLyricLine {
    var cursor = startMs
    val chars = words.mapIndexed { index, (word, durationMs) ->
        val text = if (index == words.lastIndex) word else "$word "
        PipoLyricChar(
            startMs = cursor,
            durationMs = durationMs,
            text = text,
            timingParts = sandboxTimingParts(text, cursor, durationMs),
        ).also {
            cursor += durationMs
        }
    }
    val text = chars.joinToString("") { it.text }
    val companions = buildList {
        if (companion != null) add(companion)
        if (!translation.isNullOrBlank()) {
            add(
                PipoLyricLine(
                    startMs = startMs,
                    durationMs = (cursor - startMs).coerceAtLeast(1L),
                    text = translation,
                    timing = PipoLyricTiming.Line,
                    role = PipoLyricRole.Translation,
                    alignment = alignment,
                ),
            )
        }
    }
    return PipoLyricLine(
        startMs = startMs,
        durationMs = (cursor - startMs).coerceAtLeast(1L),
        text = text,
        chars = chars,
        timing = PipoLyricTiming.Word,
        companionLines = companions,
        role = role,
        alignment = alignment,
    )
}

private fun sandboxAudioEndMs(line: PipoLyricLine): Long {
    val ownCharEnd = line.chars.maxOfOrNull { it.startMs + it.durationMs }
    val ownEnd = maxOf(ownCharEnd ?: line.startMs, line.startMs + line.durationMs)
    val companionEnd = line.companionLines.maxOfOrNull(::sandboxAudioEndMs)
    return maxOf(ownEnd, companionEnd ?: ownEnd)
}

private fun sandboxTimingParts(
    text: String,
    startMs: Long,
    durationMs: Long,
): List<PipoLyricTimingPart> {
    val visible = text.trim()
    if (visible.length <= 4 || durationMs < 900L) {
        return listOf(PipoLyricTimingPart(startMs, durationMs, text))
    }
    val firstLen = (visible.length / 2).coerceAtLeast(1)
    val firstText = visible.take(firstLen)
    val secondText = text.drop(firstLen)
    val firstDuration = (durationMs * firstText.length / text.length.coerceAtLeast(1)).coerceAtLeast(1L)
    return listOf(
        PipoLyricTimingPart(startMs, firstDuration, firstText),
        PipoLyricTimingPart(startMs + firstDuration, durationMs - firstDuration, secondText),
    )
}

private fun Float.formatOne(): String {
    return String.format(java.util.Locale.US, "%.1f", this)
}

private const val NATIVE_SANDBOX_RAW_TICK_MS = 80L
private const val NATIVE_SANDBOX_PROBE_HOLD_MS = 2_400L
private val NATIVE_SANDBOX_PROBE_POSITIONS_MS = longArrayOf(
    55_200L,
    55_250L,
    55_900L,
    56_560L,
    57_320L,
)
