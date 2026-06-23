package com.dev.docscannerpdf.ui

import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.delay

private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
private const val TAG = "NativeAdViewContainer"

@Composable
fun NativeAdViewContainer(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    DisposableEffect(nativeAd) {
        onDispose {
            nativeAd?.destroy()
        }
    }

    LaunchedEffect(loadAttempt) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Loading native ad")
        }
        loadFailed = false
        val adLoader = AdLoader.Builder(context, TEST_NATIVE_AD_UNIT_ID)
            .forNativeAd { loadedAd ->
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Native ad loaded")
                }
                nativeAd?.destroy()
                nativeAd = loadedAd
                loadFailed = false
            }
            .withAdListener(
                object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.w(TAG, "Native ad failed: ${loadAdError.message}")
                        }
                        loadFailed = true
                        nativeAd?.destroy()
                        nativeAd = null
                    }
                }
            )
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setRequestMultipleImages(false)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    if (loadFailed && nativeAd == null) {
        NativeAdFallbackPlaceholder(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            onRetry = { loadAttempt += 1 }
        )
        return
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp)),
        factory = { viewContext ->
            createNativeAdView(
                context = viewContext,
                backgroundColor = colorScheme.surfaceVariant.toArgb(),
                primaryTextColor = colorScheme.onSurface.toArgb(),
                secondaryTextColor = colorScheme.onSurfaceVariant.toArgb(),
                accentColor = colorScheme.primary.toArgb(),
                headlineTextSize = typography.titleSmall.fontSize.value,
                bodyTextSize = typography.bodySmall.fontSize.value,
                labelTextSize = typography.labelSmall.fontSize.value
            )
        },
        update = { adView ->
            bindNativeAd(adView, nativeAd)
        }
    )
}

private fun createNativeAdView(
    context: android.content.Context,
    backgroundColor: Int,
    primaryTextColor: Int,
    secondaryTextColor: Int,
    accentColor: Int,
    headlineTextSize: Float,
    bodyTextSize: Float,
    labelTextSize: Float
): NativeAdView {
    val adView = NativeAdView(context).apply {
        setBackgroundColor(backgroundColor)
        setPadding(16.dpToPx(context), 12.dpToPx(context), 16.dpToPx(context), 12.dpToPx(context))
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(52.dpToPx(context), 52.dpToPx(context)).apply {
            marginEnd = 12.dpToPx(context)
        }
        visibility = View.GONE
    }

    val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val sponsoredLabel = TextView(context).apply {
        text = "Sponsored"
        setTextColor(accentColor)
        textSize = labelTextSize
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    val headlineView = TextView(context).apply {
        setTextColor(primaryTextColor)
        textSize = headlineTextSize
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    val bodyView = TextView(context).apply {
        setTextColor(secondaryTextColor)
        textSize = bodyTextSize
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    val ctaView = Button(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(accentColor)
        textSize = labelTextSize
        minHeight = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(10.dpToPx(context), 6.dpToPx(context), 10.dpToPx(context), 6.dpToPx(context))
        visibility = View.GONE
    }

    val mediaView = MediaView(context).apply {
        layoutParams = LinearLayout.LayoutParams(82.dpToPx(context), 72.dpToPx(context)).apply {
            marginStart = 12.dpToPx(context)
        }
        visibility = View.GONE
    }

    textColumn.addView(sponsoredLabel)
    textColumn.addView(headlineView)
    textColumn.addView(bodyView)
    textColumn.addView(ctaView)
    root.addView(iconView)
    root.addView(textColumn)
    root.addView(mediaView)
    adView.addView(root)

    adView.iconView = iconView
    adView.headlineView = headlineView
    adView.bodyView = bodyView
    adView.callToActionView = ctaView
    adView.mediaView = mediaView

    return adView
}

private fun bindNativeAd(
    adView: NativeAdView,
    nativeAd: NativeAd?
) {
    if (nativeAd == null) {
        adView.visibility = View.VISIBLE
        return
    }

    adView.visibility = View.VISIBLE

    (adView.headlineView as? TextView)?.text = nativeAd.headline.orEmpty()

    (adView.bodyView as? TextView)?.apply {
        text = nativeAd.body.orEmpty()
        visibility = if (nativeAd.body.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    (adView.callToActionView as? Button)?.apply {
        text = nativeAd.callToAction.orEmpty()
        visibility = if (nativeAd.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    (adView.iconView as? ImageView)?.apply {
        val drawable = nativeAd.icon?.drawable
        setImageDrawable(drawable)
        visibility = if (drawable == null) View.GONE else View.VISIBLE
    }

    adView.mediaView?.apply {
        mediaContent = nativeAd.mediaContent
        visibility = if (nativeAd.mediaContent == null) View.GONE else View.VISIBLE
    }

    adView.setNativeAd(nativeAd)
}

private fun Int.dpToPx(context: android.content.Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

@Composable
private fun NativeAdFallbackPlaceholder(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(30_000L)
        onRetry()
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Sponsored",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Ad unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
