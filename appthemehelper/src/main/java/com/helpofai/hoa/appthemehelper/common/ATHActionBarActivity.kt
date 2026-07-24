package com.helpofai.hoa.appthemehelper.common

import androidx.appcompat.widget.Toolbar

import com.helpofai.hoa.appthemehelper.util.ToolbarContentTintHelper

class ATHActionBarActivity : ATHToolbarActivity() {

    override fun getATHToolbar(): Toolbar? {
        return ToolbarContentTintHelper.getSupportActionBarView(supportActionBar)
    }
}
