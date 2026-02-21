package com.demonicmusichost.app.ui.guest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.SongSource
import com.demonicmusichost.app.databinding.FragmentGuestBinding
import com.demonicmusichost.app.ui.queue.QueueAdapter
import com.demonicmusichost.app.util.showSnackbar
import com.demonicmusichost.app.util.toTimeString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GuestFragment : Fragment() {

    private var _binding: FragmentGuestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GuestViewModel by viewModels()
    private lateinit var queueAdapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGuestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupQueue()
        setupControls()
        setupWebView()
        observeViewModel()
    }

    private fun setupQueue() {
        queueAdapter = QueueAdapter(isHost = false)
        binding.rvQueue.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }
    }

    private fun setupControls() {
        binding.btnAddToQueue.setOnClickListener {
            if (!viewModel.canAddSongs()) {
                binding.root.showSnackbar("Der Host hat das Hinzufügen deaktiviert")
                return@setOnClickListener
            }
            findNavController().navigate(
                GuestFragmentDirections.actionGuestToSearch(
                    sessionId = viewModel.session.value?.sessionId ?: "",
                    isHost = false
                )
            )
        }

        binding.btnLeaveSession.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Session verlassen")
                .setMessage("Möchtest du die Session wirklich verlassen?")
                .setPositiveButton("Verlassen") { _, _ -> viewModel.leaveSession() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun setupWebView() {
        binding.webViewYouTube.apply {
            settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.session.collect { session ->
                session?.let {
                    binding.tvHostName.text = "Host: ${it.hostDisplayName}"
                    binding.tvGuestCount.text = "${it.getGuestCount()} Gäste"

                    val song = it.currentSong
                    if (song != null) {
                        binding.layoutNowPlaying.isVisible = true
                        binding.tvSongTitle.text = song.title
                        binding.tvSongArtist.text = song.artist
                        binding.tvDuration.text = song.durationMs.toTimeString()
                        binding.chipSource.text = when (song.source) {
                            SongSource.SPOTIFY -> "Spotify"
                            SongSource.YOUTUBE -> "YouTube"
                            SongSource.LOCAL -> "Lokal"
                        }
                        if (song.thumbnailUrl.isNotBlank()) {
                            Glide.with(this@GuestFragment)
                                .load(song.thumbnailUrl)
                                .placeholder(R.drawable.ic_music_note)
                                .into(binding.ivAlbumArt)
                        }

                        val isSpotify = song.source == SongSource.SPOTIFY
                        binding.layoutSpotifyInfo.isVisible = isSpotify
                        binding.webViewYouTube.isVisible = song.source == SongSource.YOUTUBE

                        if (isSpotify) {
                            binding.tvSpotifyInfo.text = "Wird über Spotify des Hosts abgespielt"
                        }
                    } else {
                        binding.layoutNowPlaying.isVisible = false
                        binding.webViewYouTube.isVisible = false
                    }

                    binding.btnAddToQueue.isEnabled = it.guestsCanAddSongs
                    binding.tvAddDisabled.isVisible = !it.guestsCanAddSongs
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.queue.collect { songs ->
                queueAdapter.submitList(songs)
                binding.tvQueueEmpty.isVisible = songs.isEmpty()
                binding.tvQueueCount.text = "${songs.size} Songs in der Warteschlange"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is GuestEvent.ShowError -> binding.root.showSnackbar(event.message)
                    is GuestEvent.ShowMessage -> binding.root.showSnackbar(event.message)
                    GuestEvent.SessionLeft -> findNavController().navigateUp()
                    GuestEvent.SessionEnded -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Session beendet")
                            .setMessage("Der Host hat die Session beendet.")
                            .setPositiveButton("OK") { _, _ -> findNavController().navigateUp() }
                            .setCancelable(false)
                            .show()
                    }
                    is GuestEvent.PlayYouTube -> {
                        val url = "https://www.youtube.com/embed/${event.videoId}?autoplay=1"
                        binding.webViewYouTube.loadUrl(url)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.webViewYouTube.destroy()
        _binding = null
    }
}
