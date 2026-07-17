package com.dev.docscannerpdf.domain.idscan

/**
 * The strict, orientation-independent UHD ("4K") floor every accepted ID-card CAMERA capture
 * must meet, applied to the RAW CameraX JPEG before guide-frame cropping (the baked card is a
 * crop of these pixels and is legitimately smaller). Orientation cannot matter — a portrait
 * sensor writes 3000x4000 for the same optics that write 4000x3000 — so the predicate compares
 * the longer edge against 3840 and the shorter edge against 2160:
 *
 * - 3840x2160, 2160x3840, 4000x3000, 3000x4000 -> pass
 * - 3264x2448 (8MP-class), 3839x2160, 3840x2159 -> fail
 *
 * Pure JVM so the policy is directly unit-testable; `IdCardRawCaptureInspector` applies it to
 * real capture files. Gallery imports are exempt — the user didn't shoot those through our
 * camera, so their resolution is whatever their source had.
 */
const val UHD_LONG_EDGE_MIN = 3840
const val UHD_SHORT_EDGE_MIN = 2160

fun meetsUhdCaptureRequirement(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val longEdge = maxOf(width, height)
    val shortEdge = minOf(width, height)
    return longEdge >= UHD_LONG_EDGE_MIN && shortEdge >= UHD_SHORT_EDGE_MIN
}

/** One camera JPEG output size. Orientation-independent helpers for the UHD selection policy. */
data class CaptureSize(val width: Int, val height: Int) {
    val pixelArea: Long get() = width.toLong() * height

    /** True for 4:3 in either orientation (4000x3000 and 3000x4000 both qualify). */
    val isFourByThree: Boolean
        get() = maxOf(width, height) * 3 == minOf(width, height) * 4

    val meetsUhd: Boolean get() = meetsUhdCaptureRequirement(width, height)
}

/**
 * The UHD capture-size policy, applied to the camera's REAL Camera2 JPEG capability list (never
 * inferred from advertised megapixels): keep only orientation-independent UHD-qualifying sizes,
 * prefer the 4:3 group (a sensor's largest stills are 4:3 — e.g. 4080x3060 beats 4080x2296),
 * and take the highest pixel area within the preferred group, falling back to the highest-area
 * qualifying size of any aspect when no 4:3 candidate exists. Returns null ONLY when the camera
 * exposes no UHD JPEG size at all — the caller then disables strict-4K camera capture up front
 * instead of letting the user shoot captures that can never pass. Deterministic: area, then
 * width, breaks ties.
 */
fun chooseUhdCaptureSize(supportedSizes: List<CaptureSize>): CaptureSize? {
    val qualifying = supportedSizes.filter { it.meetsUhd }
    if (qualifying.isEmpty()) return null
    val preferredPool = qualifying.filter { it.isFourByThree }.ifEmpty { qualifying }
    return preferredPool.maxWithOrNull(compareBy({ it.pixelArea }, { it.width }))
}

/**
 * Candidate ordering for the CameraX ResolutionFilter: every sub-UHD size is REMOVED and the
 * [chooseUhdCaptureSize] winner is placed first (remaining qualifiers follow by descending
 * area) — CameraX must not silently fall back below UHD when the hardware can do better. When
 * NO size qualifies, the input is returned unchanged: the preflight has already disabled the
 * strict-4K shutter on such a device, and mangling the list here would only break the shared
 * preview binding.
 */
fun orderCaptureSizesForUhd(supportedSizes: List<CaptureSize>): List<CaptureSize> {
    val chosen = chooseUhdCaptureSize(supportedSizes) ?: return supportedSizes
    val rest = supportedSizes
        .filter { it.meetsUhd && it != chosen }
        .sortedWith(compareByDescending<CaptureSize> { it.pixelArea }.thenByDescending { it.width })
    return listOf(chosen) + rest
}

/**
 * Combines a camera's NORMAL JPEG output list with its HIGH-RESOLUTION JPEG output list
 * (Camera2 splits them — on the SM-A165F the 4080x3060/4080x2296 stills live ONLY in the
 * high-resolution list, which is exactly what CameraX's PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
 * mode unlocks; inspecting only the normal list produced a false "unsupported" preflight).
 * Null-safe for cameras that report no high-resolution list, de-duplicated, and
 * deterministically ordered by descending pixel area (then width).
 */
fun combineCaptureSizeLists(
    normalSizes: List<CaptureSize>,
    highResolutionSizes: List<CaptureSize>?
): List<CaptureSize> =
    (normalSizes + highResolutionSizes.orEmpty())
        .distinct()
        .sortedWith(compareByDescending<CaptureSize> { it.pixelArea }.thenByDescending { it.width })

/**
 * The strict-4K shutter's support state. Camera2 metadata only ORDERS candidates; the
 * resolution CameraX actually ATTACHES after binding is the sole authority for enabling or
 * disabling capture (see [resolveUhdSupportState]) — metadata can be incomplete, but a bound
 * 4080x3060 ImageCapture is proof.
 */
enum class UhdSupportState {
    /** No authoritative bind result yet — shutter disabled, no warning shown. */
    CHECKING,

    /** CameraX attached a UHD-qualifying still resolution — shutter enabled. */
    SUPPORTED,

    /** CameraX attached a sub-UHD still resolution — shutter disabled with explanation. */
    UNSUPPORTED,

    /** Binding/configuration failed — shutter disabled; a later successful bind can recover. */
    ERROR
}

/**
 * Resolves the authoritative support state from a completed CameraX bind: [bindFailed] wins
 * (ERROR), an unknown attached resolution stays [UhdSupportState.CHECKING] (never falsely
 * claims either way), and otherwise the same orientation-independent UHD predicate used for
 * raw-capture validation decides SUPPORTED vs UNSUPPORTED. Publishing SUPPORTED must clear any
 * stale unsupported warning immediately — the early metadata preflight may never permanently
 * disable capture once a real UHD attachment is proven.
 */
fun resolveUhdSupportState(
    attachedWidth: Int?,
    attachedHeight: Int?,
    bindFailed: Boolean
): UhdSupportState = when {
    bindFailed -> UhdSupportState.ERROR
    attachedWidth == null || attachedHeight == null -> UhdSupportState.CHECKING
    meetsUhdCaptureRequirement(attachedWidth, attachedHeight) -> UhdSupportState.SUPPORTED
    else -> UhdSupportState.UNSUPPORTED
}

/**
 * The shutter-tap guard policy, in guard order: returns a deterministic rejection reason (used
 * verbatim in the debug `ID_CARD_CAPTURE_CLICK rejected reason=...` log) or null when the tap
 * may proceed to gate acquisition and capture submission. Pure so every rejection path is
 * unit-testable — a mystery pre-capture rejection like the line-296 report becomes a single
 * logged reason instead.
 */
fun captureClickRejection(
    supportState: UhdSupportState,
    isProcessing: Boolean,
    gateBusy: Boolean
): String? = when {
    supportState != UhdSupportState.SUPPORTED -> "support_state_${supportState.name}"
    isProcessing -> "processing"
    gateBusy -> "gate_busy"
    else -> null
}
