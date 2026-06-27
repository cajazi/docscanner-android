package com.dev.docscannerpdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PdfThumbnailLoader"
private const val THUMBNAIL_WIDTH = 180
private const val THUMBNAIL_HEIGHT = 240
private const val PREVIEW_WIDTH = 1080
private const val PREVIEW_HEIGHT = 1440
private const val PAGE_PREVIEW_WIDTH = 720
private const val PAGE_PREVIEW_HEIGHT = 1018
private const val PREVIEW_PAGE_LIMIT = 3

object PdfThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pageCache = object : LruCache<String, List<Bitmap>>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<Bitmap>): Int {
            return value.sumOf { bitmap -> bitmap.byteCount }
        }
    }

    suspend fun loadThumbnail(
        context: Context,
        pdfUriValue: String
    ): Bitmap? {
        cache.get(pdfUriValue)?.let { return it }

        return loadBitmap(context, pdfUriValue, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            ?.also { cache.put(pdfUriValue, it) }
    }

    suspend fun loadPreview(
        context: Context,
        pdfUriValue: String
    ): Bitmap? {
        return loadBitmap(context, pdfUriValue, PREVIEW_WIDTH, PREVIEW_HEIGHT)
    }

    suspend fun loadPreviewPages(
        context: Context,
        pdfUriValue: String
    ): List<Bitmap> {
        pageCache.get(pdfUriValue)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(pdfUriValue)
                if (isImageUri(uri, pdfUriValue)) {
                    return@runCatching decodeImageThumbnail(
                        context = context,
                        uri = uri,
                        rawUriValue = pdfUriValue,
                        width = PREVIEW_WIDTH,
                        height = PREVIEW_HEIGHT
                    )?.let(::listOf).orEmpty()
                }

                openFileDescriptor(context, uri, pdfUriValue)?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        (0 until minOf(renderer.pageCount, PREVIEW_PAGE_LIMIT)).mapNotNull { pageIndex ->
                            renderer.openPage(pageIndex).use { page ->
                                val bitmap = Bitmap.createBitmap(
                                    PAGE_PREVIEW_WIDTH,
                                    PAGE_PREVIEW_HEIGHT,
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                                bitmap
                            }
                        }
                    }
                }.orEmpty()
            }.onFailure { throwable ->
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.w(TAG, "Unable to render PDF pages: ${throwable.message}")
                }
            }.getOrDefault(emptyList())
        }.also { pages ->
            if (pages.isNotEmpty()) {
                pageCache.put(pdfUriValue, pages)
            }
        }
    }

    /**
     * Renders a single page (by zero-based [pageIndex]) of a PDF, or the image itself for an
     * image document. Used by the multi-page editor to show distinct per-page thumbnails and
     * the selected-page preview. Returns null when the page index is out of range or the file
     * cannot be read.
     */
    suspend fun loadPageBitmap(
        context: Context,
        uriValue: String,
        pageIndex: Int,
        width: Int = PAGE_PREVIEW_WIDTH,
        height: Int = PAGE_PREVIEW_HEIGHT
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriValue)
                if (isImageUri(uri, uriValue)) {
                    return@runCatching decodeImageThumbnail(context, uri, uriValue, width, height)
                }
                openFileDescriptor(context, uri, uriValue)?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@use null
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            bitmap
                        }
                    }
                }
            }.onFailure { throwable ->
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.w(TAG, "Unable to render PDF page $pageIndex: ${throwable.message}")
                }
            }.getOrNull()
        }
    }

    private suspend fun loadBitmap(
        context: Context,
        pdfUriValue: String,
        width: Int,
        height: Int
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(pdfUriValue)
                if (isImageUri(uri, pdfUriValue)) {
                    return@runCatching decodeImageThumbnail(context, uri, pdfUriValue, width, height)
                }
                openFileDescriptor(context, uri, pdfUriValue)?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount <= 0) return@use null

                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                width,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            bitmap
                        }
                    }
                }
            }.onFailure { throwable ->
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.w(TAG, "Unable to render document thumbnail: ${throwable.message}")
                }
            }.getOrNull()
        }
    }

    private fun isImageUri(
        uri: Uri,
        rawUriValue: String
    ): Boolean {
        val value = uri.path ?: rawUriValue
        return value.endsWith(".jpg", ignoreCase = true) ||
            value.endsWith(".jpeg", ignoreCase = true) ||
            value.endsWith(".png", ignoreCase = true) ||
            value.endsWith(".webp", ignoreCase = true)
    }

    private fun decodeImageThumbnail(
        context: Context,
        uri: Uri,
        rawUriValue: String,
        width: Int,
        height: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openInputStream(context, uri, rawUriValue)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        val sampleSize = calculateInSampleSize(bounds, width, height)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return openInputStream(context, uri, rawUriValue)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun openInputStream(
        context: Context,
        uri: Uri,
        rawUriValue: String
    ) = when (uri.scheme) {
        "content" -> context.contentResolver.openInputStream(uri)
        "file" -> File(requireNotNull(uri.path)).inputStream()
        null, "" -> File(rawUriValue).inputStream()
        else -> null
    }

    private fun openFileDescriptor(
        context: Context,
        uri: Uri,
        rawUriValue: String
    ): ParcelFileDescriptor? {
        return when (uri.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            null, "" -> ParcelFileDescriptor.open(
                File(rawUriValue),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            else -> null
        }
    }
}
