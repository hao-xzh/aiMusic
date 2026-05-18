package app.pipo.nativeapp.ui

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.Discovery
import app.pipo.nativeapp.data.ArtistAffinity
import app.pipo.nativeapp.data.BehaviorEvent
import app.pipo.nativeapp.data.BehaviorType
import app.pipo.nativeapp.data.EraSlice
import app.pipo.nativeapp.data.GenreTag
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.TasteProfile
import java.time.Instant
import java.time.ZoneId

@Composable
fun TasteScreen(
    onPlayTracks: (List<NativeTrack>) -> Unit = {},
) {
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

        AiUtilitySections(profile = p, onPlayTracks = onPlayTracks)
        Spacer(modifier = Modifier.height(24.dp))

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

private sealed interface LibraryOrganizerState {
    data object Loading : LibraryOrganizerState
    data object Empty : LibraryOrganizerState
    data class Ready(
        val duplicateGroups: Int,
        val staleCount: Int,
        val topArtistShare: Int,
        val queues: List<LibraryQueue>,
    ) : LibraryOrganizerState
}

private data class LibraryQueue(
    val key: String,
    val label: String,
    val tracks: List<NativeTrack>,
)

private sealed interface TasteRadarState {
    data object Loading : TasteRadarState
    data object Empty : TasteRadarState
    data class Ready(val tracks: List<NativeTrack>) : TasteRadarState
}

@Composable
private fun AiUtilitySections(
    profile: TasteProfile,
    onPlayTracks: (List<NativeTrack>) -> Unit,
) {
    val context = LocalContext.current
    var libraryState by remember(profile.sourceHash) {
        mutableStateOf<LibraryOrganizerState>(LibraryOrganizerState.Loading)
    }
    var radarState by remember(profile.sourceHash) {
        mutableStateOf<TasteRadarState>(TasteRadarState.Loading)
    }
    var feedback by remember(profile.sourceHash) {
        mutableStateOf(readRadarFeedback(context, profile.sourceHash))
    }

    LaunchedEffect(profile.sourceHash) {
        DiagnosticsLogStore.record(
            area = "taste_ai",
            event = "utilities_start",
            fields = mapOf(
                "profileHash" to profile.sourceHash.take(12),
                "genreCount" to profile.genres.size,
                "moodCount" to profile.moods.size,
            ),
        )
        val libraryResult = runCatching { PipoGraph.library.library() }
        libraryResult.onFailure { error ->
            DiagnosticsLogStore.record(
                area = "taste_ai",
                event = "library_load_failed",
                fields = mapOf(
                    "errorType" to error::class.java.simpleName,
                    "errorMessage" to error.message.orEmpty().take(180),
                ),
            )
        }
        val library = libraryResult.getOrDefault(emptyList())
        libraryState = buildLibraryOrganizerState(library)
        (libraryState as? LibraryOrganizerState.Ready)?.let { ready ->
            DiagnosticsLogStore.record(
                area = "taste_ai",
                event = "library_organizer_ready",
                fields = mapOf(
                    "libraryCount" to library.size,
                    "duplicateGroups" to ready.duplicateGroups,
                    "staleCount" to ready.staleCount,
                    "queueCount" to ready.queues.size,
                ),
            )
        }
        val excludeIds = library.mapNotNull { it.neteaseId }.toSet()
        val tags = (profile.genres.map { it.tag } + profile.moods + profile.culturalContext).distinct()
        val radarResult = runCatching {
            Discovery(PipoGraph.repository).fetchMore(
                around = null,
                tags = tags,
                excludeIds = excludeIds,
                wantCount = 9,
            )
        }
        radarResult.onFailure { error ->
            DiagnosticsLogStore.record(
                area = "taste_ai",
                event = "radar_discovery_failed",
                fields = mapOf(
                    "tagCount" to tags.size,
                    "excludeCount" to excludeIds.size,
                    "errorType" to error::class.java.simpleName,
                    "errorMessage" to error.message.orEmpty().take(180),
                ),
            )
        }
        val outsideRadar = radarResult.getOrDefault(emptyList())
        val behavior = runCatching { PipoGraph.behaviorLog.readAll() }.getOrDefault(emptyList())
        val revival = buildRevivalTracks(library, behavior, 3)
        val radar = mergeRadarTracks(outsideRadar, revival, 12)
        DiagnosticsLogStore.record(
            area = "taste_ai",
            event = "radar_ready",
            fields = mapOf(
                "outsideCount" to outsideRadar.size,
                "revivalCount" to revival.size,
                "finalCount" to radar.size,
                "feedbackCount" to feedback.size,
            ),
        )
        radarState = if (radar.isEmpty()) TasteRadarState.Empty else TasteRadarState.Ready(radar)
    }

    SectionTitle("音乐图书管理员")
    when (val state = libraryState) {
        LibraryOrganizerState.Loading -> SmallMutedLine("正在整理…")
        LibraryOrganizerState.Empty -> SmallMutedLine("还没有可整理的歌库。")
        is LibraryOrganizerState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactMetricRow("重复版本", "${state.duplicateGroups} 组")
            CompactMetricRow("旧歌复活", "${state.staleCount} 首")
            CompactMetricRow("集中度", "${state.topArtistShare}%")
            if (state.queues.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    state.queues.take(4).forEach { queue ->
                        MiniActionChip(label = queue.label, onClick = {
                            DiagnosticsLogStore.record(
                                area = "taste_ai",
                                event = "library_queue_play",
                                fields = mapOf(
                                    "queue" to queue.key,
                                    "trackCount" to queue.tracks.size,
                                ),
                            )
                            onPlayTracks(queue.tracks)
                        })
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    SectionTitle("Taste Radar")
    when (val state = radarState) {
        TasteRadarState.Loading -> SmallMutedLine("正在找新歌…")
        TasteRadarState.Empty -> SmallMutedLine("这周先听库里更稳。")
        is TasteRadarState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MiniActionChip(
                    label = "全部",
                    onClick = {
                        val queue = state.tracks.filter { feedback[it.id] != false }
                        DiagnosticsLogStore.record(
                            area = "taste_ai",
                            event = "radar_play_all",
                            fields = mapOf(
                                "trackCount" to queue.size,
                                "hiddenByFeedback" to (state.tracks.size - queue.size),
                            ),
                        )
                        if (queue.isNotEmpty()) onPlayTracks(queue)
                    },
                )
            }
            state.tracks.take(8).forEach { track ->
                RadarTrackRow(
                    track = track,
                    vote = feedback[track.id],
                    onPlay = {
                        DiagnosticsLogStore.record(
                            area = "taste_ai",
                            event = "radar_track_play",
                            fields = mapOf(
                                "trackId" to track.neteaseId,
                                "title" to track.title,
                            ),
                        )
                        onPlayTracks(listOf(track))
                    },
                    onVote = { approved ->
                        val next = feedback + (track.id to approved)
                        feedback = next
                        writeRadarFeedback(context, profile.sourceHash, next)
                        DiagnosticsLogStore.record(
                            area = "taste_ai",
                            event = "radar_vote",
                            fields = mapOf(
                                "trackId" to track.neteaseId,
                                "approved" to approved,
                                "feedbackCount" to next.size,
                            ),
                        )
                    },
                )
            }
        }
    }
}

private suspend fun buildLibraryOrganizerState(library: List<NativeTrack>): LibraryOrganizerState {
    if (library.isEmpty()) return LibraryOrganizerState.Empty
    val bySong = library.groupingBy {
        "${normalizeSong(it.title)}::${normalizeSong(primaryArtist(it.artist))}"
    }.eachCount()
    val byArtist = library.groupingBy { primaryArtist(it.artist).ifBlank { "Unknown" } }.eachCount()
    val duplicateGroups = bySong.values.count { it > 1 }
    val topArtistShare = ((byArtist.values.maxOrNull() ?: 0).toFloat() / library.size * 100f).toInt()
    val events = runCatching { PipoGraph.behaviorLog.readAll() }.getOrDefault(emptyList())
    val touchedIds = events
        .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed }
        .flatMap { ev -> listOfNotNull(ev.trackId, ev.neteaseId?.toString()) }
        .toSet()
    val staleCount = library.count { track ->
        track.id !in touchedIds && track.neteaseId?.toString() !in touchedIds
    }
    return LibraryOrganizerState.Ready(
        duplicateGroups = duplicateGroups,
        staleCount = staleCount,
        topArtistShare = topArtistShare,
        queues = buildLibraryQueues(library, events),
    )
}

@Composable
private fun CompactMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PipoColors.TextMuted, style = TextStyle(fontSize = 13.sp))
        Text(value, color = PipoColors.Ink, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun MiniActionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = PipoColors.Mint,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(CircleShape)
            .background(PipoColors.Mint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun RadarTrackRow(
    track: NativeTrack,
    vote: Boolean?,
    onPlay: () -> Unit,
    onVote: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (vote == false) 0.52f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onPlay)) {
            Text(
                text = track.title,
                color = PipoColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = track.artist,
                color = PipoColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 11.sp),
            )
        }
        MiniVoteChip(label = "准", selected = vote == true, onClick = { onVote(true) })
        Spacer(modifier = Modifier.width(4.dp))
        MiniVoteChip(label = "不准", selected = vote == false, onClick = { onVote(false) })
    }
}

