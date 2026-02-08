package com.helpofai.hoa.musicplayer.interfaces

import com.helpofai.hoa.musicplayer.model.Album
import com.helpofai.hoa.musicplayer.model.Artist
import com.helpofai.hoa.musicplayer.model.Genre

interface IHomeClickListener {
    fun onAlbumClick(album: Album)

    fun onArtistClick(artist: Artist)

    fun onGenreClick(genre: Genre)
}