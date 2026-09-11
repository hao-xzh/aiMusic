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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(repository: PipoRepository = PipoGraph.repository, aiOnly: Boolean = false) {
    val context = LocalContext.current
    val account by repository.account.collectAsState(initial = null)
    val playlists by repository.playlists.collectAsState(initial = emptyList())
    val cacheStats by repository.audioCacheStats.collectAsState(initial = AudioCacheStats(0, 0, 0))
    val aiConfig by repository.aiConfig.collectAsState(initial = AiConfigView("", emptyList()))
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    var apiKeyDraft by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    val active = aiConfig.providers.firstOrNull { it.id == aiConfig.activeProvider }
    fun perform(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        reply = null
        scope.launch {
            try { block(); if (success != null) reply = success }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { reply = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { repository.refreshAccount(); repository.refreshAudioCacheStats(); repository.refreshAiConfig() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { reply = e.message ?: "设置加载失败" }
    }
    LaunchedEffect(active?.id) {
        apiKeyDraft = ""
        modelOptions = emptyList()
        val id = active?.id ?: return@LaunchedEffect
        try { modelOptions = repository.aiListModels(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { reply = e.message ?: "模型列表加载失败" }
    }
    BrowsePageScaffold(if (aiOnly) "服务商与模型" else "设置", playlists.firstOrNull()?.coverUrl) {
        if (aiOnly) {
            Text("用于选歌与对话的 AI 服务", color = BrowseMuted, modifier = Modifier.padding(bottom = 24.dp))
            Text(if (active?.hasKey == true) "已配置 · ${active.label}" else "等待配置 / 添加 API Key 后启用", color = BrowseInk, modifier = Modifier.fillMaxWidth().background(PipoColors.GlassFill, androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)).padding(20.dp))
            Spacer(Modifier.height(24.dp))
            Text("服务商", color = BrowseMuted)
            Spacer(Modifier.height(10.dp))
            PipoDropdown(currentLabel = active?.label ?: "选择服务商", selectedId = aiConfig.activeProvider, options = aiConfig.providers.map { PipoDropdownOption(it.id, it.label, providerStatusText(it)) }, onSelect = { option -> perform { repository.setAiProvider(option.id); repository.refreshAiConfig() } }, modifier = Modifier.fillMaxWidth(), fillTriggerWidth = true)
            Spacer(Modifier.height(22.dp))
            Text("模型", color = BrowseMuted)
            Spacer(Modifier.height(10.dp))
            active?.let { provider ->
                PipoDropdown(currentLabel = provider.model.ifBlank { "选择模型" }, selectedId = provider.model, options = mergedModelOptions(provider.model, modelOptions), onSelect = { option -> perform { repository.aiSetModel(provider.id, option.id); repository.refreshAiConfig() } }, modifier = Modifier.fillMaxWidth(), fillTriggerWidth = true)
            }
            Spacer(Modifier.height(22.dp))
            PipoTextField(apiKeyDraft, { apiKeyDraft = it }, "API Key", singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = "输入 API Key")
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(enabled = apiKeyDraft.isNotBlank() && active != null && !busy, onClick = {
                val id = active?.id ?: return@Button
                val key = apiKeyDraft.trim()
                perform("Key 已保存") { repository.aiSetApiKey(id, key); apiKeyDraft = ""; repository.refreshAiConfig() }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = BrowseInk, contentColor = Color.Black), shape = androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text("保存 Key") }
            androidx.compose.material3.OutlinedButton(enabled = active?.hasKey == true && !busy, onClick = { perform { reply = repository.aiPing() } }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 50.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)) { Text("测试连接", color = PipoColors.Mint) }
            Spacer(Modifier.height(24.dp))
            Text("连接结果", color = BrowseMuted)
            Text(if (busy) "正在处理…" else reply ?: "保存后可测试连接", color = BrowseMuted, modifier = Modifier.padding(top = 12.dp))
        } else {
            SettingsPanel {
            PipoRow("网易云账号", account?.nickname ?: "未登录", showDivider = false) {
                PipoButton(if (account == null) "去登录 →" else "退出登录", {
                    if (account == null) nav?.openLogin?.invoke()
                    else perform("已退出登录") { repository.logout(); PipoGraph.lastPlayback.clear(); PipoGraph.library.invalidate() }
                })
            }
            }
            SettingsSectionHeader("AI 与偏好")
            SettingsPanel {
            PipoRow(
                title = "音乐口味",
                subtitle = "查看偏好、聆听倾向与来源",
                onClick = { nav?.openTaste?.invoke() },
            ) {
                Text("查看 ›", color = PipoColors.Mint, fontSize = 14.sp)
            }
            PipoRow(
                title = "服务商与模型",
                subtitle = active?.let { if (it.hasKey) "${it.label} · ${it.model}" else "未配置" } ?: "未配置",
                onClick = { nav?.openAiSettings?.invoke() },
            ) {
                Text("›", color = PipoColors.Mint, fontSize = 14.sp)
            }
            PipoRow("Pipo 性格", "调整与你聊天的语气") {
                PipoPersonaDropdown(current = PetPersona.fromId(settings.personaId), onSelect = { persona -> perform { repository.updateSettings(settings.copy(personaId = persona.id)) } })
            }
            PipoToggleRow("封面短提示", checked = settings.aiNarration, onCheckedChange = { enabled -> perform { repository.updateSettings(settings.copy(aiNarration = enabled)) } })
            PipoToggleRow("隐藏 AI 圆球", checked = settings.hideAiPetOrb, onCheckedChange = { enabled -> perform { repository.updateSettings(settings.copy(hideAiPetOrb = enabled)) } }, showDivider = false)
            }
            SettingsSectionHeader("关于你")
            PipoTextField(value = settings.userFacts, onValueChange = { value ->
                val facts = value.take(400)
                scope.launch { try { repository.updateSettings(settings.copy(userFacts = facts)); PipoGraph.petMemory.setUserFacts(facts) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { reply = e.message ?: "保存失败" } }
            }, label = "", placeholder = "写下你的喜好与习惯", singleLine = false, minLines = 3, maxLines = 6)
            Text("${settings.userFacts.length} / 400", color = BrowseMuted, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
            PipoButton("清空 AI 对话记忆", { perform("AI 对话记忆已清空") { val epoch = PipoGraph.petMemory.clearConversation(); PetChatStore.clear(epoch); DiagnosticsLogStore.record("ai_pet", "clear_conversation") } }, modifier = Modifier.align(Alignment.End).padding(top = 10.dp))
            SettingsSectionHeader("存储与诊断")
            SettingsPanel {
            PipoRow("音频缓存", "${cacheStats.totalMb} MB / ${cacheStats.maxMb} MB") {
                PipoButton("清空", { perform("缓存已清空") { repository.clearAudioCache(); repository.refreshAudioCacheStats() } })
            }
            PipoRow("缓存容量", "4 GB 上限") { PipoButton("设为 4 GB", { perform("缓存上限已设为 4 GB") { repository.setCacheMaxMb(4096); repository.refreshAudioCacheStats() } }) }
            PipoRow("诊断日志", showDivider = false) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PipoButton("复制", { perform("日志已复制") { copyDiagnosticsToClipboard(context) } })
                    PipoButton("分享", { perform { shareDiagnosticsTxt(context) } })
                }
            }
            }
            reply?.let { BrowseNotice(it) }
            if (busy) BrowseNotice("正在处理…")
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

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(title, color = BrowseMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
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
    onClick: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 60.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PipoColors.Ink,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = BrowseMuted,
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp
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
                checkedThumbColor = Color.White,
                checkedTrackColor = PipoColors.Mint,
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
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(text, color = if (isPrimary) BrowseInk else PipoColors.Mint, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun SettingsPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.025f), androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)).border(0.5.dp, Color.White.copy(alpha = 0.1f), androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)).padding(horizontal = 12.dp), content = content)
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
                .border(1.dp, PipoColors.GlassStroke, androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp))
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
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
                        fontSize = if (fillTriggerWidth) 16.sp else 14.sp,
                        fontWeight = FontWeight.Normal,
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
                        .border(1.dp, PipoColors.GlassStroke, androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp))
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
        if (label.isNotBlank()) Text(
            text = label,
            color = PipoColors.InkDim,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
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
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            cursorBrush = SolidColor(PipoColors.Ink),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .background(PipoColors.Bg1, androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp))
                .border(
                    width = 1.dp,
                    color = if (isFocused) PipoColors.Ink else PipoColors.GlassStroke,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(PipoDimens.SurfaceCornerDp)
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            color = PipoColors.TextDim,
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
