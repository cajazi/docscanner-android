package com.dev.docscannerpdf.ui.idcard

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Forces a dark status/navigation bar (black background, light icons) for the duration this
 * composable is in the composition, matching CamScanner's dark ID-card entry/capture/review
 * chrome instead of the app's normal light system bars. The original bar colors and icon
 * appearance are restored on dispose, so leaving an ID-card screen never leaks a dark status bar
 * into the rest of the app. A no-op outside a real [Activity] (e.g. preview/edit-mode).
 */
@Composable
fun DarkSystemBarsEffect() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context as? Activity ?: return
    DisposableEffect(Unit) {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)
        val originalStatusBarColor = window.statusBarColor
        val originalNavigationBarColor = window.navigationBarColor
        val originalLightStatusBars = insetsController.isAppearanceLightStatusBars
        val originalLightNavigationBars = insetsController.isAppearanceLightNavigationBars

        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        onDispose {
            window.statusBarColor = originalStatusBarColor
            window.navigationBarColor = originalNavigationBarColor
            insetsController.isAppearanceLightStatusBars = originalLightStatusBars
            insetsController.isAppearanceLightNavigationBars = originalLightNavigationBars
        }
    }
}
