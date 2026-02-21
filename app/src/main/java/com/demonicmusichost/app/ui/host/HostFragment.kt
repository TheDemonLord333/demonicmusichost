package com.demonicmusichost.app.ui.host

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.SongSource
import com.demonicmusichost.app.databinding.FragmentHostBinding
import com.demonicmusichost.app.ui.queue.QueueAdapter
import com.demonicmusichost.app.util.copyToClipboard
import com.demonicmusichost.app.util.showSnackbar
import com.demonicmusichost.app.util.toTimeString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HostFragment : Fragment() {

    private var _binding: FragmentHostBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HostViewModel by viewModels()
    private lateinit var queueAdapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHostBinding.inflate(inflater, container, false)
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
        queueAdapter = QueueAdapter(
            isHost = true,
            onRemoveClick = { song -> viewModel.removeFromQueue(song) }
        )
        binding.rvQueue.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }

        // Drag-to-reorder
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                queueAdapter.moveItem(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvQueue)
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { viewModel.playPause() }
        binding.btnSkip.setOnClickListener { viewModel.skipSong() }

        binding.btnAddToQueue.setOnClickListener {
            findNavController().navigate(
                HostFragmentDirections.actionHostToSearch(
                    sessionId = viewModel.session.value?.sessionId ?: "",
                    isHost = true
                )
            )
        }

        binding.tvSessionCode.setOnClickListener {
            val code = viewModel.getSessionCode()
            requireContext().copyToClipboard("Session Code", code)
            binding.root.showSnackbar("Session-Code kopiert: $code")
        }

        binding.btnToggleGuestAdd.setOnClickListener {
            viewModel.toggleGuestsCanAdd()
        }

        binding.btnEndSession.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Session beenden")
                .setMessage("Möchtest du die Session wirklich beenden? Alle Gäste werden aus der Session entfernt.")
                .setPositiveButton("Beenden") { _, _ -> viewModel.endSession() }
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
                allowFileAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentSong.collect { song ->
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
                        Glide.with(this@HostFragment)
                            .load(song.thumbnailUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .into(binding.ivAlbumArt)
                    }
                    binding.webViewYouTube.isVisible = song.source == SongSource.YOUTUBE
                } else {
                    binding.layoutNowPlaying.isVisible = false
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isPlaying.collect { playing ->
                binding.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
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
            viewModel.session.collect { session ->
                session?.let {
                    binding.tvSessionCode.text = it.sessionCode
                    binding.tvGuestCount.text = "${it.getGuestCount()} Gäste"
                    binding.btnToggleGuestAdd.text = if (it.guestsCanAddSongs) {
                        "Gäste: Hinzufügen AN"
                    } else {
                        "Gäste: Hinzufügen AUS"
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is HostEvent.ShowError -> binding.root.showSnackbar(event.message)
                    is HostEvent.ShowMessage -> binding.root.showSnackbar(event.message)
                    HostEvent.SessionEnded -> findNavController().navigateUp()
                    is HostEvent.PlayYouTube -> {
                        val url = "https://www.youtube.com/embed/${event.videoId}?autoplay=1"
                        binding.webViewYouTube.loadUrl(url)
                    }
                    is HostEvent.PlayLocal -> {
                        // ExoPlayer playback handled by MusicService
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
