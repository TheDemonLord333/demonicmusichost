package com.demonicmusichost.backend.routes

import com.demonicmusichost.backend.service.SessionService
import com.demonicmusichost.backend.service.SpotifyService
import com.demonicmusichost.backend.service.YouTubeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Route.searchRoutes(spotify: SpotifyService, youtube: YouTubeService, sessionService: SessionService) {

    route("/api") {

        // GET /api/search?q=...&source=spotify|youtube|all&limit=20&sessionCode=ABC123
        // sessionCode erlaubt Gästen Spotify-Suche über den Host-Token
        get("/search") {
            val query       = call.request.queryParameters["q"]?.trim()
            val source      = call.request.queryParameters["source"] ?: "all"
            val limit       = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val sessionCode = call.request.queryParameters["sessionCode"]?.uppercase()

            if (query.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing query parameter 'q'"))
                return@get
            }

            // Token-Auflösung: Cookie (Host) > sessionCode (Gast nutzt Host-Token) > null
            val userSession = call.sessions.get<UserSession>()
            val accessToken: String? = userSession?.let { spotify.getValidToken(it.userId) }
                ?: sessionCode?.let { code ->
                    sessionService.getSession(code)?.hostUserId?.let { spotify.getValidToken(it) }
                }

            val spotifyResults = if (source in listOf("spotify", "all") && accessToken != null) {
                spotify.searchTracks(accessToken, query, limit)
            } else emptyList()

            val youtubeResults = if (source in listOf("youtube", "all")) {
                youtube.searchVideos(query, limit)
            } else emptyList()

            call.respond(mapOf(
                "spotify" to spotifyResults,
                "youtube" to youtubeResults
            ))
        }
    }
}
