package app.pipo.nativeapp.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * 响度对齐增益处理器 —— 挂在 ExoPlayer 的 audio 渲染链上,按 [PlaybackGain] 的当前增益缩放 PCM。
 *
 * 衰减式(gain ≤ 1),从不放大,因此不需要 limiter、不会削顶。gain≈1 时整块透传,零额外成本。
 * 支持 16-bit / 24-bit / 32-bit float —— ExoPlayer 解码后常见的三种主格式;其它格式返回
 * NOT_SET 让 Media3 跳过本层(绝不因某首歌的输出格式把播放链带崩)。
 *
 * 与 [AmpAudioProcessor] 并列在链上、职责分离:Amp 只旁路读 RMS 驱动视觉,本处理器只缩放输出。
 */
@UnstableApi
class LoudnessGainProcessor(
    private val gain: PlaybackGain,
) : BaseAudioProcessor() {
    private var encoding: Int = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_FLOAT -> {
                encoding = inputAudioFormat.encoding
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        if (size <= 0) return
        val g = gain.gainLinear
        val out = replaceOutputBuffer(size)
        out.order(inputBuffer.order())
        if (g >= 0.999f) {
            // 无衰减:整块透传
            inputBuffer.position(pos)
            out.put(inputBuffer)
        } else {
            scale(inputBuffer, out, limit, g)
        }
        out.flip()
        inputBuffer.position(limit)
    }

    private fun scale(input: ByteBuffer, out: ByteBuffer, limit: Int, g: Float) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (limit - input.position() >= 2) {
                    val v = input.short * g
                    out.putShort(v.coerceIn(-32768f, 32767f).toInt().toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (limit - input.position() >= 4) {
                    out.putFloat((input.float * g).coerceIn(-1f, 1f))
                }
            }
            C.ENCODING_PCM_24BIT -> {
                // 24-bit signed little-endian(与 AmpAudioProcessor.rms24 一致)
                while (limit - input.position() >= 3) {
                    val b0 = input.get().toInt() and 0xFF
                    val b1 = input.get().toInt() and 0xFF
                    val b2 = input.get().toInt()
                    var s = (b2 shl 16) or (b1 shl 8) or b0
                    if ((s and 0x800000) != 0) s = s or -0x1000000
                    val scaled = (s * g).coerceIn(-8388608f, 8388607f).toInt()
                    out.put((scaled and 0xFF).toByte())
                    out.put(((scaled ushr 8) and 0xFF).toByte())
                    out.put(((scaled ushr 16) and 0xFF).toByte())
                }
            }
        }
    }
}
