package app.pipo.nativeapp.playback

internal interface LyricPlaybackPositionProvider : () -> Long {
    val playbackSpeed: Float
    val discontinuitySequence: Long
}
