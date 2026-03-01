package com.demonicmusichost.app.util

sealed class SpotifyAuthResult {
    data class Token(val accessToken: String, val expiresIn: Int) : SpotifyAuthResult()
    data class Error(val message: String) : SpotifyAuthResult()
}
