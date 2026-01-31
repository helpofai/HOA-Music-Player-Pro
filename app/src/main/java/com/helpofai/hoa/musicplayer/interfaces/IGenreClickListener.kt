package com.helpofai.hoa.musicplayer.interfaces

import android.view.View
import com.helpofai.hoa.musicplayer.model.Genre

interface IGenreClickListener {
    fun onClickGenre(genre: Genre, view: View)
}