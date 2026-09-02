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
package com.helpofai.hoa.musicplayer.adapter.album

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.adapter.base.AbsMultiSelectAdapter
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.albumCoverOptions
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.asBitmapPalette
import com.helpofai.hoa.musicplayer.glide.HoaMusicColoredTarget
import com.helpofai.hoa.musicplayer.helper.SortOrder
import com.helpofai.hoa.musicplayer.helper.menu.SongsMenuHelper
import com.helpofai.hoa.musicplayer.interfaces.IAlbumClickListener
import com.helpofai.hoa.musicplayer.model.Album
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.util.color.MediaNotificationProcessor
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider

open class AlbumAdapter(
    override val activity: FragmentActivity,
    var dataSet: List<Album>,
    var itemLayoutRes: Int,
    val listener: IAlbumClickListener?
) : AbsMultiSelectAdapter<AlbumAdapter.ViewHolder, Album>(
    activity,
    R.menu.menu_media_selection
), PopupTextProvider {

    init {
        this.setHasStableIds(false)
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        return ALBUM_TYPE
    }

    private fun getRealPosition(position: Int): Int {
        if (!AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    fun swapDataSet(dataSet: List<Album>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_album_card_ad, parent, false)
            return AdViewHolder(view)
        }
        val view = LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
        return createViewHolder(view, viewType)
    }

    protected open fun createViewHolder(view: View, viewType: Int): ViewHolder {
        return ViewHolder(view)
    }

    private fun getAlbumTitle(album: Album): String {
        return album.title
    }

    protected open fun getAlbumText(album: Album): String? {
        return album.albumArtist.let {
            if (it.isNullOrEmpty()) {
                album.artistName
            } else {
                it
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        val album = dataSet[getRealPosition(position)]
        val isChecked = isChecked(album)
        holder.itemView.isActivated = isChecked
        holder.title?.text = getAlbumTitle(album)
        holder.text?.text = getAlbumText(album)
        // Check if imageContainer exists so we can have a smooth transition without
        // CardView clipping, if it doesn't exist in current layout set transition name to image instead.
        if (holder.imageContainer != null) {
            holder.imageContainer?.transitionName = album.id.toString()
        } else {
            holder.image?.transitionName = album.id.toString()
        }
        loadAlbumCover(album, holder)
    }

    protected open fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        if (holder.paletteColorContainer != null) {
            holder.title?.setTextColor(color.primaryTextColor)
            holder.text?.setTextColor(color.secondaryTextColor)
            holder.paletteColorContainer?.setBackgroundColor(color.backgroundColor)
        }
        holder.mask?.backgroundTintList = ColorStateList.valueOf(color.primaryTextColor)
        holder.imageContainerCard?.setCardBackgroundColor(color.backgroundColor)
    }

    protected open fun loadAlbumCover(album: Album, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        val song = album.safeGetFirstSong()
        Glide.with(activity)
            .asBitmapPalette()
            .albumCoverOptions(song)
            //.checkIgnoreMediaStore()
            .load(HoaGlideExtension.getSongModel(song))
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

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 2000)
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return -1
        return dataSet[realPos].id
    }

    override fun getIdentifier(position: Int): Album? {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return null
        return dataSet[realPos]
    }

    override fun getName(model: Album): String {
        return model.title
    }

    override fun onMultipleItemAction(
        menuItem: MenuItem,
        selection: List<Album>
    ) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection), menuItem.itemId)
    }

    private fun getSongList(albums: List<Album>): List<Song> {
        val songs = ArrayList<Song>()
        for (album in albums) {
            songs.addAll(album.songs)
        }
        return songs
    }

    override fun getPopupText(position: Int): String {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return ""
        return getSectionName(realPos)
    }

    private fun getSectionName(position: Int): String {
        var sectionName: String? = null
        when (PreferenceUtil.albumSortOrder) {
            SortOrder.AlbumSortOrder.ALBUM_A_Z, SortOrder.AlbumSortOrder.ALBUM_Z_A -> sectionName =
                dataSet[position].title

            SortOrder.AlbumSortOrder.ALBUM_ARTIST -> sectionName = dataSet[position].albumArtist
            SortOrder.AlbumSortOrder.ALBUM_YEAR -> return MusicUtil.getYearString(
                dataSet[position].year
            )
        }
        return MusicUtil.getSectionName(sectionName)
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
                    image?.let {
                        listener?.onAlbumClick(dataSet[realPos].id, imageContainer ?: it)
                    }
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            return toggleChecked(layoutPosition)
        }
    }

    companion object {
        val TAG: String = AlbumAdapter::class.java.simpleName
        private const val ALBUM_TYPE = 0
        private const val AD_TYPE = 1
        private const val AD_INTERVAL = 3 // Show ad every 3 albums
    }
}
