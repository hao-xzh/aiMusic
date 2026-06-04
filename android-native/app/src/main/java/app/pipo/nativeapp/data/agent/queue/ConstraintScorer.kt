package app.pipo.nativeapp.data.agent.queue

import app.pipo.nativeapp.data.NativeTrack
import app.pipo.nativeapp.data.agent.normalize.CommandTextSignals

class ConstraintScorer {
    fun hitsAvoidTerm(track: NativeTrack, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val haystack = CommandTextSignals.normalizeForMatch("${track.title} ${track.artist} ${track.album}")
        return terms
            .map(CommandTextSignals::normalizeForMatch)
            .filter { it.isNotBlank() }
            .any { it in haystack }
    }
}
