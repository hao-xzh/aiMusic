package app.pipo.nativeapp.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AiPetCommandBus {
    enum class Command { OpenChat }

    private val _commands = MutableSharedFlow<Command>(
        extraBufferCapacity = 1,
    )

    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun openChat() {
        _commands.tryEmit(Command.OpenChat)
    }
}
