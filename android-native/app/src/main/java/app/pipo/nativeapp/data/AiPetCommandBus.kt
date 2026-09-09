package app.pipo.nativeapp.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

object AiPetCommandBus {
    enum class Command { OpenChat, OpenAssistant, RecommendMusic }

    // The player AI surface mounts after navigation; retain the shortcut until it subscribes.
    private val _commands = Channel<Command>(Channel.BUFFERED)

    val commands = _commands.receiveAsFlow()

    /** AI 覆盖层是否展开 —— PipoNativeApp 读它给播放界面做唤起虚化(背景模糊)。 */
    val isOpen = MutableStateFlow(false)

    fun openChat() {
        _commands.trySend(Command.OpenChat)
    }

    fun openAssistant() {
        _commands.trySend(Command.OpenAssistant)
    }

    fun recommendMusic() {
        _commands.trySend(Command.RecommendMusic)
    }
}
