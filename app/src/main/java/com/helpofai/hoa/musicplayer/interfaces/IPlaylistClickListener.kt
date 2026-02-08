package com.helpofai.hoa.musicplayer.interfaces

import android.view.View
import com.helpofai.hoa.musicplayer.db.PlaylistWithSongs

interface IPlaylistClickListener {
    fun onPlaylistClick(playlistWithSongs: PlaylistWithSongs, view: View)
}