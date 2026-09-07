import android.content.Context
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.data.agent.domain.AgentTurnInput
import app.pipo.nativeapp.data.agent.domain.TurnOutcome
import app.pipo.nativeapp.data.agent.execute.PlayerAgentExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.queue.QueueValidator
import app.pipo.nativeapp.data.agent.queue.TrackConstraintMetadata
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.runtime.AgentToolLoop
import app.pipo.nativeapp.playback.orchestrator.CommittedQueuePlan
import app.pipo.nativeapp.playback.orchestrator.QueueCommitResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import kotlin.coroutines.CoroutineContext

/**
 * Read-only production-service reliability harness.
 *
 * Usage:
 *   RealServiceAgentReliabilityKt <output.json> [reliability_netease] [live-provider.py]
 *
 * The Rust probe is an anonymous Netease session. This harness sends no account
 * cookie and blocks every repository write method. A nonempty stream URL means
 * only that the service returned a URL; media/entitlement proof is deliberately
 * left to the separate url_evidence probe.
 */
private const val DEFAULT_NETEASE_BINARY =
    "/Volumes/soft/Claudio/android-native/native-bridge/target/debug/examples/reliability_netease"
private const val DEFAULT_PROVIDER = "/Volumes/soft/Claudio/scripts/agent-reliability/live-provider.py"

private data class ServiceTrack(
    val id: String,
    val neteaseId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    fun native(): NativeTrack = NativeTrack(
        id = id,
        neteaseId = neteaseId,
        title = title,
        artist = artist,
        album = album,
        streamUrl = "",
        durationMs = durationMs,
    )

    fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("neteaseId", neteaseId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)
}

private fun NativeTrack.summaryJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("neteaseId", neteaseId ?: JSONObject.NULL)
    .put("title", title)
    .put("artist", artist)
    .put("album", album)
    .put("durationMs", durationMs)

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun readProcessOutput(process: Process, timeoutSeconds: Long, failure: String): String {
    // Read while the child runs: successful search payloads can fill a pipe before
    // waitFor completes, which would otherwise make real results look like timeouts.
    val output = FutureTask { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    Thread(output, "real-service-probe-output").apply { isDaemon = true }.start()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IllegalStateException(failure)
    }
    return output.get(5, TimeUnit.SECONDS).trim()
}

/** One invocation per request: the Rust executable accepts one JSON document on stdin. */
private class AnonymousNeteaseProbe(private val executable: File) {
    init {
        require(executable.isFile) { "reliability_netease_missing" }
        require(executable.canExecute()) { "reliability_netease_not_executable" }
    }

    fun call(request: JSONObject, timeoutSeconds: Long = 35): Any {
        val process = ProcessBuilder(executable.absolutePath)
            .redirectError(File("/dev/null"))
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(request.toString())
        }
        val raw = readProcessOutput(process, timeoutSeconds, "netease_probe_timeout")
        val response = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException("netease_probe_invalid_response")
        }
        if (!response.optBoolean("ok", false)) {
            val code = Regex("search_tracks code=(-?[0-9]+)").find(response.optString("error"))?.value
            throw IllegalStateException(code ?: "netease_probe_failed")
        }
        return response.opt("data")
            ?: throw IllegalStateException("netease_probe_missing_data")
    }

    fun search(query: String, limit: Int): List<ServiceTrack> {
        val data = call(JSONObject().put("operation", "search").put("query", query).put("limit", limit))
        val rows = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: data.optJSONArray("songs") ?: JSONArray()
            else -> JSONArray()
        }
        return rows.objects().mapNotNull { row ->
            val id = row.optLong("id", 0L)
            if (id <= 0L) return@mapNotNull null
            val artists = row.optJSONArray("artists") ?: row.optJSONArray("ar")
            val artist = artists?.objects()?.joinToString(", ") { it.optString("name") }
                ?.takeIf(String::isNotBlank).orEmpty()
            val album = (row.optJSONObject("album") ?: row.optJSONObject("al"))?.optString("name").orEmpty()
            ServiceTrack(
                id = id.toString(),
                neteaseId = id,
                title = row.optString("name"),
                artist = artist,
                album = album,
                durationMs = row.optLong("durationMs", row.optLong("dt", 0L)),
            )
        }
    }

    fun songUrls(ids: List<Long>, level: String): List<NativeSongUrl> {
        val data = call(JSONObject().put("operation", "urls").put("ids", JSONArray(ids)).put("level", level))
        val rows = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        return rows.objects().map { row ->
            NativeSongUrl(
                id = row.optLong("id"),
                url = row.optString("url").takeIf(String::isNotBlank),
                bitrate = row.optInt("br", row.optInt("bitrate", 0)),
                sizeBytes = row.optLong("size", row.optLong("sizeBytes", 0L)),
            )
        }
    }

    fun lyrics(trackId: Long): List<PipoLyricLine> {
        val data = call(JSONObject().put("operation", "lyrics").put("id", trackId)) as? JSONObject
            ?: return emptyList()
        val lrc = data.optString("lyric").takeIf(String::isNotBlank) ?: return emptyList()
        return lrc.lineSequence().mapNotNull { line ->
            val match = Regex("\\[([0-9]{1,2}):([0-9]{2})(?:\\.([0-9]{1,3}))?]").find(line)
                ?: return@mapNotNull null
            val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val text = line.substring(match.range.last + 1).trim()
            text.takeIf(String::isNotBlank)?.let {
                PipoLyricLine(startMs = (minute * 60 + second) * 1_000 + fraction, durationMs = 0L, text = it)
            }
        }.toList()
    }
}

