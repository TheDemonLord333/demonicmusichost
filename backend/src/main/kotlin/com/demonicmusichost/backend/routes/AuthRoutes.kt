package com.demonicmusichost.backend.routes

import com.demonicmusichost.backend.service.SessionService
import com.demonicmusichost.backend.service.SpotifyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
data class UserSession(val userId: String, val sessionCode: String? = null)

private val log = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(spotify: SpotifyService, sessionService: SessionService) {

    route("/auth") {

        // GET /auth/login?display_name=...
        // → Leitet den Host zu Spotify OAuth weiter
        get("/login") {
            val displayName = call.request.queryParameters["display_name"] ?: "Host"
            // state = "userId:displayName" — wird im Callback zurückgegeben
            val userId = UUID.randomUUID().toString()
            val state  = "$userId|${displayName.take(32)}"
            call.respondRedirect(spotify.buildAuthUrl(state))
        }

        // GET /auth/callback?code=...&state=...
        // → Tauscht Code gegen Token, erstellt Session, setzt Cookie
        get("/callback") {
            val code  = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"] ?: ""
            val error = call.request.queryParameters["error"]

            if (error != null || code == null) {
                call.respondRedirect("/?auth_error=${error ?: "missing_code"}")
                return@get
            }

            try {
                val parts       = state.split("|", limit = 2)
                val userId      = parts.getOrElse(0) { UUID.randomUUID().toString() }
                val displayName = parts.getOrElse(1) { "Host" }

                val tokenResp = spotify.exchangeCode(code)
                spotify.storeTokens(userId, tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn)

                // Spotify-Nutzerprofil laden um die richtige User-ID zu bekommen
                val spotifyUser = spotify.getCurrentUser(tokenResp.accessToken)
                val finalUserId = spotifyUser?.id ?: userId
                val finalName   = spotifyUser?.displayName ?: displayName

                // Token unter der echten Spotify-ID neu speichern
                if (finalUserId != userId) {
                    spotify.storeTokens(finalUserId, tokenResp.accessToken, tokenResp.refreshToken, tokenResp.expiresIn)
                }

                // Session erstellen
                val session = sessionService.createSession(finalUserId, finalName)

                // Cookie setzen
                call.sessions.set(UserSession(userId = finalUserId, sessionCode = session.sessionCode))

                log.info("Auth OK: userId=$finalUserId name=$finalName sessionCode=${session.sessionCode}")

                // Weiterleitung zur Host-Oberfläche
                call.respondRedirect("/host?code=${session.sessionCode}")

            } catch (e: Exception) {
                log.error("Auth callback failed: ${e.message}", e)
                call.respondRedirect("/?auth_error=token_exchange_failed")
            }
        }

        // GET /auth/token
        // → Gibt den aktuellen Access-Token zurück (für WebView/Web-Client)
        get("/token") {
            val userSession = call.sessions.get<UserSession>()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not_authenticated"))
            val token = spotify.getValidToken(userSession.userId)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "no_token"))
            call.respond(mapOf("access_token" to token))
        }

        // POST /auth/logout
        get("/logout") {
            val userSession = call.sessions.get<UserSession>()
            if (userSession != null) {
                spotify.removeTokens(userSession.userId)
                sessionService.getSessionByHost(userSession.userId)?.let {
                    sessionService.endSession(it.sessionCode, userSession.userId)
                }
                call.sessions.clear<UserSession>()
            }
            call.respondRedirect("/")
        }
    }
}
