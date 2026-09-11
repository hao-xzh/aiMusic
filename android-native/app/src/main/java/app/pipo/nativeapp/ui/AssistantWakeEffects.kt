package app.pipo.nativeapp.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

internal const val AssistantConversationFadeMillis = 800

internal class AssistantWakeEffect(
    val progress: State<Float>,
    val strength: State<Float>,
    val conversationReady: State<Boolean>,
)

/** 水波保持原速，0.65 秒后显示对话并用 0.9 秒淡出效果，随后截掉剩余动画。 */
@Composable
internal fun rememberAssistantWakeEffect(open: Boolean): AssistantWakeEffect {
    val progress = remember { Animatable(0f) }
    val strength = remember { Animatable(0f) }
    val conversationReady = remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        conversationReady.value = false
        if (open) {
            progress.snapTo(0f)
            coroutineScope {
                launch { strength.animateTo(1f, tween(420)) }
                val progressAnimation = launch {
                    progress.animateTo(0.68f, tween(1800, easing = LinearEasing))
                }
                delay(650)
                conversationReady.value = true
                strength.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
                progressAnimation.cancelAndJoin()
            }
        } else {
            if (strength.value > 0f) {
                strength.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
            }
            progress.snapTo(0f)
        }
    }
    return remember {
        AssistantWakeEffect(progress.asState(), strength.asState(), conversationReady)
    }
}

/** 只折射 AI 后面的真实播放页，消息、输入框和系统键盘不经过水面采样。 */
@Composable
internal fun Modifier.assistantWaterSurface(effect: AssistantWakeEffect): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { createAssistantShader(AssistantWaterShader) } ?: return this
    return graphicsLayer {
        val phase = effect.progress.value
        val strength = effect.strength.value
        renderEffect = if (strength > 0f && size.width > 0f && size.height > 0f) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("progress", phase)
            shader.setFloatUniform("intensity", strength)
            shader.setFloatUniform("pixelScale", density)
            // 淡出到零后再移除离屏效果，页面采样不会突然跳回原位。
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        } else null
    }
}

