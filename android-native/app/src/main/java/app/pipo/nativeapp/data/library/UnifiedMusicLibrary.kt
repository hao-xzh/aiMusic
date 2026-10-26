package app.pipo.nativeapp.data.library

import android.content.Context
import android.util.AtomicFile
import app.pipo.nativeapp.DiagnosticsLogStore
import app.pipo.nativeapp.StabilityDiagnostics
import app.pipo.nativeapp.data.MusicPage
import app.pipo.nativeapp.data.MusicPlaylist
import app.pipo.nativeapp.data.MusicPlaylistCatalog
import app.pipo.nativeapp.data.MusicPlaylistMembership
import app.pipo.nativeapp.data.MusicProvider
import app.pipo.nativeapp.data.MusicProviderError
import app.pipo.nativeapp.data.MusicProviderException
import app.pipo.nativeapp.data.MusicSourceRef
import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.NativeTrackJson
import app.pipo.nativeapp.data.RecordingMatcher
import app.pipo.nativeapp.data.SearchVersionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** A user-visible playlist is only merged after the user explicitly binds its remote IDs. */
data class UnifiedPlaylist(
    val id: String,
    val name: String,
    val bindings: List<MusicPlaylist>,
    val tracks: List<NativeTrack>,
)

/** A cross-catalog candidate needs an explicit choice when metadata cannot prove one recording. */
data class PendingLibraryMatch(
    val id: String,
    val track: NativeTrack,
    val candidates: List<NativeTrack>,
    val targetProviderId: String,
    val playlistId: String = "",
    val targetPlaylistKey: String = "",
)

/** Result of one explicit like request; queued or ambiguous providers are never reported as done. */
data class FavoriteWriteResult(
    val confirmedProviderIds: List<String>,
    val pendingProviderIds: List<String>,
    val unmatchedProviderIds: List<String> = emptyList(),
)

data class UnifiedLibraryState(
    val playlists: List<UnifiedPlaylist> = emptyList(),
    val pendingMatches: List<PendingLibraryMatch> = emptyList(),
    val pendingCount: Int = 0,
    val syncing: Boolean = false,
    val backgroundSyncing: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val playlistErrors: Map<String, List<String>> = emptyMap(),
    val accountKey: String = "",
)

/**
 * Account-scoped, conservative library synchronizer. It keeps only catalog metadata, track
 * snapshots and durable write intents; provider credentials and signed stream URLs never enter
 * this store. A caller invokes [refresh] on foreground entry and [retry] for failed intents.
 */
