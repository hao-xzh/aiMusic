package app.pipo.nativeapp.data.agent.queue

import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.QueuePlan

class AgentQueuePlanner(
    private val placementEngine: PlacementEngine = PlacementEngine(),
    private val validator: QueueValidator = QueueValidator(),
) {
    fun plan(turnPlan: MusicTurnPlan): QueuePlan {
        val llmStructured = turnPlan.plannerRaw.startsWith("tool_loop")
        val actions = turnPlan.actions.map { action ->
            if (!llmStructured && action is PlannedAction.PlayTracks && action.mode == PlayMode.ReplaceQueue) {
                action.copy(tracks = placementEngine.reorderReplaceQueue(turnPlan.userText, action.tracks))
            } else {
                action
            }
        }
        val validation = if (llmStructured) {
            validator.validateStructured(actions)
        } else {
            validator.validate(turnPlan.userText, actions)
        }
        DiagnosticsLogStore.record(
            area = "ai_agent",
            event = "queue_plan",
            fields = mapOf(
                "turnId" to turnPlan.turnId,
                "actionCount" to actions.size,
                "validationPassed" to validation.passed,
                "validation" to validation.messages.joinToString("|").take(180),
            ),
        )
        return QueuePlan(actions = actions, validation = validation)
    }
}
