package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.ArtistAffinity
import app.pipo.nativeapp.data.EraSlice
import app.pipo.nativeapp.data.GenreTag
import app.pipo.nativeapp.data.PipoGraph
import java.time.Instant
import java.time.ZoneId

@Composable
fun TasteScreen() {
    val profile by PipoGraph.tasteProfileStore.flow.collectAsState()
    val nav = LocalNav.current
    ScreenScaffold(title = "TASTE") {
        val p = profile
        if (p == null) {
            EmptyState(
                title = "还没蒸馏",
                subtitle = "去歌单页点右上 \"蒸馏\" 按钮，挑几张歌单蒸馏一份你的口味画像。",
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(CircleShape)
                    .background(PipoColors.Mint.copy(alpha = 0.18f))
                    .clickable { nav?.openDistill?.invoke() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("去歌单页", color = PipoColors.Mint, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
            return@ScreenScaffold
        }

        Text(
            text = p.summary,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        p.taglines.forEach { line ->
            Text(
                text = line,
                color = PipoColors.TextMuted,
                style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        StatGrid(
            items = listOf(
                "歌单" to p.sourcePlaylistCount.toString(),
                "总曲目" to p.totalTrackCount.toString(),
                "AI 蒸馏" to "${p.sampledTrackCount} 首",
                "更新于" to dateOnly(p.derivedAt),
            ),
        )
        Spacer(modifier = Modifier.height(28.dp))

        SectionTitle("主流派")
        p.genres.forEach { GenreRow(it) }
        Spacer(modifier = Modifier.height(20.dp))

        if (p.eras.isNotEmpty()) {
            SectionTitle("年代倾向")
            EraBars(p.eras)
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (p.moods.isNotEmpty()) {
            SectionTitle("情绪关键词")
            ChipFlow(p.moods, accent = PipoColors.Mint)
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (p.topArtists.isNotEmpty()) {
            SectionTitle("Top 艺人")
            p.topArtists.forEach { ArtistRow(it) }
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (p.culturalContext.isNotEmpty()) {
            SectionTitle("文化坐标")
            ChipFlow(p.culturalContext, accent = PipoColors.Blue)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = PipoColors.TextDim,
        style = TextStyle(fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun GenreRow(g: GenreTag) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(g.tag, color = PipoColors.Ink, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text("${(g.weight * 100).toInt()}%", color = PipoColors.TextDim, style = TextStyle(fontSize = 11.sp))
        }
        LinearProgressIndicator(
            progress = { g.weight.coerceIn(0f, 1f) },
            color = PipoColors.Mint,
            trackColor = Color(0x18FFFFFF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(4.dp)
                .clip(CircleShape),
        )
        if (g.examples.isNotEmpty()) {
            Text(
                g.examples.joinToString(" · "),
                color = PipoColors.TextDim,
                style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EraBars(eras: List<EraSlice>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        eras.forEach { e ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((40 * e.weight.coerceIn(0f, 1f) + 4).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PipoColors.Mint.copy(alpha = 0.30f + 0.6f * e.weight)),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    e.label,
                    color = PipoColors.TextDim,
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<String>, accent: Color) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { s ->
            Text(
                text = s,
                color = accent,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ArtistRow(a: ArtistAffinity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = a.name,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(Color(0x18FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(a.affinity.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(PipoColors.Mint),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(a.affinity * 100).toInt()}",
            color = PipoColors.TextDim,
            style = TextStyle(fontSize = 11.sp),
        )
    }
}

private fun dateOnly(epochSec: Long): String {
    if (epochSec <= 0) return "—"
    val date = Instant.ofEpochSecond(epochSec)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return "${date.monthValue}/${date.dayOfMonth}"
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x12FFFFFF))
                            .padding(16.dp),
                    ) {
                        Text(item.second, color = PipoColors.Ink, style = MaterialTheme.typography.headlineSmall)
                        Text(item.first, color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
