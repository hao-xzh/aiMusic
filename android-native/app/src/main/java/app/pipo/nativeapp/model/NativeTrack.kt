package app.pipo.nativeapp.model

data class NativeTrack(
    val id: String,
    val neteaseId: Long? = null,
    val title: String,
    val artist: String,
    val album: String,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
)

object DemoTracks {
    val queue = listOf(
        NativeTrack(
            id = "native-demo-1",
            neteaseId = 1,
            title = "Native Playback Seed",
            artist = "Pipo",
            album = "First Android Build",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            artworkUrl = "https://picsum.photos/900/900?random=401",
        ),
        NativeTrack(
            id = "native-demo-2",
            neteaseId = 2,
            title = "Compose Surface",
            artist = "Pipo",
            album = "First Android Build",
            streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
            artworkUrl = "https://picsum.photos/900/900?random=402",
        ),
    )
}