class UnifiedMusicLibrary(
    context: Context,
    providers: List<MusicProvider>,
    scope: CoroutineScope,
) {
    private val sourceById = providers.associateBy { it.id }
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val libraryScope = scope
    private var favoriteFanoutMigrationPending = false
    private val disk = readDisk()
    private val _state = MutableStateFlow(UnifiedLibraryState())
    val state: StateFlow<UnifiedLibraryState> = _state.asStateFlow()

    /** Changes immediately hide the previous account's cached library, even during a request. */
    private val accountEpoch = AtomicLong(0L)
    @Volatile private var visibleAccountKey = ""
    @Volatile private var annotationIndex: Map<String, String> = emptyMap()
    @Volatile private var favoriteReadiness: Map<String, Boolean> = emptyMap()
    @Volatile private var favoriteMemberships: Map<String, Boolean> = emptyMap()
    @Volatile private var refreshRequest: RefreshRequest? = null
    private val automaticLock = Any()
    @Volatile private var automaticJob: Job? = null
    private var foregroundOperations = 0

    init {
        if (favoriteFanoutMigrationPending) {
            disk.workspaces.values.firstOrNull()?.let(::persist)
        }
        scope.launch {
            StabilityDiagnostics.networkRecoveries.drop(1).collect {
                // Re-read remote state after recovery; never replay an uncertain POST directly.
                delay(1_000L)
                if ((state.value.errors.isNotEmpty() || state.value.playlistErrors.isNotEmpty()) && accountSnapshot().owners.isNotEmpty()) {
                    try {
                        DiagnosticsLogStore.record("unified_library", "network_recovery_refresh")
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Existing source errors and persisted actions remain available for retry.
                    }
                }
            }
        }
        sourceById.values.forEach { provider ->
            scope.launch {
                provider.connection.collect {
                    val key = accountSnapshot().key
                    if (key != visibleAccountKey) {
                        synchronized(automaticLock) { automaticJob?.cancel() }
                        visibleAccountKey = key
                        annotationIndex = emptyMap()
                        favoriteReadiness = emptyMap()
                        favoriteMemberships = emptyMap()
                        accountEpoch.incrementAndGet()
                        _state.value = UnifiedLibraryState()
                    }
                }
            }
        }
    }

    suspend fun refresh() = foreground {
        val accountKey = accountSnapshot().key
        val request = refreshMutex.withLock {
            refreshRequest?.takeIf { it.accountKey == accountKey && it.deferred.isActive } ?: run {
                val deferred = libraryScope.async(start = CoroutineStart.LAZY) { refreshInternal() }
                RefreshRequest(accountKey, deferred).also { next ->
                    refreshRequest = next
                    deferred.invokeOnCompletion {
                        libraryScope.launch {
                            refreshMutex.withLock {
                                if (refreshRequest === next) refreshRequest = null
                            }
                        }
                    }
                    deferred.start()
                }
            }
        }
        request.deferred.await()
    }

    private suspend fun refreshInternal() {
        val shouldContinue = onIo {
        sourceById.values.forEach { provider ->
            try {
                provider.refreshAccount()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The provider connection and the resulting source-specific error remain visible.
            }
        }
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        DiagnosticsLogStore.record("unified_library", "refresh_started", mapOf("sources" to snapshot.owners.size))
        var completed = false
        withSyncing(workspace, snapshot) {
            try {
                refreshCatalog(workspace, snapshot)
                markAutomaticPending(workspace)
                persist(workspace)
                completed = true
            } finally {
                DiagnosticsLogStore.record(
                    "unified_library",
                    "refresh_finished",
                    mapOf("completed" to completed, "sources" to snapshot.owners.size),
                )
            }
        }
        hasAutomaticWork(workspace)
        }
        if (shouldContinue) scheduleAutomatic()
    }

    suspend fun sync(playlistId: String) = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        withSyncing(workspace, snapshot) {
            val favorite = workspace.favorite
            val favoriteBinding = favorite?.bindings?.firstOrNull { favoriteViewId(it) == playlistId }
            if (favoriteBinding != null) {
                refreshBinding(workspace, snapshot, favoriteBinding)
                favorite.autoReadCursor = favorite.bindings.indexOfFirst { !it.confirmed || workspace.errors.containsKey(it.playlist.key) }
                    .let { if (it < 0) favorite.bindings.size else it }
                resetAutomaticRetry(favorite)
                val retried = retryFavoriteIntents(workspace, snapshot, favorite, favoriteBinding.playlist.key)
                if (retried.isNotEmpty()) {
                    executeOutbox(workspace, snapshot, favorite.id, MANUAL_ACTION_LIMIT, retried)
                }
            } else {
                val record = workspace.records.firstOrNull { it.id == playlistId }
                    ?: throw IllegalArgumentException("未找到已绑定歌单")
                refreshRecord(workspace, snapshot, record)
                resetAutomaticRetry(record)
            }
            persist(workspace)
        }
    } }

    suspend fun create(name: String, providerIds: Set<String>) = foreground { onIo {
        require(name.isNotBlank()) { "歌单名称不能为空" }
        require(providerIds.isNotEmpty()) { "请至少选择一个音源" }
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val selected = providerIds.sorted().map { id ->
            sourceById[id] ?: throw IllegalArgumentException("未接入音源：$id")
        }
        selected.forEach { provider ->
            require(snapshot.owners[provider.id] != null) { "${provider.id} 尚未登录" }
            require(provider.capabilities.playlistCreate) { "${provider.id} 不支持新建歌单" }
        }
        val request = CreateIntent(UUID.randomUUID().toString(), name.trim(), selected.map { it.id }.toMutableList())
        workspace.creates += request
        persist(workspace)
        createPending(workspace, snapshot, request)
        persist(workspace)
        if (isCurrent(snapshot)) publish(workspace, snapshot)
    } }

    suspend fun bind(firstId: String, secondId: String) = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val first = resolveCatalogBinding(workspace, firstId)
        val second = resolveCatalogBinding(workspace, secondId)
        require(first.key != second.key) { "请选择两个不同的远端歌单" }
        require(first.providerId != second.providerId) { "统一绑定需要来自不同音源" }
        require(snapshot.owners.containsKey(first.providerId) && snapshot.owners.containsKey(second.providerId)) {
            "账号已切换，请先刷新资料库"
        }
        val firstRecord = workspace.records.singleOrNull { record -> record.bindings.any { it.playlist.key == first.key } }
            ?: throw IllegalStateException("远端歌单尚未准备完成，请刷新后重试")
        val secondRecord = workspace.records.singleOrNull { record -> record.bindings.any { it.playlist.key == second.key } }
            ?: throw IllegalStateException("远端歌单尚未准备完成，请刷新后重试")
        require(firstRecord !== secondRecord && firstRecord.singleRemote && secondRecord.singleRemote) { "请选择两张尚未绑定的单端歌单" }
        val mergedBindings = (firstRecord.bindings + secondRecord.bindings).distinctBy { it.playlist.key }.toMutableList()
        workspace.records.remove(firstRecord)
        workspace.records.remove(secondRecord)
        workspace.records += LibraryRecord(
            id = UUID.randomUUID().toString(),
            name = first.name,
            bindings = mergedBindings,
        )
        persist(workspace)
        publish(workspace, snapshot)
    } }

    suspend fun like(track: NativeTrack, liked: Boolean): FavoriteWriteResult = timedFavorite(track, liked) { foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val favorites = workspace.favorite
        if (favorites == null) {
            if (liked) {
                snapshot.owners.keys.forEach { providerId ->
                    stageFavoriteIntent(workspace, snapshot, null, track, providerId)
                }
            } else {
                cancelFavoriteFanout(workspace, track)
            }
            if (liked || track.musicSource != null) {
                persist(workspace)
            }
            return@onIo favoriteWriteResult(workspace, snapshot, null, track, liked)
        }
        withSyncing(workspace, snapshot) {
            resetAutomaticRetry(favorites)
            val actionIds = changeMembership(workspace, snapshot, favorites, track, liked, favorites = true)
            if (liked) {
                snapshot.owners.keys.filter { providerId ->
                    favorites.bindings.none { it.playlist.providerId == providerId }
                }.forEach { providerId -> stageFavoriteIntent(workspace, snapshot, null, track, providerId) }
            }
            executeOutbox(workspace, snapshot, favorites.id, MANUAL_ACTION_LIMIT, actionIds)
            persist(workspace)
        }
        favoriteWriteResult(workspace, snapshot, favorites, track, liked)
    } } }

    private suspend fun timedFavorite(track: NativeTrack, liked: Boolean, block: suspend () -> FavoriteWriteResult): FavoriteWriteResult {
        val started = android.os.SystemClock.elapsedRealtime()
        var result: FavoriteWriteResult? = null
        try {
            return block().also { result = it }
        } finally {
            DiagnosticsLogStore.record("unified_library", "favorite_request", mapOf(
                "source" to track.musicSource?.providerId, "liked" to liked,
                "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - started),
                "confirmed" to result?.confirmedProviderIds?.joinToString(","),
                "pending" to result?.pendingProviderIds?.joinToString(","),
                "completed" to (result != null),
            ))
        }
    }

    suspend fun add(playlistId: String, track: NativeTrack) = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val record = workspace.records.firstOrNull { it.id == playlistId } ?: throw IllegalArgumentException("未找到已绑定歌单")
        withSyncing(workspace, snapshot) {
            val actionIds = changeMembership(workspace, snapshot, record, track, desired = true, favorites = false)
            executeOutbox(workspace, snapshot, record.id, MANUAL_ACTION_LIMIT, actionIds)
            persist(workspace)
        }
    } }

    suspend fun remove(playlistId: String, track: NativeTrack) = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val record = workspace.records.firstOrNull { it.id == playlistId } ?: throw IllegalArgumentException("未找到已绑定歌单")
        withSyncing(workspace, snapshot) {
            val actionIds = changeMembership(workspace, snapshot, record, track, desired = false, favorites = false)
            executeOutbox(workspace, snapshot, record.id, MANUAL_ACTION_LIMIT, actionIds)
            persist(workspace)
        }
    } }

    suspend fun confirmMatch(matchId: String, candidate: NativeTrack) = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val pending = workspace.matches.firstOrNull { it.id == matchId } ?: throw IllegalArgumentException("待确认匹配不存在")
        require(candidate.musicSource?.providerId == pending.targetProviderId) { "候选不属于目标音源" }
        require(pending.candidates.any { sourceIdentity(it) == sourceIdentity(candidate) }) { "候选不是该待确认项的一部分" }
        val target = findBinding(workspace, pending.targetPlaylistKey) ?: throw IllegalStateException("目标歌单已不存在")
        if (workspace.suppressions.contains(suppressionKey(target.playlist, recordingKey(workspace, pending.track)))) {
            // A persisted pre-cancel match must never turn a later confirmation into a re-add.
            workspace.matches.remove(pending)
            persist(workspace)
            if (isCurrent(snapshot)) publish(workspace, snapshot)
            throw IllegalStateException("曲目已取消，请先显式重新添加")
        }
        val source = requireNotNull(candidate.musicSource) { "候选缺少音源 ID" }
        linkRecordings(workspace, pending.track, candidate)
            ?: throw IllegalArgumentException("候选与已有录音映射冲突")
        workspace.matches.remove(pending)
        val action = stageAction(workspace, snapshot, target.playlist, source, desired = true, favorites = pending.favorites, track = candidate)
        executeOutbox(workspace, snapshot, pending.playlistId, MANUAL_ACTION_LIMIT, setOf(action.id))
        persist(workspace)
        if (isCurrent(snapshot)) publish(workspace, snapshot)
    } }

    suspend fun retry() = foreground { onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        workspace.errors.clear()
        // An explicit retry reopens only empty candidate lists; non-empty lists still require a manual choice.
        workspace.matches.filter { it.favorites && it.candidates.isEmpty() }.toList().forEach { pending ->
            stageFavoriteIntent(
                workspace,
                snapshot,
                findBinding(workspace, pending.targetPlaylistKey),
                pending.track,
                pending.targetProviderId,
            )
        }
        workspace.matches.removeAll { it.candidates.isEmpty() }
        workspace.creates.filter { !it.uncertain }.toList().forEach { createPending(workspace, snapshot, it) }
        markAutomaticPending(workspace, resetRetry = true)
        persist(workspace)
        if (isCurrent(snapshot)) publish(workspace, snapshot)
    } }

    /** Applies only an already-confirmed source-to-recording mapping; it never searches or guesses. */
    fun annotate(track: NativeTrack): NativeTrack {
        val source = track.musicSource ?: return track
        val recording = annotationIndex[sourceIdentity(source)] ?: return track
        return track.copy(recordingId = recording)
    }

    /** Per-source favorite state; another provider's same recording cannot answer this question. */
    fun isLiked(track: NativeTrack): Boolean? {
        val source = track.musicSource ?: return null
        favoriteMemberships[source.key]?.let { return it }
        return if (favoriteReadiness[source.providerId] == true) false else null
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    /** Foreground work cancels only between persisted automatic slices, before taking [mutex]. */
    private suspend fun pauseAutomatic() {
        val started = android.os.SystemClock.elapsedRealtime()
        val job = synchronized(automaticLock) { automaticJob }
        job?.cancel()
        job?.join()
        DiagnosticsLogStore.record("unified_library", "foreground_sync_wait", mapOf(
            "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - started),
        ))
        synchronized(automaticLock) { if (automaticJob === job) automaticJob = null }
    }

    private suspend fun <T> foreground(block: suspend () -> T): T {
        synchronized(automaticLock) { foregroundOperations += 1 }
        try {
            pauseAutomatic()
            return block()
        } finally {
            val resume = synchronized(automaticLock) {
                foregroundOperations -= 1
                foregroundOperations == 0
            }
            if (resume) scheduleAutomatic()
        }
    }

    private fun scheduleAutomatic() {
        synchronized(automaticLock) {
            if (foregroundOperations > 0) return
            if (automaticJob?.isActive == true) return
            val job = libraryScope.launch(start = CoroutineStart.LAZY) { automaticLoop() }
            automaticJob = job
            job.invokeOnCompletion {
                synchronized(automaticLock) { if (automaticJob === job) automaticJob = null }
            }
            job.start()
        }
    }

    private suspend fun automaticLoop() {
        try {
            while (true) {
                val slice = automaticSlice()
                if (!slice.more) {
                    onIo {
                        val snapshot = activate(accountSnapshot())
                        val workspace = workspace(snapshot)
                        if (!hasAutomaticWork(workspace) && isCurrent(snapshot)) {
                            publish(workspace, snapshot, backgroundSyncing = false)
                        }
                    }
                    return
                }
                if (slice.waitMs > 0L) delay(slice.waitMs) else yield()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: AccountChanged) {
            val resume = try {
                hasCurrentAutomaticWork()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (resume) scheduleAutomaticAfter(AUTO_ACCOUNT_RESUME_DELAY_MS)
        } catch (_: Exception) {
            val wait = try {
                recordAutomaticWorkerFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (wait != null) scheduleAutomaticAfter(wait)
        }
    }

    private suspend fun hasCurrentAutomaticWork(): Boolean = onIo {
        val snapshot = activate(accountSnapshot())
        hasAutomaticWork(workspace(snapshot))
    }

    private suspend fun recordAutomaticWorkerFailure(): Long? = onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val now = System.currentTimeMillis()
        automaticRecords(workspace).filter { hasAutomaticWork(workspace, it) }.forEach { scheduleAutomaticRetry(it) }
        if (!hasAutomaticWork(workspace)) {
            workspace.errors[AUTO_ERROR_KEY] = "自动同步已暂停，请刷新后重试"
            persist(workspace)
            if (isCurrent(snapshot)) publish(workspace, snapshot, backgroundSyncing = false)
            return@onIo null
        }
        workspace.errors[AUTO_ERROR_KEY] = "自动同步暂时停止，将稍后重试"
        persist(workspace)
        publish(workspace, snapshot, backgroundSyncing = true)
        automaticRecords(workspace).filter { hasAutomaticWork(workspace, it) }
            .minOfOrNull { it.autoRetryAtMillis.takeIf { time -> time > now } ?: now + AUTO_RETRY_BASE_MS }
            ?.minus(now)
            ?.coerceAtLeast(AUTO_RETRY_BASE_MS)
    }

    private fun scheduleAutomaticAfter(waitMs: Long) {
        libraryScope.launch {
            delay(waitMs)
            scheduleAutomatic()
        }
    }

    private suspend fun automaticSlice(): AutomaticSlice = onIo {
        val snapshot = activate(accountSnapshot())
        val workspace = workspace(snapshot)
        val candidates = automaticRecords(workspace).filter { hasAutomaticWork(workspace, it) }
        val record = candidates.firstOrNull { it.autoRetryAtMillis <= System.currentTimeMillis() }
            ?: candidates.minByOrNull { it.autoRetryAtMillis }
            ?: run {
                if (isCurrent(snapshot)) publish(workspace, snapshot, backgroundSyncing = false)
                return@onIo AutomaticSlice(false)
            }
        val now = System.currentTimeMillis()
        if (record.autoRetryAtMillis > now) {
            publish(workspace, snapshot, backgroundSyncing = true)
            return@onIo AutomaticSlice(true, record.autoRetryAtMillis - now)
        }

        workspace.errors.remove(AUTO_ERROR_KEY)
        publish(workspace, snapshot, backgroundSyncing = true)
        if (record.singleRemote) {
            val action = nextActionForRecord(workspace, record.id)
            if (action != null) {
                executeOutbox(workspace, snapshot, record.id, AUTO_ACTION_LIMIT)
                if (workspace.actions.any { it.id == action.id }) scheduleAutomaticRetry(record) else resetAutomaticRetry(record)
            }
            persist(workspace)
            publish(workspace, snapshot, backgroundSyncing = true)
            return@onIo automaticSliceResult(workspace)
        }
        if (record === workspace.favorite && (workspace.favoriteIntents.isNotEmpty() || nextActionForRecord(workspace, record.id) != null)) {
            retryFavoriteIntents(workspace, snapshot, record)
            val nextAction = nextActionForRecord(workspace, record.id)
            if (nextAction != null) {
                executeOutbox(workspace, snapshot, record.id, AUTO_ACTION_LIMIT)
            }
            if (workspace.favoriteIntents.isNotEmpty() || nextActionForRecord(workspace, record.id) != null) {
                scheduleAutomaticRetry(record)
            } else resetAutomaticRetry(record)
            persist(workspace)
            publish(workspace, snapshot, backgroundSyncing = true)
            return@onIo automaticSliceResult(workspace)
        }

        if (record.autoReadCursor < record.bindings.size) {
            val binding = record.bindings[record.autoReadCursor]
            refreshBinding(workspace, snapshot, binding)
            if (binding.confirmed && !workspace.errors.containsKey(binding.playlist.key)) {
                record.autoReadCursor += 1
                resetAutomaticRetry(record)
            } else {
                scheduleAutomaticRetry(record)
            }
            persist(workspace)
            publish(workspace, snapshot, backgroundSyncing = true)
            return@onIo automaticSliceResult(workspace)
        }


        if (record === workspace.favorite) return@onIo automaticSliceResult(workspace)

        if (!reconcileAutomaticSlice(workspace, snapshot, record)) {
            scheduleAutomaticRetry(record)
            persist(workspace)
            publish(workspace, snapshot, backgroundSyncing = true)
            return@onIo automaticSliceResult(workspace)
        }
        val nextAction = nextActionForRecord(workspace, record.id)
        if (nextAction != null) {
            executeOutbox(workspace, snapshot, record.id, AUTO_ACTION_LIMIT)
            if (workspace.actions.any { it.id == nextAction.id }) {
                scheduleAutomaticRetry(record)
            } else {
                resetAutomaticRetry(record)
            }
        }
        persist(workspace)
        publish(workspace, snapshot, backgroundSyncing = true)
        automaticSliceResult(workspace)
    }

    private fun automaticSliceResult(workspace: Workspace): AutomaticSlice {
        val earliest = automaticRecords(workspace).filter { hasAutomaticWork(workspace, it) }
            .minOfOrNull { it.autoRetryAtMillis } ?: return AutomaticSlice(false)
        val now = System.currentTimeMillis()
        return AutomaticSlice(true, (earliest - now).coerceAtLeast(0L))
    }

    private fun automaticRecords(workspace: Workspace): List<LibraryRecord> =
        listOfNotNull(workspace.favorite) + workspace.records

    private fun hasAutomaticWork(workspace: Workspace): Boolean = automaticRecords(workspace).any { hasAutomaticWork(workspace, it) }

    private fun hasAutomaticWork(workspace: Workspace, record: LibraryRecord): Boolean {
        if (record.autoBlocked) return false
        if (record === workspace.favorite) {
            return record.autoReadCursor < record.bindings.size ||
                workspace.favoriteIntents.isNotEmpty() ||
                nextActionForRecord(workspace, record.id) != null
        }
        if (record.singleRemote) return nextActionForRecord(workspace, record.id) != null
        return record.autoReadCursor < record.bindings.size ||
            record.autoCursor < automaticPairs(record).size ||
            nextActionForRecord(workspace, record.id) != null
    }

    private fun markAutomaticPending(workspace: Workspace, resetRetry: Boolean = false) {
        automaticRecords(workspace).forEach { record ->
            if (record === workspace.favorite) {
                if (record.autoRetryCount > 0 || resetRetry) resetAutomaticRetry(record)
                return@forEach
            }
            val restart = resetRetry || record.autoBlocked || !hasAutomaticWork(workspace, record)
            if (!record.singleRemote && restart) {
                record.autoReadCursor = 0
                record.autoCursor = 0
            }
            if (restart || record.autoRetryCount > 0) resetAutomaticRetry(record)
        }
    }

    private suspend fun refreshRecord(workspace: Workspace, snapshot: AccountSnapshot, record: LibraryRecord) {
        record.bindings.forEach { binding -> refreshBinding(workspace, snapshot, binding) }
        record.autoReadCursor = record.bindings.indexOfFirst { binding ->
            !binding.confirmed || workspace.errors.containsKey(binding.playlist.key)
        }.let { if (it < 0) record.bindings.size else it }
        record.autoCursor = 0
    }

    private fun scheduleAutomaticRetry(record: LibraryRecord) {
        if (record.autoRetryCount >= MAX_AUTO_RETRY_EXPONENT) {
            record.autoBlocked = true
            record.autoRetryAtMillis = 0L
            return
        }
        record.autoRetryCount = (record.autoRetryCount + 1).coerceAtMost(MAX_AUTO_RETRY_EXPONENT)
        val multiplier = 1L shl record.autoRetryCount
        record.autoRetryAtMillis = System.currentTimeMillis() + (AUTO_RETRY_BASE_MS * multiplier).coerceAtMost(AUTO_RETRY_MAX_MS)
    }

    private fun resetAutomaticRetry(record: LibraryRecord) {
        record.autoRetryCount = 0
        record.autoRetryAtMillis = 0L
        record.autoBlocked = false
    }

    private suspend fun withSyncing(workspace: Workspace, snapshot: AccountSnapshot, block: suspend () -> Unit) {
        publish(workspace, snapshot, syncing = true)
        try {
            block()
        } finally {
            if (isCurrent(snapshot)) publish(workspace, snapshot, syncing = false)
        }
    }

    private fun activate(snapshot: AccountSnapshot): AccountSnapshot {
        if (visibleAccountKey != snapshot.key) {
            visibleAccountKey = snapshot.key
            annotationIndex = emptyMap()
            favoriteReadiness = emptyMap()
            favoriteMemberships = emptyMap()
            accountEpoch.incrementAndGet()
            _state.value = UnifiedLibraryState()
        }
        return snapshot
    }

    private fun accountSnapshot(): AccountSnapshot = AccountSnapshot(
        sourceById.values.mapNotNull { provider ->
            provider.connection.value.account?.id?.takeIf { it.isNotBlank() }?.let { provider.id to it }
        }.toMap(),
        accountEpoch.get(),
    )

    private fun isCurrent(snapshot: AccountSnapshot): Boolean =
        visibleAccountKey == snapshot.key && accountEpoch.get() == snapshot.epoch && accountSnapshot().owners == snapshot.owners

    private fun workspace(snapshot: AccountSnapshot): Workspace = disk.workspaces.getOrPut(snapshot.key) { Workspace() }

    private suspend fun refreshCatalog(workspace: Workspace, snapshot: AccountSnapshot) {
        val active = sourceById.values.filter { snapshot.owners[it.id] != null && it.capabilities.library }
        kotlinx.coroutines.supervisorScope {
            val results = kotlinx.coroutines.channels.Channel<CatalogOutcome>(active.size)
            active.forEach { provider -> launch {
                val outcome = try {
                    requireCurrent(snapshot)
                    CatalogSuccess(provider.id, kotlinx.coroutines.withTimeout(CATALOG_TIMEOUT_MS) {
                        provider.playlistCatalog().also { requireCurrent(snapshot) }
                    })
                } catch (timeout: TimeoutCancellationException) {
                    CatalogFailure(provider.id, "读取歌单超时")
                } catch (cancelled: CancellationException) {
                    CatalogCancelled(cancelled)
                } catch (changed: AccountChanged) {
                    CatalogChanged
                } catch (failure: Exception) {
                    recordOperationFailure("catalog", provider.id, failure)
                    CatalogFailure(provider.id, failure.message ?: "读取歌单失败")
                }
                results.send(outcome)
            } }
            repeat(active.size) {
                when (val outcome = results.receive()) {
                    is CatalogSuccess -> {
                        val catalog = outcome.catalog
                        val incoming = catalog.playlists.filter { it.providerId == outcome.providerId }
                        if (catalog.complete) {
                            workspace.catalog.removeAll { it.providerId == outcome.providerId }
                            workspace.catalog += incoming
                            workspace.errors.remove(outcome.providerId)
                        } else {
                            val merged = linkedMapOf<String, MusicPlaylist>()
                            workspace.catalog.filter { it.providerId == outcome.providerId }.forEach { merged[it.key] = it }
                            incoming.forEach { merged[it.key] = it }
                            workspace.catalog.removeAll { it.providerId == outcome.providerId }
                            workspace.catalog += merged.values
                            workspace.errors[outcome.providerId] = catalog.warning ?: "歌单目录未完整读取"
                        }
                        updateFavorites(workspace)
                        updateOrdinaryRecords(workspace)
                        DiagnosticsLogStore.record(
                            "unified_library",
                            "catalog_source_ready",
                            mapOf("source" to outcome.providerId, "count" to incoming.size, "complete" to catalog.complete),
                        )
                        publish(workspace, snapshot, syncing = true)
                    }
                    is CatalogFailure -> {
                        workspace.errors[outcome.providerId] = outcome.message
                        if (outcome.message == "读取歌单超时") {
                            DiagnosticsLogStore.record(
                                "unified_library",
                                "catalog_source_timeout",
                                mapOf("source" to outcome.providerId),
                            )
                        }
                        publish(workspace, snapshot, syncing = true)
                    }
                    is CatalogCancelled -> throw outcome.cause
                    CatalogChanged -> throw AccountChanged()
                }
            }
            results.close()
        }
        persist(workspace)
    }

    private fun updateFavorites(workspace: Workspace) {
        val old = workspace.favorite
        val previousByKey = old?.bindings?.associateBy { it.playlist.key }.orEmpty()
        val bindings = workspace.catalog.filter { it.isFavorites }.mapTo(mutableListOf()) { playlist ->
            val previous = previousByKey[playlist.key]
            Binding(playlist, previous?.tracks.orEmpty().toMutableList(), previous?.confirmed == true,
                previous?.memberships.orEmpty().toMutableMap())
        }
        val unchanged = old?.bindings?.map { it.playlist.key }?.toSet() == bindings.map { it.playlist.key }.toSet()
        workspace.favorite = if (bindings.isEmpty()) null else LibraryRecord(
            FAVORITES_ID, FAVORITES_NAME, bindings, automatic = true,
            autoReadCursor = if (unchanged) old?.autoReadCursor ?: 0 else 0,
            autoCursor = if (unchanged) old?.autoCursor ?: 0 else 0,
            autoRetryAtMillis = if (unchanged) old?.autoRetryAtMillis ?: 0L else 0L,
            autoRetryCount = if (unchanged) old?.autoRetryCount ?: 0 else 0,
            autoBlocked = if (unchanged) old?.autoBlocked ?: false else false,
        )
    }

    /** Every ordinary remote ID starts as an independent, durable card until an explicit bind. */
    private fun updateOrdinaryRecords(workspace: Workspace) {
        workspace.catalog.filterNot { it.isFavorites }.forEach { playlist ->
            val record = workspace.records.firstOrNull { it.bindings.any { binding -> binding.playlist.key == playlist.key } }
            if (record == null) {
                workspace.records += LibraryRecord(
                    id = "remote:${playlist.key}",
                    name = playlist.name,
                    bindings = mutableListOf(Binding(playlist)),
                    singleRemote = true,
                )
            } else {
                record.bindings.firstOrNull { it.playlist.key == playlist.key }?.playlist = playlist
            }
        }
    }

    private suspend fun refreshBinding(workspace: Workspace, snapshot: AccountSnapshot, binding: Binding) {
        val provider = sourceById[binding.playlist.providerId] ?: return
        if (snapshot.owners[provider.id] == null) return
        try {
            val tracks = completePlaylist(provider, binding.playlist, snapshot)
            val previous = binding.tracks.toList()
            if (binding.confirmed) {
                previous.forEach { old ->
                    if (tracks.none { sourceIdentity(it) == sourceIdentity(old) }) {
                        workspace.suppressions += suppressionKey(binding.playlist, recordingKey(workspace, old))
                    }
                }
            }
            binding.tracks.clear()
            binding.tracks += tracks.map { annotate(workspace, it) }
            binding.memberships.clear()
            binding.confirmed = true
            workspace.errors.remove(binding.playlist.key)
            // A complete fresh read can confirm old intents without replaying any write.
            val present = tracks.map(::sourceIdentity).toSet()
            val confirmed = workspace.actions.filter { action ->
                action.playlistKey == binding.playlist.key && (sourceIdentity(action.source) in present) == action.desired
            }
            workspace.actions.removeAll(confirmed.toSet())
            confirmed.forEach { workspace.errors.remove(it.id) }
        } catch (timeout: TimeoutCancellationException) {
            // A timeout is not an empty remote snapshot: preserve the last confirmed tracks.
            workspace.errors[binding.playlist.key] = "读取曲目超时"
            DiagnosticsLogStore.record(
                "unified_library",
                "playlist_tracks_timeout",
                mapOf("source" to provider.id, "favorites" to binding.playlist.isFavorites),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: AccountChanged) {
            throw changed
        } catch (failure: Exception) {
            // A failed or partial read must leave the previous complete snapshot intact.
            recordOperationFailure("playlist_read", provider.id, failure)
            workspace.errors[binding.playlist.key] = failure.message ?: "读取曲目失败"
        }
    }

    private fun automaticPairs(record: LibraryRecord): List<AutomaticPair> {
        val desired = record.bindings.flatMap { it.tracks }.distinctBy(::sourceIdentity)
        return record.bindings.flatMap { target -> desired.map { track -> AutomaticPair(target, track) } }
    }

    /** Stages at most a few unresolved source lookups; cursor advancement is persisted by caller. */
    private suspend fun reconcileAutomaticSlice(workspace: Workspace, snapshot: AccountSnapshot, record: LibraryRecord): Boolean {
        val pairs = automaticPairs(record)
        var cursor = record.autoCursor.coerceIn(0, pairs.size)
        var lookups = 0
        while (cursor < pairs.size) {
            val pair = pairs[cursor]
            val target = pair.target
            val track = pair.track
            val provider = sourceById[target.playlist.providerId]
            if (provider == null || !target.playlist.canEdit || snapshot.owners[provider.id] == null ||
                workspace.suppressions.contains(suppressionKey(target.playlist, recordingKey(workspace, track))) ||
                target.tracks.any { sameRecording(workspace, it, track) } || hasStagedAction(workspace, target, track)
            ) {
                cursor += 1
                continue
            }
            if (lookups >= AUTO_LOOKUP_LIMIT) break
            lookups += 1
            val source = try {
                resolveTargetSource(workspace, snapshot, record, target, track, favorites = record.automatic)
            } catch (_: LookupUnavailable) {
                // A failed request does not mean this song has no match. Resume this same pair.
                record.autoCursor = cursor
                return false
            }
            source?.let {
                stageAction(workspace, snapshot, target.playlist, source, desired = true, favorites = record.automatic, track = track)
            }
            cursor += 1
        }
        record.autoCursor = cursor
        if (cursor >= pairs.size) {
            record.bindings.forEach { workspace.errors.remove("search:${record.id}:${it.playlist.providerId}") }
        }
        return true
    }

    private fun hasStagedAction(workspace: Workspace, target: Binding, track: NativeTrack): Boolean {
        val recording = recordingKey(workspace, track)
        return workspace.actions.any { action ->
            action.desired && action.playlistKey == target.playlist.key &&
                (workspace.recordings[sourceIdentity(action.source)] ?: sourceIdentity(action.source)) == recording
        }
    }

    private suspend fun changeMembership(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        record: LibraryRecord,
        track: NativeTrack,
        desired: Boolean,
        favorites: Boolean,
    ): Set<String> {
        val staged = linkedSetOf<String>()
        val primarySource = track.musicSource
        if (!desired && favorites) cancelFavoriteFanout(workspace, track)
        record.bindings.forEach { target ->
            val provider = sourceById[target.playlist.providerId] ?: return@forEach
            val trackKey = recordingKey(workspace, track)
            if (desired) workspace.suppressions.remove(suppressionKey(target.playlist, trackKey))
            else {
                workspace.suppressions += suppressionKey(target.playlist, trackKey)
                if (!favorites) {
                    // Cancel durable opposite writes even when this binding has not loaded the track.
                    workspace.actions.removeAll { action ->
                        action.desired && action.playlistKey == target.playlist.key &&
                            (sourceIdentity(action.source) == sourceIdentity(track) ||
                                workspace.recordings[sourceIdentity(action.source)] == trackKey)
                    }
                    workspace.matches.removeAll { pending ->
                        pending.playlistId == record.id &&
                            (sourceIdentity(pending.track) == sourceIdentity(track) || recordingKey(workspace, pending.track) == trackKey)
                    }
                }
            }
            if (!target.playlist.canEdit || snapshot.owners[provider.id] == null) return@forEach
            val existing = target.tracks.firstOrNull { sameRecording(workspace, it, track) }
            if (!desired) {
                // A user removal changes only the source they selected. Other bindings retain
                // their remote membership; suppression prevents the union rule from adding it back.
                val existingSource = existing?.musicSource ?: primarySource?.takeIf { it.providerId == provider.id }
                if (primarySource?.providerId == provider.id && existingSource != null) {
                    staged += stageAction(workspace, snapshot, target.playlist, existingSource, desired = false, favorites = favorites, track = track).id
                }
            } else if (desired && existing == null) {
                // Confirm the selected source first; cross-provider matching remains durable work.
                if (favorites && provider.id != primarySource?.providerId) {
                    stageFavoriteIntent(workspace, snapshot, target, track)
                    return@forEach
                }
                val source = try {
                    resolveTargetSource(workspace, snapshot, record, target, track, favorites)
                } catch (_: LookupUnavailable) {
                    if (favorites) stageFavoriteIntent(workspace, snapshot, target, track)
                    return@forEach
                } ?: return@forEach
                staged += stageAction(workspace, snapshot, target.playlist, source, desired = true, favorites = favorites, track = track).id
            }
        }
        persist(workspace)
        return staged
    }

    /** Cancels only unfinished writes from this explicit fanout; existing remote favorites remain untouched. */
    private fun cancelFavoriteFanout(workspace: Workspace, track: NativeTrack) {
        val trackKey = recordingKey(workspace, track)
        fun sameTrack(candidate: NativeTrack): Boolean =
            sourceIdentity(candidate) == sourceIdentity(track) || recordingKey(workspace, candidate) == trackKey

        val actions = workspace.actions.filter { it.favorites && it.desired &&
            (sourceIdentity(it.source) == sourceIdentity(track) || workspace.recordings[sourceIdentity(it.source)] == trackKey) }
        val matches = workspace.matches.filter { it.favorites && sameTrack(it.track) }
        workspace.favoriteIntents.removeAll { sameTrack(it.track) }
        workspace.actions.removeAll(actions.toSet())
        workspace.matches.removeAll(matches.toSet())
        workspace.errors.keys.removeAll(actions.map { it.id }.toSet() + matches.map { it.id }.toSet())
    }

    /** A failed cross-source lookup from an explicit like remains retryable without assuming a match. */
    private fun stageFavoriteIntent(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        target: Binding?,
        track: NativeTrack,
        requestedProviderId: String = target?.playlist?.providerId.orEmpty(),
    ) {
        val providerId = requestedProviderId
        val accountId = snapshot.owners[providerId] ?: return
        val existing = workspace.favoriteIntents.any { request ->
            request.targetPlaylistKey == target?.playlist?.key.orEmpty() && request.providerId == providerId &&
                sourceIdentity(request.track) == sourceIdentity(track)
        }
        if (!existing) {
            workspace.favoriteIntents += FavoriteIntent(
                id = UUID.randomUUID().toString(),
                targetPlaylistKey = target?.playlist?.key.orEmpty(),
                providerId = providerId,
                accountId = accountId,
                track = annotate(workspace, track),
            )
        }
    }

    /** Retries only explicit like intents; it never recreates historical cross-library union work. */
    private suspend fun retryFavoriteIntents(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        favorite: LibraryRecord,
        onlyTargetPlaylistKey: String? = null,
    ): Set<String> {
        var attempted = 0
        val staged = linkedSetOf<String>()
        workspace.favoriteIntents.toList().forEach { request ->
            if (attempted >= FAVORITE_RETRY_LOOKUP_LIMIT) return@forEach
            if (snapshot.owners[request.providerId] != request.accountId) return@forEach
            val target = favorite.bindings.firstOrNull {
                it.playlist.key == request.targetPlaylistKey ||
                    (request.targetPlaylistKey.isBlank() && it.playlist.providerId == request.providerId)
            } ?: return@forEach
            if (onlyTargetPlaylistKey != null && target.playlist.key != onlyTargetPlaylistKey) return@forEach
            val lookupKey = "${target.playlist.key}|${sourceIdentity(request.track)}"
            if (workspace.matches.any { it.lookupKey == lookupKey }) {
                workspace.favoriteIntents.remove(request)
                return@forEach
            }
            attempted += 1
            val source = try {
                resolveTargetSource(workspace, snapshot, favorite, target, request.track, favorites = true)
            } catch (_: LookupUnavailable) {
                return@forEach
            }
            if (source == null) {
                if (workspace.matches.any { it.lookupKey == lookupKey }) workspace.favoriteIntents.remove(request)
                return@forEach
            }
            staged += stageAction(workspace, snapshot, target.playlist, source, desired = true, favorites = true, track = request.track).id
            workspace.favoriteIntents.remove(request)
            workspace.errors.remove(request.id)
        }
        return staged
    }

    private fun favoriteWriteResult(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        favorite: LibraryRecord?,
        track: NativeTrack,
        liked: Boolean,
    ): FavoriteWriteResult {
        val sourceProvider = track.musicSource?.providerId
        val providers = if (liked) snapshot.owners.keys else listOfNotNull(sourceProvider)
        val confirmed = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val unmatched = mutableListOf<String>()
        providers.forEach { providerId ->
            val binding = favorite?.bindings?.firstOrNull { it.playlist.providerId == providerId }
            val matchedTrack = binding?.tracks?.firstOrNull { sourceIdentity(it) == sourceIdentity(track) || sameRecording(workspace, it, track) }
            val hasMatch = matchedTrack != null
            val hasPendingWrite = binding != null && workspace.actions.any { action ->
                action.playlistKey == binding.playlist.key &&
                    (sourceIdentity(action.source) == sourceIdentity(track) ||
                        workspace.recordings[sourceIdentity(action.source)] == recordingKey(workspace, track))
            }
            val hasRetry = binding != null && workspace.favoriteIntents.any { request ->
                request.targetPlaylistKey == binding.playlist.key && recordingKey(workspace, request.track) == recordingKey(workspace, track)
            }
            val hasManualMatch = binding != null && workspace.matches.any { match ->
                match.targetPlaylistKey == binding.playlist.key && recordingKey(workspace, match.track) == recordingKey(workspace, track)
            }
            val membershipKnown = binding?.confirmed == true ||
                binding?.memberships?.get(matchedTrack?.sourceKey ?: track.sourceKey) == liked
            val done = membershipKnown && !hasPendingWrite && !hasRetry && !hasManualMatch &&
                (if (liked) hasMatch else !hasMatch)
            when {
                done -> confirmed += providerId
                hasManualMatch -> {
                    pending += providerId
                    unmatched += providerId
                }
                else -> pending += providerId
            }
        }
        return FavoriteWriteResult(confirmed, pending, unmatched)
    }

    private suspend fun resolveTargetSource(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        record: LibraryRecord,
        target: Binding,
        track: NativeTrack,
        favorites: Boolean,
    ): MusicSourceRef? {
        val targetProvider = sourceById[target.playlist.providerId] ?: return null
        val searchErrorKey = "search:${record.id}:${targetProvider.id}"
        track.musicSource?.takeIf { it.providerId == targetProvider.id }?.let { return it }
        track.alternateSources.firstOrNull { it.providerId == targetProvider.id }?.let { return it }
        val lookupKey = "${target.playlist.key}|${sourceIdentity(track)}"
        // Pending means the automatic decision is closed until an explicit confirmation arrives.
        if (workspace.matches.any { it.lookupKey == lookupKey }) return null
        val localCandidates = target.tracks.filter { strictSameRecording(track, it) }
        if (localCandidates.size == 1) {
            val candidate = localCandidates.single()
            return linkRecordings(workspace, track, candidate)?.let { candidate.musicSource }
        }
        if (favorites) {
            uniqueFavoriteCandidate(track, target.tracks)?.let { candidate ->
                linkRecordings(workspace, track, candidate)?.let { return candidate.musicSource }
            }
        }
        if (!targetProvider.capabilities.search || track.title.isBlank() || track.artist.isBlank() || track.durationMs <= 0L ||
            (!favorites && track.album.isBlank())
        ) {
            queueMatch(workspace, lookupKey, record.id, target, track, emptyList(), favorites)
            return null
        }
        val candidates = try {
            boundedSearch(targetProvider, "${track.title} ${track.artist}", snapshot)
        } catch (timeout: TimeoutCancellationException) {
            workspace.errors[searchErrorKey] = "跨源检索超时，可重试"
            throw LookupUnavailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: AccountChanged) {
            throw changed
        } catch (failure: Exception) {
            recordOperationFailure("song_match", targetProvider.id, failure)
            workspace.errors[searchErrorKey] = failure.message ?: "跨源检索失败"
            throw LookupUnavailable()
        }
        workspace.errors.remove(searchErrorKey)
        val proven = candidates.filter { strictSameRecording(track, it) }
        if (proven.size != 1) {
            if (favorites) {
                uniqueFavoriteCandidate(track, candidates)?.let { candidate ->
                    linkRecordings(workspace, track, candidate)?.let { return candidate.musicSource }
                }
            }
            queueMatch(workspace, lookupKey, record.id, target, track, candidates, favorites)
            return null
        }
        val candidate = proven.single()
        return linkRecordings(workspace, track, candidate)?.let { candidate.musicSource }
            ?: run {
                queueMatch(workspace, lookupKey, record.id, target, track, candidates, favorites)
                null
            }
    }

    private fun queueMatch(
        workspace: Workspace,
        lookupKey: String,
        recordId: String,
        target: Binding,
        track: NativeTrack,
        candidates: List<NativeTrack>,
        favorites: Boolean,
    ) {
        workspace.matches += MatchIntent(
            id = UUID.randomUUID().toString(),
            lookupKey = lookupKey,
            playlistId = recordId,
            targetPlaylistKey = target.playlist.key,
            targetProviderId = target.playlist.providerId,
            track = annotate(workspace, track),
            candidates = visibleMatchCandidates(track, candidates).map { annotate(workspace, it) }.toMutableList(),
            favorites = favorites,
        )
        persist(workspace)
    }

    /** Candidate lists are for human confirmation, never a title-only version merge. */
    private fun visibleMatchCandidates(track: NativeTrack, candidates: List<NativeTrack>): List<NativeTrack> {
        val title = RecordingMatcher.normalize(track.title)
        val artists = artistTokens(track.artist)
        if (title.isBlank() || artists.isEmpty()) return emptyList()
        return candidates.filter { candidate ->
            RecordingMatcher.normalize(candidate.title) == title && artists.intersect(artistTokens(candidate.artist)).isNotEmpty()
        }.distinctBy(::sourceIdentity).take(MAX_MATCH_CANDIDATES)
    }

    private fun artistTokens(artist: String): Set<String> = artist
        .split(Regex("[\\s/,&、，;；]+"))
        .map(RecordingMatcher::normalize)
        .filter { it.isNotBlank() }
        .toSet()

    /** Only explicit favorites may bridge album metadata when one fully version-safe result exists. */
    private fun uniqueFavoriteCandidate(track: NativeTrack, candidates: List<NativeTrack>): NativeTrack? {
        val title = RecordingMatcher.normalize(track.title)
        val artists = artistTokens(track.artist)
        if (title.isBlank() || artists.isEmpty() || track.durationMs <= 0L) return null
        val matches = candidates.filter { candidate ->
            candidate.durationMs > 0L &&
                RecordingMatcher.normalize(candidate.title) == title &&
                artistTokens(candidate.artist) == artists &&
                abs(track.durationMs - candidate.durationMs) <= MAX_DURATION_DELTA_MS &&
                SearchVersionPolicy.accepts(track, candidate.title) &&
                SearchVersionPolicy.accepts(candidate, track.title)
        }.distinctBy(::sourceIdentity)
        return matches.singleOrNull()
    }

    private fun stageAction(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        playlist: MusicPlaylist,
        source: MusicSourceRef,
        desired: Boolean,
        favorites: Boolean,
        track: NativeTrack? = null,
    ): WriteIntent {
        val id = actionId(snapshot, playlist.providerId, playlist.remoteId, source, desired)
        workspace.actions.firstOrNull { it.id == id }?.let { return it }
        val action = WriteIntent(
            id = id,
            providerId = playlist.providerId,
            accountId = snapshot.owners.getValue(playlist.providerId),
            remotePlaylistId = playlist.remoteId,
            playlistKey = playlist.key,
            source = source,
            desired = desired,
            favorites = favorites,
            track = track?.let {
                // IDs come only from the already proven source match; never reuse another source's URL.
                annotate(workspace, it.copy(
                    id = source.neteaseId?.toString() ?: source.key,
                    neteaseId = source.neteaseId, sourceRef = source,
                    streamUrl = "", streamCacheKey = null, alternateSources = emptyList(),
                ))
            },
        )
        workspace.actions.removeAll {
            it.providerId == action.providerId && it.accountId == action.accountId &&
                it.playlistKey == action.playlistKey && sourceIdentity(it.source) == sourceIdentity(action.source)
        }
        workspace.actions += action
        persist(workspace)
        return action
    }

    private suspend fun executeOutbox(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        onlyRecordId: String? = null,
        actionLimit: Int = Int.MAX_VALUE,
        onlyActionIds: Set<String>? = null,
    ) {
        val blocked = mutableSetOf<String>()
        var handled = 0
        workspace.actions.toList().forEach { action ->
            if (!isCurrent(snapshot)) return
            if (action !in workspace.actions) return@forEach
            val record = recordForPlaylistKey(workspace, action.playlistKey) ?: return@forEach
            if (onlyRecordId != null && record.id != onlyRecordId) return@forEach
            if (onlyActionIds != null && action.id !in onlyActionIds) return@forEach
            if (handled >= actionLimit) return@forEach
            if (action.providerId in blocked || snapshot.owners[action.providerId] != action.accountId) return@forEach
            val provider = sourceById[action.providerId] ?: return@forEach
            val binding = findBinding(workspace, action.playlistKey) ?: return@forEach
            handled += 1
            try {
                requireCurrent(snapshot)
                // New explicit intents already know the desired state. Only uncertain/replayed
                // writes need a read before POST; acknowledged writes are never POSTed repeatedly.
                if (action.attempted) {
                    if (confirmMembership(workspace, snapshot, provider, binding, action)) return@forEach
                    if (action.acknowledged) {
                        workspace.errors[action.id] = "平台已受理，等待收藏状态更新"
                        persist(workspace)
                        return@forEach
                    }
                }
                action.attempted = true
                persist(workspace) // A process death or cancellation must force a read before retry.
                val writeStarted = android.os.SystemClock.elapsedRealtime()
                try {
                    if (action.favorites) provider.like(action.source, action.desired)
                    else provider.modifyPlaylist(binding.playlist, listOf(action.source), action.desired)
                    requireCurrent(snapshot)
                    action.acknowledged = true
                    persist(workspace)
                } finally {
                    DiagnosticsLogStore.record("unified_library", "membership_write", mapOf(
                        "source" to provider.id, "acknowledged" to action.acknowledged,
                        "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - writeStarted),
                    ))
                }
                if (!confirmMembership(workspace, snapshot, provider, binding, action)) {
                    workspace.errors[action.id] = "平台已受理，等待收藏状态更新"
                    persist(workspace)
                }
            } catch (timeout: TimeoutCancellationException) {
                if (action !in workspace.actions) return@forEach
                workspace.errors[binding.playlist.key] = "读取曲目超时"
                DiagnosticsLogStore.record(
                    "unified_library",
                    "playlist_tracks_timeout",
                    mapOf("source" to provider.id, "favorites" to binding.playlist.isFavorites),
                )
                persist(workspace)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: AccountChanged) {
                return
            } catch (failure: Exception) {
                if (action !in workspace.actions) return@forEach
                recordOperationFailure("membership_sync", action.providerId, failure)
                workspace.errors[action.id] = failure.message ?: "同步写入失败"
                if (failure.isLoginFailure()) blocked += action.providerId
                persist(workspace)
            }
        }
    }

    /** An exact membership read updates one song; it never replaces a list with a partial page. */
    private suspend fun confirmMembership(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        provider: MusicProvider,
        binding: Binding,
        action: WriteIntent,
    ): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        val membership: MusicPlaylistMembership
        try {
            membership = kotlinx.coroutines.withTimeout(PLAYLIST_TRACKS_TIMEOUT_MS) {
                provider.playlistMembership(binding.playlist, action.source)
            }
            requireCurrent(snapshot)
        } finally {
            DiagnosticsLogStore.record("unified_library", "membership_read", mapOf(
                "source" to provider.id,
                "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - started),
            ))
        }
        if (action !in workspace.actions || membership.present != action.desired) return false
        if (membership.present) {
            val track = membership.track ?: action.track
                ?: binding.tracks.firstOrNull { sourceIdentity(it) == sourceIdentity(action.source) }
            // Legacy persisted actions did not retain metadata. Hydrate once only when needed.
            if (track == null) {
                return settleReadBack(workspace, binding, action, completePlaylist(provider, binding.playlist, snapshot))
            }
            binding.tracks.removeAll { sourceIdentity(it) == sourceIdentity(action.source) }
            binding.tracks.add(0, annotate(workspace, track))
        } else {
            binding.tracks.removeAll { sourceIdentity(it) == sourceIdentity(action.source) }
        }
        binding.memberships[sourceIdentity(action.source)] = membership.present
        workspace.actions.remove(action)
        workspace.errors.remove(action.id)
        persist(workspace)
        // Publish each provider independently; other pending sources must not hold its heart state.
        publish(workspace, snapshot)
        return true
    }

    /** Applies a complete remote snapshot and removes the durable intent only when it is proven. */
    private fun settleReadBack(
        workspace: Workspace,
        binding: Binding,
        action: WriteIntent,
        tracks: List<NativeTrack>,
    ): Boolean {
        binding.tracks.clear()
        binding.tracks += tracks.map { annotate(workspace, it) }
        binding.memberships.clear()
        binding.confirmed = true
        workspace.errors.remove(binding.playlist.key)
        val present = tracks.any { sourceIdentity(it) == sourceIdentity(action.source) }
        if (present != action.desired) return false
        workspace.actions.remove(action)
        workspace.errors.remove(action.id)
        persist(workspace)
        return true
    }

    private suspend fun createPending(workspace: Workspace, snapshot: AccountSnapshot, intent: CreateIntent) {
        if (intent.uncertain || !isCurrent(snapshot)) return
        intent.providerIds.forEach { providerId ->
            if (intent.created.any { it.providerId == providerId }) return@forEach
            if (providerId in intent.started) {
                // A process died after persisting the intent but before recording the remote ID.
                // Retrying could duplicate a user playlist, and name lookup is intentionally forbidden.
                intent.uncertain = true
                workspace.errors[intent.id] = "新建歌单结果未知，请手动绑定"
                persist(workspace)
                return
            }
            val provider = sourceById[providerId] ?: return@forEach
            try {
                requireCurrent(snapshot)
                intent.started += providerId
                persist(workspace) // The intent is durable before the remote create call.
                val created = provider.createPlaylist(intent.name)
                requireCurrent(snapshot)
                intent.created += created
                intent.started.remove(providerId)
                persist(workspace)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: AccountChanged) {
                return
            } catch (failure: Exception) {
                // A remote create can have succeeded just before a transport failure. Never retry it
                // by name: ordinary playlists are not name-merged and duplicate creation is unsafe.
                intent.uncertain = true
                workspace.errors[intent.id] = failure.message ?: "新建歌单结果未知，请手动绑定"
                persist(workspace)
                return
            }
        }
        if (intent.created.size == intent.providerIds.size) {
            intent.created.forEach { created ->
                workspace.catalog.removeAll { it.key == created.key }
                workspace.catalog += created
            }
            workspace.records += LibraryRecord(
                id = intent.id,
                name = intent.name,
                bindings = intent.created.map { Binding(it) }.toMutableList(),
                singleRemote = intent.providerIds.size == 1,
            )
            workspace.creates.remove(intent)
            workspace.errors.remove(intent.id)
        }
    }

    private suspend fun completePlaylist(
        provider: MusicProvider,
        playlist: MusicPlaylist,
        snapshot: AccountSnapshot,
    ): List<NativeTrack> = kotlinx.coroutines.withTimeout(PLAYLIST_TRACKS_TIMEOUT_MS) {
        completePages(snapshot) { offset -> provider.playlistTracks(playlist, PAGE_SIZE, offset) }
    }

    private suspend fun boundedSearch(
        provider: MusicProvider,
        query: String,
        snapshot: AccountSnapshot,
    ): List<NativeTrack> = kotlinx.coroutines.withTimeout(SEARCH_TIMEOUT_MS) {
        requireCurrent(snapshot)
        provider.search(query, SEARCH_PAGE_SIZE, 0).items.distinctBy(::sourceIdentity).also { requireCurrent(snapshot) }
    }

    private suspend fun completePages(
        snapshot: AccountSnapshot,
        fetch: suspend (Int) -> MusicPage<NativeTrack>,
    ): List<NativeTrack> {
        val all = mutableListOf<NativeTrack>()
        var offset = 0
        var pages = 0
        while (true) {
            requireCurrent(snapshot)
            val page = fetch(offset)
            requireCurrent(snapshot)
            all += page.items
            val next = page.nextOffset ?: page.total?.let { total -> (offset + page.items.size).takeIf { it < total } }
            if (next == null) {
                if (page.total != null && all.size < page.total) {
                    throw IllegalStateException("远端分页未完整结束")
                }
                return all.distinctBy(::sourceIdentity)
            }
            if (next <= offset || ++pages > MAX_PAGES) throw IllegalStateException("远端分页未完整结束")
            offset = next
        }
    }

    private fun requireCurrent(snapshot: AccountSnapshot) {
        if (!isCurrent(snapshot)) throw AccountChanged()
    }

    private fun resolveCatalogBinding(workspace: Workspace, id: String): MusicPlaylist {
        val choices = workspace.catalog.filter { it.key == id || it.remoteId == id }
        require(choices.size == 1) { "远端歌单 ID 不存在或不唯一，请使用 provider:owner:remoteId" }
        return choices.single()
    }

    private fun findBinding(workspace: Workspace, key: String): Binding? =
        (listOfNotNull(workspace.favorite) + workspace.records).flatMap { it.bindings }.firstOrNull { it.playlist.key == key }

    private fun recordForPlaylistKey(workspace: Workspace, key: String): LibraryRecord? =
        (listOfNotNull(workspace.favorite) + workspace.records).firstOrNull { record ->
            record.bindings.any { it.playlist.key == key }
        }

    private fun nextActionForRecord(workspace: Workspace, recordId: String): WriteIntent? =
        workspace.actions.firstOrNull { action -> recordForPlaylistKey(workspace, action.playlistKey)?.id == recordId }

    private fun favoriteViewId(binding: Binding): String = "$FAVORITE_VIEW_PREFIX${binding.playlist.key}"

    private fun favoriteViewIdForPlaylistKey(playlistKey: String): String = "$FAVORITE_VIEW_PREFIX$playlistKey"

    private fun annotate(workspace: Workspace, track: NativeTrack): NativeTrack {
        val source = track.musicSource ?: return track
        return workspace.recordings[sourceIdentity(source)]?.let { track.copy(recordingId = it) } ?: track
    }

    private fun recordingKey(workspace: Workspace, track: NativeTrack): String =
        track.musicSource?.let(::sourceIdentity)?.let { workspace.recordings[it] }
            ?: track.recordingId?.takeIf { it.isNotBlank() }
            ?: sourceIdentity(track)

    private fun sameRecording(workspace: Workspace, left: NativeTrack, right: NativeTrack): Boolean =
        sourceIdentity(left) == sourceIdentity(right) || recordingKey(workspace, left) == recordingKey(workspace, right)

    private fun sameRecording(left: NativeTrack, right: NativeTrack): Boolean =
        sourceIdentity(left) == sourceIdentity(right) || (!left.recordingId.isNullOrBlank() && left.recordingId == right.recordingId)

    private fun linkRecordings(workspace: Workspace, first: NativeTrack, second: NativeTrack): String? {
        val firstSource = first.musicSource ?: return null
        val secondSource = second.musicSource ?: return null
        val firstKey = sourceIdentity(firstSource)
        val secondKey = sourceIdentity(secondSource)
        val existing = listOfNotNull(workspace.recordings[firstKey], workspace.recordings[secondKey],
            first.recordingId?.takeIf { it.isNotBlank() }, second.recordingId?.takeIf { it.isNotBlank() }).distinct()
        if (existing.size > 1) return null
        val recording = existing.singleOrNull() ?: "recording:${UUID.randomUUID()}"
        workspace.recordings[firstKey] = recording
        workspace.recordings[secondKey] = recording
        // A cancellation may predate the first proven cross-source link and therefore be keyed
        // by a source ID. Preserve it as recording-level suppression before future reconciliation.
        val oldKeys = setOf(firstKey, secondKey)
        val migrated = workspace.suppressions.map { value ->
            val separator = value.lastIndexOf('|')
            if (separator >= 0 && value.substring(separator + 1) in oldKeys) {
                value.substring(0, separator + 1) + recording
            } else {
                value
            }
        }
        workspace.suppressions.clear()
        workspace.suppressions += migrated
        return recording
    }

    private fun strictSameRecording(first: NativeTrack, second: NativeTrack): Boolean =
        first.title.isNotBlank() && second.title.isNotBlank() &&
            first.artist.isNotBlank() && second.artist.isNotBlank() &&
            first.album.isNotBlank() && second.album.isNotBlank() &&
            first.durationMs > 0L && second.durationMs > 0L &&
            RecordingMatcher.normalize(first.title) == RecordingMatcher.normalize(second.title) &&
            RecordingMatcher.normalize(first.artist) == RecordingMatcher.normalize(second.artist) &&
            RecordingMatcher.normalize(first.album) == RecordingMatcher.normalize(second.album) &&
            abs(first.durationMs - second.durationMs) <= MAX_DURATION_DELTA_MS

    /** Opaque provider mediaId is the stable song identity; file/type are request metadata only. */
    private fun sourceIdentity(track: NativeTrack): String = track.musicSource?.let(::sourceIdentity) ?: "legacy:${track.id}"

    private fun sourceIdentity(source: MusicSourceRef): String = source.key

    private fun suppressionKey(playlist: MusicPlaylist, recording: String): String = "${playlist.key}|$recording"

    private fun actionId(snapshot: AccountSnapshot, provider: String, remotePlaylist: String, source: MusicSourceRef, desired: Boolean): String =
        "${snapshot.key}|$provider|$remotePlaylist|${sourceIdentity(source)}|$desired"

    private fun publish(
        workspace: Workspace,
        snapshot: AccountSnapshot,
        syncing: Boolean = false,
        backgroundSyncing: Boolean = false,
    ) {
        if (!isCurrent(snapshot)) return
        annotationIndex = workspace.recordings.toMap()
        favoriteReadiness = workspace.favorite?.bindings
            ?.groupBy { it.playlist.providerId }
            ?.mapValues { (_, bindings) -> bindings.all { it.confirmed && !workspace.errors.containsKey(it.playlist.key) } }
            .orEmpty()
        // 随资料库快照一次性建立索引，滚动挂载歌曲行时不再遍历整份收藏列表。
        // 来源身份保持隔离；单曲读回的 false/true 优先于列表快照，缺项仍由 readiness 判定。
        favoriteMemberships = buildMap {
            workspace.favorite?.bindings.orEmpty().forEach { binding ->
                binding.tracks.forEach { track -> put(sourceIdentity(track), true) }
            }
            workspace.favorite?.bindings.orEmpty().forEach { binding ->
                putAll(binding.memberships)
            }
        }
        val favoriteViews = workspace.favorite?.bindings.orEmpty().map { binding ->
            UnifiedPlaylist(
                id = favoriteViewId(binding),
                name = FAVORITES_NAME,
                bindings = listOf(binding.playlist),
                tracks = binding.tracks.map { annotate(workspace, it).copy(alternateSources = emptyList()) }.distinctBy(::sourceIdentity),
            )
        }
        val next = UnifiedLibraryState(
            playlists = favoriteViews + workspace.records.map { record ->
                UnifiedPlaylist(
                    id = record.id,
                    name = record.name,
                    bindings = record.bindings.map { it.playlist },
                    tracks = displayTracks(workspace, record.bindings.flatMap { it.tracks }),
                )
            },
            pendingMatches = workspace.matches.map { match ->
                PendingLibraryMatch(
                    match.id,
                    annotate(workspace, match.track),
                    visibleMatchCandidates(match.track, match.candidates).map { candidate -> annotate(workspace, candidate) },
                    match.targetProviderId,
                    if (match.favorites) favoriteViewIdForPlaylistKey(match.targetPlaylistKey) else match.playlistId,
                    match.targetPlaylistKey,
                )
            },
            pendingCount = workspace.actions.size + workspace.favoriteIntents.size + workspace.creates.size,
            syncing = syncing,
            backgroundSyncing = backgroundSyncing,
            errors = visibleErrors(workspace),
            playlistErrors = visiblePlaylistErrors(workspace),
            accountKey = snapshot.key,
        )
        if (next.errors != _state.value.errors || next.playlistErrors != _state.value.playlistErrors) {
            DiagnosticsLogStore.record("unified_library", "error_state_changed", mapOf(
                "catalogSources" to next.errors.keys.joinToString(","),
                "affectedPlaylists" to next.playlistErrors.size,
                "pendingWrites" to workspace.actions.size,
            ))
        }
        _state.value = next
    }

    /** Internal action keys and transport payloads stay in storage, never in the browsing UI. */
    private fun visibleErrors(workspace: Workspace): Map<String, String> {
        return buildMap {
            workspace.errors.forEach { (key, raw) ->
                when {
                    key in sourceById -> put(key, "歌单列表：${visibleErrorMessage(raw)}")
                    workspace.creates.any { it.id == key } -> put("library", "新建结果尚未确认，请检查平台歌单后手动绑定。")
                }
            }
        }
    }

    /** Reading a catalog and reconciling its playlists have independent success states. */
    private fun visiblePlaylistErrors(workspace: Workspace): Map<String, List<String>> {
        val actions = workspace.actions.associateBy { it.id }
        val messages = linkedMapOf<String, MutableSet<String>>()
        workspace.errors.forEach { (key, raw) ->
            workspace.records.forEach recordLoop@{ record ->
                val binding = record.bindings.firstOrNull { it.playlist.key == key || it.playlist.key == actions[key]?.playlistKey }
                val searchProvider = record.bindings.firstOrNull {
                    key == "search:${record.id}:${it.playlist.providerId}"
                }?.playlist?.providerId
                val provider = binding?.playlist?.providerId ?: searchProvider?.takeIf { source ->
                    !record.singleRemote && record.bindings.any { it.playlist.providerId == source }
                }
                val operation = when {
                    actions[key] != null && binding != null -> "收藏同步"
                    binding != null -> "曲目读取"
                    provider != null -> "歌曲匹配"
                    else -> return@recordLoop
                }
                val label = provider?.let { "${app.pipo.nativeapp.data.MusicProviders.label(it)}：" }.orEmpty()
                messages.getOrPut(record.id) { linkedSetOf() }.add("$label$operation：${visibleErrorMessage(raw)}")
            }
            workspace.favorite?.bindings?.forEach favoriteLoop@{ binding ->
                val action = actions[key]
                val belongs = binding.playlist.key == key || action?.playlistKey == binding.playlist.key ||
                    key == "search:$FAVORITES_ID:${binding.playlist.providerId}"
                if (!belongs) return@favoriteLoop
                val operation = when {
                    action != null -> "收藏同步"
                    key.startsWith("search:") -> "歌曲匹配"
                    else -> "曲目读取"
                }
                val label = "${app.pipo.nativeapp.data.MusicProviders.label(binding.playlist.providerId)}："
                messages.getOrPut(favoriteViewId(binding)) { linkedSetOf() }
                    .add("$label$operation：${visibleErrorMessage(raw)}")
            }
        }
        return messages.mapValues { it.value.take(2) }
    }

    private fun visibleErrorMessage(raw: String): String {
        val text = raw.lowercase()
        return when {
            "频繁" in raw || "rate limit" in text || "too many requests" in text || "429" in text -> "请求较频繁，请稍后重试。"
            "登录" in raw || "login" in text || "auth" in text -> "需要确认账号，请重新连接该平台。"
            "timeout" in text || "timed out" in text || "超时" in text -> "超时，请稍后重试。"
            listOf("unable to resolve host", "no address associated", "unknownhost", "网络", "failed to connect", "connection", "socket").any { it in text } ->
                "连接暂时失败，请稍后重试。"
            "目录" in raw && ("完整" in raw || "部分" in raw) -> "尚未全部获取，可先浏览已有歌单。"
            else -> "暂未完成，请稍后重试。"
        }
    }

    private fun recordOperationFailure(operation: String, providerId: String, failure: Exception) {
        DiagnosticsLogStore.record("unified_library", "operation_failed", mapOf(
            "operation" to operation,
            "source" to providerId,
            "errorType" to failure.javaClass.simpleName,
            "category" to visibleErrorMessage(failure.message.orEmpty()),
        ))
    }

    private fun displayTracks(workspace: Workspace, tracks: List<NativeTrack>): List<NativeTrack> {
        val grouped = linkedMapOf<String, NativeTrack>()
        tracks.map { annotate(workspace, it) }.distinctBy(::sourceIdentity).forEach { track ->
            val key = recordingKey(workspace, track)
            val old = grouped[key]
            grouped[key] = if (old == null) track else old.copy(
                alternateSources = (old.alternateSources + listOfNotNull(track.musicSource) + track.alternateSources)
                    .filter { sourceIdentity(it) != old.musicSource?.let(::sourceIdentity) }.distinctBy(::sourceIdentity),
            )
        }
        return grouped.values.toList()
    }

    private fun persist(workspace: Workspace) {
        // [workspace] is intentionally an argument: every mutation happens under [mutex].
        val target = file.startWrite()
        try {
            target.write(encodeDisk().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(target)
        } catch (failure: Exception) {
            file.failWrite(target)
            throw failure
        }
    }

    private fun readDisk(): Disk = runCatching {
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        if (root.optInt("version") != VERSION) return@runCatching Disk()
        Disk().also { disk ->
            val spaces = root.optJSONObject("workspaces") ?: return@also
            spaces.keys().forEach { key -> decodeWorkspace(spaces.optJSONObject(key))?.let { disk.workspaces[key] = it } }
        }
    }.getOrDefault(Disk())

    private fun encodeDisk(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("workspaces", JSONObject().also { all -> disk.workspaces.forEach { (key, workspace) -> all.put(key, encodeWorkspace(workspace)) } })
    }

    private fun encodeWorkspace(workspace: Workspace): JSONObject = JSONObject().apply {
        put("favoriteFanoutVersion", workspace.favoriteFanoutVersion)
        put("catalog", JSONArray().also { array -> workspace.catalog.forEach { array.put(encodePlaylist(it)) } })
        workspace.favorite?.let { put("favorite", encodeRecord(it)) }
        put("records", JSONArray().also { array -> workspace.records.forEach { array.put(encodeRecord(it)) } })
        put("recordings", JSONObject().also { obj -> workspace.recordings.forEach { (source, recording) -> obj.put(source, recording) } })
        put("suppressions", JSONArray(workspace.suppressions.toList()))
        put("matches", JSONArray().also { array -> workspace.matches.forEach { array.put(encodeMatch(it)) } })
        put("actions", JSONArray().also { array -> workspace.actions.forEach { array.put(encodeAction(it)) } })
        put("favoriteIntents", JSONArray().also { array -> workspace.favoriteIntents.forEach { array.put(encodeFavoriteIntent(it)) } })
        put("creates", JSONArray().also { array -> workspace.creates.forEach { array.put(encodeCreate(it)) } })
        put("errors", JSONObject().also { obj -> workspace.errors.forEach { (key, value) -> obj.put(key, value) } })
    }

    private fun decodeWorkspace(json: JSONObject?): Workspace? = json?.let { root -> runCatching {
        Workspace().also { workspace ->
            workspace.favoriteFanoutVersion = root.optInt("favoriteFanoutVersion", 0)
            decodePlaylists(root.optJSONArray("catalog")).forEach { workspace.catalog += it }
            root.optJSONObject("favorite")?.let(::decodeRecord)?.let { workspace.favorite = it }
            decodeRecords(root.optJSONArray("records")).forEach { workspace.records += it }
            root.optJSONObject("recordings")?.keys()?.forEach { key -> root.optJSONObject("recordings")?.optString(key)?.takeIf { it.isNotBlank() }?.let { workspace.recordings[key] = it } }
            decodeStrings(root.optJSONArray("suppressions")).forEach { workspace.suppressions += it }
            decodeMatches(root.optJSONArray("matches")).forEach { workspace.matches += it }
            decodeActions(root.optJSONArray("actions")).forEach { workspace.actions += it }
            decodeFavoriteIntents(root.optJSONArray("favoriteIntents")).forEach { workspace.favoriteIntents += it }
            decodeCreates(root.optJSONArray("creates")).forEach { workspace.creates += it }
            root.optJSONObject("errors")?.keys()?.forEach { key ->
                // Legacy provider-wide lookup errors have no playlist owner. Pending work remains
                // intact and reports a fresh, scoped error if its next attempt still fails.
                if (sourceById.keys.none { key == "search:$it" }) {
                    workspace.errors[key] = root.optJSONObject("errors")?.optString(key).orEmpty()
                }
            }
            if (workspace.favoriteFanoutVersion < FAVORITE_FANOUT_VERSION) migrateFavoriteFanoutV1(workspace)
        }
    }.getOrNull() }

    private fun encodeRecord(record: LibraryRecord): JSONObject = JSONObject().apply {
        put("id", record.id); put("name", record.name); put("automatic", record.automatic); put("singleRemote", record.singleRemote)
        put("autoReadCursor", record.autoReadCursor); put("autoCursor", record.autoCursor)
        put("autoRetryAtMillis", record.autoRetryAtMillis); put("autoRetryCount", record.autoRetryCount); put("autoBlocked", record.autoBlocked)
        put("bindings", JSONArray().also { array -> record.bindings.forEach { array.put(encodeBinding(it)) } })
    }

    private fun decodeRecord(json: JSONObject): LibraryRecord? = runCatching {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
        val bindings = decodeBindings(json.optJSONArray("bindings"))
        if (bindings.isEmpty()) return null
        LibraryRecord(
            id, name, bindings.toMutableList(), json.optBoolean("automatic"), json.optBoolean("singleRemote"),
            json.optInt("autoReadCursor"), json.optInt("autoCursor"), json.optLong("autoRetryAtMillis"), json.optInt("autoRetryCount"), json.optBoolean("autoBlocked"),
        )
    }.getOrNull()

    private fun decodeRecords(array: JSONArray?): List<LibraryRecord> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) array.optJSONObject(index)?.let(::decodeRecord)?.let(::add)
    }

    private fun encodeBinding(binding: Binding): JSONObject = JSONObject().apply {
        put("playlist", encodePlaylist(binding.playlist)); put("confirmed", binding.confirmed)
        put("memberships", JSONObject(binding.memberships as Map<*, *>))
        put("tracks", JSONArray().also { array -> binding.tracks.forEach { array.put(NativeTrackJson.encode(it)) } })
    }

    private fun decodeBindings(array: JSONArray?): List<Binding> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val playlist = item.optJSONObject("playlist")?.let(::decodePlaylist) ?: continue
            val tracks = decodeTracks(item.optJSONArray("tracks")).toMutableList()
            val memberships = mutableMapOf<String, Boolean>()
            item.optJSONObject("memberships")?.let { values ->
                values.keys().forEach { key -> if (values.opt(key) is Boolean) memberships[key] = values.getBoolean(key) }
            }
            add(Binding(playlist, tracks, item.optBoolean("confirmed"), memberships))
        }
    }

    private fun encodePlaylist(playlist: MusicPlaylist): JSONObject = JSONObject().apply {
        put("providerId", playlist.providerId); put("remoteId", playlist.remoteId); put("name", playlist.name)
        put("trackCount", playlist.trackCount); playlist.ownerId?.let { put("ownerId", it) }
        put("canEdit", playlist.canEdit); put("favorites", playlist.isFavorites); playlist.coverUrl?.let { put("coverUrl", it) }
        playlist.directoryId?.let { put("directoryId", it) }
    }

    private fun decodePlaylist(json: JSONObject): MusicPlaylist? = runCatching {
        val provider = json.optString("providerId").takeIf { it.isNotBlank() } ?: return null
        val remote = json.optString("remoteId").takeIf { it.isNotBlank() } ?: return null
        MusicPlaylist(provider, remote, json.optString("name"), json.optInt("trackCount"), json.optNullable("ownerId"),
            json.optBoolean("canEdit"), json.optBoolean("favorites"), json.optNullable("coverUrl"), json.optLongOrNull("directoryId"))
    }.getOrNull()

    private fun decodePlaylists(array: JSONArray?): List<MusicPlaylist> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) array.optJSONObject(index)?.let(::decodePlaylist)?.let(::add)
    }

    private fun decodeTracks(array: JSONArray?): List<NativeTrack> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) array.optJSONObject(index)?.let(NativeTrackJson::decode)?.let(::add)
    }

    private fun encodeMatch(match: MatchIntent): JSONObject = JSONObject().apply {
        put("id", match.id); put("lookupKey", match.lookupKey); put("playlistId", match.playlistId)
        put("targetPlaylistKey", match.targetPlaylistKey); put("targetProviderId", match.targetProviderId); put("favorites", match.favorites)
        put("track", NativeTrackJson.encode(match.track))
        put("candidates", JSONArray().also { array -> match.candidates.forEach { array.put(NativeTrackJson.encode(it)) } })
    }

    private fun decodeMatches(array: JSONArray?): List<MatchIntent> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val track = item.optJSONObject("track")?.let(NativeTrackJson::decode) ?: continue
            val targetKey = item.optString("targetPlaylistKey").takeIf { it.isNotBlank() } ?: continue
            val targetProvider = item.optString("targetProviderId").takeIf { it.isNotBlank() } ?: continue
            add(MatchIntent(id, item.optString("lookupKey"), item.optString("playlistId"), targetKey, targetProvider, track,
                decodeTracks(item.optJSONArray("candidates")).toMutableList(), item.optBoolean("favorites")))
        }
    }

    private fun encodeAction(action: WriteIntent): JSONObject = JSONObject().apply {
        put("id", action.id); put("providerId", action.providerId); put("accountId", action.accountId)
        put("remotePlaylistId", action.remotePlaylistId); put("playlistKey", action.playlistKey); put("desired", action.desired); put("favorites", action.favorites)
        put("source", encodeSource(action.source))
        put("attempted", action.attempted); put("acknowledged", action.acknowledged)
        action.track?.let { put("track", NativeTrackJson.encode(it)) }
    }

    private fun decodeActions(array: JSONArray?): List<WriteIntent> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val provider = item.optString("providerId").takeIf { it.isNotBlank() } ?: continue
            val account = item.optString("accountId").takeIf { it.isNotBlank() } ?: continue
            val remote = item.optString("remotePlaylistId").takeIf { it.isNotBlank() } ?: continue
            val key = item.optString("playlistKey").takeIf { it.isNotBlank() } ?: continue
            val source = item.optJSONObject("source")?.let(::decodeSource) ?: continue
            add(WriteIntent(id, provider, account, remote, key, source, item.optBoolean("desired"), item.optBoolean("favorites"),
                item.optJSONObject("track")?.let(NativeTrackJson::decode),
                item.optBoolean("attempted", true), item.optBoolean("acknowledged")))
        }
    }

    private fun encodeFavoriteIntent(intent: FavoriteIntent): JSONObject = JSONObject().apply {
        put("id", intent.id); put("targetPlaylistKey", intent.targetPlaylistKey)
        put("providerId", intent.providerId); put("accountId", intent.accountId)
        put("track", NativeTrackJson.encode(intent.track))
    }

    private fun decodeFavoriteIntents(array: JSONArray?): List<FavoriteIntent> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val key = item.optString("targetPlaylistKey")
            val provider = item.optString("providerId").takeIf { it.isNotBlank() } ?: continue
            val account = item.optString("accountId").takeIf { it.isNotBlank() } ?: continue
            val track = item.optJSONObject("track")?.let(NativeTrackJson::decode) ?: continue
            add(FavoriteIntent(id, key, provider, account, track))
        }
    }

    private fun migrateFavoriteFanoutV1(workspace: Workspace) {
        val retiredActions = workspace.actions.filter { it.favorites && it.desired }
        val retiredMatches = workspace.matches.filter { it.favorites }
        workspace.actions.removeAll(retiredActions.toSet())
        workspace.matches.removeAll(retiredMatches.toSet())
        workspace.favoriteIntents.clear()
        val retiredIds = retiredActions.map { it.id }.toSet() + retiredMatches.map { it.id }.toSet()
        workspace.errors.keys.removeAll { key ->
            key in retiredIds || key.startsWith("search:$FAVORITES_ID:")
        }
        workspace.favoriteFanoutVersion = FAVORITE_FANOUT_VERSION
        favoriteFanoutMigrationPending = true
    }

    private fun encodeCreate(create: CreateIntent): JSONObject = JSONObject().apply {
        put("id", create.id); put("name", create.name); put("providers", JSONArray(create.providerIds))
        put("started", JSONArray(create.started.toList())); put("uncertain", create.uncertain)
        put("created", JSONArray().also { array -> create.created.forEach { array.put(encodePlaylist(it)) } })
    }

    private fun decodeCreates(array: JSONArray?): List<CreateIntent> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
            val providers = decodeStrings(item.optJSONArray("providers")).toMutableList()
            if (providers.isEmpty()) continue
            add(CreateIntent(id, name, providers, decodeStrings(item.optJSONArray("started")).toMutableSet(),
                item.optBoolean("uncertain"), decodePlaylists(item.optJSONArray("created")).toMutableList()))
        }
    }

    private fun encodeSource(source: MusicSourceRef): JSONObject = JSONObject().apply {
        put("providerId", source.providerId); put("mediaId", source.mediaId); source.mediaFileId?.let { put("mediaFileId", it) }
        source.numericId?.let { put("numericId", it) }; put("songType", source.songType)
    }

    private fun decodeSource(json: JSONObject): MusicSourceRef? = runCatching {
        MusicSourceRef(json.optString("providerId"), json.optString("mediaId"), json.optNullable("mediaFileId"),
            json.optLongOrNull("numericId"), json.optInt("songType", 0))
    }.getOrNull()

    private fun decodeStrings(array: JSONArray?): List<String> = buildList {
        array ?: return@buildList
        for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun JSONObject.optNullable(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    private fun JSONObject.optLongOrNull(key: String): Long? = when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
    private fun Exception.isLoginFailure(): Boolean = this is MusicProviderException &&
        (code == MusicProviderError.AUTH_REQUIRED || code == MusicProviderError.STALE_SESSION || code == MusicProviderError.UNCONFIGURED)

    private data class AccountSnapshot(val owners: Map<String, String>, val epoch: Long) {
        val key: String = owners.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${it.value}" }.ifBlank { "signed-out" }
    }
    private class AccountChanged : IllegalStateException("音乐账号已切换")
    private class LookupUnavailable : IllegalStateException("跨平台检索暂时不可用")
    private sealed class CatalogOutcome
    private class CatalogSuccess(val providerId: String, val catalog: MusicPlaylistCatalog) : CatalogOutcome()
    private class CatalogFailure(val providerId: String, val message: String) : CatalogOutcome()
    private class CatalogCancelled(val cause: CancellationException) : CatalogOutcome()
    private object CatalogChanged : CatalogOutcome()
    private class RefreshRequest(val accountKey: String, val deferred: Deferred<Unit>)
    private class AutomaticPair(val target: Binding, val track: NativeTrack)
    private class AutomaticSlice(val more: Boolean, val waitMs: Long = 0L)
    private class Disk(val workspaces: MutableMap<String, Workspace> = linkedMapOf())
    private class Workspace(
        var favoriteFanoutVersion: Int = FAVORITE_FANOUT_VERSION,
        val catalog: MutableList<MusicPlaylist> = mutableListOf(),
        var favorite: LibraryRecord? = null,
        val records: MutableList<LibraryRecord> = mutableListOf(),
        val recordings: MutableMap<String, String> = linkedMapOf(),
        val suppressions: MutableSet<String> = linkedSetOf(),
        val matches: MutableList<MatchIntent> = mutableListOf(),
        val actions: MutableList<WriteIntent> = mutableListOf(),
        val favoriteIntents: MutableList<FavoriteIntent> = mutableListOf(),
        val creates: MutableList<CreateIntent> = mutableListOf(),
        val errors: MutableMap<String, String> = linkedMapOf(),
    )
    private class LibraryRecord(
        val id: String,
        val name: String,
        val bindings: MutableList<Binding>,
        val automatic: Boolean = false,
        val singleRemote: Boolean = false,
        var autoReadCursor: Int = 0,
        var autoCursor: Int = 0,
        var autoRetryAtMillis: Long = 0L,
        var autoRetryCount: Int = 0,
        var autoBlocked: Boolean = false,
    )
    private class Binding(
        var playlist: MusicPlaylist, val tracks: MutableList<NativeTrack> = mutableListOf(), var confirmed: Boolean = false,
        val memberships: MutableMap<String, Boolean> = mutableMapOf(),
    )
    private class MatchIntent(
        val id: String, val lookupKey: String, val playlistId: String, val targetPlaylistKey: String,
        val targetProviderId: String, val track: NativeTrack, val candidates: MutableList<NativeTrack>, val favorites: Boolean,
    )
    private class WriteIntent(
        val id: String, val providerId: String, val accountId: String, val remotePlaylistId: String,
        val playlistKey: String, val source: MusicSourceRef, val desired: Boolean, val favorites: Boolean,
        val track: NativeTrack? = null, var attempted: Boolean = false, var acknowledged: Boolean = false,
    )
    private class FavoriteIntent(
        val id: String,
        val targetPlaylistKey: String,
        val providerId: String,
        val accountId: String,
        val track: NativeTrack,
    )
    private class CreateIntent(
        val id: String, val name: String, val providerIds: MutableList<String>, val started: MutableSet<String> = mutableSetOf(),
        var uncertain: Boolean = false, val created: MutableList<MusicPlaylist> = mutableListOf(),
    )

    private companion object {
        const val FILE_NAME = "unified-music-library.json"
        const val VERSION = 1
        const val FAVORITES_ID = "favorites"
        const val FAVORITES_NAME = "我喜欢"
        const val FAVORITE_VIEW_PREFIX = "favorite:"
        const val FAVORITE_FANOUT_VERSION = 1
        const val PAGE_SIZE = 100
        const val SEARCH_PAGE_SIZE = 50
        const val SEARCH_TIMEOUT_MS = 8_000L
        const val CATALOG_TIMEOUT_MS = 20_000L
        const val PLAYLIST_TRACKS_TIMEOUT_MS = 30_000L
        const val AUTO_LOOKUP_LIMIT = 2
        const val AUTO_ACTION_LIMIT = 1
        const val MANUAL_ACTION_LIMIT = 2
        const val AUTO_RETRY_BASE_MS = 5_000L
        const val AUTO_RETRY_MAX_MS = 60_000L
        const val MAX_AUTO_RETRY_EXPONENT = 4
        const val AUTO_ACCOUNT_RESUME_DELAY_MS = 50L
        const val AUTO_ERROR_KEY = "automatic"
        const val MAX_PAGES = 1_000
        const val MAX_DURATION_DELTA_MS = 2_000L
        const val MAX_MATCH_CANDIDATES = 12
        const val FAVORITE_RETRY_LOOKUP_LIMIT = 1
    }
}
