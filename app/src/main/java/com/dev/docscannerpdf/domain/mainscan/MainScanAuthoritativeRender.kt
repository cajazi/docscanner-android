package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import java.util.UUID

/**
 * A fully validated, source-resolution post-crop result.
 *
 * ## Why this type exists
 *
 * The crop editor works on a preview: [com.dev.docscannerpdf.ui.mainscan.MainScanCaptureImageLoader]
 * downsamples the capture to a 2048 px bound so an interactive surface cannot OOM. That bitmap is
 * excellent for dragging corners and completely unacceptable as the thing a future Confirm writes
 * into the user's library — a 4080x3060 capture reaches the editor as 2040x1530, so persisting it
 * would silently throw away three quarters of the pixels the user paid for with a steady hand.
 *
 * Nothing structural stopped that from happening: both stages were plain `Bitmap`s, so the preview
 * was assignable everywhere the authoritative page was. This type closes that hole by construction.
 * It cannot be built from a downsampled decode ([sourceSampleSize] must be
 * [MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE]), it cannot be built from pixels still in
 * memory (both siblings are already-written files), and it cannot be built at all unless every
 * invariant below holds — so "the preview leaked into the authoritative slot" is not a defect that
 * can be introduced later, it is a state that cannot be represented.
 *
 * @param croppedUri the perspective-corrected page, written once at source resolution.
 * @param enhancedUri the enhanced sibling, produced from the SAME in-memory warp pixels as
 *   [croppedUri] and never by decoding it back — see [MainScanAuthoritativeRender].
 * @param pixelWidth width of both siblings, in real pixels.
 * @param pixelHeight height of both siblings, in real pixels.
 * @param sourceSampleSize the `BitmapFactory` sample size the source was decoded at. Always 1.
 * @param rotationQuarterTurns the user's quarter turns already baked into the pixels, retained so a
 *   later stage can describe the artifact without re-deriving it.
 */
data class MainScanAuthoritativeArtifact(
    val croppedUri: String,
    val enhancedUri: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sourceSampleSize: Int,
    val rotationQuarterTurns: Int
) {
    init {
        require(croppedUri.isNotBlank()) { "An authoritative artifact needs a cropped sibling." }
        require(enhancedUri.isNotBlank()) { "An authoritative artifact needs an enhanced sibling." }
        require(croppedUri != enhancedUri) {
            "The cropped and enhanced siblings must be two distinct files."
        }
        require(pixelWidth > 0 && pixelHeight > 0) {
            "An authoritative artifact must have real pixel dimensions."
        }
        require(sourceSampleSize == MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE) {
            "An authoritative artifact may only come from a full-resolution decode."
        }
        require(rotationQuarterTurns in 0..3) { "Quarter turns are 0..3." }
    }
}

/** Why an authoritative render did not produce an artifact. There is no partial-success value. */
enum class MainScanRenderFailure {
    /** The source could not be read, or the decode did not yield the dimensions its header claimed. */
    DECODE,

    /** The confirmed polygon could not be warped into an output page. */
    WARP,

    /** The authoritative enhancement could not be produced from the authoritative warp pixels. */
    ENHANCE,

    /** A sibling could not be written, or the written file failed validation. */
    WRITE,

    /** Admission control refused, or an allocation failed despite admission. */
    INSUFFICIENT_MEMORY,

    /** The page did not come from this scanner's own camera capture directory. */
    UNSUPPORTED_SOURCE,

    /**
     * The frame reproduced from the source did not match the frame the crop editor was actually
     * showing when the polygon was confirmed.
     *
     * Distinct from [DECODE] on purpose: the source decoded perfectly and the reproduction is
     * internally consistent — it simply describes a DIFFERENT frame from the one the user's corners
     * were placed against, which is the one failure a self-consistent render cannot notice on its
     * own. The most likely cause is a second EXIF read disagreeing with the first.
     */
    EDITOR_FRAME_MISMATCH
}

/**
 * The result of one authoritative render attempt.
 *
 * Deliberately two cases and no third: there is no "degraded", no "preview instead", and no
 * nullable artifact riding along with a failure. A caller that wants pixels for the library has
 * exactly one way to get them, and it is spelled [Authoritative].
 */
