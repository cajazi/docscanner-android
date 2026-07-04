package com.dev.docscannerpdf.domain.pdf

/** Which side of an ID card an image represents when laid out on one A4 export page. */
enum class IdCardSide { FRONT, BACK }

/**
 * Raw input for an ID-card front/back export. The front image is required; the back image is
 * optional and only present when the user captured a second side. [ocrText] always comes from
 * the front image — front/back capture does not change OCR behavior.
 */
data class IdCardPdfInput(
    val frontImageUrl: String?,
    val backImageUrl: String? = null,
    val ocrText: String? = null
)

/** A single resolved side, ready to be drawn into its slot on the export page. */
data class IdCardSidePlan(val side: IdCardSide, val imageUrl: String)

/** Outcome of planning an ID-card front/back export. */
sealed interface IdCardPdfPlan {
    /** [sides] is ordered front-first, back second when present. */
    data class Ready(val sides: List<IdCardSidePlan>, val ocrText: String?) : IdCardPdfPlan

    /** The request cannot produce a faithful export; [reason] is user-presentable. */
    data class Invalid(val reason: String) : IdCardPdfPlan
}

/**
 * Pure planning logic for an ID-card front/back export: resolves which sides are present and
 * orders them front-first, back-second. Kept free of Android framework types so it can be unit
 * tested directly on the JVM, independent of [PdfExportService].
 */
object IdCardPdfPlanner {

    /**
     * Builds an [IdCardPdfPlan] from [input]. A blank/missing front image yields
     * [IdCardPdfPlan.Invalid] since an ID card always needs at least a front. A blank/missing
     * back image is not an error — the plan is simply front-only, matching a single-sided scan.
     */
    fun plan(input: IdCardPdfInput): IdCardPdfPlan {
        val front = input.frontImageUrl?.takeIf { it.isNotBlank() }
            ?: return IdCardPdfPlan.Invalid("No front image is available to export.")
        val back = input.backImageUrl?.takeIf { it.isNotBlank() }
        val sides = buildList {
            add(IdCardSidePlan(IdCardSide.FRONT, front))
            if (back != null) add(IdCardSidePlan(IdCardSide.BACK, back))
        }
        return IdCardPdfPlan.Ready(
            sides = sides,
            ocrText = input.ocrText?.takeIf { it.isNotBlank() }
        )
    }
}
