package app.pipo.nativeapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TasteScreen(repository: PipoRepository = PipoGraph.repository) {
    val profile by repository.tasteProfile.collectAsState(
        initial = app.pipo.nativeapp.data.TasteProfile(0f, 0f, 0f, 0f, emptyList()),
    )
    ScreenScaffold(title = "TASTE", headline = "Music taste") {
        TasteOrb(profile.energy, profile.warmth, profile.novelty)
        Spacer(modifier = Modifier.height(22.dp))
        MetricRow("Energy", profile.energy)
        MetricRow("Warmth", profile.warmth)
        MetricRow("Novelty", profile.novelty)
        MetricRow("Flow", profile.flow)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Signals", color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
        profile.tags.forEach { tag ->
            LabelRow(tag, "Active taste vector")
        }
    }
}

@Composable
fun DistillScreen(repository: PipoRepository = PipoGraph.repository) {
    val state by repository.distillState.collectAsState(
        initial = app.pipo.nativeapp.data.DistillState(0, 0, 0f, 0),
    )
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        repository.refreshPlaylists()
    }
    ScreenScaffold(title = "DISTILL", headline = "Playlist distill") {
        StatGrid(
            items = listOf(
                "Sources" to state.sourceCount.toString(),
                "Candidates" to state.candidateCount.toString(),
                "AI judged" to state.aiJudgedCount.toString(),
                "Smoothness" to "${(state.smoothness * 100).toInt()}%",
            ),
        )
        Spacer(modifier = Modifier.height(26.dp))
        Text("Sources", color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
        playlists.forEach { playlist ->
            LabelRow(playlist.name, "${playlist.trackCount} tracks")
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text("Pipeline", color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
        LabelRow("Candidate recall", "Finds playable tracks beyond the source list")
        LabelRow("Transition scoring", "Ranks energy, tempo, intro, and outro fit")
        LabelRow("Queue smoothing", "Keeps the station from feeling shuffled")
        LabelRow("AI narration", "Prepares short DJ-style notes")
    }
}

@Composable
fun SettingsScreen(repository: PipoRepository = PipoGraph.repository) {
    val account by repository.account.collectAsState(initial = null)
    val cacheStats by repository.audioCacheStats.collectAsState(
        initial = app.pipo.nativeapp.data.AudioCacheStats(0, 0, 0),
    )
    val aiConfig by repository.aiConfig.collectAsState(
        initial = app.pipo.nativeapp.data.AiConfigView(activeProvider = "", providers = emptyList()),
    )
    val settings by repository.settings.collectAsState(
        initial = NativeSettings(),
    )
    val scope = rememberCoroutineScope()
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var apiKeyDraft by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repository.refreshAccount()
        repository.refreshAudioCacheStats()
        repository.refreshAiConfig()
    }
    ScreenScaffold(title = "SETTINGS", headline = "Local settings") {
        SettingsGroup("Music source", Icons.Rounded.Cloud) {
            LabelRow("Netease login", account?.nickname ?: "QR and phone login will attach here")
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it },
                label = { Text("Captcha") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            val start = repository.startQrLogin()
                            qrContent = start.qrContent
                            loginStatus = "QR login waiting"
                            repeat(30) {
                                val status = repository.checkQrLogin(start.key)
                                loginStatus = status.nickname ?: status.message ?: "QR login waiting"
                                if (status.code == 803) {
                                    qrContent = null
                                    repository.refreshAccount()
                                    return@launch
                                }
                                if (status.code == 800 || status.code < 0) {
                                    return@launch
                                }
                                delay(2_000)
                            }
                        }
                    },
                ) {
                    Text("QR", color = PipoColors.Mint)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val sent = repository.sendPhoneCaptcha(phone = phone)
                            loginStatus = sent.message ?: "Captcha sent"
                        }
                    },
                ) {
                    Text("SMS", color = PipoColors.Blue)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val status = repository.loginWithPhone(phone = phone, captcha = captcha)
                            loginStatus = status.nickname ?: status.message ?: "Phone login submitted"
                            repository.refreshAccount()
                        }
                    },
                ) {
                    Text("Login", color = PipoColors.Gold)
                }
            }
            qrContent?.let { content ->
                QrCode(
                    content = content,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            loginStatus?.let { LabelRow("Login status", it) }
            LabelRow("Audio cache", "${cacheStats.totalMb} MB / ${cacheStats.maxMb} MB, ${cacheStats.count} tracks")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        scope.launch { repository.clearAudioCache() }
                    },
                ) {
                    Text("Clear", color = PipoColors.Blue)
                }
                TextButton(
                    onClick = {
                        scope.launch { repository.setCacheMaxMb(4096) }
                    },
                ) {
                    Text("4 GB", color = PipoColors.Gold)
                }
            }
        }
        SettingsGroup("AI access", Icons.Rounded.Key) {
            val activeProvider = aiConfig.providers.firstOrNull { it.id == aiConfig.activeProvider }
            LabelRow(
                "Provider",
                activeProvider?.let { "${it.label} - ${it.model}" } ?: "DeepSeek, OpenAI, and MiMo model slots",
            )
            ToggleRow("AI narration", settings.aiNarration) {
                scope.launch { repository.updateSettings(settings.copy(aiNarration = it)) }
            }
            aiConfig.providers.forEach { provider ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        LabelRow(
                            provider.label,
                            if (provider.hasKey) "Key ${provider.keyPreview ?: "saved"}" else "No API key",
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch { repository.setAiProvider(provider.id) }
                        },
                    ) {
                        Text(if (provider.id == aiConfig.activeProvider) "Active" else "Use", color = PipoColors.Mint)
                    }
                }
            }
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.aiSetApiKey(aiConfig.activeProvider, apiKeyDraft)
                        }
                    },
                ) {
                    Text("Save key", color = PipoColors.Blue)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            aiReply = repository.aiPing()
                        }
                    },
                ) {
                    Text("Ping", color = PipoColors.Gold)
                }
            }
            aiReply?.let { LabelRow("AI reply", it) }
        }
        SettingsGroup("Playback rules", Icons.Rounded.Tune) {
            ToggleRow("Workday autoplay", settings.workdayAutoplay) {
                scope.launch { repository.updateSettings(settings.copy(workdayAutoplay = it)) }
            }
            ToggleRow("Pause during meetings", settings.pauseDuringMeetings) {
                scope.launch { repository.updateSettings(settings.copy(pauseDuringMeetings = it)) }
            }
            ToggleRow("Lunch relax mode", settings.lunchRelaxMode) {
                scope.launch { repository.updateSettings(settings.copy(lunchRelaxMode = it)) }
            }
        }
        SettingsGroup("Appearance", Icons.Rounded.Palette) {
            ToggleRow("Hide dot pattern", settings.hideDotPattern) {
                scope.launch { repository.updateSettings(settings.copy(hideDotPattern = it)) }
            }
        }
        SettingsGroup("About you", Icons.Rounded.Check) {
            OutlinedTextField(
                value = settings.userFacts,
                onValueChange = { value ->
                    scope.launch { repository.updateSettings(settings.copy(userFacts = value.take(400))) }
                },
                label = { Text("Work hours, habits, preferences") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            LabelRow("Memory", "${settings.userFacts.length} / 400")
        }
    }
}

