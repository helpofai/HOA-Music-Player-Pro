package com.helpofai.hoa.musicplayer.util.theme

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.extensions.generalThemeValue
import com.helpofai.hoa.musicplayer.util.PreferenceUtil
import com.helpofai.hoa.musicplayer.util.theme.ThemeMode.*

@StyleRes
fun Context.getThemeResValue(): Int =
    if (PreferenceUtil.materialYou) {
        if (generalThemeValue == BLACK) R.style.Theme_HOAMusic_MD3_Black
        else R.style.Theme_HOAMusic_MD3
    } else {
        when (generalThemeValue) {
            LIGHT -> R.style.Theme_HOAMusic_Light
            DARK -> R.style.Theme_HOAMusic_Base
            BLACK -> R.style.Theme_HOAMusic_Black
            AUTO -> R.style.Theme_HOAMusic_FollowSystem
        }
    }

fun Context.getNightMode(): Int = when (generalThemeValue) {
    LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    DARK -> AppCompatDelegate.MODE_NIGHT_YES
    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}