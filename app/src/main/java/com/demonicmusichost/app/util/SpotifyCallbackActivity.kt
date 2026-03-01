package com.demonicmusichost.app.util

import android.app.Activity
import android.os.Bundle
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse

/**
 * Transparent trampoline activity that receives the Spotify OAuth redirect
 * and forwards the result to whoever launched the auth flow.
 */
class SpotifyCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val response = AuthorizationClient.getResponse(Activity.RESULT_OK, intent)
        val result = when (response.type) {
            AuthorizationResponse.Type.TOKEN ->
                SpotifyAuthResult.Token(response.accessToken, response.expiresIn)
            AuthorizationResponse.Type.ERROR ->
                SpotifyAuthResult.Error(response.error ?: "Unknown error")
            else -> null
        }
        if (result != null) SpotifyAuthBus.emit(result)
        finish()
    }
}
