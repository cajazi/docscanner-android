package com.dev.docscannerpdf.domain.filter

import java.util.UUID

/**
 * Collision-proof output filename for one rendered [filter] result. Uniqueness comes from a
 * random UUID — NOT from a timestamp: front and back renders run concurrently into the same
 * directory and can share the same millisecond, which silently overwrote one side's output.
 * The lowercase filter identifier stays in the name purely for human readability when
 * inspecting the app's private files. Pure JVM (no Android types) so it is directly testable.
 */
fun filterOutputFileName(filter: DocumentFilter): String =
    "filter-${filter.name.lowercase()}-${UUID.randomUUID()}.jpg"

/**
 * Builds the 4x5 row-major ColorMatrix for a plain contrast adjustment: each RGB channel is
 * scaled by [multiplier] around the mid-gray point, i.e. offset `(-0.5 * multiplier + 0.5) * 255`.
 * Top-level (not a companion member) so [DocumentFilter] enum entries can use it in their own
 * initializers, and public so tests can assert recipes against the exact same math.
 */
fun contrastColorMatrix(multiplier: Float): FloatArray {
    val offset = (-0.5f * multiplier + 0.5f) * 255f
    return floatArrayOf(
        multiplier, 0f, 0f, 0f, offset,
        0f, multiplier, 0f, 0f, offset,
        0f, 0f, multiplier, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )
}

/**
 * The app's shared document filter catalog: stable identifiers (the enum names), display names,
 * and deterministic recipes. Declaration order IS the catalog/picker order. This is the single
 * source of truth for user-selectable filters — currently consumed by the ID-card review flow,
 * and designed to be wired to the normal-document editor in a later slice (it is deliberately
 * not ID-card-specific).
 *
 * A recipe is at most one 4x5 [colorMatrix] (row-major, RGBA rows, offsets in 0..255 space)
 * followed by one sharpen convolution of [sharpenStrength] (0f = none) — both applied by
 * [DocumentFilterRenderer] via [DocumentFilterPrimitives]. [ORIGINAL] is pure identity: no
 * matrix, no sharpen, and renderers must return the source untouched without writing any file.
 * All recipe data here is plain floats so the exact specifications are unit-testable on the JVM
 * with no Android dependency.
 */
enum class DocumentFilter(
    val displayName: String,
    /** 4x5 row-major ColorMatrix, or null when the recipe has no color transform. */
    val colorMatrix: FloatArray?,
    /** Strength for the shared sharpen convolution ([DocumentFilterPrimitives.applySharpen]); 0f disables it. */
    val sharpenStrength: Float
) {
    /** Identity/reset: display the unfiltered base image directly. */
    ORIGINAL("Original", null, 0f),

    /**
     * Balanced contrast + sharpening. The numbers are exactly
     * [com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor.Config]'s defaults (contrast
     * 1.12, sharpen 0.18) so the default ID-card appearance is visually unchanged from the old
     * destructive auto-enhance.
     */
    ENHANCE("Enhance", contrastColorMatrix(1.12f), 0.18f),

    /** Moderate brightening: +25 per RGB channel. */
    BRIGHTNESS(
        "Brightness",
        floatArrayOf(
            1f, 0f, 0f, 0f, 25f,
            0f, 1f, 0f, 0f, 25f,
            0f, 0f, 1f, 0f, 25f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    ),

    /** Moderate contrast increase: x1.25 around mid-gray (offset -31.875). */
    CONTRAST("Contrast", contrastColorMatrix(1.25f), 0f),

    /** Sharpening only — deliberately no color or brightness change. */
    SHARPEN("Sharpen", null, 0.35f),

    /**
     * High-contrast black & white: Rec.601 grayscale pre-multiplied with a x1.55 contrast
     * boost (each gray coefficient x1.55, offset (-0.5*1.55+0.5)*255 = -70.125).
     */
    BW(
        "B&W",
        floatArrayOf(
            0.4635f, 0.9099f, 0.1767f, 0f, -70.125f,
            0.4635f, 0.9099f, 0.1767f, 0f, -70.125f,
            0.4635f, 0.9099f, 0.1767f, 0f, -70.125f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    ),

    /** Standard deterministic sepia matrix. */
    SEPIA(
        "Sepia",
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    ),

    /** Standard Rec.601 luminance grayscale. */
    GRAY(
        "Gray",
        floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    ),

    /** Warmer color temperature: lift red, drop blue. */
    WARM(
        "Warm",
        floatArrayOf(
            1.10f, 0f, 0f, 0f, 0f,
            0f, 1.03f, 0f, 0f, 0f,
            0f, 0f, 0.88f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    ),

    /** Cooler color temperature: drop red, lift blue. */
    COOL(
        "Cool",
        floatArrayOf(
            0.88f, 0f, 0f, 0f, 0f,
            0f, 1.02f, 0f, 0f, 0f,
            0f, 0f, 1.10f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        0f
    );

    /** True when the recipe changes no pixels — renderers must pass the source through untouched. */
    val isIdentity: Boolean get() = colorMatrix == null && sharpenStrength == 0f

    companion object {
        /** The user-facing catalog, in exact picker order. */
        val CATALOG: List<DocumentFilter> = entries.toList()
    }
}
