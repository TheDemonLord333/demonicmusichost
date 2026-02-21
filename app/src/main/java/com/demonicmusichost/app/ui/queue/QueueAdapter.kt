package com.demonicmusichost.app.ui.queue

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.Song
import com.demonicmusichost.app.data.model.SongSource
import com.demonicmusichost.app.databinding.ItemQueueSongBinding
import com.demonicmusichost.app.util.toTimeString
import java.util.Collections

class QueueAdapter(
    private val isHost: Boolean,
    private val onRemoveClick: ((Song) -> Unit)? = null
) : ListAdapter<Song, QueueAdapter.QueueViewHolder>(SongDiffCallback()) {

    private val items = mutableListOf<Song>()

    override fun submitList(list: List<Song>?) {
        items.clear()
        list?.let { items.addAll(it) }
        super.submitList(list)
    }

    fun moveItem(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    fun getItems(): List<Song> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1)
    }

    inner class QueueViewHolder(private val binding: ItemQueueSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            binding.tvPosition.text = position.toString()
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = song.durationMs.toTimeString()
            binding.tvAddedBy.text = "von ${song.addedByUserName}"

            binding.chipSource.text = when (song.source) {
                SongSource.SPOTIFY -> "Spotify"
                SongSource.YOUTUBE -> "YouTube"
                SongSource.LOCAL -> "Lokal"
            }
            binding.chipSource.chipBackgroundColor = binding.root.context.let { ctx ->
                android.content.res.ColorStateList.valueOf(
                    when (song.source) {
                        SongSource.SPOTIFY -> ctx.getColor(R.color.spotify_green)
                        SongSource.YOUTUBE -> ctx.getColor(R.color.youtube_red)
                        SongSource.LOCAL -> ctx.getColor(R.color.discord_blurple)
                    }
                )
            }

            if (song.thumbnailUrl.isNotBlank()) {
                Glide.with(binding.root)
                    .load(song.thumbnailUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .into(binding.ivThumbnail)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_music_note)
            }

            binding.btnRemove.isVisible = isHost
            binding.ivDragHandle.isVisible = isHost
            binding.btnRemove.setOnClickListener {
                onRemoveClick?.invoke(song)
            }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean = oldItem == newItem
    }
}
