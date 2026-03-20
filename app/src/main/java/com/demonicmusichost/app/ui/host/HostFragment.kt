package com.demonicmusichost.app.ui.host

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.demonicmusichost.app.data.network.BackendConfig
import com.demonicmusichost.app.databinding.DialogWebInviteBinding
import com.demonicmusichost.app.databinding.FragmentHostBinding
import com.demonicmusichost.app.service.MusicService
import com.demonicmusichost.app.ui.queue.QueueAdapter
import com.demonicmusichost.app.util.copyToClipboard
import com.demonicmusichost.app.util.showSnackbar
import com.demonicmusichost.app.util.toTimeString
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HostFragment : Fragment() {

    private var _binding: FragmentHostBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HostViewModel by viewModels()
    private lateinit var queueAdapter: QueueAdapter

    @Inject lateinit var backendConfig: BackendConfig

    /** Reference to the YouTubePlayer once it is ready. */
    private var youTubePlayer: YouTubePlayer? = null

    /** Manages the hidden WebView running the Spotify Web Playback SDK. */
    private lateinit var spotifyWebPlayer: SpotifyWebPlayer

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
        setupYouTubePlayer()
        setupSpotifyWebPlayer()
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
        binding.btnPrevious.setOnClickListener { viewModel.playPrevious() }
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

        binding.btnToggleGuestAdd.setOnClickListener { viewModel.toggleGuestsCanAdd() }

        binding.btnWebInvite.setOnClickListener { showWebInviteDialog() }

        binding.btnEndSession.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Session beenden")
                .setMessage("Möchtest du die Session wirklich beenden? Alle Gäste werden aus der Session entfernt.")
                .setPositiveButton("Beenden") { _, _ -> viewModel.endSession() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    // ── Web-Gäste einladen (QR-Code BottomSheet) ──────────────────────────

    private fun showWebInviteDialog() {
        val backendCode = viewModel.getBackendSessionCode()
        if (backendCode.isBlank()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Backend nicht bereit")
                .setMessage("Das Backend ist noch nicht verbunden oder nicht konfiguriert.\n\nEinstellungen öffnen?")
                .setPositiveButton("Einstellungen") { _, _ ->
                    findNavController().navigate(R.id.settingsFragment)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }

        val inviteUrl = "${backendConfig.baseUrl}/guest.html?code=$backendCode"
        val dialogBinding = DialogWebInviteBinding.inflate(layoutInflater)

        val dialog = BottomSheetDialog(requireContext()).apply {
            setContentView(dialogBinding.root)
        }

        // QR-Code generieren
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bits  = QRCodeWriter().encode(inviteUrl, BarcodeFormat.QR_CODE, 512, 512, hints)
            val bmp   = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            dialogBinding.ivQrCode.setImageBitmap(bmp)
        }.onFailure {
            dialogBinding.ivQrCode.isVisible = false
        }

        dialogBinding.tvInviteUrl.text = inviteUrl

        dialogBinding.btnCopyUrl.setOnClickListener {
            requireContext().copyToClipboard("Einlade-Link", inviteUrl)
            binding.root.showSnackbar("Link kopiert")
        }

        dialogBinding.btnShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Tritt meiner Musik-Session bei: $inviteUrl")
            }
            startActivity(Intent.createChooser(shareIntent, "Session teilen"))
        }

        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun setupYouTubePlayer() {
        // Register with the lifecycle so the player is properly released
        viewLifecycleOwner.lifecycle.addObserver(binding.youtubePlayerView)

        binding.youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {

            override fun onReady(youTubePlayer: YouTubePlayer) {
                this@HostFragment.youTubePlayer = youTubePlayer
            }

            override fun onStateChange(
                youTubePlayer: YouTubePlayer,
                state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
            ) {
                when (state) {
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.ENDED -> {
                        // Video finished → auto-advance to next song in our queue
                        viewModel.onYouTubeSongEnded()
                        binding.youtubePlayerView.isVisible = false
                    }
                    else -> Unit
                }
            }

            override fun onError(
                youTubePlayer: YouTubePlayer,
                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
            ) {
                // Error 101/150/152: video owner has disabled embedded playback.
                // Skip automatically so the queue keeps moving.
                binding.root.showSnackbar("YouTube-Video nicht einbettbar – überspringe Song")
                binding.youtubePlayerView.isVisible = false
                viewModel.skipSong()
            }
        })
    }

    private fun setupSpotifyWebPlayer() {
        spotifyWebPlayer = SpotifyWebPlayer(requireContext())
        spotifyWebPlayer.accessTokenProvider = { viewModel.getSpotifyAccessToken() }
        spotifyWebPlayer.onDeviceReady = { deviceId ->
            viewModel.setSpotifyDeviceId(deviceId)
        }
        spotifyWebPlayer.onDeviceNotReady = {
            viewModel.clearSpotifyDeviceId()
        }
        spotifyWebPlayer.onTrackEnded = {
            viewModel.onSpotifyTrackEnded()
        }
        spotifyWebPlayer.onError = { message ->
            // Mark SDK as failed so awaitSdkReady() unblocks immediately and the
            // fallback playback path (Steps 1-3) is used instead of waiting indefinitely.
            viewModel.onSdkError(message)
        }

        // Add the WebView as VISIBLE with alpha=0 inside a 1×1dp container.
        // INVISIBLE blocks the Spotify SDK's audio-pipeline initialisation on some
        // Android versions; VISIBLE+alpha=0 keeps it fully rendered but transparent.
        val webView = spotifyWebPlayer.createWebView()
        val container = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            addView(webView)
        }
        (binding.root as ViewGroup).addView(container)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentSong.collect { song ->
                val hasSong = song != null
                binding.layoutNowPlaying.isVisible = hasSong
                binding.layoutNoSong.isVisible = !hasSong

                if (song != null) {
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
                    // Show YouTube player only while a YouTube song is active
                    binding.youtubePlayerView.isVisible = song.source == SongSource.YOUTUBE
                } else {
                    binding.youtubePlayerView.isVisible = false
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
                        "Gäste: Songs hinzufügen AN"
                    } else {
                        "Gäste: Songs hinzufügen AUS"
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
                        // Load into the in-app YouTubePlayerView (no app switch)
                        binding.youtubePlayerView.isVisible = true
                        val player = youTubePlayer
                        if (player != null) {
                            player.loadVideo(event.videoId, 0f)
                        } else {
                            // Player not ready yet – add a one-shot listener
                            binding.youtubePlayerView.addYouTubePlayerListener(
                                object : AbstractYouTubePlayerListener() {
                                    override fun onReady(youTubePlayer: YouTubePlayer) {
                                        youTubePlayer.loadVideo(event.videoId, 0f)
                                        binding.youtubePlayerView.removeYouTubePlayerListener(this)
                                    }
                                }
                            )
                        }
                    }

                    is HostEvent.PlayLocal -> {
                        requireContext().startService(
                            Intent(requireContext(), MusicService::class.java).apply {
                                action = MusicService.ACTION_PLAY_LOCAL
                                putExtra(MusicService.EXTRA_FILE_PATH, event.filePath)
                            }
                        )
                    }
                    HostEvent.PauseLocal -> {
                        requireContext().startService(
                            Intent(requireContext(), MusicService::class.java).apply {
                                action = MusicService.ACTION_PAUSE
                            }
                        )
                    }
                    HostEvent.ResumeLocal -> {
                        requireContext().startService(
                            Intent(requireContext(), MusicService::class.java).apply {
                                action = MusicService.ACTION_RESUME
                            }
                        )
                    }
                    HostEvent.PauseYouTube -> youTubePlayer?.pause()
                    HostEvent.ResumeYouTube -> youTubePlayer?.play()
                    HostEvent.StopYouTube -> {
                        youTubePlayer?.pause()
                        binding.youtubePlayerView.isVisible = false
                    }
                    HostEvent.StopLocal -> {
                        requireContext().startService(
                            Intent(requireContext(), MusicService::class.java).apply {
                                action = MusicService.ACTION_STOP
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        youTubePlayer = null
        spotifyWebPlayer.release()
        _binding = null
    }
}
