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
                lyricLineHeight = 35.sp,
                lyricFontWeight = FontWeight.Bold,
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
                "Close" to 420L,
                "your" to 260L,
                "eyes" to 520L,
                "and" to 180L,
                "let" to 240L,
                "the" to 180L,
                "midnight" to 820L,
                "color" to 660L,
                "move" to 1_220L,
            ),
            translation = "普通英文密集扫色和上浮",
        ),
        sandboxLine(
            startMs = 5_700L,
            words = listOf(
                "we" to 140L,
                "go" to 150L,
                "on" to 130L,
                "and" to 120L,
                "run" to 170L,
                "it" to 120L,
                "up" to 240L,
                "again" to 520L,
            ),
            translation = "极短英文连续词",
        ),
        sandboxLine(
            startMs = 8_200L,
            words = listOf(
                "Hold" to 680L,
                "on" to 420L,
                "stay" to 1_620L,
                "soft" to 1_260L,
                "glow" to 1_780L,
            ),
            translation = "英文慢词：stay / soft / glow",
            companion = sandboxLine(
                startMs = 10_150L,
                words = listOf(
                    "(stay)" to 1_000L,
                    "(glow)" to 1_200L,
                ),
                role = PipoLyricRole.Companion,
            ),
        ),
        sandboxLine(
            startMs = 12_600L,
            words = listOf(
                "rising" to 680L,
                "river" to 740L,
                "joins" to 900L,
                "every" to 520L,
                "fragile" to 940L,
                "journey" to 1_120L,
                "near" to 460L,
                "bright" to 640L,
                "edges" to 900L,
            ),
            translation = "多行英文换行 / r j 悬伸 / 放大越界压测",
        ),
        sandboxLine(
            startMs = 15_100L,
            words = listOf(
                "月光" to 720L,
                "慢慢" to 1_580L,
                "升起" to 940L,
                "落在" to 760L,
                "你眼里" to 1_360L,
            ),
            spacing = false,
            romaji = "yue guang man man sheng qi",
            translation = "中文扫色、慢词和副词行",
        ),
        sandboxLine(
            startMs = 21_300L,
            words = listOf(
                "一瞬" to 260L,
                "一拍" to 240L,
                "一闪" to 220L,
                "又回来" to 1_260L,
            ),
            spacing = false,
            romaji = "yi shun yi pai yi shan",
            translation = "极短中文连续词",
        ),
        sandboxLine(
            startMs = 25_000L,
            words = listOf(
                "I" to 220L,
                "hear" to 560L,
                "you" to 260L,
                "singing" to 900L,
                "from" to 260L,
                "the" to 180L,
                "other" to 560L,
                "side" to 1_240L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "右侧对唱切句",
        ),
        sandboxLine(
            startMs = 25_000L,
            words = listOf(
                "我也" to 520L,
                "在同一拍" to 1_120L,
                "回应" to 860L,
                "你的" to 560L,
                "声音" to 1_220L,
            ),
            spacing = false,
            translation = "左侧中文合唱/重叠行",
            companion = sandboxLine(
                startMs = 27_380L,
                words = listOf(
                    "(回应)" to 900L,
                    "(声音)" to 1_180L,
                ),
                role = PipoLyricRole.Companion,
                spacing = false,
            ),
        ),
        sandboxLine(
            startMs = 31_000L,
            words = listOf(
                "One" to 280L,
                "more" to 300L,
                "breath" to 460L,
            ),
            translation = "快切 1",
        ),
        sandboxLine(
            startMs = 31_960L,
            words = listOf(
                "then" to 260L,
                "we" to 220L,
                "fall" to 520L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "快切 2",
        ),
        sandboxLine(
            startMs = 32_880L,
            words = listOf(
                "right" to 300L,
                "back" to 300L,
                "in" to 180L,
            ),
            translation = "快切 3",
        ),
        sandboxLine(
            startMs = 33_620L,
            words = listOf(
                "time" to 360L,
                "again" to 760L,
            ),
            alignment = PipoLyricAlignment.End,
            translation = "快切 4",
        ),
        sandboxLine(
            startMs = 35_400L,
            words = listOf(
                "最后" to 620L,
                "停住" to 1_420L,
                "再亮起来" to 1_560L,
            ),
            spacing = false,
            romaji = "zui hou ting zhu zai liang qi lai",
            translation = "中文慢词收尾",
        ),
    )
}

private fun sandboxLine(
    startMs: Long,
    words: List<Pair<String, Long>>,
    role: PipoLyricRole = PipoLyricRole.Primary,
    alignment: PipoLyricAlignment = PipoLyricAlignment.Start,
    spacing: Boolean = true,
    romaji: String? = null,
    translation: String? = null,
    companion: PipoLyricLine? = null,
): PipoLyricLine {
    var cursor = startMs
    val chars = words.mapIndexed { index, (word, durationMs) ->
        val text = if (spacing && index != words.lastIndex) "$word " else word
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
        if (!romaji.isNullOrBlank()) {
            add(
                PipoLyricLine(
                    startMs = startMs,
                    durationMs = (cursor - startMs).coerceAtLeast(1L),
                    text = romaji,
                    timing = PipoLyricTiming.Line,
                    role = PipoLyricRole.Romaji,
                    alignment = alignment,
                ),
            )
        }
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
    5_700L,
    8_200L,
    10_150L,
    15_100L,
    21_300L,
    25_000L,
    31_000L,
    31_960L,
    35_400L,
)
