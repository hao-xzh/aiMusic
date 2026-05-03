package app.pipo.nativeapp.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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

        // ---- 助手回复浮气泡：贴在底部输入条正上方，6s 自动消散，新回复来覆盖旧的 ----
        // 不遮播放器，跟系统通知一样飘在角上
        AnimatedVisibility(
            visible = open && latestReply != null,
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
                // 输入条高 ~52dp + nav bar inset + 输入条与气泡间距 8dp
                .padding(bottom = 70.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            ReplyBubble(text = latestReply.orEmpty(), tint = rgbToColor(useCoverEdgeColors(coverUrl).right, fallback = PipoColors.Mint))
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
                coverUrl = coverUrl,
                input = input,
                pending = pending,
                hintText = if (messages.isEmpty()) emptyHint else "说点什么…",
                onInputChange = { input = it },
                onClose = { open = false },
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty()) return@PetCommandBar
                    input = ""
                    messages += PetMessage(fromUser = true, text = text)
                    PetBubbleState.lastUserContext = text
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
                        latestReply = response.reply
                        if (response.action == app.pipo.nativeapp.data.PetAgent.Action.Play &&
                            response.initialBatch.isNotEmpty()) {
                            onPlayFromAgent(response.initialBatch, response.continuous)
                        }
                        pending = false
                    }
                },
            )
        }

        // ---- Hint 气泡：仍贴 orb（panel 关闭状态下才显示）----
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

        // ---- Orb：常驻挂在封面右下，是命令条的开关 ----
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
    // 跟新面板同语言：玻璃 + 顶沿微亮 + 一根头发丝边框（不再用扁平深色块）
    Box(
        modifier = Modifier
            .padding(bottom = 8.dp, end = 4.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC0A0D14))
            .drawBehind {
                // 头发丝顶沿高光
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x18FFFFFF), Color.Transparent),
                        startY = 0f, endY = 18f,
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = PipoColors.Ink,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
        )
    }
}

/**
 * 助手回复浮气泡 —— 跟系统通知同语言：贴底部输入条上方，6s 自动消散，新回复覆盖旧的。
 * 玻璃 + 顶沿微亮 + 封面色边线（让"是谁说的"和音乐有关联，不像系统通知那么冷）。
 */
@Composable
private fun ReplyBubble(text: String, tint: Color) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE60A0D14))
            .drawBehind {
                // 顶沿 + 左边沿一道封面色光，让气泡看起来"是从音乐里说出来的"
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(tint.copy(alpha = 0.22f), Color.Transparent),
                        startY = 0f, endY = 24f,
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = text,
            color = Color(0xF2F5F7FF),
            style = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.15.sp,
            ),
        )
    }
}

/**
 * 底部命令条 —— 小爱同学风：横贯屏宽 + 钉到底部，只有一根胶囊。
 *
 * 不再是覆盖整屏的对话框 —— 播放器永远露脸，不被遮。所以：
 *   - 高 ~52dp，左右各 12dp 屏边距，顶/底圆角全 999dp 胶囊
 *   - 容器：玻璃黑 + 封面色描边 + 头发丝顶沿光
 *   - 内布局：[Pipo 小笑脸 24dp] [输入框 weight=1] [发送 32dp] [关闭 ×]
 *   - 输入条本身永远不滚动消息列表 —— 历史回复在上方做浮气泡，6s 自动消散
 *
 * 这种形态的好处：拇指原生区、不打断音乐 / 歌词、跟手机系统输入条同高度，符合用户心智。
 */
@Composable
private fun PetCommandBar(
    coverUrl: String?,
    input: String,
    pending: Boolean,
    hintText: String,
    onInputChange: (String) -> Unit,
    onClose: () -> Unit,
    onSend: () -> Unit,
) {
    val edges = useCoverEdgeColors(coverUrl)
    val tintColor = rgbToColor(edges.right, fallback = PipoColors.Mint)
    val tintTop = rgbToColor(edges.top, fallback = PipoColors.Mint)
    val canSend = input.isNotBlank() && !pending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE60A0D14))
            // 容器内浅光 + 封面色微辉，做出"小爱那种鲜活胶囊"的感觉，但克制 ——
            // 不抢主屏，也跟整体玻璃语言对齐
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            tintTop.copy(alpha = 0.14f),
                            Color.Transparent,
                            tintColor.copy(alpha = 0.14f),
                        ),
                    ),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x1FFFFFFF), Color.Transparent),
                        startY = 0f, endY = 16f,
                    ),
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 左侧 Pipo 小笑脸（视觉锚点，告诉用户这是 AI 在听）----
        PetFaceMini(
            modifier = Modifier.size(32.dp),
            pending = pending,
        )

        // ---- 输入框 ----
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = PipoColors.Ink,
                    fontSize = 14.sp,
                    letterSpacing = 0.15.sp,
                ),
                cursorBrush = SolidColor(tintColor.copy(alpha = 0.85f)),
                // 软键盘右下角显示"发送"按钮，按下 = 直接 send
                // 物理键盘的 Enter 由 singleLine=true 自动转成 IME action
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (input.isNotBlank() && !pending) onSend() },
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) {
                        Text(
                            text = hintText,
                            color = Color(0x80FFFFFF),
                            style = TextStyle(
                                fontSize = 14.sp,
                                letterSpacing = 0.15.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )
        }

        // ---- 发送钮（封面色填充） ----
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) tintColor.copy(alpha = 0.85f)
                    else Color(0x14FFFFFF),
                )
                .clickable(
                    enabled = canSend,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSend,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "发送",
                tint = if (canSend) Color(0xFF0A0D14) else Color(0x99FFFFFF),
                modifier = Modifier.size(18.dp),
            )
        }

        // ---- 收起 ×（拇指可达；orb 也能再点一下收起） ----
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "收起",
                tint = Color(0x99FFFFFF),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 命令条左侧的 Pipo 小笑脸 —— 简化版的 PetOrb，没有绳子、没有摆动、没有 halo。
 * 静态笑脸：奶白底 + 黑眼 + 笑嘴。pending 时眼睛眨一下（待办）。
 */
@Composable
private fun PetFaceMini(modifier: Modifier = Modifier, pending: Boolean = false) {
    val faceCream = Color(0xFFE0D9C4)
    val faceInk = Color(0xFF1B1815)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(faceCream),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val s = size.minDimension
            val eyeW = s * 0.10f
            val eyeH = if (pending) s * 0.04f else s * 0.16f
            val eyeY = h * 0.38f
            drawOval(
                color = faceInk,
                topLeft = Offset(w * 0.34f - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH),
            )
            drawOval(
                color = faceInk,
                topLeft = Offset(w * 0.66f - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH),
            )
            val mouthY = h * 0.62f
            val xL = w * 0.22f
            val xR = w * 0.78f
            val smile = Path().apply {
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
                path = smile,
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
