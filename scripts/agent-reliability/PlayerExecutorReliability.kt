import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.execute.PlayerAgentExecutor
import app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlan
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext

/** Executes the production front-end executor; only media/network/UI boundaries are controlled. */
object PlayerExecutorReliability {
    private val old = NativeTrack("101", 101, "旧当前曲", "歌手A", "录音室", "fixture://101")
    private val next = NativeTrack("102", 102, "新播放曲", "歌手B", "录音室", "fixture://102")
    private val afterSkip = NativeTrack("103", 103, "跳过后歌曲", "歌手C", "录音室", "fixture://103")

    private class Fixture {
        var live = old
        var skipTarget = next
        var inPlayerDispatch = false
        var callbacksOnPlayerDispatch = true
        var rejectCommit = false
        val likes = mutableListOf<Long>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                inPlayerDispatch = true
                try { block.run() } finally { inPlayerDispatch = false }
            }
        }
        val repository = Proxy.newProxyInstance(PipoRepository::class.java.classLoader,
            arrayOf(PipoRepository::class.java)) { _, method, arguments ->
            when (method.name) {
                "likeSong" -> { likes.add((arguments!![0] as Number).toLong()); Unit }
                "toString" -> "PlayerExecutorFixtureRepository"
                "hashCode" -> 1
                "equals" -> false
                else -> error("Unexpected executor repository call: ${method.name}")
            }
        } as PipoRepository
        val executor = PlayerAgentExecutor(
            repository = repository,
            currentTrackProvider = { live },
            currentQueueProvider = { listOf(old, afterSkip) },
            sourceUserText = "播放并收藏",
            onApplyAgentQueueRequest = { request ->
                callbacksOnPlayerDispatch = callbacksOnPlayerDispatch && inPlayerDispatch
                if (rejectCommit) QueueCommitResult.Rejected(request, "fixture_rejection") else {
                    // Intentionally keep Compose's live provider stale until a later frame.
                    QueueCommitResult.Success(CommittedQueuePlan.snapshot(
                        sessionId = "fixture", queueVersion = 1, requestId = request.requestId,
                        operation = request.operation, sourceUserText = request.sourceUserText,
                        tracks = request.tracks, preserveCurrent = request.preserveCurrent,
                    ).copy(jumpToInserted = request.jumpToInserted))
                }
            },
            onSkip = { callbacksOnPlayerDispatch = callbacksOnPlayerDispatch && inPlayerDispatch; live = skipTarget },
            playerDispatcher = dispatcher,
            resolvePlayableTrack = { it },
        )
        suspend fun play(preserve: Boolean = false) = executor.playQueue("play", PlayMode.PlayNow,
            listOf(next), null, MusicGoal(), null, false, preserve)
    }

    suspend fun runCases(report: (String, String, Boolean, String, Any?) -> Unit) {
        Fixture().let { f ->
            val play = f.play()
            f.executor.likeCurrent("like", true)
            report("real_executor_play_then_like", "前台真实执行器：换歌后收藏，Compose尚未重组",
                play.success && f.likes == listOf(102L), "like newly accepted playback target", f.likes)
            report("real_executor_main_dispatch", "前台真实执行器：播放回调线程",
                f.callbacksOnPlayerDispatch, "queue callback uses injected player dispatcher", f.callbacksOnPlayerDispatch)
        }
        Fixture().let { f ->
            f.executor.likeCurrent("like", true); f.play()
            report("real_executor_like_then_play", "前台真实执行器：先收藏再换歌",
                f.likes == listOf(101L), "like original current before playback commit", f.likes)
        }
        Fixture().let { f ->
            val play = f.play(preserve = true); f.executor.likeCurrent("like", true)
            report("real_executor_preserve_then_like", "前台真实执行器：替换未来歌曲后收藏当前",
                play.success && f.likes == listOf(101L) && play.currentTrack?.id == old.id,
                "preserved old current remains the like target and result current", f.likes)
        }
        Fixture().let { f ->
            val insert = f.executor.insertNext("insert", listOf(next), false)
            f.executor.likeCurrent("like", true)
            report("real_executor_insert_then_like", "前台真实执行器：不跳转插播后收藏",
                f.likes == listOf(101L) && insert.queueSnapshot.map { it.id } == listOf("101", "102", "103"),
                "non-jumping insertion preserves current and existing future tracks",
                mapOf("likes" to f.likes, "queue" to insert.queueSnapshot.map { it.id }))
        }
        Fixture().let { f ->
            f.rejectCommit = true
            val play = f.play(); f.executor.likeCurrent("like", true)
            report("real_executor_failed_play_keeps_current", "前台真实执行器：播放器拒绝后收藏",
                !play.success && f.likes == listOf(101L), "rejected commit cannot replace current target", f.likes)
        }
        Fixture().let { f ->
            f.executor.skip("skip"); f.executor.likeCurrent("like", true)
            report("real_executor_skip_then_like", "前台真实执行器：跳过后收藏",
                f.callbacksOnPlayerDispatch && f.likes == listOf(102L),
                "skip uses player dispatcher and refreshes the current target", f.likes)
        }
        Fixture().let { f ->
            f.play(); f.skipTarget = afterSkip
            val skip = f.executor.skip("skip"); f.executor.likeCurrent("like", true)
            report("real_executor_play_skip_like", "前台真实执行器：播放→跳过→收藏",
                skip.success && f.likes == listOf(103L),
                "skip replaces committed current cursor before the following like", f.likes)
        }
        Fixture().let { f ->
            f.play(); f.skipTarget = next
            val frame = CoroutineScope(currentCoroutineContext()).launch { delay(40); f.live = afterSkip }
            val skip = f.executor.skip("skip"); f.executor.likeCurrent("like", true)
            frame.join()
            report("real_executor_skip_ignores_stale_frame", "前台真实执行器：跳过时重组先报告上一播放目标",
                skip.success && f.likes == listOf(103L),
                "an intermediate Compose frame cannot be mistaken for the skip result", f.likes)
        }
    }
}
