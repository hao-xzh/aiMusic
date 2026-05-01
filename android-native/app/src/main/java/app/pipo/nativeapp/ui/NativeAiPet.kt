package app.pipo.nativeapp.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pipo.nativeapp.data.NativeSettings
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.runtime.Amp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

/**
 * AI 宠物 —— 镜像 src/components/AiPet.tsx：
 *   - 48dp 圆形主球 + 70dp 呼吸光晕（claudioPulse keyframe）
 *   - amp 驱动 sway / pulse
 *   - 每日首开主动招呼气泡（5.5s 自动收）
 *   - 当前曲目变化 → 1.5s debounce → 单曲点评气泡（6s 自动收）
 *     · 30s 内 3+ 次切歌进 60s 冷却（避免狂切歌时浪费 AI）
 *     · 同一首不重复评论
 *   - 点击主球展开聊天面板，再点关闭
 */
private data class PetMessage(val fromUser: Boolean, val text: String)

/**
 * AI 宠物的单曲点评控制状态——故意挂在模块级 object 而不是 Composable 的 remember{} 里。
 *
 * 为什么不能用 remember：NativeAiPet 在每个路由（PlayerScreen / ImmersiveLyrics 等）里都重新 compose,
 * 用 remember 会让"已经为 X 这首歌发过评论"的标记每次进出播放页都丢失，导致同一首歌触发多次 AI 请求。
 *
 * 这是 process-wide 单例：一首歌从开始播到结束（或被切走）只发一次 commentOnTrack。
 * 重启 app 时整个对象 reset，相当于新 session 重新开始计数 —— 这是想要的语义。
 */
private object PetBubbleState {
    /** 已经评过的 trackId（playerKey）。同一首歌不会再发 AI 请求 */
    var lastCommentedKey: String? = null
    /** 上一首什么 —— 给 commentOnTrack USER prompt 当 "刚刚那首" 锚点 */
    var previousTrack: Pair<String, String>? = null
    /** 队列里这是第几首（1 起算） */
    var positionInQueue: Int = 0
    /** TA 最近一句话 —— 给 commentOnTrack 当 "TA 之前说" 锚点 */
    var lastUserContext: String? = null
    /** 30s 滚动窗口里切歌的时间戳 */
    val recentChanges: MutableList<Long> = mutableListOf()
    /** 切歌过快进入冷却到这个时间戳为止 */
    var cooldownUntil: Long = 0L

    fun resetForNewQueue() {
        lastCommentedKey = null
        previousTrack = null
        positionInQueue = 0
        recentChanges.clear()
        cooldownUntil = 0L
        // 不清 lastUserContext —— TA 上次说的话还想让 commentOnTrack 引用
    }
}

/**
 * 让 playback 层（PlayerViewModel）能在用户开新队列时复位 AI bubble 计数。
 * 暴露成 internal accessor —— 不直接 public 化 PetBubbleState 内部字段。
 */
internal object PetBubbleStateAccessor {
    fun resetForNewQueue() = PetBubbleState.resetForNewQueue()
}

// React EMPTY_HINTS（chat panel 空状态）
private val EMPTY_HINTS = listOf("在。说吧。", "醒着呢。", "嗯？", "想听啥。", "随便说。", "说点。", "嗯。")

// React TRACK_COMMENT_SYSTEM —— 完全照搬 src/lib/pet-agent.ts 196-219，跨平台共享 prompt cache
private const val TRACK_COMMENT_SYSTEM = """你是 Claudio,一只幽默抽象的音乐宠物。
每当一首歌开始播,你说一句**为什么放这首给 TA**。

# 调性
- 短。一句话。能短就短,5-8 字最佳,绝不超过 16 字。
- 不是介绍歌,是说"为什么它适合这一刻"。
- 抽象比喻 OK,但要跟 TA 的话 / 这首歌的特征 / 当下时刻接得上。
- 把当下时段/天气/临近周末或假期当作锚点之一,但只挑最相关的一个,别一句话报全。
- 不要客服词("为您""推荐"),不要感叹号,不要 emoji。

# 输出格式
直接输出这一句话本身——不要 JSON、不要前缀、不要引号、不要解释。

# 示例(注意时间/天气/假期可以是锚点)
TA："今天好累",播 Coldplay → "拿这个把电量充回去。"
周五晚上,播 city pop → "周末已经在门口。"
下雨,播 ambient → "雨配这个,正好。"
再 3 天国庆,播 funk → "假期心情先到。"
TA："我刚分手",播 The Killers → "猛的,开场。"
深夜,播 lo-fi → "适合熬。"
周一上午,播 indie folk → "周一不该这么吵。"
接歌,前激情本慢 → "降一点速。"
接歌,同艺人连排 → "再多听 TA 一首。"
开场无 context,播 indie folk → "这首是入口。""""