sealed interface MainScanRenderOutcome {

    /** The render completed and every contract in [MainScanAuthoritativeRender.validateCandidate] held. */
    data class Authoritative(val artifact: MainScanAuthoritativeArtifact) : MainScanRenderOutcome

    /** The render did not complete. Nothing was published, and no artifact exists. */
    data class NonAuthoritative(val reason: MainScanRenderFailure) : MainScanRenderOutcome
}

/** The artifact when this outcome is authoritative, and null in every other case. */
val MainScanRenderOutcome.artifactOrNull: MainScanAuthoritativeArtifact?
    get() = (this as? MainScanRenderOutcome.Authoritative)?.artifact

/** True only for [MainScanRenderOutcome.Authoritative]. */
val MainScanRenderOutcome.isAuthoritative: Boolean
    get() = this is MainScanRenderOutcome.Authoritative

/**
 * The pixel frame the crop editor was showing, reproduced at source resolution: the EXIF-upright
 * capture turned by the user's quarter turns. The confirmed polygon is normalized against exactly
 * this frame, which is why the authoritative path must rebuild it rather than warp the upright
 * capture directly.
 */
data class MainScanAuthoritativeFrame(
    val width: Int,
    val height: Int,
    val rotationQuarterTurns: Int
)

/**
 * What one authoritative render will need to hold, in pixels. Derived once — before any large
 * allocation — from the source header, the EXIF orientation, the quarter turns and the single warp
 * plan, so admission control decides on the real numbers rather than on an estimate of them.
 */
data class MainScanAuthoritativeDemand(
    val orientedSourceWidth: Int,
    val orientedSourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int
)

/**
 * A sample of the memory the process can actually still obtain.
 *
 * Sampled by the Android layer immediately before admission, never assumed. [bitmapsAllocateOffHeap]
 * is what makes the model honest across the supported API range: from API 26 bitmap pixels live in
 * the native heap and are NOT bounded by `Runtime.maxMemory()`, while on API 23-25 they are counted
 * in it. One rule for both would be wrong on one of them — too permissive on the old floor, or
 * uselessly strict on every modern device.
 */
data class MainScanMemoryProbe(
    /** `Runtime.getRuntime().maxMemory()`. Reflects `largeHeap`, unlike `memoryClass`. */
    val maxHeapBytes: Long,
    /** `totalMemory() - freeMemory()`. */
    val usedHeapBytes: Long,
    /** `ActivityManager.MemoryInfo.availMem`, or 0 when it could not be read. */
    val deviceAvailableBytes: Long,
    /** `ActivityManager.MemoryInfo.threshold` — the level at which the platform starts killing. */
    val deviceLowMemoryThresholdBytes: Long,
    /** `ActivityManager.MemoryInfo.lowMemory`. */
    val deviceLowMemory: Boolean,
    /**
     * Sum of `allocationByteCount` over the bitmaps this pipeline holds and will NOT release for
     * the duration of the render (the retained preview stays on screen behind the progress).
     */
    val retainedBitmapBytes: Long,
    /** True from API 26, where bitmap pixel data is allocated outside the Java heap. */
    val bitmapsAllocateOffHeap: Boolean
)

/**
 * The pure half of the authoritative post-crop artifact: what it costs, what makes it valid, and
 * what coordinate frame it is produced in. No Android types, so every rule below is decided on the
 * JVM by `MainScanAuthoritativeRenderTest` rather than on a device.
 *
 * ## Admission control is not a guarantee
 *
 * [admits] answers one question — "is refusing now clearly the right answer?" — and nothing more. A
 * true result does not promise the allocations will succeed: between the probe and the allocation
 * another process can take the memory, the heap can fragment, and a native bitmap allocation can
 * fail for reasons no counter here can see. The Android layer therefore contains allocation failure
 * and `OutOfMemoryError` at the processing boundary as well, and the two mechanisms are independent
 * on purpose.
 */
object MainScanAuthoritativeRender {

    /**
     * The only sample size an authoritative camera artifact may be decoded at.
     *
     * A camera still is the highest-fidelity thing this flow will ever hold. Accepting sample size 2
     * would quarter it, and — because the preview path already decodes at 2 or 4 — would make the
     * preview itself a legal authoritative source. There is no downgrade path: a source that cannot
     * be decoded at 1 is refused, never sampled down.
     */
    const val AUTHORITATIVE_SAMPLE_SIZE = 1

