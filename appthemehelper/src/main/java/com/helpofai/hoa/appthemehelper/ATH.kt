package com.helpofai.hoa.appthemehelper

import android.content.Context
import android.view.View
import androidx.annotation.ColorInt
import com.helpofai.hoa.appthemehelper.util.TintHelper

/**
 * @author Rajib Adhikary
 */
object ATH {

    @JvmStatic
    fun didThemeValuesChange(context: Context, since: Long): Boolean {
        return ThemeStore.isConfigured(context) && ThemeStore.prefs(context).getLong(
            ThemeStorePrefKeys.VALUES_CHANGED,
            -1
        ) > since
    }

    @JvmStatic
    fun setTint(view: View, @ColorInt color: Int) {
        TintHelper.setTintAuto(view, color, false)
    }

    @JvmStatic
    @Suppress("unused")
    fun setBackgroundTint(view: View, @ColorInt color: Int) {
        TintHelper.setTintAuto(view, color, true)
    }
}
