package com.dev.docscannerpdf.ui.mainscan

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.domain.crop.WarpPlan
import com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor
import com.dev.docscannerpdf.domain.mainscan.MainScanAuthoritativeArtifact
import com.dev.docscannerpdf.domain.mainscan.MainScanAuthoritativeDemand
import com.dev.docscannerpdf.domain.mainscan.MainScanAuthoritativeRender
import com.dev.docscannerpdf.domain.mainscan.MainScanDocumentFinder
import com.dev.docscannerpdf.domain.mainscan.MainScanMemoryProbe
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderFailure
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderOutcome
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.filter.DocumentFilterPrimitives
import com.dev.docscannerpdf.domain.filter.ensureOutputDirectory
import com.dev.docscannerpdf.ui.detection.LumaFrameFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android side of the Main Scanner's crop and enhancement stages. Every method suspends onto
 * [Dispatchers.Default] or [Dispatchers.IO] — full-resolution decode, warping and filtering must
 * never run on the UI thread, and the surfaces above stay responsive with the page still on screen.
 *
 * All geometry comes from the tested pure layer ([PerspectiveTransformEngine],
 * [PerspectiveGeometry]); this file only feeds matrices into a Canvas and writes files. Outputs go
 * exclusively into app-private directories supplied by the caller.
 */
object MainScanCaptureProcessor {

    private const val TAG = "MainScanCapture"
    private const val JPEG_QUALITY = 92

