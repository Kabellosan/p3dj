package io.github.kabellosan.p3dj

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays P3 in the background (screen locked, car, lock screen) and turns the
 * "next track" button everywhere (notification, headphones, car, Android Auto) into Skip.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    companion object {
        const val STREAM = "https://live-icy.dr.dk/A/A05H.mp3"

        fun p3Item(): MediaItem = MediaItem.Builder()
            .setMediaId("p3")
            .setUri(STREAM)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("P3 live").setArtist("P3 DJ")
                    .setIsPlayable(true).setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()
            ).build()

        private val root: MediaItem = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("P3 DJ").setIsPlayable(false).setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()
            ).build()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val e = metadata.get(i)
                    if (e is IcyInfo) Dj.onTitle(e.title)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = Dj.onPlaying(isPlaying)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_IDLE && !exo.playWhenReady) Dj.onStopped()
            }
        })
        Dj.attach(this, exo)

        val skipPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    super.isCommandAvailable(command)
            override fun hasNextMediaItem() = true
            override fun seekToNext() = Dj.skip()
            override fun seekToNextMediaItem() = Dj.skip()
            override fun play() {
                // Pressing play while skipping means "back to P3 now".
                if (Dj.state.value.mode == Dj.Mode.SPOTIFY) Dj.back("You went back to P3.")
                if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) {
                    if (exo.mediaItemCount == 0) exo.setMediaItem(p3Item())
                    exo.prepare()
                }
                super.play()
            }
            override fun stop() { super.stop(); Dj.onStopped() }
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, skipPlayer, LibraryCallback())
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root, params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(p3Item()), params))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(if (mediaId == "root") root else p3Item(), null))

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { p3Item() }.toMutableList())
    }
}
