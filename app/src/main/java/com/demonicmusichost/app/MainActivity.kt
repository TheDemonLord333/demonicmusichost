package com.demonicmusichost.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.demonicmusichost.app.databinding.ActivityMainBinding
import com.demonicmusichost.app.util.SpotifyAuthBus
import com.demonicmusichost.app.util.SpotifyAuthResult
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Handle Spotify callback if this instance was started fresh from the redirect URI
        // (e.g. system killed MainActivity while LoginActivity was open and recreated it).
        handleSpotifyCallback(intent)
    }

    /**
     * Called when the Spotify OAuth redirect (demonicmusichost://callback) arrives via
     * Chrome Custom Tab firing ACTION_VIEW for the custom URI scheme.
     * Because MainActivity is singleTask it always receives this on the EXISTING instance
     * rather than spawning a new one inside Chrome's task.
     * We parse the response and broadcast it via SpotifyAuthBus so HomeFragment can react.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyCallback(intent)
    }

    private fun handleSpotifyCallback(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        // Chrome Custom Tab redirect delivers the token in the URI fragment:
        // demonicmusichost://callback#access_token=TOKEN&token_type=Bearer&expires_in=3600
        val fragment = intent.data?.fragment ?: return
        val fragmentUri = Uri.parse("?$fragment")
        val accessToken = fragmentUri.getQueryParameter("access_token") ?: return
        val expiresIn = fragmentUri.getQueryParameter("expires_in")?.toIntOrNull() ?: 3600
        SpotifyAuthBus.emit(SpotifyAuthResult.Token(accessToken, expiresIn))
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
