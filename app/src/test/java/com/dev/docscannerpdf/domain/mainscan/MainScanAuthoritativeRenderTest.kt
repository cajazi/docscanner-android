package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authoritative post-crop artifact contract: what may become one, what it costs, and what
 * happens when it cannot be produced.
 *
 * ## The defect this suite exists to make unreachable
 *
 * The crop editor works on a bitmap downsampled to 2048 px. It looks correct, it behaves correctly,
 * and it is the wrong file to save — a 4080x3060 capture reaches it as 2040x1530. Because both the
 * preview and the finished page were plain `Bitmap`s, nothing structural prevented the preview from
 * being handed to whatever eventually persists a document, and the UI would have looked identical
 * either way. Every assertion below is aimed at that: the artifact type refuses a downsampled
 * origin, refusals never become successes, and the numbers admission control decides on are fixed
 * rather than sampled.
 *
 * ## Why the memory rules are asserted as exact byte counts
 *
 * An estimate nobody can reproduce is not admission control, it is a guess with a threshold. The
 * expected values here are computed by hand from the pixel dimensions and written out in full, so a
 * change to the model has to be a deliberate edit to this file rather than a number that quietly
 * drifted.
 */
class MainScanAuthoritativeRenderTest {

    private companion object {

        /** A 12.5 MP portrait capture — the dimensions the shipped camera path actually produces. */
        const val SOURCE_WIDTH = 3060
        const val SOURCE_HEIGHT = 4080

        /** A plausible de-skewed page from that capture. */
        const val OUTPUT_WIDTH = 2400
        const val OUTPUT_HEIGHT = 3200

        const val MIB = 1024L * 1024L

        val FILES_DIR = "/data/user/0/com.dev.docscannerpdf/files"

        val DEMAND = MainScanAuthoritativeDemand(
            orientedSourceWidth = SOURCE_WIDTH,
            orientedSourceHeight = SOURCE_HEIGHT,
            outputWidth = OUTPUT_WIDTH,
            outputHeight = OUTPUT_HEIGHT
        )

        // 3060 * 4080 * 4
        const val SOURCE_BYTES = 49_939_200L

        // 2400 * 3200 * 4
        const val OUTPUT_BYTES = 30_720_000L

        fun artifact(
            croppedUri: String = "file://$FILES_DIR/main_scan_cropped/a.jpg",
            enhancedUri: String = "file://$FILES_DIR/main_scan_enhanced/b.jpg",
            pixelWidth: Int = OUTPUT_WIDTH,
            pixelHeight: Int = OUTPUT_HEIGHT,
            sourceSampleSize: Int = 1,
            rotationQuarterTurns: Int = 0
        ) = MainScanAuthoritativeArtifact(
            croppedUri = croppedUri,
            enhancedUri = enhancedUri,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            sourceSampleSize = sourceSampleSize,
            rotationQuarterTurns = rotationQuarterTurns
        )

        /** A probe with plenty of everything, on an API 26+ device. */
        fun generousProbe(
            retainedBitmapBytes: Long = 40 * MIB,
            bitmapsAllocateOffHeap: Boolean = true
        ) = MainScanMemoryProbe(
            maxHeapBytes = 256 * MIB,
            usedHeapBytes = 64 * MIB,
            deviceAvailableBytes = 2048 * MIB,
            deviceLowMemoryThresholdBytes = 200 * MIB,
            deviceLowMemory = false,
            retainedBitmapBytes = retainedBitmapBytes,
            bitmapsAllocateOffHeap = bitmapsAllocateOffHeap
        )
    }

    // --- the artifact type cannot be built from a preview -------------------------------------

    @Test
    fun aFullResolutionResultIsAValidArtifact() {
        val subject = artifact()
        assertEquals(OUTPUT_WIDTH, subject.pixelWidth)
        assertEquals(OUTPUT_HEIGHT, subject.pixelHeight)
        assertEquals(
            "authority is defined by a full-resolution decode",
            MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
            subject.sourceSampleSize
        )
    }

    @Test
    fun aDownsampledSourceCanNeverBecomeAnAuthoritativeArtifact() {
        // Sample size 2 is exactly what the preview loader picks for this capture. If the type
        // accepted it, the preview path and the authoritative path would produce the same kind of
        // value and the compiler would stop distinguishing them.
        for (sampleSize in listOf(2, 4, 8)) {
            assertThrows(IllegalArgumentException::class.java) {
                artifact(sourceSampleSize = sampleSize)
            }
        }
    }

    @Test
    fun theCameraSamplePolicyIsExactlyOne() {
        assertEquals(1, MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE)
    }

    @Test
    fun anArtifactWithoutRealPixelsIsNotRepresentable() {
        assertThrows(IllegalArgumentException::class.java) { artifact(pixelWidth = 0) }
        assertThrows(IllegalArgumentException::class.java) { artifact(pixelHeight = 0) }
        assertThrows(IllegalArgumentException::class.java) { artifact(pixelWidth = -1) }
    }

    @Test
    fun anArtifactMustCarryTwoDistinctSiblings() {
        val shared = "file://$FILES_DIR/main_scan_cropped/a.jpg"
        assertThrows(IllegalArgumentException::class.java) {
            artifact(croppedUri = shared, enhancedUri = shared)
        }
        assertThrows(IllegalArgumentException::class.java) { artifact(croppedUri = "") }
        assertThrows(IllegalArgumentException::class.java) { artifact(enhancedUri = "   ") }
    }

