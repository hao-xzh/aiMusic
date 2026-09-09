package app.pipo.nativeapp.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.ArtistAffinity
import app.pipo.nativeapp.data.GenreTag
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.TasteProfile
import java.time.Instant
import java.time.ZoneId

@Composable
fun TasteScreen() {
    val profile by PipoGraph.tasteProfileStore.flow.collectAsState()
    BrowsePageScaffold(title = "音乐口味", coverUrl = "file:///android_asset/artwork/login-music-classics.png", artistic = true) {
        Text("从对话、收藏与聆听，持续了解你", color = BrowseMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 24.dp))
        UnifiedTasteSection(profile)
        val p = profile
        if (p == null) {
            EmptyState(
                title = "还没有音乐画像",
                subtitle = "你明确表达的偏好和近期聆听记录仍可在上方查看。",
            )
            return@BrowsePageScaffold
        }

        Text("你的音乐画像", color = BrowseMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        Text(
            text = p.summary,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text("来自 ${p.sourcePlaylistCount} 张歌单 · ${p.sampledTrackCount} 首分析样本", color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))

        SectionTitle("流派倾向")
        p.genres.forEach { GenreRow(it) }
        Spacer(modifier = Modifier.height(20.dp))

        if (p.moods.isNotEmpty()) {
            SectionTitle("情绪关键词")
            ChipFlow(p.moods, accent = PipoColors.Mint)
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (p.topArtists.isNotEmpty()) {
            SectionTitle("常听艺人")
            p.topArtists.forEach { ArtistRow(it) }
            Spacer(modifier = Modifier.height(20.dp))
        }

    }
}

@Composable
private fun UnifiedTasteSection(profile: TasteProfile?) {
    val explicit by PipoGraph.userTaste.explicit.collectAsState()
    var snapshot by remember { mutableStateOf<app.pipo.nativeapp.data.UserTasteSnapshot?>(null) }
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(profile?.sourceHash, explicit) {
        try {
            snapshot = PipoGraph.userTaste.snapshot()
            loadError = false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    SectionTitle("你的口味库")
    Text("本次点歌要求始终优先。长期偏好帮助选歌，近期情绪随聆听变化。", color = BrowseMuted,
        fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))
    Text("你明确说过", color = BrowseInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    if (explicit.isEmpty()) {
        Text("还没有记录长期偏好。你可以告诉 AI 喜欢或不喜欢什么；一次点歌不会被直接记作长期喜好。",
            color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
    }
    explicit.asReversed().forEach { fact ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${if (fact.liked) "偏爱" else "尽量避开"} · ${fact.value}", color = BrowseInk, fontSize = 15.sp)
                Text("对话 · ${dateOnly(fact.updatedAt / 1000)} · “${fact.evidence}”", color = BrowseMuted, fontSize = 12.sp)
            }
            androidx.compose.material3.TextButton(onClick = { PipoGraph.userTaste.forget(fact.key) }) {
                Text("忘记", color = BrowseMuted, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("最近的聆听", color = BrowseInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val recent = snapshot?.behavior
    Text(recent?.brief() ?: if (loadError) "聆听记录暂时读取失败，下次进入时重试。" else if (snapshot == null) "正在整理聆听记录…" else "继续聆听，听完、重听和跳过会逐渐形成近期倾向。",
        color = BrowseMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
    if (recent?.hasSignal == true) Text("依据 ${recent.observedCount} 条播放记录 · 近期信号会随时间减弱", color = BrowseMuted, fontSize = 12.sp)
    snapshot?.takeIf { it.libraryCount > 0 }?.let { value ->
        Spacer(Modifier.height(16.dp))
        Text("收藏中的音乐人", color = BrowseInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("来自资料库 ${value.libraryCount} 首歌曲，作为探索起点", color = BrowseMuted, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp))
        ChipFlow(value.libraryArtists.take(8), accent = PipoColors.Mint)
    }
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = PipoColors.TextDim,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
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
