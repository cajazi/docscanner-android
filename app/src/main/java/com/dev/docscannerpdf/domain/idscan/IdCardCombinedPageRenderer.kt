package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rasterizes the CamScanner-style combined ID-card result: front (and back, when captured) as
 * same-size card tiles centered on one white A4-proportioned page, saved as a single JPEG in
 * app-private storage. This one image is what the Document Ready preview displays and what
 * "Save to gallery" writes out, so what the user sees is pixel-identical to what gets saved —
 * and, because the layout comes from [IdCardCombinedPagePlanner] (which reuses
 * [com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner]), it visually matches the exported PDF
 * page too. Drawing never stretches a side: each photo is aspect-fit inside its card slot.
 */
object IdCardCombinedPageRenderer {

    /** Output page width; height follows from the A4 ratio (~1754px), comfortably print-sharp. */
    private const val PAGE_WIDTH_PX = 1240

    private const val JPEG_QUALITY = 92

    /** Source photos are downsampled to this bound before drawing to keep memory in check. */
    private const val MAX_SOURCE_DIMENSION = 2048

    /** Same light-gray card outline the on-screen tiles use, so cards read against the white page. */
    private const val CARD_BORDER_COLOR = 0xFFD9DBE0.toInt()

    /**
     * Renders the combined page for [frontUri] (+ optional [backUri]) into a new JPEG under
     * [outputDirectory] (app-private; created if missing) and returns its file URI. A missing
     * front image fails the render (returns null); a back image that fails to decode degrades to
     * a front-only page rather than blocking the save.
     */
    suspend fun render(
        context: Context,
        frontUri: Uri,
        backUri: Uri?,
        outputDirectory: File
    ): Uri? = withContext(Dispatchers.IO) {
        val front = decode(context, frontUri) ?: return@withContext null
        val back = backUri?.let { decode(context, it) }
        try {
            val plan = IdCardCombinedPagePlanner.plan(
                pageWidth = PAGE_WIDTH_PX,
                frontImageWidth = front.width,
                frontImageHeight = front.height,
                backImageWidth = back?.width,
                backImageHeight = back?.height
            )
            val page = Bitmap.createBitmap(plan.pageWidth, plan.pageHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(page)
                canvas.drawColor(Color.WHITE)
                drawSide(canvas, front, plan.front)
                if (back != null && plan.back != null) {
                    drawSide(canvas, back, plan.back)
                }
                save(page, outputDirectory)
            } finally {
                page.recycle()
            }
        } catch (throwable: Throwable) {
            null
        } finally {
            front.recycle()
            back?.recycle()
        }
    }

    private fun drawSide(canvas: Canvas, bitmap: Bitmap, draw: IdCardCombinedSideDraw) {
        val destination = RectF(
            draw.imageRect.left,
            draw.imageRect.top,
            draw.imageRect.right,
            draw.imageRect.bottom
        )
        canvas.drawBitmap(
            bitmap,
            null,
            destination,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = CARD_BORDER_COLOR
        }
        canvas.drawRect(destination, borderPaint)
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null
        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > MAX_SOURCE_DIMENSION ||
            boundsOptions.outHeight / sampleSize > MAX_SOURCE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun save(page: Bitmap, outputDirectory: File): Uri? {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) return null
        val file = File(outputDirectory, "id_card_combined_${System.currentTimeMillis()}.jpg")
        val saved = runCatching {
            file.outputStream().use { output ->
                page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
        }.getOrDefault(false)
        return if (saved) Uri.fromFile(file) else null
    }
}
