package app.pipo.nativeapp.data.agent.reply

import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.domain.ActionExecutionResult
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.QueueValidation
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

data class ReplyFacts(
    val userText: String,
    val actionType: String,
    val success: Boolean,
    val personaId: String,
    val firstTrackTitle: String = "",
    val firstTrackArtist: String = "",
    val queueCount: Int = 0,
    val changedTrackCount: Int = 0,
    val changedTracks: List<NativeTrack> = emptyList(),
    val requiredArtist: String = "",
    val artistScope: String = "",
    val includedTitles: List<String> = emptyList(),
    val closerTitle: String = "",
    val reorderedForSeamless: Boolean = false,
    val mixMode: String = "",
    val transitionFeel: String = "",
    val insertedTitle: String = "",
    val insertedArtist: String = "",
    val likedTitle: String = "",
    val playlistName: String = "",
    val acceptedByPlayer: Boolean = false,
    val actuallyStarted: Boolean = false,
    val preservedCurrent: Boolean = false,
    val validationPassed: Boolean = true,
    val warnings: List<String> = emptyList(),
    val errorMessage: String = "",
    val resultMessage: String = "",
    val successShownByCard: Boolean = false,
)

object ReplyFactsBuilder {
    fun from(
        plan: MusicTurnPlan,
        validation: QueueValidation,
        results: List<ActionExecutionResult>,
        persona: PetPersona,
    ): ReplyFacts {
        val primaryPlay = plan.actions.filterIsInstance<PlannedAction.PlayTracks>().firstOrNull()
        val primaryGoal = primaryPlay?.primaryGoal
        val firstResult = results.firstOrNull()
        val failed = results.firstOrNull { !it.success }
        val queue = firstResult?.queueSnapshot?.takeIf { it.isNotEmpty() } ?: firstResult?.tracks.orEmpty()
        val actionType = when {
            firstResult != null -> firstResult.type
            primaryPlay?.mode == PlayMode.InsertNext -> "insert_next"
            primaryPlay?.mode == PlayMode.PlayNow -> "play_now"
            primaryPlay?.mode == PlayMode.ReplaceQueue -> "replace_queue"
            else -> plan.actions.firstOrNull()?.javaClass?.simpleName.orEmpty()
        }
        // `queueSnapshot` 是操作后的完整播放队列；插播时它也包含此前就在队列里的歌。
        // 本次真正写入/播放的曲目以 action result 的 tracks 为准，不能拿完整队列冒充插入数量。
        val changedTracks = firstResult?.tracks.orEmpty().ifEmpty {
            firstResult?.insertedTrack?.let(::listOf).orEmpty()
        }
        val responseTracks = if (actionType == "insert_next") changedTracks else queue.ifEmpty { changedTracks }
        val firstTrack = responseTracks.firstOrNull()
        val summary = firstResult?.committedQueueSummary
        val requiredArtist = primaryGoal?.primaryArtists
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?: CommandTextSignals.primaryArtistHints(plan.userText).joinToString("、")
        val artistScope = primaryGoal?.artistScope?.name
            ?: CommandTextSignals.artistScope(plan.userText).name
        val warnings = (validation.messages + results.flatMap { it.warnings }).distinct()
        val validationPassed = validation.passed && (summary?.validationPassed ?: true)
        return ReplyFacts(
            userText = plan.userText,
            actionType = actionType,
            success = results.all { it.success } && failed == null && validationPassed,
            personaId = persona.id,
            firstTrackTitle = firstTrack?.title.orEmpty(),
            firstTrackArtist = firstTrack?.artist.orEmpty(),
            queueCount = queue.size,
            changedTrackCount = changedTracks.size,
            changedTracks = changedTracks,
            requiredArtist = requiredArtist,
            artistScope = artistScope,
            includedTitles = (queue + changedTracks).map { it.title }.filter { it.isNotBlank() }.distinct(),
            closerTitle = primaryGoal?.closer?.title.orEmpty().takeIf { validation.closerSatisfied }.orEmpty(),
            reorderedForSeamless = summary?.reordered == true,
            mixMode = summary?.mixMode.orEmpty(),
            transitionFeel = summary?.transitionFeel.orEmpty(),
            insertedTitle = firstResult?.insertedTrack?.title.orEmpty(),
            insertedArtist = firstResult?.insertedTrack?.artist.orEmpty(),
            likedTitle = firstResult?.likedTrack?.title.orEmpty(),
            playlistName = firstResult?.playlistName.orEmpty(),
            acceptedByPlayer = results.any { it.acceptedByPlayer },
            actuallyStarted = results.any { it.actuallyStarted },
            preservedCurrent = primaryPlay?.preserveCurrent == true,
            validationPassed = validationPassed,
            warnings = warnings,
            errorMessage = failed?.errorMessage ?: failed?.message.orEmpty(),
            resultMessage = firstResult?.message.orEmpty(),
        )
    }
}
