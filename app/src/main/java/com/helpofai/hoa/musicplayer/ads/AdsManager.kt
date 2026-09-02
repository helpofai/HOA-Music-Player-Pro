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
 * Supports three ad formats: Banner (adaptive & inline), Interstitial (full-screen),
 * Native (custom layout), and Rewarded (pro feature trials).
 * Pro users see zero ads.
 */
object AdsManager {
    private const val TAG = "AdsManager"

    // Production IDs are safely injected via BuildConfig from local.properties
    private val PROD_BANNER_AD_UNIT_ID = BuildConfig.ADMOB_BANNER_ID
    private val PROD_INTERSTITIAL_AD_UNIT_ID = BuildConfig.ADMOB_INTERSTITIAL_ID
    private val PROD_NATIVE_AD_UNIT_ID = BuildConfig.ADMOB_NATIVE_ID
    private val PROD_REWARDED_AD_UNIT_ID = BuildConfig.ADMOB_REWARDED_ID

    // Official Google Test IDs (fallback when production IDs are not configured)
    private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
    private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    private const val BANNER_RETRY_DELAY_MS = 25_000L      // Retry failed banner after 25s

    // Default to enabled; runtime pro check in shouldShowAds() handles pro gating.
    // Use setAdsEnabled() for remote-config overrides.
    private var isAdsEnabled = true
    private var mInterstitialAd: InterstitialAd? = null
    private var mRewardedAd: RewardedAd? = null
    private var preloadedNativeAd: NativeAd? = null
    private var isPreloadingNative = false
    private var lastNativeFailureTimestamp = 0L
    private const val NATIVE_FAILURE_COOLDOWN_MS = 15_000L

    // Track pending retry Runnables keyed by container identity, so we can cancel
    // stale retries when a fresh loadBannerAd is called for the same container.
    private val pendingBannerRetries = HashMap<Int, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getBannerId(): String {
        return if (PROD_BANNER_AD_UNIT_ID.isNotEmpty()) PROD_BANNER_AD_UNIT_ID else TEST_BANNER_AD_UNIT_ID
    }

    fun getInterstitialId(): String {
        return if (PROD_INTERSTITIAL_AD_UNIT_ID.isNotEmpty()) PROD_INTERSTITIAL_AD_UNIT_ID else TEST_INTERSTITIAL_AD_UNIT_ID
    }

    fun getNativeId(): String {
        return if (PROD_NATIVE_AD_UNIT_ID.isNotEmpty()) PROD_NATIVE_AD_UNIT_ID else TEST_NATIVE_AD_UNIT_ID
    }

    fun getRewardedId(): String {
        return if (PROD_REWARDED_AD_UNIT_ID.isNotEmpty()) PROD_REWARDED_AD_UNIT_ID else TEST_REWARDED_AD_UNIT_ID
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
     * Helper to compute Anchored Adaptive Banner size for highest fill rate.
     */
    private fun getAdaptiveBannerSize(context: Context, container: ViewGroup): AdSize {
        val displayMetrics = context.resources.displayMetrics
        var widthPixels = container.width.toFloat()
        if (widthPixels <= 0f) {
            widthPixels = displayMetrics.widthPixels.toFloat()
        }
        val density = displayMetrics.density
        val adWidth = (widthPixels / density).toInt().coerceAtLeast(320)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
    }

    /**
     * Format AdMob LoadAdError into clear actionable developer details.
     */
    fun formatAdError(error: LoadAdError): String {
        val codeDescription = when (error.code) {
            AdRequest.ERROR_CODE_NO_FILL -> "ERROR_CODE_NO_FILL (3): AdMob has no ad to serve right now. Causes: account-level temporary ad limits, missing/unverified app-ads.txt, unlinked Play Store listing, or low regional advertiser demand."
            AdRequest.ERROR_CODE_NETWORK_ERROR -> "ERROR_CODE_NETWORK_ERROR (2): Device network connection or timeout issue."
            AdRequest.ERROR_CODE_INVALID_REQUEST -> "ERROR_CODE_INVALID_REQUEST (1): Invalid Ad Unit ID or App ID mismatch in AndroidManifest."
            AdRequest.ERROR_CODE_INTERNAL_ERROR -> "ERROR_CODE_INTERNAL_ERROR (0): Internal AdMob server error or user consent missing."
            else -> "UNKNOWN (${error.code})"
        }
        return "[$codeDescription] Message: ${error.message} | Domain: ${error.domain}"
    }

    /**
     * Load and show a banner ad in the provided container.
     *
     * @param container ViewGroup where the AdView should be placed
     * @param adaptive Whether to use Anchored Adaptive Banner (full screen width) or fixed standard BANNER
     */
    fun loadBannerAd(container: ViewGroup, adaptive: Boolean = true) {
        if (!shouldShowAds()) {
            container.visibility = View.GONE
            return
        }

        // Avoid destroying already attached or in-flight AdViews when scrolling RecyclerView
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is AdView) {
                container.visibility = View.VISIBLE
                return
            }
        }

