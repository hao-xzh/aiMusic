import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.data.*
import app.pipo.nativeapp.data.agent.domain.*
import app.pipo.nativeapp.data.agent.execute.AgentActionExecutor
import app.pipo.nativeapp.data.agent.memory.AgentLedgerStore
import app.pipo.nativeapp.data.agent.queue.AgentQueuePlanner
import app.pipo.nativeapp.data.agent.reply.ReplyGrounder
import app.pipo.nativeapp.data.agent.resolve.MusicResolver
import app.pipo.nativeapp.data.agent.resolve.TrackResolver
import app.pipo.nativeapp.data.agent.normalize.MusicSemanticSignals
import app.pipo.nativeapp.data.agent.runtime.AgentToolLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy

private fun track(id: Long, title: String, artist: String = "周杰伦") = NativeTrack(
    id = id.toString(), neteaseId = id, title = title, artist = artist,
    album = "测试专辑", streamUrl = "fixture://$id", durationMs = 200000,
)
private val catalog = listOf(track(1, "晴天"), track(2, "稻香"), track(3, "夜曲"),
    track(4, "十年", "陈奕迅"), track(5, "红豆", "王菲"))
private fun tool(name: String, args: Any = JSONObject()): JSONObject = JSONObject()
    .put("id", "call_${System.nanoTime()}").put("type", "function")
    .put("function", JSONObject().put("name", name).put("arguments", args))
private fun assistant(vararg calls: JSONObject): String = JSONObject().put("role", "assistant")
    .put("tool_calls", JSONArray(calls.toList())).toString()
private fun obj(raw: String) = JSONObject(raw)
private fun final(message: String) = assistant(tool("final_response", JSONObject().put("message", message)))

private class Fixture(val responses: List<String>, val failAction: String? = null,
                      val cancelAction: String? = null) {
    var providerCalls = 0
    val observations = mutableListOf<JSONObject>()
    val prompts = mutableListOf<String>()
    val actions = mutableListOf<JSONObject>()
    val searches = mutableListOf<String>()
    var queue = catalog.take(3)
    var current = catalog.first()
    val repo = Proxy.newProxyInstance(PipoRepository::class.java.classLoader,
        arrayOf(PipoRepository::class.java)) { _, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "aiChatTools" -> {
                val messages = JSONArray(args[0] as String)
                prompts.add(messages.getJSONObject(1).optString("content"))
                observations.clear()
                for (i in 0 until messages.length()) {
                    val msg = messages.getJSONObject(i)
                    if (msg.optString("role") == "tool") observations.add(JSONObject(msg.getString("content")))
                }
                val index = providerCalls++
                if (index >= responses.size) throw IllegalStateException("HTTP 401 fixture provider exhausted")
                responses[index]
            }
            "getPlaylists" -> MutableStateFlow(listOf(PipoPlaylist(10, "通勤", 5)))
            "getCloudTracks" -> MutableStateFlow(catalog)
            "getSettings" -> MutableStateFlow(NativeSettings())
            "getAccount" -> MutableStateFlow<PipoAccount?>(null)
            "searchTracks" -> {
                val query = args[0] as String
                searches.add(query)
                catalog.filter { query.contains(it.title) || query.contains(it.artist) }
                    .take((args[1] as Number).toInt())
            }
            "cachedTracksFor", "tracksForPlaylist", "cloudDiskTracks" -> catalog
            "refreshPlaylists", "refreshAccount" -> Unit
            "toString" -> "FixtureRepository"
            "hashCode" -> 1
            "equals" -> false
            else -> error("Unimplemented fixture repository call: ${method.name}")
        }
    } as PipoRepository
    val executor = Proxy.newProxyInstance(AgentActionExecutor::class.java.classLoader,
        arrayOf(AgentActionExecutor::class.java)) { _, method, arguments ->
        val args = arguments.orEmpty()
        val name = method.name
        if (name == cancelAction) throw CancellationException("fixture cancelled")
        val action = JSONObject().put("method", name).put("currentBefore", current.title)
        val tracks = if (name == "playQueue") args[2] as List<NativeTrack>
            else if (name == "insertNext") args[1] as List<NativeTrack> else emptyList()
        action.put("tracks", JSONArray(tracks.map { it.title }))
        if (name == "playQueue") action.put("mode", args[1].toString())
            .put("artists", JSONArray((args[4] as MusicGoal).primaryArtists))
            .put("preserveCurrent", args[7] as Boolean)
        if (name == "insertNext") action.put("jump", args[2])
        actions.add(action)
        val success = name != failAction
        if (success && name == "playQueue") {
            if (args[7] as Boolean) queue = listOf(current) + tracks
            else { queue = tracks; current = tracks.first() }
        }
        if (success && name == "insertNext") queue = listOf(current) + tracks + queue.filter { it.id != current.id }
        val type = when (name) {
            "playQueue" -> "play_queue"
            "insertNext" -> "insert_next"
            "likeCurrent", "likeTrack" -> "like"
            "modifyPlaylist" -> "playlist"
            "createPlaylist" -> "playlist_create"
            "skip" -> "skip"
            else -> error("Unknown fixture action $name")
        }
        ActionExecutionResult(actionId = args[0] as String, type = type, success = success,
            message = if (success) "动作完成" else "fixture rejected", tracks = tracks,
            queueSnapshot = queue, currentTrack = current,
            likedTrack = if (type == "like") current else null,
            insertedTrack = if (name == "insertNext") tracks.firstOrNull() else null,
            errorMessage = if (success) null else "fixture rejected")
    } as AgentActionExecutor
    val loop = AgentToolLoop(repo, AgentLedgerStore(), MusicResolver(repo), AgentQueuePlanner(), ReplyGrounder())
    fun reflect(name: String, vararg args: Any): Any? {
        val method = loop.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return method.invoke(loop, *args)
    }
    fun input(text: String) = AgentTurnInput(text, emptyList(), currentTrack = current,
        currentQueue = queue, userFacts = "", persona = PetPersona.FRIENDLY)
}