@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { buildQrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR login",
        modifier = modifier.background(Color.White),
        contentScale = ContentScale.FillBounds,
    )
}

private fun buildQrBitmap(content: String, size: Int = 512): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    headline: String,
    content: @Composable Column.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PipoColors.Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 34.dp, bottom = 118.dp),
    ) {
        Text(
            text = title,
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = headline,
            color = PipoColors.Text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        content()
    }
}

@Composable
private fun TasteOrb(energy: Float, warmth: Float, novelty: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(196.dp)) {
            val radius = size.minDimension / 2f
            drawCircle(PipoColors.Blue.copy(alpha = 0.18f), radius)
            drawArc(
                color = PipoColors.Mint,
                startAngle = -90f,
                sweepAngle = 360f * energy,
                useCenter = false,
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = PipoColors.Gold,
                startAngle = 30f,
                sweepAngle = 360f * warmth,
                useCenter = false,
                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(
                color = PipoColors.Text.copy(alpha = 0.9f),
                radius = 5.dp.toPx(),
                center = Offset(center.x + (novelty - 0.5f) * radius, center.y - energy * radius * 0.5f),
            )
        }
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = PipoColors.Text, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun MetricRow(label: String, value: Float) {
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = PipoColors.Text, style = MaterialTheme.typography.titleSmall)
            Text("${(value * 100).toInt()}%", color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            color = PipoColors.Mint,
            trackColor = Color(0x18FFFFFF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(5.dp)
                .clip(CircleShape),
        )
    }
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
                        Text(item.second, color = PipoColors.Text, style = MaterialTheme.typography.headlineSmall)
                        Text(item.first, color = PipoColors.TextDim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    label: String,
    icon: ImageVector,
    content: @Composable Column.() -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = PipoColors.TextDim, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Column(content = content)
}

@Composable
private fun LabelRow(label: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(PipoColors.Mint.copy(alpha = 0.17f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = PipoColors.Mint, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(label, color = PipoColors.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                detail,
                color = PipoColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = PipoColors.Text, style = MaterialTheme.typography.titleSmall)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PipoColors.Background,
                checkedTrackColor = PipoColors.Mint,
                uncheckedThumbColor = PipoColors.TextDim,
                uncheckedTrackColor = Color(0x22FFFFFF),
            ),
        )
    }
}
