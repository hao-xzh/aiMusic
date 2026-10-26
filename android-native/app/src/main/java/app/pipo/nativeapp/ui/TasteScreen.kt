package app.pipo.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.ArtistAffinity
import app.pipo.nativeapp.data.GenreTag
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.TasteProfile
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId

private const val TasteArtwork = "file:///android_asset/artwork/login-music-classics.png"
private val GenreColors = listOf(PipoColors.Mint, PipoColors.TasteGold, PipoColors.TasteBlue)

@Composable
fun TasteScreen() {
    val profile by PipoGraph.tasteProfileStore.flow.collectAsState()
    LaunchedEffect(Unit) { PipoGraph.distillCoordinator.ensureMixed() }
    val onBack = LocalOnBack.current
    val genres = remember(profile) { profile?.genres.orEmpty().sortedByDescending { it.weight.tasteStrength() } }
    val artists = remember(profile) { profile?.topArtists.orEmpty().sortedByDescending { it.affinity.tasteStrength() } }
    Column(
        modifier = Modifier.fillMaxSize().background(PipoColors.TasteBackground)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TasteHero(profile, onBack)
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()
            .padding(horizontal = PipoDimens.TastePagePaddingDp).padding(bottom = 28.dp)) {
            if (genres.isNotEmpty()) {
                GenreProfile(genres)
                Spacer(Modifier.height(28.dp))
            }
            if (artists.isNotEmpty()) {
                SectionTitle("常听艺人")
                ArtistGrid(artists)
                Spacer(Modifier.height(28.dp))
            }
            UnifiedTasteSection(profile)
        }
    }
}

