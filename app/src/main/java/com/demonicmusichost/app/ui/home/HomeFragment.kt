package com.demonicmusichost.app.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.demonicmusichost.app.R
import com.demonicmusichost.app.databinding.FragmentHomeBinding
import com.demonicmusichost.app.util.hide
import com.demonicmusichost.app.util.show
import com.demonicmusichost.app.util.showSnackbar
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    private val spotifyAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val response = AuthorizationClient.getResponse(result.resultCode, result.data)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    viewModel.onSpotifyAuthSuccess(response.accessToken, response.expiresIn)
                }
                AuthorizationResponse.Type.ERROR -> {
                    binding.root.showSnackbar("Spotify-Anmeldung fehlgeschlagen: ${response.error}")
                }
                else -> Unit
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        observeViewModel()
    }

    private fun setupUi() {
        binding.btnHostSession.setOnClickListener {
            viewModel.createHostSession()
        }

        binding.btnJoinSession.setOnClickListener {
            val code = binding.etSessionCode.text.toString()
            viewModel.joinSession(code)
        }

        binding.btnSpotifyLogin.setOnClickListener {
            launchSpotifyAuth()
        }

        binding.etSessionCode.setOnEditorActionListener { _, _, _ ->
            val code = binding.etSessionCode.text.toString()
            viewModel.joinSession(code)
            true
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.isVisible = loading
                binding.btnHostSession.isEnabled = !loading
                binding.btnJoinSession.isEnabled = !loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSpotifyAuthenticated.collect { authenticated ->
                binding.layoutSpotifyStatus.isVisible = authenticated
                binding.btnSpotifyLogin.isVisible = !authenticated
                binding.btnHostSession.isEnabled = authenticated
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSpotifyPremium.collect { isPremium ->
                binding.ivPremiumBadge.isVisible = isPremium
                binding.tvPremiumStatus.text = if (isPremium) {
                    getString(R.string.spotify_premium_active)
                } else {
                    getString(R.string.spotify_free_account)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.spotifyDisplayName.collect { name ->
                binding.tvSpotifyUser.text = name ?: ""
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is HomeEvent.NavigateToHost -> {
                        findNavController().navigate(
                            HomeFragmentDirections.actionHomeToHost(event.session.sessionId)
                        )
                    }
                    is HomeEvent.NavigateToGuest -> {
                        findNavController().navigate(
                            HomeFragmentDirections.actionHomeToGuest(event.session.sessionId)
                        )
                    }
                    is HomeEvent.ShowError -> {
                        binding.root.showSnackbar(event.message)
                    }
                    HomeEvent.SpotifyAuthRequired -> launchSpotifyAuth()
                }
            }
        }

    }

    private fun launchSpotifyAuth() {
        val request = viewModel.getSpotifyAuthRequest()
        val intent = AuthorizationClient.createLoginActivityIntent(requireActivity(), request)
        spotifyAuthLauncher.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
