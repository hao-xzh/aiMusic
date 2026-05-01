package app.pipo.nativeapp.runtime

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局信号源 —— 对应 React 端 `window.__claudioAmp`。
 *
 * - PlayerViewModel / 任意采样源往里写当前 RMS（0..1）。
 * - DotField / AiPet 等视觉组件读它驱动流速、半径、亮度、节拍弹跳。
 *
 * 没有正在播放或 ExoPlayer 没暴露 PCM 时，我们退化成"isPlaying ? 0.45 : 0.0"
 * 的固定底，让背景仍然有缓慢呼吸感而不是死寂。后续 Slice 5 接 ExoPlayer
 * AudioProcessor 取真实 PCM 后再换成实时 RMS。
 */
object Amp {
    val flow = MutableStateFlow(0f)

    fun set(value: Float) {
        flow.value = value.coerceIn(0f, 1f)
    }
}