    /**
     * Cropped sibling quality. 95, matching [com.dev.docscannerpdf.domain.idscan.PassportCropRenderer]
     * — the app's established value for a canonical crop BASE. The enhanced sibling is not produced
     * by reading this file (see [MainScanRenderOutcome]), but the cropped file is still the
     * highest-fidelity artifact this flow writes, and it is encoded exactly once.
     */
    const val CROPPED_JPEG_QUALITY = 95

    /**
     * Enhanced sibling quality. 92, matching every filter output in the app
     * ([com.dev.docscannerpdf.domain.filter.DocumentFilterRenderer]).
     */
    const val ENHANCED_JPEG_QUALITY = 92

    /** App-private directory for the authoritative cropped sibling. */
    const val CROPPED_DIRECTORY_NAME = "main_scan_cropped"

    /** App-private directory for the authoritative enhanced sibling. */
    const val ENHANCED_DIRECTORY_NAME = "main_scan_enhanced"

    /** The capture directory an authoritative source must live in. */
    const val CAPTURE_DIRECTORY_NAME = "main_scan_capture"

    /** ARGB_8888 — the config every stage of this pipeline works in. */
    const val BYTES_PER_PIXEL = 4L

    /**
     * The largest pixel count either side of a demand may describe before the byte arithmetic in
     * this object stops being trustworthy.
     *
     * Every figure below is `pixels * 4` and then summed a handful of times. At 2^32 pixels the
     * largest of those sums is around 2^36 bytes, nine orders of magnitude clear of `Long.MAX_VALUE`
     * — while a demand near `Int.MAX_VALUE` on both axes would overflow the multiplication into a
     * NEGATIVE byte count, and a negative requirement compares as "fits" against every headroom
     * there is. This is four gigapixels: unreachably far above any camera, and low enough that the
     * wrap can never be reached.
     */
    const val MAX_ADMISSIBLE_PIXELS = 1L shl 32

    /**
     * Live bitmap copies at the worst moment of the ENHANCE recipe.
     *
     * Counted from the actual code rather than guessed. Inside
     * `MainScanCaptureProcessor.applyFilter`, the sharpen step is the peak: the retained warp result
     * (the caller still owns it — the cropped sibling was written from it, and the enhancement must
     * come from these pixels rather than from that JPEG), the current intermediate, and the bitmap
     * `applySharpen` builds for its result are all alive at once.
     */
    const val ENHANCE_PEAK_BITMAP_COPIES = 3L

    /**
     * Full-image `IntArray` buffers alive at that same moment.
     *
     * `DocumentFilterPrimitives.applySharpen` reads the whole image into one `IntArray` and writes
     * the whole result into a second before it touches a bitmap. These are ordinary Java arrays at
     * every API level, so on API 26+ they are the part of the render still bounded by
     * `Runtime.maxMemory()` even though the bitmaps beside them are not.
     */
    const val ENHANCE_PEAK_INT_BUFFER_COPIES = 2L

    /**
     * JPEG encode allowance as a fraction of the output image (`outputBytes / 4`).
     *
     * A quality-95 encode of a document page lands well under this; the margin covers the encoder's
     * working buffers and the stream underneath it.
     */
    const val JPEG_ENCODE_DIVISOR = 4L

    /**
     * Explicit reserve for everything this model does not enumerate: Compose's retained composition
     * and display lists, the CameraX session the visit may still hold, framework transients, and
     * ordinary fragmentation.
     *
     * Deliberately a fixed floor rather than a share of the heap. A percentage of `memoryClass`
     * scales the reserve with the DEVICE, while the things it reserves for scale with the UI — so on
     * a large-heap device a percentage silently reserves far more than anything needs, and on a
     * small one far less than Compose alone occupies.
     */
    const val FIXED_RESERVE_BYTES = 24L * 1024L * 1024L

    // ---- coordinate frame -----------------------------------------------------------------------

