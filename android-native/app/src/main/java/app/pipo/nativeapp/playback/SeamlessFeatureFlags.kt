package app.pipo.nativeapp.playback

data class SeamlessFeatureFlags(
    val agentQueueRequestEnabled: Boolean = true,
    val playbackSessionManagerEnabled: Boolean = true,
    val seamlessOptimizerEnabled: Boolean = true,
    val transitionPreparerEnabled: Boolean = true,
    val realtimeCrossfadeV2Enabled: Boolean = true,
    val preserveOrderStrictEnabled: Boolean = true,
)

object SeamlessRuntimeFlags {
    val current: SeamlessFeatureFlags = SeamlessFeatureFlags()
}
