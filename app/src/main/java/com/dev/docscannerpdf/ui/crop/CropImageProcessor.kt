package com.dev.docscannerpdf.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Android-side bitmap loading and perspective warp for the crop editor. The geometry comes from
 * the pure [PerspectiveTransformEngine]/[com.dev.docscannerpdf.domain.crop.WarpMatrixCalculator];
 * this class only feeds the computed homography into a [Canvas] draw and saves the result
 * locally. No backend mutation — cropped output is a new local file.
 */
class CropImageProcessor(
    private val context: Context,
    private val client: OkHttpClient = NetworkClient.createOkHttpClient(),
    private val outputDirectory: File = File(context.filesDir, "cropped_images")
) {

    /** Loads the source image (http(s) via OkHttp, otherwise via the content resolver). */
    suspend fun loadSource(uriValue: String): Bitmap? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return@withContext null
        when (uri.scheme?.lowercase()) {
            "http", "https" -> runCatching {
                client.newCall(Request.Builder().url(uriValue).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
            else -> runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }

    /**
     * Warps [source] to the rectangle defined by [normalizedQuad] and saves a JPEG locally,
     * returning its file Uri. Returns null when the quad is invalid or the image cannot be saved.
     */
    suspend fun warpAndSave(source: Bitmap, normalizedQuad: PerspectiveQuad): Uri? =
        withContext(Dispatchers.IO) {
            val quad = PerspectiveGeometry.normalize(normalizedQuad)
            if (!PerspectiveGeometry.isValid(quad)) return@withContext null

            val plan = PerspectiveTransformEngine.plan(quad, source.width, source.height)
            val output = Bitmap.createBitmap(
                plan.outputWidth,
                plan.outputHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val matrix = Matrix().apply { setValues(plan.matrix.toFloatArray()) }
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

            runCatching {
                if (!outputDirectory.exists()) outputDirectory.mkdirs()
                val file = File(outputDirectory, "crop-${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    output.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                Uri.fromFile(file)
            }.getOrNull()
        }
}
