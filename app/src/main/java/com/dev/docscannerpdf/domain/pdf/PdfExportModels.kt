package com.dev.docscannerpdf.domain.pdf

/**
 * Image role a searchable-PDF page is rendered from. The export prefers the backend
 * [ENHANCED] image and falls back to the [PROCESSED] image, mirroring the CamScanner-style
 * "best available scan" ordering required for the export flow.
 */
enum class PdfExportImageSource { ENHANCED, PROCESSED }

/**
 * Raw, per-page input for a searchable PDF export. Every value here originates from real
 * backend results or the unified result state — nothing is fabricated. [ocrText] is the
 * OCR already produced for the page (re-used, never re-run); a null/blank value simply
 * means the page carries no searchable text layer.
 */
data class PdfExportPageInput(
    val pageNumber: Int,
    val enhancedImageUrl: String? = null,
    val processedImageUrl: String? = null,
    val ocrText: String? = null
)

/**
 * A page resolved for rendering: a concrete image URL with the role it came from plus the
 * normalized (non-blank or null) OCR text to embed as the invisible searchable layer.
 */
data class PdfExportPagePlan(
    val pageNumber: Int,
    val imageUrl: String,
    val imageSource: PdfExportImageSource,
    val ocrText: String?
) {
    /** True when this page will receive an invisible, selectable OCR text layer. */
    val hasSearchableText: Boolean
        get() = !ocrText.isNullOrBlank()
}

/** Outcome of turning raw page inputs into a renderable plan. */
sealed interface PdfExportPlan {
    /** Pages are ready to render, ordered by page number. */
    data class Ready(val pages: List<PdfExportPagePlan>) : PdfExportPlan

    /** The request cannot produce a faithful PDF; [reason] is user-presentable. */
    data class Invalid(val reason: String) : PdfExportPlan
}

/**
 * Pure planning + layout logic for searchable PDF export. Kept free of Android framework
 * types so the image-selection, multi-page ordering, OCR-embedding, and error-handling
 * rules can be unit tested directly on the JVM, independent of [PdfExportService].
 */
object PdfExportPlanner {

    /**
     * Selects the image URL for a page: the enhanced image is preferred, the processed
     * image is the fallback. Blank values are skipped so an empty backend field never
     * wins over a usable one. Returns null when the page has no usable image at all.
     */
    fun selectImage(page: PdfExportPageInput): Pair<String, PdfExportImageSource>? {
        page.enhancedImageUrl?.takeIf { it.isNotBlank() }
            ?.let { return it to PdfExportImageSource.ENHANCED }
        page.processedImageUrl?.takeIf { it.isNotBlank() }
            ?.let { return it to PdfExportImageSource.PROCESSED }
        return null
    }

    /**
     * Builds a [PdfExportPlan] from raw page inputs. An empty request, or any page without
     * a usable enhanced/processed image, yields [PdfExportPlan.Invalid] with a clear reason
     * rather than silently producing a blank or partial PDF. Pages are ordered by page
     * number so multi-page documents render in sequence.
     */
    fun plan(pages: List<PdfExportPageInput>): PdfExportPlan {
        if (pages.isEmpty()) {
            return PdfExportPlan.Invalid("No pages are available to export.")
        }
        val resolved = ArrayList<PdfExportPagePlan>(pages.size)
        for (page in pages.sortedBy { it.pageNumber }) {
            val image = selectImage(page)
                ?: return PdfExportPlan.Invalid(
                    "Page ${page.pageNumber} has no enhanced or processed image to export."
                )
            resolved += PdfExportPagePlan(
                pageNumber = page.pageNumber,
                imageUrl = image.first,
                imageSource = image.second,
                ocrText = page.ocrText?.takeIf { it.isNotBlank() }
            )
        }
        return PdfExportPlan.Ready(resolved)
    }

    /**
     * Splits OCR text into render-ready lines for the invisible layer: existing line breaks
     * are honored and over-long lines are soft-wrapped on word boundaries (falling back to a
     * hard split for single words longer than [maxCharsPerLine]). Pure and deterministic so
     * the layer composition can be unit tested without a [android.graphics.Canvas].
     */
    fun layoutSearchableLines(text: String, maxCharsPerLine: Int): List<String> {
        require(maxCharsPerLine > 0) { "maxCharsPerLine must be positive" }
        val lines = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= maxCharsPerLine) {
                lines += trimmed
                continue
            }
            var current = StringBuilder()
            for (word in trimmed.split(Regex("\\s+"))) {
                var remaining = word
                // Hard-split words that cannot fit on a line on their own.
                while (remaining.length > maxCharsPerLine) {
                    if (current.isNotEmpty()) {
                        lines += current.toString()
                        current = StringBuilder()
                    }
                    lines += remaining.take(maxCharsPerLine)
                    remaining = remaining.drop(maxCharsPerLine)
                }
                if (remaining.isEmpty()) continue
                when {
                    current.isEmpty() -> current.append(remaining)
                    current.length + 1 + remaining.length <= maxCharsPerLine ->
                        current.append(' ').append(remaining)
                    else -> {
                        lines += current.toString()
                        current = StringBuilder(remaining)
                    }
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines
    }
}
