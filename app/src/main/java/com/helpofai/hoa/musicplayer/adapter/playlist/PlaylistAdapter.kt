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
package com.helpofai.hoa.musicplayer.adapter.playlist

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentActivity
import com.helpofai.hoa.appthemehelper.util.ATHUtil
import com.helpofai.hoa.appthemehelper.util.TintHelper
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.adapter.base.AbsMultiSelectAdapter
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.db.PlaylistEntity
import com.helpofai.hoa.musicplayer.db.PlaylistWithSongs
import com.helpofai.hoa.musicplayer.db.toSongs
import com.helpofai.hoa.musicplayer.extensions.dipToPix
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.playlistOptions
import com.helpofai.hoa.musicplayer.glide.playlistPreview.PlaylistPreview
import com.helpofai.hoa.musicplayer.helper.SortOrder.PlaylistSortOrder
import com.helpofai.hoa.musicplayer.helper.menu.PlaylistMenuHelper
import com.helpofai.hoa.musicplayer.helper.menu.SongsMenuHelper
import com.helpofai.hoa.musicplayer.interfaces.IPlaylistClickListener
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider

class PlaylistAdapter(
    override val activity: FragmentActivity,
    var dataSet: List<PlaylistWithSongs>,
    private var itemLayoutRes: Int,
    private val listener: IPlaylistClickListener
) : AbsMultiSelectAdapter<PlaylistAdapter.ViewHolder, PlaylistWithSongs>(
    activity,
    R.menu.menu_playlists_selection
), PopupTextProvider {

    companion object {
        private const val PLAYLIST_TYPE = 0
        private const val AD_TYPE = 1
        private const val AD_INTERVAL = 4
    }

    init {
        setHasStableIds(false)
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        return PLAYLIST_TYPE
    }

    private fun getRealPosition(position: Int): Int {
        if (!AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    fun swapDataSet(dataSet: List<PlaylistWithSongs>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 1000)
        return dataSet[getRealPosition(position)].playlistEntity.playListId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_list_ad, parent, false)
            return AdViewHolder(view)
        }
        val view = LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
        return createViewHolder(view)
    }

    private fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    private fun getPlaylistTitle(playlist: PlaylistEntity): String {
        return playlist.playlistName.ifEmpty { "-" }
    }

    private fun getPlaylistText(playlist: PlaylistWithSongs): String {
        return MusicUtil.getPlaylistInfoString(activity, playlist.songs.toSongs())
    }

    override fun getPopupText(position: Int): String {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return ""
        val sectionName: String = when (PreferenceUtil.playlistSortOrder) {
            PlaylistSortOrder.PLAYLIST_A_Z, PlaylistSortOrder.PLAYLIST_Z_A -> dataSet[realPos].playlistEntity.playlistName
            PlaylistSortOrder.PLAYLIST_SONG_COUNT, PlaylistSortOrder.PLAYLIST_SONG_COUNT_DESC -> dataSet[realPos].songs.size.toString()
            else -> {
                return ""
            }
        }
        return MusicUtil.getSectionName(sectionName)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (getItemViewType(position) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        val playlist = dataSet[getRealPosition(position)]
        holder.itemView.isActivated = isChecked(playlist)
        holder.title?.text = getPlaylistTitle(playlist.playlistEntity)
        holder.text?.text = getPlaylistText(playlist)
        holder.menu?.isGone = isChecked(playlist)
        if (itemLayoutRes == R.layout.item_list) {
            holder.image?.setPadding(activity.dipToPix(8F).toInt())
            holder.image?.setImageDrawable(getIconRes())
        } else {
            Glide.with(activity)
                .load(PlaylistPreview(playlist))
                .playlistOptions()
                .into(holder.image!!)
        }
    }

    private fun getIconRes(): Drawable = TintHelper.createTintedDrawable(
        activity,
        R.drawable.ic_playlist_play,
        ATHUtil.resolveColor(activity, android.R.attr.colorControlNormal)
    )

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    override fun getIdentifier(position: Int): PlaylistWithSongs? {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return null
        return dataSet[realPos]
    }

    override fun getName(model: PlaylistWithSongs): String {
        return model.playlistEntity.playlistName
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<PlaylistWithSongs>) {
        when (menuItem.itemId) {
            else -> SongsMenuHelper.handleMenuClick(
                activity,
                getSongList(selection),
                menuItem.itemId
            )
        }
    }

    private fun getSongList(playlists: List<PlaylistWithSongs>): List<Song> {
        val songs = mutableListOf<Song>()
        playlists.forEach {
            songs.addAll(it.songs.toSongs())
        }
        return songs
    }

    inner class AdViewHolder(itemView: View) : ViewHolder(itemView) {
        private val adContainer: ViewGroup? = itemView.findViewById(R.id.ad_container)

        fun loadAd() {
            adContainer?.let { AdsManager.loadBannerAd(it) }
        }

        override fun onClick(v: View?) {}
        override fun onLongClick(v: View?): Boolean = false
    }

    open inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {
        init {
            menu?.setOnClickListener { view ->
                val popupMenu = PopupMenu(activity, view)
                popupMenu.inflate(R.menu.menu_item_playlist)
                popupMenu.setOnMenuItemClickListener { item ->
                    PlaylistMenuHelper.handleMenuClick(activity, dataSet[getRealPosition(layoutPosition)], item)
                }
                popupMenu.show()
            }

            imageTextContainer?.apply {
                cardElevation = 0f
                setCardBackgroundColor(Color.TRANSPARENT)
            }
        }

        override fun onClick(v: View?) {
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                itemView.transitionName = "playlist"
                listener.onPlaylistClick(dataSet[getRealPosition(layoutPosition)], itemView)
            }
        }

        override fun onLongClick(v: View?): Boolean {
            toggleChecked(layoutPosition)
            return true
        }
    }
}
