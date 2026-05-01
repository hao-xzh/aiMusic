package app.pipo.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 跨组件共享的封面 viewport rect。镜像 React 端 AiPet 用
 *   document.querySelector("[data-claudio-cover]").getBoundingClientRect()
 * 拿到的封面位置 —— 让浮动 AiPet 能"挂在封面右下角内侧"。
 *
 * 用法：
 *   - PlayerScreen 内的 compact 封面 Box 上挂 `Modifier.reportCoverRect()` →
 *     CoverRectHolder.value 自动更新
 *   - AiPet 通过 LocalCoverAnchor.current.value 读取，没值时退化为屏幕右下角 free 模式
 */
data class CoverRect(val rect: Rect?, val playerRoot: Boolean)

class CoverAnchorState {
    val state: MutableState<CoverRect> = mutableStateOf(CoverRect(rect = null, playerRoot = false))
}

val LocalCoverAnchor = compositionLocalOf<CoverAnchorState> { CoverAnchorState() }

@Composable
fun rememberCoverAnchorState(): CoverAnchorState = remember { CoverAnchorState() }

/** 给 compact cover 用：每帧把它在 window 里的 rect 写到 LocalCoverAnchor */
@Composable
fun Modifier.reportCoverRect(): Modifier {
    val anchor = LocalCoverAnchor.current
    return this.then(
        Modifier.onGloballyPositioned { layoutCoordinates ->
            anchor.state.value = CoverRect(
                rect = layoutCoordinates.boundsInWindow(),
                playerRoot = true,
            )
        },
    )
}

/** PlayerScreen 进入沉浸式或被覆盖时调，通知 AiPet 退到 free 模式 */
fun CoverAnchorState.releaseCoverRect() {
    state.value = state.value.copy(rect = null, playerRoot = false)
}
