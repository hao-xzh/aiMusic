package app.pipo.nativeapp.playback

data class SeamlessFeatureFlags(
    val seamlessOptimizerEnabled: Boolean = true,
    val transitionPreparerEnabled: Boolean = true,
    val realtimeCrossfadeV2Enabled: Boolean = true,
    val preserveOrderStrictEnabled: Boolean = true,
)

object SeamlessRuntimeFlags {
    val current: SeamlessFeatureFlags = SeamlessFeatureFlags()
}
