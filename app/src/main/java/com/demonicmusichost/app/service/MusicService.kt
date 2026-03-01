package com.demonicmusichost.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.demonicmusichost.app.MainActivity
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import dagger.hilt.android.AndroidEntryPoint

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_LOCAL -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
                val mediaItem = MediaItem.fromUri(Uri.parse(filePath))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> player.play()
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

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

    companion object {
        const val ACTION_PLAY_LOCAL = "com.demonicmusichost.ACTION_PLAY_LOCAL"
        const val ACTION_PAUSE     = "com.demonicmusichost.ACTION_PAUSE"
        const val ACTION_RESUME    = "com.demonicmusichost.ACTION_RESUME"
        const val ACTION_STOP      = "com.demonicmusichost.ACTION_STOP"
        const val EXTRA_FILE_PATH  = "extra_file_path"
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
