package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona

interface PersonaActionCopywriter {
    suspend fun write(facts: ReplyFacts, persona: PetPersona): String
}

class TemplatePersonaActionCopywriter(
    private val templates: GroundedReplyTemplates = GroundedReplyTemplates(),
) : PersonaActionCopywriter {
    override suspend fun write(facts: ReplyFacts, persona: PetPersona): String =
        templates.grounded(facts, persona)
}
