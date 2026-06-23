package com.dev.docscannerpdf.domain.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.dev.docscannerpdf.util.AppConstants
import java.io.File

object PdfRenderHelper {
    fun validatePdfPassword(password: String): String? {
        val trimmedPassword = password.trim()
        return when {
            trimmedPassword.isBlank() -> "Password cannot be blank."
            trimmedPassword.length < 4 -> "Password must be at least 4 characters."
            else -> null
        }
    }

    fun openPdfDescriptor(contentResolver: ContentResolver, uri: Uri): ParcelFileDescriptor? {
        return when (uri.scheme) {
            "content" -> contentResolver.openFileDescriptor(uri, "r")
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            null, "" -> ParcelFileDescriptor.open(
                File(uri.toString()),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            else -> null
        }
    }

    fun countPdfPages(contentResolver: ContentResolver, uri: Uri): Int {
        openPdfDescriptor(contentResolver, uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                return renderer.pageCount
            }
        }
        return 0
    }

    fun renderPdfPageToBitmap(page: PdfRenderer.Page, maxDimension: Int): Bitmap {
        val scale = minOf(
            maxDimension / page.width.toFloat(),
            maxDimension / page.height.toFloat()
        ).coerceAtMost(1.8f).coerceAtLeast(0.2f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(
            bitmap,
            null,
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        )
        return bitmap
    }

    fun drawImageOnA4Page(canvas: Canvas, bitmap: Bitmap) {
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
        val destination = RectF(left, top, left + imageWidth, top + imageHeight)
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    fun parsePageRange(input: String, pageCount: Int): List<Int> {
        if (input.isBlank()) return emptyList()
        return input.split(",")
            .flatMap { part ->
                val trimmed = part.trim()
                when {
                    "-" in trimmed -> {
                        val bounds = trimmed.split("-", limit = 2)
                        val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                        val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                        if (start == null || end == null) {
                            emptyList()
                        } else {
                            val range = if (start <= end) start..end else end..start
                            range.map { it - 1 }
                        }
                    }
                    else -> listOfNotNull(trimmed.toIntOrNull()?.minus(1))
                }
            }
            .filter { it in 0 until pageCount }
            .distinct()
            .sorted()
    }

    fun decodeBitmapForPdf(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val sampleSize = calculatePdfImageSampleSize(boundsOptions)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    fun calculatePdfImageSampleSize(options: BitmapFactory.Options): Int {
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / sampleSize > AppConstants.MAX_PDF_IMAGE_DIMENSION ||
            height / sampleSize > AppConstants.MAX_PDF_IMAGE_DIMENSION
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun displayNameForUri(contentResolver: ContentResolver, uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Selected PDF"
    }

    fun sizeForUri(contentResolver: ContentResolver, uri: Uri): Long? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
