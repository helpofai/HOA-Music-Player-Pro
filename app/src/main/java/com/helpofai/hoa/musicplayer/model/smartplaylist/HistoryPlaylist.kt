package com.helpofai.hoa.musicplayer.model.smartplaylist

import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.model.Song
import kotlinx.parcelize.Parcelize
import org.koin.core.component.KoinComponent

@Parcelize
class HistoryPlaylist : AbsSmartPlaylist(
    name = App.getContext().getString(R.string.history),
    iconRes = R.drawable.ic_history
), KoinComponent {

    override fun songs(): List<Song> {
        return topPlayedRepository.recentlyPlayedTracks()
    }
}