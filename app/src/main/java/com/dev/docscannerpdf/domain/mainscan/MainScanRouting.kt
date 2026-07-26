package com.dev.docscannerpdf.domain.mainscan

/** Which scanner the Dashboard's primary Scan action opens. Typed, so neither can be a boolean. */
enum class PrimaryScanTarget {
    /** The app-owned Main Scanner (clean preview, manual shutter, dedicated crop workflow). */
    MAIN_SCANNER,

    /** The existing ML Kit document scanner — the intact, still-callable fallback path. */
    ML_KIT
}

/**
 * The single routing decision for the primary Scan action, keyed on BUILD TYPE:
 *
 *     debug   -> PrimaryScanTarget.MAIN_SCANNER
 *     release -> PrimaryScanTarget.ML_KIT
 *
 * The app-owned Main Scanner is being built in slices against `docs/main-scanner-reference.md` and
 * cannot yet save a document, so it must never be what a release build opens. Binding the decision
 * to `BuildConfig.DEBUG` makes that deterministic: there is no constant to flip, no stored
 * preference, no user-facing scanner-selection setting, and no remote-config surface — a release
 * binary routes to ML Kit as a property of how it was built.
 *
 * [isDebugBuild] is the ONLY input, on purpose. No document state, no device capability, and no
 * feature flag participates, so nothing observable at runtime can move a release build onto the
 * incomplete flow. The one production call site passes `BuildConfig.DEBUG` directly, and
 * `MainScanRoutingContractTest` asserts against the real source that no other call site exists.
 *
 * When the Main Scanner reaches approved parity, this function becomes unconditional and the ML Kit
 * path is removed — until then `MainActivity.startDocumentScanner` stays intact and callable.
 */
object MainScanRouting {

    fun primaryScanTarget(isDebugBuild: Boolean): PrimaryScanTarget =
        if (isDebugBuild) PrimaryScanTarget.MAIN_SCANNER else PrimaryScanTarget.ML_KIT
}