    /** [quarterTurns] wrapped into 0..3. Four turns are the identity, in either direction. */
    fun normalizeQuarterTurns(quarterTurns: Int): Int = ((quarterTurns % 4) + 4) % 4

    /**
     * The frame the confirmed polygon is normalized against: the EXIF-upright source turned by
     * [rotationQuarterTurns]. Odd turns transpose the dimensions — the identical rule the editor's
     * own rotated working bitmap follows, which is precisely why the polygon still lands where the
     * user put it.
     */
    fun orientedFrame(
        uprightWidth: Int,
        uprightHeight: Int,
        rotationQuarterTurns: Int
    ): MainScanAuthoritativeFrame {
        val turns = normalizeQuarterTurns(rotationQuarterTurns)
        return if (turns % 2 == 0) {
            MainScanAuthoritativeFrame(uprightWidth, uprightHeight, turns)
        } else {
            MainScanAuthoritativeFrame(uprightHeight, uprightWidth, turns)
        }
    }

    /**
     * Whether [demand] is small enough for the byte model below to be exact. See
     * [MAX_ADMISSIBLE_PIXELS]. `Int * Int` widened to `Long` cannot itself overflow, so this check
     * is safe to perform on the very inputs it is protecting against.
     */
    fun isRepresentable(demand: MainScanAuthoritativeDemand): Boolean {
        if (demand.orientedSourceWidth <= 0 || demand.orientedSourceHeight <= 0) return false
        if (demand.outputWidth <= 0 || demand.outputHeight <= 0) return false
        val sourcePixels =
            demand.orientedSourceWidth.toLong() * demand.orientedSourceHeight.toLong()
        val outputPixels = demand.outputWidth.toLong() * demand.outputHeight.toLong()
        return sourcePixels <= MAX_ADMISSIBLE_PIXELS && outputPixels <= MAX_ADMISSIBLE_PIXELS
    }

    /**
     * Whether a frame reproduced at source resolution really is the frame the crop editor was
     * showing when the user confirmed the polygon.
     *
     * ## The failure this closes
     *
     * The editor's frame and the authoritative frame are built from the same capture by two
     * INDEPENDENT sequences: the interactive loader reads EXIF and downsamples, and the
     * authoritative render reads EXIF again at full resolution. Each is internally consistent, so
     * neither can notice that the other disagreed — and if the second EXIF read returns something
     * different from the first (a re-written file, an orientation tag the two readers interpret
     * differently, a source swapped underneath the flow), the confirmed polygon is applied to a
     * frame it was never normalized against. The render succeeds, the preview looks perfect, and the
     * saved page is cropped somewhere the user never indicated. Nothing downstream can detect it.
     *
     * ## What "matches" means, exactly
     *
     * The editor frame is the reproduced frame DOWNSAMPLED by the loader's power-of-two sample size
     * — never rotated relative to it, never larger than it. So a match is: both axes reduce to the
     * editor's dimensions under one and the same power of two, allowing the single pixel of slack
     * `BitmapFactory` may round either way on an odd dimension. A transposition, a different
     * quarter-turn count, or a different EXIF reading fails on at least one axis and therefore fails
     * here, because no single factor can satisfy both.
     *
     * Returns false for any non-positive dimension: an unknown editor frame is not a matching one.
     */
    fun reproducesEditorFrame(
        reproducedWidth: Int,
        reproducedHeight: Int,
        editorFrameWidth: Int,
        editorFrameHeight: Int
    ): Boolean {
        if (reproducedWidth <= 0 || reproducedHeight <= 0) return false
        if (editorFrameWidth <= 0 || editorFrameHeight <= 0) return false
        // The editor works on a downsample. A larger editor frame is not the same frame scaled — it
        // is a different frame, and the polygon on it means something else.
        if (editorFrameWidth > reproducedWidth || editorFrameHeight > reproducedHeight) return false
        var sample = 1
        while (sample <= reproducedWidth && sample <= reproducedHeight) {
            if (reducesTo(reproducedWidth, editorFrameWidth, sample) &&
                reducesTo(reproducedHeight, editorFrameHeight, sample)
            ) {
                return true
            }
            sample *= 2
        }
        return false
    }

