package com.demonicmusichost.backend.websocket

import com.demonicmusichost.backend.model.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Zentraler WebSocket-Hub.
 * Hält alle aktiven Connections pro Session und broadcastet Events
 * an Host + alle Gäste (oder nur an eine Teilmenge).
 */
class SessionHub {

    private val log = LoggerFactory.getLogger(SessionHub::class.java)

    data class Connection(
        val session: WebSocketSession,
        val userId: String,
        val role: String   // "host" | "guest"
    )

    // sessionCode → Liste aktiver Connections
    private val connections = ConcurrentHashMap<String, MutableList<Connection>>()
    private val mutex = Mutex()

    suspend fun join(sessionCode: String, conn: Connection) {
        mutex.withLock {
            connections.getOrPut(sessionCode) { mutableListOf() }.add(conn)
        }
        log.info("WS join  [$sessionCode] userId=${conn.userId} role=${conn.role} " +
                "total=${connections[sessionCode]?.size}")
    }

    suspend fun leave(sessionCode: String, session: WebSocketSession) {
        mutex.withLock {
            connections[sessionCode]?.removeIf { it.session === session }
            if (connections[sessionCode]?.isEmpty() == true) connections.remove(sessionCode)
        }
        log.info("WS leave [$sessionCode] total=${connections[sessionCode]?.size ?: 0}")
    }

    /** Broadcast an alle Verbindungen dieser Session */
    suspend fun broadcast(sessionCode: String, type: String, payload: Any? = null) {
        val msg = buildEnvelope(type, payload)
        val conns = mutex.withLock { connections[sessionCode]?.toList() } ?: return
        conns.forEach { conn ->
            runCatching { conn.session.send(Frame.Text(msg)) }
                .onFailure { log.warn("WS send failed for ${conn.userId}: ${it.message}") }
        }
    }

    /** Broadcast nur an Gäste */
    suspend fun broadcastToGuests(sessionCode: String, type: String, payload: Any? = null) {
        val msg = buildEnvelope(type, payload)
        val conns = mutex.withLock {
            connections[sessionCode]?.filter { it.role == "guest" }?.toList()
        } ?: return
        conns.forEach { conn ->
            runCatching { conn.session.send(Frame.Text(msg)) }
        }
    }

    /** Sende nur an den Host */
    suspend fun sendToHost(sessionCode: String, type: String, payload: Any? = null) {
        val msg = buildEnvelope(type, payload)
        val host = mutex.withLock {
            connections[sessionCode]?.firstOrNull { it.role == "host" }
        } ?: return
        runCatching { host.session.send(Frame.Text(msg)) }
    }

    fun connectionCount(sessionCode: String): Int = connections[sessionCode]?.size ?: 0

    private fun buildEnvelope(type: String, payload: Any?): String {
        val env = if (payload == null) {
            WsEnvelope(type = type)
        } else {
            val jsonEl = when (payload) {
                is WsQueueUpdatePayload  -> Json.encodeToJsonElement(payload)
                is WsNowPlayingPayload   -> Json.encodeToJsonElement(payload)
                is WsGuestJoinedPayload  -> Json.encodeToJsonElement(payload)
                is WsGuestLeftPayload    -> Json.encodeToJsonElement(payload)
                is WsErrorPayload        -> Json.encodeToJsonElement(payload)
                is WsWelcomePayload      -> Json.encodeToJsonElement(payload)
                else -> Json.encodeToJsonElement(payload.toString())
            }
            WsEnvelope(type = type, payload = jsonEl)
        }
        return Json.encodeToString(env)
    }
}
