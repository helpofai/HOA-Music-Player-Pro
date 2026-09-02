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
package com.helpofai.hoa.musicplayer.adapter.artist

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.adapter.base.AbsMultiSelectAdapter
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.extensions.hide
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.artistImageOptions
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.asBitmapPalette
import com.helpofai.hoa.musicplayer.glide.HoaMusicColoredTarget
import com.helpofai.hoa.musicplayer.helper.menu.SongsMenuHelper
import com.helpofai.hoa.musicplayer.interfaces.IAlbumArtistClickListener
import com.helpofai.hoa.musicplayer.interfaces.IArtistClickListener
import com.helpofai.hoa.musicplayer.model.Artist
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.util.color.MediaNotificationProcessor
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider

class ArtistAdapter(
    override val activity: FragmentActivity,
    var dataSet: List<Artist>,
    var itemLayoutRes: Int,
    val IArtistClickListener: IArtistClickListener,
    val IAlbumArtistClickListener: IAlbumArtistClickListener? = null
) : AbsMultiSelectAdapter<ArtistAdapter.ViewHolder, Artist>(activity, R.menu.menu_media_selection),
    PopupTextProvider {

    var albumArtistsOnly = false

    companion object {
        private const val ARTIST_TYPE = 0
        private const val AD_TYPE = 1
        private const val AD_INTERVAL = 3
    }

    init {
        this.setHasStableIds(false)
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        return ARTIST_TYPE
    }

    private fun getRealPosition(position: Int): Int {
        if (!AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Artist>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
        albumArtistsOnly = PreferenceUtil.albumArtistsOnly
    }

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 3000)
        return dataSet[getRealPosition(position)].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_list_ad, parent, false)
            return AdViewHolder(view)
        }
        val view =
            try {
                LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
            } catch (e: Resources.NotFoundException) {
                LayoutInflater.from(activity).inflate(R.layout.item_grid_circle, parent, false)
            }
        return createViewHolder(view)
    }

    private fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        val artist = dataSet[getRealPosition(position)]
        val isChecked = isChecked(artist)
        holder.itemView.isActivated = isChecked
        holder.title?.text = artist.name
        holder.text?.hide()
        val transitionName =
            if (albumArtistsOnly) artist.name else artist.id.toString()
        if (holder.imageContainer != null) {
            holder.imageContainer?.transitionName = transitionName
        } else {
            holder.image?.transitionName = transitionName
        }
        loadArtistImage(artist, holder)
    }

    private fun setColors(processor: MediaNotificationProcessor, holder: ViewHolder) {
        holder.mask?.backgroundTintList = ColorStateList.valueOf(processor.primaryTextColor)
        if (holder.paletteColorContainer != null) {
            holder.paletteColorContainer?.setBackgroundColor(processor.backgroundColor)
            holder.title?.setTextColor(processor.primaryTextColor)
        }
        holder.imageContainerCard?.setCardBackgroundColor(processor.backgroundColor)
    }

    private fun loadArtistImage(artist: Artist, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .asBitmapPalette()
            .artistImageOptions(artist)
            .load(HoaGlideExtension.getArtistModel(artist))
            .transition(HoaGlideExtension.getDefaultTransition())
            .into(object : HoaMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    override fun getIdentifier(position: Int): Artist? {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return null
        return dataSet[realPos]
    }

    override fun getName(model: Artist): String {
        return model.name
    }

    override fun onMultipleItemAction(
        menuItem: MenuItem,
        selection: List<Artist>
    ) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection), menuItem.itemId)
    }

    private fun getSongList(artists: List<Artist>): List<Song> {
        val songs = ArrayList<Song>()
        for (artist in artists) {
            songs.addAll(artist.songs) // maybe async in future?
        }
        return songs
    }

    override fun getPopupText(position: Int): String {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return ""
        return getSectionName(realPos)
    }

    private fun getSectionName(position: Int): String {
        return MusicUtil.getSectionName(dataSet[position].name)
    }

    inner class AdViewHolder(itemView: View) : ViewHolder(itemView) {
        private val adContainer: ViewGroup? = itemView.findViewById(R.id.ad_container)

        fun loadAd() {
            adContainer?.let { AdsManager.loadBannerAd(it, adaptive = false) }
        }

        override fun onClick(v: View?) {}
        override fun onLongClick(v: View?): Boolean = false
    }

    open inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

        init {
            menu?.isVisible = false
        }

        override fun onClick(v: View?) {
            super.onClick(v)
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                val realPos = getRealPosition(layoutPosition)
                if (realPos >= 0 && realPos < dataSet.size) {
                    val artist = dataSet[realPos]
                    image?.let {
                        if (albumArtistsOnly && IAlbumArtistClickListener != null) {
                            IAlbumArtistClickListener.onAlbumArtist(artist.name, imageContainer ?: it)
                        } else {
                            IArtistClickListener.onArtist(artist.id, imageContainer ?: it)
                        }
                    }
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            return toggleChecked(layoutPosition)
        }
    }
}