    @Test
    fun rotationIsRecordedAsAQuarterTurnAndNothingElse() {
        for (turns in 0..3) {
            assertEquals(turns, artifact(rotationQuarterTurns = turns).rotationQuarterTurns)
        }
        assertThrows(IllegalArgumentException::class.java) { artifact(rotationQuarterTurns = 4) }
        assertThrows(IllegalArgumentException::class.java) { artifact(rotationQuarterTurns = -1) }
    }

    // --- a preview candidate fails validation --------------------------------------------------

    @Test
    fun aPreviewSizedCandidateIsRejectedRatherThanPublished() {
        // What a preview-derived write would look like on disk: real files, plausible content, and
        // dimensions bounded by the interactive decode rather than by the source.
        val rejection = MainScanAuthoritativeRender.validateCandidate(
            sourceSampleSize = 1,
            expectedWidth = OUTPUT_WIDTH,
            expectedHeight = OUTPUT_HEIGHT,
            croppedExists = true,
            croppedLengthBytes = 900_000L,
            croppedDecodedWidth = 1200,
            croppedDecodedHeight = 1600,
            enhancedExists = true,
            enhancedLengthBytes = 800_000L,
            enhancedDecodedWidth = 1200,
            enhancedDecodedHeight = 1600
        )
        assertEquals(MainScanRenderFailure.WRITE, rejection)
    }

    @Test
    fun aDownsampledDecodeIsRejectedByValidationToo() {
        val rejection = MainScanAuthoritativeRender.validateCandidate(
            sourceSampleSize = 2,
            expectedWidth = OUTPUT_WIDTH,
            expectedHeight = OUTPUT_HEIGHT,
            croppedExists = true,
            croppedLengthBytes = 1L,
            croppedDecodedWidth = OUTPUT_WIDTH,
            croppedDecodedHeight = OUTPUT_HEIGHT,
            enhancedExists = true,
            enhancedLengthBytes = 1L,
            enhancedDecodedWidth = OUTPUT_WIDTH,
            enhancedDecodedHeight = OUTPUT_HEIGHT
        )
        assertEquals(MainScanRenderFailure.DECODE, rejection)
    }

    @Test
    fun aFullyValidCandidatePassesValidation() {
        assertNull(
            MainScanAuthoritativeRender.validateCandidate(
                sourceSampleSize = 1,
                expectedWidth = OUTPUT_WIDTH,
                expectedHeight = OUTPUT_HEIGHT,
                croppedExists = true,
                croppedLengthBytes = 3_400_000L,
                croppedDecodedWidth = OUTPUT_WIDTH,
                croppedDecodedHeight = OUTPUT_HEIGHT,
                enhancedExists = true,
                enhancedLengthBytes = 2_900_000L,
                enhancedDecodedWidth = OUTPUT_WIDTH,
                enhancedDecodedHeight = OUTPUT_HEIGHT
            )
        )
    }

    @Test
    fun aMissingOrEmptySiblingIsAWriteFailure() {
        fun validate(
            croppedExists: Boolean = true,
            croppedLength: Long = 10L,
            enhancedExists: Boolean = true,
            enhancedLength: Long = 10L
        ) = MainScanAuthoritativeRender.validateCandidate(
            sourceSampleSize = 1,
            expectedWidth = OUTPUT_WIDTH,
            expectedHeight = OUTPUT_HEIGHT,
            croppedExists = croppedExists,
            croppedLengthBytes = croppedLength,
            croppedDecodedWidth = OUTPUT_WIDTH,
            croppedDecodedHeight = OUTPUT_HEIGHT,
            enhancedExists = enhancedExists,
            enhancedLengthBytes = enhancedLength,
            enhancedDecodedWidth = OUTPUT_WIDTH,
            enhancedDecodedHeight = OUTPUT_HEIGHT
        )

        assertEquals(MainScanRenderFailure.WRITE, validate(croppedExists = false))
        assertEquals(MainScanRenderFailure.WRITE, validate(croppedLength = 0L))
        assertEquals(MainScanRenderFailure.WRITE, validate(enhancedExists = false))
        assertEquals(MainScanRenderFailure.WRITE, validate(enhancedLength = 0L))
    }

    @Test
    fun aSiblingThatDisagreesOnDimensionsIsRejected() {
        // Only the enhanced sibling is off by a pixel. Publishing this pair would give a future
        // Confirm two files that are supposed to be the same page and are not.
        val rejection = MainScanAuthoritativeRender.validateCandidate(
            sourceSampleSize = 1,
            expectedWidth = OUTPUT_WIDTH,
            expectedHeight = OUTPUT_HEIGHT,
            croppedExists = true,
            croppedLengthBytes = 10L,
            croppedDecodedWidth = OUTPUT_WIDTH,
            croppedDecodedHeight = OUTPUT_HEIGHT,
            enhancedExists = true,
            enhancedLengthBytes = 10L,
            enhancedDecodedWidth = OUTPUT_WIDTH,
            enhancedDecodedHeight = OUTPUT_HEIGHT - 1
        )
        assertEquals(MainScanRenderFailure.WRITE, rejection)
    }

