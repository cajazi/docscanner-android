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
 * Rec.601 saturation matrix: `s = 1` is identity, `s > 1` boosts chroma, `s = 0` is grayscale.
 * Neutral (r == g == b) pixels are mathematically unchanged for every `s`, so a saturation
 * boost can never introduce a color cast into gray or white regions.
 */
fun saturationColorMatrix(saturation: Float): FloatArray {
    val invR = 0.299f * (1f - saturation)
    val invG = 0.587f * (1f - saturation)
    val invB = 0.114f * (1f - saturation)
    return floatArrayOf(
        invR + saturation, invG, invB, 0f, 0f,
        invR, invG + saturation, invB, 0f, 0f,
        invR, invG, invB + saturation, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
}

// ENHANCE calibration (device-QA target: match CamScanner's brighter, more saturated ID-card
// look without washing out whites or crushing text). ALL tone mapping lives in one shouldered
// LUT — there is deliberately no linear contrast/brightness stacked on top, because a linear
// term multiplies highlights too and was what collapsed everything >= ~235 into pure white.
// Gamma history: 0.94 (with linear stack) was too dark, 0.86 over-corrected — device QA showed
// gray blacks and washed-out midtones. 0.91 is the retune: deeper darks, midtones still
// clearly brighter than Original. (The recommended 0.92 was band-checked first: it leaves
// 128 -> 135 and 64 -> 71, one below the agreed 136-142 / 72-76 floors; 0.91 lands every
// band with no extra shadow-contrast stage.) Neutral reference points through the LUT
// (saturation is identity on neutrals): 0 -> 0, 30 -> 36, 64 -> 72, 128 -> 136, 200 -> 203,
// 230 -> 231, 240 -> 240, 250 -> 250, 255 -> 255 — near-whites stay strictly ordered and
// only 255 maps to 255.
const val ENHANCE_GAMMA = 0.91f
const val ENHANCE_SHOULDER_START = 180
const val ENHANCE_SATURATION = 1.18f
const val ENHANCE_SHARPEN = 0.18f

/**
 * A 256-entry tone curve with a highlight-preserving shoulder: below [shoulderStart] it is the
 * plain gamma-[gamma] lift (shadows and midtones brighten); above it the lift fades LINEARLY to
 * zero at 255, so the curve tapers to identity at white — near-white values keep their
 * separation instead of collapsing into a 255 plateau, and pure white maps to exactly 255 while
 * black stays 0. Monotonic and deterministic; this is NOT a clamp — no input below 255 can
 * reach 255 through this curve. Pure JVM so the shipped table itself is unit-testable.
 */
fun shoulderedGammaLut(gamma: Float, shoulderStart: Int): IntArray {
    require(gamma > 0f) { "Gamma must be positive." }
    require(shoulderStart in 1..254) { "Shoulder must start inside the value range." }
    return IntArray(256) { value ->
        val lifted = 255.0 * Math.pow(value / 255.0, gamma.toDouble())
        val lift = lifted - value
        val weight = if (value <= shoulderStart) {
            1.0
        } else {
            (255.0 - value) / (255.0 - shoulderStart)
        }
        Math.round(value + lift * weight).toInt().coerceIn(0, 255)
    }
}

/** ENHANCE's shipped tone curve — see [shoulderedGammaLut] and the calibration notes above. */
fun enhanceToneLut(): IntArray = shoulderedGammaLut(ENHANCE_GAMMA, ENHANCE_SHOULDER_START)

/**
 * The app's shared document filter catalog: stable identifiers (the enum names), display names,
 * and deterministic recipes. Declaration order IS the catalog/picker order. This is the single
 * source of truth for user-selectable filters — currently consumed by the ID-card review flow,
 * and designed to be wired to the normal-document editor in a later slice (it is deliberately
 * not ID-card-specific).
 *
 * A recipe is at most one nonlinear [toneLut] pass (256-entry tone curve, applied first), one
 * 4x5 [colorMatrix] (row-major, RGBA rows, offsets in 0..255 space), and one sharpen
 * convolution of [sharpenStrength] (0f = none) — all applied by [DocumentFilterRenderer] via
 * [DocumentFilterPrimitives]. [ORIGINAL] is pure identity: no LUT, no matrix, no sharpen, and
 * renderers must return the source untouched without writing any file. All recipe data here is
 * plain numbers so the exact specifications are unit-testable on the JVM with no Android
 * dependency.
 */
enum class DocumentFilter(
    val displayName: String,
    /** 4x5 row-major ColorMatrix, or null when the recipe has no color transform. */
    val colorMatrix: FloatArray?,
    /** Strength for the shared sharpen convolution ([DocumentFilterPrimitives.applySharpen]); 0f disables it. */
    val sharpenStrength: Float,
    /** 256-entry tone curve applied to RGB BEFORE the color matrix; null disables it. */
    val toneLut: IntArray? = null
) {
    /** Identity/reset: display the unfiltered base image directly. */
    ORIGINAL("Original", null, 0f),

    /**
     * CamScanner-calibrated enhance: the shouldered tone curve ([enhanceToneLut] — gamma
     * [ENHANCE_GAMMA] shadow/midtone lift fading to identity at white, so near-white detail
     * survives), then Rec.601 saturation x[ENHANCE_SATURATION] (identity on neutrals — the
     * matrix cannot clip or cast grays/whites), then the existing mild [ENHANCE_SHARPEN]
     * sharpen. Replaces the old contrast-pivot recipe, whose mid-gray pivot DARKENED every
     * pixel below 128, and the interim linear contrast+brightness stack, which collapsed
     * everything >= ~235 into pure white.
     */
    ENHANCE("Enhance", saturationColorMatrix(ENHANCE_SATURATION), ENHANCE_SHARPEN, enhanceToneLut()),

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
    val isIdentity: Boolean get() = colorMatrix == null && sharpenStrength == 0f && toneLut == null

    companion object {
        /** The user-facing catalog, in exact picker order. */
        val CATALOG: List<DocumentFilter> = entries.toList()
    }
}
