package com.helpofai.hoa.appthemehelper.common.prefs.supportv7

import android.content.Context
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.helpofai.hoa.appthemehelper.R
import com.helpofai.hoa.appthemehelper.ThemeStore
import com.helpofai.hoa.appthemehelper.util.ATHUtil
import com.helpofai.hoa.appthemehelper.util.TintHelper
import com.helpofai.hoa.appthemehelper.util.VersionUtils

class ATESeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1,
    defStyleRes: Int = -1
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    var unit: String = ""

    init {
        context.withStyledAttributes(attrs, R.styleable.ATESeekBarPreference, 0, 0) {
            getString(R.styleable.ATESeekBarPreference_ateKey_pref_unit)?.let {
                unit = it
            }
        }
        icon?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
            ATHUtil.resolveColor(
                context,
                android.R.attr.colorControlNormal
            ), BlendModeCompat.SRC_IN
        )
    }

    override fun onBindViewHolder(view: PreferenceViewHolder) {
        super.onBindViewHolder(view)
        val seekBar = view.findViewById(androidx.preference.R.id.seekbar) as SeekBar
        val accentColor = ThemeStore.accentColor(context)
        TintHelper.setTintAuto(
            seekBar, // Set MD3 accent if MD3 is enabled or in-app accent otherwise
            accentColor, false
        )
        (view.findViewById(androidx.preference.R.id.seekbar_value) as TextView).apply {
            // Fix: Only append unit once on bind, skip doAfterTextChanged to avoid infinite loop with EmojiCompat
            if (unit.isNotEmpty() && !text.endsWith(unit)) {
                append(unit)
            }
        }

        // Use dynamic ID lookup to support multi-module builds
        fun findViewByName(name: String): android.view.View? {
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            return if (id != 0) view.findViewById(id) else null
        }

        findViewByName("button_minus")?.let {
            TintHelper.setTintAuto(it, accentColor, false)
            it.setOnClickListener {
                val newValue = value - 1
                if (callChangeListener(newValue)) {
                    value = newValue
                }
            }
        }
        findViewByName("button_plus")?.let {
            TintHelper.setTintAuto(it, accentColor, false)
            it.setOnClickListener {
                val newValue = value + 1
                if (callChangeListener(newValue)) {
                    value = newValue
                }
            }
        }
    }
}
