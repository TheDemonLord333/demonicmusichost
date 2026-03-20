package com.demonicmusichost.backend.routes

import com.demonicmusichost.backend.model.*
import com.demonicmusichost.backend.service.SessionService
import com.demonicmusichost.backend.websocket.SessionHub
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WsRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Route.wsRoutes(hub: SessionHub, sessionService: SessionService) {

    // ws://server/ws/{sessionCode}
    webSocket("/ws/{code}") {
        val code = call.parameters["code"]?.uppercase()
        if (code == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing session code"))
            return@webSocket
        }

        val session = sessionService.getSession(code)
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session not found"))
            return@webSocket
        }

        var userId      = ""
        var role        = "guest"
        var displayName = "Unknown"
        var joined      = false

        try {
            incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val text = frame.readText()

                val envelope = runCatching { json.decodeFromString<WsEnvelope>(text) }.getOrNull()
                    ?: return@consumeEach

                when (envelope.type) {

                    WsType.JOIN -> {
                        val payload = runCatching {
                            json.decodeFromJsonElement<WsJoinPayload>(envelope.payload!!)
                        }.getOrNull() ?: return@consumeEach

                        userId      = payload.userId
                        role        = payload.role
                        displayName = payload.displayName

                        val conn = SessionHub.Connection(this, userId, role)
                        hub.join(code, conn)
                        joined = true

                        // Willkommensnachricht mit aktuellem State
                        val s = sessionService.getSession(code) ?: return@consumeEach
                        hub.broadcast(code, WsType.WELCOME, WsWelcomePayload(
                            sessionCode  = code,
                            role         = role,
                            currentSong  = s.currentSong,
                            queue        = s.queue.toList(),
                            playbackInfo = s.playbackInfo,
                            guestCount   = s.guests.size
                        ))

                        log.info("WS JOIN [$code] userId=$userId role=$role name=$displayName")
                    }

                    WsType.PING -> {
                        // Nur an diesen Client antworten
                        send(Frame.Text("""{"type":"${WsType.PONG}"}"""))
                    }

                    else -> log.debug("WS unknown type=${envelope.type} from $userId")
                }
            }
        } catch (e: Exception) {
            log.warn("WS error [$code] userId=$userId: ${e.message}")
        } finally {
            if (joined) {
                hub.leave(code, this)
                if (role == "guest" && userId.isNotBlank()) {
                    sessionService.removeGuest(code, userId)
                }
                log.info("WS LEAVE [$code] userId=$userId")
            }
        }
    }
}
