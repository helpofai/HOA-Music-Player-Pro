package com.helpofai.hoa.musicplayer.model.smartplaylist

import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class ShuffleAllPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.action_shuffle_all),
    iconRes = R.drawable.ic_shuffle
) {
    override fun songs(): List<Song> {
        return songRepository.songs()
    }
}