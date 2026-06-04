package app.pipo.nativeapp.data.agent.intent

import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals
import app.pipo.nativeapp.data.agent.session.SessionMutation

object IntentReducer {
    fun mutationFor(plan: MusicTurnPlan): SessionMutation {
        val text = plan.userText
        if (CommandTextSignals.disableContinuation(text)) return SessionMutation.PauseCurrentSession
        val modes = plan.actions.mapNotNull {
            when (it) {
                is PlannedAction.PlayRequest -> it.mode
                is PlannedAction.PlayTracks -> it.mode
                else -> null
            }
        }
        if (modes.any { it == PlayMode.InsertNext }) {
            return SessionMutation.KeepCurrentSession
        }
        if (modes.any { it == PlayMode.PreserveCurrentThenReplace }) {
            return SessionMutation.CreateNewSession
        }
        if (modes.any { it == PlayMode.ReplaceQueue || it == PlayMode.PlayNow }) {
            return SessionMutation.CreateNewSession
        }
        return SessionMutation.None
    }
}
