package app.pipo.nativeapp.playback

internal interface LyricPlaybackPositionProvider : () -> Long {
    val playbackSpeed: Float
    val discontinuitySequence: Long
}

internal class PlayerLyricPlaybackPositionProvider(
    private val viewModel: PlayerViewModel,
) : LyricPlaybackPositionProvider {
    override fun invoke(): Long = viewModel.currentPlaybackPositionMs()

    override val playbackSpeed: Float
        get() = viewModel.lyricPlaybackSpeed

    override val discontinuitySequence: Long
        get() = viewModel.lyricPlaybackDiscontinuitySequence
}
