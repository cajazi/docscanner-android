package com.dev.docscannerpdf.domain.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dev.docscannerpdf.domain.ads.AdManager.AD_UNIT_ID_TEST_INTERSTITIAL
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AdManager {
    private const val TAG = "AdManager"

    // Official Google test interstitial ad unit ID — REMEMBER: replace with your
    // production ad unit id before releasing to users / the Play Store.
    const val AD_UNIT_ID_TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"

    private val initialized = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)
    private val lastAdClosedAt = AtomicLong(0L)

    @Volatile
    private var interstitialAd: InterstitialAd? = null

    @Volatile
    private var premiumProvider: (() -> Boolean)? = null

    private fun logDebug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }

    fun setPremiumProvider(provider: () -> Boolean) {
        premiumProvider = provider
    }

    /** Initialize Mobile Ads SDK and preload one interstitial. Safe to call multiple times. */
    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            logDebug("Initializing MobileAds")

            // Configure test devices (include emulator). Keep for debug/testing only.
            val requestConfig = RequestConfiguration.Builder()
                .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                .build()
            MobileAds.setRequestConfiguration(requestConfig)

            MobileAds.initialize(context) { initializationStatus ->
                logDebug("MobileAds initialized: $initializationStatus")
                // Preload one interstitial after initialization completes.
                try {
                    loadInterstitial(context)
                } catch (t: Throwable) {
                    logDebug("Failed to start preloading interstitial: ${t.message}")
                }
            }
        } else {
            logDebug("AdManager.initialize() called but already initialized")
        }
    }

    /** Load an interstitial ad if not already loading. Non-blocking. */
    fun loadInterstitial(context: Context) {
        if (premiumProvider?.invoke() == true) return
        if (isLoading.compareAndSet(false, true)) {
            logDebug("Starting interstitial load")

            val adRequest = AdRequest.Builder().build()

            InterstitialAd.load(
                context,
                AD_UNIT_ID_TEST_INTERSTITIAL,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        logDebug("onAdLoaded: interstitial loaded")
                        synchronized(this@AdManager) {
                            interstitialAd = ad
                            // Attach a default full screen callback that logs lifecycle events.
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdShowedFullScreenContent() {
                                    logDebug("onAdShowedFullScreenContent")
                                }

                                override fun onAdDismissedFullScreenContent() {
                                    logDebug("onAdDismissedFullScreenContent")
                                    lastAdClosedAt.set(System.currentTimeMillis())
                                    // Preload another ad for next time.
                                    try {
                                        loadInterstitial(context)
                                    } catch (t: Throwable) {
                                        logDebug("Failed to reload after dismiss: ${t.message}")
                                    }
                                }

                                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                                    logDebug("onAdFailedToShowFullScreenContent: ${error.message}")
                                    lastAdClosedAt.set(System.currentTimeMillis())
                                    try {
                                        loadInterstitial(context)
                                    } catch (t: Throwable) {
                                        logDebug("Failed to reload after failure: ${t.message}")
                                    }
                                }

                                override fun onAdImpression() {
                                    logDebug("onAdImpression")
                                }
                            }
                        }
                        isLoading.set(false)
                    }

                    override fun onAdFailedToLoad(loadAdError: com.google.android.gms.ads.LoadAdError) {
                        logDebug("onAdFailedToLoad: ${loadAdError.message}")
                        isLoading.set(false)
                        // schedule/attempt no further action here; caller may attempt again.
                    }
                }
            )
        } else {
            logDebug("loadInterstitial called but a load is already in progress")
        }
    }

    /**
     * Show interstitial if allowed by throttle and if an ad is available.
     * Never blocks user flow — `onAdClosed` is always invoked promptly.
     */
    fun showInterstitialIfAllowed(activity: Activity, onAdClosed: () -> Unit) {
        if (premiumProvider?.invoke() == true) {
            onAdClosed()
            return
        }

        // Throttle: require at least 120 seconds between ad dismissals.
        val now = System.currentTimeMillis()
        val last = lastAdClosedAt.get()
        val elapsed = now - last
        val minIntervalMs = 120_000L

        if (last != 0L && elapsed < minIntervalMs) {
            logDebug("Ad throttled: only $elapsed ms since last ad; skipping show")
            // Immediately return to user flow.
            onAdClosed()
            return
        }

        val adToShow: InterstitialAd? = synchronized(this) {
            val a = interstitialAd
            if (a != null) {
                interstitialAd = null // consume the ad reference
            }
            a
        }

        if (adToShow == null) {
            logDebug("No interstitial available to show; calling onAdClosed immediately")
            // Make sure we try to load another ad for future.
            try {
                loadInterstitial(activity.applicationContext)
            } catch (t: Throwable) {
                logDebug("Failed to start reload when ad was null: ${t.message}")
            }
            onAdClosed()
            return
        }

        logDebug("Showing interstitial ad")

        // Ensure FullScreenContentCallback handles lifecycle and user flow.
        adToShow.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                logDebug("Ad show started")
            }

            override fun onAdDismissedFullScreenContent() {
                logDebug("Ad dismissed by user")
                lastAdClosedAt.set(System.currentTimeMillis())
                try {
                    loadInterstitial(activity.applicationContext)
                } catch (t: Throwable) {
                    logDebug("Failed to preload after dismissal: ${t.message}")
                }
                try {
                    onAdClosed()
                } catch (t: Throwable) {
                    logDebug("onAdClosed threw: ${t.message}")
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                logDebug("Ad failed to show: ${error.message}")
                lastAdClosedAt.set(System.currentTimeMillis())
                try {
                    loadInterstitial(activity.applicationContext)
                } catch (t: Throwable) {
                    logDebug("Failed to preload after failure: ${t.message}")
                }
                try {
                    onAdClosed()
                } catch (t: Throwable) {
                    logDebug("onAdClosed threw after failure: ${t.message}")
                }
            }
        }

        try {
            adToShow.show(activity)
        } catch (t: Throwable) {
            logDebug("Exception while showing ad: ${t.message}")
            // Ensure user flow continues even if showing fails.
            lastAdClosedAt.set(System.currentTimeMillis())
            try {
                loadInterstitial(activity.applicationContext)
            } catch (t2: Throwable) {
                logDebug("Failed to preload after show exception: ${t2.message}")
            }
            try {
                onAdClosed()
            } catch (t2: Throwable) {
                logDebug("onAdClosed threw after show exception: ${t2.message}")
            }
        }
    }
}