@Composable
private fun MiniVoteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) PipoColors.Mint else PipoColors.TextMuted,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) PipoColors.Mint.copy(alpha = 0.14f) else Color(0x10FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun SmallMutedLine(text: String) {
    Text(
        text = text,
        color = PipoColors.TextMuted,
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

private fun buildLibraryQueues(
    library: List<NativeTrack>,
    events: List<BehaviorEvent>,
): List<LibraryQueue> {
    val touchedIds = events
        .filter { it.type == BehaviorType.PlayStarted || it.type == BehaviorType.Completed }
        .flatMap { ev -> listOfNotNull(ev.trackId, ev.neteaseId?.toString()) }
        .toSet()
    val stale = library.filter { track ->
        track.id !in touchedIds && track.neteaseId?.toString() !in touchedIds
    }
    return listOf(
        LibraryQueue("revive", "旧歌", diverseByArtist(stale.ifEmpty { library }).take(18)),
        LibraryQueue("work", "工作", scoreQueue(library, listOf("ambient", "chill", "lofi", "indie", "民谣", "器乐", "轻")).take(18)),
        LibraryQueue("drive", "开车", scoreQueue(library, listOf("rock", "pop", "city", "funk", "律动", "公路")).take(18)),
        LibraryQueue("night", "睡前", scoreQueue(library, listOf("night", "moon", "rain", "dream", "blue", "acoustic", "piano", "夜", "月", "雨", "梦")).take(18)),
    ).filter { it.tracks.size >= 4 }
}

private fun scoreQueue(library: List<NativeTrack>, words: List<String>): List<NativeTrack> {
    return library
        .map { track ->
            val haystack = "${track.title} ${track.artist} ${track.album}".lowercase()
            val score = words.count { word -> word.isNotBlank() && haystack.contains(word.lowercase()) }
            track to score
        }
        .sortedWith(compareByDescending<Pair<NativeTrack, Int>> { it.second }.thenBy { stableRank(it.first) })
        .map { it.first }
}

private fun diverseByArtist(tracks: List<NativeTrack>): List<NativeTrack> {
    val seen = HashMap<String, Int>()
    val sorted = tracks.sortedBy { stableRank(it) }
    return sorted.filter { track ->
        val artist = primaryArtist(track.artist)
        val count = seen[artist] ?: 0
        seen[artist] = count + 1
        count < 2
    }
}

private fun buildRevivalTracks(
    library: List<NativeTrack>,
    events: List<BehaviorEvent>,
    count: Int,
): List<NativeTrack> {
    val touched = events.flatMap { ev -> listOfNotNull(ev.trackId, ev.neteaseId?.toString()) }.toSet()
    return library
        .filter { track -> track.id !in touched && track.neteaseId?.toString() !in touched }
        .sortedBy { stableRank(it) }
        .take(count)
}

private fun mergeRadarTracks(
    outside: List<NativeTrack>,
    revivals: List<NativeTrack>,
    limit: Int,
): List<NativeTrack> {
    val seen = HashSet<String>()
    val out = ArrayList<NativeTrack>()
    for (track in outside.take(6) + revivals + outside.drop(6)) {
        val key = track.neteaseId?.toString() ?: track.id
        if (!seen.add(key)) continue
        out.add(track)
        if (out.size >= limit) break
    }
    return out
}

private fun readRadarFeedback(context: Context, profileHash: String): Map<String, Boolean> {
    val raw = context.getSharedPreferences("pipo-taste-radar", Context.MODE_PRIVATE)
        .getString(profileHash, null)
        .orEmpty()
    if (raw.isBlank()) return emptyMap()
    return raw.split(',')
        .mapNotNull { part ->
            val pair = part.split(':')
            if (pair.size != 2) null else pair[0] to (pair[1] == "1")
        }
        .toMap()
}

private fun writeRadarFeedback(
    context: Context,
    profileHash: String,
    feedback: Map<String, Boolean>,
) {
    val raw = feedback.entries.joinToString(",") { (key, approved) -> "$key:${if (approved) "1" else "0"}" }
    context.getSharedPreferences("pipo-taste-radar", Context.MODE_PRIVATE)
        .edit()
        .putString(profileHash, raw)
        .apply()
}

private fun primaryArtist(value: String): String =
    value.split('/', '&', ',', '、').firstOrNull()?.trim().orEmpty()

private fun normalizeSong(value: String): String =
    value.lowercase().replace(Regex("\\s+|[()（）\\[\\]【】\"'’‘“”\\-—_,，。.、]"), "")

private fun stableRank(track: NativeTrack): Long {
    val id = track.neteaseId ?: track.id.hashCode().toLong()
    return (id * 1103515245L + 12345L) and 0x7fffffff
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
