package com.demonicmusichost.app.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class PlaybackState {
    PLAYING, PAUSED, STOPPED, LOADING, ERROR
}

@Parcelize
data class SessionUser(
    val userId: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis()
) : Parcelable {
    fun toMap(): Map<String, Any?> = mapOf(
        "userId" to userId,
        "displayName" to displayName,
        "avatarUrl" to avatarUrl,
        "isHost" to isHost,
        "joinedAt" to joinedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): SessionUser = SessionUser(
            userId = map["userId"] as? String ?: "",
            displayName = map["displayName"] as? String ?: "",
            avatarUrl = map["avatarUrl"] as? String ?: "",
            isHost = map["isHost"] as? Boolean ?: false,
            joinedAt = (map["joinedAt"] as? Long) ?: (map["joinedAt"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
        )
    }
}

@Parcelize
data class PlaybackInfo(
    val state: PlaybackState = PlaybackState.STOPPED,
    val positionMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable {
    fun toMap(): Map<String, Any?> = mapOf(
        "state" to state.name,
        "positionMs" to positionMs,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PlaybackInfo = PlaybackInfo(
            state = PlaybackState.valueOf(map["state"] as? String ?: PlaybackState.STOPPED.name),
            positionMs = (map["positionMs"] as? Long) ?: (map["positionMs"] as? Number)?.toLong() ?: 0L,
            updatedAt = (map["updatedAt"] as? Long) ?: (map["updatedAt"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
        )
    }
}

@Parcelize
data class Session(
    val sessionId: String = "",
    val sessionCode: String = "",
    val hostUserId: String = "",
    val hostDisplayName: String = "",
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val playbackInfo: PlaybackInfo = PlaybackInfo(),
    val guests: Map<String, SessionUser> = emptyMap(),
    val guestsCanAddSongs: Boolean = true,
    val maxGuests: Int = 50,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) : Parcelable {

    fun getGuestCount(): Int = guests.size

    fun isHost(userId: String): Boolean = hostUserId == userId

    fun isGuestAllowed(): Boolean = guests.size < maxGuests && isActive
}
