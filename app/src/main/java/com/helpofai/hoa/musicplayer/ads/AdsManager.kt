/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 */
package com.helpofai.hoa.musicplayer.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.helpofai.hoa.musicplayer.App
import com.helpofai.hoa.musicplayer.BuildConfig
import com.helpofai.hoa.musicplayer.R
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Professional Ads Manager to handle dynamic ad display.
 *
 * Supports three ad formats: Banner (inline), Interstitial (full-screen),
 * and Native (custom layout). Pro users see zero ads.
 */
object AdsManager {
    private const val TAG = "AdsManager"

    // Use test IDs in Debug builds, Production IDs in Release builds
    private val IS_TEST_MODE = BuildConfig.DEBUG

    // Production IDs are now safely injected via BuildConfig from local.properties
    private val PROD_BANNER_AD_UNIT_ID = BuildConfig.ADMOB_BANNER_ID
    private val PROD_INTERSTITIAL_AD_UNIT_ID = BuildConfig.ADMOB_INTERSTITIAL_ID
    private val PROD_NATIVE_AD_UNIT_ID = BuildConfig.ADMOB_NATIVE_ID

    // Official Google Test IDs
    private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

    // Rewarded ad — test ID from Google, production uses same native ID as placeholder
    private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    private const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    private const val BANNER_RETRY_DELAY_MS = 30_000L      // Retry failed banner after 30s

    // Default to enabled; runtime pro check in shouldShowAds() handles pro gating.
    // Use setAdsEnabled() for remote-config overrides.
    private var isAdsEnabled = true
    private var mInterstitialAd: InterstitialAd? = null
    private var mRewardedAd: RewardedAd? = null
    private var preloadedNativeAd: NativeAd? = null

    // Track pending retry Runnables keyed by container identity, so we can cancel
    // stale retries when a fresh loadBannerAd is called for the same container.
    private val pendingBannerRetries = HashMap<Int, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getBannerId(): String {
        return if (IS_TEST_MODE || PROD_BANNER_AD_UNIT_ID.isEmpty()) TEST_BANNER_AD_UNIT_ID else PROD_BANNER_AD_UNIT_ID
    }

    private fun getInterstitialId(): String {
        return if (IS_TEST_MODE || PROD_INTERSTITIAL_AD_UNIT_ID.isEmpty()) TEST_INTERSTITIAL_AD_UNIT_ID else PROD_INTERSTITIAL_AD_UNIT_ID
    }

    private fun getNativeId(): String {
        return if (IS_TEST_MODE || PROD_NATIVE_AD_UNIT_ID.isEmpty()) TEST_NATIVE_AD_UNIT_ID else PROD_NATIVE_AD_UNIT_ID
    }

    private fun getRewardedId(): String {
        return if (IS_TEST_MODE || PROD_REWARDED_AD_UNIT_ID.isEmpty()) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
    }

    /**
     * Check if ads should be shown.
     */
    fun shouldShowAds(): Boolean {
        return isAdsEnabled && !App.isProVersion()
    }

    /**
     * Set dynamic ad visibility (e.g. from remote config).
     */
    fun setAdsEnabled(enabled: Boolean) {
        isAdsEnabled = enabled
    }

