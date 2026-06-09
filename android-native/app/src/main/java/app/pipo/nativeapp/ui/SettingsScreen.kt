package app.pipo.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.AudioCacheStats
import app.pipo.nativeapp.data.AiConfigView
import app.pipo.nativeapp.data.AiProviderView
import app.pipo.nativeapp.data.ModelOption
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PetPersona
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
    val nav = LocalNav.current
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var qrJob by remember { mutableStateOf<Job?>(null) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf<String?>(null) }
    var modelOptionsByProvider by remember { mutableStateOf<Map<String, List<ModelOption>>>(emptyMap()) }

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
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // 01 / SERVICES
        SettingsSectionHeader("01", "SERVICES")
        
        // 网易云登录
        PipoRow(
            title = "网易云登录",
            subtitle = account?.nickname ?: "未登录 —— 手机验证码或网易云 App 扫码"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account == null) {
                    PipoButton(
                        text = "手机号登录",
                        onClick = { nav?.openLogin?.invoke() },
                        isPrimary = true,
                    )
                }
                PipoButton(
                    text = if (qrContent == null) "扫码登录" else "刷新二维码",
                    onClick = {
                        qrJob?.cancel()
                        qrJob = scope.launch {
                            qrContent = null
                            loginStatus = "正在请求二维码…"
                            val startResult = runCatching { repository.startQrLogin() }
                            val start = startResult.getOrNull()
                            if (start == null || start.qrContent.isBlank()) {
                                startResult.exceptionOrNull()?.let { err ->
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
                    },
                )
                if (account != null) {
                    PipoButton(
                        text = "退出登录",
                        onClick = {
                            scope.launch {
                                qrContent = null
                                repository.logout()
                                runCatching { PipoGraph.lastPlayback.clear() }
                                runCatching { PipoGraph.library.invalidate() }
                                DiagnosticsLogStore.record("login", "logout")
                                loginStatus = "已退出"
                            }
                        }
                    )
                }
            }
        }

        qrContent?.let { content ->
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .border(1.dp, PipoColors.GlassStroke, RectangleShape)
                    .padding(8.dp)
            ) {
                QrCode(
                    content = content,
                    modifier = Modifier.size(160.dp),
                )
            }
        }

        loginStatus?.let { status ->
            PipoRow(title = "登录状态", subtitle = status)
        }

        // AI Provider 配置
        val activeProvider = aiConfig.providers.firstOrNull { it.id == aiConfig.activeProvider }
        val anyHasKey = aiConfig.providers.any { it.hasKey }

        LaunchedEffect(activeProvider?.id) {
            val providerId = activeProvider?.id ?: return@LaunchedEffect
            if (modelOptionsByProvider[providerId] == null) {
                val models = runCatching { repository.aiListModels(providerId) }
                    .getOrElse { emptyList() }
                modelOptionsByProvider = modelOptionsByProvider + (providerId to models)
            }
        }

        PipoRow(
            title = "服务商",
            subtitle = activeProvider?.let(::providerStatusText) ?: "DeepSeek / OpenAI / MiMo"
        ) {
            if (aiConfig.providers.isNotEmpty()) {
                PipoDropdown(
                    currentLabel = activeProvider?.label ?: "选择",
                    selectedId = aiConfig.activeProvider,
                    options = aiConfig.providers.map { provider ->
                        PipoDropdownOption(
                            id = provider.id,
                            label = provider.label,
                            description = providerStatusText(provider),
                        )
                    },
                    onSelect = { option ->
                        aiReply = null
                        scope.launch { repository.setAiProvider(option.id) }
                    },
                    modifier = Modifier.width(190.dp),
                    fillTriggerWidth = true,
                )
            }
        }
        activeProvider?.let { provider ->
            val modelOptions = mergedModelOptions(
                activeModel = provider.model,
                options = modelOptionsByProvider[provider.id].orEmpty(),
            )
            PipoRow(
                title = "模型",
                subtitle = "当前服务商：${provider.label}",
            ) {
                PipoDropdown(
                    currentLabel = provider.model,
                    selectedId = provider.model,
                    options = modelOptions,
                    onSelect = { option ->
                        aiReply = null
                        scope.launch {
                            repository.aiSetModel(provider.id, option.id)
                            repository.refreshAiConfig()
                        }
                    },
                    modifier = Modifier.width(220.dp),
                    fillTriggerWidth = true,
                )
            }
        }
        if (!anyHasKey) {
            PipoRow(title = "提示", subtitle = "填入任一 key 后启用 AI")
        }

        Spacer(modifier = Modifier.height(14.dp))
        PipoTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = "API KEY",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = "输入新 key"
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PipoButton(
                text = "保存 KEY",
                onClick = {
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
                },
                isPrimary = true
            )
            PipoButton(
                text = "PING",
                onClick = {
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
                }
            )
        }
        
        aiReply?.let { reply ->
            Spacer(modifier = Modifier.height(10.dp))
            PipoRow(title = "AI 回复", subtitle = reply, showDivider = false)
        }

        // 02 / RULES
        SettingsSectionHeader("02", "RULES")

        PipoToggleRow(
            title = "封面短提示",
            subtitle = "让 AI 生成更简短个性的音乐封面介绍",
            checked = settings.aiNarration,
            onCheckedChange = {
                logSettingToggle("aiNarration", it)
                scope.launch { repository.updateSettings(settings.copy(aiNarration = it)) }
            }
        )

        // 03 / PREFERENCES
        SettingsSectionHeader("03", "PREFERENCES")

        // Pipo 性格：5 选 1 改成下拉，体感跟其它一行设置项一致，不再撑满半屏。
        // 当前人格作为 trigger 文案，点开 Popup 在原位置弹出全部选项；选中即写
        // settings.personaId。视觉对齐 PipoButton（透明底 + GlassStroke 边框 + Ink 文字）。
        val currentPersona = PetPersona.fromId(settings.personaId)
        PipoRow(
            title = "Pipo 性格",
            subtitle = "TA 跟你说话的语气。切换立即生效；下次开 app 用新人格打招呼。",
        ) {
            PipoPersonaDropdown(
                current = currentPersona,
                onSelect = { persona ->
                    logSettingToggle("persona:${persona.id}", true)
                    scope.launch { repository.updateSettings(settings.copy(personaId = persona.id)) }
                },
            )
        }

        PipoToggleRow(
            title = "隐藏 AI 圆球",
            subtitle = "在播放页隐藏 Pipo Pet 的物理实体悬浮球",
            checked = settings.hideAiPetOrb,
            onCheckedChange = {
                logSettingToggle("hideAiPetOrb", it)
                scope.launch { repository.updateSettings(settings.copy(hideAiPetOrb = it)) }
            }
        )
        
        Spacer(modifier = Modifier.height(14.dp))
        PipoTextField(
            value = settings.userFacts,
            onValueChange = { value ->
                val facts = value.take(400)
                scope.launch { repository.updateSettings(settings.copy(userFacts = facts)) }
                runCatching { PipoGraph.petMemory.setUserFacts(facts) }
            },
            label = "ABOUT YOU (工作时间 / 作息 / 习惯 / 喜好)",
            placeholder = "写下你的喜好与习惯，以便 AI 更好地为你排歌...",
            singleLine = false,
            minLines = 3,
            maxLines = 6
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "${settings.userFacts.length} / 400",
                color = PipoColors.TextDim,
                style = TextStyle(fontSize = 11.sp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        // 清空 AI 对话记忆：清对话流 + 摘要 + 最近原话 + 音乐指代，并清掉界面对话气泡。
        // 保留 ABOUT YOU（上面那栏是用户显式填的画像，单独编辑/清空）。
        PipoButton(
            text = "清空 AI 对话记忆",
            onClick = {
                runCatching { PipoGraph.petMemory.clearConversation() }
                PetChatStore.clear()
                DiagnosticsLogStore.record("ai_pet", "clear_conversation")
            }
        )

        // 04 / SYSTEM
        SettingsSectionHeader("04", "SYSTEM")
        
        PipoRow(
            title = "音频缓存",
            subtitle = "${cacheStats.totalMb} MB / ${cacheStats.maxMb} MB · ${cacheStats.count} 首"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PipoButton(
                    text = "清空缓存",
                    onClick = { scope.launch { repository.clearAudioCache() } }
                )
                PipoButton(
                    text = "上限 4 GB",
                    onClick = { scope.launch { repository.setCacheMaxMb(4096) } }
                )
            }
        }
        
        PipoRow(
            title = "诊断日志",
            subtitle = "记录播放、歌词、混音、AI 排歌、Taste Radar 和崩溃摘要；不含个人隐私数据。",
            showDivider = false
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PipoButton(
                    text = "复制日志",
                    onClick = { scope.launch { copyDiagnosticsToClipboard(context) } }
                )
                PipoButton(
                    text = "分享 TXT",
                    onClick = { scope.launch { shareDiagnosticsTxt(context) } }
                )
            }
        }
    }
}