    /** [full] downsampled by [sample], accepting either rounding `BitmapFactory` may apply. */
    private fun reducesTo(full: Int, reduced: Int, sample: Int): Boolean =
        reduced == full / sample || reduced == (full + sample - 1) / sample

    /**
     * The editor-frame quad expressed back in the EXIF-upright frame.
     *
     * The authoritative render does NOT use this — it rebuilds the editor's frame in pixels and
     * warps the polygon exactly as confirmed, which is the one sequence that cannot drift from what
     * the user saw. It exists so the two descriptions of the same geometry can be checked against
     * each other, and so a later reader can see what the rotation means without running the pipeline.
     */
    fun toUprightQuad(editorQuad: PerspectiveQuad, rotationQuarterTurns: Int): PerspectiveQuad =
        MainScanDetectionMapper.rotateNormalizedQuad(
            quad = editorQuad,
            rotationDegrees = normalizeQuarterTurns(-rotationQuarterTurns) * 90
        )

    /** The upright-frame quad expressed in the editor's frame — the inverse of [toUprightQuad]. */
    fun toEditorQuad(uprightQuad: PerspectiveQuad, rotationQuarterTurns: Int): PerspectiveQuad =
        MainScanDetectionMapper.rotateNormalizedQuad(
            quad = uprightQuad,
            rotationDegrees = normalizeQuarterTurns(rotationQuarterTurns) * 90
        )

    // ---- source admissibility -------------------------------------------------------------------

    /**
     * Whether [uriString] is a source this slice may render authoritatively: an app-owned `file://`
     * JPEG inside this scanner's own capture directory.
     *
     * A gallery `content://` original is refused here rather than downsampled or copied. Import is
     * out of scope for this slice, and the honest failure for an out-of-scope source is
     * [MainScanRenderFailure.UNSUPPORTED_SOURCE] — not a quietly lower-fidelity artifact that looks
     * identical in the review.
     *
     * Layered ON TOP of [MainScanFileOwnership.isOwnedFileUri], never instead of it.
     */
    fun isSupportedAuthoritativeSource(uriString: String?, filesDirPath: String): Boolean {
        if (!MainScanFileOwnership.isOwnedFileUri(uriString, filesDirPath)) return false
        val path = uriString.orEmpty().removePrefix("file://")
        return path.contains("/$CAPTURE_DIRECTORY_NAME/")
    }

    // ---- owned target paths ---------------------------------------------------------------------

    /**
     * Collision-proof names for the two siblings, on the same UUID rationale as
     * [com.dev.docscannerpdf.domain.filter.filterOutputFileName]: a timestamp is not unique enough
     * when two writes can share a millisecond, and one silently overwriting the other would leave a
     * published artifact pointing at the wrong page.
     */
    fun croppedFileName(nonce: String = UUID.randomUUID().toString()): String =
        "main-scan-cropped-$nonce.jpg"

    /** @see croppedFileName */
    fun enhancedFileName(nonce: String = UUID.randomUUID().toString()): String =
        "main-scan-enhanced-$nonce.jpg"

    // ---- memory demand --------------------------------------------------------------------------

    /** Bytes one full copy of the oriented source occupies as ARGB_8888. */
    fun sourceBytes(demand: MainScanAuthoritativeDemand): Long =
        demand.orientedSourceWidth.toLong() * demand.orientedSourceHeight.toLong() * BYTES_PER_PIXEL

    /** Bytes one full copy of the warp output occupies as ARGB_8888. */
    fun outputBytes(demand: MainScanAuthoritativeDemand): Long =
        demand.outputWidth.toLong() * demand.outputHeight.toLong() * BYTES_PER_PIXEL

    /**
     * Peak of phase A — decode, orientation, quarter turn, perspective output.
     *
     * Each rotation allocates its result while its input is still alive, so a turn costs two full
     * source copies; the warp then holds the oriented source and the output together. The peak is
     * whichever of those two moments is larger.
     */
    fun decodePhasePeakBytes(demand: MainScanAuthoritativeDemand): Long {
        val source = sourceBytes(demand)
        return maxOf(2L * source, source + outputBytes(demand))
    }

