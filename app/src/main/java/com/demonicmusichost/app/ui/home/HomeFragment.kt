package com.demonicmusichost.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.demonicmusichost.app.R
import com.demonicmusichost.app.databinding.FragmentHomeBinding
import com.demonicmusichost.app.util.SpotifyAuthBus
import com.demonicmusichost.app.util.SpotifyAuthResult
import com.demonicmusichost.app.util.SpotifyAuthTrampoline
import com.demonicmusichost.app.util.hide
import com.demonicmusichost.app.util.show
import com.demonicmusichost.app.util.showSnackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

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

        // SpotifyAuthTrampoline starts LoginActivity in a non-singleTask context and
        // emits the result here, bypassing the RESULT_CANCELED issue that occurs when
        // MainActivity (singleTask) calls startActivityForResult directly.
        viewLifecycleOwner.lifecycleScope.launch {
            SpotifyAuthBus.events.collect { result ->
                when (result) {
                    is SpotifyAuthResult.Token -> {
                        viewModel.onSpotifyAuthSuccess(result.accessToken, result.expiresIn)
                    }
                    is SpotifyAuthResult.Error -> {
                        binding.root.showSnackbar(
                            "Spotify-Anmeldung fehlgeschlagen: ${result.message}"
                        )
                    }
                }
            }
        }
    }

    private fun launchSpotifyAuth() {
        val request = viewModel.getSpotifyAuthRequest()
        val intent = Intent(requireContext(), SpotifyAuthTrampoline::class.java)
            .putExtra(SpotifyAuthTrampoline.EXTRA_REQUEST, request)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
