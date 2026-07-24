package com.helpofai.hoa.musicplayer.extensions

import androidx.core.view.WindowInsetsCompat
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.util.HoaUtil

fun WindowInsetsCompat?.getBottomInsets(): Int {
    return if (PreferenceUtil.isFullScreenMode) {
        return 0
    } else {
        this?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: HoaUtil.navigationBarHeight
    }
}
