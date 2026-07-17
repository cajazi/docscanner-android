package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardCaptureQualityTest {

    // --- the orientation-independent UHD predicate ---

    @Test
    fun uhdLandscape3840x2160Passes() {
        assertTrue(meetsUhdCaptureRequirement(3840, 2160))
    }

    @Test
    fun uhdPortrait2160x3840Passes() {
        assertTrue(meetsUhdCaptureRequirement(2160, 3840))
    }

    @Test
    fun fourByThree4000x3000Passes() {
        assertTrue(meetsUhdCaptureRequirement(4000, 3000))
    }

    @Test
    fun fourByThreePortrait3000x4000Passes() {
        assertTrue(meetsUhdCaptureRequirement(3000, 4000))
    }

    @Test
    fun oneShortOnTheLongEdgeFails() {
        assertFalse(meetsUhdCaptureRequirement(3839, 2160))
    }

    @Test
    fun oneShortOnTheShortEdgeFails() {
        assertFalse(meetsUhdCaptureRequirement(3840, 2159))
    }

    @Test
    fun eightMegapixelClass3264x2448Fails() {
        assertFalse(meetsUhdCaptureRequirement(3264, 2448))
    }

    @Test
    fun zeroAndNegativeDimensionsFail() {
        assertFalse(meetsUhdCaptureRequirement(0, 2160))
        assertFalse(meetsUhdCaptureRequirement(3840, 0))
        assertFalse(meetsUhdCaptureRequirement(0, 0))
        assertFalse(meetsUhdCaptureRequirement(-3840, 2160))
        assertFalse(meetsUhdCaptureRequirement(3840, -2160))
    }

    @Test
    fun orientationNeverAffectsTheResult() {
        listOf(
            3840 to 2160, 4000 to 3000, 3264 to 2448, 3839 to 2160, 1920 to 1080
        ).forEach { (w, h) ->
            assertEquals(
                "swap-invariant for ${w}x$h",
                meetsUhdCaptureRequirement(w, h),
                meetsUhdCaptureRequirement(h, w)
            )
        }
    }

    @Test
    fun rawCaptureInfoAppliesThePredicate() {
        assertTrue(RawCaptureInfo(4000, 3000, 90, 4_000_000L).meetsUhd)
        assertFalse(RawCaptureInfo(3264, 2448, 90, 2_000_000L).meetsUhd)
    }

    // --- UHD capture-size selection policy (from the camera's REAL capability list) ---

    @Test
    fun fourByThreeUhdIsPreferredOverSixteenByNineUhd() {
        val chosen = chooseUhdCaptureSize(
            listOf(CaptureSize(3840, 2160), CaptureSize(4000, 3000))
        )

        assertEquals(CaptureSize(4000, 3000), chosen)
    }

    @Test
    fun higherAreaFourByThreeWinsOverWideUhd() {
        val chosen = chooseUhdCaptureSize(
            listOf(CaptureSize(4000, 2250), CaptureSize(4032, 3024))
        )

        assertEquals(CaptureSize(4032, 3024), chosen)
    }

    @Test
    fun samsungA165CapabilityListSelects4080x3060() {
        // The exact JPEG list dumped from the SM-A165F's back camera 0.
        val chosen = chooseUhdCaptureSize(
            listOf(
                CaptureSize(4080, 3060), CaptureSize(4080, 2296), CaptureSize(4080, 1884),
                CaptureSize(3056, 3056), CaptureSize(2336, 1080), CaptureSize(1920, 1080),
                CaptureSize(1920, 888), CaptureSize(1440, 1440), CaptureSize(1440, 1080),
                CaptureSize(1280, 720), CaptureSize(1088, 1088), CaptureSize(960, 720),
                CaptureSize(720, 720), CaptureSize(720, 480), CaptureSize(640, 480),
                CaptureSize(640, 360), CaptureSize(480, 320)
            )
        )

        assertEquals(CaptureSize(4080, 3060), chosen)
    }

    @Test
    fun sixteenByNineUhdIsUsedWhenNoFourByThreeQualifies() {
        val chosen = chooseUhdCaptureSize(
            listOf(CaptureSize(3840, 2160), CaptureSize(3264, 2448))
        )

        assertEquals(CaptureSize(3840, 2160), chosen)
    }

    @Test
    fun subUhdOnlyListReturnsNull() {
        assertEquals(null, chooseUhdCaptureSize(listOf(CaptureSize(3264, 2448), CaptureSize(1920, 1080))))
        assertEquals(null, chooseUhdCaptureSize(emptyList()))
    }

    @Test
    fun orientationSwappedSizesAreHandled() {
        val chosen = chooseUhdCaptureSize(
            listOf(CaptureSize(3000, 4000), CaptureSize(2160, 3840))
        )

        // 3000x4000 is 4:3 in portrait orientation and higher-area — it must win.
        assertEquals(CaptureSize(3000, 4000), chosen)
    }

    @Test
    fun selectionIsDeterministicRegardlessOfInputOrder() {
        val sizes = listOf(
            CaptureSize(4080, 3060), CaptureSize(4080, 2296), CaptureSize(3840, 2160)
        )

        assertEquals(chooseUhdCaptureSize(sizes), chooseUhdCaptureSize(sizes.reversed()))
        assertEquals(chooseUhdCaptureSize(sizes), chooseUhdCaptureSize(sizes.shuffled(kotlin.random.Random(42))))
    }

    // --- ResolutionFilter ordering (configuration must agree with the runtime predicate) ---

    @Test
    fun orderingRemovesSubUhdAndPutsTheChosenSizeFirst() {
        val ordered = orderCaptureSizesForUhd(
            listOf(
                CaptureSize(1920, 1080), CaptureSize(4080, 2296),
                CaptureSize(4080, 3060), CaptureSize(3264, 2448)
            )
        )

        assertEquals(
            listOf(CaptureSize(4080, 3060), CaptureSize(4080, 2296)),
            ordered
        )
        assertTrue("no sub-UHD candidate survives", ordered.all { it.meetsUhd })
    }

    @Test
    fun orderingLeavesListUntouchedWhenNothingQualifies() {
        // Preflight has already disabled the strict-4K shutter on such a camera; the filter
        // must not break the shared preview binding.
        val subUhd = listOf(CaptureSize(3264, 2448), CaptureSize(1920, 1080))

        assertEquals(subUhd, orderCaptureSizesForUhd(subUhd))
    }

    // --- preview recovery guard (frozen preview after a rejected capture) ---

    @Test
    fun recoveryRunsOnlyWhenNotStreamingAndOnlyOnceAtATime() {
        val guard = PreviewRecoveryGuard()

        assertFalse("streaming preview needs no recovery", guard.shouldAttemptRecovery(isPreviewStreaming = true))
        assertTrue("frozen preview triggers recovery", guard.shouldAttemptRecovery(isPreviewStreaming = false))
        assertTrue(guard.isRecovering)
        assertFalse("second request while in flight is rejected", guard.shouldAttemptRecovery(isPreviewStreaming = false))

        guard.onRecoveryComplete()
        assertFalse(guard.isRecovering)
        assertTrue("guard re-arms after completion", guard.shouldAttemptRecovery(isPreviewStreaming = false))
    }

    // --- quality gating within the capture flow (gate + capture-state interactions) ---

    private class RecordingShutterSound : CameraShutterSoundPlayer {
        var playCount = 0
        override fun play() {
            playCount++
        }

        override fun release() = Unit
    }

    @Test
    fun qualifyingCaptureProceedsToCaptureState() {
        // The screen only calls onSideCaptured when meetsUhd passed — model both branches.
        val initial = IdCardCaptureState()
        val qualifying = RawCaptureInfo(4000, 3000, 90, 4_000_000L)

        val next = if (qualifying.meetsUhd) {
            IdCardCaptureFlow.onSideCaptured(initial, "file://baked/front.jpg")
        } else {
            initial
        }

        assertEquals(IdCardCaptureStage.BACK, next.stage)
        assertEquals("file://baked/front.jpg", next.frontImageUri)
    }

    @Test
    fun inadequateCaptureCreatesNoReviewOrCaptureState() {
        val initial = IdCardCaptureState()
        val inadequate = RawCaptureInfo(3264, 2448, 90, 2_000_000L)

        val next = if (inadequate.meetsUhd) {
            IdCardCaptureFlow.onSideCaptured(initial, "file://baked/front.jpg")
        } else {
            initial
        }

        assertEquals("stage unchanged — no review handoff", initial, next)
    }

    @Test
    fun qualityRejectionPlaysNoExtraSoundAndRestoresRetryability() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        // Accepted + submitted capture: one sound.
        assertTrue(gate.onCaptureAccepted())
        IdCardCaptureSubmission.submit(submit = { }, onSubmitted = gate::onCaptureSubmitted)
        assertEquals(1, sound.playCount)

        // Quality validation rejects AFTER the callback — the screen's finally re-arms the
        // gate; validation itself never touches the sound.
        gate.onCaptureFinished()
        assertEquals("no second sound from quality validation", 1, sound.playCount)
        assertFalse(gate.isCapturing)

        // Retry is a fresh accepted capture with its own single sound.
        assertTrue(gate.onCaptureAccepted())
        IdCardCaptureSubmission.submit(submit = { }, onSubmitted = gate::onCaptureSubmitted)
        assertEquals(2, sound.playCount)
    }

    // --- capability-list union (normal + high-resolution JPEG lists) ---

    @Test
    fun unionCombinesNormalAndHighResolutionLists() {
        // The SM-A165F failure shape: the UHD stills live ONLY in the high-resolution list.
        val normal = listOf(CaptureSize(2336, 1080), CaptureSize(1920, 1080))
        val highResolution = listOf(CaptureSize(4080, 3060), CaptureSize(4080, 2296))

        val combined = combineCaptureSizeLists(normal, highResolution)

        assertTrue(CaptureSize(4080, 3060) in combined)
        assertTrue(CaptureSize(4080, 2296) in combined)
        assertTrue(CaptureSize(1920, 1080) in combined)
        assertEquals(4, combined.size)
    }

    @Test
    fun unionDiscovers4080x3060WhenPresentOnlyInHighResolutionList() {
        val combined = combineCaptureSizeLists(
            normalSizes = listOf(CaptureSize(1920, 1080)),
            highResolutionSizes = listOf(CaptureSize(4080, 3060), CaptureSize(4080, 2296))
        )

        assertEquals(CaptureSize(4080, 3060), chooseUhdCaptureSize(combined))
    }

    @Test
    fun unionPrefers4080x3060Over4080x2296() {
        val combined = combineCaptureSizeLists(
            normalSizes = emptyList(),
            highResolutionSizes = listOf(CaptureSize(4080, 2296), CaptureSize(4080, 3060))
        )

        assertEquals(CaptureSize(4080, 3060), chooseUhdCaptureSize(combined))
        assertEquals(
            listOf(CaptureSize(4080, 3060), CaptureSize(4080, 2296)),
            orderCaptureSizesForUhd(combined)
        )
    }

    @Test
    fun unionIsNullSafeAndDeduplicated() {
        val nullSafe = combineCaptureSizeLists(listOf(CaptureSize(1920, 1080)), null)
        assertEquals(listOf(CaptureSize(1920, 1080)), nullSafe)

        val deduplicated = combineCaptureSizeLists(
            normalSizes = listOf(CaptureSize(4080, 3060), CaptureSize(1920, 1080)),
            highResolutionSizes = listOf(CaptureSize(4080, 3060))
        )
        assertEquals(2, deduplicated.size)
    }

    @Test
    fun unionOrderingIsDeterministicByDescendingArea() {
        val a = combineCaptureSizeLists(
            listOf(CaptureSize(1920, 1080), CaptureSize(4080, 3060)),
            listOf(CaptureSize(4080, 2296))
        )
        val b = combineCaptureSizeLists(
            listOf(CaptureSize(4080, 3060), CaptureSize(1920, 1080)),
            listOf(CaptureSize(4080, 2296))
        )

        assertEquals(a, b)
        assertEquals(
            listOf(CaptureSize(4080, 3060), CaptureSize(4080, 2296), CaptureSize(1920, 1080)),
            a
        )
    }

    // --- authoritative support state (the CameraX-attached resolution decides) ---

    @Test
    fun boundUhdResolutionIsSupported() {
        assertEquals(
            UhdSupportState.SUPPORTED,
            resolveUhdSupportState(attachedWidth = 4080, attachedHeight = 3060, bindFailed = false)
        )
    }

    @Test
    fun boundSubUhdResolutionIsUnsupported() {
        assertEquals(
            UhdSupportState.UNSUPPORTED,
            resolveUhdSupportState(attachedWidth = 1920, attachedHeight = 1080, bindFailed = false)
        )
    }

    @Test
    fun bindFailureIsError() {
        assertEquals(
            UhdSupportState.ERROR,
            resolveUhdSupportState(attachedWidth = null, attachedHeight = null, bindFailed = true)
        )
    }

    @Test
    fun unknownAttachedResolutionStaysChecking() {
        // Inconclusive evidence must never claim supported OR unsupported.
        assertEquals(
            UhdSupportState.CHECKING,
            resolveUhdSupportState(attachedWidth = null, attachedHeight = null, bindFailed = false)
        )
    }

    @Test
    fun uhdBindClearsAStaleUnsupportedStateAndErrorCanRecover() {
        // The state is re-resolved on every bind: a stale UNSUPPORTED (incomplete metadata) or
        // ERROR is replaced the moment a real UHD attachment is proven.
        var state = resolveUhdSupportState(attachedWidth = 1920, attachedHeight = 1080, bindFailed = false)
        assertEquals(UhdSupportState.UNSUPPORTED, state)
        state = resolveUhdSupportState(attachedWidth = 4080, attachedHeight = 3060, bindFailed = false)
        assertEquals(UhdSupportState.SUPPORTED, state)

        var errored = resolveUhdSupportState(attachedWidth = null, attachedHeight = null, bindFailed = true)
        assertEquals(UhdSupportState.ERROR, errored)
        errored = resolveUhdSupportState(attachedWidth = 4080, attachedHeight = 3060, bindFailed = false)
        assertEquals(UhdSupportState.SUPPORTED, errored)
    }

    @Test
    fun orientationSwappedAttachmentIsSupported() {
        assertEquals(
            UhdSupportState.SUPPORTED,
            resolveUhdSupportState(attachedWidth = 3060, attachedHeight = 4080, bindFailed = false)
        )
    }

    // --- shutter-tap guard policy (every rejection has one deterministic reason) ---

    @Test
    fun supportedAndIdleClickIsAccepted() {
        assertEquals(
            null,
            captureClickRejection(UhdSupportState.SUPPORTED, isProcessing = false, gateBusy = false)
        )
    }

    @Test
    fun nonSupportedStatesRejectWithTheirOwnReason() {
        assertEquals(
            "support_state_CHECKING",
            captureClickRejection(UhdSupportState.CHECKING, isProcessing = false, gateBusy = false)
        )
        assertEquals(
            "support_state_UNSUPPORTED",
            captureClickRejection(UhdSupportState.UNSUPPORTED, isProcessing = false, gateBusy = false)
        )
        assertEquals(
            "support_state_ERROR",
            captureClickRejection(UhdSupportState.ERROR, isProcessing = false, gateBusy = false)
        )
    }

    @Test
    fun processingAndGateBusyRejectDuplicates() {
        assertEquals(
            "processing",
            captureClickRejection(UhdSupportState.SUPPORTED, isProcessing = true, gateBusy = false)
        )
        assertEquals(
            "gate_busy",
            captureClickRejection(UhdSupportState.SUPPORTED, isProcessing = false, gateBusy = true)
        )
    }

    @Test
    fun acceptedClickReachesSubmissionExactlyOnceWithOneSound() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)
        var submissions = 0

        // Full accepted-click pipeline: policy -> gate -> submission -> sound.
        assertEquals(null, captureClickRejection(UhdSupportState.SUPPORTED, false, gate.isCapturing))
        assertTrue(gate.onCaptureAccepted())
        IdCardCaptureSubmission.submit(
            submit = { submissions++ },
            onSubmitted = gate::onCaptureSubmitted
        )

        assertEquals(1, submissions)
        assertEquals(1, sound.playCount)
        // A duplicate tap during the in-flight capture is rejected by the policy already.
        assertEquals(
            "gate_busy",
            captureClickRejection(UhdSupportState.SUPPORTED, isProcessing = false, gateBusy = gate.isCapturing)
        )
    }
}
