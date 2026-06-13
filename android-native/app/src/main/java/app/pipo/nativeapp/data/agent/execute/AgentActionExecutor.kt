package app.pipo.nativeapp.data.agent.execute

import app.pipo.nativeapp.data.ContinuousQueueSource
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement

interface AgentActionExecutor {
    suspend fun playQueue(
        actionId: String,
        mode: PlayMode,
        tracks: List<NativeTrack>,
        continuous: ContinuousQueueSource?,
        primaryGoal: MusicGoal,
        target: TrackRequirement?,
        similar: Boolean,
    ): ActionExecutionResult

    /** 插到当前歌后面；tracks 支持整批（“这首听完放 X 的歌/下一首开始听 Y”），保持批内顺序。 */
    suspend fun insertNext(
        actionId: String,
        tracks: List<NativeTrack>,
        jumpToInserted: Boolean,
    ): ActionExecutionResult

    suspend fun skip(actionId: String): ActionExecutionResult

    suspend fun likeCurrent(actionId: String, like: Boolean): ActionExecutionResult

    suspend fun likeTrack(
        actionId: String,
        like: Boolean,
        target: TrackRequirement,
    ): ActionExecutionResult

    suspend fun modifyPlaylist(
        actionId: String,
        add: Boolean,
        playlistName: String,
    ): ActionExecutionResult
}
