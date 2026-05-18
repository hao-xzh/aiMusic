package app.pipo.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioCacheStats
import app.pipo.nativeapp.data.AiConfigView
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(repository: PipoRepository = PipoGraph.repository) {
    val context = LocalContext.current
    val account by repository.account.collectAsState(initial = null)
    val cacheStats by repository.audioCacheStats.collectAsState(initial = AudioCacheStats(0, 0, 0))
    val aiConfig by repository.aiConfig.collectAsState(initial = AiConfigView(activeProvider = "", providers = emptyList()))
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val scope = rememberCoroutineScope()
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var qrJob by remember { mutableStateOf<Job?>(null) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { qrJob?.cancel() }
    }
    LaunchedEffect(Unit) {
        repository.refreshAccount()
        repository.refreshAudioCacheStats()
        repository.refreshAiConfig()
    }

    ScreenScaffold(title = "SETTINGS") {
        Text(
            "账号、播放和 AI 都在本机。",
            color = PipoColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        SettingsGroup("音乐来源") {
            LabelRow("网易云登录", account?.nickname ?: "未登录 —— 点下面用网易云 App 扫码")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    qrJob?.cancel()
                    qrJob = scope.launch {
                        qrContent = null
                        loginStatus = "正在请求二维码…"
                        val startResult = runCatching { repository.startQrLogin() }
                        val start = startResult.getOrNull()
                        if (start == null || start.qrContent.isBlank()) {
                            startResult.exceptionOrNull()?.let { err ->
                                Log.w("PipoSettings", "start QR login failed", err)
                                DiagnosticsLogStore.record(
                                    area = "login",
                                    event = "qr_start_failed",
                                    fields = mapOf(
                                        "errorType" to err::class.java.simpleName,
                                        "errorMessage" to err.message.orEmpty().take(180),
                                    ),
                                )
                            }
                            loginStatus = "二维码加载失败，检查网络后刷新"
                            return@launch
                        }
                        qrContent = start.qrContent
                        loginStatus = "等待扫码"
                        repeat(30) {
                            val statusResult = runCatching { repository.checkQrLogin(start.key) }
                            val status = statusResult.getOrElse { err ->
                                Log.w("PipoSettings", "check QR login failed", err)
                                DiagnosticsLogStore.record(
                                    area = "login",
                                    event = "qr_check_failed",
                                    fields = mapOf(
                                        "errorType" to err::class.java.simpleName,
                                        "errorMessage" to err.message.orEmpty().take(180),
                                    ),
                                )
                                loginStatus = "登录状态获取失败，稍等后刷新"
                                qrContent = null
                                return@launch
                            }
                            loginStatus = when (status.code) {
                                801 -> "等待扫码"
                                802 -> "等待手机端确认"
                                803 -> status.nickname?.let { "已登录 · $it" } ?: "登录成功"
                                800 -> "二维码已过期 —— 点刷新重新生成"
                                else -> status.message ?: "等待中"
                            }
                            if (status.code == 803) {
                                DiagnosticsLogStore.record(
                                    area = "login",
                                    event = "qr_login_success",
                                    fields = mapOf("hasNickname" to !status.nickname.isNullOrBlank()),
                                )
                                qrContent = null
                                repository.refreshAccount()
                                return@launch
                            }
                            if (status.code == 800 || status.code < 0) {
                                qrContent = null
                                return@launch
                            }
                            delay(2_000)
                        }
                        if (qrContent != null) {
                            loginStatus = "二维码超时 —— 点刷新重新生成"
                            qrContent = null
                        }
                    }
                }) {
                    Text(if (qrContent == null) "扫码登录" else "刷新二维码", color = PipoColors.Mint)
                }
                if (account != null) {
                    TextButton(onClick = {
                        scope.launch {
                            qrContent = null
                            repository.logout()
                            runCatching { PipoGraph.lastPlayback.clear() }
                            runCatching { PipoGraph.library.invalidate() }
                            DiagnosticsLogStore.record("login", "logout")
                            loginStatus = "已退出"
                        }
                    }) { Text("退出", color = PipoColors.TextDim) }
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
            loginStatus?.let { LabelRow("登录状态", it) }
        }

        SettingsGroup("音频缓存") {
            LabelRow("歌曲原始字节缓存", "${cacheStats.totalMb} MB / ${cacheStats.maxMb} MB · ${cacheStats.count} 首")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { scope.launch { repository.clearAudioCache() } }) {
                    Text("清空缓存", color = PipoColors.Blue)
                }
                TextButton(onClick = { scope.launch { repository.setCacheMaxMb(4096) } }) {
                    Text("上限 4 GB", color = PipoColors.Gold)
                }
            }
        }

        SettingsGroup("AI key") {
            val activeProvider = aiConfig.providers.firstOrNull { it.id == aiConfig.activeProvider }
            val anyHasKey = aiConfig.providers.any { it.hasKey }
            LabelRow("服务商", activeProvider?.let { "${it.label} · ${it.model}" } ?: "DeepSeek / OpenAI / MiMo")
            if (!anyHasKey) {
                LabelRow("未配置", "填入任一 key 后启用 AI")
            }
            ToggleRow("封面短提示", settings.aiNarration) {
                logSettingToggle("aiNarration", it)
                scope.launch { repository.updateSettings(settings.copy(aiNarration = it)) }
            }
            aiConfig.providers.forEach { provider ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        LabelRow(provider.label, if (provider.hasKey) "key ${provider.keyPreview ?: "已存"}" else "未填 key")
                    }
                    TextButton(onClick = { scope.launch { repository.setAiProvider(provider.id) } }) {
                        Text(if (provider.id == aiConfig.activeProvider) "当前" else "切换", color = PipoColors.Mint)
                    }
                }
            }
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = { apiKeyDraft = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        DiagnosticsLogStore.record(
                            area = "settings",
                            event = "ai_key_save",
                            fields = mapOf(
                                "provider" to aiConfig.activeProvider,
                                "keyLen" to apiKeyDraft.length,
                            ),
                        )
                        repository.aiSetApiKey(aiConfig.activeProvider, apiKeyDraft)
                    }
                }) { Text("保存 key", color = PipoColors.Blue) }
                TextButton(onClick = {
                    scope.launch {
                        val result = runCatching { repository.aiPing() }
                        result.onSuccess {
                            DiagnosticsLogStore.record(
                                area = "settings",
                                event = "ai_ping_success",
                                fields = mapOf(
                                    "provider" to aiConfig.activeProvider,
                                    "replyLen" to it.length,
                                ),
                            )
                        }.onFailure {
                            DiagnosticsLogStore.record(
                                area = "settings",
                                event = "ai_ping_failed",
                                fields = mapOf(
                                    "provider" to aiConfig.activeProvider,
                                    "errorType" to it::class.java.simpleName,
                                    "errorMessage" to it.message.orEmpty().take(180),
                                ),
                            )
                        }
                        aiReply = result.getOrElse { "断线了。" }
                    }
                }) { Text("Ping", color = PipoColors.Gold) }
            }
            aiReply?.let { LabelRow("AI 回复", it) }
        }

        SettingsGroup("播放规则") {
            ToggleRow("主动安排一段", settings.smartSessionPlanner) {
                logSettingToggle("smartSessionPlanner", it)
                scope.launch { repository.updateSettings(settings.copy(smartSessionPlanner = it)) }
            }
            ToggleRow("工作时段自动播放", settings.workdayAutoplay) {
                logSettingToggle("workdayAutoplay", it)
                scope.launch { repository.updateSettings(settings.copy(workdayAutoplay = it)) }
            }
            ToggleRow("午休换放松歌单", settings.lunchRelaxMode) {
                logSettingToggle("lunchRelaxMode", it)
                scope.launch { repository.updateSettings(settings.copy(lunchRelaxMode = it)) }
            }
            ToggleRow("深夜低刺激", settings.lateNightCalmMode) {
                logSettingToggle("lateNightCalmMode", it)
                scope.launch { repository.updateSettings(settings.copy(lateNightCalmMode = it)) }
            }
            OutlinedTextField(
                value = settings.promptedRadioRule,
                onValueChange = { value ->
                    scope.launch {
                        repository.updateSettings(settings.copy(promptedRadioRule = value.take(240)))
                    }
                },
                label = { Text("Prompted Radio") },
                placeholder = { Text("上午不吵，熟歌 70%，新歌 30%") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsGroup("外观") {
            ToggleRow("隐藏点阵叠加", settings.hideDotPattern) {
                logSettingToggle("hideDotPattern", it)
                scope.launch { repository.updateSettings(settings.copy(hideDotPattern = it)) }
            }
            ToggleRow("隐藏 AI 圆球", settings.hideAiPetOrb) {
                logSettingToggle("hideAiPetOrb", it)
                scope.launch { repository.updateSettings(settings.copy(hideAiPetOrb = it)) }
            }
        }

        SettingsGroup("诊断日志") {
            LabelRow("范围", "记录播放、歌词、混音、AI 排歌、Taste Radar、网络错误和崩溃摘要；不会记录 cookie、API key、完整 URL 或完整歌词。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    scope.launch { copyDiagnosticsToClipboard(context) }
                }) {
                    Text("复制日志", color = PipoColors.Mint)
                }
                TextButton(onClick = {
                    scope.launch { shareDiagnosticsTxt(context) }
                }) {
                    Text("分享 txt", color = PipoColors.Blue)
                }
            }
        }

        SettingsGroup("关于你") {
            OutlinedTextField(
                value = settings.userFacts,
                onValueChange = { value ->
                    val facts = value.take(400)
                    scope.launch { repository.updateSettings(settings.copy(userFacts = facts)) }
                    runCatching { PipoGraph.petMemory.setUserFacts(facts) }
                },
                label = { Text("工作时间 / 作息 / 习惯 / 喜好") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            LabelRow("已写", "${settings.userFacts.length} / 400")
        }
    }
}

private suspend fun copyDiagnosticsToClipboard(context: Context) {
    val text = withContext(Dispatchers.IO) {
        DiagnosticsLogStore.snapshotText(context, maxBytes = 360_000)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PIPO diagnostic log", text))
    DiagnosticsLogStore.record("diagnostics", "copied", mapOf("chars" to text.length))
    Toast.makeText(context, "诊断日志已复制", Toast.LENGTH_SHORT).show()
}

private suspend fun shareDiagnosticsTxt(context: Context) {
    val file = withContext(Dispatchers.IO) {
        DiagnosticsLogStore.createShareFile(context)
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.diagnostics",
        file,
    )
    DiagnosticsLogStore.record(
        area = "diagnostics",
        event = "share_txt",
        fields = mapOf("bytes" to file.length()),
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PIPO diagnostic log")
        putExtra(Intent.EXTRA_TEXT, "PIPO 诊断日志")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, "分享诊断日志"))
    }.onFailure {
        Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
    }
}

private fun logSettingToggle(name: String, value: Boolean) {
    DiagnosticsLogStore.record(
        area = "settings",
        event = "toggle",
        fields = mapOf(
            "name" to name,
            "value" to value,
        ),
    )
}

@Composable
private fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = label,
        color = PipoColors.Ink,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    )
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
        Column {
            Text(label, color = PipoColors.Ink, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                detail,
                color = PipoColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
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
        Text(label, color = PipoColors.Ink, style = MaterialTheme.typography.titleSmall)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PipoColors.Bg0,
                checkedTrackColor = PipoColors.Mint,
                uncheckedThumbColor = PipoColors.TextDim,
                uncheckedTrackColor = Color(0x22FFFFFF),
            ),
        )
    }
}