/** Exact stdin/stdout schema of scripts/agent-reliability/live-provider.py. */
private class RealServiceDeepSeekToolProvider(private val script: File) {
    init { require(script.isFile) { "live_provider_missing" } }

    fun call(messages: String, tools: String, timeoutSeconds: Int): JSONObject {
        val process = ProcessBuilder("python3", script.absolutePath)
            .redirectError(File("/dev/null"))
            .start()
        val request = JSONObject()
            .put("messages", JSONArray(messages))
            .put("tools", JSONArray(tools))
            .put("timeoutSeconds", timeoutSeconds)
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(request.toString()) }
        val raw = readProcessOutput(process, (timeoutSeconds + 8).toLong(), "live_provider_timeout")
        val response = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException("live_provider_invalid_response")
        }
        if (!response.optBoolean("ok", false)) throw IllegalStateException("live_provider_failed")
        return response.optJSONObject("assistant") ?: throw IllegalStateException("live_provider_missing_assistant")
    }
}

private class DirectDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

private data class TurnCapture(
    val userText: String,
    val modelCalls: MutableList<JSONObject> = mutableListOf(),
    val toolObservations: MutableList<JSONObject> = mutableListOf(),
    val searchRequests: MutableList<JSONObject> = mutableListOf(),
    val urlRequests: MutableList<JSONObject> = mutableListOf(),
    val lyricRequests: MutableList<JSONObject> = mutableListOf(),
    val commits: MutableList<JSONObject> = mutableListOf(),
    var reply: String? = null,
    var error: String? = null,
) {
    fun json(queueAfter: List<NativeTrack>): JSONObject = JSONObject()
        .put("userText", userText)
        .put("modelToolCalls", JSONArray(modelCalls))
        .put("toolObservations", JSONArray(toolObservations))
        .put("realSearches", JSONArray(searchRequests))
        .put("songUrlChecks", JSONArray(urlRequests))
        .put("lyricChecks", JSONArray(lyricRequests))
        .put("queueCommits", JSONArray(commits))
        .put("queueAfter", JSONArray(queueAfter.map(NativeTrack::summaryJson)))
        .put("reply", reply ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
}

private class RealServiceFixture(
    private val netease: AnonymousNeteaseProbe,
    private val provider: RealServiceDeepSeekToolProvider,
) {
    private val context = Context()
    private val featuresStore = AudioFeaturesStore(context)
    private val semanticStore = TrackSemanticStore(context)
    private lateinit var repository: PipoRepository
    private lateinit var indexer: SemanticIndexer
    private var activeCapture: TurnCapture? = null
    private var turnSequence = 0
    private val dispatcher = DirectDispatcher()
    var queue: List<NativeTrack> = emptyList()
    var current: NativeTrack? = null

    private fun capture(): TurnCapture = checkNotNull(activeCapture) { "no_active_capture" }

    init {
        repository = readOnlyRepository()
        indexer = SemanticIndexer(repository, semanticStore, featuresStore)
    }

    private fun readOnlyRepository(): PipoRepository = Proxy.newProxyInstance(
        PipoRepository::class.java.classLoader,
        arrayOf(PipoRepository::class.java),
    ) { _, method, args0 ->
        val args = args0.orEmpty()
        when (method.name) {
            "aiChatTools" -> {
                val messages = args[0] as String
                val tools = args[1] as String
                JSONArray(messages).objects()
                    .filter { it.optString("role") == "tool" }
                    .forEach { tool ->
                        capture().toolObservations += JSONObject()
                            .put("name", tool.optString("name"))
                            .put("content", tool.optString("content"))
                    }
                val assistant = provider.call(messages, tools, timeoutSeconds = 35)
                capture().modelCalls += assistantToolCalls(assistant)
                assistant.toString()
            }
            "searchTracks" -> {
                val query = args[0] as String
                val limit = (args[1] as Number).toInt()
                runCatching { netease.search(query, limit) }.fold(
                    onSuccess = { tracks ->
                        capture().searchRequests += JSONObject()
                            .put("operation", "search")
                            .put("query", query)
                            .put("limit", limit)
                            .put("ok", true)
                            .put("results", JSONArray(tracks.map(ServiceTrack::json)))
                        tracks.map(ServiceTrack::native)
                    },
                    onFailure = { failure ->
                        capture().searchRequests += JSONObject()
                            .put("operation", "search")
                            .put("query", query)
                            .put("limit", limit)
                            .put("ok", false)
                            .put("error", failure::class.java.simpleName)
                        throw MusicSearchException(failure)
                    },
                )
            }
            "songUrls" -> {
                val ids = (args[0] as List<*>).mapNotNull { (it as? Number)?.toLong() }
                val level = args[1] as String
                runCatching { netease.songUrls(ids, level) }.fold(
                    onSuccess = { urls ->
                        capture().urlRequests += JSONObject()
                            .put("operation", "urls")
                            .put("ids", JSONArray(ids))
                            .put("level", level)
                            .put("ok", true)
                            .put("nonEmptyUrlIds", JSONArray(urls.filter { !it.url.isNullOrBlank() }.map(NativeSongUrl::id)))
                            .put("returnedIds", JSONArray(urls.map(NativeSongUrl::id)))
                        urls
                    },
                    onFailure = { failure ->
                        capture().urlRequests += JSONObject()
                            .put("operation", "urls")
                            .put("ids", JSONArray(ids))
                            .put("level", level)
                            .put("ok", false)
                            .put("error", failure::class.java.simpleName)
                        throw failure
                    },
                )
            }
            "lyricsForTrack" -> {
                val id = (args[0] as String).toLong()
                runCatching { netease.lyrics(id) }.fold(
                    onSuccess = { lines ->
                        capture().lyricRequests += JSONObject()
                            .put("operation", "lyrics")
                            .put("id", id)
                            .put("ok", true)
                            .put("lineCount", lines.size)
                        lines
                    },
                    onFailure = { failure ->
                        capture().lyricRequests += JSONObject()
                            .put("operation", "lyrics")
                            .put("id", id)
                            .put("ok", false)
                            .put("error", failure::class.java.simpleName)
                        throw failure
                    },
                )
            }
            "getAccount" -> MutableStateFlow<PipoAccount?>(null)
            "getPlaylists" -> MutableStateFlow(emptyList<PipoPlaylist>())
            "getCloudTracks" -> MutableStateFlow(emptyList<NativeTrack>())
            "getDistillState" -> MutableStateFlow(DistillState(0, 0, 0f, 0))
            "getSettings" -> MutableStateFlow(NativeSettings())
            "getAudioCacheStats" -> MutableStateFlow(AudioCacheStats(0, 0, 0))
            "getAiConfig" -> MutableStateFlow(AiConfigView("", emptyList()))
            "cachedTracksFor", "cloudDiskTracks", "tracksForPlaylist" -> emptyList<NativeTrack>()
            "refreshAccount", "refreshPlaylists", "refreshAudioCacheStats" -> Unit
            "toString" -> "ReadOnlyAnonymousNeteaseRepository"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            // These methods can write server state or local settings/cache. The harness
            // fails closed even if a model produces an unrequested tool call.
            else -> throw IllegalStateException("repository_method_blocked")
        }
    } as PipoRepository

    private fun assistantToolCalls(assistant: JSONObject): JSONObject {
        val calls = assistant.optJSONArray("tool_calls")?.objects().orEmpty().map { call ->
            val function = call.optJSONObject("function")
            JSONObject()
                .put("id", call.optString("id"))
                .put("name", function?.optString("name") ?: "")
                .put("arguments", function?.optString("arguments") ?: "")
        }
        return JSONObject().put("toolCalls", JSONArray(calls))
    }

    private fun realRecall(
        intent: PetIntent,
        pool: List<NativeTrack>,
        currentTrack: NativeTrack?,
        limit: Int,
    ): List<CandidateRecall.Candidate> = CandidateRecall.recall(
        intent = intent,
        library = pool,
        featuresStore = featuresStore,
        semanticStore = semanticStore,
        indexer = indexer,
        tasteProfile = null,
        behaviorEvents = emptyList(),
        behaviorPreference = BehaviorPreferenceSnapshot.Empty,
        currentTrack = currentTrack,
        limit = limit,
    )

    private fun executor(userText: String): PlayerAgentExecutor = PlayerAgentExecutor(
        repository = repository,
        currentTrackProvider = { current },
        currentQueueProvider = { queue },
        sourceUserText = userText,
        taskId = "real-service-agent-reliability-$turnSequence",
        playerDispatcher = dispatcher,
        onApplyAgentQueueRequest = { request ->
            queue = request.tracks
            current = request.tracks.firstOrNull()
            capture().commits += JSONObject()
                .put("operation", request.operation.name)
                .put("count", request.tracks.size)
                .put("first", request.tracks.firstOrNull()?.summaryJson() ?: JSONObject.NULL)
                .put("tracks", JSONArray(request.tracks.map(NativeTrack::summaryJson)))
            QueueCommitResult.Success(
                CommittedQueuePlan.snapshot(
                    sessionId = "real-service-reliability",
                    queueVersion = 1,
                    requestId = request.requestId,
                    operation = request.operation,
                    sourceUserText = request.sourceUserText,
                    tracks = request.tracks,
                    preserveCurrent = request.preserveCurrent,
                ).copy(jumpToInserted = request.jumpToInserted),
            )
        },
        onSkip = { current = queue.firstOrNull { it.id != current?.id } ?: current },
    )

    suspend fun runTurn(text: String, history: List<PetMemory.ConversationTurn>): TurnCapture {
        turnSequence += 1
        val capture = TurnCapture(text)
        activeCapture = capture
        val resolver = MusicResolver(
            repository = repository,
            loadLocalTracks = { emptyList() },
            recallCandidates = ::realRecall,
        )
        // The verifier stays production code. No provider field establishes verified
        // language/genre, so there is intentionally no synthetic metadata adapter.
        val loop = AgentToolLoop(
            repository = repository,
            ledger = AgentLedgerStore(),
            resolver = resolver,
            queuePlanner = AgentQueuePlanner(
                validator = QueueValidator(metadataForTrack = { TrackConstraintMetadata() }),
            ),
            replyGrounder = ReplyGrounder(),
        )
        try {
            val outcome: TurnOutcome? = loop.run(
                AgentTurnInput(
                    userText = text,
                    history = history,
                    currentTrack = current,
                    currentQueue = queue,
                    userFacts = "",
                    persona = PetPersona.FRIENDLY,
                ),
                executor(text),
            )
            capture.reply = outcome?.reply
        } catch (failure: Throwable) {
            capture.error = failure::class.java.simpleName
        } finally {
            activeCapture = null
        }
        return capture
    }
}

private data class Scenario(val id: String, val userText: String)

private val scenarios = listOf(
    Scenario("eagles_exact_first", "我要听加州旅馆"),
    Scenario("eagles_exact_second", "我要听原唱的"),
    Scenario("rnb_group", "我要听rnb"),
    Scenario("screenshot_group_opening", "来一点安静的中文歌，第一首要易烊千玺的粉雾海"),
)

fun main(args: Array<String>) = runBlocking {
    require(args.size in 1..3) {
        "usage: RealServiceAgentReliabilityKt <output.json> [reliability_netease] [live-provider.py]"
    }
    val output = File(args[0])
    val netease = AnonymousNeteaseProbe(File(args.getOrElse(1) { DEFAULT_NETEASE_BINARY }))
    val provider = RealServiceDeepSeekToolProvider(File(args.getOrElse(2) { DEFAULT_PROVIDER }))
    val fixture = RealServiceFixture(netease, provider)
    val history = mutableListOf<PetMemory.ConversationTurn>()
    val turns = JSONArray()

    scenarios.forEach { scenario ->
        val capture = fixture.runTurn(scenario.userText, history)
        turns.put(JSONObject()
            .put("id", scenario.id)
            .put("capture", capture.json(fixture.queue)))
        output.parentFile?.mkdirs()
        output.writeText(JSONObject().put("turns", turns).toString(2) + "\n")
        println("real_service_turn=${scenario.id} searches=${capture.searchRequests.size} commits=${capture.commits.size} error=${capture.error ?: "none"}")
        val now = System.currentTimeMillis() / 1_000L
        history += PetMemory.ConversationTurn(PetMemory.ROLE_USER, scenario.userText, now)
        capture.reply?.let { history += PetMemory.ConversationTurn(PetMemory.ROLE_ASSISTANT, it, now) }
    }

    val finalQueue = fixture.queue.map(NativeTrack::summaryJson)
    output.parentFile?.mkdirs()
    output.writeText(JSONObject()
        .put("session", JSONObject()
            .put("neteaseSession", "anonymous")
            .put("providerCredential", "process_env_only")
            .put("streamUrlMeaning", "nonempty URL only; not entitlement, trial, HTTP, range, or decode proof"))
        .put("turns", turns)
        .put("finalQueue", JSONArray(finalQueue))
        .put("lastTracksForExternalUrlEvidence", JSONArray(finalQueue))
        .toString(2) + "\n")
}
