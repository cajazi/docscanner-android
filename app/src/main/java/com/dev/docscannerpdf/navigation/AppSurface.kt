package com.dev.docscannerpdf.navigation

/**
 * The authoritative top-priority surface of the app's single-Activity UI. The guided ID-card
 * capture screen hosts a live CameraX session and therefore holds near-modal priority: while
 * [ID_CARD_CAPTURE] is active, NOTHING except app-lock and onboarding may replace it — a parent
 * branch replacing the mounted capture screen reconstructs the camera controller mid-visit
 * (two controllers, double bind, camera detach churn).
 */
enum class AppSurface {
    APP_LOCK,
    ONBOARDING,
    ID_CARD_CAPTURE,
    ID_CARD_REVIEW,
    OTHER
}

/**
 * Pure, single-decision route selector for the top of DocScannerApp's surface chain. By
 * construction, no state OUTSIDE these four inputs can influence the result — document lists,
 * preview state, filter state, camera support state, capture stage (FRONT/BACK), frame
 * geometry, and permission recomposition are simply not inputs, so none of them can ever
 * deselect [AppSurface.ID_CARD_CAPTURE] while it is active. Leaving capture happens ONLY
 * through its own flag: explicit exit (flag cleared) or review navigation (flag cleared +
 * review opened). [AppSurface.ID_CARD_REVIEW]/[AppSurface.OTHER] are informational for
 * diagnostics — the legacy branch chain below the top three continues to render them.
 */
fun resolveAppSurface(
    appLockActive: Boolean,
    showOnboarding: Boolean,
    showIdCardGuidedCapture: Boolean,
    idCardReviewOpen: Boolean
): AppSurface = when {
    appLockActive -> AppSurface.APP_LOCK
    showOnboarding -> AppSurface.ONBOARDING
    showIdCardGuidedCapture -> AppSurface.ID_CARD_CAPTURE
    idCardReviewOpen -> AppSurface.ID_CARD_REVIEW
    else -> AppSurface.OTHER
}