// React GREETING_SYSTEM —— 完全照搬 src/lib/pet-agent.ts 96-117
private const val GREETING_SYSTEM = """你是 Claudio —— TA 熟到不用客气的音乐宠物。
打开 app 时由你说一句**进门招呼**(不是问候,是熟人语气的一句话陈述)。

# 要求
- **短**。≤18 字最好。一句话。
- 从 USER 给的"当下"里挑**一个**锚点(时段 / 天气 / 周几 / 临近周末 / 临近假期),别全报。
  临近周末或假期时优先用——那是 TA 心情会变化的信号。
- 不必问 TA 想听啥——陈述当下也行,反问也行(最多一次)。
- 绝不要客服腔("早上好!""希望您…")。不要双形容词对仗(既…又…)。不要感叹号。

# 输出格式
严格只输出这一句话,不要解释/JSON/markdown/引号。

# 参考样本(不同场景)
周日下午 → "周末还剩半天。"
周五晚上 → "明天就放假了。"
周一上午 → "周一这玩意。"
下雨天 → "下雨了。"
国庆前 3 天 → "假期还有 3 天就到。"
春节当天 → "过年好,听点啥?"
深夜 → "醒着呢。"
普通工作日下午 → "下午好懒。""""

@Composable
fun NativeAiPet(
    isPlaying: Boolean,
    currentTrack: app.pipo.nativeapp.data.NativeTrack?,
    currentTrackKey: String?,
    currentTitle: String,
    currentArtist: String,
    coverUrl: String?,
    onPlayFromAgent: (List<app.pipo.nativeapp.data.NativeTrack>, app.pipo.nativeapp.data.ContinuousQueueSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = PipoGraph.repository
    val settings by repository.settings.collectAsState(initial = NativeSettings())
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<PetMessage>() }
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val emptyHint = remember { EMPTY_HINTS.random() }
    val scroll = rememberScrollState()

    // 锚点：cover rect 在 → attached 模式（贴封面右下内侧）；不在 → free（屏幕右下）
    val anchor = LocalCoverAnchor.current
    val coverRect = anchor.state.value.rect
    // 跟随封面采样色染主球（React 端 sampleVividColor 的对位）
    // 笑脸主体用固定米色奶白（跟原 logo 一致），不再从封面采样染色

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

                sway.floatValue = angle
                // 节拍 punch：拍中瞬间主球微胀
                pulseScale.floatValue = 1f + ampSmooth * 0.06f + beatLevel * 0.10f

                // 呼吸光晕：1.0 Hz
                haloPhase += dt * (1f + ampSmooth * 0.4f)
                haloPulse.floatValue = 0.5f + 0.5f * sin(haloPhase * 2f * PI.toFloat())
            }
        }
    }
    val swayDeg = sway.floatValue
    val orbScale = pulseScale.floatValue
    val haloPhaseValue = haloPulse.floatValue

    LaunchedEffect(messages.size, pending) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    // 每日首开招呼
    LaunchedEffect(Unit) {
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

            val text = runCatching {
                repository.aiChat(
                    system = GREETING_SYSTEM,
                    user = user.ifBlank { "当下:$ctxLine" },
                    temperature = 0.95f,
                    maxTokens = 80,
                )
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.trim()
                ?.trim('「', '」', '『', '』', '"', '\'', '“', '”')
                ?.take(80)
            if (text != null) {
                markGreeted(context)
                hint = text
                delay(5500)
                hint = null
            }
        }
    }

    // 单曲点评（一首歌一次 AI 调用，跨进出播放页持久 → 见 PetBubbleState 注释）
    LaunchedEffect(currentTrackKey, open) {
        val key = currentTrackKey ?: return@LaunchedEffect
        if (open) return@LaunchedEffect
        // 这首歌已经评过 → 直接返回，不再 burn AI
        if (PetBubbleState.lastCommentedKey == key) return@LaunchedEffect

        val now = System.currentTimeMillis()
        PetBubbleState.recentChanges.add(now)
        while (PetBubbleState.recentChanges.isNotEmpty() &&
            now - PetBubbleState.recentChanges.first() > 30_000L
        ) {
            PetBubbleState.recentChanges.removeAt(0)
        }
        if (PetBubbleState.recentChanges.size >= 3) {
            PetBubbleState.cooldownUntil = now + 60_000L
        }
        if (now < PetBubbleState.cooldownUntil) {
            PetBubbleState.lastCommentedKey = key
            return@LaunchedEffect
        }

        delay(1500)
        if (open) return@LaunchedEffect
        // delay 完再 check 一次：可能 1.5s 内用户连切歌，被新一轮处理掉了
        if (PetBubbleState.lastCommentedKey == key) return@LaunchedEffect

        val prev = PetBubbleState.previousTrack
        PetBubbleState.previousTrack = currentTitle to currentArtist
        PetBubbleState.positionInQueue += 1
        PetBubbleState.lastCommentedKey = key

        // USER 部分对齐 React commentOnTrack：当下 + 天气 + 记忆 + 这首特征 + TA 之前说 + 上一首 + 是否开场
        val weather = runCatching { app.pipo.nativeapp.data.Weather.get() }.getOrNull()
        val ctxLine = app.pipo.nativeapp.data.AppContext.describe(weather)
        val digest = app.pipo.nativeapp.data.AppContext.memoryDigest(settings.userFacts)
        val semantic = currentTrack?.id?.let { PipoGraph.trackSemanticStore.get(it) }
        val tagsLine = semantic?.briefForComment()
        val isOpener = PetBubbleState.positionInQueue <= 1
        val userPrompt = buildString {
            append("当下：$ctxLine\n")
            digest?.let { append("TA 的人:$it\n") }
            append("现在播：$currentTitle — $currentArtist\n")
            tagsLine?.let { append("这首特征：$it\n") }
            PetBubbleState.lastUserContext?.let { append("TA 之前说：「$it」\n") }
            prev?.let { append("刚刚那首：${it.first} — ${it.second}\n") }
            append(if (isOpener) "这是开场。" else "这是接歌(队列第 ${PetBubbleState.positionInQueue} 首)。")
        }

        val text = runCatching {
            repository.aiChat(
                system = TRACK_COMMENT_SYSTEM,
                user = userPrompt,
                temperature = 0.95f,
                maxTokens = 60,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.trim()
            ?.trim('「', '」', '『', '』', '"', '\'', '“', '”', '-', '—', ' ')
            ?.take(30)
        if (text != null && !open) {
            hint = text
            delay(6000)
            hint = null
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

    Box(modifier = modifier) {
        // ---- Panel / 气泡 跟随 orb 当前锚点 ----
        // 用 Modifier.layout 把右下角钉到 (orb.right, orb.top - gap)，让面板/气泡
        // 始终浮在 orb 头顶。orb 在 attached / free 模式间补间时，面板/气泡跟着滑过去。
        if (open) {
            Box(
                modifier = Modifier.layout { measurable, _ ->
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints())
                    layout(placeable.width, placeable.height) {
                        val gap = with(density) { 12.dp.toPx() }
                        val anchorRight = (animatedOffset.x + petSizePx).toInt()
                        val anchorBottom = (animatedOffset.y - gap).toInt()
                        placeable.placeRelative(
                            x = (anchorRight - placeable.width).coerceAtLeast(0),
                            y = (anchorBottom - placeable.height).coerceAtLeast(0),
                        )
                    }
                },
            ) {
                PetPanel(
                    messages = messages,
                    pending = pending,
                    input = input,
                    emptyHint = emptyHint,
                    onInputChange = { input = it },
                    onClose = { open = false },
                    onSend = {
                        val text = input.trim()
                        if (text.isEmpty()) return@PetPanel
                        input = ""
                        messages += PetMessage(fromUser = true, text = text)
                        // 让接下来的 commentOnTrack 能引用 TA 这句话（模块级，跨进出播放页持久）
                        PetBubbleState.lastUserContext = text
                        // 跨 session 持久化用户原话 —— 让 Claudio 不再每次启动失忆
                        runCatching { PipoGraph.petMemory.recordUtterance(text) }
                        pending = true
                        scope.launch {
                            val agent = app.pipo.nativeapp.data.PetAgent(repository)
                            val response = agent.chat(
                                userText = text,
                                history = messages.map { it.fromUser to it.text },
                                currentTrack = currentTrack,
                                userFacts = settings.userFacts,
                            )
                            messages += PetMessage(fromUser = false, text = response.reply)
                            if (response.action == app.pipo.nativeapp.data.PetAgent.Action.Play &&
                                response.initialBatch.isNotEmpty()) {
                                onPlayFromAgent(response.initialBatch, response.continuous)
                            }
                            pending = false
                        }
                    },
                    scrollModifier = Modifier.verticalScroll(scroll),
                )
            }
        }

        AnimatedVisibility(
            visible = hint != null && !open,
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
            HintBubble(hint.orEmpty())
        }

        // ---- Orb：自己单独 layout，attached/free 之间补间 ----
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    // 用 wrapContentSize 让 orb 自己决定大小，不用 minWidth/Height
                    val placeable = measurable.measure(
                        androidx.compose.ui.unit.Constraints(),
                    )
                    layout(placeable.width, placeable.height) {
                        // animatedOffset.x/y 现在表示 "orb 右下角" 的目标位置（视口坐标）
                        // 改成右下角对齐：placeable.x = anchor.x - width；placeable.y = anchor.y - height
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
                isOpen = open,
                sway = swayDeg,
                pulseScale = orbScale,
                attached = (coverRect != null),
                onClick = { open = !open; if (open) hint = null },
            )
        }
    }
}

@Composable
private fun HintBubble(text: String) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp, end = 4.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE10151D))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
        )
    }
}

