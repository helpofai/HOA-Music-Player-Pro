package com.helpofai.hoa.musicplayer.model.smartplaylist

import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class LastAddedPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.last_added),
    iconRes = R.drawable.ic_library_add
) {
    override fun songs(): List<Song> {
        return lastAddedRepository.recentSongs()
    }
}