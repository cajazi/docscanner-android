package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad

/**
 * The document corners FROZEN at the moment a shutter tap was accepted, bound to the capture that
 * produced them.
 *
 * Freezing is the point. The detector keeps running while the still is being taken, so by the time
 * the JPEG lands the live quad has already moved — using "the latest quad" would seed the crop with
 * corners the user never saw, for a frame that no longer exists. The seed is captured once, at
 * acceptance, and is immutable thereafter.
 *
 * [sessionId] and [generation] are copied from the accepting [MainScanCaptureTicket], so a seed can
 * be matched to exactly one capture. [quad] is in ROTATED normalized space (space 4 of
 * [MainScanDetectionMapper]) — the last space provably shared between what the user saw and what the
 * capture contains. [analysisFrame] and [rotationDegrees] travel with it because Slice 3 needs them
 * to relate these corners to the full-resolution still.
 */
data class MainScanCropSeed(
    val sessionId: Long,
    val generation: Long,
    val quad: PerspectiveQuad,
    val analysisFrame: FrameSize,
    val rotationDegrees: Int,
    val timestampMs: Long
) {
    /** True when this seed belongs to exactly [ticket] — both visit and capture must match. */
    fun matches(ticket: MainScanCaptureTicket): Boolean =
        sessionId == ticket.sessionId && generation == ticket.generation
}

/**
 * Pure rules for producing and consuming a crop seed.
 *
 * The governing principle is that a WRONG seed is worse than no seed. A crop opened on corners that
 * do not match the photograph silently mis-frames the document, and the user may not notice until
 * the page is saved. So every path that cannot be proven correct resolves to the full frame, which
 * is always safe and always obviously editable.
 */
object MainScanCropSeeding {

    /**
     * Freezes [result] against [ticket], or returns null when the crop must open full-frame.
     *
     * TWO conditions are required, and the second is the important one:
     *
     * 1. [result] passes [MainScanGuideEligibility] — it is a convex, large-enough, in-bounds,
     *    confident, stable quad.
     * 2. [guideVisible] — the debounced guide is ACTUALLY ON SCREEN.
     *
     * Eligibility alone is not sufficient, because eligibility is a per-frame verdict while
     * visibility requires [MainScanGuideVisibilityReducer.FRAMES_TO_SHOW] consecutive eligible
     * frames. A shutter tap landing on the FIRST eligible frame would otherwise freeze corners that
     * were never drawn — the crop would open on a boundary the user had no opportunity to see or
     * reject, which is precisely the silent mis-framing this flow must not do. Requiring visibility
     * makes the guarantee literal: the crop opens on the guide the user was looking at.
     *
     * It also raises the bar against transient false positives. A high-contrast non-document scene
     * that happens to satisfy the geometry for a single frame can no longer seed anything; it must
     * hold the detection long enough to be displayed first.
     */
    fun freeze(
        ticket: MainScanCaptureTicket,
        result: MainScanAnalysisResult?,
        guideVisible: Boolean,
        timestampMs: Long
    ): MainScanCropSeed? {
        if (!guideVisible) return null
        if (!MainScanGuideEligibility.isEligible(result)) return null
        val eligible = result ?: return null
        val quad = eligible.quad ?: return null
        return MainScanCropSeed(
            sessionId = ticket.sessionId,
            generation = ticket.generation,
            quad = quad,
            analysisFrame = eligible.analysisFrame,
            rotationDegrees = eligible.rotationDegrees,
            timestampMs = timestampMs
        )
    }

    /**
     * The quad the crop surface should open on for [ticket]: the frozen seed when it belongs to
     * exactly this capture, otherwise the full frame.
     *
     * Every mismatch resolves to full frame, and each is a real scenario rather than defensive
     * padding: a seed from an earlier visit (the user backed out and re-entered), a seed from a
     * superseded capture in the same visit (a retake), and no seed at all (nothing was detected, or
     * the detection never stabilised). In all three the capture itself still succeeds — the shutter
     * is never gated on detection.
     */
    fun resolveQuad(seed: MainScanCropSeed?, ticket: MainScanCaptureTicket): PerspectiveQuad =
        if (seed != null && seed.matches(ticket)) seed.quad else PerspectiveQuad.full()

    /** True when [ticket] will open on real detected corners rather than the full-frame fallback. */
    fun hasUsableSeed(seed: MainScanCropSeed?, ticket: MainScanCaptureTicket): Boolean =
        seed != null && seed.matches(ticket)
}