@Composable
internal fun AssistantWakeLight(
    progress: State<Float>,
    strength: State<Float>,
) {
    val visible by remember(strength) { derivedStateOf { strength.value > 0f } }
    if (!visible) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { createAssistantShader(AssistantLightShader) }
        if (shader != null) {
            val brush = remember(shader) { ShaderBrush(shader) }
            Canvas(Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("progress", progress.value)
                shader.setFloatUniform("intensity", strength.value)
                drawRect(brush)
            }
            return
        }
    }
    // Android 12 及以下没有 AGSL：保留连续柔光，不用线圈或分段描边冒充折射。
    val colors = remember { listOf(Color(0xFF74D6ED), Color(0xFFAE8FDF), Color(0xFFE39FBE), Color(0xFFE0C29C)) }
    Canvas(Modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val phase = progress.value
        val intensity = strength.value
        val spread = (phase / 0.18f).coerceIn(0f, 1f)
        colors.forEachIndexed { index, color ->
            val side = if (index % 2 == 0) 0.02f else 0.98f
            val center = Offset(
                size.width * (0.5f + (side - 0.5f) * spread + 0.025f * sin(phase * 4f + index)),
                size.height * (1.06f - 1.35f * (phase - 0.12f).coerceAtLeast(0f) + index * 0.06f),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.20f * intensity), Color.Transparent),
                    center = center, radius = size.width * 0.60f,
                ),
            )
        }
        drawRect(
            Brush.radialGradient(
                listOf(colors.first().copy(alpha = 0.06f * intensity), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 1.10f), radius = size.width * 0.80f,
            ),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createAssistantShader(source: String): RuntimeShader? = runCatching {
    RuntimeShader(source)
}.onFailure { error ->
    DiagnosticsLogStore.record(
        area = "ai_pet", event = "wake_shader_unavailable",
        fields = mapOf("message" to error.message.orEmpty()),
    )
}.getOrNull()

// 彩光先从底部中央向左右展开，再沿两侧上行；连续色场和柔光不依赖分段描边。
private const val AssistantLightShader = """
uniform float2 resolution;
uniform float progress;
uniform float intensity;

half4 main(float2 position) {
    float2 uv = position / resolution;
    float height = 1.0 - uv.y;
    float drift = 0.045 * sin(uv.y * 7.0 - progress * 5.0)
                + 0.025 * sin(uv.y * 13.0 + progress * 3.0);
    float flow = 0.16 * sin(uv.x * 6.0 + uv.y * 3.0 - progress * 4.0)
               + 0.09 * sin(uv.x * 9.0 - uv.y * 5.0 + progress * 2.0);
    float hue = uv.x * 0.54 + height * 0.16 + flow + progress * 0.12;
    float3 color = 0.52 + 0.40 * cos(6.2831853 * (hue + float3(0.0, 0.3333, 0.6667)));
    float travel = height + 0.24 * abs(uv.x - 0.5) * 2.0;
    float front = progress * 1.85;
    float revealed = 1.0 - smoothstep(front - 0.12, front + 0.14, travel);
    float edge = min(uv.x, 1.0 - uv.x);
    float width = 0.10 + 0.04 * sin(height * 5.0 + progress * 3.0);
    float halo = exp(-pow(edge / width, 2.0));
    float rim = exp(-edge / 0.022);
    float packet = exp(-pow((travel - front + 0.12 + drift) / 0.30, 2.0));
    float swirl = 0.5 + 0.5 * sin(uv.x * 4.0 + uv.y * 2.0 - progress * 1.5 + flow);
    float bottom = exp(-height / 0.10);
    float alpha = intensity * revealed
                * (0.16 * halo + 0.16 * rim
                   + 0.14 * packet * (0.20 + 0.80 * halo) * (0.55 + 0.45 * swirl)
                   + 0.10 * bottom);
    alpha = clamp(alpha, 0.0, 0.44);
    return half4(half3(color * alpha), half(alpha));
}
"""

// 水面是一个移动的宽波包：解析梯度生成表面法线，法线控制背景折射、明暗和镜面高光。
private const val AssistantWaterShader = """
uniform shader content;
uniform float2 resolution;
uniform float progress;
uniform float pixelScale;
uniform float intensity;

half4 main(float2 position) {
    float2 uv = position / resolution;
    float a = uv.x * 8.0 + progress * 3.0;
    float b = uv.x * 15.0 - progress * 2.0;
    float bend = 0.045 * sin(a) + 0.022 * sin(b);
    float slopeX = 0.36 * cos(a) + 0.33 * cos(b);
    float u = 1.0 - uv.y - progress * 1.45 + 0.10 + bend;
    float modulation = uv.x * 5.0 - progress * 4.0;
    float phase = u * 19.0 + 0.45 * sin(modulation);
    float envelope = exp(-u * u * 28.0);
    float wave = sin(phase);
    float crest = cos(phase);
    float dx = envelope * (crest * (19.0 * slopeX + 2.25 * cos(modulation))
                          - 56.0 * u * slopeX * wave);
    float dy = envelope * (-19.0 * crest + 56.0 * u * wave);
    float fade = smoothstep(0.0, 0.08, progress) * intensity;
    float3 normal = normalize(float3(-dx * 0.05, -dy * 0.045, 1.0));
    float2 offset = normal.xy * pixelScale * 8.0 * fade;
    float2 samplePosition = clamp(position + offset, float2(0.0), resolution - float2(1.0));
    half4 base = content.eval(samplePosition);
    float3 light = normalize(float3(-0.35, -0.55, 1.0));
    float specular = pow(max(dot(normal, light), 0.0), 24.0) * envelope * fade;
    float shadow = (1.0 - normal.z) * 0.10 * envelope * fade;
    float3 water = float3(base.rgb) * (1.0 - shadow)
                 + float3(0.65, 0.86, 1.0) * specular * 0.055 * float(base.a);
    return half4(half3(clamp(water, float3(0.0), float3(base.a))), base.a);
}
"""
