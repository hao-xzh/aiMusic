import app.pipo.nativeapp.data.MusicSearchException
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.PetPersona
import app.pipo.nativeapp.data.PipoRepository
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.MusicGoal
import app.pipo.nativeapp.data.agent.domain.MusicSelectionMode
import app.pipo.nativeapp.data.agent.domain.MusicStyleProfile
import app.pipo.nativeapp.data.agent.domain.MusicTurnPlan
import app.pipo.nativeapp.data.agent.domain.PlannedAction
import app.pipo.nativeapp.data.agent.domain.PlayMode
import app.pipo.nativeapp.data.agent.domain.TrackRequirement
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.resolve.ResolveError
import app.pipo.nativeapp.data.agent.resolve.TrackResolver
import app.pipo.nativeapp.data.agent.runtime.AgentToolLoop
import app.pipo.nativeapp.data.agent.runtime.AgentTurnExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy

/** 搜索服务故障必须与 200 空候选分开，并在同轮阻止重复联网搜索。 */
object SearchFailureReliability {
    fun runCases(report: (String, String, Boolean, String, Any?) -> Unit) = runBlocking {
        val unsafeCause = IllegalStateException("code=50000005 cookie=private")
        val unavailableRepository = searchRepository { throw MusicSearchException(unsafeCause) }
        val unavailable = runCatching {
            TrackResolver(unavailableRepository).resolve(TrackRequirement("粉雾海", "易烊千玺"), emptyList())
        }.exceptionOrNull()
        report(
            "search_failure_is_not_not_found",
            "服务搜索失败",
            unavailable is MusicSearchException && unavailable.message == "music_search_unavailable",
            "MusicSearchException without raw service detail",
            unavailable?.message,
        )

        val empty = TrackResolver(searchRepository { emptyList() })
            .resolve(TrackRequirement("不存在的歌", "测试歌手"), emptyList())
        report(
            "search_empty_result_is_not_found",
            "200 空数组",
            empty.error == ResolveError.NotFound,
            "successful empty response remains NotFound",
            empty.error,
        )

        val cancelled = runCatching {
            TrackResolver(searchRepository { throw CancellationException("cancelled") })
                .resolve(TrackRequirement("粉雾海", "易烊千玺"), emptyList())
        }.exceptionOrNull()
        report(
            "search_cancellation_propagates",
            "取消搜索",
            cancelled is CancellationException,
            "CancellationException is rethrown",
            cancelled?.javaClass?.simpleName,
        )

        var resolverSearches = 0
        val resolver = MusicResolver(
            repository = searchRepository {
                resolverSearches += 1
                throw MusicSearchException(unsafeCause)
            },
            loadLocalTracks = { emptyList() },
        )
        val resolverFailure = runCatching {
            resolver.resolve(
                MusicTurnPlan(
                    turnId = "search-failure-resolver",
                    userText = "来点安静的中文歌",
                    plannerRaw = "tool_loop",
                    actions = listOf(
                        PlannedAction.PlayRequest(
                            actionId = "search-failure",
                            mode = PlayMode.ReplaceQueue,
                            primaryGoal = MusicGoal(
                                selectionMode = MusicSelectionMode.OpenRecommendation,
                                styleProfile = MusicStyleProfile(semanticQuery = "安静 中文歌"),
                            ),
                            desiredCount = 6,
                        ),
                    ),
                ),
                input = AgentTurnInput(userText = "来点安静的中文歌", history = emptyList(), currentTrack = null,
                    currentQueue = emptyList(), userFacts = "", persona = PetPersona.FRIENDLY),
            )
        }.exceptionOrNull()
        report(
            "batch_search_failure_stops_early",
            "批量补齐全部失败",
            resolverFailure is MusicSearchException && resolverSearches in 1..3,
            "one bounded batch surfaces service failure instead of trying every query",
            "type=${resolverFailure?.javaClass?.simpleName}, searches=$resolverSearches",
        )

        var externalSearches = 0
        val observations = mutableListOf<JSONObject>()
        val responses = listOf(
            assistantTool("search_tracks", JSONObject().put("query", "粉雾海")),
            assistantTool("search_tracks", JSONObject().put("query", "易烊千玺")),
            assistantTool("final_response", JSONObject().put("message", "音乐搜索服务暂时不可用。")),
        )
        var providerCall = 0
        val loopRepository = Proxy.newProxyInstance(
            PipoRepository::class.java.classLoader,
            arrayOf(PipoRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "aiChatTools" -> {
                    val messages = JSONArray(arguments.orEmpty().first() as String)
                    for (index in 0 until messages.length()) {
                        val message = messages.getJSONObject(index)
                        if (message.optString("role") == "tool") observations += JSONObject(message.getString("content"))
                    }
                    responses[providerCall++]
                }
                "searchTracks" -> {
                    externalSearches += 1
                    throw MusicSearchException(unsafeCause)
                }
                else -> error("unexpected loop repository call: ${method.name}")
            }
        } as PipoRepository
        val noActionExecutor = Proxy.newProxyInstance(
            AgentActionExecutor::class.java.classLoader,
            arrayOf(AgentActionExecutor::class.java),
        ) { _, method, _ -> error("unexpected executor call: ${method.name}") } as AgentActionExecutor
        val terminalFailure = runCatching {
            AgentToolLoop(
                loopRepository,
                AgentLedgerStore(),
                MusicResolver(loopRepository),
                AgentQueuePlanner(),
                ReplyGrounder(),
            ).run(
                AgentTurnInput(userText = "搜索粉雾海", history = emptyList(), currentTrack = null,
                    currentQueue = emptyList(), userFacts = "", persona = PetPersona.FRIENDLY),
                noActionExecutor,
            )
        }.exceptionOrNull()
        val searchErrors = observations.filter { it.optString("error") == "music_search_unavailable" }
        report(
            "tool_search_failure_is_bounded_and_safe",
            "同轮第二次换 query",
            externalSearches == 1 && searchErrors.size >= 2 && searchErrors.all { error ->
                error.optString("message") == "音乐搜索服务暂时不可用，请稍后再试。" &&
                    !error.toString().contains("50000005") && !error.toString().contains("cookie")
            } && terminalFailure is AgentTurnExecutionException && !terminalFailure.retryable &&
                terminalFailure.message == "音乐搜索服务暂时不可用，请稍后再试。",
            "repeat search is local-only; an empty turn terminates as a non-retryable service failure",
            "externalSearches=$externalSearches, errors=${searchErrors.size}, terminal=${terminalFailure?.message}",
        )
    }

    private fun searchRepository(search: () -> List<NativeTrack>): PipoRepository =
        Proxy.newProxyInstance(
            PipoRepository::class.java.classLoader,
            arrayOf(PipoRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "searchTracks" -> search()
                else -> error("unexpected search repository call: ${method.name}")
            }
        } as PipoRepository

    private fun assistantTool(name: String, args: JSONObject): String = JSONObject()
        .put("role", "assistant")
        .put(
            "tool_calls",
            JSONArray().put(
                JSONObject()
                    .put("id", "search_failure_${name}")
                    .put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args)),
            ),
        )
        .toString()
}
