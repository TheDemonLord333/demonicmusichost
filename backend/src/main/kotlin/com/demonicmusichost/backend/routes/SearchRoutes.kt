package com.demonicmusichost.backend.routes

import com.demonicmusichost.backend.service.SpotifyService
import com.demonicmusichost.backend.service.YouTubeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Route.searchRoutes(spotify: SpotifyService, youtube: YouTubeService) {

    route("/api") {

        // GET /api/search?q=...&source=spotify|youtube|all&limit=20
        // Authentifizierung: Session-Cookie (Host) oder sessionCode-Parameter (Gäste)
        get("/search") {
            val query  = call.request.queryParameters["q"]?.trim()
            val source = call.request.queryParameters["source"] ?: "spotify"
            val limit  = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20

            if (query.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing query parameter 'q'"))
                return@get
            }

            // Token bestimmen: Host hat Cookie, Gast gibt sessionCode mit
            val userSession = call.sessions.get<UserSession>()
            val accessToken: String? = when {
                userSession != null -> spotify.getValidToken(userSession.userId)
                else -> {
                    // Gäste können nicht im Namen des Hosts suchen ohne Delegation.
                    // Für jetzt: Spotify-Suche nur für authentifizierte Hosts.
                    null
                }
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
