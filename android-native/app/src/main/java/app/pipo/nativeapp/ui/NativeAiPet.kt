package app.pipo.nativeapp.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pipo.nativeapp.data.AiCaptionBus
import app.pipo.nativeapp.data.AiPetCommandBus
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.runtime.Amp
import app.pipo.nativeapp.runtime.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * AI 说"加到 [工作] 歌单" → 在用户 playlist 列表里模糊匹配一个出来。
 * 1) 精确同名 2) playlist 名含查询 3) 查询含 playlist 名（用户说短、歌单名长）。
 */
private fun matchPlaylist(
    playlists: List<app.pipo.nativeapp.data.PipoPlaylist>,
    query: String,
): app.pipo.nativeapp.data.PipoPlaylist? {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return null
    return playlists.firstOrNull { it.name.lowercase() == q }
        ?: playlists.firstOrNull { it.name.lowercase().contains(q) }
        ?: playlists.firstOrNull { q.contains(it.name.lowercase()) }
}

@Composable
fun NativeAiPet(
    isPlaying: Boolean,
    currentTrack: app.pipo.nativeapp.data.NativeTrack?,
    currentTrackKey: String?,
    currentTitle: String,
    currentArtist: String,
    coverUrl: String?,
    onPlayFromAgent: (List<app.pipo.nativeapp.data.NativeTrack>, app.pipo.nativeapp.data.ContinuousQueueSource?) -> Unit,
    onInsertFromAgent: (app.pipo.nativeapp.data.NativeTrack) -> Unit,
    onSkipFromAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = PipoGraph.repository
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val coverCaption by AiCaptionBus.caption.collectAsState()
    val appInForeground by AppForeground.isForeground.collectAsState()
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<PetMessage>() }
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    // 最新一条助手回复（贴在底部输入条上方，6s 后自动消散）
    var latestReply by remember { mutableStateOf<String?>(null) }
    val emptyHint = remember { EMPTY_HINTS.random() }

    // latestReply 的自动消散
    LaunchedEffect(latestReply) {
        if (latestReply != null) {
            delay(6000)
            latestReply = null
        }
    }
    LaunchedEffect(Unit) {
        AiPetCommandBus.commands.collect { command ->
            when (command) {
                AiPetCommandBus.Command.OpenChat -> {
                    open = true
                    hint = null
                    DiagnosticsLogStore.record(
                        area = "ai_pet",
                        event = "open_from_shortcut",
                        fields = mapOf("hideOrb" to settings.hideAiPetOrb),
                    )
                }
            }
        }
    }

    // 锚点：cover rect 在 → attached 模式（贴封面右下内侧）；不在 → free（屏幕右下）
    val anchor = LocalCoverAnchor.current
    val coverRect = anchor.state.value.rect
    // 封面采样色统一驱动宠物、输入条和回复气泡，避免常驻球体在播放页里突兀。
    val petPalette = rememberPetPalette(useCoverEdgeColors(coverUrl))

    // 风场弹簧物理 + 节拍包络 —— 镜像 src/components/AiPet.tsx 159-289 行
    //   - 三层不同频率正弦叠加形成"自然风"
    //   - amp 瞬时尖峰 (norm > ampSlow + 0.12) → velocity 推一拍 + beatLevel 拉满
    //   - 弹簧-阻尼 K=20 / C=3.6 让摆动自然回弹
    //   - 节拍包络 110ms 半衰期指数衰减
    val sway = remember { mutableFloatStateOf(0f) }
    val pulseScale = remember { mutableFloatStateOf(1f) }
    val haloPulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val phaseA = rnd.nextFloat() * 2f * PI.toFloat()
        val phaseB = rnd.nextFloat() * 2f * PI.toFloat()
        val phaseC = rnd.nextFloat() * 2f * PI.toFloat()
        var angle = 0f
        var velocity = 0f
        var ampSmooth = 0f
        var ampSlow = 0f
        var beatLevel = 0f
        var lastBeatAt = 0L
        var nextGustAt = 0L
        var lastNs = 0L
        // 呼吸光晕用单独 phase（独立于风场，1Hz 固定，跟 React `claudioPulse` 类似）
        var haloPhase = 0f

        while (true) {
            androidx.compose.runtime.withFrameNanos { ns ->
                val dt = if (lastNs == 0L) 0.016f else
                    kotlin.math.min(0.05f, kotlin.math.max(0.001f, (ns - lastNs) / 1_000_000_000f))
                lastNs = ns
                val now = ns / 1_000_000L
                val tSec = ns / 1_000_000_000f

                // amp 归一化 + EMA
                val rawAmp = Amp.flow.value
                val norm = ((rawAmp - 0.25f) / 0.7f).coerceIn(0f, 1f)
                val ampLerp = 1f - kotlin.math.exp(-dt / 0.2f)
                ampSmooth += (norm - ampSmooth) * ampLerp
                val slowLerp = 1f - kotlin.math.exp(-dt / 0.8f)
                ampSlow += (norm - ampSlow) * slowLerp

                // 三层风
                val breeze = (
                    sin(tSec * 0.18f + phaseA) * 1.0f +
                    sin(tSec * 0.31f + phaseB) * 0.55f +
                    sin(tSec * 0.49f + phaseC) * 0.3f
                ) * ampSmooth * 3.4f

                // 节拍冲量
                val beatExcess = norm - ampSlow
                if (beatExcess > 0.12f && now - lastBeatAt > 160 && ampSmooth > 0.05f) {
                    val impulse = 5f + beatExcess * 26f
                    val dir = if (kotlin.math.abs(velocity) > 1.5f) {
                        if (velocity >= 0f) 1f else -1f
                    } else if (rnd.nextFloat() > 0.5f) 1f else -1f
                    velocity += impulse * dir
                    beatLevel = kotlin.math.min(1f, beatLevel + 0.6f + beatExcess * 1.4f)
                    lastBeatAt = now
                }
                val beatDecay = 1f - kotlin.math.exp(-dt / 0.16f)
                beatLevel += (0f - beatLevel) * beatDecay

                // 阵风冲量
                if (now >= nextGustAt) {
                    if (ampSmooth > 0.08f) {
                        val musicLift = 0.8f + ampSmooth * 1.9f
                        val mag = (5f + rnd.nextFloat() * 6f) * musicLift
                        val dir = if (rnd.nextFloat() > 0.5f) 1f else -1f
                        velocity += mag * dir
                    }
                    nextGustAt = now + 1500 + (rnd.nextFloat() * 3300).toLong() - (ampSmooth * 1800).toLong()
                }

                // 弹簧-阻尼 K=20 C=3.6
                val target = breeze * 2.8f
                val accel = (target - angle) * 20f - velocity * 3.6f
                velocity += accel * dt
                angle += velocity * dt
                if (angle > 18f) { angle = 18f; if (velocity > 0f) velocity = 0f }
                else if (angle < -18f) { angle = -18f; if (velocity < 0f) velocity = 0f }

                if (pending) {
                    val thinkingSway = sin(tSec * 2f * PI.toFloat() * 1.8f) * 8f
                    angle += (thinkingSway - angle) * (1f - kotlin.math.exp(-dt / 0.15f))
                    sway.floatValue = angle
                    pulseScale.floatValue = 1f + 0.08f * sin(tSec * 2f * PI.toFloat() * 1.5f)
                    haloPhase += dt * 2.0f
                    haloPulse.floatValue = 0.5f + 0.5f * sin(haloPhase * 2f * PI.toFloat())
                } else {
                    sway.floatValue = angle
                    // 节拍 punch：拍中瞬间主球微胀
                    pulseScale.floatValue = 1f + ampSmooth * 0.06f + beatLevel * 0.10f

                    // 呼吸光晕：1.0 Hz
                    haloPhase += dt * (1f + ampSmooth * 0.4f)
                    haloPulse.floatValue = 0.5f + 0.5f * sin(haloPhase * 2f * PI.toFloat())
                }
            }
        }
    }
    val swayDeg = sway.floatValue
    val orbScale = pulseScale.floatValue
    val haloPhaseValue = haloPulse.floatValue


    // 每日首开招呼
    LaunchedEffect(settings.aiNarration, appInForeground) {
        if (!settings.aiNarration || !appInForeground) return@LaunchedEffect
        if (shouldGreetToday(context)) {
            // USER 部分对齐 React generateDailyGreeting：当下 + 天气 + 口味画像 + 跨 session 记忆
            val weather = runCatching { app.pipo.nativeapp.data.Weather.get() }.getOrNull()
            val ctxLine = app.pipo.nativeapp.data.AppContext.describe(weather)
            val profile = PipoGraph.tasteProfileStore.flow.value
            val profileHint = profile?.summary?.takeIf { it.isNotBlank() }?.let { "(TA 的音乐画像:$it)" } ?: ""
            val digest = app.pipo.nativeapp.data.AppContext.memoryDigest(settings.userFacts)
            val user = listOf(
                "当下:$ctxLine",
                profileHint,
                digest?.let { "TA 的人:$it" } ?: "",
            ).filter { it.isNotEmpty() }.joinToString("\n")

            if (!AppForeground.isForeground.value) return@LaunchedEffect
            val text = try {
                repository.aiChat(
                    system = app.pipo.nativeapp.data.PetPersona.fromId(settings.personaId).greetingSystemPrompt,
                    user = user.ifBlank { "当下:$ctxLine" },
                    temperature = 0.95f,
                    maxTokens = 80,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() }
                ?.trim()
                ?.trim('「', '」', '『', '』', '"', '\'', '“', '”')
                ?.take(80)
            if (text != null) {
                markGreeted(context)
                AiCaptionBus.show(text)
                hint = text
                delay(5500)
                hint = null
            }
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val petSizePx = with(density) { 36.dp.toPx() }
    val freePadEndPx = with(density) { 18.dp.toPx() }
    val freePadBottomPx = with(density) { 96.dp.toPx() }
    val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }

    val targetOffset = if (coverRect != null) {
        // attached：orb 悬挂在封面下方 14dp 的"绳子距离"，水平方向内缩 14dp 让 orb 右边沿
        // 不超出封面右边沿。React 原版让 orb 略微突出 8dp，这里走更安全的"完全在封面之下"。
        val inset = with(density) { 14.dp.toPx() }
        androidx.compose.ui.unit.IntOffset(
            x = (coverRect.right - petSizePx - inset).toInt(),  // orb top-left X
            y = (coverRect.bottom + inset).toInt(),               // orb top-left Y（封面下方）
        )
    } else {
        // free：orb 钉到屏幕右下角内缩 18dp / 96dp（让 nav 图标行的位置）
        androidx.compose.ui.unit.IntOffset(
            x = (screenW - petSizePx - freePadEndPx).toInt(),
            y = (screenH - petSizePx - freePadBottomPx).toInt(),
        )
    }

    val animatedOffset by animateIntOffsetAsState(
        targetValue = targetOffset,
        animationSpec = tween(durationMillis = 600, easing = PipoMotion.FlipEase),
        label = "petAnchor",
    )

    val keyboard = LocalSoftwareKeyboardController.current

    Box(modifier = modifier) {
        // ---- 透明点击捕获层：panel 打开时点空白处 = 收起 ----
        // 不画 scrim（用户明确拒绝过遮罩），但仍要让用户能"点空白处关闭"
        // —— 这是 iOS / 小爱 / 微信 浮层的通用心智
        if (open) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            keyboard?.hide()
                            open = false
                        },
                    ),
            )
        }

        // 隐藏圆球时：AI 的话只贴在封面上方，logo + 一句话，自动消散。
        val coverCaptionText = if (settings.hideAiPetOrb && !open) {
            (coverCaption?.text ?: AiCaptionBus.compact(hint ?: latestReply))
                .takeIf { it.isNotBlank() }
        } else null
        AnimatedVisibility(
            visible = coverCaptionText != null,
            enter = fadeIn(tween(220)) + slideInVertically(
                animationSpec = tween(260, easing = PipoMotion.FlipEase),
                initialOffsetY = { -it / 3 },
            ),
            exit = fadeOut(tween(180)) + slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { -it / 3 },
            ),
            modifier = Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints())
                layout(placeable.width, placeable.height) {
                    val rect = coverRect
                    if (rect != null) {
                        val topInset = with(density) { 12.dp.toPx() }.toInt()
                        val left = (rect.left + topInset).toInt()
                        val top = (rect.top + topInset).toInt()
                        placeable.placeRelative(
                            x = left.coerceAtLeast(0),
                            y = top.coerceAtLeast(0),
                        )
                    } else {
                        placeable.placeRelative(
                            x = ((screenW - placeable.width) / 2).toInt().coerceAtLeast(0),
                            y = with(density) { 72.dp.toPx() }.toInt(),
                        )
                    }
                }
            },
        ) {
            CoverAiCaption(coverCaptionText.orEmpty(), palette = petPalette)
        }

        // ---- 历史对话面板 ----
        AnimatedVisibility(
            visible = open && (messages.isNotEmpty() || pending),
            enter = fadeIn(tween(220)) + slideInVertically(
                animationSpec = tween(220, easing = PipoMotion.FlipEase),
                initialOffsetY = { it / 3 },
            ),
            exit = fadeOut(tween(180)) + slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { it / 3 },
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp)
                // 输入条高 ~52dp + nav bar inset + 间距 8dp
                .padding(bottom = 70.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            ChatHistoryPanel(
                messages = messages,
                pending = pending,
                palette = petPalette,
            )
        }

        // ---- 底部命令条：横贯屏宽 + 钉到底部，输入永远在拇指区 ----
        // imePadding() 让条本身随键盘弹起浮在键盘上方，整个 activity 不再 pan/resize
        // —— 解决了"键盘收起后顶部残留封面"的视觉残影（Edge-to-Edge + 默认 IME 行为
        //    会让系统介入做 pan，pan 复位时偶发留下封面 stale frame）
        AnimatedVisibility(
            visible = open,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 280, easing = PipoMotion.FlipEase),
                initialOffsetY = { fullHeight -> fullHeight },
            ) + fadeIn(tween(180)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 220, easing = PipoMotion.CloseEase),
                targetOffsetY = { fullHeight -> fullHeight },
            ) + fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            PetCommandBar(
                palette = petPalette,
                input = input,
                pending = pending,
                hintText = if (messages.isEmpty()) emptyHint else "说点什么…",
                onInputChange = { input = it },
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty()) return@PetCommandBar
                    input = ""
                    messages += PetMessage(fromUser = true, text = text)
                    PetBubbleState.lastUserContext = text
                    runCatching { PipoGraph.petMemory.recordUtterance(text) }
                    pending = true
                    scope.launch {
                        try {
                            val agent = app.pipo.nativeapp.data.PetAgent(repository)
                            val response = agent.chat(
                                userText = text,
                                history = messages.map { it.fromUser to it.text },
                                currentTrack = currentTrack,
                                userFacts = settings.userFacts,
                                persona = app.pipo.nativeapp.data.PetPersona.fromId(settings.personaId),
                            )
                            messages += PetMessage(fromUser = false, text = response.reply)
                            latestReply = response.reply
                            AiCaptionBus.show(response.reply)
                            when {
                                response.action == app.pipo.nativeapp.data.PetAgent.Action.Skip -> {
                                    onSkipFromAgent()
                                }
                                response.action == app.pipo.nativeapp.data.PetAgent.Action.Like ||
                                    response.action == app.pipo.nativeapp.data.PetAgent.Action.Unlike -> {
                                    val tid = currentTrack?.neteaseId
                                    val like = response.action == app.pipo.nativeapp.data.PetAgent.Action.Like
                                    if (tid != null) {
                                        app.pipo.nativeapp.DiagnosticsLogStore.record(
                                            area = "ai_agent",
                                            event = "like_intent_invoke",
                                            fields = mapOf(
                                                "neteaseId" to tid,
                                                "like" to like,
                                                "title" to currentTrack?.title,
                                            ),
                                        )
                                        runCatching { repository.likeSong(tid, like) }
                                            .onSuccess {
                                                // AI 的 reply 是人格化文案，未必明确告诉用户"已收藏"。
                                                // 这里追加一条系统态反馈，让用户确认操作真的执行了。
                                                messages += PetMessage(
                                                    fromUser = false,
                                                    text = if (like) "（已加心：「${currentTrack?.title.orEmpty()}」→ 我喜欢的音乐）"
                                                        else "（已取消收藏：「${currentTrack?.title.orEmpty()}」）",
                                                )
                                            }
                                            .onFailure { err ->
                                                messages += PetMessage(
                                                    fromUser = false,
                                                    text = "（${if (like) "收藏" else "取消收藏"}失败：${err.message ?: err::class.java.simpleName}）",
                                                )
                                            }
                                    } else {
                                        messages += PetMessage(fromUser = false, text = "（现在没在放歌。）")
                                    }
                                }
                                response.action == app.pipo.nativeapp.data.PetAgent.Action.AddToPlaylist ||
                                    response.action == app.pipo.nativeapp.data.PetAgent.Action.RemoveFromPlaylist -> {
                                    val tid = currentTrack?.neteaseId
                                    val pname = response.trackOp?.playlistName
                                    val opStr = if (response.action == app.pipo.nativeapp.data.PetAgent.Action.AddToPlaylist) "add" else "del"
                                    when {
                                        tid == null -> messages += PetMessage(fromUser = false, text = "（现在没在放歌。）")
                                        pname == null -> messages += PetMessage(fromUser = false, text = "（没说要加到哪个歌单。）")
                                        else -> {
                                            val playlists = repository.playlists.first()
                                            val target = matchPlaylist(playlists, pname)
                                            if (target == null) {
                                                messages += PetMessage(fromUser = false, text = "（没找到歌单「$pname」。）")
                                            } else {
                                                runCatching {
                                                    repository.playlistModifyTracks(target.id, opStr, listOf(tid))
                                                }.onSuccess {
                                                    val verb = if (opStr == "add") "已加入" else "已移出"
                                                    messages += PetMessage(
                                                        fromUser = false,
                                                        text = "（$verb「${target.name}」：${currentTrack?.title.orEmpty()}）",
                                                    )
                                                }.onFailure { err ->
                                                    messages += PetMessage(
                                                        fromUser = false,
                                                        text = "（操作歌单失败：${err.message ?: err::class.java.simpleName}）",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                response.action == app.pipo.nativeapp.data.PetAgent.Action.Insert &&
                                    response.initialBatch.isNotEmpty() -> {
                                    onInsertFromAgent(response.initialBatch.first())
                                }
                                (response.action == app.pipo.nativeapp.data.PetAgent.Action.Play ||
                                    response.action == app.pipo.nativeapp.data.PetAgent.Action.Similar) &&
                                    response.initialBatch.isNotEmpty() -> {
                                    onPlayFromAgent(response.initialBatch, response.continuous)
                                }
                                // Action.Explain / Action.Chat：reply 已入 messages，无需额外动作
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val reply = "我这边刚刚断了一下，再说一次。"
                            messages += PetMessage(fromUser = false, text = reply)
                            latestReply = reply
                            AiCaptionBus.show(reply)
                        } finally {
                            pending = false
                        }
                    }
                },
            )
        }

        // ---- Hint 气泡：仍贴 orb（panel 关闭状态下才显示）----
        AnimatedVisibility(
            visible = hint != null && !open && !settings.hideAiPetOrb,
            enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.92f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.92f),
            modifier = Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints())
                layout(placeable.width, placeable.height) {
                    val gap = with(density) { 8.dp.toPx() }
                    val anchorRight = (animatedOffset.x + petSizePx).toInt()
                    val anchorBottom = (animatedOffset.y - gap).toInt()
                    placeable.placeRelative(
                        x = (anchorRight - placeable.width).coerceAtLeast(0),
                        y = (anchorBottom - placeable.height).coerceAtLeast(0),
                    )
                }
            },
        ) {
            HintBubble(hint.orEmpty(), palette = petPalette)
        }

        // ---- Orb：常驻挂在封面右下，是命令条的开关 ----
        if (!settings.hideAiPetOrb) {
            Box(
                modifier = Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(
                            androidx.compose.ui.unit.Constraints(),
                        )
                        layout(placeable.width, placeable.height) {
                            val anchorRight = (animatedOffset.x + petSizePx).toInt()
                            val anchorBottom = (animatedOffset.y + petSizePx).toInt()
                            placeable.placeRelative(
                                x = anchorRight - placeable.width,
                                y = anchorBottom - placeable.height,
                            )
                        }
                    },
            ) {
                PetOrb(
                    haloPulse = haloPhaseValue,
                    sway = swayDeg,
                    pulseScale = orbScale,
                    attached = (coverRect != null),
                    palette = petPalette,
                    pending = pending,
                    onClick = { open = !open; if (open) hint = null },
                )
            }
        }
    }
}
