package app.pipo.nativeapp.data

import android.content.Context

/**
 * 全局 DI 容器。RustBridgeRepository 在 PipoApplication.onCreate 装配；
 * TasteProfileStore / DistillEngine 在 installContext 时装配（需要 Application Context）。
 */
object PipoGraph {
    @Volatile
    private var overrideRepository: PipoRepository? = null

    @Volatile
    private var profileStore: TasteProfileStore? = null

    @Volatile
    private var featuresStore: AudioFeaturesStore? = null

    @Volatile
    private var behaviorLogger: BehaviorLog? = null

    @Volatile
    private var lastPlaybackStore: LastPlaybackStore? = null

    @Volatile
    private var semanticStore: TrackSemanticStore? = null

    @Volatile
    private var indexer: SemanticIndexer? = null

    @Volatile
    private var memory: PetMemory? = null

    @Volatile
    private var recLog: RecommendationLog? = null

    @Volatile
    private var libraryAnalysis: LibraryAnalysis? = null

    @Volatile
    private var libraryLoader: LibraryLoader? = null

    @Volatile
    private var embedStore: EmbeddingStore? = null

    @Volatile
    private var embedIndexer: EmbeddingIndexer? = null

    @Volatile
    private var engine: DistillEngine? = null

    @Volatile
    private var distillCoord: DistillCoordinator? = null

    val repository: PipoRepository
        get() = overrideRepository ?: emptyRepository

    val tasteProfileStore: TasteProfileStore
        get() = profileStore ?: error("PipoGraph.installContext() must be called before tasteProfileStore")

    val audioFeaturesStore: AudioFeaturesStore
        get() = featuresStore ?: error("PipoGraph.installContext() must be called before audioFeaturesStore")

    val behaviorLog: BehaviorLog
        get() = behaviorLogger ?: error("PipoGraph.installContext() must be called before behaviorLog")

    val lastPlayback: LastPlaybackStore
        get() = lastPlaybackStore ?: error("PipoGraph.installContext() must be called before lastPlayback")

    val trackSemanticStore: TrackSemanticStore
        get() = semanticStore ?: error("PipoGraph.installContext() must be called before trackSemanticStore")

    val semanticIndexer: SemanticIndexer
        get() = indexer ?: SemanticIndexer(repository, trackSemanticStore, audioFeaturesStore)
            .also { indexer = it }

    val petMemory: PetMemory
        get() = memory ?: error("PipoGraph.installContext() must be called before petMemory")

    val recommendationLog: RecommendationLog
        get() = recLog ?: error("PipoGraph.installContext() must be called before recommendationLog")

    val libraryAnalyzer: LibraryAnalysis
        get() = libraryAnalysis ?: LibraryAnalysis(repository, audioFeaturesStore)
            .also { libraryAnalysis = it }

    val library: LibraryLoader
        get() = libraryLoader ?: LibraryLoader(repository).also { libraryLoader = it }

    val embeddingStore: EmbeddingStore
        get() = embedStore ?: error("PipoGraph.installContext() must be called before embeddingStore")

    val embeddingIndexer: EmbeddingIndexer
        get() = embedIndexer ?: EmbeddingIndexer(repository, embeddingStore, trackSemanticStore)
            .also { embedIndexer = it }

    val distillEngine: DistillEngine
        get() = engine ?: DistillEngine(repository, tasteProfileStore, audioFeaturesStore, behaviorLog)
            .also { engine = it }

    val distillCoordinator: DistillCoordinator
        get() = distillCoord ?: DistillCoordinator(distillEngine).also { distillCoord = it }

    /** Bridge 还没装配前的空仓库——所有页面走 React 端的"空状态"分支。 */
    private val emptyRepository: PipoRepository by lazy { EmptyPipoRepository() }

    fun installRepository(repository: PipoRepository) {
        overrideRepository = repository
        engine = null            // 仓库换了，引擎要跟着换
        indexer = null
        libraryLoader = null
        libraryAnalysis = null
        embedIndexer = null
    }

    fun installContext(context: Context) {
        val app = context.applicationContext
        if (profileStore == null) profileStore = TasteProfileStore(app)
        if (featuresStore == null) featuresStore = AudioFeaturesStore(app)
        if (behaviorLogger == null) behaviorLogger = BehaviorLog(app)
        if (lastPlaybackStore == null) lastPlaybackStore = LastPlaybackStore(app)
        if (semanticStore == null) semanticStore = TrackSemanticStore(app)
        if (memory == null) memory = PetMemory(app)
        if (recLog == null) recLog = RecommendationLog(app)
        if (embedStore == null) embedStore = EmbeddingStore(app)
    }
}