    /** Peak bitmap bytes of phase B — the authoritative enhancement. See [ENHANCE_PEAK_BITMAP_COPIES]. */
    fun enhancePhasePeakBitmapBytes(demand: MainScanAuthoritativeDemand): Long =
        ENHANCE_PEAK_BITMAP_COPIES * outputBytes(demand)

    /** Peak Java-array bytes of phase B. See [ENHANCE_PEAK_INT_BUFFER_COPIES]. */
    fun enhancePhasePeakIntBufferBytes(demand: MainScanAuthoritativeDemand): Long =
        ENHANCE_PEAK_INT_BUFFER_COPIES * outputBytes(demand)

    /** Working room for the two single-pass JPEG encodes. */
    fun encodeAllowanceBytes(demand: MainScanAuthoritativeDemand): Long =
        outputBytes(demand) / JPEG_ENCODE_DIVISOR

    /**
     * Everything the render will hold at its single worst moment, wherever the platform puts it.
     * Phase A and phase B never overlap — the oriented source is released before enhancement begins
     * — so the peak is the larger phase, not their sum.
     */
    fun totalPeakBytes(demand: MainScanAuthoritativeDemand): Long = maxOf(
        decodePhasePeakBytes(demand),
        enhancePhasePeakBitmapBytes(demand) + enhancePhasePeakIntBufferBytes(demand)
    ) + encodeAllowanceBytes(demand)

    /**
     * The part of the peak that must come out of the Java heap on THIS device.
     *
     * On API 26+ that is only the enhancement's `IntArray` buffers and the encode allowance — the
     * bitmaps beside them are native. Below 26 every bitmap is on the Java heap too, so the whole
     * peak applies.
     */
    fun requiredHeapBytes(
        demand: MainScanAuthoritativeDemand,
        bitmapsAllocateOffHeap: Boolean
    ): Long = if (bitmapsAllocateOffHeap) {
        enhancePhasePeakIntBufferBytes(demand) + encodeAllowanceBytes(demand)
    } else {
        totalPeakBytes(demand)
    }

    /**
     * The retained preview bitmaps, as a non-negative figure. Where those bytes BELONG is decided by
     * [heapReserveBytes] and [deviceReserveBytes], not here.
     */
    private fun retainedBytes(probe: MainScanMemoryProbe): Long =
        probe.retainedBitmapBytes.coerceAtLeast(0L)

    /**
     * The reserve held back from the JAVA HEAP: [FIXED_RESERVE_BYTES], plus the retained preview
     * bitmaps only where those bitmaps are actually on the Java heap.
     *
     * ## Why the retained bitmaps are conditional here and unconditional in [deviceReserveBytes]
     *
     * From API 26 `Bitmap` pixel storage is allocated in the NATIVE heap and is not bounded by
     * `Runtime.maxMemory()` at all. Charging the retained preview against Java-heap headroom on
     * those devices reserves capacity in a budget the pixels never occupied, and then measures the
     * render's Java requirement — which on API 26+ is the enhancement's `IntArray` buffers and the
     * encode allowance, nothing more — against the shortfall. That is a double count, and its cost
     * is not theoretical: it refuses full-resolution renders on modern devices that had ample room
     * for them, with no way for the user to tell the refusal was arithmetic rather than real.
     *
     * Below API 26 bitmap pixels ARE Java-heap allocations, so on that floor they belong here and
     * are counted here.
     *
     * Removing them from this budget does NOT remove them from the model: on API 26+ they move
     * WHOLE into [deviceReserveBytes], which is the budget that governs native pixels. No byte is
     * counted twice, and no byte stops being counted.
     */
    fun heapReserveBytes(probe: MainScanMemoryProbe): Long =
        if (probe.bitmapsAllocateOffHeap) {
            FIXED_RESERVE_BYTES
        } else {
            FIXED_RESERVE_BYTES + retainedBytes(probe)
        }

    /**
     * The reserve held back from DEVICE / native memory: [FIXED_RESERVE_BYTES] plus the retained
     * preview bitmaps, at every API level.
     *
     * The retained bitmaps are already allocated and therefore already absent from `availMem`, so
     * holding them back again is deliberately conservative. That conservatism is the right way
     * round for admission control — over-reserving only ever refuses a render that might have fit,
     * while under-reserving hands back an `OutOfMemoryError` in the middle of one — and it is what
     * keeps native preview pixels represented in the one budget they genuinely occupy.
     */
    fun deviceReserveBytes(probe: MainScanMemoryProbe): Long =
        FIXED_RESERVE_BYTES + retainedBytes(probe)