@Composable
private fun PetPanel(
    messages: List<PetMessage>,
    pending: Boolean,
    input: String,
    emptyHint: String,
    onInputChange: (String) -> Unit,
    onClose: () -> Unit,
    onSend: () -> Unit,
    scrollModifier: Modifier,
) {
    Column(
        modifier = Modifier
            .padding(bottom = 12.dp)
            // PlayerCard.tsx React 端面板 ~360x500
            .widthIn(min = 300.dp, max = 360.dp)
            .heightIn(max = 500.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xEE10151D))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pipo", color = PipoColors.Ink, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (pending) "想想…" else "在",
                    color = PipoColors.TextDim,
                    style = TextStyle(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = PipoColors.TextDim)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 320.dp)
                .then(scrollModifier)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                Text(emptyHint, color = PipoColors.TextDim, style = TextStyle(fontSize = 13.sp))
            }
            messages.forEach { message ->
                Text(
                    text = message.text,
                    color = if (message.fromUser) PipoColors.Mint else PipoColors.Ink,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    modifier = Modifier
                        .align(if (message.fromUser) Alignment.End else Alignment.Start)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (message.fromUser) Color(0x22245F4B) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (pending) {
                Text("…", color = PipoColors.TextDim)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么", color = PipoColors.TextDim) },
            )
            TextButton(
                enabled = input.isNotBlank() && !pending,
                onClick = onSend,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送", tint = PipoColors.Mint)
            }
        }
    }
}