    /**
     * Re-runs document detection on the CAPTURED image. Used only when the live seed could not be
     * proven to map onto the still, so the common path never pays for it.
     *
     * Returns the detected quad in normalized captured-image coordinates, or null when nothing
     * plausible was found — the caller then falls back to full frame rather than guessing.
     */
    suspend fun detectOnCapture(bitmap: Bitmap): PerspectiveQuad? =
        withContext(Dispatchers.Default) {
            runCatching {
                val frame = LumaFrameFactory.fromBitmap(bitmap)
                MainScanDocumentFinder.find(frame)
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_STILL_DETECT failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Rotates the working bitmap a quarter turn so the displayed page turns with the polygon. The
     * crop editor's Left/Right must move BOTH — rotating only the polygon would leave the user
     * dragging corners that no longer sit on the document.
     *
     * Returns a new bitmap; the caller owns recycling the old one once Compose has stopped drawing it.
     */
    suspend fun rotate(bitmap: Bitmap, clockwise: Boolean): Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                val matrix = Matrix().apply { postRotate(if (clockwise) 90f else 270f) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_ROTATE failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Applies the user's polygon as a genuine perspective correction: the quad is de-skewed onto a
     * rectangle sized from its own edge lengths, so a page photographed at an angle comes out square
     * rather than merely cropped.
     *
     * The source bitmap is never modified — the result is a new bitmap, and the original capture
     * file on disk is untouched, so a failure here can never cost the user their photograph.
     */
    suspend fun perspectiveCrop(source: Bitmap, normalizedQuad: PerspectiveQuad): Bitmap? =
        withContext(Dispatchers.Default) {
            val quad = PerspectiveGeometry.normalize(normalizedQuad)
            if (!PerspectiveGeometry.isValid(quad)) {
                Log.w(TAG, "MAIN_SCAN_CROP rejected reason=invalid_quad")
                return@withContext null
            }
            runCatching {
                drawWarp(source, PerspectiveTransformEngine.plan(quad, source.width, source.height))
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_CROP failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Renders [source] through an ALREADY PLANNED warp. Split out so the authoritative path can plan
     * once, size its memory admission from that exact plan, and then warp with the very same
     * matrix — planning twice would make the numbers admission was granted on describe a different
     * transform from the one that runs.
     */
    private fun drawWarp(source: Bitmap, plan: WarpPlan): Bitmap {
        // KTX `createBitmap` — the form lint prefers; identical behaviour.
        val output = createBitmap(plan.outputWidth, plan.outputHeight)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply { setValues(plan.matrix.toFloatArray()) }
        canvas.drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return output
    }

    /**
     * Applies [filter] to [source], returning a NEW bitmap and never mutating the input.
     *
     * Always rendering from the untouched perspective-cropped page is what keeps filtering
     * non-destructive: re-applying a filter to an already-filtered image compounds sharpening and
     * contrast until the page is unusable, and there is no way back. ORIGINAL is a defensive copy
     * so the caller can recycle uniformly.
     */
    suspend fun applyFilter(source: Bitmap, filter: DocumentFilter): Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                if (filter == DocumentFilter.ORIGINAL || filter.isIdentity) {
                    return@runCatching source.copy(
                        source.config ?: Bitmap.Config.ARGB_8888,
                        false
                    )
                }
                var current: Bitmap = source
                filter.toneLut?.let { lut ->
                    val next = DocumentFilterPrimitives.applyToneLut(current, lut)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                filter.colorMatrix?.let { matrix ->
                    val next = DocumentFilterPrimitives.applyColorMatrix(current, matrix)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                if (filter.sharpenStrength > 0f) {
                    val next = DocumentFilterPrimitives.applySharpen(current, filter.sharpenStrength)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                val owned = current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
                if (owned !== current && current !== source) current.recycle()
                owned
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_FILTER failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Writes [bitmap] as a JPEG under [outputDirectory] (app-private). Honors the compress return
     * value: a false result, a throw, or a zero-length file deletes the partial and returns null, so
     * a URI is never handed back for an empty output.
     */
    suspend fun writeJpeg(
        bitmap: Bitmap,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = withContext(Dispatchers.IO) {
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) {
            Log.w(TAG, "MAIN_SCAN_WRITE rejected reason=directory_unavailable")
            return@withContext null
        }
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)
        if (!ok || !file.exists() || file.length() == 0L) {
            runCatching { file.delete() }
            Log.w(TAG, "MAIN_SCAN_WRITE failed reason=compress")
            return@withContext null
        }
        Uri.fromFile(file)
    }

    // ---- authoritative source-resolution render ---------------------------------------------------
    //
    // Everything above this line produces the INTERACTIVE preview: it is decoded at a 2048 px bound
    // so the crop editor can hold it, and it must never become the page a document is made from.
    // Everything below produces the authoritative artifact — the same geometry the user confirmed,
    // applied to the full-resolution capture, written once, and validated before anyone may see it
    // as a result.

    /**
     * Produces the authoritative post-crop artifact for the confirmed polygon, or a truthful reason
     * why it could not.
     *
     * ## The sequence, and why it is this sequence
     *
     * The crop polygon is normalized against the frame the EDITOR was showing: the capture decoded,
     * turned upright by EXIF, then turned again by however many quarter turns the user applied. To
     * put those same corners on the same part of the page at full resolution, that frame has to be
     * rebuilt in exactly that order and exactly once each — decode at sample size 1, EXIF once,
     * quarter turns once — and then the polygon applies unchanged. Re-deriving the polygon into some
     * other frame instead would work at 0 and 180 degrees and silently crop the wrong region at 90
     * and 270, which is the failure that looks perfect on screen and wrong in the file.
     *
     * The warp is PLANNED before anything large is allocated, so admission control sees the real
     * output dimensions rather than an estimate, and the plan it approved is the plan that runs.
     *
     * ## Why the caller must supply the editor's own frame dimensions
     *
     * [editorFrameWidth] and [editorFrameHeight] are the dimensions of the working bitmap the user
     * was ACTUALLY looking at when the polygon was confirmed. The reproduction above is internally
     * consistent by construction, which is exactly why it cannot catch its own worst failure: this
     * function reads EXIF a SECOND time, independently of the read the interactive loader made, and
     * a disagreement between those two reads produces a perfectly self-consistent frame that the
     * user's corners were never placed against. Checked against the real editor frame — through
     * [MainScanAuthoritativeRender.reproducesEditorFrame], BEFORE the warp is planned — that
     * disagreement becomes [MainScanRenderFailure.EDITOR_FRAME_MISMATCH] and nothing is published.
     *
     * ## What the caller must have done first
     *
     * [croppedTarget] and [enhancedTarget] are supplied, not chosen here, because they must already
     * be in the visit's ownership ledger before the first byte is written. A path this function
     * invented would be unowned for the whole write, and a failure in that window would strand it.
     *
     * ## Two JPEG generations, stated plainly
     *
     * The camera hands this flow a JPEG, and each sibling is encoded once from pixels held in
     * memory. So both artifacts are two JPEG generations deep — CameraX's encode, then this one.
     * There is deliberately no third: the enhanced sibling is rendered from the in-memory warp
     * result, never by decoding [croppedTarget] back.
     */
    suspend fun renderAuthoritative(
        context: Context,
        sourceUri: Uri,
        editorQuad: PerspectiveQuad,
        rotationQuarterTurns: Int,
        editorFrameWidth: Int,
        editorFrameHeight: Int,
        croppedTarget: File,
        enhancedTarget: File,
        filter: DocumentFilter,
        retainedBitmapBytes: Long
    ): MainScanRenderOutcome = withContext(Dispatchers.IO) {
        // Camera captures only. A gallery original is refused outright rather than downsampled,
        // copied or re-encoded into something that would look identical in the review and be a
        // different, lesser file on disk.
        if (!MainScanAuthoritativeRender.isSupportedAuthoritativeSource(
                uriString = sourceUri.toString(),
                filesDirPath = context.filesDir.absolutePath
            )
        ) {
            return@withContext nonAuthoritative(MainScanRenderFailure.UNSUPPORTED_SOURCE)
        }

        val bounds = readBounds(context, sourceUri)
            ?: return@withContext nonAuthoritative(MainScanRenderFailure.DECODE)

        // Step 2 of the frame reconstruction, measured only — the pixels are turned later, once.
        val exifDegrees = IdScanPostProcessor.rotationDegreesFromExif(context, sourceUri)
        val uprightWidth = if (exifDegrees % 180 == 0) bounds.first else bounds.second
        val uprightHeight = if (exifDegrees % 180 == 0) bounds.second else bounds.first

        // Step 3, measured: the editor's frame is the upright capture turned by the user's turns.
        val frame = MainScanAuthoritativeRender.orientedFrame(
            uprightWidth = uprightWidth,
            uprightHeight = uprightHeight,
            rotationQuarterTurns = rotationQuarterTurns
        )
        if (frame.width <= 0 || frame.height <= 0) {
            return@withContext nonAuthoritative(MainScanRenderFailure.DECODE)
        }

        // Step 3b: that reproduced frame must be the frame the user actually confirmed against.
        // Fail closed — no preview is promoted to authority, and any previously published artifact
        // survives untouched, because this returns before a single byte is written.
        if (!MainScanAuthoritativeRender.reproducesEditorFrame(
                reproducedWidth = frame.width,
                reproducedHeight = frame.height,
                editorFrameWidth = editorFrameWidth,
                editorFrameHeight = editorFrameHeight
            )
        ) {
            Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE rejected reason=editor_frame_mismatch")
            return@withContext nonAuthoritative(MainScanRenderFailure.EDITOR_FRAME_MISMATCH)
        }

        // Step 4: the confirmed polygon, in that reproduced frame.
        val quad = PerspectiveGeometry.normalize(editorQuad)
        if (!PerspectiveGeometry.isValid(quad)) {
            return@withContext nonAuthoritative(MainScanRenderFailure.WARP)
        }

        // Step 5: the single plan. Everything downstream — admission, the warp, the dimensions the
        // written files are validated against — comes from this one call.
        val plan = runCatching {
            PerspectiveTransformEngine.plan(quad, frame.width, frame.height)
        }.getOrNull() ?: return@withContext nonAuthoritative(MainScanRenderFailure.WARP)

        val demand = MainScanAuthoritativeDemand(
            orientedSourceWidth = frame.width,
            orientedSourceHeight = frame.height,
            outputWidth = plan.outputWidth,
            outputHeight = plan.outputHeight
        )
        if (!MainScanAuthoritativeRender.admits(demand, probeMemory(context, retainedBitmapBytes))) {
            Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE refused reason=insufficient_memory")
            return@withContext nonAuthoritative(MainScanRenderFailure.INSUFFICIENT_MEMORY)
        }

        renderAuthoritativeContained(
            context = context,
            sourceUri = sourceUri,
            exifDegrees = exifDegrees,
            rotationQuarterTurns = MainScanAuthoritativeRender.normalizeQuarterTurns(
                rotationQuarterTurns
            ),
            expectedWidth = frame.width,
            expectedHeight = frame.height,
            plan = plan,
            croppedTarget = croppedTarget,
            enhancedTarget = enhancedTarget,
            filter = filter
        )
    }

    /**
     * The allocating half, behind a boundary nothing escapes.
     *
     * Admission control is not a promise: it reads counters, and between reading them and asking for
     * the memory another process can take it, the heap can fragment, and a native bitmap allocation
     * can simply fail. An `OutOfMemoryError` from any of those is an ERROR, not an exception — it
     * would pass straight through ordinary handling and take the pipeline with it. It is caught here
     * and turned into a refusal, which is the only honest thing to do with it: nothing is published,
     * nothing is downgraded, and the capture on disk is untouched.
     *
     * [kotlinx.coroutines.CancellationException] is re-thrown deliberately. It means the user pressed
     * Back, and swallowing it would report a render failure for work that was correctly abandoned.
     */
    private suspend fun renderAuthoritativeContained(
        context: Context,
        sourceUri: Uri,
        exifDegrees: Int,
        rotationQuarterTurns: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        plan: WarpPlan,
        croppedTarget: File,
        enhancedTarget: File,
        filter: DocumentFilter
    ): MainScanRenderOutcome {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var warped: Bitmap? = null
        var enhanced: Bitmap? = null
        // The stage an unexpected throw belongs to, advanced as the render progresses so a failure
        // is reported against the step that was actually running.
        var stage = MainScanRenderFailure.DECODE
        try {
            decoded = decodeAtSourceResolution(context, sourceUri)
                ?: return nonAuthoritative(MainScanRenderFailure.DECODE)

            oriented = orientToEditorFrame(decoded, exifDegrees, rotationQuarterTurns)
                ?: return nonAuthoritative(MainScanRenderFailure.DECODE)
            // `oriented` owns the pixels from here either way — it either IS the decode (no turn was
            // needed) or replaced it. Dropping the second handle keeps the cleanup below unambiguous.
            if (oriented !== decoded) decoded.recycle()
            decoded = null
            // The frame admission was granted for must be the frame that exists. A mismatch means
            // the header lied or the decoder substituted something; either way the polygon would be
            // applied to a frame it was not normalized against, so fail closed rather than crop
            // somewhere the user never indicated.
            if (oriented.width != expectedWidth || oriented.height != expectedHeight) {
                Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE rejected reason=frame_mismatch")
                return nonAuthoritative(MainScanRenderFailure.DECODE)
            }

            stage = MainScanRenderFailure.WARP
            warped = drawWarp(oriented, plan)
            oriented.recycle()
            oriented = null

            stage = MainScanRenderFailure.WRITE
            if (!writeJpegTo(warped, croppedTarget, MainScanAuthoritativeRender.CROPPED_JPEG_QUALITY)) {
                return nonAuthoritative(MainScanRenderFailure.WRITE)
            }

            // The enhancement reads the warp pixels that are still in memory. Decoding the cropped
            // JPEG back would add a generation to the enhanced artifact and make it a copy of a
            // copy; the whole point of holding `warped` this long is that it never has to.
            stage = MainScanRenderFailure.ENHANCE
            enhanced = applyFilter(warped, filter)
                ?: return nonAuthoritative(MainScanRenderFailure.ENHANCE)

            stage = MainScanRenderFailure.WRITE
            if (!writeJpegTo(enhanced, enhancedTarget, MainScanAuthoritativeRender.ENHANCED_JPEG_QUALITY)) {
                return nonAuthoritative(MainScanRenderFailure.WRITE)
            }

            val croppedBounds = readFileBounds(croppedTarget)
            val enhancedBounds = readFileBounds(enhancedTarget)
            val rejection = MainScanAuthoritativeRender.validateCandidate(
                sourceSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
                expectedWidth = plan.outputWidth,
                expectedHeight = plan.outputHeight,
                croppedExists = croppedTarget.exists(),
                croppedLengthBytes = croppedTarget.length(),
                croppedDecodedWidth = croppedBounds?.first ?: 0,
                croppedDecodedHeight = croppedBounds?.second ?: 0,
                enhancedExists = enhancedTarget.exists(),
                enhancedLengthBytes = enhancedTarget.length(),
                enhancedDecodedWidth = enhancedBounds?.first ?: 0,
                enhancedDecodedHeight = enhancedBounds?.second ?: 0
            )
            if (rejection != null) return nonAuthoritative(rejection)

            return MainScanRenderOutcome.Authoritative(
                MainScanAuthoritativeArtifact(
                    croppedUri = Uri.fromFile(croppedTarget).toString(),
                    enhancedUri = Uri.fromFile(enhancedTarget).toString(),
                    pixelWidth = plan.outputWidth,
                    pixelHeight = plan.outputHeight,
                    sourceSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
                    rotationQuarterTurns = rotationQuarterTurns
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE contained=oom stage=${stage.name}")
            return nonAuthoritative(MainScanRenderFailure.INSUFFICIENT_MEMORY)
        } catch (failure: Throwable) {
            // The CLASS of the failure, never its message. A FileNotFoundException or an
            // IOException on this path carries the full app-private path of the capture or of a
            // sibling in its message, and logcat is readable off-device on a release build. The
            // class plus the stage identifies the fault just as precisely without naming a file.
            Log.w(
                TAG,
                "MAIN_SCAN_AUTHORITATIVE failed stage=${stage.name} " +
                    "cause=${failure.javaClass.simpleName}"
            )
            return nonAuthoritative(stage)
        } finally {
            // Full-resolution pixels are far too large to leave to the collector's timing, and none
            // of these are on screen — the review shows the preview, which this never touches.
            decoded?.recycle()
            oriented?.recycle()
            enhanced?.recycle()
            warped?.recycle()
        }
    }

    /** One place to build a refusal, so no call site can accidentally return a half-success. */
    private fun nonAuthoritative(reason: MainScanRenderFailure): MainScanRenderOutcome =
        MainScanRenderOutcome.NonAuthoritative(reason)

    /**
     * Decodes the capture at [MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE] — never higher.
     * There is no downscale fallback here on purpose: sampling down on pressure is exactly the
     * silent resolution downgrade this whole path exists to prevent.
     */
    private fun decodeAtSourceResolution(context: Context, sourceUri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return context.contentResolver.openInputStream(sourceUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /**
     * Rebuilds the crop editor's frame: [exifDegrees] applied once, then [rotationQuarterTurns]
     * applied once, in that order.
     *
     * Both turns go into ONE matrix and one `createBitmap`, which is what keeps "exactly once" true
     * by construction — there is no second rotation call that a later edit could leave in place, and
     * no intermediate full-resolution copy between them.
     */
    private fun orientToEditorFrame(
        source: Bitmap,
        exifDegrees: Int,
        rotationQuarterTurns: Int
    ): Bitmap? {
        val matrix = Matrix()
        matrix.postRotate(exifDegrees.toFloat())
        matrix.postRotate((rotationQuarterTurns * 90).toFloat())
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Writes [bitmap] to the EXACT [target] the caller already registered as owned, at [quality].
     * A false compress result, a throw, or a zero-length file deletes the partial and returns false
     * — a candidate that half-wrote must never reach validation looking plausible.
     */
    private fun writeJpegTo(bitmap: Bitmap, target: File, quality: Int): Boolean {
        val directory = target.parentFile
        if (directory == null || !ensureOutputDirectory(directory)) {
            Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE write rejected reason=directory_unavailable")
            return false
        }
        val ok = runCatching {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        }.getOrDefault(false)
        if (!ok || !target.exists() || target.length() == 0L) {
            runCatching { target.delete() }
            Log.w(TAG, "MAIN_SCAN_AUTHORITATIVE write failed reason=compress")
            return false
        }
        return true
    }

    /** Header-only bounds of a source URI — never decodes pixels. */
    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    /** Header-only bounds of a written candidate — the dimension half of its validation. */
    private fun readFileBounds(file: File): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    /**
     * Samples what the process can actually still obtain, right now.
     *
     * `ActivityManager.MemoryInfo` is the device-level reading that matters from API 26, where
     * bitmap pixels no longer come out of the Java heap; `Runtime` is what matters below it, and for
     * the enhancement's `IntArray` buffers at every level. Both are read, and
     * [MainScanAuthoritativeRender.admits] decides which bounds apply — a probe that could not read
     * the device figures reports zero rather than a guess, and zero refuses.
     */
    private fun probeMemory(context: Context, retainedBitmapBytes: Long): MainScanMemoryProbe {
        val runtime = Runtime.getRuntime()
        val info = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.let { ActivityManager.MemoryInfo().apply(it::getMemoryInfo) }
        }.getOrNull()
        return MainScanMemoryProbe(
            maxHeapBytes = runtime.maxMemory(),
            usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            deviceAvailableBytes = info?.availMem ?: 0L,
            deviceLowMemoryThresholdBytes = info?.threshold ?: 0L,
            deviceLowMemory = info?.lowMemory ?: false,
            retainedBitmapBytes = retainedBitmapBytes,
            bitmapsAllocateOffHeap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        )
    }
}
