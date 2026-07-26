package com.dev.docscannerpdf.ui.idcard

/**
 * The DocScanner screen that OWNS a CameraX controller instance. Every [IdCardCameraController] is
 * constructed with exactly one owner, passed EXPLICITLY from its call site. Ownership is never
 * inferred from a title, a label, or the current (mutable) navigation route — so the controller
 * created by the ID Cards entry preview can never be mistaken, in the logs, for the separate
 * controller the Passport capture screen owns. Different screens legitimately own different
 * controllers; the owner tag is what proves that in evidence.
 */
enum class CameraSurfaceOwner {
    /** The ID Cards entry screen's dimmed live-preview backdrop (ScannerDashboardScreen). */
    ID_CARDS_ENTRY,

    /** The guided ID-card (front then back) capture screen. */
    ID_CARD_GUIDED_CAPTURE,

    /** The guided single-page Passport capture screen. */
    PASSPORT_GUIDED_CAPTURE,

    /** The app-owned Main Scanner document capture screen (clean preview, manual shutter). */
    MAIN_SCAN_CAPTURE,

    /** Any other camera surface not enumerated above. */
    OTHER
}

/**
 * Pure, content-free formatters for CameraX lifecycle diagnostics. Every line embeds the owning
 * [CameraSurfaceOwner] and a stable controller id — and NOTHING else. No image paths, document
 * data, or personal information can ever pass through here because the functions accept only an
 * owner enum, a controller id, and a fixed bind reason. Kept pure (String in / String out) so the
 * exact debug-log contract is unit-testable on the JVM without a device or CameraX.
 *
 * All callers gate these behind `BuildConfig.DEBUG`; the strings themselves are local-only.
 */
object CameraOwnershipLog {

    fun created(owner: CameraSurfaceOwner, controllerId: String): String =
        "CAMERA_CONTROLLER owner=$owner created=$controllerId"

    fun bind(owner: CameraSurfaceOwner, reason: String, controllerId: String): String =
        "CAMERA_BIND owner=$owner reason=$reason controller=$controllerId"

    fun released(owner: CameraSurfaceOwner, controllerId: String): String =
        "CAMERA_CONTROLLER owner=$owner released=$controllerId"

    /**
     * Truthful replacement for the old, misleading single-boolean host line: it reports EACH
     * capture surface's visibility independently, so "surface=PASSPORT_CAPTURE" never appears next
     * to an ID-card-only "captureVisible=false".
     */
    fun host(
        surface: String,
        idCardCaptureVisible: Boolean,
        passportCaptureVisible: Boolean,
        mainScanCaptureVisible: Boolean = false
    ): String =
        "CAMERA_HOST surface=$surface " +
            "idCardCaptureVisible=$idCardCaptureVisible " +
            "passportCaptureVisible=$passportCaptureVisible " +
            "mainScanCaptureVisible=$mainScanCaptureVisible"
}
