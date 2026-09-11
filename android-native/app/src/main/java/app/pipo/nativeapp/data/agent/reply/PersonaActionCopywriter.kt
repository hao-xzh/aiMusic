package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona

enum class ReplyDraftSource(val logValue: String) {
    LLM("llm"),
    TEMPLATE("template"),
    FALLBACK("fallback"),
}

data class ReplyDraft(
    val text: String,
    val source: ReplyDraftSource,
)

interface PersonaActionCopywriter {
    suspend fun write(facts: ReplyFacts, persona: PetPersona): ReplyDraft
}

class TemplatePersonaActionCopywriter(
    private val templates: GroundedReplyTemplates = GroundedReplyTemplates(),
) : PersonaActionCopywriter {
    override suspend fun write(facts: ReplyFacts, persona: PetPersona): ReplyDraft =
        ReplyDraft(if (facts.successShownByCard) "" else templates.grounded(facts, persona), ReplyDraftSource.TEMPLATE)
}
