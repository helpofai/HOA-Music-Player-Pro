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
package com.helpofai.hoa.musicplayer.helper.menu

import android.content.Intent
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import com.helpofai.hoa.musicplayer.EXTRA_ALBUM_ID
import com.helpofai.hoa.musicplayer.EXTRA_ARTIST_ID
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.activities.tageditor.AbsTagEditorActivity
import com.helpofai.hoa.musicplayer.activities.tageditor.SongTagEditorActivity
import com.helpofai.hoa.musicplayer.dialogs.AddToPlaylistDialog
import com.helpofai.hoa.musicplayer.dialogs.DeleteSongsDialog
import com.helpofai.hoa.musicplayer.dialogs.SongDetailDialog
import com.helpofai.hoa.musicplayer.fragments.LibraryViewModel
import com.helpofai.hoa.musicplayer.fragments.ReloadType
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.interfaces.IPaletteColorHolder
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.providers.BlacklistStore
import com.helpofai.hoa.musicplayer.repository.RealRepository
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.RingtoneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

object SongMenuHelper : KoinComponent {
    val MENU_RES
        get() = R.menu.menu_item_song

    fun handleMenuClick(activity: FragmentActivity, song: Song, menuItemId: Int): Boolean {
        val libraryViewModel = activity.getViewModel() as LibraryViewModel
        when (menuItemId) {
            R.id.action_set_as_ringtone -> {
                if (RingtoneManager.requiresDialog(activity)) {
                    RingtoneManager.showDialog(activity)
                } else {
                    RingtoneManager.setRingtone(activity, song)
                }
                return true
            }
            R.id.action_share -> {
                activity.startActivity(
                    Intent.createChooser(
                        MusicUtil.createShareSongFileIntent(activity, song),
                        null
                    )
                )
                return true
            }
            R.id.action_delete_from_device -> {
                DeleteSongsDialog.create(song).show(activity.supportFragmentManager, "DELETE_SONGS")
                return true
            }
            R.id.action_add_to_playlist -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val playlists = get<RealRepository>().fetchPlaylists()
                    withContext(Dispatchers.Main) {
                        AddToPlaylistDialog.create(playlists, song)
                            .show(activity.supportFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(song)
                return true
            }
            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(song)
                return true
            }
            R.id.action_tag_editor -> {
                val tagEditorIntent = Intent(activity, SongTagEditorActivity::class.java)
                tagEditorIntent.putExtra(AbsTagEditorActivity.EXTRA_ID, song.id)
                if (activity is IPaletteColorHolder)
                    tagEditorIntent.putExtra(
                        AbsTagEditorActivity.EXTRA_PALETTE,
                        (activity as IPaletteColorHolder).paletteColor
                    )
                activity.startActivity(tagEditorIntent)
                return true
            }
            R.id.action_details -> {
                SongDetailDialog.create(song).show(activity.supportFragmentManager, "SONG_DETAILS")
                return true
            }
            R.id.action_go_to_album -> {
                activity.findNavController(R.id.fragment_container).navigate(
                    R.id.albumDetailsFragment,
                    bundleOf(EXTRA_ALBUM_ID to song.albumId)
                )
                return true
            }
            R.id.action_go_to_artist -> {
                activity.findNavController(R.id.fragment_container).navigate(
                    R.id.artistDetailsFragment,
                    bundleOf(EXTRA_ARTIST_ID to song.artistId)
                )
                return true
            }
            R.id.action_add_to_blacklist -> {
                BlacklistStore.getInstance(activity).addPath(File(song.data))
                libraryViewModel.forceReload(ReloadType.Songs)
                return true
            }
        }
        return false
    }

    abstract class OnClickSongMenu(private val activity: FragmentActivity) :
        View.OnClickListener, PopupMenu.OnMenuItemClickListener {

        open val menuRes: Int
            get() = MENU_RES

        abstract val song: Song

        override fun onClick(v: View) {
            val popupMenu = PopupMenu(activity, v)
            popupMenu.inflate(menuRes)
            popupMenu.setOnMenuItemClickListener(this)
            popupMenu.show()
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            return handleMenuClick(activity, song, item.itemId)
        }
    }
}
