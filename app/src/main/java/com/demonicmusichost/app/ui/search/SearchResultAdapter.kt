package com.demonicmusichost.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.SearchResult
import com.demonicmusichost.app.databinding.ItemSearchResultBinding
import com.demonicmusichost.app.util.toTimeString

class SearchResultAdapter(
    private val onAddClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultAdapter.SearchViewHolder>(SearchDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SearchViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: SearchResult) {
            when (result) {
                is SearchResult.SpotifyTrack -> {
                    binding.tvTitle.text = result.name
                    binding.tvArtist.text = result.artists.joinToString(", ")
                    binding.tvDuration.text = result.durationMs.toTimeString()
                    binding.tvAlbum.text = result.albumName
                    binding.chipSource.text = "Spotify"
                    binding.chipSource.setChipBackgroundColorResource(R.color.spotify_green)
                    loadImage(result.albumImageUrl)
                }
                is SearchResult.YouTubeVideo -> {
                    binding.tvTitle.text = result.title
                    binding.tvArtist.text = result.channelTitle
                    binding.tvDuration.text = result.durationMs.toTimeString()
                    binding.tvAlbum.text = ""
                    binding.chipSource.text = "YouTube"
                    binding.chipSource.setChipBackgroundColorResource(R.color.youtube_red)
                    loadImage(result.thumbnailUrl)
                }
                is SearchResult.LocalFile -> {
                    binding.tvTitle.text = result.title
                    binding.tvArtist.text = result.artist
                    binding.tvDuration.text = result.durationMs.toTimeString()
                    binding.tvAlbum.text = result.album
                    binding.chipSource.text = "Lokal"
                    binding.chipSource.setChipBackgroundColorResource(R.color.discord_blurple)
                    if (result.albumArtUri != null) {
                        loadImage(result.albumArtUri)
                    } else {
                        binding.ivThumbnail.setImageResource(R.drawable.ic_music_note)
                    }
                }
            }

            binding.btnAdd.setOnClickListener { onAddClick(result) }
        }

        private fun loadImage(url: String) {
            Glide.with(binding.root)
                .load(url)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(binding.ivThumbnail)
        }
    }

    class SearchDiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            when {
                oldItem is SearchResult.SpotifyTrack && newItem is SearchResult.SpotifyTrack ->
                    oldItem.id == newItem.id
                oldItem is SearchResult.YouTubeVideo && newItem is SearchResult.YouTubeVideo ->
                    oldItem.videoId == newItem.videoId
                oldItem is SearchResult.LocalFile && newItem is SearchResult.LocalFile ->
                    oldItem.id == newItem.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem == newItem
    }
}
