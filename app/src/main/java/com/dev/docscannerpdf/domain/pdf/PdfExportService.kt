package com.dev.docscannerpdf.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.dev.docscannerpdf.network.NetworkClient
import com.dev.docscannerpdf.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Produces a CamScanner-style searchable PDF from already-processed document pages.
 *
 * Each page is rendered from its real backend image (enhanced preferred, processed as the
 * fallback) onto an A4 page, with the page's existing OCR text drawn as a fully transparent
 * — therefore invisible but selectable/searchable — text layer on top. No backend calls
 * re-run OCR or processing here: the service only fetches the bytes of images that already
 * exist and re-uses OCR text resolved earlier in the flow.
 *
 * All decision logic (image selection, ordering, validation) lives in [PdfExportPlanner],
 * which is unit tested independently; this class is the thin Android rendering shell.
 */
class PdfExportService(
    private val context: Context,
    private val imageLoader: PdfImageLoader = HttpPdfImageLoader(context),
    private val outputDirectory: File = File(context.filesDir, "searchable_pdfs")
) {

    sealed interface Result {
        /** [uri] is a shareable FileProvider URI; [file] is the on-disk PDF. */
        data class Success(val uri: Uri, val file: File, val pageCount: Int) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Renders [pages] into a single searchable PDF named after [fileName], returning the
     * generated file's URI. Validation and image-fetch failures are surfaced as
     * [Result.Failure] rather than thrown, so callers can show a single clear message.
     */
    suspend fun export(
        pages: List<PdfExportPageInput>,
        fileName: String
    ): Result = withContext(Dispatchers.IO) {
        when (val plan = PdfExportPlanner.plan(pages)) {
            is PdfExportPlan.Invalid -> Result.Failure(plan.reason)
            is PdfExportPlan.Ready -> runCatching { render(plan.pages, fileName) }
                .getOrElse { throwable ->
                    Result.Failure(throwable.message ?: "Unable to export searchable PDF.")
                }
        }
    }

    private suspend fun render(pages: List<PdfExportPagePlan>, fileName: String): Result {
        if (!outputDirectory.exists()) outputDirectory.mkdirs()
        val outputFile = uniqueFile(outputDirectory, sanitizeFileName(fileName))
        val pdfDocument = PdfDocument()
        try {
            pages.forEach { page ->
                val bitmap = imageLoader.load(page.imageUrl)
                    ?: throw IllegalStateException(
                        "Unable to load the image for page ${page.pageNumber}."
                    )
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        AppConstants.A4_WIDTH_POINTS,
                        AppConstants.A4_HEIGHT_POINTS,
                        page.pageNumber
                    ).create()
                    val pdfPage = pdfDocument.startPage(pageInfo)
                    drawImageOnA4Page(pdfPage.canvas, bitmap)
                    page.ocrText?.let { drawInvisibleTextLayer(pdfPage.canvas, it) }
                    pdfDocument.finishPage(pdfPage)
                } finally {
                    bitmap.recycle()
                }
            }
            outputFile.outputStream().use { output -> pdfDocument.writeTo(output) }
        } finally {
            pdfDocument.close()
        }

        val uri = fileProviderUri(outputFile) ?: Uri.fromFile(outputFile)
        return Result.Success(uri = uri, file = outputFile, pageCount = pages.size)
    }

    /** Scales [bitmap] to fit a centered A4 page on a white background. */
    private fun drawImageOnA4Page(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.WHITE)
        val pageWidth = AppConstants.A4_WIDTH_POINTS.toFloat()
        val pageHeight = AppConstants.A4_HEIGHT_POINTS.toFloat()
        val scale = minOf(
            pageWidth / bitmap.width.toFloat(),
            pageHeight / bitmap.height.toFloat()
        )
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val left = (pageWidth - imageWidth) / 2f
        val top = (pageHeight - imageHeight) / 2f
        val destination = android.graphics.RectF(left, top, left + imageWidth, top + imageHeight)
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    /**
     * Draws [text] across the page with a fully transparent paint. Android's [PdfDocument]
     * still records the glyphs into the PDF content stream, so the text is invisible on
     * screen yet remains selectable and searchable — the standard invisible-OCR-layer trick.
     */
    private fun drawInvisibleTextLayer(canvas: Canvas, text: String) {
        val paint = Paint().apply {
            color = Color.argb(0, 0, 0, 0)
            textSize = SEARCHABLE_TEXT_SIZE
            isAntiAlias = true
        }
        val margin = SEARCHABLE_TEXT_MARGIN
        val usableWidth = AppConstants.A4_WIDTH_POINTS - margin * 2f
        // Estimate how many characters fit per line from the average glyph width.
        val averageCharWidth = paint.measureText("M").coerceAtLeast(1f)
        val maxCharsPerLine = (usableWidth / averageCharWidth).toInt().coerceAtLeast(1)
        val lineHeight = paint.fontSpacing
        val bottomLimit = AppConstants.A4_HEIGHT_POINTS - margin

        var y = margin + lineHeight
        for (line in PdfExportPlanner.layoutSearchableLines(text, maxCharsPerLine)) {
            if (y > bottomLimit) break
            canvas.drawText(line, margin, y, paint)
            y += lineHeight
        }
    }

    private fun fileProviderUri(file: File): Uri? {
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    private fun sanitizeFileName(title: String): String {
        val sanitized = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
        return sanitized.ifBlank { "searchable-pdf" }
    }

    private fun uniqueFile(directory: File, baseName: String): File {
        var candidate = File(directory, "$baseName.pdf")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($index).pdf")
            index++
        }
        return candidate
    }

    private companion object {
        const val SEARCHABLE_TEXT_SIZE = 10f
        const val SEARCHABLE_TEXT_MARGIN = 24f
    }
}

/** Loads the bitmap for a page image URL. Abstracted so the service can be tested with fakes. */
interface PdfImageLoader {
    suspend fun load(url: String): Bitmap?
}

/**
 * Default [PdfImageLoader]: fetches remote http(s) images over OkHttp and reads local
 * content/file URIs through the content resolver, downsampling large images to keep PDF
 * generation within memory limits. It only ever reads bytes — it never triggers backend
 * processing or OCR.
 */
class HttpPdfImageLoader(
    private val context: Context,
    private val client: OkHttpClient = NetworkClient.createOkHttpClient()
) : PdfImageLoader {

    override suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@withContext null
        when (uri.scheme?.lowercase()) {
            "http", "https" -> loadRemote(url)
            else -> loadLocal(uri)
        }
    }

    private fun loadRemote(url: String): Bitmap? {
        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        }.getOrNull() ?: return null
        return decodeSampledBitmap(bytes)
    }

    private fun loadLocal(uri: Uri): Bitmap? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return decodeSampledBitmap(bytes)
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > AppConstants.MAX_PDF_IMAGE_DIMENSION ||
            boundsOptions.outHeight / sampleSize > AppConstants.MAX_PDF_IMAGE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }
}
