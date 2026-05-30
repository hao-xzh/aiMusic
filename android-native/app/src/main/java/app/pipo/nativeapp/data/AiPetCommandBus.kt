package app.pipo.nativeapp.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AiPetCommandBus {
    enum class Command { OpenChat }

    private val _commands = MutableSharedFlow<Command>(
        extraBufferCapacity = 1,
    )

    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /** AI 覆盖层是否展开 —— PipoNativeApp 读它给播放界面做唤起虚化(背景模糊)。 */
    val isOpen = MutableStateFlow(false)

    fun openChat() {
        _commands.tryEmit(Command.OpenChat)
    }
}
