package app.pipo.nativeapp

/** In-memory diagnostics boundary; production logging is Android-dependent. */
object DiagnosticsLogStore {
    val events = mutableListOf<Map<String, Any?>>()
    fun record(area: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        events.add(mapOf("area" to area, "event" to event) + fields)
    }
}