private val results = JSONArray()
private fun result(id: String, text: String, pass: Boolean, expected: String, actual: Any?) {
    val kind = if (id in setOf("malformed_string", "malformed_array", "malformed_null", "false_success",
            "honest_negation", "cancel_propagation", "partial_omission", "duplicate_skip", "multi_early_finish",
            "unknown_tool", "failure_then_success", "failed_like_continue_playlist_skip")) "fault_injection"
        else if (id.startsWith("conversation") || id in setOf("direct_play", "direct_insert", "mixed_two_commits",
            "search_then_play", "like_playlist_skip", "create_play_like", "list_read_play_like", "simple_like")) "scripted_tool_loop"
        else "local_semantics_or_queue_validation"
    results.put(JSONObject().put("id", id).put("input", text).put("passed", pass)
        .put("kind", kind).put("realModelTested", false).put("realPlayerTested", false)
        .put("expected", expected).put("actual", actual))
    val summary = if (actual is JSONObject && actual.has("actions")) {
        "actions=${actual.optJSONArray("actions")}; reply=${actual.optString("reply")}; error=${actual.optString("error")}"
    } else actual.toString()
    println("${if (pass) "PASS" else "FAIL"} $id $text -> $summary")
}

fun main(args: Array<String>) = runBlocking {
    val f = Fixture(emptyList())
    val openingText = "来一点安静的中文歌，第一首要易烊千玺的粉雾海"
    val openingRequest = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"exact_track","operation":"play_now","target_title":"粉雾海","target_artist":"易烊千玺","artists":["易烊千玺"],"count":1}"""), f.input(openingText)) as PlannedAction.PlayRequest
    result("opening_is_group", openingText, openingRequest.mode == PlayMode.ReplaceQueue && openingRequest.desiredCount == 12,
        "head requirement cannot collapse group to one", openingRequest)
    result("opening_identity", openingText, openingRequest.target == TrackRequirement("粉雾海", "易烊千玺", TrackPlacement.Now) &&
        openingRequest.primaryGoal.primaryTracks == listOf(TrackRequirement("粉雾海", "易烊千玺", TrackPlacement.Now)), "preserve exact opening singer and title", openingRequest.target)
    result("opening_artist_scope", openingText, openingRequest.primaryGoal.primaryArtists.isEmpty(),
        "opening artist does not constrain every song", openingRequest.primaryGoal.primaryArtists)
    result("opening_group_semantics", openingText, openingRequest.primaryGoal.styleProfile.energy == "low" &&
        "mandarin" in openingRequest.primaryGoal.styleProfile.languages,
        "quiet Chinese conditions retained for the group", openingRequest.primaryGoal.styleProfile)
    val styleRequest = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"exact_track","operation":"play_now","target_title":"Kiss Me Thru The Phone","count":1}"""), f.input("我要听rnb")) as PlannedAction.PlayRequest
    result("style_not_model_picked_single", "我要听rnb", styleRequest.mode == PlayMode.ReplaceQueue &&
        styleRequest.desiredCount == 12 && styleRequest.target == null && "r&b" in styleRequest.primaryGoal.styleProfile.genres,
        "arbitrary model target/count cannot turn explicit style request into one song", styleRequest)
    val oneStyle = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"open_recommendation","operation":"play_now","count":1,"genres":["rnb"]}"""), f.input("放一首R&B")) as PlannedAction.PlayRequest
    result("explicit_single_style", "放一首R&B", oneStyle.desiredCount == 1 && oneStyle.mode == PlayMode.ReplaceQueue,
        "explicit user single request remains one selection", oneStyle)
    val hotel = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"exact_track","operation":"play_now","target_title":"Hotel California","target_artist":"Eagles"}"""), f.input("我要听加州旅馆")) as PlannedAction.PlayRequest
    result("translated_exact_still_single", "我要听加州旅馆", hotel.mode == PlayMode.PlayNow && hotel.desiredCount == 1 && hotel.target?.artist == "Eagles",
        "translated exact catalog identity remains a single song", hotel)
    val ordinaryStyle = f.reflect("goalFromArgs", obj("""{"intent_mode":"open_recommendation","genres":["rnb"],"languages":["zh"]}"""), "来点中文R&B") as MusicGoal
    result("ordinary_style_not_unavailable_metadata_gate", "来点中文R&B", ordinaryStyle.hardGenres.isEmpty() && ordinaryStyle.hardLanguages.isEmpty() &&
        ordinaryStyle.styleProfile.genres == listOf("rnb") && ordinaryStyle.styleProfile.languages == listOf("zh"),
        "ordinary preferences still drive selection without requiring unavailable certified labels", ordinaryStyle)
    val strictStyle = f.reflect("goalFromArgs", obj("""{"intent_mode":"open_recommendation","genres":["rnb"],"languages":["zh"]}"""), "必须全部是中文R&B") as MusicGoal
    result("explicit_hard_style_retained", "必须全部是中文R&B", strictStyle.hardGenres == listOf("rnb") && strictStyle.hardLanguages == listOf("zh"),
        "explicit hard constraints remain blocking on unknown evidence", strictStyle)
    val headFirst = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"exact_track","operation":"play_now","target_title":"粉雾海","count":1}"""),
        f.input("第一首放《粉雾海》，后面全听英文爵士")) as PlannedAction.PlayRequest
    result("opening_clause_keeps_following_conditions", "第一首放《粉雾海》，后面全听英文爵士",
        headFirst.desiredCount == 12 && headFirst.primaryGoal.hardGenres == listOf("jazz") &&
            headFirst.primaryGoal.hardLanguages == listOf("english"), "retain the entire suffix and its hard constraints", headFirst)
    val referred = f.reflect("playRequestFromArgs", obj("""{"intent_mode":"exact_track","target_title":"Hotel California","target_artist":"Eagles","reference_context":"last_search"}"""),
        f.input("播放刚刚那首英文歌")) as PlannedAction.PlayRequest
    result("single_reference_not_group", "播放刚刚那首英文歌", referred.desiredCount == 1 && referred.mode == PlayMode.PlayNow &&
        referred.target?.title == "Hotel California", "contextual singular reference remains exact", referred)
    val namedQuiet = f.reflect("goalFromArgs", obj("""{"intent_mode":"exact_track","target_title":"安静","target_artist":"周杰伦"}"""), "播放周杰伦的安静") as MusicGoal
    result("exact_song_title_not_energy_preference", "播放周杰伦的安静", namedQuiet.selectionMode == MusicSelectionMode.ExactTrack &&
        namedQuiet.styleProfile.energy == "any", "named song title cannot introduce unrelated energy constraints", namedQuiet)
    data class ModeCase(val id: String, val text: String, val requested: PlayMode, val expected: PlayMode)
    listOf(
        ModeCase("mode_plain", "播放晴天", PlayMode.PlayNow, PlayMode.PlayNow),
        ModeCase("mode_next", "下一首放晴天", PlayMode.InsertNext, PlayMode.InsertNext),
        ModeCase("mode_now_then_next", "先播放晴天，然后下一首放稻香", PlayMode.PlayNow, PlayMode.PlayNow),
        ModeCase("mode_replace_then_insert", "替换队列为周杰伦的歌，再插播红豆", PlayMode.InsertNext, PlayMode.InsertNext),
        ModeCase("mode_english_next", "Play 晴天 next, keep this song playing", PlayMode.InsertNext, PlayMode.InsertNext),
        ModeCase("mode_no_skip", "播放晴天，不要跳到下一首", PlayMode.PlayNow, PlayMode.PlayNow),
        ModeCase("mode_after_current", "这首听完再放晴天", PlayMode.InsertNext, PlayMode.InsertNext),
        ModeCase("mode_wrong_insert_for_plain_play", "我想听晴天", PlayMode.InsertNext, PlayMode.PlayNow),
        ModeCase("mode_negated_insert", "不要插播，播放晴天", PlayMode.InsertNext, PlayMode.PlayNow),
        ModeCase("mode_then_listen", "先听晴天，再听稻香", PlayMode.InsertNext, PlayMode.InsertNext),
    ).forEach { c ->
        val actual = f.reflect("effectivePlayMode", c.requested,
            MusicGoal(selectionMode = MusicSelectionMode.ExactTrack), c.text)
        result(c.id, c.text, actual == c.expected, c.expected.toString(), actual.toString())
    }
    val semanticCases = listOf(
        Triple("scope_similar", "周杰伦和风格接近的歌手混着放", "similar"),
        Triple("scope_focus", "周杰伦为主，掺一点其他人的歌", "focus"),
        Triple("scope_strict", "只放周杰伦", "strict"),
    )
    val nowCorrection = f.reflect("effectivePlayMode", PlayMode.InsertNext,
        MusicGoal(selectionMode = MusicSelectionMode.ExactTrack), "现在播放稻香，不要跳到下一首")
    result("negative_next_correction", "现在播放稻香，不要跳到下一首", nowCorrection == PlayMode.PlayNow,
        "explicit now wins over a negated next clause", nowCorrection.toString())
    val closerText = app.pipo.nativeapp.data.agent.normalize.CommandTextSignals.closerTrackTitle("先播放稻香，最后用晴天收尾")
    result("unquoted_closer_identity", "最后用晴天收尾", closerText == "晴天", "exact closer title without directive words", closerText)
    val semanticScorer = app.pipo.nativeapp.data.agent.queue.ConstraintScorer {
        app.pipo.nativeapp.data.agent.queue.TrackConstraintMetadata(language = "en", genres = listOf("rnb"), verified = true)
    }
    result("semantic_avoid_not_title_substring", "不要说唱，但曲名含 No Rap 不代表说唱",
        !semanticScorer.hitsAvoidTerm(track(91, "No Rap Cruise", "Ava"), listOf("rap")),
        "verified genre evidence controls semantic exclusion", semanticScorer.avoidEvidence(track(91, "No Rap Cruise", "Ava"), listOf("rap")).name)
    semanticCases.forEach { (id, text, scope) ->
        val goal = f.reflect("goalFromArgs", JSONObject().put("intent_mode", "artist_focus")
            .put("artists", JSONArray(listOf("周杰伦"))).put("artist_scope", scope), text) as MusicGoal
        result(id, text, goal.artistScope.name.equals(scope, true), scope, goal.artistScope.name)
    }
    val initialArgs = obj("""{"intent_mode":"artist_focus","artists":["周杰伦","陈奕迅"],"count":8}""")
    val screenshotText = "我想听丁世光，刘思鉴，方大同，陶喆，曹格的歌"
    listOf("replace_queue", "insert_next").forEach { operation ->
        val badArgs = obj("""{"intent_mode":"artist_focus","artists":["丁世光","刘思鉴","方大同","陶喆","曹格"],"preserve_current":true}""")
            .put("operation", operation)
        val request = f.reflect("playRequestFromArgs", badArgs, f.input(screenshotText)) as PlannedAction.PlayRequest
        result("screenshot_plain_want_$operation", screenshotText,
            request.mode == PlayMode.ReplaceQueue && !request.preserveCurrent && request.primaryGoal.primaryArtists.size == 5,
            "replace the entire queue now despite incorrect model timing arguments",
            "mode=${request.mode}; preserve=${request.preserveCurrent}; artists=${request.primaryGoal.primaryArtists}")
    }
    listOf(
        "我想听周杰伦和陈奕迅的歌" to false,
        "换成轻爵士" to false,
        "我想听周杰伦的歌，不要插播" to false,
        "我想听周杰伦的歌，不要放到后面" to false,
        "现在播放晴天，不要切到下一首" to false,
        "播放周杰伦的《下一首》，不要保留当前曲" to false,
        "Play Jay Chou now, not later" to false,
        "Don't play Jay Chou next, play now" to false,
        "后面想听周杰伦和陈奕迅的歌" to true,
        "这首听完再放周杰伦的歌" to true,
        "再加点王菲进去，原来的也保留" to true,
        "这首不要打断，后面换成轻爵士" to true,
    ).forEachIndexed { index, (text, expected) ->
        val request = f.reflect("playRequestFromArgs", JSONObject(initialArgs.toString())
            .put("operation", "replace_queue").put("preserve_current", true), f.input(text)) as PlannedAction.PlayRequest
        result("preserve_requires_current_intent_$index", text, request.preserveCurrent == expected,
            "preserve_current=$expected", request.preserveCurrent)
    }
    val initialGoal = f.reflect("goalFromArgs", initialArgs, "我要听周杰伦和陈奕迅的歌") as MusicGoal
    result("two_artist_slots", "我要听周杰伦和陈奕迅的歌",
        initialGoal.primaryArtists == listOf("周杰伦", "陈奕迅"), "retain both artists", initialGoal.primaryArtists)
    val expandedGoal = f.reflect("goalFromArgs", obj("""{"intent_mode":"artist_focus","artists":["周杰伦","陈奕迅","王菲"]}"""),
        "再加点王菲进去，原来的也保留") as MusicGoal
    result("followup_explicit_union", "再加点王菲进去，原来的也保留",
        expandedGoal.primaryArtists.size == 3, "retain all three if model supplies union", expandedGoal.primaryArtists)
    val mixedGoal = f.reflect("goalFromArgs", initialArgs,
        "先放周杰伦和陈奕迅的歌，中途加王菲的《红豆》") as MusicGoal
    result("mixed_sentence_intent", "先放周杰伦和陈奕迅的歌，中途加王菲的《红豆》",
        mixedGoal.selectionMode == MusicSelectionMode.ArtistFocus,
        "first action remains artist_focus", mixedGoal.selectionMode)
    val styleGoal = f.reflect("goalFromArgs", obj("""{"intent_mode":"open_recommendation","query":"夜间开车的R&B","style":{"genres":["rnb"],"moods":["relaxed"],"scenes":["driving"],"languages":["zh","en"],"vocal_types":["female"],"avoid_tags":["rap"]}}"""),
        "改成夜间开车的R&B，女声，别说唱") as MusicGoal
    result("style_slots", "改成夜间开车的R&B，女声，别说唱",
        styleGoal.styleProfile.genres == listOf("rnb") && styleGoal.styleProfile.scenes == listOf("driving") &&
            styleGoal.styleProfile.avoidTags == listOf("rap"), "retain style, scene and exclusions", styleGoal.styleProfile)
    val negative = MusicSemanticSignals.extract("来10首不要电音的摇滚")
    result("negated_style", "来10首不要电音的摇滚", "electronic" !in negative.genres,
        "electronic excluded from positive genres", "genres=${negative.genres}; avoid=${negative.aiAvoidStyles}")
    val calm = MusicSemanticSignals.extract("来几首夜晚开车听的安静歌曲")
    result("scene_signals", "来几首夜晚开车听的安静歌曲", calm.hasSignal && calm.scenes.isNotEmpty(),
        "scene signals exist", "energy=${calm.energy}; scenes=${calm.scenes}")
    val localTrack = TrackResolver(f.repo).resolve(TrackRequirement("晴天", "周杰伦"), catalog, false)
    result("exact_local_track", "播放周杰伦的晴天", localTrack.track?.id == "1", "correct local match", localTrack.track?.title)
    val wrongArtist = TrackResolver(f.repo).resolve(TrackRequirement("晴天", "不存在的歌手"), emptyList())
    val impersonator = TrackResolver(f.repo).resolve(TrackRequirement("晴天", "周杰伦"),
        listOf(track(92, "晴天", "周杰伦翻唱者")), false)
    result("artist_substring_not_identity", "歌手名字含周杰伦不等于周杰伦", impersonator.track == null,
        "artist identity uses complete collaborator name", impersonator.track?.artist)
    val versionValidation = app.pipo.nativeapp.data.agent.queue.QueueValidator().validateStructured(listOf(
        PlannedAction.PlayTracks("version", PlayMode.PlayNow, listOf(track(93, "晴天伴奏")), null,
            MusicGoal(primaryTracks = listOf(TrackRequirement("晴天", "周杰伦"))))))
    result("title_substring_not_identity", "晴天伴奏不能冒充晴天", !versionValidation.passed,
        "direct commit validates complete requested title", versionValidation.messages)
    result("unspecified_version_allows_labelled_live", "易烊千玺的粉雾海",
        app.pipo.nativeapp.data.agent.normalize.CommandTextSignals.trackTitleMatches("粉雾海 (浴池Live版)", "粉雾海"),
        "unqualified song name permits its labelled live edition; artist remains independently checked", "粉雾海 (浴池Live版)")
    result("explicit_version_stays_exact", "指定2013重制版不能换1999版",
        !app.pipo.nativeapp.data.agent.normalize.CommandTextSignals.trackTitleMatches("Hotel California (1999 Remaster)", "Hotel California (2013 Remaster)"),
        "explicit edition is not erased", "different remaster years")
    result("unrequested_accompaniment_stays_blocked", "粉雾海不能用伴奏替代",
        !app.pipo.nativeapp.data.agent.normalize.CommandTextSignals.trackTitleMatches("粉雾海 (Live 伴奏)", "粉雾海"),
        "live label cannot conceal unrequested accompaniment", "粉雾海 (Live 伴奏)")
    result("online_wrong_artist", "播放不存在的歌手的晴天", wrongArtist.track == null,
        "no substitution with another artist", wrongArtist.track?.let { "${it.artist}-${it.title}" })
    val wrongTitle = TrackResolver(f.repo).resolve(TrackRequirement("不存在的歌曲", "周杰伦"), emptyList())
    result("online_wrong_title", "播放周杰伦的不存在的歌曲", wrongTitle.track == null,
        "no substitution with another title", wrongTitle.track?.let { "${it.artist}-${it.title}" })
    fun validateCase(id: String, text: String, tracks: List<NativeTrack>, goal: MusicGoal,
                     expectedPass: Boolean, expected: String, mode: PlayMode = PlayMode.ReplaceQueue) {
        val plan = MusicTurnPlan(id, text, listOf(PlannedAction.PlayTracks("fixture", mode, tracks,
            continuous = null, primaryGoal = goal)), plannerRaw = "tool_loop")
        val actual = AgentQueuePlanner().plan(plan).validation
        result(id, text, actual.passed == expectedPass, expected, "passed=${actual.passed}; messages=${actual.messages}")
    }
    validateCase("two_artist_both_present", "周杰伦和陈奕迅都要有", listOf(catalog[0], catalog[3]), initialGoal,
        true, "accept when both present")
    validateCase("two_artist_one_missing", "周杰伦和陈奕迅都要有", catalog.take(3), initialGoal,
        false, "reject missing 陈奕迅")
    validateCase("added_artist_missing", "再加点王菲进去，原来的也保留", listOf(catalog[0], catalog[3]), expandedGoal,
        false, "reject missing 王菲")
    validateCase("strict_artist_violation", "只放周杰伦", listOf(catalog[3]),
        MusicGoal(primaryArtists = listOf("周杰伦")), false, "reject wrong artist")
    validateCase("insert_artist_violation", "下一首开始只放周杰伦", listOf(catalog[3]),
        MusicGoal(primaryArtists = listOf("周杰伦")), false, "insert must retain artist scope", PlayMode.InsertNext)
    validateCase("wrong_version_required", "必须包含王菲的红豆", listOf(track(20, "红豆", "其他歌手")),
        MusicGoal(mustInclude = listOf(TrackRequirement("红豆", "王菲"))), false, "reject wrong singer for must include")
    validateCase("play_now_missing_closer", "现在听夜曲、红豆，最后仍用晴天收尾", listOf(catalog[2], catalog[4]),
        MusicGoal(closer = TrackRequirement("晴天", "周杰伦")), false,
        "play_now must enforce the same closing-track contract as replace_queue", PlayMode.PlayNow)
    validateCase("play_now_correct_closer", "现在听夜曲、红豆，最后仍用晴天收尾", listOf(catalog[2], catalog[4], catalog[0]),
        MusicGoal(closer = TrackRequirement("晴天", "周杰伦")), true,
        "play_now accepts the correctly ordered complete queue", PlayMode.PlayNow)
    validateCase("closer_not_last", "最后一首一定是晴天", catalog.take(2),
        MusicGoal(closer = TrackRequirement("晴天", placement = TrackPlacement.Closer)), false, "closer must be last")
    validateCase("missing_required_track", "必须有红豆", catalog.take(2),
        MusicGoal(mustInclude = listOf(TrackRequirement("红豆"))), false, "reject missing required track")
    validateCase("english_only_wrong_queue", "换成全英文歌", catalog.take(3),
        MusicGoal(hardLanguages = listOf("en")), false, "reject all-Chinese queue against required language")
    validateCase("genre_not_validated", "换成爵士，不要流行歌", catalog.take(3),
        MusicGoal(hardGenres = listOf("jazz")), false, "require evidence for hard genre or reject unverified tracks")
    suspend fun runCase(id: String, text: String, script: List<String>, expected: String,
                        fail: String? = null, cancel: String? = null,
                        check: (Fixture, TurnOutcome?, Throwable?) -> Boolean) {
        val fixture = Fixture(script, fail, cancel)
        DiagnosticsLogStore.events.clear()
        var outcome: TurnOutcome? = null
        var error: Throwable? = null
        try { outcome = fixture.loop.run(fixture.input(text), fixture.executor) } catch (e: Throwable) { error = e }
        val actual = JSONObject().put("actions", JSONArray(fixture.actions)).put("providerCalls", fixture.providerCalls)
            .put("reply", outcome?.reply).put("error", error?.javaClass?.simpleName)
            .put("observations", JSONArray(fixture.observations)).put("events", JSONArray(DiagnosticsLogStore.events))
        result(id, text, check(fixture, outcome, error), expected, actual)
    }
    runCase("simple_like", "收藏这首", listOf(assistant(tool("like_current", obj("""{"like":true,"more_actions_pending":false}""")))),
        "exactly one like action") { fx, out, e -> fx.actions.size == 1 && out != null && e == null }
    runCase("malformed_string", "收藏这首", listOf(assistant(tool("like_current", "{\"like\":")), final("参数错误，未执行。")),
        "malformed arguments cannot execute") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("malformed_array", "不要收藏这首", listOf(assistant(tool("like_current", JSONArray()))),
        "non-object arguments cannot execute") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("malformed_null", "不要收藏这首", listOf(assistant(tool("like_current", JSONObject.NULL))),
        "null arguments cannot execute") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("false_success", "播放晴天", listOf(final("播放成功，正在为你播放晴天。")),
        "cannot report success without execution") { _, out, _ -> out == null || !out.reply.contains("播放成功") }
    runCase("honest_negation", "能帮我排个周杰伦专场吗", listOf(final("还没排好周杰伦专场，请先选歌。")),
        "honest explanation accepted") { _, out, _ -> out?.reply == "还没排好周杰伦专场，请先选歌。" }
    runCase("failure_then_success", "收藏这首，再跳过", listOf(assistant(
        tool("like_current", obj("""{"like":true,"more_actions_pending":true}""")),
        tool("skip_current", obj("""{"more_actions_pending":false}""")))),
        "both actions attempted and failed like reported", fail = "likeCurrent") { fx, out, _ ->
        fx.actions.size == 2 && out?.trace?.execution?.contains("like:false") == true }
    runCase("cancel_propagation", "收藏这首", listOf(assistant(tool("like_current")), final("无法执行。")),
        "CancellationException must propagate; no subsequent provider call", cancel = "likeCurrent") { fx, _, e ->
        e is CancellationException && fx.providerCalls == 1 }
    runCase("partial_omission", "收藏这首，再播放红豆", listOf(
        assistant(tool("like_current", obj("""{"more_actions_pending":true}"""))),
        final("收藏完成，但红豆没找到，播放没有执行。")),
        "partial failure message retained") { _, out, _ -> out?.reply?.contains("没找到") == true }
    runCase("duplicate_skip", "跳过这首", listOf(
        assistant(tool("skip_current", obj("""{"more_actions_pending":true}"""))),
        assistant(tool("skip_current", obj("""{"more_actions_pending":false}""")))),
        "same request does not skip twice") { fx, _, _ -> fx.actions.count { it.optString("method") == "skip" } == 1 }
    fun commit(keys: List<String>, operation: String, pending: Boolean, extra: JSONObject = JSONObject()): JSONObject {
        return tool("commit_queue", extra.put("track_keys", JSONArray(keys)).put("operation", operation)
            .put("more_actions_pending", pending))
    }
    runCase("direct_play", "现在播放稻香", listOf(assistant(commit(listOf("t2"), "play_now", false,
        obj("""{"intent_mode":"exact_track"}""")))), "execute playQueue for 稻香") { fx, _, e ->
        e == null && fx.actions.singleOrNull()?.optString("method") == "playQueue" && fx.current.title == "稻香" }
    runCase("injected_play_denied", "只搜索稻香，不要播放", listOf(assistant(
        commit(listOf("t2"), "play_now", false, obj("""{"intent_mode":"exact_track"}"""))), final("没有执行播放。")),
        "read-only request rejects an injected playback action") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("closer_not_insert_next", "先播放晴天，最后用稻香收尾", listOf(assistant(
        commit(listOf("t2"), "insert_next", false, obj("""{"intent_mode":"exact_track"}"""))), final("没有完成收尾调整。")),
        "a closer cannot be incorrectly inserted before the remaining queue") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("negative_playlist_denied", "收藏这首，不要加入通勤歌单", listOf(assistant(
        tool("like_current"), tool("modify_playlist_current", obj("""{"playlist_name":"通勤"}"""))), final("没有加入歌单。")),
        "explicitly forbidden playlist change cannot execute") { fx, _, _ ->
        fx.actions.map { it.optString("method") } == listOf("likeCurrent") }
    runCase("negative_skip_denied", "不要跳到下一首", listOf(assistant(tool("skip_current")), final("没有跳过。")),
        "explicitly forbidden skip cannot execute") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("direct_insert", "这首别打断，下一首放稻香", listOf(assistant(commit(listOf("t2"), "insert_next", false,
        obj("""{"intent_mode":"exact_track","jump_to_inserted":false}""")))), "insert without changing current") { fx, _, e ->
        e == null && fx.actions.singleOrNull()?.optString("method") == "insertNext" && fx.current.title == "晴天" }
    listOf("replace_queue", "insert_next").forEach { operation ->
        runCase("direct_plain_want_$operation", "我想听周杰伦的歌", listOf(assistant(
            commit(listOf("t2", "t3"), operation, false,
                obj("""{"intent_mode":"artist_focus","artists":["周杰伦"],"preserve_current":true}""")))),
            "new requested queue starts at 稻香; old 晴天 is removed") { fx, _, e ->
            e == null && fx.actions.singleOrNull()?.optString("method") == "playQueue" &&
                fx.actions.single().optBoolean("preserveCurrent").not() &&
                fx.current.title == "稻香" && fx.queue.map { it.title } == listOf("稻香", "夜曲") }
    }
    runCase("direct_explicit_later", "后面想听周杰伦的歌", listOf(assistant(
        commit(listOf("t2", "t3"), "replace_queue", false,
            obj("""{"intent_mode":"artist_focus","artists":["周杰伦"],"preserve_current":true}""")))),
        "explicit later retains current 晴天 and changes its future queue") { fx, _, e ->
        e == null && fx.actions.singleOrNull()?.optBoolean("preserveCurrent") == true &&
            fx.current.title == "晴天" && fx.queue.map { it.title } == listOf("晴天", "稻香", "夜曲") }
    runCase("mixed_two_commits", "先播放稻香，然后下一首放夜曲", listOf(assistant(
        commit(listOf("t2"), "play_now", true, obj("""{"intent_mode":"exact_track"}""")),
        commit(listOf("t3"), "insert_next", false, obj("""{"intent_mode":"exact_track"}""")))),
        "playQueue 稻香 then insertNext 夜曲") { fx, _, _ ->
        fx.actions.map { it.optString("method") } == listOf("playQueue", "insertNext") }
    runCase("search_then_play", "现在播放王菲的红豆", listOf(
        assistant(tool("search_tracks", obj("""{"query":"王菲 红豆"}"""))),
        assistant(commit(listOf("t4"), "play_now", false, obj("""{"intent_mode":"exact_track"}""")))),
        "real search observation binds returned key to 红豆") { fx, _, e ->
        e == null && fx.searches == listOf("王菲 红豆") && fx.current.title == "红豆" }
    runCase("original_correction_current_track", "我要听原唱的", listOf(
        final("当前正在播放的《晴天》-周杰伦就是原唱，无需切换。"),
        assistant(commit(listOf("t1"), "play_now", false,
            obj("""{"intent_mode":"exact_track","target_title":"晴天","target_artist":"周杰伦","action_id":"a1"}""")))),
        "unverified status is rejected, then the existing matching track is really submitted once without an unnecessary plan") { fx, _, e ->
        e == null && fx.actions.size == 1 && fx.actions.single().optString("method") == "playQueue" &&
            fx.current.title == "晴天" && fx.current.artist == "周杰伦" }
    runCase("unknown_tool", "音量调到30%", listOf(assistant(tool("set_volume", obj("""{"volume":30}"""))),
        final("暂不支持调整音量。")), "unknown tool must not execute") { fx, out, e ->
        fx.actions.isEmpty() && out?.reply == "暂不支持调整音量。" && e == null }
    runCase("like_playlist_skip", "收藏这首，加入通勤歌单，然后跳过", listOf(assistant(
        tool("like_current", obj("""{"like":true,"more_actions_pending":true}""")),
        tool("modify_playlist_current", obj("""{"playlist_name":"通勤","add":true,"more_actions_pending":true}""")),
        tool("skip_current", obj("""{"more_actions_pending":false}""")))),
        "three side effects in requested order") { fx, _, e ->
        e == null && fx.actions.map { it.optString("method") } == listOf("likeCurrent", "modifyPlaylist", "skip") }
    runCase("create_play_like", "新建歌单通勤，加入周杰伦的晴天和稻香，再播放稻香并收藏", listOf(assistant(
        tool("create_playlist_from_tracks", obj("""{"playlist_name":"通勤","tracks":[{"title":"晴天","artist":"周杰伦"},{"title":"稻香","artist":"周杰伦"}],"more_actions_pending":true}""")),
        commit(listOf("t2"), "play_now", true, obj("""{"intent_mode":"exact_track"}""")),
        tool("like_current", obj("""{"like":true,"more_actions_pending":false}""")))),
        "create playlist, play 稻香, like new current 稻香") { fx, _, e ->
        e == null && fx.actions.map { it.optString("method") } == listOf("createPlaylist", "playQueue", "likeCurrent") &&
            fx.actions.last().optString("currentBefore") == "稻香" }
    runCase("list_read_play_like", "打开通勤歌单播放，然后收藏第一首", listOf(
        assistant(tool("plan_actions", obj("""{"actions":[{"id":"play","tool":"commit_queue","description":"播放通勤歌单"},{"id":"like","tool":"like_current","description":"收藏第一首"}]}""")), tool("list_playlists")),
        assistant(tool("get_playlist_tracks", obj("""{"playlist_name":"通勤"}"""))),
        assistant(commit(listOf("t1", "t2", "t3"), "replace_queue", true,
            obj("""{"intent_mode":"playlist","playlist_name":"通勤"}""")),
            tool("like_current", obj("""{"more_actions_pending":false}""")))),
        "list/read observations followed by playback and like") { fx, _, e ->
        e == null && fx.providerCalls == 3 && fx.actions.map { it.optString("method") } == listOf("playQueue", "likeCurrent") }
    runCase("multi_early_finish", "收藏这首，然后加入通勤歌单，再跳过", listOf(
        assistant(tool("like_current", obj("""{"more_actions_pending":false}"""))),
        assistant(tool("modify_playlist_current", obj("""{"playlist_name":"通勤","more_actions_pending":true}""")),
            tool("skip_current", obj("""{"more_actions_pending":false}""")))),
        "incomplete compound request must not silently close after first tool") { fx, _, _ -> fx.actions.size == 3 }
    runCase("failed_like_continue_playlist_skip", "收藏这首，加入通勤歌单，然后跳过", listOf(assistant(
        tool("like_current", obj("""{"more_actions_pending":true}""")),
        tool("modify_playlist_current", obj("""{"playlist_name":"通勤","more_actions_pending":true}""")),
        tool("skip_current", obj("""{"more_actions_pending":false}""")))),
        "failed first step does not prevent independent remaining actions", fail = "likeCurrent") { fx, out, e ->
        e == null && fx.actions.size == 3 && out?.trace?.execution?.contains("like:false") == true }
    // Real loop invocations across turns; the model decisions below are explicitly scripted.
    // This measures context transport and action dispatch, not model comprehension or auto-radio.
    val conversation = Fixture(listOf(
        assistant(tool("search_tracks", obj("""{"query":"陈奕迅 十年"}"""))),
        assistant(commit(listOf("t1", "t4"), "replace_queue", false,
            obj("""{"intent_mode":"artist_focus","artists":["周杰伦","陈奕迅"]}"""))),
        assistant(tool("search_tracks", obj("""{"query":"王菲 红豆"}"""))),
        assistant(commit(listOf("t3"), "insert_next", false,
            obj("""{"intent_mode":"artist_focus","artists":["王菲"],"jump_to_inserted":false}"""))),
        assistant(commit(listOf("t1", "t2", "t3"), "replace_queue", false,
            obj("""{"intent_mode":"artist_focus","artists":["周杰伦","陈奕迅","王菲"]}"""))),
    ))
    val turn1 = conversation.loop.run(conversation.input("我要听周杰伦和陈奕迅的歌"), conversation.executor)
    result("conversation_initial", "第1轮：我要听周杰伦和陈奕迅的歌",
        turn1 != null && conversation.queue.map { it.artist }.toSet() == setOf("周杰伦", "陈奕迅"),
        "dispatch requested two-artist queue", conversation.queue.map { "${it.artist}-${it.title}" })
    val turn2 = conversation.loop.run(conversation.input("这首不要打断，再加点王菲进去"), conversation.executor)
    result("conversation_add", "第2轮：这首不要打断，再加点王菲进去",
        turn2 != null && conversation.current.title == "晴天" && conversation.queue[1].artist == "王菲",
        "insert 王菲 without interrupting current song; continuation source not covered", conversation.queue.map { "${it.artist}-${it.title}" })
    val conversationData = JSONObject(conversation.prompts[2]
        .substringAfter("[untrusted_context_json]\n").substringBefore("\n[/untrusted_context_json]"))
    val contextQueue = conversationData.getJSONArray("current_queue")
    result("conversation_context", "第2轮是否收到上一轮事实和当前队列",
        conversationData.toString().contains("我要听周杰伦和陈奕迅的歌") &&
            (0 until contextQueue.length()).any { index -> contextQueue.getJSONObject(index).let {
                it.optString("artist") == "陈奕迅" && it.optString("title") == "十年"
            } },
        "prior execution and current queue reach provider prompt", conversation.prompts[2])
    val turn3 = conversation.loop.run(conversation.input("替换成这三个人的歌，后续也只放他们"), conversation.executor)
    result("conversation_replace_union", "第3轮：替换成这三个人的歌，后续也只放他们",
        turn3 != null && conversation.actions.last().optJSONArray("artists")?.length() == 3,
        "correct explicit model union reaches executor; real auto-radio not covered", conversation.actions.last())
    runCase("style_single_commit_rejected", "我要听rnb", listOf(
        assistant(tool("search_tracks", obj("""{"query":"晴天"}"""))),
        assistant(commit(listOf("t1"), "play_now", false, obj("""{"intent_mode":"exact_track","target_title":"晴天"}"""))),
        final("目前只找到一首，还没有替换队列。")),
        "direct commit cannot present a single model-chosen song as a whole group") { fx, _, _ -> fx.actions.isEmpty() }
    runCase("opening_wrong_order_rejected", "来一组歌，第一首要周杰伦的稻香", listOf(
        assistant(tool("search_tracks", obj("""{"query":"周杰伦"}"""))),
        assistant(commit(listOf("t1", "t2"), "replace_queue", false, obj("""{"intent_mode":"open_recommendation"}"""))),
        final("第一首顺序没有满足，还没有替换队列。")),
        "manual commit must put the requested head first") { fx, _, _ -> fx.actions.isEmpty() }
    ContextReliability.runCases(::result)
    PlayerExecutorReliability.runCases(::result)
    TaskRetryReliability.runCases(::result)
    SearchFailureReliability.runCases(::result)
    productionResolverReliability().forEach { (id, failure) ->
        result(id, "真实 MusicResolver + 受控目录/召回边界", failure == null, "production resolution constraints hold", failure ?: "ok")
    }
    File(args[0]).writeText(results.toString(2))
    val passed = (0 until results.length()).count { results.getJSONObject(it).getBoolean("passed") }
    println("RESULTS ${results.length()} cases; $passed passed; ${args[0]}")
    if (passed != results.length()) kotlin.system.exitProcess(1)
}