    // --- no failure ever becomes a success ------------------------------------------------------

    @Test
    fun noFailureReasonCarriesAnArtifact() {
        for (reason in MainScanRenderFailure.entries) {
            val outcome: MainScanRenderOutcome = MainScanRenderOutcome.NonAuthoritative(reason)
            assertFalse("$reason must not read as authoritative", outcome.isAuthoritative)
            assertNull("$reason must carry no artifact", outcome.artifactOrNull)
            assertFalse(
                "$reason must not be an Authoritative outcome",
                outcome is MainScanRenderOutcome.Authoritative
            )
        }
    }

    @Test
    fun onlyTheAuthoritativeOutcomeYieldsAnArtifact() {
        val subject = artifact()
        val outcome: MainScanRenderOutcome = MainScanRenderOutcome.Authoritative(subject)
        assertTrue(outcome.isAuthoritative)
        assertEquals(subject, outcome.artifactOrNull)
    }

    // --- unsupported sources fail closed --------------------------------------------------------

    @Test
    fun onlyAnAppOwnedCaptureFileIsAnAuthoritativeSource() {
        assertTrue(
            MainScanAuthoritativeRender.isSupportedAuthoritativeSource(
                "file://$FILES_DIR/main_scan_capture/page.jpg",
                FILES_DIR
            )
        )
    }

    @Test
    fun aGalleryOriginalIsRefusedRatherThanRenderedAtWhateverItOffers() {
        // Import is out of scope for this slice. The honest answer is "unsupported", never a
        // quietly lower-fidelity artifact that looks identical in the review.
        for (uri in listOf(
            "content://media/external/images/media/42",
            "file:///sdcard/DCIM/Camera/IMG_0001.jpg",
            "https://example.com/page.jpg",
            "",
            null
        )) {
            assertFalse(
                "$uri must not be an authoritative source",
                MainScanAuthoritativeRender.isSupportedAuthoritativeSource(uri, FILES_DIR)
            )
        }
    }

    @Test
    fun aDerivedArtifactIsNotItselfAnAuthoritativeSource() {
        // Re-rendering FROM a written sibling would add a JPEG generation and make the result a
        // copy of a copy. Only the camera capture is a source.
        assertFalse(
            MainScanAuthoritativeRender.isSupportedAuthoritativeSource(
                "file://$FILES_DIR/main_scan_cropped/a.jpg",
                FILES_DIR
            )
        )
        assertFalse(
            MainScanAuthoritativeRender.isSupportedAuthoritativeSource(
                "file://$FILES_DIR/main_scan_enhanced/b.jpg",
                FILES_DIR
            )
        )
    }

    @Test
    fun traversalOutOfPrivateStorageIsRefused() {
        assertFalse(
            MainScanAuthoritativeRender.isSupportedAuthoritativeSource(
                "file://$FILES_DIR/../../elsewhere/main_scan_capture/page.jpg",
                FILES_DIR
            )
        )
    }

    // --- the memory model is deterministic and exact ---------------------------------------------

    @Test
    fun theByteEstimatesAreTheHandComputedOnes() {
        assertEquals(SOURCE_BYTES, MainScanAuthoritativeRender.sourceBytes(DEMAND))
        assertEquals(OUTPUT_BYTES, MainScanAuthoritativeRender.outputBytes(DEMAND))

        // Phase A: a quarter turn holds two full source copies, which beats source + output here.
        assertEquals(
            2 * SOURCE_BYTES,
            MainScanAuthoritativeRender.decodePhasePeakBytes(DEMAND)
        )
        assertEquals(
            3 * OUTPUT_BYTES,
            MainScanAuthoritativeRender.enhancePhasePeakBitmapBytes(DEMAND)
        )
        assertEquals(
            2 * OUTPUT_BYTES,
            MainScanAuthoritativeRender.enhancePhasePeakIntBufferBytes(DEMAND)
        )
        assertEquals(OUTPUT_BYTES / 4, MainScanAuthoritativeRender.encodeAllowanceBytes(DEMAND))

        // The two phases do not overlap, so the peak is the larger one plus the encode allowance.
        assertEquals(
            maxOf(2 * SOURCE_BYTES, 5 * OUTPUT_BYTES) + OUTPUT_BYTES / 4,
            MainScanAuthoritativeRender.totalPeakBytes(DEMAND)
        )
    }

    @Test
    fun decodePhaseFollowsTheWarpWhenTheOutputIsLargerThanTheSource() {
        // A quad wider than its source (an extreme de-skew) makes source + output the peak instead.
        val wide = MainScanAuthoritativeDemand(
            orientedSourceWidth = 1000,
            orientedSourceHeight = 1000,
            outputWidth = 4000,
            outputHeight = 4000
        )
        val source = 1000L * 1000L * 4L
        val output = 4000L * 4000L * 4L
        assertEquals(source + output, MainScanAuthoritativeRender.decodePhasePeakBytes(wide))
    }

    @Test
    fun theSameDemandAndProbeAlwaysGiveTheSameAnswer() {
        val probe = generousProbe()
        val peak = MainScanAuthoritativeRender.totalPeakBytes(DEMAND)
        val verdict = MainScanAuthoritativeRender.admits(DEMAND, probe)
        repeat(50) {
            assertEquals(peak, MainScanAuthoritativeRender.totalPeakBytes(DEMAND))
            assertEquals(verdict, MainScanAuthoritativeRender.admits(DEMAND, probe))
            // A structurally equal probe must decide identically — nothing here may read a clock,
            // a random source, or any state outside its arguments.
            assertEquals(verdict, MainScanAuthoritativeRender.admits(DEMAND, generousProbe()))
        }
    }

