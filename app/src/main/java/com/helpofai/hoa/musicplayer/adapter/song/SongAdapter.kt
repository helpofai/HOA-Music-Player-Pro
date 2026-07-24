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
package com.helpofai.hoa.musicplayer.adapter.song

import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.helpofai.hoa.musicplayer.EXTRA_ALBUM_ID
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.adapter.base.AbsMultiSelectAdapter
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.asBitmapPalette
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.songCoverOptions
import com.helpofai.hoa.musicplayer.glide.HoaMusicColoredTarget
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.helper.SortOrder
import com.helpofai.hoa.musicplayer.helper.menu.SongMenuHelper
import com.helpofai.hoa.musicplayer.helper.menu.SongsMenuHelper
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.util.HoaUtil
import com.helpofai.hoa.musicplayer.util.color.MediaNotificationProcessor
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider
import java.util.*

/**
 * Created by rajib on 13/08/17.
 */

open class SongAdapter(
    override val activity: FragmentActivity,
    var dataSet: MutableList<Song>,
    protected var itemLayoutRes: Int,
    showSectionName: Boolean = true,
    protected val showAds: Boolean = true
) : AbsMultiSelectAdapter<RecyclerView.ViewHolder, Song>(
    activity,
    R.menu.menu_media_selection
), PopupTextProvider {

    private var showSectionName = true

    init {
        this.showSectionName = showSectionName
        this.setHasStableIds(true)
    }

    companion object {
        val TAG: String = SongAdapter::class.java.simpleName
        const val SONG_TYPE = 0
        const val AD_TYPE = 1
        const val AD_INTERVAL = 5 // Show ad every 5 songs
    }

    override fun getItemViewType(position: Int): Int {
        if (showAds && AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        return SONG_TYPE
    }

    protected open fun getRealPosition(position: Int): Int {
        if (!showAds || !AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    open fun swapDataSet(dataSet: List<Song>) {
        this.dataSet = ArrayList(dataSet)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 1000)
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return -1
        return dataSet[realPos].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_list_ad, parent, false)
            return AdViewHolder(view)
        }
        val view =
            try {
                LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
            } catch (e: Resources.NotFoundException) {
                LayoutInflater.from(activity).inflate(R.layout.item_list, parent, false)
            }
        return createViewHolder(view)
    }

    protected open fun createViewHolder(view: View): RecyclerView.ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (getItemViewType(position) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        val songHolder = holder as? ViewHolder ?: return
        val song = dataSet[getRealPosition(position)]
        val isChecked = isChecked(song)
        songHolder.itemView.isActivated = isChecked
        songHolder.menu?.isGone = isChecked
        songHolder.title?.text = getSongTitle(song)
        songHolder.text?.text = getSongText(song)
        songHolder.text2?.text = getSongText(song)
        loadAlbumCover(song, songHolder)
        val landscape = HoaUtil.isLandscape
        if ((PreferenceUtil.songGridSize > 2 && !landscape) || (PreferenceUtil.songGridSizeLand > 5 && landscape)) {
            songHolder.menu?.isVisible = false
        }
    }

    private fun setColors(color: MediaNotificationProcessor, holder: ViewHolder) {
        if (holder.paletteColorContainer != null) {
            holder.title?.setTextColor(color.primaryTextColor)
            holder.text?.setTextColor(color.secondaryTextColor)
            holder.paletteColorContainer?.setBackgroundColor(color.backgroundColor)
            holder.menu?.imageTintList = ColorStateList.valueOf(color.primaryTextColor)
        }
        holder.mask?.backgroundTintList = ColorStateList.valueOf(color.primaryTextColor)
    }

    protected open fun loadAlbumCover(song: Song, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .asBitmapPalette()
            .songCoverOptions(song)
            .load(HoaGlideExtension.getSongModel(song))
            .into(object : HoaMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    private fun getSongTitle(song: Song): String {
        return song.title
    }

    private fun getSongText(song: Song): String {
        return song.artistName
    }

    private fun getSongText2(song: Song): String {
        return song.albumName
    }

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (showAds && AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    override fun getIdentifier(position: Int): Song? {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return null
        return dataSet[realPos]
    }

    override fun getName(model: Song): String {
        return model.title
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        SongsMenuHelper.handleMenuClick(activity, selection, menuItem.itemId)
    }

    override fun getPopupText(position: Int): String {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return ""
        
        val sectionName: String? = when (PreferenceUtil.songSortOrder) {
            SortOrder.SongSortOrder.SONG_DEFAULT -> return MusicUtil.getSectionName(
                dataSet[realPos].title,
                true
            )

            SortOrder.SongSortOrder.SONG_A_Z, SortOrder.SongSortOrder.SONG_Z_A -> dataSet[realPos].title
            SortOrder.SongSortOrder.SONG_ALBUM -> dataSet[realPos].albumName
            SortOrder.SongSortOrder.SONG_ARTIST -> dataSet[realPos].artistName
            SortOrder.SongSortOrder.SONG_YEAR -> return MusicUtil.getYearString(dataSet[realPos].year)
            SortOrder.SongSortOrder.COMPOSER -> dataSet[realPos].composer
            SortOrder.SongSortOrder.SONG_ALBUM_ARTIST -> dataSet[realPos].albumArtist
            else -> {
                return ""
            }
        }
        return MusicUtil.getSectionName(sectionName)
    }

    open inner class AdViewHolder(itemView: View) : ViewHolder(itemView) {
        private val adContainer: ViewGroup? = itemView.findViewById(R.id.ad_container)
        override val song: Song
            get() = Song.emptySong

        fun loadAd() {
            adContainer?.let { AdsManager.loadBannerAd(it) }
        }

        override fun onClick(v: View?) {}
        override fun onLongClick(v: View?): Boolean = false
    }

    open inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {
        protected open var songMenuRes = SongMenuHelper.MENU_RES
        protected open val song: Song
            get() = dataSet[getRealPosition(layoutPosition)]

        init {
            menu?.setOnClickListener(object : SongMenuHelper.OnClickSongMenu(activity) {
                override val song: Song
                    get() = this@ViewHolder.song

                override val menuRes: Int
                    get() = songMenuRes

                override fun onMenuItemClick(item: MenuItem): Boolean {
                    return onSongMenuItemClick(item) || super.onMenuItemClick(item)
                }
            })
        }

        protected open fun onSongMenuItemClick(item: MenuItem): Boolean {
            if (image != null && image!!.isVisible) {
                when (item.itemId) {
                    R.id.action_go_to_album -> {
                        activity.findNavController(R.id.fragment_container)
                            .navigate(
                                R.id.albumDetailsFragment,
                                bundleOf(EXTRA_ALBUM_ID to song.albumId)
                            )
                        return true
                    }
                }
            }
            return false
        }

        override fun onClick(v: View?) {
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                MusicPlayerRemote.openQueueKeepShuffleMode(dataSet, getRealPosition(layoutPosition), true)
            }
        }

        override fun onLongClick(v: View?): Boolean {
            return toggleChecked(layoutPosition)
        }
    }
}