package app.pipo.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.pipo.nativeapp.runtime.Amp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * DotField v4 —— 整页游动的粒子流。
 * 与 src/components/DotField.tsx 一一对应：
 *
 *   - 300 颗粒子撒满视口
 *   - 粒径 power-law（u^3 让多数粒子小、少数大）
 *   - 2D flow field：三层不同空间频率 + 时间频率的 sin/cos 叠加
 *   - 边缘 vignette 60dp 衰减
 *   - amp 推流速 (1 + amp*1.8)、半径 (1 + amp*0.4)、亮度 (baseAlpha + amp*0.3)
 *   - playing=false → fade-out 后停渲染
 */
@Composable
fun DotField(
    playing: Boolean,
    color: Color = PipoColors.Ink,
    count: Int = 300,
    modifier: Modifier = Modifier,
) {
    val amp by Amp.flow.collectAsState()

    // 当前 canvas 尺寸（px）
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 粒子数组：尺寸首次 > 0 时初始化，之后即使 resize 也不重生成（粒子会自然漂到新区域）
    val particles = remember { ParticleSet(count) }

    // 帧驱动状态
    var visAlpha by remember { mutableStateOf(0f) }
    var smoothAmp by remember { mutableStateOf(0f) }
    var t by remember { mutableStateOf(0f) }

    LaunchedEffect(canvasSize, playing) {
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        particles.ensure(canvasSize.width.toFloat(), canvasSize.height.toFloat())

        var lastNs = 0L
        while (true) {
            withFrameNanos { ns ->
                val dt = if (lastNs == 0L) 0.016f else min(0.05f, (ns - lastNs) / 1_000_000_000f)
                lastNs = ns
                t += dt

                val target = if (playing) 1f else 0f
                visAlpha += (target - visAlpha) * min(1f, dt * 4f)

                val rawAmp = amp
                smoothAmp += (rawAmp - smoothAmp) * min(1f, dt * 7f)

                if (visAlpha >= 0.005f && canvasSize.width > 0 && canvasSize.height > 0) {
                    particles.tick(
                        dt = dt,
                        t = t,
                        amp = smoothAmp,
                        width = canvasSize.width.toFloat(),
                        height = canvasSize.height.toFloat(),
                    )
                }
            }
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { canvasSize = it },
    ) {
        if (visAlpha < 0.005f) return@Canvas
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@Canvas

        val baseAlpha = 0.42f
        val amplitude = smoothAmp
        val radiusMul = 1f + amplitude * 0.4f

        val feather = 60f
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()

        for (i in 0 until particles.size) {
            val px = particles.x[i]
            val py = particles.y[i]

            // 边缘 vignette
            val edgeFx = min(px, w - px)
            val edgeFy = min(py, h - py)
            val edgeF = min(edgeFx, edgeFy)
            val edgeAlpha = if (edgeF >= feather) 1f else max(0f, edgeF / feather)
            if (edgeAlpha < 0.02f) continue

            val r = particles.baseR[i] * radiusMul
            if (r < 0.2f) continue

            val a = visAlpha * edgeAlpha * (baseAlpha + amplitude * 0.3f)
            drawCircle(
                color = color.copy(alpha = a.coerceIn(0f, 1f)),
                radius = r,
                center = Offset(px, py),
            )
        }
    }
}

/** 粒子状态 —— 拆成并列数组便于热路径里少分配。 */
private class ParticleSet(val capacity: Int) {
    var size: Int = 0
        private set
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val baseR = FloatArray(capacity)
    val speedMul = FloatArray(capacity)
    val phase = FloatArray(capacity)

    private val rnd = Random(0x9be3c6)

    fun ensure(width: Float, height: Float) {
        if (size > 0 || width <= 0f || height <= 0f) return
        for (i in 0 until capacity) {
            val u = rnd.nextFloat()
            x[i] = rnd.nextFloat() * width
            y[i] = rnd.nextFloat() * height
            vx[i] = 0f
            vy[i] = 0f
            // 0.7..3.3 power-law
            baseR[i] = 0.7f + u * u * u * 2.6f
            speedMul[i] = 0.6f + rnd.nextFloat() * 0.8f
            phase[i] = (rnd.nextFloat() * (2 * PI)).toFloat()
        }
        size = capacity
    }

    fun tick(dt: Float, t: Float, amp: Float, width: Float, height: Float) {
        if (size == 0) return
        val baseSpeed = 22f * (1f + amp * 1.8f)
        val tA = t * 0.22f
        val tB = t * 0.18f
        val tC = t * 0.13f

        for (i in 0 until size) {
            val px = x[i]
            val py = y[i]

            // flow field 角度
            val angle = (sin(px * 0.0036f + tA + phase[i]) +
                cos(py * 0.0042f + tB) * 1.1f +
                sin((px + py) * 0.0021f + tC + phase[i] * 0.5f) * 0.8f)

            val targetSpeed = baseSpeed * speedMul[i]
            val targetVx = cos(angle) * targetSpeed
            val targetVy = sin(angle) * targetSpeed

            // 速度平滑：0.06 因子使 flow 角度突变不至于急转
            vx[i] += (targetVx - vx[i]) * 0.06f
            vy[i] += (targetVy - vy[i]) * 0.06f

            var nx = px + vx[i] * dt
            var ny = py + vy[i] * dt

            // wrap
            if (nx < -10f) nx = width + 10f
            else if (nx > width + 10f) nx = -10f
            if (ny < -10f) ny = height + 10f
            else if (ny > height + 10f) ny = -10f

            x[i] = nx
            y[i] = ny
        }
    }
}
