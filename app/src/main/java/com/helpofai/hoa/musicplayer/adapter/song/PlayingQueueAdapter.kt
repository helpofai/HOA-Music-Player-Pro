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

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension.songCoverOptions
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote.isPlaying
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote.playNextSong
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote.removeFromQueue
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.ViewUtil
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.annotation.DraggableItemStateFlags
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.swipeable.SwipeableItemConstants
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultAction
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionDefault
import com.h6ah4i.android.widget.advrecyclerview.swipeable.action.SwipeResultActionRemoveItem
import me.zhanghai.android.fastscroll.PopupTextProvider

class PlayingQueueAdapter(
    activity: FragmentActivity,
    dataSet: MutableList<Song>,
    private var current: Int,
    itemLayoutRes: Int,
) : SongAdapter(activity, dataSet, itemLayoutRes, showAds = true),
    DraggableItemAdapter<PlayingQueueAdapter.ViewHolder>,
    SwipeableItemAdapter<PlayingQueueAdapter.ViewHolder>,
    PopupTextProvider {

    private var songToRemove: Song? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_list_ad, parent, false)
            return AdViewHolder(view)
        }
        val view = LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (getItemViewType(position) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        super.onBindViewHolder(holder, position)
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return
        val song = dataSet[realPos]
        val songHolder = holder as? ViewHolder ?: return
        songHolder.time?.text = MusicUtil.getReadableDurationString(song.duration)
        if (getItemViewType(position) == HISTORY || getItemViewType(position) == CURRENT) {
            setAlpha(songHolder, 0.5f)
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        val realPos = getRealPosition(position)
        if (realPos < current) {
            return HISTORY
        } else if (realPos > current) {
            return UP_NEXT
        }
        return CURRENT
    }

    override fun loadAlbumCover(song: Song, holder: SongAdapter.ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .load(HoaGlideExtension.getSongModel(song))
            .songCoverOptions(song)
            .into(holder.image!!)
    }

    fun swapDataSet(dataSet: List<Song>, position: Int) {
        this.dataSet = dataSet.toMutableList()
        current = position
        notifyDataSetChanged()
    }

    fun setCurrent(current: Int) {
        this.current = current
        notifyDataSetChanged()
    }

    private fun setAlpha(holder: SongAdapter.ViewHolder, alpha: Float) {
        holder.image?.alpha = alpha
        holder.title?.alpha = alpha
        holder.text?.alpha = alpha
        holder.paletteColorContainer?.alpha = alpha
        holder.dragView?.alpha = alpha
        holder.menu?.alpha = alpha
    }

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 1000)
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return -1
        return dataSet[realPos].id
    }

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    override fun getPopupText(position: Int): String {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return ""
        return MusicUtil.getSectionName(dataSet[realPos].title)
    }

    override fun onCheckCanStartDrag(holder: ViewHolder, position: Int, x: Int, y: Int): Boolean {
        if (getItemViewType(position) == AD_TYPE) return false
        val imageText = holder.imageText
        val dragView = holder.dragView
        return (imageText != null && ViewUtil.hitTest(imageText, x, y)) || 
               (dragView != null && ViewUtil.hitTest(dragView, x, y))
    }

    override fun onGetItemDraggableRange(holder: ViewHolder, position: Int): ItemDraggableRange? {
        return null
    }

    override fun onMoveItem(fromPosition: Int, toPosition: Int) {
        MusicPlayerRemote.moveSong(getRealPosition(fromPosition), getRealPosition(toPosition))
    }

    override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean {
        return getItemViewType(dropPosition) != AD_TYPE
    }

    override fun onItemDragStarted(position: Int) {
        notifyDataSetChanged()
    }

    override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
        notifyDataSetChanged()
    }

    fun setSongToRemove(song: Song) {
        songToRemove = song
    }

    inner class AdViewHolder(itemView: View) : ViewHolder(itemView) {
        private val adContainer: ViewGroup? = itemView.findViewById(R.id.ad_container)

        fun loadAd() {
            adContainer?.let { AdsManager.loadBannerAd(it) }
        }

        override fun onClick(v: View?) {}
        override fun onLongClick(v: View?): Boolean = false
    }

    open inner class ViewHolder(itemView: View) : SongAdapter.ViewHolder(itemView) {
        @DraggableItemStateFlags
        private var mDragStateFlags: Int = 0

        override var songMenuRes: Int
            get() = R.menu.menu_item_playing_queue_song
            set(value) {
                super.songMenuRes = value
            }

        init {
            dragView?.isVisible = true
        }

        override fun onClick(v: View?) {
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                val realPos = getRealPosition(layoutPosition)
                if (realPos >= 0 && realPos < dataSet.size) {
                    MusicPlayerRemote.playSongAt(realPos)
                }
            }
        }

        override fun onSongMenuItemClick(item: MenuItem): Boolean {
            val realPos = getRealPosition(layoutPosition)
            if (realPos >= 0 && realPos < dataSet.size) {
                when (item.itemId) {
                    R.id.action_remove_from_playing_queue -> {
                        removeFromQueue(realPos)
                        return true
                    }
                }
            }
            return super.onSongMenuItemClick(item)
        }

        @DraggableItemStateFlags
        override fun getDragStateFlags(): Int {
            return mDragStateFlags
        }

        override fun setDragStateFlags(@DraggableItemStateFlags flags: Int) {
            mDragStateFlags = flags
        }

        override fun getSwipeableContainerView(): View {
            return dummyContainer!!
        }
    }

    companion object {
        private const val HISTORY = 10
        private const val CURRENT = 11
        private const val UP_NEXT = 12
    }

    override fun onSwipeItem(holder: ViewHolder, position: Int, result: Int): SwipeResultAction {
        return if (result == SwipeableItemConstants.RESULT_CANCELED) {
            SwipeResultActionDefault()
        } else {
            SwipedResultActionRemoveItem(this, position)
        }
    }

    override fun onGetSwipeReactionType(holder: ViewHolder, position: Int, x: Int, y: Int): Int {
        if (getItemViewType(position) == AD_TYPE) return SwipeableItemConstants.REACTION_CAN_NOT_SWIPE_BOTH_H
        return if (onCheckCanStartDrag(holder, position, x, y)) {
            SwipeableItemConstants.REACTION_CAN_NOT_SWIPE_BOTH_H
        } else {
            SwipeableItemConstants.REACTION_CAN_SWIPE_BOTH_H
        }
    }

    override fun onSwipeItemStarted(holder: ViewHolder, position: Int) {
    }

    override fun onSetSwipeBackground(holder: ViewHolder, position: Int, result: Int) {
    }

    internal class SwipedResultActionRemoveItem(
        private val adapter: PlayingQueueAdapter,
        private val position: Int,
    ) : SwipeResultActionRemoveItem() {

        private var songToRemove: Song? = null
        override fun onPerformAction() {
            // currentlyShownSnackbar = null
        }

        override fun onSlideAnimationEnd() {
            // initializeSnackBar(adapter, position, activity, isPlaying)
            val realPos = adapter.getRealPosition(position)
            if (realPos >= 0 && realPos < adapter.dataSet.size) {
                songToRemove = adapter.dataSet[realPos]
                // If song removed was the playing song, then play the next song
                if (isPlaying(songToRemove!!)) {
                    playNextSong()
                }
                // Swipe animation is much smoother when we do the heavy lifting after it's completed
                adapter.setSongToRemove(songToRemove!!)
                removeFromQueue(songToRemove!!)
            }
        }
    }
}