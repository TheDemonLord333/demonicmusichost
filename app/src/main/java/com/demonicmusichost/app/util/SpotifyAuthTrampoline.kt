package com.demonicmusichost.app.util

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

/**
 * Transparent trampoline that starts Spotify's LoginActivity and forwards the result
 * to SpotifyAuthBus.
 *
 * Why this exists: MainActivity is singleTask. When a singleTask activity calls
 * startActivityForResult, ActivityResultLauncher delivers RESULT_CANCELED immediately
 * (before LoginActivity even opens) and may discard the later RESULT_OK. This activity
 * is standard-launchMode, so it receives RESULT_OK from LoginActivity reliably.
 */
class SpotifyAuthTrampoline : AppCompatActivity() {

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        val authResult = when (response.type) {
            AuthorizationResponse.Type.TOKEN ->
                SpotifyAuthResult.Token(response.accessToken, response.expiresIn)
            AuthorizationResponse.Type.ERROR ->
                SpotifyAuthResult.Error(response.error ?: "Unknown error")
            else -> null
        }
        if (authResult != null) SpotifyAuthBus.emit(authResult)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val request = intent.getParcelableExtra<AuthorizationRequest>(EXTRA_REQUEST)
            ?: run { finish(); return }
        loginLauncher.launch(AuthorizationClient.createLoginActivityIntent(this, request))
    }

    companion object {
        const val EXTRA_REQUEST = "spotify_auth_request"
    }
}
