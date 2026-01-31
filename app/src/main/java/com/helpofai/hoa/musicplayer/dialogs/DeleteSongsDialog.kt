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
package com.helpofai.hoa.musicplayer.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.fragment.app.DialogFragment
import com.helpofai.hoa.appthemehelper.util.VersionUtils
import com.helpofai.hoa.musicplayer.EXTRA_SONG
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.activities.saf.SAFGuideActivity
import com.helpofai.hoa.musicplayer.extensions.extraNotNull
import com.helpofai.hoa.musicplayer.extensions.materialDialog
import com.helpofai.hoa.musicplayer.fragments.LibraryViewModel
import com.helpofai.hoa.musicplayer.fragments.ReloadType
import com.helpofai.hoa.musicplayer.helper.MusicPlayerRemote
import com.helpofai.hoa.musicplayer.model.Song
import com.helpofai.hoa.musicplayer.util.MusicUtil
import com.helpofai.hoa.musicplayer.util.SAFUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.getViewModel

class DeleteSongsDialog : DialogFragment() {
    lateinit var libraryViewModel: LibraryViewModel

    // Activity Result Launchers for the new API
    private val safGuideResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Launch tree picker directly
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
            safPickerResultLauncher.launch(intent)
        }
    }

    private val safPickerResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                SAFUtil.saveTreeUri(requireActivity(), data)
                val songs = extraNotNull<List<Song>>(EXTRA_SONG).value
                deleteSongs(songs)
            }
        }
    }

    companion object {
        fun create(song: Song): DeleteSongsDialog {
            val list = ArrayList<Song>()
            list.add(song)
            return create(list)
        }

        fun create(songs: List<Song>): DeleteSongsDialog {
            return DeleteSongsDialog().apply {
                arguments = bundleOf(
                    EXTRA_SONG to ArrayList(songs)
                )
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        libraryViewModel = activity?.getViewModel() as LibraryViewModel
        val songs = extraNotNull<List<Song>>(EXTRA_SONG).value
        if (VersionUtils.hasR()) {
            val deleteResultLauncher =
                registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        if ((songs.size == 1) && MusicPlayerRemote.isPlaying(songs[0])) {
                            MusicPlayerRemote.playNextSong()
                        }
                        MusicPlayerRemote.removeFromQueue(songs)
                        reloadTabs()
                    }
                    dismiss()
                }
            val pendingIntent =
                MediaStore.createDeleteRequest(requireActivity().contentResolver, songs.map {
                    MusicUtil.getSongFileUri(it.id)
                })
            deleteResultLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            return super.onCreateDialog(savedInstanceState)
        } else {
            val pair = if (songs.size > 1) {
                Pair(
                    R.string.delete_songs_title,
                    String.format(getString(R.string.delete_x_songs), songs.size).parseAsHtml()
                )
            } else {
                Pair(
                    R.string.delete_song_title,
                    String.format(getString(R.string.delete_song_x), songs[0].title).parseAsHtml()
                )
            }

            return materialDialog()
                .title(pair.first)
                .message(text = pair.second)
                .noAutoDismiss()
                .negativeButton(android.R.string.cancel)
                {
                    dismiss()
                }
                .positiveButton(R.string.action_delete)
                {
                    if ((songs.size == 1) && MusicPlayerRemote.isPlaying(songs[0])) {
                        MusicPlayerRemote.playNextSong()
                    }
                    if (!SAFUtil.isSAFRequiredForSongs(songs)) {
                        CoroutineScope(Dispatchers.IO).launch {
                            dismiss()
                            MusicUtil.deleteTracks(requireContext(), songs)
                            reloadTabs()
                        }
                    } else {
                        if (SAFUtil.isSDCardAccessGranted(requireActivity())) {
                            deleteSongs(songs)
                        } else {
                            safGuideResultLauncher.launch(
                                Intent(requireActivity(), SAFGuideActivity::class.java)
                            )
                        }
                    }
                }
        }
    }

    fun deleteSongs(songs: List<Song>) {
        CoroutineScope(Dispatchers.IO).launch {
            dismiss()
            MusicUtil.deleteTracks(requireActivity(), songs, null, null)
            reloadTabs()
        }
    }

    private fun reloadTabs() {
        libraryViewModel.forceReload(ReloadType.Songs)
        libraryViewModel.forceReload(ReloadType.HomeSections)
        libraryViewModel.forceReload(ReloadType.Artists)
        libraryViewModel.forceReload(ReloadType.Albums)
        libraryViewModel.forceReload(ReloadType.PlayCount)
    }
}