    /** Java-heap headroom after [heapReserveBytes]. Never negative. */
    fun heapHeadroomBytes(probe: MainScanMemoryProbe): Long =
        (probe.maxHeapBytes - probe.usedHeapBytes - heapReserveBytes(probe)).coerceAtLeast(0L)

    /**
     * Device headroom after [deviceReserveBytes] and the platform's own low-memory threshold — what
     * the process can still obtain before the killer engages. Zero when the probe carried no device
     * figures, which [admits] treats as a refusal rather than as permission.
     */
    fun deviceHeadroomBytes(probe: MainScanMemoryProbe): Long {
        if (probe.deviceAvailableBytes <= 0L) return 0L
        return (
            probe.deviceAvailableBytes -
                probe.deviceLowMemoryThresholdBytes.coerceAtLeast(0L) -
                deviceReserveBytes(probe)
            ).coerceAtLeast(0L)
    }

    /**
     * Whether the render may begin. Deterministic: the same demand and probe always give the same
     * answer, so the refusal path is testable without provoking a real OutOfMemoryError.
     *
     * Both bounds must admit it. Where bitmaps are native the device bound governs the pixels while
     * the heap bound still governs the enhancement's Java arrays — checking only one of the two is
     * how a render passes admission and then dies on the half nobody measured.
     */
    fun admits(demand: MainScanAuthoritativeDemand, probe: MainScanMemoryProbe): Boolean {
        // Degenerate and unrepresentable demands are refused before a single byte figure is
        // computed — an overflowed requirement would compare as "fits" against every headroom.
        if (!isRepresentable(demand)) return false
        if (probe.deviceLowMemory) return false
        if (requiredHeapBytes(demand, probe.bitmapsAllocateOffHeap) > heapHeadroomBytes(probe)) {
            return false
        }
        if (!probe.bitmapsAllocateOffHeap) return true
        // API 26+: the pixels come from the device, so a device reading is required. A probe that
        // could not obtain one is refused rather than admitted on the heap check alone.
        val deviceHeadroom = deviceHeadroomBytes(probe)
        if (deviceHeadroom <= 0L) return false
        return totalPeakBytes(demand) <= deviceHeadroom
    }

    // ---- candidate validation ---------------------------------------------------------------------

    /**
     * The gate a written candidate must pass before it may be published, returning null when it may
     * and the reason when it may not.
     *
     * Every check here is a thing that has to be TRUE of the files on disk, not of the intent that
     * produced them: a compress call can return true and leave a truncated file, a directory can
     * vanish, and a decode can come back at dimensions nobody asked for. Publishing on the strength
     * of "no exception was thrown" is exactly how a partially written page becomes the one the
     * library keeps.
     */
    fun validateCandidate(
        sourceSampleSize: Int,
        expectedWidth: Int,
        expectedHeight: Int,
        croppedExists: Boolean,
        croppedLengthBytes: Long,
        croppedDecodedWidth: Int,
        croppedDecodedHeight: Int,
        enhancedExists: Boolean,
        enhancedLengthBytes: Long,
        enhancedDecodedWidth: Int,
        enhancedDecodedHeight: Int
    ): MainScanRenderFailure? {
        if (sourceSampleSize != AUTHORITATIVE_SAMPLE_SIZE) return MainScanRenderFailure.DECODE
        if (expectedWidth <= 0 || expectedHeight <= 0) return MainScanRenderFailure.WARP
        if (!croppedExists || croppedLengthBytes <= 0L) return MainScanRenderFailure.WRITE
        if (!enhancedExists || enhancedLengthBytes <= 0L) return MainScanRenderFailure.WRITE
        if (croppedDecodedWidth != expectedWidth || croppedDecodedHeight != expectedHeight) {
            return MainScanRenderFailure.WRITE
        }
        if (enhancedDecodedWidth != expectedWidth || enhancedDecodedHeight != expectedHeight) {
            return MainScanRenderFailure.WRITE
        }
        return null
    }
}
