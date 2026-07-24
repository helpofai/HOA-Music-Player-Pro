package com.helpofai.hoa.musicplayer.model.smartplaylist

import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.model.Song
import kotlinx.parcelize.Parcelize

@Parcelize
class TopTracksPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.my_top_tracks),
    iconRes = R.drawable.ic_trending_up
) {
    override fun songs(): List<Song> {
        return topPlayedRepository.topTracks()
    }
}