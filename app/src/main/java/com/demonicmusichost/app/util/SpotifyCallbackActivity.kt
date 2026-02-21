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

        val response = AuthorizationClient.getResponse(
            AuthorizationResponse.Type.TOKEN.ordinal,
            intent
        )
        // Broadcast the response so the ViewModel / fragment can pick it up
        SpotifyAuthBus.emit(response)
        finish()
    }
}
