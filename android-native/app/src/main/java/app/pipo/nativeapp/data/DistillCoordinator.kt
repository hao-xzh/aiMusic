package app.pipo.nativeapp.data

import app.pipo.nativeapp.DiagnosticsLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-scoped coordinator for the legacy playlist flow and the account-scoped mixed taste flow. */
class DistillCoordinator(private val engine: DistillEngine) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow<DistillProgress?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _running = MutableStateFlow(false)
    private var currentJob: Job? = null
    private var currentJobAccountKey = ""
    private var jobGeneration = 0L
    private var failedAccountKey = ""
    private var failedAtMillis = 0L
    private var checkedAccountKey = ""
    private var checkedAtMillis = 0L
    private var contentCheckQueued = false
    private var accountRecoveryJob: Job? = null
    private var accountRecoveryAttemptMillis = 0L

    val progress: StateFlow<DistillProgress?> = _progress.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Launches the historical NetEase-only flow for callers that still explicitly select playlists. */
    @Synchronized
    fun start(
        playlists: List<PipoPlaylist>,
        sampleSize: Int = 200,
        onComplete: () -> Unit = {},
    ): Boolean {
        if (currentJob != null || playlists.isEmpty()) return false
        _error.value = null
        val generation = ++jobGeneration
        _running.value = true
        currentJobAccountKey = ""
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.distill(playlists, sampleSize) { publishProgress(generation, it) }
                if (isCurrentGeneration(generation)) onComplete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                publishError(generation, failure.message ?: "蒸馏失败")
            } finally {
                finish(generation)
            }
        }
        currentJob = job
        job.start()
        return true
    }

    /**
     * Silently checks library contents. AI runs only when the complete input has changed.
     * force bypasses the read cooldown, never the content comparison.
     * A result for a prior account is cancelled and its finally block cannot clear a newer job.
     */
    @Synchronized
    fun ensureMixed(force: Boolean = false): Boolean {
        val music = PipoGraph.music
        if (!music.accountsReady.value) {
            if (accountRecoveryJob != null || (!force && accountRecoveryAttemptMillis != 0L &&
                    System.currentTimeMillis() - accountRecoveryAttemptMillis < FAILURE_RETRY_DELAY_MS)) return false
            accountRecoveryAttemptMillis = System.currentTimeMillis()
            val recovery = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    music.refreshAccounts(onlyIfNeeded = true)
                } finally {
                    synchronized(this@DistillCoordinator) { accountRecoveryJob = null }
                }
                if (music.accountsReady.value) ensureMixed(force)
            }
            accountRecoveryJob = recovery
            recovery.start()
            return true
        }
        val loader = MixedTasteLoader(music)
        val accountKey = loader.currentAccountKey()
        val providerIds = loader.currentProviderIds()
        val store = PipoGraph.tasteProfileStore
        val profile = store.current()
        // An unavailable saved source is not an intentional disconnection.
        if (music.providers.any { it.id in profile?.sourceProviderIds.orEmpty() && it.connection.value.error != null }) return false

        if (accountKey.isBlank() || providerIds.isEmpty()) {
            if (_running.value || currentJob != null) cancelActiveLocked()
            if (profile != null) store.clear()
            _error.value = null
            failedAccountKey = ""
            failedAtMillis = 0L
            checkedAccountKey = ""
            checkedAtMillis = 0L
            return false
        }

        val accountChanged = profile != null && profile.sourceAccountKey != accountKey
        val legacyProfile = profile?.version == 1
        if (currentJob != null && currentJobAccountKey != accountKey) cancelActiveLocked()
        if (accountChanged || legacyProfile) {
            store.clear()
            checkedAccountKey = ""
            checkedAtMillis = 0L
        }

        if (currentJob != null && currentJobAccountKey == accountKey) {
            if (force) contentCheckQueued = true
            return false
        }
        if (!force && checkedAccountKey == accountKey &&
            System.currentTimeMillis() - checkedAtMillis < CONTENT_CHECK_INTERVAL_MS
        ) return false
        if (!force && failedAccountKey == accountKey &&
            System.currentTimeMillis() - failedAtMillis < FAILURE_RETRY_DELAY_MS
        ) return false

        _error.value = null
        val generation = ++jobGeneration
        // Directory/page reads are background maintenance, not distillation progress.
        _running.value = false
        currentJobAccountKey = accountKey
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                val input = loader.load()
                if (!isCurrentMixedJob(generation, accountKey) || input.accountKey != accountKey) {
                    throw CancellationException("音乐账号已切换")
                }
                check(input.complete) { "音乐资料库尚未完整读取，保留上次画像" }
                val previous = store.current()
                if (previous?.sourceAccountKey == accountKey && previous.sourceDataComplete &&
                    previous.sourceHash == input.sourceHash && previous.sourceProviderIds == providerIds
                ) {
                    markChecked(generation, accountKey)
                    DiagnosticsLogStore.record("taste", "unchanged", mapOf("playlists" to input.slices.size))
                    return@launch
                }
                if (input.slices.none { it.tracks.isNotEmpty() }) {
                    clearEmptyIfCurrent(generation, accountKey, loader, store)
                    return@launch
                }
                beginDistilling(generation)
                val distilled = engine.distillMixed(input) { publishProgress(generation, it) }
                saveMixedIfCurrent(generation, accountKey, loader, store, distilled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                publishMixedFailure(generation, accountKey, failure.message ?: "口味画像生成失败")
            } finally {
                finish(generation)
            }
        }
        currentJob = job
        job.start()
        return true
    }

    @Synchronized
    fun cancel() {
        cancelActiveLocked()
    }

    fun dismissError() {
        _error.value = null
    }

    @Synchronized
    private fun publishProgress(generation: Long, progress: DistillProgress) {
        if (generation == jobGeneration) _progress.value = progress
    }

    @Synchronized
    private fun publishError(generation: Long, message: String) {
        if (generation == jobGeneration) _error.value = message
    }

    @Synchronized
    private fun publishMixedFailure(generation: Long, accountKey: String, message: String) {
        if (generation != jobGeneration || currentJobAccountKey != accountKey) return
        failedAccountKey = accountKey
        failedAtMillis = System.currentTimeMillis()
        // A failed silent refresh must not cover the app with a misleading distillation banner.
        if (_running.value) _error.value = message
        DiagnosticsLogStore.record("taste", "refresh_deferred", mapOf("reason" to message))
    }

    @Synchronized
    private fun beginDistilling(generation: Long) {
        if (generation != jobGeneration) throw CancellationException("音乐账号已切换")
        _progress.value = null
        _running.value = true
    }

    @Synchronized
    private fun markChecked(generation: Long, accountKey: String) {
        if (generation != jobGeneration) return
        checkedAccountKey = accountKey
        checkedAtMillis = System.currentTimeMillis()
        failedAccountKey = ""
        failedAtMillis = 0L
    }

    @Synchronized
    private fun clearEmptyIfCurrent(generation: Long, accountKey: String, loader: MixedTasteLoader, store: TasteProfileStore) {
        if (!isCurrentMixedJob(generation, accountKey) || loader.currentAccountKey() != accountKey) {
            throw CancellationException("音乐账号已切换")
        }
        store.clear()
        markChecked(generation, accountKey)
    }

    @Synchronized
    private fun isCurrentGeneration(generation: Long): Boolean = generation == jobGeneration

    @Synchronized
    private fun isCurrentMixedJob(generation: Long, accountKey: String): Boolean =
        generation == jobGeneration && currentJobAccountKey == accountKey

    @Synchronized
    private fun saveMixedIfCurrent(
        generation: Long,
        accountKey: String,
        loader: MixedTasteLoader,
        store: TasteProfileStore,
        profile: TasteProfile,
    ) {
        if (generation != jobGeneration || currentJobAccountKey != accountKey || loader.currentAccountKey() != accountKey) {
            throw CancellationException("音乐账号已切换")
        }
        check(profile.sourceDataComplete && loader.currentProviderIds() == profile.sourceProviderIds) {
            "音乐资料库尚未完整读取，保留上次画像"
        }
        store.save(profile)
        markChecked(generation, accountKey)
    }

    @Synchronized
    private fun finish(generation: Long) {
        if (generation != jobGeneration) return
        _progress.value = null
        _running.value = false
        currentJob = null
        currentJobAccountKey = ""
        if (contentCheckQueued) {
            contentCheckQueued = false
            ensureMixed(force = true)
        }
    }

    /** Caller holds this instance monitor. Incrementing first makes old workers inert. */
    private fun cancelActiveLocked() {
        ++jobGeneration
        currentJob?.cancel()
        currentJob = null
        currentJobAccountKey = ""
        contentCheckQueued = false
        _running.value = false
        _progress.value = null
    }

    private companion object {
        const val CONTENT_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        const val FAILURE_RETRY_DELAY_MS = 10 * 60 * 1000L
    }
}
