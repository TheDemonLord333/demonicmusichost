package com.demonicmusichost.app.data.network

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die Basis-URL des Ktor-Backends und leitet WebSocket-URLs ab.
 * Standardmäßig zeigt die URL auf den Android-Emulator-Localhost (10.0.2.2:8080).
 * Für echte Geräte/Produktion muss [baseUrl] auf die Server-IP bzw. Domain geändert werden.
 *
 * Der Wert wird in SharedPreferences persistiert, damit er über App-Starts erhalten bleibt.
 */
@Singleton
class BackendConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("backend_prefs", Context.MODE_PRIVATE)

    /**
     * HTTP-Basis-URL des Backends, z.B. "http://192.168.1.100:8080"
     * oder "https://deine-domain.de".
     */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            val normalized = value.trimEnd('/')
            prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        }

    /** True wenn eine URL konfiguriert wurde (nicht leer und nicht der Emulator-Standard). */
    fun isConfigured(): Boolean = baseUrl.isNotBlank()

    /** Baut die WebSocket-URL für eine Session. */
    fun wsUrl(sessionCode: String): String =
        baseUrl.replace("https://", "wss://").replace("http://", "ws://") + "/ws/$sessionCode"

    /** REST-Endpunkte */
    fun registerTokenUrl()                = "$baseUrl/auth/register-token"
    fun refreshTokenUrl()                 = "$baseUrl/auth/refresh-token"
    fun sessionUrl(code: String)          = "$baseUrl/session/$code"
    fun queueUrl(code: String)            = "$baseUrl/session/$code/queue"
    fun removeSongUrl(code: String, id: String) = "$baseUrl/session/$code/queue/$id"
    fun nowPlayingUrl(code: String)       = "$baseUrl/session/$code/now-playing"
    fun queueNextUrl(code: String)        = "$baseUrl/session/$code/queue/next"

    companion object {
        private const val KEY_BASE_URL = "base_url"
        /** Android-Emulator verlinkt 10.0.2.2 auf den Host-Localhost. */
        const val DEFAULT_URL = "http://10.0.2.2:8080"
    }
}
