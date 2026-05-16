package app.pipo.nativeapp.ui

import android.util.Log
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.AudioCacheStats
import app.pipo.nativeapp.data.AiConfigView
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: PipoRepository = PipoGraph.repository) {
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
            "Pipo 把你的账号状态、播放规则、AI 口吻都攒在本地。",
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
                LabelRow("未配置", "填入任一 provider 的 API key 才会有 AI 招呼 / 单曲点评 / Discovery")
            }
            ToggleRow("DJ 旁白", settings.aiNarration) {
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
                    scope.launch { repository.aiSetApiKey(aiConfig.activeProvider, apiKeyDraft) }
                }) { Text("保存 key", color = PipoColors.Blue) }
                TextButton(onClick = {
                    scope.launch { aiReply = repository.aiPing() }
                }) { Text("Ping", color = PipoColors.Gold) }
            }
            aiReply?.let { LabelRow("AI 回复", it) }
        }

        SettingsGroup("播放规则") {
            ToggleRow("工作时段自动播放", settings.workdayAutoplay) {
                scope.launch { repository.updateSettings(settings.copy(workdayAutoplay = it)) }
            }
            ToggleRow("会议时暂停", settings.pauseDuringMeetings) {
                scope.launch { repository.updateSettings(settings.copy(pauseDuringMeetings = it)) }
            }
            ToggleRow("午休换放松歌单", settings.lunchRelaxMode) {
                scope.launch { repository.updateSettings(settings.copy(lunchRelaxMode = it)) }
            }
        }

        SettingsGroup("外观") {
            ToggleRow("隐藏点阵叠加", settings.hideDotPattern) {
                scope.launch { repository.updateSettings(settings.copy(hideDotPattern = it)) }
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
