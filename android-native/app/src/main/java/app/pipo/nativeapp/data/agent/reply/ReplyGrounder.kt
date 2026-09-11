package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import kotlinx.coroutines.CancellationException

class ReplyGrounder(
    private val verifier: ReplyVerifier = ReplyVerifier(),
    private val templates: GroundedReplyTemplates = GroundedReplyTemplates(),
    private val copywriter: PersonaActionCopywriter? = TemplatePersonaActionCopywriter(templates),
) {
    companion object {
        private const val NO_PLAYABLE_SOURCE_MESSAGE = "很抱歉，没有找到可播放的音源。"

        /**
         * 让回复由 LLM 按人格现写（不再用模板）：[aiChat] 通常接 `repository.aiChat`。
         * 仍走 [ReplyVerifier] 校验；成功卡片允许静默，其余结果保留模板兜底。
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
        successShownByCard: Boolean = false,
    ): String {
        if (plan.actions.size == 1 && plan.actions.first() is PlannedAction.Say) {
            return (plan.actions.first() as PlannedAction.Say).text.ifBlank { plan.replyHint }.ifBlank { "嗯。" }.take(420)
        }
        if (plan.actions.size == 1 && plan.actions.first() is PlannedAction.Clarify) {
            return (plan.actions.first() as PlannedAction.Clarify).question.ifBlank { "你想听哪首？" }.take(420)
        }
        val executionFacts = ReplyFactsBuilder.from(plan, validation, results, persona)
        val facts = executionFacts.copy(
            successShownByCard = successShownByCard && executionFacts.success && executionFacts.warnings.isEmpty(),
        )
        if (!facts.success && facts.errorMessage.contains(NO_PLAYABLE_SOURCE_MESSAGE)) {
            logPersonaReply(persona, facts, NO_PLAYABLE_SOURCE_MESSAGE, ReplyDraftSource.FALLBACK, verified = true)
            return NO_PLAYABLE_SOURCE_MESSAGE
        }
        val draft = try {
            copywriter?.write(facts, persona)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: ReplyDraft(
            if (facts.successShownByCard) "" else templates.grounded(facts, persona),
            ReplyDraftSource.TEMPLATE,
        )
        if (facts.successShownByCard && draft.text.isBlank()) {
            logPersonaReply(persona, facts, "", draft.source, verified = true)
            return ""
        }
        val verified = verifier.verify(draft.text, results, facts)
        if (verified) {
            logPersonaReply(persona, facts, draft.text, draft.source, verified = true)
            return draft.text.take(420)
        }
        if (facts.successShownByCard) {
            logPersonaReply(persona, facts, "", ReplyDraftSource.FALLBACK, verified = false)
            return ""
        }
        val strictDraft = templates.grounded(facts, persona)
        if (verifier.verify(strictDraft, results, facts)) {
            logPersonaReply(persona, facts, strictDraft, ReplyDraftSource.TEMPLATE, verified = true)
            return strictDraft.take(420)
        }
        val fallback = templates.safeFallback(facts, persona)
        logPersonaReply(persona, facts, fallback, ReplyDraftSource.FALLBACK, verified = false)
        return fallback.take(420)
    }

    private fun logPersonaReply(
        persona: PetPersona,
        facts: ReplyFacts,
        reply: String,
        source: ReplyDraftSource,
        verified: Boolean,
    ) {
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "persona_reply",
            fields = mapOf(
                "persona" to persona.id,
                "actionType" to facts.actionType,
                "success" to facts.success,
                "replySource" to source.logValue,
                "verified" to verified,
                "reply" to reply.take(120),
            ),
        )
    }
}
