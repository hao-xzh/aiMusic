package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona

object PersonaReplyPrompt {
    fun actionPrefix(persona: PetPersona): String =
        when (persona) {
            PetPersona.TOXIC -> "行，接上。"
            PetPersona.FRIENDLY -> "好，接上。"
            PetPersona.COLD -> "接上。"
            PetPersona.KITTY -> "接上喵。"
            PetPersona.JIANGHU -> "走着，接上。"
        }
}
