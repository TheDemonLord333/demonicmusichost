package com.demonicmusichost.backend.routes

import com.demonicmusichost.backend.model.*
import com.demonicmusichost.backend.service.SessionService
import com.demonicmusichost.backend.service.SpotifyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

fun Route.sessionRoutes(sessionService: SessionService, spotify: SpotifyService) {

    route("/session") {

        // GET /session/{code} — öffentliche Session-Info für Gäste
        get("/{code}") {
            val code    = call.parameters["code"]?.uppercase()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing code"))
            val session = sessionService.getSession(code)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "session not found"))
            call.respond(SessionInfoResponse(
                sessionCode     = session.sessionCode,
                hostDisplayName = session.hostDisplayName,
                currentSong     = session.currentSong,
                queue           = session.queue.toList(),
                playbackInfo    = session.playbackInfo,
                guestCount      = session.guests.size,
                guestsCanAdd    = session.guestsCanAddSongs
            ))
        }

        // POST /session/{code}/join — Gast tritt bei
        post("/{code}/join") {
            val code = call.parameters["code"]?.uppercase()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing code"))

            @Serializable
            data class JoinRequest(val userId: String, val displayName: String)

            val body    = call.receive<JoinRequest>()
            val session = sessionService.joinAsGuest(code, body.userId, body.displayName)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "session not found or full"))
            call.respond(SessionInfoResponse(
                sessionCode     = session.sessionCode,
                hostDisplayName = session.hostDisplayName,
                currentSong     = session.currentSong,
                queue           = session.queue.toList(),
                playbackInfo    = session.playbackInfo,
                guestCount      = session.guests.size,
                guestsCanAdd    = session.guestsCanAddSongs
            ))
        }

        // DELETE /session/{code} — Host beendet Session
        delete("/{code}") {
            val code        = call.parameters["code"]?.uppercase()
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val userSession = call.sessions.get<UserSession>()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val ok = sessionService.endSession(code, userSession.userId)
            if (ok) call.respond(HttpStatusCode.NoContent)
            else    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not session host"))
        }

        // ── Queue ──────────────────────────────────────────────────────────

        // GET /session/{code}/queue
        get("/{code}/queue") {
            val code  = call.parameters["code"]?.uppercase()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val queue = sessionService.getQueue(code)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("queue" to queue))
        }

        // POST /session/{code}/queue — Song hinzufügen (Host oder Gast)
        post("/{code}/queue") {
            val code = call.parameters["code"]?.uppercase()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val song = call.receive<Song>()
            val ok   = sessionService.addToQueue(code, song)
            if (ok) call.respond(HttpStatusCode.Created, mapOf("status" to "added"))
            else    call.respond(HttpStatusCode.NotFound, mapOf("error" to "session not found"))
        }

        // DELETE /session/{code}/queue/{songId} — Song entfernen (nur Host)
        delete("/{code}/queue/{songId}") {
            val code        = call.parameters["code"]?.uppercase() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val songId      = call.parameters["songId"]            ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val userSession = call.sessions.get<UserSession>()     ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val ok = sessionService.removeFromQueue(code, songId, userSession.userId)
            if (ok) call.respond(HttpStatusCode.NoContent)
            else    call.respond(HttpStatusCode.Forbidden)
        }

        // ── Playback ───────────────────────────────────────────────────────

        // PUT /session/{code}/now-playing — Host meldet aktuellen Track
        put("/{code}/now-playing") {
            val code        = call.parameters["code"]?.uppercase() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val userSession = call.sessions.get<UserSession>()     ?: return@put call.respond(HttpStatusCode.Unauthorized)

            @Serializable
            data class NowPlayingRequest(val song: Song?, val playbackInfo: PlaybackInfo)

            val body = call.receive<NowPlayingRequest>()
            val ok   = sessionService.updateNowPlaying(code, body.song, body.playbackInfo, userSession.userId)
            if (ok) call.respond(HttpStatusCode.NoContent)
            else    call.respond(HttpStatusCode.Forbidden)
        }

        // POST /session/{code}/queue/next — nächsten Song aus Queue laden
        post("/{code}/queue/next") {
            val code        = call.parameters["code"]?.uppercase() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val userSession = call.sessions.get<UserSession>()     ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val next = sessionService.advanceQueue(code, userSession.userId)
            if (next != null) call.respond(mapOf("song" to next))
            else              call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class SessionInfoResponse(
    val sessionCode: String,
    val hostDisplayName: String,
    val currentSong: Song?,
    val queue: List<Song>,
    val playbackInfo: PlaybackInfo,
    val guestCount: Int,
    val guestsCanAdd: Boolean
)
