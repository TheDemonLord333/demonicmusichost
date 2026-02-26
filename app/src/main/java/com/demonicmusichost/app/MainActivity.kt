package com.demonicmusichost.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.demonicmusichost.app.databinding.ActivityMainBinding
import com.demonicmusichost.app.util.SpotifyAuthBus
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
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
    }

    /**
     * Called when the Spotify OAuth redirect (demonicmusichost://callback) arrives.
     * Because MainActivity is singleTop and owns the redirect intent-filter,
     * Chrome Custom Tab delivers the token/error URI here rather than to LoginActivity.
     * We parse it and broadcast via SpotifyAuthBus so HomeFragment can react.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val response = AuthorizationClient.getResponse(RESULT_OK, intent)
        if (response.type != AuthorizationResponse.Type.EMPTY) {
            SpotifyAuthBus.emit(response)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