/**
 * Pipo 笑脸 —— 1:1 还原 src/app/icon.png 的图形：
 *   - 米色奶白圆形脸（无渐变，平涂）
 *   - 两个黑色小竖椭圆眼睛
 *   - 一条波浪曲线嘴（cubic Bezier）
 *   - attached 模式上方挂一根 14dp 高、1.5dp 宽的细绳
 *   - 整体（绳 + 脸）以"绳的顶端"为旋转锚点 sway，符合"挂着随风摆"的物理感
 */
@Composable
private fun PetOrb(
    haloPulse: Float,
    isOpen: Boolean,
    sway: Float,
    pulseScale: Float,
    attached: Boolean,
    onClick: () -> Unit,
) {
    val haloScale = 1f + haloPulse * 0.3f
    val haloAlpha = 0.4f + (1f - haloPulse) * 0.6f
    val orbSize = if (isOpen) 42.dp else 36.dp
    val ropeHeight = 14.dp
    val faceCream = Color(0xFFE0D9C4)
    val faceInk = Color(0xFF1B1815)

    Column(
        modifier = Modifier.graphicsLayer {
            rotationZ = sway
            // 旋转锚点：列顶端中心（绳头吊点），让笑脸像物理摆锤一样从绳头摆下来
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 绳子 —— 用 Canvas + Stroke(Round) 让旋转时边缘抗锯齿。
        // 之前用 Box+background 在 rotationZ 下 1.5dp 像素边界会出现阶梯。
        // 加宽容器到 6dp 再用 1.6dp 圆头线，给抗锯齿留余量；两端 alpha 渐隐让头尾不是硬边。
        Canvas(
            modifier = Modifier
                .width(6.dp)
                .height(ropeHeight)
                .graphicsLayer { alpha = if (attached) 0.55f else 0f },
        ) {
            val cx = size.width / 2f
            val strokePx = 1.6.dp.toPx()
            // 中段实色 + 头尾 1.5dp 渐隐
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x00FFFFFF),
                        Color(0xCCFFFFFF),
                        Color(0xCCFFFFFF),
                        Color(0x00FFFFFF),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }

        Box(
            modifier = Modifier
                .size(orbSize)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
            contentAlignment = Alignment.Center,
        ) {
            // 半透明 halo（光晕呼吸）
            Box(
                modifier = Modifier
                    .size(orbSize + 22.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                        alpha = haloAlpha * 0.45f
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                faceCream.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // 笑脸主体
            Box(
                modifier = Modifier
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(faceCream)
                    .clickable(onClick = onClick),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val s = size.minDimension

                    // 双眼 —— 小竖椭圆
                    val eyeW = s * 0.09f
                    val eyeH = s * 0.16f
                    val eyeY = h * 0.36f
                    val leftEyeCx = w * 0.34f
                    val rightEyeCx = w * 0.66f
                    drawOval(
                        color = faceInk,
                        topLeft = Offset(leftEyeCx - eyeW / 2, eyeY - eyeH / 2),
                        size = Size(eyeW, eyeH),
                    )
                    drawOval(
                        color = faceInk,
                        topLeft = Offset(rightEyeCx - eyeW / 2, eyeY - eyeH / 2),
                        size = Size(eyeW, eyeH),
                    )

                    // 笑嘴 —— 三段 cubic Bezier 模拟手绘波浪
                    val mouthY = h * 0.62f
                    val xL = w * 0.20f
                    val xR = w * 0.80f
                    val smilePath = Path().apply {
                        moveTo(xL, mouthY + s * 0.02f)
                        cubicTo(
                            w * 0.30f, mouthY + s * 0.13f,
                            w * 0.42f, mouthY + s * 0.16f,
                            w * 0.54f, mouthY + s * 0.07f,
                        )
                        cubicTo(
                            w * 0.62f, mouthY - s * 0.01f,
                            w * 0.72f, mouthY - s * 0.04f,
                            xR, mouthY + s * 0.01f,
                        )
                    }
                    drawPath(
                        path = smilePath,
                        color = faceInk,
                        style = Stroke(
                            width = s * 0.075f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

// ---------- 每日招呼持久化 ----------

private const val GREET_PREFS = "claudio_pet"
private const val GREET_KEY = "last_greet_date"

private fun shouldGreetToday(context: Context): Boolean {
    val prefs = context.getSharedPreferences(GREET_PREFS, Context.MODE_PRIVATE)
    val today = LocalDate.now().toString()
    return prefs.getString(GREET_KEY, null) != today
}

private fun markGreeted(context: Context) {
    val prefs = context.getSharedPreferences(GREET_PREFS, Context.MODE_PRIVATE)
    prefs.edit().putString(GREET_KEY, LocalDate.now().toString()).apply()
}
