package app.pipo.nativeapp.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pipo.nativeapp.data.PipoGraph
import app.pipo.nativeapp.data.PipoLyricLine
import app.pipo.nativeapp.model.DemoTracks
import app.pipo.nativeapp.model.NativeTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

data class PlayerUiState(
    val queue: List<NativeTrack> = DemoTracks.queue,
    val currentIndex: Int = 0,
    val title: String = DemoTracks.queue.first().title,
    val artist: String = DemoTracks.queue.first().artist,
    val album: String = DemoTracks.queue.first().album,
    val artworkUrl: String? = DemoTracks.queue.first().artworkUrl,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: List<PipoLyricLine> = emptyList(),
    val isReady: Boolean = false,
) {
    val activeLyricIndex: Int
        get() = lyrics.indexOfLast { line -> positionMs >= line.startMs }.coerceAtLeast(0)
}

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PipoGraph.repository
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PipoPlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var loadedLyricsFor: String? = null

    var state by mutableStateOf(PlayerUiState())
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }
    }

    init {
        controllerFuture.addListener(
            {
                controller = controllerFuture.get().also { player ->
                    player.addListener(listener)
                    if (player.mediaItemCount == 0) {
                        viewModelScope.launch {
                            val queue = runCatching {
                                repository.refreshAccount()
                                repository.refreshPlaylists()
                                val playlistId = repository.playlists.first().firstOrNull()?.id ?: 1L
                                resolvePlayableQueue(repository.tracksForPlaylist(playlistId))
                            }.getOrElse {
                                DemoTracks.queue
                            }.filter { it.streamUrl.isNotBlank() }.ifEmpty {
                                DemoTracks.queue
                            }
                            state = state.copy(queue = queue)
                            player.setMediaItems(queue.map(::toMediaItem))
                            player.prepare()
                            syncFrom(player)
                        }
                    } else {
                        syncFrom(player)
                    }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
        syncFrom(player)
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        syncFrom(player)
    }

    fun refreshPosition() {
        controller?.let(::syncFrom)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
        super.onCleared()
    }

    private fun syncFrom(player: Player) {
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val queue = state.queue.ifEmpty { DemoTracks.queue }
        val track = queue.getOrNull(index) ?: queue.first()
        if (loadedLyricsFor != track.id) {
            loadedLyricsFor = track.id
            viewModelScope.launch {
                val lines = runCatching {
                    repository.lyricsForTrack(track.id)
                }.getOrDefault(emptyList())
                state = state.copy(lyrics = lines)
            }
        }
        state = state.copy(
            currentIndex = index,
            title = player.mediaMetadata.title?.toString() ?: track.title,
            artist = player.mediaMetadata.artist?.toString() ?: track.artist,
            album = player.mediaMetadata.albumTitle?.toString() ?: track.album,
            artworkUrl = player.mediaMetadata.artworkUri?.toString() ?: track.artworkUrl,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: track.durationMs,
            isReady = true,
        )
    }

    private fun toMediaItem(track: NativeTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun resolvePlayableQueue(queue: List<NativeTrack>): List<NativeTrack> {
        val missingIds = queue.mapNotNull { track ->
            if (track.streamUrl.isBlank()) track.neteaseId else null
        }
        if (missingIds.isEmpty()) return queue
        val urls = runCatching {
            repository.songUrls(missingIds).associateBy { it.id }
        }.getOrDefault(emptyMap())
        return queue.map { track ->
            val id = track.neteaseId
            val resolved = if (id != null) urls[id]?.url else null
            if (track.streamUrl.isBlank() && !resolved.isNullOrBlank()) {
                track.copy(streamUrl = resolved)
            } else {
                track
            }
        }
    }
}
