package com.demonicmusichost.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.demonicmusichost.app.MainActivity
import com.demonicmusichost.app.MusicHostApp
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private var onPlaybackCompleted: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onPlaybackCompleted?.invoke()
                    }
                }
            })
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    fun playSong(song: Song) {
        if (song.source == SongSource.LOCAL && song.localFilePath.isNotBlank()) {
            val mediaItem = MediaItem.fromUri(Uri.parse(song.localFilePath))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
        // Spotify: handled by Spotify SDK / API (not ExoPlayer)
        // YouTube: handled by WebView embedded player in Fragment
    }

    fun pausePlayback() {
        player.pause()
    }

    fun resumePlayback() {
        player.play()
    }

    fun stopPlayback() {
        player.stop()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long = player.currentPosition

    fun isPlaying(): Boolean = player.isPlaying

    fun setOnPlaybackCompleted(callback: () -> Unit) {
        onPlaybackCompleted = callback
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