    @Test
    fun theRetainedPreviewIsReservedInTheBudgetThatActuallyHoldsIt() {
        // API 26+: the retained preview's pixels are NATIVE. They belong in the device reserve in
        // full, and nowhere in the Java-heap reserve — the heap never held them.
        val modern = generousProbe(retainedBitmapBytes = 40 * MIB)
        assertEquals(
            "native preview pixels must not be charged against the Java heap",
            MainScanAuthoritativeRender.FIXED_RESERVE_BYTES,
            MainScanAuthoritativeRender.heapReserveBytes(modern)
        )
        assertEquals(
            "and they must still be charged in full against device memory",
            MainScanAuthoritativeRender.FIXED_RESERVE_BYTES + 40 * MIB,
            MainScanAuthoritativeRender.deviceReserveBytes(modern)
        )

        // API 23-25: bitmap pixels ARE Java-heap allocations, so both budgets hold them.
        val legacy = modern.copy(bitmapsAllocateOffHeap = false)
        assertEquals(
            "on the old floor the preview really is on the Java heap",
            MainScanAuthoritativeRender.FIXED_RESERVE_BYTES + 40 * MIB,
            MainScanAuthoritativeRender.heapReserveBytes(legacy)
        )

        // A negative or absent retained figure never shrinks either reserve below the floor.
        for (probe in listOf(modern, legacy)) {
            assertEquals(
                MainScanAuthoritativeRender.FIXED_RESERVE_BYTES,
                MainScanAuthoritativeRender.heapReserveBytes(
                    probe.copy(retainedBitmapBytes = -1L)
                )
            )
            assertEquals(
                MainScanAuthoritativeRender.FIXED_RESERVE_BYTES,
                MainScanAuthoritativeRender.deviceReserveBytes(
                    probe.copy(retainedBitmapBytes = -1L)
                )
            )
        }
    }

    @Test
    fun theReserveIsNotAPercentageOfTheHeap() {
        // The whole point of a fixed floor: doubling the heap must not double what is held back.
        val small = generousProbe(retainedBitmapBytes = 0L)
        val large = small.copy(maxHeapBytes = small.maxHeapBytes * 4)
        assertEquals(
            MainScanAuthoritativeRender.heapReserveBytes(small),
            MainScanAuthoritativeRender.heapReserveBytes(large)
        )
        assertEquals(
            MainScanAuthoritativeRender.deviceReserveBytes(small),
            MainScanAuthoritativeRender.deviceReserveBytes(large)
        )
    }

    // --- the retained native preview is counted ONCE, in the budget that holds it ------------------
    //
    // The defect these close: on API 26+ the retained preview's bytes were subtracted from Java-heap
    // headroom, and the render's Java requirement was then measured against what was left. Those
    // pixels were never on that heap, so the subtraction reserved capacity in a budget they do not
    // occupy — and refused full-resolution renders on devices with ample room for them. The fix has
    // to MOVE those bytes, not delete them: taken out of both budgets they would stop being counted
    // at all, which is the opposite error and a worse one.

    @Test
    fun aModernDeviceIsNoLongerRefusedForPreviewBytesTheJavaHeapNeverHeld() {
        val retained = 48 * MIB
        val probe = MainScanMemoryProbe(
            maxHeapBytes = 160 * MIB,
            usedHeapBytes = 40 * MIB,
            deviceAvailableBytes = 2048 * MIB,
            deviceLowMemoryThresholdBytes = 200 * MIB,
            deviceLowMemory = false,
            retainedBitmapBytes = retained,
            bitmapsAllocateOffHeap = true
        )

        val required =
            MainScanAuthoritativeRender.requiredHeapBytes(DEMAND, bitmapsAllocateOffHeap = true)

        // What the double-counting model computed: the same heap, minus the native preview.
        val doubleCountedHeadroom = probe.maxHeapBytes -
            probe.usedHeapBytes -
            MainScanAuthoritativeRender.FIXED_RESERVE_BYTES -
            retained
        assertTrue(
            "this probe must be one the double-counting model refused, or it proves nothing",
            doubleCountedHeadroom < required
        )

        // What is actually true: the Java heap only has to hold the enhancement's IntArrays and the
        // encode allowance, and it has 96 MiB free for 65.9 MiB of them.
        assertEquals(
            probe.maxHeapBytes - probe.usedHeapBytes -
                MainScanAuthoritativeRender.FIXED_RESERVE_BYTES,
            MainScanAuthoritativeRender.heapHeadroomBytes(probe)
        )
        assertTrue(
            "a device with real room must not be refused by arithmetic",
            MainScanAuthoritativeRender.admits(DEMAND, probe)
        )
    }