@Composable
private fun TasteHero(profile: TasteProfile?, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // 原始封面仅作局部背景层，文字按实际长度自然排版。
        AsyncImage(model = TasteArtwork, contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to PipoColors.TasteBackground.copy(alpha = 0.96f),
            0.52f to PipoColors.TasteBackground.copy(alpha = 0.48f),
            1f to Color.Transparent,
        )))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to PipoColors.TasteBackground.copy(alpha = 0.12f),
            0.45f to PipoColors.TasteBackground.copy(alpha = 0.18f),
            0.78f to PipoColors.TasteBackground.copy(alpha = 0.82f),
            1f to PipoColors.TasteBackground,
        )))
        Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = { onBack?.invoke() }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "返回", tint = PipoColors.Ink, modifier = Modifier.size(28.dp))
                }
                Text("音乐口味", color = PipoColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()
                .padding(horizontal = PipoDimens.TastePagePaddingDp)) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(18.dp).height(2.dp).background(PipoColors.Mint, CircleShape))
                    Text("你的音乐画像", color = PipoColors.TasteMuted, fontSize = 13.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                val summary = profile?.summary?.takeIf { it.isNotBlank() } ?: "还没有音乐画像"
                val compact = summary.length > 28
                Text(summary, color = PipoColors.Ink,
                    style = TextStyle(
                        fontSize = if (compact) PipoTypography.TasteSummaryCompact else PipoTypography.TasteSummary,
                        lineHeight = if (compact) 38.sp else 44.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.6).sp, lineBreak = LineBreak.Heading,
                    ),
                    modifier = Modifier.widthIn(max = if (compact) 520.dp else 260.dp).semantics { heading() })
                Text("从对话、收藏与聆听，持续了解你", color = PipoColors.TasteMuted, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 12.dp))
                if (profile != null) {
                    if (profile.moods.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ChipFlow(profile.moods)
                    }
                    Text("来自 ${profile.sourcePlaylistCount} 张歌单 · ${profile.sampledTrackCount} 首分析样本",
                        color = PipoColors.TasteMuted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp))
                } else {
                    Text("你明确表达的偏好和近期聆听记录仍可在下方查看。", color = PipoColors.TasteMuted,
                        fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(28.dp))
            }
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
    SectionTitle("你明确说过")
    if (explicit.isEmpty()) {
        Text("还没有记录长期偏好。你可以告诉 AI 喜欢或不喜欢什么；一次点歌不会被直接记作长期喜好。",
            color = PipoColors.TasteMuted, fontSize = 14.sp, lineHeight = 22.sp)
    }
    val actionLineHeight = with(LocalDensity.current) { 24.sp.toDp() }
    val actionHeight = (actionLineHeight + 24.dp).coerceAtLeast(48.dp)
    val actionWidth = with(LocalDensity.current) { 32.sp.toDp() }.coerceAtLeast(48.dp)
    val textTopInset = (actionHeight - actionLineHeight) / 2
    explicit.asReversed().forEachIndexed { index, fact ->
        if (index > 0) HorizontalDivider(color = PipoColors.TasteRule)
        // 无容器缩进：正文、来源、分隔线与标题共用页面左边界。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = textTopInset, bottom = 12.dp, end = 12.dp)) {
                Text("${if (fact.liked) "偏爱" else "尽量避开"} · ${fact.value}", color = PipoColors.Ink,
                    fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                Text("对话 · ${dateOnly(fact.updatedAt / 1000)} · “${fact.evidence}”", color = PipoColors.TasteMuted,
                    fontSize = 12.sp, lineHeight = 18.sp)
            }
            TextButton(
                onClick = { PipoGraph.userTaste.forget(fact.key) }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(actionWidth).height(actionHeight)
                    .semantics { contentDescription = "忘记偏好：${fact.value}" },
            ) {
                Text("忘记", color = PipoColors.TasteMuted, fontSize = 14.sp, lineHeight = 24.sp, maxLines = 1,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    SectionTitle("最近的聆听")
    val recent = snapshot?.behavior
    Text(recent?.brief() ?: if (loadError) "聆听记录暂时读取失败，下次进入时重试。"
        else if (snapshot == null) "正在整理聆听记录…"
        else "继续聆听，听完、重听和跳过会逐渐形成近期倾向。",
        color = PipoColors.TasteMuted, fontSize = 14.sp, lineHeight = 22.sp)
    if (recent?.hasSignal == true) {
        Text("依据 ${recent.observedCount} 条播放记录 · 近期信号会随时间减弱",
            color = PipoColors.TasteMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
    }
    snapshot?.takeIf { it.libraryCount > 0 }?.let { value ->
        Spacer(Modifier.height(24.dp))
        SectionTitle("收藏中的音乐人")
        Text("来自资料库 ${value.libraryCount} 首歌曲，作为探索起点", color = PipoColors.TasteMuted,
            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
        ChipFlow(value.libraryArtists.take(8))
    }
    Text("本次点歌要求始终优先。长期偏好帮助选歌，近期情绪随聆听变化。",
        color = PipoColors.TasteMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 24.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = PipoColors.Ink, fontSize = PipoTypography.TasteSection,
        fontWeight = FontWeight.SemiBold, lineHeight = 26.sp,
        modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
}

@Composable
private fun GenreProfile(genres: List<GenreTag>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("流派倾向", color = PipoColors.Ink, fontSize = PipoTypography.TasteSection,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        Text("倾向强度", color = PipoColors.TasteMuted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(12.dp))
    val leading = genres.take(3)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GenreRecord(leading, PipoDimens.TasteGenreChartSizeDp, Modifier.align(Alignment.CenterHorizontally))
                GenreLegend(leading, Modifier.fillMaxWidth())
            }
        } else {
            val chartDiameter = if (maxWidth < 360.dp) 144.dp else PipoDimens.TasteGenreChartSizeDp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                GenreRecord(leading, chartDiameter)
                GenreLegend(leading, Modifier.weight(1f))
            }
        }
    }
    // 圆弧强调前三项，其余流派继续展示。每项是独立权重，不是占比饼图。
    if (genres.size > 3) {
        Spacer(Modifier.height(12.dp))
        genres.drop(3).forEach { genre ->
            HorizontalDivider(color = PipoColors.TasteRule)
            GenreLabel(genre, PipoColors.Mint, prominent = false)
        }
    }
}

@Composable
private fun GenreRecord(genres: List<GenreTag>, diameter: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(diameter).clearAndSetSemantics { }) {
        val stroke = 6.dp.toPx()
        val outer = (size.minDimension - stroke) / 2f
        repeat(18) { index ->
            drawCircle(PipoColors.Mint.copy(alpha = 0.09f), radius = outer * (index + 1) / 19f,
                style = Stroke(0.5.dp.toPx()))
        }
        genres.forEachIndexed { index, genre ->
            val radius = outer - index * 18.dp.toPx()
            drawCircle(PipoColors.TasteRule, radius = radius, style = Stroke(stroke))
            val strength = genre.weight.tasteStrength()
            if (strength == 1f) {
                drawCircle(GenreColors[index], radius = radius, style = Stroke(stroke))
            } else if (strength > 0f) {
                drawArc(color = GenreColors[index], startAngle = -90f, sweepAngle = 360f * strength,
                    useCenter = false, topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        drawCircle(PipoColors.Ink, radius = 3.dp.toPx())
    }
}

@Composable
private fun GenreLegend(genres: List<GenreTag>, modifier: Modifier = Modifier) {
    Column(modifier) {
        genres.forEachIndexed { index, genre ->
            if (index > 0) HorizontalDivider(color = PipoColors.TasteRule)
            GenreLabel(genre, GenreColors[index], prominent = true)
        }
    }
}

@Composable
private fun GenreLabel(genre: GenreTag, accent: Color, prominent: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(if (prominent) 10.dp else 6.dp).background(accent, CircleShape))
            Text(genre.tag, color = PipoColors.Ink, fontSize = 15.sp, lineHeight = 21.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${(genre.weight.tasteStrength() * 100).toInt()}%", color = PipoColors.Ink,
                fontSize = if (prominent) 24.sp else 17.sp, fontWeight = FontWeight.Medium)
        }
        if (genre.examples.isNotEmpty()) {
            Text(genre.examples.joinToString(" · "), color = PipoColors.TasteMuted, fontSize = 11.sp,
                lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Text(item, color = PipoColors.TasteMuted, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.border(1.dp, PipoColors.TasteMuted.copy(alpha = 0.65f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun ArtistGrid(artists: List<ArtistAffinity>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 240.dp || fontScale > 1.6f -> 1
            maxWidth < 320.dp || fontScale > 1.2f -> 2
            else -> 3
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            artists.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { columnIndex, artist ->
                        if (columnIndex > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(PipoColors.TasteRule))
                        }
                        Column(
                            Modifier.weight(1f).padding(start = if (columnIndex == 0) 0.dp else 16.dp,
                                end = if (columnIndex == columns - 1) 0.dp else 12.dp),
                        ) {
                            Text((rowIndex * columns + columnIndex + 1).toString().padStart(2, '0'),
                                color = PipoColors.TasteMuted, fontSize = 11.sp, lineHeight = 17.sp)
                            Text(artist.name, color = PipoColors.Ink, fontSize = 16.sp, lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            Text("亲和度 ${(artist.affinity.tasteStrength() * 100).toInt()}", color = PipoColors.TasteMuted,
                                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun Float.tasteStrength(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

private fun dateOnly(epochSec: Long): String {
    if (epochSec <= 0) return "—"
    val date = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.monthValue}/${date.dayOfMonth}"
}
