package com.demonicmusichost.backend.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Alle WebSocket-Nachrichten teilen sich dieses Envelope-Format:
 *   { "type": "QUEUE_UPDATE", "payload": { ... } }
 *
 * Client → Server:
 *   JOIN       { role: "host"|"guest", userId: "...", displayName: "..." }
 *   PING       {}
 *
 * Server → Client (broadcast):
 *   QUEUE_UPDATE      { queue: [Song, ...] }
 *   NOW_PLAYING       { song: Song|null, playbackInfo: PlaybackInfo }
 *   GUEST_JOINED      { guest: SessionGuest }
 *   GUEST_LEFT        { userId: "..." }
 *   SESSION_ENDED     {}
 *   ERROR             { message: "..." }
 *   PONG              {}
 */
@Serializable
data class WsEnvelope(
    val type: String,
    val payload: JsonElement? = null
)

// Typen-Konstanten
object WsType {
    // Client → Server
    const val JOIN = "JOIN"
    const val PING = "PING"

    // Server → Client
    const val QUEUE_UPDATE   = "QUEUE_UPDATE"
    const val NOW_PLAYING    = "NOW_PLAYING"
    const val GUEST_JOINED   = "GUEST_JOINED"
    const val GUEST_LEFT     = "GUEST_LEFT"
    const val SESSION_ENDED  = "SESSION_ENDED"
    const val ERROR          = "ERROR"
    const val PONG           = "PONG"
    const val WELCOME        = "WELCOME"
}

@Serializable
data class WsJoinPayload(
    val role: String,           // "host" | "guest"
    val userId: String,
    val displayName: String
)

@Serializable
data class WsQueueUpdatePayload(
    val queue: List<Song>
)

@Serializable
data class WsNowPlayingPayload(
    val song: Song?,
    val playbackInfo: PlaybackInfo
)

@Serializable
data class WsGuestJoinedPayload(
    val guest: SessionGuest
)

@Serializable
data class WsGuestLeftPayload(
    val userId: String
)

@Serializable
data class WsErrorPayload(
    val message: String
)

@Serializable
data class WsWelcomePayload(
    val sessionCode: String,
    val role: String,
    val currentSong: Song?,
    val queue: List<Song>,
    val playbackInfo: PlaybackInfo,
    val guestCount: Int
)
