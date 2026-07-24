/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package com.helpofai.hoa.musicplayer.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.helpofai.hoa.appthemehelper.ThemeStore
import com.helpofai.hoa.musicplayer.*
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.db.PlaylistWithSongs
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.albumCoverOptions
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.artistImageOptions
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.songCoverOptions
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.helper.menu.SongMenuHelper
import com.helpofai.hoa.musicplayer.model.Album
import com.helpofai.hoa.musicplayer.model.Artist
import com.helpofai.hoa.musicplayer.model.Genre
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import java.util.*

class SearchAdapter(
    private val activity: FragmentActivity,
    private var dataSet: List<Any>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Any>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD
        }
        val realPos = getRealPosition(position)
        if (dataSet[realPos] is Album) return ALBUM
        if (dataSet[realPos] is Artist) return if ((dataSet[realPos] as Artist).isAlbumArtist) ALBUM_ARTIST else ARTIST
        if (dataSet[realPos] is Genre) return GENRE
        if (dataSet[realPos] is PlaylistWithSongs) return PLAYLIST
        return if (dataSet[realPos] is Song) SONG else HEADER
    }

    private fun getRealPosition(position: Int): Int {
        if (!AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            AD -> AdViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.item_list_ad,
                    parent,
                    false
                )
            )

            HEADER -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.sub_header,
                    parent,
                    false
                ), viewType
            )

            ALBUM, ARTIST, ALBUM_ARTIST -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.item_list_big,
                    parent,
                    false
                ), viewType
            )

            else -> ViewHolder(
                LayoutInflater.from(activity).inflate(R.layout.item_list, parent, false),
                viewType
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (getItemViewType(position) == AD) {
            (holder as AdViewHolder).loadAd()
            return
        }
        val realPos = getRealPosition(position)
        val searchHolder = holder as ViewHolder
        when (getItemViewType(position)) {
            ALBUM -> {
                searchHolder.imageTextContainer?.isVisible = true
                val album = dataSet[realPos] as Album
                searchHolder.title?.text = album.title
                searchHolder.text?.text = album.artistName
                Glide.with(activity).asDrawable().albumCoverOptions(album.safeGetFirstSong())
                    .load(HoaGlideExtension.getSongModel(album.safeGetFirstSong()))
                    .into(searchHolder.image!!)
            }

            ARTIST -> {
                searchHolder.imageTextContainer?.isVisible = true
                val artist = dataSet[realPos] as Artist
                searchHolder.title?.text = artist.name
                searchHolder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    HoaGlideExtension.getArtistModel(artist)
                ).into(searchHolder.image!!)
            }

            SONG -> {
                searchHolder.imageTextContainer?.isVisible = true
                val song = dataSet[realPos] as Song
                searchHolder.title?.text = song.title
                searchHolder.text?.text = song.albumName
                Glide.with(activity).asDrawable().songCoverOptions(song)
                    .load(HoaGlideExtension.getSongModel(song)).into(searchHolder.image!!)
            }

            GENRE -> {
                val genre = dataSet[realPos] as Genre
                searchHolder.title?.text = genre.name
                searchHolder.text?.text = String.format(
                    Locale.getDefault(),
                    "%d %s",
                    genre.songCount,
                    if (genre.songCount > 1) activity.getString(R.string.songs) else activity.getString(
                        R.string.song
                    )
                )
            }

            PLAYLIST -> {
                val playlist = dataSet[realPos] as PlaylistWithSongs
                searchHolder.title?.text = playlist.playlistEntity.playlistName
                //holder.text?.text = MusicUtil.playlistInfoString(activity, playlist.songs)
            }

            ALBUM_ARTIST -> {
                searchHolder.imageTextContainer?.isVisible = true
                val artist = dataSet[realPos] as Artist
                searchHolder.title?.text = artist.name
                searchHolder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    HoaGlideExtension.getArtistModel(artist)
                ).into(searchHolder.image!!)
            }

            else -> {
                searchHolder.title?.text = dataSet[realPos].toString()
                searchHolder.title?.setTextColor(ThemeStore.accentColor(activity))
            }
        }
    }

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    inner class AdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val adContainer: ViewGroup? = itemView.findViewById(R.id.ad_container)
        fun loadAd() {
            adContainer?.let { AdsManager.loadBannerAd(it) }
        }
    }

    inner class ViewHolder(itemView: View, val viewType: Int) : MediaEntryViewHolder(itemView) {
        init {
            itemView.setOnLongClickListener(null)
            imageTextContainer?.isInvisible = true
            if (viewType == SONG) {
                imageTextContainer?.isGone = true
                menu?.isVisible = true
                menu?.setOnClickListener(object : SongMenuHelper.OnClickSongMenu(activity) {
                    override val song: Song
                        get() = dataSet[getRealPosition(layoutPosition)] as Song
                })
            } else {
                menu?.isVisible = false
            }

            when (viewType) {
                ALBUM -> setImageTransitionName(activity.getString(R.string.transition_album_art))
                ARTIST -> setImageTransitionName(activity.getString(R.string.transition_artist_image))
                else -> {
                    val container = itemView.findViewById<View>(R.id.imageContainer)
                    container?.isVisible = false
                }
            }
        }

        override fun onClick(v: View?) {
            val realPos = getRealPosition(layoutPosition)
            if (realPos < 0 || realPos >= dataSet.size) return
            val item = dataSet[realPos]
            when (viewType) {
                ALBUM -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumDetailsFragment,
                        bundleOf(EXTRA_ALBUM_ID to (item as Album).id)
                    )
                }

                ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.artistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_ID to (item as Artist).id)
                    )
                }

                ALBUM_ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumArtistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_NAME to (item as Artist).name)
                    )
                }

                GENRE -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.genreDetailsFragment,
                        bundleOf(EXTRA_GENRE to (item as Genre))
                    )
                }

                PLAYLIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.playlistDetailsFragment,
                        bundleOf(EXTRA_PLAYLIST_ID to (item as PlaylistWithSongs).playlistEntity.playListId)
                    )
                }

                SONG -> {
                    MusicPlayerRemote.playNext(item as Song)
                    MusicPlayerRemote.playNextSong()
                }
            }
        }
    }

    companion object {
        private const val HEADER = 0
        private const val ALBUM = 1
        private const val ARTIST = 2
        private const val SONG = 3
        private const val GENRE = 4
        private const val PLAYLIST = 5
        private const val ALBUM_ARTIST = 6
        private const val AD = 7
        private const val AD_INTERVAL = 4
    }
}