    /**
     * Destroy any AdView children in the container to prevent memory leaks.
     */
    private fun destroyExistingAdViews(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is AdView) {
                child.destroy()
            }
        }
    }

    /**
     * Load and show a banner ad in the provided container.
     * Automatically destroys any previous AdView in the container and cancels
     * any stale retry Runnables.
     *
     * Uses fixed-size [AdSize.BANNER] for maximum fill rate. Keeps the container
     * VISIBLE at all times so the ad slot is always present — on failure the
     * container shows but is transparent to clicks.
     *
     * Retries up to 3 times with exponential backoff (30s, 60s, 90s).
     */
    fun loadBannerAd(container: ViewGroup) {
        if (!shouldShowAds()) {
            container.visibility = View.GONE
            return
        }

        val containerKey = System.identityHashCode(container)

        // Cancel any stale retry from a previous load of this container
        pendingBannerRetries.remove(containerKey)?.let { mainHandler.removeCallbacks(it) }

        // Destroy any existing AdView to prevent memory leaks
        destroyExistingAdViews(container)

        val adView = AdView(container.context)
        adView.adUnitId = getBannerId()
        adView.setAdSize(AdSize.BANNER)

        container.removeAllViews()
        container.addView(adView)
        container.visibility = View.VISIBLE

        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        var retryAttempts = 0
        val maxRetries = 3

        adView.adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.e(TAG, "Banner failed to load: ${error.message} (attempt ${retryAttempts + 1})")
                // Keep container VISIBLE — the empty slot is better than a
                // disappearing layout. User will see a blank space bounded by
                // the container's minHeight.
                if (retryAttempts < maxRetries) {
                    retryAttempts++
                    val delay = BANNER_RETRY_DELAY_MS * retryAttempts
                    val retryTask = Runnable {
                        // Double-check we haven't been replaced by a fresh bind
                        if (container.indexOfChild(adView) >= 0 && shouldShowAds()) {
                            adView.loadAd(adRequest)
                        }
                    }
                    pendingBannerRetries[containerKey] = retryTask
                    mainHandler.postDelayed(retryTask, delay)
                }
            }

            override fun onAdLoaded() {
                retryAttempts = 0
            }

            override fun onAdImpression() {
                retryAttempts = 0
            }
        }
    }

    /**
     * Preload Interstitial Ad.
     */
    fun loadInterstitialAd(context: Context) {
        if (!shouldShowAds()) return

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, getInterstitialId(), adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Interstitial failed to load: ${adError.message}")
                    mInterstitialAd = null
                }

                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    Log.d(TAG, "Interstitial loaded.")
                    mInterstitialAd = interstitialAd
                }
            })
    }

    /**
     * Show Interstitial Ad if loaded.
     */
    fun showInterstitialAd(activity: Activity) {
        if (!shouldShowAds()) return

        mInterstitialAd?.let { ad ->
            ad.show(activity)
            mInterstitialAd = null
            loadInterstitialAd(activity)
        } ?: run {
            Log.d(TAG, "The interstitial ad wasn't ready yet.")
            loadInterstitialAd(activity)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Native Ads
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Destroy a NativeAd and release its resources. Safe to call with null.
     */
    fun destroyNativeAd(nativeAd: NativeAd?) {
        nativeAd?.destroy()
    }

    /**
     * Preload a NativeAd in the background for instant display later.
     */
    fun preloadNativeAd(context: Context) {
        if (!shouldShowAds()) return

        val adLoader = AdLoader.Builder(context, getNativeId())
            .forNativeAd { nativeAd ->
                preloadedNativeAd?.destroy()
                preloadedNativeAd = nativeAd
                Log.d(TAG, "Native ad preloaded and cached.")
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Native ad preload failed: ${adError.message}")
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Get a cached (preloaded) NativeAd, or load fresh if none cached.
     * The callback receives the ad immediately if preloaded, or after a fresh load.
     */
    fun getNativeAd(context: Context, callback: (NativeAd?) -> Unit) {
        if (!shouldShowAds()) {
            callback(null)
            return
        }

        // Return cached ad instantly if available
        val cached = preloadedNativeAd
        if (cached != null) {
            preloadedNativeAd = null
            callback(cached)
            // Preload next ad in background for next time
            preloadNativeAd(context)
            return
        }

        // No cache — load fresh
        val adLoader = AdLoader.Builder(context, getNativeId())
            .forNativeAd { nativeAd ->
                callback(nativeAd)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Native ad failed to load: ${adError.message}")
                    callback(null)
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    /**
     * Populate Native Ad View with data.
     */
    fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)
        adView.mediaView = adView.findViewById(R.id.ad_media)
        adView.advertiserView = adView.findViewById(R.id.ad_advertiser)

        (adView.headlineView as TextView).text = nativeAd.headline
        nativeAd.mediaContent?.let { (adView.mediaView as MediaView).mediaContent = it }

        if (nativeAd.body == null) {
            adView.bodyView?.visibility = View.INVISIBLE
        } else {
            adView.bodyView?.visibility = View.VISIBLE
            (adView.bodyView as TextView).text = nativeAd.body
        }

        if (nativeAd.callToAction == null) {
            adView.callToActionView?.visibility = View.INVISIBLE
        } else {
            adView.callToActionView?.visibility = View.VISIBLE
            (adView.callToActionView as Button).text = nativeAd.callToAction
        }

        if (nativeAd.icon == null) {
            adView.iconView?.visibility = View.GONE
        } else {
            (adView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            adView.iconView?.visibility = View.VISIBLE
        }

        if (nativeAd.advertiser == null) {
            adView.advertiserView?.visibility = View.INVISIBLE
        } else {
            (adView.advertiserView as TextView).text = nativeAd.advertiser
            adView.advertiserView?.visibility = View.VISIBLE
        }

        adView.setNativeAd(nativeAd)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Rewarded Ads (Pro Feature Trials)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Preload a RewardedAd for pro feature trials.
     */
    fun loadRewardedAd(context: Context) {
        if (!shouldShowAds()) return

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(context, getRewardedId(), adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Rewarded ad failed to load: ${adError.message}")
                    mRewardedAd = null
                }

                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    Log.d(TAG, "Rewarded ad loaded.")
                    mRewardedAd = rewardedAd
                }
            })
    }

    /**
     * Show a RewardedAd if loaded. The [onReward] callback fires when the user earns
     * the reward (watches the full ad).
     */
    fun showRewardedAd(
        activity: Activity,
        onReward: (rewardAmount: Int, rewardType: String) -> Unit,
        onError: (() -> Unit)? = null
    ) {
        if (!shouldShowAds()) return

        mRewardedAd?.let { ad ->
            ad.show(activity) { rewardItem: RewardItem ->
                val amount = rewardItem.amount
                val type = rewardItem.type
                Log.d(TAG, "User earned reward: $amount $type")
                onReward(amount, type)
            }
            mRewardedAd = null
            loadRewardedAd(activity)
        } ?: run {
            Log.d(TAG, "Rewarded ad wasn't ready yet.")
            onError?.invoke()
            loadRewardedAd(activity)
        }
    }

    /**
     * Release all ad resources. Call from Application.onTerminate() or when ads are permanently disabled.
     */
    /**
     * Print diagnostic info to Logcat — useful for debugging ad fill issues.
     * Look for "AdDiagnostics" in the log. Call from App.onCreate() or via debug menu.
     */
    fun logDiagnostics() {
        val isPro = App.isProVersion()
        val nativeReady = preloadedNativeAd != null
        val interstitialReady = mInterstitialAd != null
        val rewardedReady = mRewardedAd != null

        Log.i(TAG, "===== Ad Diagnostics =====")
        Log.i(TAG, "Debug build: ${BuildConfig.DEBUG}")
        Log.i(TAG, "Pro version: $isPro")
        Log.i(TAG, "Ads enabled: $isAdsEnabled")
        Log.i(TAG, "shouldShowAds(): ${shouldShowAds()}")
        Log.i(TAG, "Native ad unit: ${
            if (IS_TEST_MODE || PROD_NATIVE_AD_UNIT_ID.isEmpty()) "TEST ($TEST_NATIVE_AD_UNIT_ID)"
            else "PROD ($PROD_NATIVE_AD_UNIT_ID)"
        }")
        Log.i(TAG, "Banner ad unit:  ${
            if (IS_TEST_MODE || PROD_BANNER_AD_UNIT_ID.isEmpty()) "TEST ($TEST_BANNER_AD_UNIT_ID)"
            else "PROD ($PROD_BANNER_AD_UNIT_ID)"
        }")
        Log.i(TAG, "Interstitial ad unit: ${
            if (IS_TEST_MODE || PROD_INTERSTITIAL_AD_UNIT_ID.isEmpty()) "TEST ($TEST_INTERSTITIAL_AD_UNIT_ID)"
            else "PROD ($PROD_INTERSTITIAL_AD_UNIT_ID)"
        }")
        Log.i(TAG, "Native preloaded: $nativeReady")
        Log.i(TAG, "Interstitial loaded: $interstitialReady")
        Log.i(TAG, "Rewarded loaded: $rewardedReady")
        Log.i(TAG, "==========================")
    }

    fun release() {
        mInterstitialAd = null
        mRewardedAd = null
        preloadedNativeAd?.destroy()
        preloadedNativeAd = null
        pendingBannerRetries.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