private suspend fun copyDiagnosticsToClipboard(context: Context) {
    val text = withContext(Dispatchers.IO) {
        DiagnosticsLogStore.snapshotText(context, maxBytes = 360_000)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Pipo diagnostic log", text))
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
        putExtra(Intent.EXTRA_SUBJECT, "Pipo diagnostic log")
        putExtra(Intent.EXTRA_TEXT, "Pipo 诊断日志")
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
private fun SettingsSectionHeader(index: String, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = index,
                color = PipoColors.TextDim,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            )
            Text(
                text = "/",
                color = PipoColors.TextDim.copy(alpha = 0.5f),
                style = TextStyle(fontSize = 11.sp)
            )
            Text(
                text = title.uppercase(),
                color = PipoColors.Ink,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        PipoDivider()
    }
}

@Composable
private fun PipoDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PipoColors.GlassStroke)
    )
}

@Composable
private fun PipoRow(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    content: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PipoColors.Ink,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = PipoColors.TextDim,
                        style = TextStyle(
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    )
                }
            }
            if (content != null) {
                Spacer(modifier = Modifier.width(16.dp))
                content()
            }
        }
        if (showDivider) {
            PipoDivider()
        }
    }
}

@Composable
private fun PipoToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true
) {
    PipoRow(
        title = title,
        subtitle = subtitle,
        showDivider = showDivider
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PipoColors.Bg0,
                checkedTrackColor = PipoColors.Ink,
                uncheckedThumbColor = PipoColors.TextDim,
                uncheckedTrackColor = Color(0x14FFFFFF),
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun PipoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    val backgroundColor = if (isPrimary) PipoColors.Ink else Color.Transparent
    val textColor = if (isPrimary) PipoColors.Bg0 else PipoColors.Ink
    val borderModifier = if (isPrimary) {
        Modifier
    } else {
        Modifier.border(1.dp, PipoColors.GlassStroke, RectangleShape)
    }
    
    Box(
        modifier = modifier
            .background(backgroundColor, RectangleShape)
            .then(borderModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        )
    }
}

private data class PipoDropdownOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

private fun providerStatusText(provider: AiProviderView): String {
    val keyStatus = if (provider.hasKey) {
        "key ${provider.keyPreview ?: "已存"}"
    } else {
        "未填 key"
    }
    return "$keyStatus · ${provider.model}"
}

private fun mergedModelOptions(
    activeModel: String,
    options: List<ModelOption>,
): List<PipoDropdownOption> {
    val knownOptions = options.map { option ->
        PipoDropdownOption(
            id = option.id,
            label = option.label,
            description = option.id,
        )
    }
    if (activeModel.isBlank() || knownOptions.any { it.id == activeModel }) {
        return knownOptions
    }
    return listOf(
        PipoDropdownOption(
            id = activeModel,
            label = activeModel,
            description = "当前自定义模型",
        )
    ) + knownOptions
}

/**
 * 单选下拉，复用 PipoButton 的视觉（透明底 + GlassStroke 边框 + Ink/SemiBold/12sp/letter-spacing 1）。
 * trigger 显示当前项，点开 Popup 在 trigger 正下方对齐右边线弹出选项面板，
 * 选项内 (当前) 项 mint 高亮 + "当前" 角标。Popup 用 PopupPositionProvider 精确对齐，
 * 避免默认 alignment 把弹层贴到右侧外面。
 */
@Composable
private fun PipoDropdown(
    currentLabel: String,
    selectedId: String,
    options: List<PipoDropdownOption>,
    onSelect: (PipoDropdownOption) -> Unit,
    modifier: Modifier = Modifier,
    fillTriggerWidth: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // 右边线对齐 anchor 右边线，往下偏 6px 给点缝隙
                val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
                val y = anchorBounds.bottom + 6
                return IntOffset(x, y)
            }
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        // trigger：跟 PipoButton 同款 chip
        Box(
            modifier = (if (fillTriggerWidth) Modifier.fillMaxWidth() else Modifier)
                .border(1.dp, PipoColors.GlassStroke, RectangleShape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = if (fillTriggerWidth) Modifier.fillMaxWidth() else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (fillTriggerWidth) Arrangement.SpaceBetween else Arrangement.Start,
            ) {
                Text(
                    text = currentLabel,
                    color = PipoColors.Ink,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (fillTriggerWidth) Modifier.weight(1f) else Modifier.widthIn(max = 180.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = PipoColors.Ink,
                    style = TextStyle(fontSize = 10.sp),
                )
            }
        }
        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 240.dp, max = 320.dp)
                        .background(PipoColors.Bg1)
                        .border(1.dp, PipoColors.GlassStroke, RectangleShape)
                        .padding(vertical = 4.dp),
                ) {
                    options.forEach { option ->
                        val isCurrent = option.id == selectedId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isCurrent) {
                                        onSelect(option)
                                    }
                                    expanded = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.label,
                                        color = if (isCurrent) PipoColors.Mint else PipoColors.Ink,
                                        style = TextStyle(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Text(
                                            text = "当前",
                                            color = PipoColors.Mint,
                                            style = TextStyle(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp,
                                            ),
                                        )
                                    }
                                }
                                option.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = description,
                                        color = PipoColors.TextDim,
                                        style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipoPersonaDropdown(
    current: PetPersona,
    onSelect: (PetPersona) -> Unit,
    modifier: Modifier = Modifier,
) {
    PipoDropdown(
        currentLabel = current.label,
        selectedId = current.id,
        options = PetPersona.entries.map { persona ->
            PipoDropdownOption(
                id = persona.id,
                label = persona.label,
                description = persona.description,
            )
        },
        onSelect = { option ->
            PetPersona.entries.firstOrNull { it.id == option.id }?.let(onSelect)
        },
        modifier = modifier,
    )
}

@Composable
private fun PipoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = PipoColors.InkDim,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            ),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(
                color = PipoColors.Ink,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            cursorBrush = SolidColor(PipoColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .background(PipoColors.Bg1, RectangleShape)
                .border(
                    width = 1.dp,
                    color = if (isFocused) PipoColors.Ink else PipoColors.GlassStroke,
                    shape = RectangleShape
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            color = PipoColors.TextDim,
                            style = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
