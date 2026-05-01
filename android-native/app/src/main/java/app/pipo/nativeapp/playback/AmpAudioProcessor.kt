package app.pipo.nativeapp.playback

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.pipo.nativeapp.runtime.Amp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 镜像 src/lib/player-state.tsx 里 WebAudio AnalyserNode + getByteTimeDomainData 的真实
 * PCM RMS 计算 —— 数据从 ExoPlayer 的 audio 渲染链 tap 出来：
 *
 *   1. 拼接到 DefaultAudioSink 的 audioProcessors 链中（passthrough：原样转发输出）
 *   2. 每帧把 PCM 数据 RMS 算出来归一化到 0..1，写到全局 Amp.flow
 *   3. AiPet / DotField 等视觉组件读 Amp.flow 驱动节拍 / 流速 / 颗粒亮度
 *
 * 支持 16-bit PCM、24-bit PCM、32-bit float —— ExoPlayer 解码后的常见三种主格式。
 */
@UnstableApi
class AmpAudioProcessor : BaseAudioProcessor() {
    private var inputEncoding: Int = 0  // C.PCM_ENCODING_*
    private var inputChannelCount: Int = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 非 PCM 不接管 —— 抛 UnhandledAudioFormatException 让 sink 跳过这层处理器
        when (inputAudioFormat.encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT,
            androidx.media3.common.C.ENCODING_PCM_24BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> Unit
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputEncoding = inputAudioFormat.encoding
        inputChannelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()

        val rms = computeRms(inputBuffer, pos, limit)
        // React 端 baseAlpha 0.42 + amp 0.3 系数，amp 大致在 0..1
        // 这里直接把 RMS 归一化到 0..1 写进去，AiPet / DotField 自己再二阶平滑
        Amp.set(rms.coerceIn(0f, 1f))

        // 把数据原样拷给输出（让音频继续往下游 sink 流）
        val out = replaceOutputBuffer(limit - pos)
        inputBuffer.position(pos)
        out.put(inputBuffer)
        out.flip()
        inputBuffer.position(limit)
    }

    private fun computeRms(buf: ByteBuffer, start: Int, end: Int): Float {
        // 限制最大采样数，避免极大 buffer 拖慢主线程外的解码线程
        val maxSamples = 4096
        return when (inputEncoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> rms16(buf, start, end, maxSamples)
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> rmsFloat(buf, start, end, maxSamples)
            androidx.media3.common.C.ENCODING_PCM_24BIT -> rms24(buf, start, end, maxSamples)
            else -> 0f
        }
    }

    private fun rms16(buf: ByteBuffer, start: Int, end: Int, maxSamples: Int): Float {
        val total = (end - start) / 2
        if (total <= 0) return 0f
        val step = ((total + maxSamples - 1) / maxSamples).coerceAtLeast(1)
        val order = if (buf.order() == ByteOrder.LITTLE_ENDIAN) 1 else -1
        var sum = 0.0
        var n = 0
        var i = start
        while (i + 1 < end) {
            val lo = buf.get(i).toInt() and 0xFF
            val hi = buf.get(i + 1).toInt()
            val s = if (order == 1) (hi shl 8) or lo else (lo shl 8) or (hi and 0xFF)
            val sample = s.toShort().toInt().toFloat() / 32768f
            sum += (sample * sample).toDouble()
            n++
            i += 2 * step
        }
        if (n == 0) return 0f
        return sqrt(sum / n).toFloat()
    }

    private fun rmsFloat(buf: ByteBuffer, start: Int, end: Int, maxSamples: Int): Float {
        val total = (end - start) / 4
        if (total <= 0) return 0f
        val step = ((total + maxSamples - 1) / maxSamples).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var i = start
        while (i + 3 < end) {
            val sample = buf.getFloat(i)
            sum += (sample * sample).toDouble()
            n++
            i += 4 * step
        }
        if (n == 0) return 0f
        return sqrt(sum / n).toFloat()
    }

    private fun rms24(buf: ByteBuffer, start: Int, end: Int, maxSamples: Int): Float {
        val total = (end - start) / 3
        if (total <= 0) return 0f
        val step = ((total + maxSamples - 1) / maxSamples).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var i = start
        while (i + 2 < end) {
            val b0 = buf.get(i).toInt() and 0xFF
            val b1 = buf.get(i + 1).toInt() and 0xFF
            val b2 = buf.get(i + 2).toInt()
            // 24-bit signed little-endian
            var s = (b2 shl 16) or (b1 shl 8) or b0
            if ((s and 0x800000) != 0) s = s or 0xFF000000.toInt()
            val sample = s.toFloat() / 8388608f
            sum += (sample * sample).toDouble()
            n++
            i += 3 * step
        }
        if (n == 0) return 0f
        return sqrt(sum / n).toFloat()
    }
}
