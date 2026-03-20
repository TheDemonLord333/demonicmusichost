package com.demonicmusichost.backend.service

import com.demonicmusichost.backend.model.*
import com.demonicmusichost.backend.websocket.SessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Verwaltet alle aktiven Sessions im Arbeitsspeicher.
 * (Persistenz via Datenbank kann später ergänzt werden.)
 */
class SessionService(private val hub: SessionHub) {

    private val log = LoggerFactory.getLogger(SessionService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    // sessionCode → Session
    private val sessions = ConcurrentHashMap<String, Session>()
    // hostUserId → sessionCode (damit jeder Host max. eine aktive Session hat)
    private val hostToSession = ConcurrentHashMap<String, String>()

    // ── Session-Lifecycle ──────────────────────────────────────────────────

    fun createSession(hostUserId: String, hostDisplayName: String): Session {
        // Bestehende Session des Hosts beenden
        hostToSession[hostUserId]?.let { endSession(it, hostUserId) }

        val code = generateUniqueCode()
        val session = Session(
            sessionId       = java.util.UUID.randomUUID().toString(),
            sessionCode     = code,
            hostUserId      = hostUserId,
            hostDisplayName = hostDisplayName
        )
        sessions[code] = session
        hostToSession[hostUserId] = code
        log.info("Session created: code=$code host=$hostDisplayName")
        return session
    }

    fun getSession(code: String): Session? = sessions[code]

    fun getSessionByHost(hostUserId: String): Session? =
        hostToSession[hostUserId]?.let { sessions[it] }

    fun endSession(code: String, requestingUserId: String): Boolean {
        val session = sessions[code] ?: return false
        if (session.hostUserId != requestingUserId) return false
        session.isActive = false
        sessions.remove(code)
        hostToSession.remove(session.hostUserId)
        scope.launch { hub.broadcast(code, WsType.SESSION_ENDED) }
        log.info("Session ended: code=$code")
        return true
    }

    // ── Gäste ──────────────────────────────────────────────────────────────

    fun joinAsGuest(code: String, userId: String, displayName: String): Session? {
        val session = sessions[code] ?: return null
        if (!session.isActive) return null
        if (session.guests.size >= session.maxGuests) return null
        val guest = SessionGuest(userId = userId, displayName = displayName)
        session.guests[userId] = guest
        scope.launch {
            hub.sendToHost(code, WsType.GUEST_JOINED, WsGuestJoinedPayload(guest))
        }
        return session
    }

    fun removeGuest(code: String, userId: String) {
        val session = sessions[code] ?: return
        session.guests.remove(userId)
        scope.launch {
            hub.broadcast(code, WsType.GUEST_LEFT, WsGuestLeftPayload(userId))
        }
    }

    // ── Queue ──────────────────────────────────────────────────────────────

    fun addToQueue(code: String, song: Song): Boolean {
        val session = sessions[code] ?: return false
        if (!session.isActive) return false
        session.queue.add(song)
        scope.launch {
            hub.broadcast(code, WsType.QUEUE_UPDATE, WsQueueUpdatePayload(session.queue.toList()))
        }
        return true
    }

    fun removeFromQueue(code: String, songId: String, requestingUserId: String): Boolean {
        val session = sessions[code] ?: return false
        if (session.hostUserId != requestingUserId) return false
        val removed = session.queue.removeIf { it.id == songId }
        if (removed) {
            scope.launch {
                hub.broadcast(code, WsType.QUEUE_UPDATE, WsQueueUpdatePayload(session.queue.toList()))
            }
        }
        return removed
    }

    fun getQueue(code: String): List<Song>? = sessions[code]?.queue?.toList()

    // ── Playback ───────────────────────────────────────────────────────────

    fun updateNowPlaying(code: String, song: Song?, playbackInfo: PlaybackInfo, requestingUserId: String): Boolean {
        val session = sessions[code] ?: return false
        if (session.hostUserId != requestingUserId) return false
        // Immutable replacement
        val updated = session.copy(currentSong = song, playbackInfo = playbackInfo)
        sessions[code] = updated
        scope.launch {
            hub.broadcast(code, WsType.NOW_PLAYING, WsNowPlayingPayload(song, playbackInfo))
        }
        return true
    }

    /** Nächsten Song aus der Queue nehmen und als currentSong setzen */
    fun advanceQueue(code: String, requestingUserId: String): Song? {
        val session = sessions[code] ?: return null
        if (session.hostUserId != requestingUserId) return null
        if (session.queue.isEmpty()) return null
        val next = session.queue.removeAt(0)
        val info = PlaybackInfo(state = PlaybackState.PLAYING)
        val updated = session.copy(currentSong = next, playbackInfo = info)
        sessions[code] = updated
        scope.launch {
            hub.broadcast(code, WsType.NOW_PLAYING, WsNowPlayingPayload(next, info))
            hub.broadcast(code, WsType.QUEUE_UPDATE, WsQueueUpdatePayload(updated.queue.toList()))
        }
        return next
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────

    private fun generateUniqueCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        var code: String
        do { code = (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("") }
        while (sessions.containsKey(code))
        return code
    }
}