    @Test
    fun theRetainedNativeBytesStillConstrainTheDeviceBudget() {
        // Same probe twice, differing ONLY in the retained preview, with a Java heap far too large
        // to be the deciding bound. If removing those bytes from the heap reserve had removed them
        // from the model, both would admit. The device budget still holds them, so one refuses.
        val base = MainScanMemoryProbe(
            maxHeapBytes = 512 * MIB,
            usedHeapBytes = 32 * MIB,
            deviceAvailableBytes = 200 * MIB,
            deviceLowMemoryThresholdBytes = 0L,
            deviceLowMemory = false,
            retainedBitmapBytes = 0L,
            bitmapsAllocateOffHeap = true
        )
        val withPreview = base.copy(retainedBitmapBytes = 48 * MIB)

        assertTrue(
            "without a retained preview this device has room",
            MainScanAuthoritativeRender.admits(DEMAND, base)
        )
        assertFalse(
            "the retained preview's native bytes must still be able to refuse a render",
            MainScanAuthoritativeRender.admits(DEMAND, withPreview)
        )
        assertEquals(
            "device headroom must shrink by exactly the retained bytes — no more, no less",
            MainScanAuthoritativeRender.deviceHeadroomBytes(base) - 48 * MIB,
            MainScanAuthoritativeRender.deviceHeadroomBytes(withPreview)
        )
        assertEquals(
            "and the Java heap must be untouched by them",
            MainScanAuthoritativeRender.heapHeadroomBytes(base),
            MainScanAuthoritativeRender.heapHeadroomBytes(withPreview)
        )
    }

    @Test
    fun onApiTwentyThreeToTwentyFiveTheRetainedPreviewStillCostsJavaHeap() {
        // Below API 26 the preview's pixels really are Java-heap allocations. Same heap, same
        // demand; the only difference is a large retained preview, and it must still decide.
        val base = MainScanMemoryProbe(
            maxHeapBytes = 512 * MIB,
            usedHeapBytes = 32 * MIB,
            deviceAvailableBytes = 0L,
            deviceLowMemoryThresholdBytes = 0L,
            deviceLowMemory = false,
            retainedBitmapBytes = 0L,
            bitmapsAllocateOffHeap = false
        )
        assertTrue(MainScanAuthoritativeRender.admits(DEMAND, base))
        assertFalse(
            "a 320 MiB retained preview must still be subtracted from a pre-Oreo Java heap",
            MainScanAuthoritativeRender.admits(DEMAND, base.copy(retainedBitmapBytes = 320 * MIB))
        )
        assertEquals(
            MainScanAuthoritativeRender.heapHeadroomBytes(base) - 320 * MIB,
            MainScanAuthoritativeRender.heapHeadroomBytes(
                base.copy(retainedBitmapBytes = 320 * MIB)
            )
        )
    }

