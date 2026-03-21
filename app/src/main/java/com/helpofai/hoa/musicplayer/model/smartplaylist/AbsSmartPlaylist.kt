package com.helpofai.hoa.musicplayer.model.smartplaylist

import androidx.annotation.DrawableRes
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.model.AbsCustomPlaylist

abstract class AbsSmartPlaylist(
    name: String,
    @DrawableRes val iconRes: Int = R.drawable.ic_queue_music
) : AbsCustomPlaylist(
    id = PlaylistIdGenerator(name, iconRes),
    name = name
)