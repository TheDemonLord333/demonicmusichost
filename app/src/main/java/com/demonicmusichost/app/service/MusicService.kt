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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject lateinit var playbackEventBus: PlaybackEventBus

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playbackEventBus.notifySongEnded()
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
