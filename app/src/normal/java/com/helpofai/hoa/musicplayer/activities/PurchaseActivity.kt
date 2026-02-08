package com.helpofai.hoa.musicplayer.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import com.helpofai.hoa.appthemehelper.util.MaterialUtil
import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.R
import com.helpofai.hoa.musicplayer.activities.base.AbsThemeActivity
import com.helpofai.hoa.musicplayer.billing.BillingManager
import com.helpofai.hoa.musicplayer.databinding.ActivityProVersionBinding
import com.helpofai.hoa.musicplayer.extensions.accentColor
import com.helpofai.hoa.musicplayer.extensions.setLightStatusBar
import com.helpofai.hoa.musicplayer.extensions.setStatusBarColor
import com.helpofai.hoa.musicplayer.extensions.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PurchaseActivity : AbsThemeActivity() {

    private lateinit var binding: ActivityProVersionBinding
    private lateinit var billingManager: BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProVersionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setStatusBarColor(Color.TRANSPARENT)
        setLightStatusBar(false)
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        billingManager = App.getContext().billingManager

        MaterialUtil.setTint(binding.purchaseButton, true)

        binding.restoreButton.setOnClickListener {
            restorePurchase()
        }
        binding.purchaseButton.setOnClickListener {
            billingManager.launchBillingFlow(this@PurchaseActivity)
        }
        binding.bannerContainer.backgroundTintList =
            ColorStateList.valueOf(accentColor())
    }

    private fun restorePurchase() {
        billingManager.restorePurchases {
            if (App.isProVersion()) {
                showToast(R.string.restored_previous_purchase_please_restart)
                setResult(RESULT_OK)
            } else {
                showToast(R.string.no_purchase_found)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        billingManager.release()
        super.onDestroy()
    }
}