    @Test
    fun realHeadroomShortfallStillRefusesOnBothBudgets() {
        // The correction must not become permissiveness. Each of these genuinely lacks one real
        // resource, and each must still be refused.
        val heapStarved = generousProbe(retainedBitmapBytes = 0L).copy(
            maxHeapBytes = 96 * MIB,
            usedHeapBytes = 48 * MIB
        )
        assertTrue(
            "the Java requirement must genuinely exceed this heap",
            MainScanAuthoritativeRender.requiredHeapBytes(DEMAND, bitmapsAllocateOffHeap = true) >
                MainScanAuthoritativeRender.heapHeadroomBytes(heapStarved)
        )
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, heapStarved))

        val deviceStarved = generousProbe(retainedBitmapBytes = 0L).copy(
            deviceAvailableBytes = 160 * MIB,
            deviceLowMemoryThresholdBytes = 0L
        )
        assertTrue(
            "the peak must genuinely exceed this device's headroom",
            MainScanAuthoritativeRender.totalPeakBytes(DEMAND) >
                MainScanAuthoritativeRender.deviceHeadroomBytes(deviceStarved)
        )
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, deviceStarved))
    }

    // --- the byte model cannot be talked into wrapping ---------------------------------------------

    @Test
    fun anUnrepresentableDemandIsRefusedRatherThanWrappingIntoAdmission() {
        val absurd = MainScanAuthoritativeDemand(
            orientedSourceWidth = Int.MAX_VALUE,
            orientedSourceHeight = Int.MAX_VALUE,
            outputWidth = Int.MAX_VALUE,
            outputHeight = Int.MAX_VALUE
        )
        // The hazard, stated: the byte figure for such a demand is NEGATIVE, and a negative
        // requirement compares as "fits" against every headroom there is.
        assertTrue(
            "the hazard this guard exists for must be real",
            MainScanAuthoritativeRender.sourceBytes(absurd) < 0L
        )
        assertFalse(MainScanAuthoritativeRender.isRepresentable(absurd))
        assertFalse(
            "an unrepresentable demand must be refused before any byte figure is compared",
            MainScanAuthoritativeRender.admits(absurd, generousProbe())
        )

        // The boundary is exact, and four gigapixels is far above anything a camera produces.
        assertTrue(
            MainScanAuthoritativeRender.isRepresentable(
                MainScanAuthoritativeDemand(65_536, 65_536, 65_536, 65_536)
            )
        )
        assertFalse(
            MainScanAuthoritativeRender.isRepresentable(
                MainScanAuthoritativeDemand(65_536, 65_537, 65_536, 65_536)
            )
        )
        assertFalse(MainScanAuthoritativeRender.isRepresentable(DEMAND.copy(outputWidth = -1)))
        assertTrue(MainScanAuthoritativeRender.isRepresentable(DEMAND))
    }

    // --- the reproduced frame must be the frame the user confirmed against -------------------------

    @Test
    fun theReproducedFrameMustBeTheEditorsOwnFrameDownsampled() {
        // The real case: a 12.5 MP capture reaches the editor at sample size 2.
        assertTrue(
            MainScanAuthoritativeRender.reproducesEditorFrame(
                reproducedWidth = SOURCE_WIDTH,
                reproducedHeight = SOURCE_HEIGHT,
                editorFrameWidth = SOURCE_WIDTH / 2,
                editorFrameHeight = SOURCE_HEIGHT / 2
            )
        )
        // A capture small enough to need no downsample at all.
        assertTrue(MainScanAuthoritativeRender.reproducesEditorFrame(1600, 1200, 1600, 1200))
        // And one that needed sample size 4.
        assertTrue(MainScanAuthoritativeRender.reproducesEditorFrame(8160, 6120, 2040, 1530))
        // Odd dimensions: BitmapFactory may round either way, and both roundings are one frame.
        assertTrue(MainScanAuthoritativeRender.reproducesEditorFrame(3061, 4081, 1530, 2040))
        assertTrue(MainScanAuthoritativeRender.reproducesEditorFrame(3061, 4081, 1531, 2041))
    }

    @Test
    fun aSecondExifReadThatDisagreesWithTheFirstFailsTheFrameCheck() {
        // The editor saw a 1530x2040 portrait preview. The authoritative render read EXIF again and
        // reproduced a LANDSCAPE frame — internally consistent, and cropped somewhere the user never
        // indicated. No single downsample factor relates the two, so it is refused.
        assertFalse(
            MainScanAuthoritativeRender.reproducesEditorFrame(
                reproducedWidth = SOURCE_HEIGHT,
                reproducedHeight = SOURCE_WIDTH,
                editorFrameWidth = SOURCE_WIDTH / 2,
                editorFrameHeight = SOURCE_HEIGHT / 2
            )
        )
        // A frame that agrees on one axis and not the other is not a downsample of anything.
        assertFalse(
            MainScanAuthoritativeRender.reproducesEditorFrame(
                SOURCE_WIDTH,
                SOURCE_HEIGHT,
                SOURCE_WIDTH / 2,
                SOURCE_HEIGHT / 4
            )
        )
        // The editor works on a downsample; a LARGER editor frame is a different frame entirely.
        assertFalse(MainScanAuthoritativeRender.reproducesEditorFrame(1530, 2040, 3060, 4080))
        // An unknown editor frame is not a matching one.
        for (dimension in listOf(0, -1)) {
            assertFalse(
                MainScanAuthoritativeRender.reproducesEditorFrame(
                    SOURCE_WIDTH,
                    SOURCE_HEIGHT,
                    dimension,
                    SOURCE_HEIGHT / 2
                )
            )
            assertFalse(
                MainScanAuthoritativeRender.reproducesEditorFrame(
                    dimension,
                    SOURCE_HEIGHT,
                    SOURCE_WIDTH / 2,
                    SOURCE_HEIGHT / 2
                )
            )
        }
    }

    @Test
    fun aFrameMismatchIsItsOwnFailureAndCarriesNoArtifact() {
        val outcome = MainScanRenderOutcome.NonAuthoritative(
            MainScanRenderFailure.EDITOR_FRAME_MISMATCH
        )
        assertNull(
            "a frame mismatch must never publish — least of all a preview",
            outcome.artifactOrNull
        )
        assertFalse(outcome.isAuthoritative)
    }

    @Test
    fun theFrameCheckIsDecidedBeforeTheWarpIsPlanned() {
        val body = functionBody(processorSource(), "renderAuthoritative")
        val check = body.indexOf("MainScanAuthoritativeRender.reproducesEditorFrame(")
        val plan = body.indexOf("PerspectiveTransformEngine.plan(")
        assertTrue("the editor-frame check must exist in the production path", check >= 0)
        assertTrue("the warp must be planned in the production path", plan >= 0)
        assertTrue(
            "a frame the user never confirmed against must not reach the transform engine at all",
            check < plan
        )
        assertTrue(
            "and the mismatch must fail closed with its own reason",
            body.contains("MainScanRenderFailure.EDITOR_FRAME_MISMATCH")
        )
    }

    // --- refusal happens on the numbers, not on an OutOfMemoryError -------------------------------

    @Test
    fun aHealthyModernDeviceIsAdmitted() {
        assertTrue(MainScanAuthoritativeRender.admits(DEMAND, generousProbe()))
    }

    @Test
    fun aDeviceCloseToItsLowMemoryThresholdIsRefused() {
        val probe = generousProbe().copy(
            deviceAvailableBytes = 220 * MIB,
            deviceLowMemoryThresholdBytes = 200 * MIB
        )
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, probe))
        assertEquals(0L, MainScanAuthoritativeRender.deviceHeadroomBytes(probe))
    }

    @Test
    fun aDeviceAlreadyReportingLowMemoryIsRefusedOutright() {
        assertFalse(
            MainScanAuthoritativeRender.admits(DEMAND, generousProbe().copy(deviceLowMemory = true))
        )
    }

    @Test
    fun anUnreadableDeviceReadingRefusesRatherThanAssumingRoom() {
        // On API 26+ the pixels come from the device. A probe that could not obtain a device figure
        // knows nothing about the bound that actually matters, and "unknown" must not mean "yes".
        val probe = generousProbe().copy(deviceAvailableBytes = 0L)
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, probe))
    }

    @Test
    fun theJavaHeapBoundStillAppliesWhereBitmapsAreNative() {
        // The enhancement's two full-image IntArray buffers are ordinary Java arrays at every API
        // level. A device with acres of free RAM and a nearly full heap must still be refused.
        val probe = generousProbe().copy(
            maxHeapBytes = 128 * MIB,
            usedHeapBytes = 96 * MIB
        )
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, probe))
        assertTrue(
            "the device side alone would have admitted it",
            MainScanAuthoritativeRender.totalPeakBytes(DEMAND) <=
                MainScanAuthoritativeRender.deviceHeadroomBytes(probe)
        )
    }

    @Test
    fun onlyTheJavaArraysAndTheEncodeAreChargedToTheHeapOnModernDevices() {
        assertEquals(
            2 * OUTPUT_BYTES + OUTPUT_BYTES / 4,
            MainScanAuthoritativeRender.requiredHeapBytes(DEMAND, bitmapsAllocateOffHeap = true)
        )
        assertEquals(
            MainScanAuthoritativeRender.totalPeakBytes(DEMAND),
            MainScanAuthoritativeRender.requiredHeapBytes(DEMAND, bitmapsAllocateOffHeap = false)
        )
    }

    @Test
    fun aPreOreoHeapTooSmallForTheCaptureIsRefused() {
        // API 23-25: every bitmap is on the Java heap, so a 12.5 MP render simply does not fit in a
        // 192 MB heap that is already holding the previews. Refusing is correct; downgrading is not.
        val probe = MainScanMemoryProbe(
            maxHeapBytes = 192 * MIB,
            usedHeapBytes = 48 * MIB,
            deviceAvailableBytes = 2048 * MIB,
            deviceLowMemoryThresholdBytes = 200 * MIB,
            deviceLowMemory = false,
            retainedBitmapBytes = 40 * MIB,
            bitmapsAllocateOffHeap = false
        )
        assertFalse(MainScanAuthoritativeRender.admits(DEMAND, probe))
    }

    @Test
    fun theApiTwentyThreeToTwentyFiveShortfallIsMeasuredRatherThanAssumed() {
        // The correction above moved native preview bytes out of the Java heap on API 26+. It
        // changes NOTHING below 26, where those bytes really are on that heap — so the pre-Oreo
        // limit has to be re-measured rather than assumed to have gone with it.
        //
        // A generous stock pre-Oreo device: a 192 MiB heap (this app declares no largeHeap), 48 MiB
        // already in use, and ~28 MiB of retained previews. The numbers are pinned so the day this
        // stops being true, it is this assertion that says so.
        val retained = 28 * MIB
        val probe = MainScanMemoryProbe(
            maxHeapBytes = 192 * MIB,
            usedHeapBytes = 48 * MIB,
            deviceAvailableBytes = 2048 * MIB,
            deviceLowMemoryThresholdBytes = 200 * MIB,
            deviceLowMemory = false,
            retainedBitmapBytes = retained,
            bitmapsAllocateOffHeap = false
        )
        val required =
            MainScanAuthoritativeRender.requiredHeapBytes(DEMAND, bitmapsAllocateOffHeap = false)
        assertEquals(
            "below API 26 the whole peak is a Java-heap requirement",
            MainScanAuthoritativeRender.totalPeakBytes(DEMAND),
            required
        )
        assertFalse(
            "a 12.5 MP full-resolution render still does not fit a stock pre-Oreo heap",
            MainScanAuthoritativeRender.admits(DEMAND, probe)
        )

        // What it WOULD take, stated rather than implied: about 254 MiB of heap, which no stock
        // API 23-25 device provides. Refusing is the correct answer; sampling down is not, and this
        // path has no downgrade to reach for.
        val neededHeap = probe.usedHeapBytes +
            MainScanAuthoritativeRender.heapReserveBytes(probe) +
            required
        assertTrue(
            "the shortfall must be a real one, not a rounding",
            neededHeap > probe.maxHeapBytes + 48 * MIB
        )
        assertEquals(
            "the same render is admitted on a modern device, so this is a heap limit and not a " +
                "defect in the demand",
            true,
            MainScanAuthoritativeRender.admits(DEMAND, probe.copy(bitmapsAllocateOffHeap = true))
        )
    }

    @Test
    fun aPreOreoLargeHeapWithRoomIsAdmitted() {
        val probe = MainScanMemoryProbe(
            maxHeapBytes = 512 * MIB,
            usedHeapBytes = 32 * MIB,
            deviceAvailableBytes = 0L,
            deviceLowMemoryThresholdBytes = 0L,
            deviceLowMemory = false,
            retainedBitmapBytes = 0L,
            bitmapsAllocateOffHeap = false
        )
        assertTrue(MainScanAuthoritativeRender.admits(DEMAND, probe))
    }

    @Test
    fun aDegenerateDemandIsRefused() {
        val probe = generousProbe()
        assertFalse(
            MainScanAuthoritativeRender.admits(DEMAND.copy(outputWidth = 0), probe)
        )
        assertFalse(
            MainScanAuthoritativeRender.admits(DEMAND.copy(orientedSourceHeight = 0), probe)
        )
    }

    // --- the refusal happens BEFORE the allocation, in the production sequence --------------------

    @Test
    fun admissionIsDecidedBeforeAnyLargeAllocation() {
        val body = functionBody(processorSource(), "renderAuthoritative")
        val admits = body.indexOf("MainScanAuthoritativeRender.admits(")
        val contained = body.indexOf("renderAuthoritativeContained(")
        assertTrue("the admission probe must exist", admits >= 0)
        assertTrue("the allocating half must be reached from here", contained >= 0)
        assertTrue(
            "admission must be decided before the allocating half is entered",
            admits < contained
        )
        assertFalse(
            "nothing full-resolution may be decoded before admission",
            body.contains("decodeAtSourceResolution(")
        )
        assertFalse(
            "nothing may be warped before admission",
            body.contains("drawWarp(")
        )
    }

    @Test
    fun aRefusedAdmissionReturnsInsufficientMemoryAndNothingElse() {
        val body = functionBody(processorSource(), "renderAuthoritative")
        val refusal = body.substringAfter("MainScanAuthoritativeRender.admits(")
            .substringBefore("renderAuthoritativeContained(")
        assertTrue(
            "a refused admission must be reported as INSUFFICIENT_MEMORY",
            refusal.contains("MainScanRenderFailure.INSUFFICIENT_MEMORY")
        )
        assertFalse(
            "a refusal must never fall back to a smaller sample size",
            refusal.contains("inSampleSize")
        )
    }

    @Test
    fun allocationFailureIsContainedAtTheProcessingBoundary() {
        val body = functionBody(processorSource(), "renderAuthoritativeContained")
        assertTrue(
            "OutOfMemoryError is an Error and would otherwise escape the pipeline entirely",
            body.contains("catch (oom: OutOfMemoryError)")
        )
        assertTrue(
            "a contained OOM must refuse, not downgrade",
            body.contains("MainScanRenderFailure.INSUFFICIENT_MEMORY")
        )
        assertTrue(
            "cancellation is not a render failure and must keep propagating",
            body.contains("catch (cancellation: CancellationException)") &&
                body.contains("throw cancellation")
        )
        assertTrue(
            "every full-resolution bitmap must be released on every exit",
            body.contains("finally {")
        )
    }

    @Test
    fun theEnhancedSiblingIsNeverDerivedFromTheCroppedJpeg() {
        val body = functionBody(processorSource(), "renderAuthoritativeContained")
        val enhanceCall = body.indexOf("applyFilter(warped")
        assertTrue(
            "the enhancement must read the in-memory warp result",
            enhanceCall >= 0
        )
        assertFalse(
            "the cropped JPEG must never be decoded back for the enhancement",
            body.contains("readFileBounds(croppedTarget).let") ||
                body.contains("decodeAtSourceResolution(croppedTarget")
        )
    }

    @Test
    fun theTwoSiblingQualitiesAreTheApprovedOnes() {
        assertEquals(95, MainScanAuthoritativeRender.CROPPED_JPEG_QUALITY)
        assertEquals(92, MainScanAuthoritativeRender.ENHANCED_JPEG_QUALITY)
    }

    @Test
    fun theDerivedDirectoriesAreTheApprovedAppPrivateOnes() {
        assertEquals("main_scan_cropped", MainScanAuthoritativeRender.CROPPED_DIRECTORY_NAME)
        assertEquals("main_scan_enhanced", MainScanAuthoritativeRender.ENHANCED_DIRECTORY_NAME)
        assertEquals("main_scan_capture", MainScanAuthoritativeRender.CAPTURE_DIRECTORY_NAME)
    }

    @Test
    fun derivedFileNamesCannotCollide() {
        val names = (1..200).map { MainScanAuthoritativeRender.croppedFileName() }
        assertEquals("every derived name must be unique", names.size, names.toSet().size)
        assertTrue(names.all { it.endsWith(".jpg") })
        // The two siblings must never resolve to the same name even for the same nonce.
        val nonce = "fixed"
        assertTrue(
            MainScanAuthoritativeRender.croppedFileName(nonce) !=
                MainScanAuthoritativeRender.enhancedFileName(nonce)
        )
    }

    // --- source-contract helpers --------------------------------------------------------------

    /**
     * The Android half of this contract mutates bitmaps and touches the filesystem, and this module
     * has JUnit only — no Robolectric, no mocking framework — so the ORDER of its steps is asserted
     * against the real source, exactly as [MainScanPipelineTeardownTest] already does. Removing a
     * guard therefore fails the build rather than passing quietly.
     */
    private fun processorSource(): String {
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/ui/mainscan/MainScanCaptureProcessor.kt"),
            File("app/src/main/java/com/dev/docscannerpdf/ui/mainscan/MainScanCaptureProcessor.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "could not locate MainScanCaptureProcessor.kt from ${File("").absolutePath}",
            found
        )
        return found!!.readText()
    }

    /** The body of [name], by brace matching from its declaration. */
    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name(")
        assertTrue("$name must exist", declaration >= 0)
        val open = source.indexOf('{', declaration)
        assertTrue("$name must have a block body", open >= 0)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces while reading $name")
    }
}
