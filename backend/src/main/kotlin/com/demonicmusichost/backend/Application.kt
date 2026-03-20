package com.demonicmusichost.backend

import com.demonicmusichost.backend.routes.*
import com.demonicmusichost.backend.service.SessionService
import com.demonicmusichost.backend.service.SpotifyService
import com.demonicmusichost.backend.service.YouTubeService
import com.demonicmusichost.backend.websocket.SessionHub
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // ── Konfiguration aus Umgebungsvariablen ────────────────────────────────
    val spotifyClientId     = System.getenv("SPOTIFY_CLIENT_ID")     ?: error("SPOTIFY_CLIENT_ID not set")
    val spotifyClientSecret = System.getenv("SPOTIFY_CLIENT_SECRET") ?: error("SPOTIFY_CLIENT_SECRET not set")
    val spotifyRedirectUri  = System.getenv("SPOTIFY_REDIRECT_URI")  ?: "http://localhost:8080/auth/callback"
    val youtubeApiKey       = System.getenv("YOUTUBE_API_KEY")       ?: ""
    val sessionSecret       = System.getenv("SESSION_SECRET")        ?: "change-me-in-production-min-32-chars!!"

    // ── HTTP-Client ─────────────────────────────────────────────────────────
    val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(io.ktor.client.plugins.logging.Logging) {
            level = io.ktor.client.plugins.logging.LogLevel.NONE
        }
    }

    // ── Services ────────────────────────────────────────────────────────────
    val hub            = SessionHub()
    val spotifyService = SpotifyService(httpClient, spotifyClientId, spotifyClientSecret, spotifyRedirectUri)
    val youtubeService = YouTubeService(httpClient, youtubeApiKey)
    val sessionService = SessionService(hub)

    // ── Plugins ─────────────────────────────────────────────────────────────
    install(DefaultHeaders)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint       = false
            isLenient         = true
            ignoreUnknownKeys = true
        })
    }

    install(WebSockets) {
        pingPeriod         = Duration.ofSeconds(30)
        timeout            = Duration.ofSeconds(60)
        maxFrameSize       = 64 * 1024L
        masking            = false
    }

    install(Sessions) {
        cookie<UserSession>("dmh_session") {
            cookie.path      = "/"
            cookie.maxAgeInSeconds = 60 * 60 * 24 * 7  // 7 Tage
            cookie.httpOnly  = true
            cookie.secure    = false  // auf true setzen wenn HTTPS aktiv ist
            transform(SessionTransportTransformerMessageAuthentication(
                sessionSecret.toByteArray()
            ))
        }
    }

    install(CORS) {
        anyHost()   // In Production auf konkrete Domains einschränken
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = true
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "internal server error"))
            )
        }
    }

    // ── Routing ─────────────────────────────────────────────────────────────
    routing {
        // Health-Check
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        authRoutes(spotifyService, sessionService)
        sessionRoutes(sessionService, spotifyService)
        searchRoutes(spotifyService, youtubeService, sessionService)
        wsRoutes(hub, sessionService)

        // Statische Web-Assets aus dem JAR (resources/static/)
        staticResources("/", "static") {
            defaultResource("index.html")
        }
    }
}
