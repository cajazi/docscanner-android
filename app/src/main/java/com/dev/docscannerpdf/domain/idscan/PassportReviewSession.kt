package com.dev.docscannerpdf.domain.idscan

import android.graphics.Bitmap
import com.dev.docscannerpdf.domain.filter.DocumentFilter

/**
 * The four effects that compose the passport preview and the final saved image: crop (the
 * geometric shape of the page), rotation (90/180/270), filter (color), and watermark (overlay).
 * The in-memory preview applies them in exactly this order; the authoritative JPEG pipeline
 * applies filter before rotation (it renders the filter from the canonical base, then bakes the
 * rotation from the filtered page). Both land on the same pixels because filter and rotation
 * commute for every recipe in the grid — see [PassportPreviewRenderer] for why — and the watermark
 * is applied last in both, so "what you see is what is saved" holds by construction. Pure JVM, no
 * Android types beyond the [Bitmap] reference already required for any in-memory image work.
 */
data class PassportEffectChain(
    val crop: PassportCropRect = PassportCropRect.FULL,
    val rotationQuarterTurns: Int = 0,
    val filter: DocumentFilter = DocumentFilter.ORIGINAL,
    /** The text the user set for the watermark, or null when no watermark is requested. */
    val watermarkText: String? = null
) {
    /** True when no effect would change the displayed image — the trivial identity case. */
    val isIdentity: Boolean
        get() = crop == PassportCropRect.FULL &&
            rotationQuarterTurns == 0 &&
            filter == DocumentFilter.ORIGINAL &&
            watermarkText.isNullOrBlank()

    companion object {
        /**
         * Maps the review state's requested rotation in degrees (0/90/180/270, but robust to any
         * multiple of 90) to the 0..3 quarter-turn count the preview renderer applies. Four
         * 90° rotations are exactly 0 turns — the preview returns to the original orientation.
         */
        fun quarterTurns(degrees: Int): Int = (((degrees / 90) % 4) + 4) % 4
    }
}

/**
 * The cached in-memory state for one active passport review session. Holds the downscaled base
 * bitmap (decoded once per base generation, never per edit), the current interactive preview
 * bitmap, and the two monotonic generation tokens used to drop stale preview / final results.
 *
 * The cache is deliberately NOT in [PassportReviewState] (which is framework-free and pure
 * JVM so its reducer is unit-testable) — it lives here, behind a thin façade the viewmodel
 * owns. Bitmaps are NEVER recycled by this class: callers are responsible for ensuring Compose
 * no longer references a bitmap before recycling. The viewmodel releases to the GC only when
 * it is certain Compose has dropped the reference (e.g. after a navigation transition that
 * leaves the review).
 *
 * The authoritative URI is stored in [PassportReviewState] — the cache mirrors it for the
 * preview pipeline only and is never read by the save path. Saved pixels are always the
 * [com.dev.docscannerpdf.domain.idscan.PassportReviewState.displayedUri] JPEG.
 */
class PassportReviewSession {

    /** The base generation: bumped only when a NEW base is installed (review open or crop settle). */
    var baseGeneration: Int = 0
        private set

    /** The downscaled base bitmap for the current [baseGeneration], or null before the first decode. */
    var downscaledBase: Bitmap? = null
        private set

    /** The preview bitmap currently shown (from the in-memory pipeline). */
    var currentPreview: Bitmap? = null
        private set

    /** The currently requested effect chain — crop/rotation/filter/watermark. */
    var requestedChain: PassportEffectChain = PassportEffectChain()
        private set

    /** Monotonic token for preview publication. Each new op increments before launching. */
    var previewGeneration: Int = 0
        private set

    /** Monotonic token for authoritative final render publication. */
    var finalRenderGeneration: Int = 0
        private set

    /**
     * Installs a new authoritative base. Bumps [baseGeneration] and clears [downscaledBase] so
     * the next preview op decodes the new base exactly once. Called when the review opens with
     * a freshly captured page and after a crop Apply settles.
     */
    fun installBase() {
        baseGeneration++
        downscaledBase = null
        currentPreview = null
    }

    /**
     * Stores the just-decoded downscaled base for the current [baseGeneration]. Idempotent for
     * a given generation — repeated calls with the same generation overwrite the cached value
     * (a crop-settled base will replace the pre-crop one).
     */
    fun storeDownscaledBase(bitmap: Bitmap) {
        downscaledBase = bitmap
    }

    fun updateRequestedChain(chain: PassportEffectChain) {
        requestedChain = chain
    }

    fun beginPreview(): Int {
        previewGeneration++
        return previewGeneration
    }

    fun beginFinal(): Int {
        finalRenderGeneration++
        return finalRenderGeneration
    }

    /**
     * Returns true when [generation] still owns the current preview publication slot — i.e.
     * no newer preview op has started. The bus uses this to drop stale completions.
     */
    fun isPreviewCurrent(generation: Int): Boolean = generation == previewGeneration

    /**
     * Returns true when [generation] still owns the current final publication slot — i.e. no
     * newer final render has started. A superseded final result must be deleted, not shown.
     */
    fun isFinalCurrent(generation: Int): Boolean = generation == finalRenderGeneration

    fun storePreview(bitmap: Bitmap) {
        currentPreview = bitmap
    }

    /**
     * Drops every cached bitmap. Call ONLY when Compose has stopped drawing the current preview
     * (e.g. navigation away from the review, or a review cancel). Never call this in the middle
     * of a recomposition that may still be drawing the bitmap. This method does not call
     * [Bitmap.recycle] — it relies on the GC, per the brief's "do not recycle a Bitmap while
     * Compose may still display it" rule.
     */
    fun clear() {
        downscaledBase = null
        currentPreview = null
    }
}
