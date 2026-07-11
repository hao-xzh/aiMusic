package app.pipo.nativeapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.AgentUiCard
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.execute.PlayerAgentExecutor
import app.pipo.nativeapp.data.agent.runtime.AgentRuntime
import app.pipo.nativeapp.data.agent.task.AgentTask
import app.pipo.nativeapp.data.agent.task.AgentTaskGateway
import app.pipo.nativeapp.playback.orchestrator.AgentQueueRequest
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import app.pipo.nativeapp.runtime.Amp
import app.pipo.nativeapp.runtime.AppForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

private fun AgentUiCard.toPetResultCard(): PetResultCard =
    when (kind) {
        AgentUiCard.Kind.Play -> PetResultCard.Play(
            count = count,
            artists = artists,
            covers = covers,
            insert = insert,
            similar = similar,
        )
        AgentUiCard.Kind.Skip -> PetResultCard.Action(icon = PetActionIcon.Skip, label = label, ok = ok)
        AgentUiCard.Kind.Like -> PetResultCard.Action(icon = PetActionIcon.Like, label = label, ok = ok)
        AgentUiCard.Kind.Unlike -> PetResultCard.Action(icon = PetActionIcon.Unlike, label = label, ok = ok)
        AgentUiCard.Kind.PlaylistAdd -> PetResultCard.Action(icon = PetActionIcon.PlaylistAdd, label = label, ok = ok)
        AgentUiCard.Kind.PlaylistRemove -> PetResultCard.Action(icon = PetActionIcon.PlaylistRemove, label = label, ok = ok)
        AgentUiCard.Kind.Error -> PetResultCard.Action(icon = PetActionIcon.Error, label = label, ok = false)
    }

