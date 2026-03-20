package com.demonicmusichost.backend.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackState { PLAYING, PAUSED, STOPPED }

@Serializable
data class SessionGuest(
    val userId: String = "",
    val displayName: String = "",
    val joinedAt: Long = System.currentTimeMillis()
)

@Serializable
data class PlaybackInfo(
    val state: PlaybackState = PlaybackState.STOPPED,
    val positionMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Session(
    val sessionId: String = "",
    val sessionCode: String = "",         // 6-stelliger Code für Gäste
    val hostUserId: String = "",
    val hostDisplayName: String = "",
    val currentSong: Song? = null,
    val queue: MutableList<Song> = mutableListOf(),
    val playbackInfo: PlaybackInfo = PlaybackInfo(),
    val guests: MutableMap<String, SessionGuest> = mutableMapOf(),
    val guestsCanAddSongs: Boolean = true,
    val maxGuests: Int = 50,
    val createdAt: Long = System.currentTimeMillis(),
    var isActive: Boolean = true
)

/** Gespeicherte Spotify-Token pro Host-UserId */
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long        // Unix-Millis
) {
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt - 60_000
}