        val containerKey = System.identityHashCode(container)

        // Cancel any stale retry from a previous load of this container
        pendingBannerRetries.remove(containerKey)?.let { mainHandler.removeCallbacks(it) }

        val adView = AdView(container.context)
        adView.adUnitId = getBannerId()
        
        try {
            if (adaptive) {
                adView.setAdSize(getAdaptiveBannerSize(container.context, container))
            } else {
                adView.setAdSize(AdSize.BANNER)
            }
        } catch (_: Exception) {
            adView.setAdSize(AdSize.BANNER)
        }

        container.removeAllViews()
        container.addView(adView)
        container.visibility = View.VISIBLE

        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        var retryAttempts = 0
        val maxRetries = 3

        adView.adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.w(TAG, "Banner failed to load: ${formatAdError(error)} (attempt ${retryAttempts + 1})")
                if (retryAttempts < maxRetries) {
                    retryAttempts++
                    val delay = BANNER_RETRY_DELAY_MS * retryAttempts
                    val retryTask = Runnable {
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
                Log.d(TAG, "Banner successfully loaded and displayed.")
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
                    Log.w(TAG, "Interstitial failed to load: ${formatAdError(adError)}")
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
        if (!shouldShowAds() || preloadedNativeAd != null || isPreloadingNative) return
        if (System.currentTimeMillis() - lastNativeFailureTimestamp < NATIVE_FAILURE_COOLDOWN_MS) return

        isPreloadingNative = true
        val adLoader = AdLoader.Builder(context, getNativeId())
            .forNativeAd { nativeAd ->
                preloadedNativeAd?.destroy()
                preloadedNativeAd = nativeAd
                isPreloadingNative = false
                Log.d(TAG, "Native ad preloaded and cached.")
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    isPreloadingNative = false
                    lastNativeFailureTimestamp = System.currentTimeMillis()
                    Log.w(TAG, "Native ad preload failed: ${formatAdError(adError)}")
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

        // Avoid spamming AdMob server if recent request failed
        if (System.currentTimeMillis() - lastNativeFailureTimestamp < NATIVE_FAILURE_COOLDOWN_MS) {
            callback(null)
            return
        }

        // No cache — load fresh
        val adLoader = AdLoader.Builder(context, getNativeId())
            .forNativeAd { nativeAd ->
                callback(nativeAd)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    lastNativeFailureTimestamp = System.currentTimeMillis()
                    Log.w(TAG, "Native ad failed to load: ${formatAdError(adError)}")
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
                    Log.w(TAG, "Rewarded ad failed to load: ${formatAdError(adError)}")
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
        Log.i(TAG, "Native ad unit:       ${if (PROD_NATIVE_AD_UNIT_ID.isNotEmpty()) "PROD ($PROD_NATIVE_AD_UNIT_ID)" else "TEST ($TEST_NATIVE_AD_UNIT_ID)"}")
        Log.i(TAG, "Banner ad unit:       ${if (PROD_BANNER_AD_UNIT_ID.isNotEmpty()) "PROD ($PROD_BANNER_AD_UNIT_ID)" else "TEST ($TEST_BANNER_AD_UNIT_ID)"}")
        Log.i(TAG, "Interstitial ad unit: ${if (PROD_INTERSTITIAL_AD_UNIT_ID.isNotEmpty()) "PROD ($PROD_INTERSTITIAL_AD_UNIT_ID)" else "TEST ($TEST_INTERSTITIAL_AD_UNIT_ID)"}")
        Log.i(TAG, "Rewarded ad unit:     ${if (PROD_REWARDED_AD_UNIT_ID.isNotEmpty()) "PROD ($PROD_REWARDED_AD_UNIT_ID)" else "TEST ($TEST_REWARDED_AD_UNIT_ID)"}")
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
