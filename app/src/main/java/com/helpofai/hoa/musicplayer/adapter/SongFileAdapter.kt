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

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.helpofai.hoa.appthemehelper.util.ATHUtil
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.adapter.base.AbsMultiSelectAdapter
import com.helpofai.hoa.musicplayer.adapter.base.MediaEntryViewHolder
import com.helpofai.hoa.musicplayer.extensions.getTintedDrawable
import com.helpofai.hoa.musicplayer.glide.HoaGlideExtension
import com.helpofai.hoa.musicplayer.glide.audiocover.AudioFileCover
import com.helpofai.hoa.musicplayer.interfaces.ICallbacks
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.ads.AdsManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.MediaStoreSignature
import me.zhanghai.android.fastscroll.PopupTextProvider
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class SongFileAdapter(
    override val activity: AppCompatActivity,
    private var dataSet: List<File>,
    private val itemLayoutRes: Int,
    private val iCallbacks: ICallbacks?
) : AbsMultiSelectAdapter<SongFileAdapter.ViewHolder, File>(
    activity, R.menu.menu_media_selection
), PopupTextProvider {

    init {
        this.setHasStableIds(false)
    }

    override fun getItemViewType(position: Int): Int {
        if (AdsManager.shouldShowAds() && position > 0 && (position + 1) % (AD_INTERVAL + 1) == 0) {
            return AD_TYPE
        }
        return if (dataSet[getRealPosition(position)].isDirectory) FOLDER else FILE
    }

    private fun getRealPosition(position: Int): Int {
        if (!AdsManager.shouldShowAds()) return position
        return position - (position / (AD_INTERVAL + 1))
    }

    override fun getItemId(position: Int): Long {
        if (getItemViewType(position) == AD_TYPE) return -(position.toLong() + 6000)
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return -1
        return dataSet[realPos].hashCode().toLong()
    }

    fun swapDataSet(songFiles: List<File>) {
        this.dataSet = songFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == AD_TYPE) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_list_ad, parent, false)
            return AdViewHolder(view)
        }
        return ViewHolder(LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, index: Int) {
        if (getItemViewType(index) == AD_TYPE) {
            (holder as? AdViewHolder)?.loadAd()
            return
        }
        val file = dataSet[getRealPosition(index)]
        holder.itemView.isActivated = isChecked(file)
        holder.title?.text = getFileTitle(file)
        if (holder.text != null) {
            if (holder.itemViewType == FILE) {
                holder.text?.text = getFileText(file)
            } else {
                holder.text?.isVisible = false
            }
        }

        if (holder.image != null) {
            loadFileImage(file, holder)
        }
    }

    private fun getFileTitle(file: File): String {
        return file.name
    }

    private fun getFileText(file: File): String? {
        return if (file.isDirectory) null else readableFileSize(file.length())
    }

    private fun loadFileImage(file: File, holder: ViewHolder) {
        val iconColor = ATHUtil.resolveColor(activity, androidx.appcompat.R.attr.colorControlNormal)
        if (file.isDirectory) {
            holder.image?.let {
                it.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
                it.setImageResource(R.drawable.ic_folder)
            }
            holder.imageTextContainer?.setCardBackgroundColor(
                ATHUtil.resolveColor(
                    activity,
                    com.google.android.material.R.attr.colorSurface
                )
            )
        } else {
            val error = activity.getTintedDrawable(R.drawable.ic_audio_file, iconColor)
            Glide.with(activity)
                .load(AudioFileCover(file.path))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .error(error)
                .placeholder(error)
                .transition(HoaGlideExtension.getDefaultTransition())
                .signature(MediaStoreSignature("", file.lastModified(), 0))
                .into(holder.image!!)
        }
    }

    override fun getItemCount(): Int {
        var count = dataSet.size
        if (AdsManager.shouldShowAds() && count >= AD_INTERVAL) {
            count += count / AD_INTERVAL
        }
        return count
    }

    override fun getIdentifier(position: Int): File? {
        val realPos = getRealPosition(position)
        if (realPos < 0 || realPos >= dataSet.size) return null
        return dataSet[realPos]
    }

    override fun getName(model: File): String {
        return getFileTitle(model)
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<File>) {
        if (iCallbacks == null) return
        iCallbacks.onMultipleItemAction(menuItem, selection as ArrayList<File>)
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
            adContainer?.let { AdsManager.loadBannerAd(it) }
        }
        override fun onClick(v: View?) {}
        override fun onLongClick(v: View?): Boolean = false
    }

    open inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

        init {
            if (menu != null && iCallbacks != null) {
                menu?.setOnClickListener { v ->
                    val position = layoutPosition
                    val realPos = getRealPosition(position)
                    if (isPositionInRange(realPos)) {
                        iCallbacks.onFileMenuClicked(dataSet[realPos], v)
                    }
                }
            }
            if (imageTextContainer != null) {
                imageTextContainer?.cardElevation = 0f
            }
        }

        override fun onClick(v: View?) {
            val position = layoutPosition
            val realPos = getRealPosition(position)
            if (isPositionInRange(realPos)) {
                if (isInQuickSelectMode) {
                    toggleChecked(position)
                } else {
                    iCallbacks?.onFileSelected(dataSet[realPos])
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            val position = layoutPosition
            return isPositionInRange(getRealPosition(position)) && toggleChecked(position)
        }

        private fun isPositionInRange(position: Int): Boolean {
            return position >= 0 && position < dataSet.size
        }
    }

    companion object {

        private const val FILE = 0
        private const val FOLDER = 1
        private const val AD_TYPE = 2
        private const val AD_INTERVAL = 4

        fun readableFileSize(size: Long): String {
            if (size <= 0) return "$size B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
            return DecimalFormat("#,##0.##").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
        }
    }
}
