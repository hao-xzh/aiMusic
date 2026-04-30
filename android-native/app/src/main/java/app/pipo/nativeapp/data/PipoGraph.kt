package app.pipo.nativeapp.data

object PipoGraph {
    @Volatile
    private var overrideRepository: PipoRepository? = null

    val repository: PipoRepository
        get() = overrideRepository ?: demoRepository

    private val demoRepository: PipoRepository by lazy { DemoPipoRepository() }

    fun installRepository(repository: PipoRepository) {
        overrideRepository = repository
    }
}
