package com.demonicmusichost.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.database.FirebaseDatabase
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MusicHostApp : Application() {

    companion object {
        const val CHANNEL_PLAYBACK = "music_playback"
        const val CHANNEL_SESSION = "session_notifications"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackChannel = NotificationChannel(
                CHANNEL_PLAYBACK,
                "Musikwiedergabe",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Steuert die aktive Musikwiedergabe"
                setShowBadge(false)
            }

            val sessionChannel = NotificationChannel(
                CHANNEL_SESSION,
                "Session Benachrichtigungen",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Benachrichtigungen zu aktiven Sessions"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(playbackChannel)
            manager.createNotificationChannel(sessionChannel)
        }
    }
}