@Composable
fun NativeAiPet(
    isPlaying: Boolean,
    currentTrack: app.pipo.nativeapp.data.NativeTrack?,
    currentQueue: List<app.pipo.nativeapp.data.NativeTrack>,
    currentTrackKey: String?,
    currentTitle: String,
    currentArtist: String,
    coverUrl: String?,
    onApplyAgentQueueRequest: suspend (AgentQueueRequest) -> QueueCommitResult,
    onSkipFromAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = PipoGraph.repository
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val coverCaption by AiCaptionBus.caption.collectAsState()
    val appInForeground by AppForeground.isForeground.collectAsState()
    val scope = rememberCoroutineScope()
    // 进程级保留 —— 离开播放页 / 看歌词 / 横竖屏切换都不再清空（NativeAiPet 是条件挂载的）。
    // 冷启动由下方 LaunchedEffect 从 PetMemory 回填最近对话，不再是一进来就空白。
    val messages = PetChatStore.messages
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    // 最新一条助手回复（贴在底部输入条上方，6s 后自动消散）
    var latestReply by remember { mutableStateOf<String?>(null) }
    val emptyHint = remember { EMPTY_HINTS.random() }

    // 冷启动回填：把 PetMemory 里持久化的最近对话还原成气泡。hydrateOnce 幂等，进程存活期间
    // 反复进出播放页不会重复回填，也不会覆盖本会话已累积的卡片；只有进程被杀后才真正再跑一次。
    LaunchedEffect(Unit) {
        val turns = withContext(Dispatchers.IO) {
            runCatching { PipoGraph.petMemory.conversationContext().turns }.getOrDefault(emptyList())
        }
        PetChatStore.hydrateOnce(turns)
    }

    val currentQueueSignature = remember(currentQueue) {
        currentQueue.joinToString(separator = "|") { it.id }
    }
    LaunchedEffect(currentQueueSignature) {
        PetChatStore.syncLatestPlayCardCount(currentQueue.size)
    }

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
    // Observe durable task state so leaving/re-entering the player does not cancel the visual state.
    LaunchedEffect(Unit) {
        PipoGraph.agentTasks.store.tasks.collect { tasks ->
            pending = tasks.any { it.status == app.pipo.nativeapp.data.agent.task.AgentTaskStatus.QUEUED || it.status == app.pipo.nativeapp.data.agent.task.AgentTaskStatus.RUNNING }
            tasks.filter {
                (it.status == app.pipo.nativeapp.data.agent.task.AgentTaskStatus.SUCCEEDED ||
                    it.status == app.pipo.nativeapp.data.agent.task.AgentTaskStatus.FAILED) &&
                    it.resultReply.isNotBlank()
            }
                .takeLast(3)
                .forEach { task ->
                    val alreadyDelivered = messages.any { it.taskId == task.id } ||
                        messages.any { !it.fromUser && it.taskId == null && it.text == task.resultReply }
                    if (!alreadyDelivered) {
                        messages += PetMessage(fromUser = false, text = task.resultReply, taskId = task.id)
                    }
                }
        }
    }

    // open 变化镜像到共享状态 —— PipoNativeApp 读它给播放界面做唤起虚化(背景模糊)。
    LaunchedEffect(open) { AiPetCommandBus.isOpen.value = open }

    // 锚点：cover rect 在 → attached 模式（贴封面右下内侧）；不在 → free（屏幕右下）
    val anchor = LocalCoverAnchor.current
    val coverRect = anchor.state.value.rect
    // 封面采样色统一驱动宠物、输入条和回复气泡，避免常驻球体在播放页里突兀。
    val petPalette = rememberPetPalette(useCoverEdgeColors(coverUrl))
    val agentRuntime = remember(repository, context) {
        AgentRuntime(
            repository = repository,
            ledger = AgentLedgerStore(context),
        )
    }

    // The durable coordinator owns execution; this gateway is only the current UI/player bridge.
    // It is registered process-wide and removed when this composable leaves the tree.
    val taskGateway = remember(repository, context, currentTrackKey, currentQueueSignature, settings.personaId, settings.userFacts) {
        object : AgentTaskGateway {
            override suspend fun execute(task: AgentTask): app.pipo.nativeapp.data.agent.domain.TurnOutcome {
                val promptContext = runCatching { PipoGraph.petMemory.conversationContext() }
                    .getOrDefault(app.pipo.nativeapp.data.PetMemory.ConversationContext())
                val snapshotTrack = currentTrack
                val executor = PlayerAgentExecutor(
                    repository = repository,
                    currentTrackProvider = { snapshotTrack },
                    sourceUserText = task.userText,
                    onApplyAgentQueueRequest = onApplyAgentQueueRequest,
                    onSkip = onSkipFromAgent,
                    taskId = task.id,
                )
                return agentRuntime.handle(
                    input = AgentTurnInput(
                        userText = task.userText,
                        history = promptContext.turns,
                        historySummary = promptContext.summary,
                        musicReferences = promptContext.musicReferences,
                        currentTrack = snapshotTrack,
                        currentQueue = currentQueue,
                        userFacts = settings.userFacts,
                        persona = app.pipo.nativeapp.data.PetPersona.fromId(settings.personaId),
                    ),
                    executor = executor,
                )
            }
        }
    }
    DisposableEffect(taskGateway) {
        PipoGraph.agentTasks.registerGateway(taskGateway)
        onDispose { PipoGraph.agentTasks.unregisterGateway(taskGateway) }
    }

    // 风场弹簧物理 + 节拍包络 —— 镜像 src/components/AiPet.tsx 159-289 行
    //   - 三层不同频率正弦叠加形成"自然风"
    //   - amp 瞬时尖峰 (norm > ampSlow + 0.12) → velocity 推一拍 + beatLevel 拉满
    //   - 弹簧-阻尼 K=20 / C=3.6 让摆动自然回弹
    //   - 节拍包络 110ms 半衰期指数衰减
    val sway = remember { mutableFloatStateOf(0f) }
    val pulseScale = remember { mutableFloatStateOf(1f) }
    val haloPulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(settings.hideAiPetOrb, isPlaying) {
        if (settings.hideAiPetOrb) {
            sway.floatValue = 0f
            pulseScale.floatValue = 1f
            haloPulse.floatValue = 0f
            return@LaunchedEffect
        }
        // 没在播放 → 缓动落位后停掉这条 60fps 风场循环。之前不管放没放、只要停在播放页就
        // 一直 60fps 写 sway/scale/halo 驱动重绘，待机白烧。isPlaying 变 true 时本 effect
        // 自动重启，重新进入下面的风场循环；播放中的"思考摆动"仍由循环内 pending 分支处理。
        if (!isPlaying) {
            var settleNs = 0L
            while (kotlin.math.abs(sway.floatValue) > 0.05f ||
                kotlin.math.abs(pulseScale.floatValue - 1f) > 0.002f
            ) {
                androidx.compose.runtime.withFrameNanos { ns ->
                    val dt = if (settleNs == 0L) 0.016f else
                        kotlin.math.min(0.05f, (ns - settleNs) / 1_000_000_000f)
                    settleNs = ns
                    val k = 1f - kotlin.math.exp(-dt / 0.18f)
                    sway.floatValue += (0f - sway.floatValue) * k
                    pulseScale.floatValue += (1f - pulseScale.floatValue) * k
                }
            }
            sway.floatValue = 0f
            pulseScale.floatValue = 1f
            return@LaunchedEffect
        }
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

    // 面板打开时，系统返回键 / 返回手势 = 关闭面板。这是最稳的关闭途径，跟圆球是否隐藏、
    // 面板撑多高都无关 —— 修「面板全屏后盖住点击关闭层、又没圆球，结果关不掉」的死角。
    // open=false 时本 handler 关闭，返回键交还给 PipoNativeApp 的路由 BackHandler。
    BackHandler(enabled = open) {
        keyboard?.hide()
        open = false
    }

    // 唤起强度 0→1：驱动覆盖层入场。真实播放页仍在下面作为背景。
    val backdropIntensity by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(320, easing = PipoMotion.FlipEase),
        label = "aiBackdrop",
    )

    Box(modifier = modifier) {
        // 像系统助手一样盖在播放页上：只轻压暗 + 封面色环境光，播放器本身仍然可见。
        // intensity≤0 时内部直接 return,关闭态零开销。
        AiChatBackdrop(
            coverUrl = coverUrl,
            palette = petPalette,
            intensity = backdropIntensity,
        )

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

        // 隐藏圆球时：AI 的话落在播放页底部按钮下方的空白区，logo + 一句话，自动消散。
        val coverCaptionText = if (settings.hideAiPetOrb && !open) {
            (coverCaption?.text ?: AiCaptionBus.compact(hint ?: latestReply))
                .takeIf { it.isNotBlank() }
        } else null
        AnimatedVisibility(
            visible = coverCaptionText != null,
            enter = fadeIn(tween(220)) + slideInVertically(
                animationSpec = tween(260, easing = PipoMotion.FlipEase),
                initialOffsetY = { it / 3 },
            ),
            exit = fadeOut(tween(180)) + slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { it / 3 },
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            CoverAiCaption(coverCaptionText.orEmpty(), palette = petPalette)
        }

        // ---- 历史对话面板 ----
        AnimatedVisibility(
            visible = open && (messages.isNotEmpty() || pending),
            // 不再是"从下往上的抽屉"——内容随光晕**就地浮现**(淡入 + 从底部 orb 处轻微放大),glow 略先一步。
            enter = fadeIn(tween(300, delayMillis = 70)) + scaleIn(
                animationSpec = tween(360, delayMillis = 70, easing = PipoMotion.FlipEase),
                initialScale = 0.94f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
            ),
            exit = fadeOut(tween(150)) + scaleOut(
                animationSpec = tween(150),
                targetScale = 0.96f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 顶部只让开状态栏，不再留会露出播放页的空白：面板从状态栏下直接铺到命令条。
                // 关闭改由返回键(上面的 BackHandler)负责，不依赖"点空白处"，所以无需为了能关
                // 而露出背后的播放页。
                .statusBarsPadding()
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
                // 用满「状态栏下 → 命令条上」的整段高度，不再固定 480dp：内容少时自然贴在
                // 输入框上方(wrap)，内容多时撑满可用高度并内部滚动 —— 解决「没全屏 + 底部被截」。
                maxHeight = configuration.screenHeightDp.dp,
                transparent = true,
            )
        }

        // ---- 空态建议 chips：贴命令条上方，点了填进输入框 ----
        AnimatedVisibility(
            visible = open && messages.isEmpty() && !pending,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 70.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SuggestedChips(
                hints = SUGGESTED_PROMPTS,
                palette = petPalette,
                onPick = { input = it },
            )
        }

        // ---- 底部命令条：横贯屏宽 + 钉到底部，输入永远在拇指区 ----
        // imePadding() 让条本身随键盘弹起浮在键盘上方，整个 activity 不再 pan/resize
        // —— 解决了"键盘收起后顶部残留封面"的视觉残影（Edge-to-Edge + 默认 IME 行为
        //    会让系统介入做 pan，pan 复位时偶发留下封面 stale frame）
        AnimatedVisibility(
            visible = open,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 300, easing = PipoMotion.FlipEase),
                initialOffsetY = { fullHeight -> fullHeight / 2 },
            ) + fadeIn(tween(220)),
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
                    val taskContext = JSONObject()
                        .put("currentTrack", currentTrack?.let { JSONObject().put("id", it.id).put("neteaseId", it.neteaseId).put("title", it.title).put("artist", it.artist).put("album", it.album).put("streamUrl", if (it.neteaseId == null) it.streamUrl else "") })
                        .put("queue", JSONArray(currentQueue.take(24).map { JSONObject().put("id", it.id).put("neteaseId", it.neteaseId).put("title", it.title).put("artist", it.artist).put("album", it.album).put("streamUrl", if (it.neteaseId == null) it.streamUrl else "").put("artworkUrl", it.artworkUrl) }))
                        .put("userFacts", settings.userFacts)
                        .put("persona", settings.personaId)
                        .toString()
                    var submittedTaskId = ""
                    val submittedTask = PipoGraph.agentTasks.submit(text, contextJson = taskContext, onFinished = { result ->
                        scope.launch {
                            result.fold(
                                onSuccess = { outcome ->
                                    if (messages.none { it.taskId == submittedTaskId && it.card == null }) {
                                        messages += PetMessage(fromUser = false, text = outcome.reply, taskId = submittedTaskId)
                                    }
                                    outcome.cards.forEach { card -> messages += PetMessage(false, "", card.toPetResultCard(), taskId = submittedTaskId) }
                                    latestReply = outcome.reply
                                    AiCaptionBus.show(outcome.reply)
                                },
                                onFailure = {
                                    val reply = "这次请求没能完成，请检查网络或 AI 配置后重试。"
                                    if (messages.none { it.taskId == submittedTaskId && it.card == null }) {
                                        messages += PetMessage(fromUser = false, text = reply, taskId = submittedTaskId)
                                    }
                                    latestReply = reply
                                    AiCaptionBus.show(reply)
                                },
                            )
                            pending = false
                        }
                    })
                    submittedTaskId = submittedTask.id
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
