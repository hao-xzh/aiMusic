package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.QueueValidation

class ReplyGrounder(
    private val verifier: ReplyVerifier = ReplyVerifier(),
    private val templates: GroundedReplyTemplates = GroundedReplyTemplates(),
    private val copywriter: PersonaActionCopywriter? = TemplatePersonaActionCopywriter(templates),
) {
    companion object {
        private const val NO_PLAYABLE_SOURCE_MESSAGE = "很抱歉，没有找到可播放的音源。"

        /**
         * 让回复由 LLM 按人格现写（不再用模板）：[aiChat] 通常接 `repository.aiChat`。
         * 仍走 [ReplyVerifier] 校验 + 模板兜底，言行一致不打折。
         */
        fun withLlmCopywriter(aiChat: suspend (system: String, user: String) -> String): ReplyGrounder {
            val templates = GroundedReplyTemplates()
            return ReplyGrounder(
                templates = templates,
                copywriter = LlmPersonaActionCopywriter(aiChat, templates),
            )
        }
    }

    suspend fun ground(
        plan: MusicTurnPlan,
        validation: QueueValidation,
        results: List<ActionExecutionResult>,
        persona: PetPersona,
    ): String {
        if (plan.actions.size == 1 && plan.actions.first() is PlannedAction.Say) {
            return (plan.actions.first() as PlannedAction.Say).text.ifBlank { plan.replyHint }.ifBlank { "嗯。" }.take(420)
        }
        if (plan.actions.size == 1 && plan.actions.first() is PlannedAction.Clarify) {
            return (plan.actions.first() as PlannedAction.Clarify).question.ifBlank { "你想听哪首？" }.take(420)
        }
        val facts = ReplyFactsBuilder.from(plan, validation, results, persona)
        if (!facts.success && facts.errorMessage.contains(NO_PLAYABLE_SOURCE_MESSAGE)) {
            logPersonaReply(persona, facts, NO_PLAYABLE_SOURCE_MESSAGE, usedCopywriter = false, verified = true)
            return NO_PLAYABLE_SOURCE_MESSAGE
        }
        val drafted = runCatching { copywriter?.write(facts, persona) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: templates.grounded(facts, persona)
        val verified = verifier.verify(drafted, results, facts)
        if (verified) {
            logPersonaReply(persona, facts, drafted, usedCopywriter = copywriter != null, verified = true)
            return drafted.take(420)
        }
        val strictDraft = templates.grounded(facts, persona)
        if (verifier.verify(strictDraft, results, facts)) {
            logPersonaReply(persona, facts, strictDraft, usedCopywriter = false, verified = true)
            return strictDraft.take(420)
        }
        val fallback = templates.safeFallback(facts, persona)
        logPersonaReply(persona, facts, fallback, usedCopywriter = false, verified = false)
        return fallback.take(420)
    }

    private fun logPersonaReply(
        persona: PetPersona,
        facts: ReplyFacts,
        reply: String,
        usedCopywriter: Boolean,
        verified: Boolean,
    ) {
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "persona_reply",
            fields = mapOf(
                "persona" to persona.id,
                "actionType" to facts.actionType,
                "success" to facts.success,
                "usedCopywriter" to usedCopywriter,
                "verified" to verified,
                "reply" to reply.take(120),
            ),
        )
    }
}
