package com.dev.docscannerpdf.domain.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraPipelineTest {

    private fun rectangleFrame(): LumaFrame {
        val width = 100; val height = 150
        val background = 210; val document = 40
        val luma = IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            if (x in 20..79 && y in 30..119) document else background
        }
        return LumaFrame(width, height, luma)
    }

    // --- Frame conversion correctness ---------------------------------------

    @Test
    fun yPlaneConvertsToLumaPreservingUnsignedValues() {
        // 2x2 image, rowStride 4 (2 padding bytes per row), pixelStride 1.
        val y = byteArrayOf(
            200.toByte(), 10, 0, 0,
            255.toByte(), 128.toByte(), 0, 0
        )
        val frame = YuvLumaConverter.fromLumaPlane(y, width = 2, height = 2, rowStride = 4)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(listOf(200, 10, 255, 128), frame.luma.toList())
    }

    @Test
    fun yPlaneHonoursPixelStride() {
        val y = byteArrayOf(50, 0, 60, 0) // pixelStride 2 -> picks indices 0 and 2
        val frame = YuvLumaConverter.fromLumaPlane(y, width = 2, height = 1, rowStride = 4, pixelStride = 2)
        assertEquals(listOf(50, 60), frame.luma.toList())
    }

    @Test
    fun yPlaneDownscalesByNearestNeighbour() {
        // 4x4 with distinct corners; downscale to max dim 2 samples the four corners.
        val luma = IntArray(16)
        luma[0] = 11          // (0,0)
        luma[3] = 22          // (3,0)
        luma[12] = 33         // (0,3)
        luma[15] = 44         // (3,3)
        val bytes = ByteArray(16) { luma[it].toByte() }
        val frame = YuvLumaConverter.fromLumaPlane(bytes, 4, 4, rowStride = 4, targetMaxDimension = 2)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(listOf(11, 22, 33, 44), frame.luma.toList())
    }

    // --- Frame drop logic under load ----------------------------------------

    @Test
    fun frameDropPolicyDropsWhenBusy() {
        val policy = FrameDropPolicy(maxInFlight = 1)
        assertTrue(policy.tryAcquire())   // first frame accepted
        assertFalse(policy.tryAcquire())  // second dropped (one already in flight)
        assertEquals(1, policy.inFlightCount())
        policy.release()
        assertEquals(0, policy.inFlightCount())
        assertTrue(policy.tryAcquire())   // accepted again after release
    }

    @Test
    fun frameRateLimiterSpacesFramesToTargetFps() {
        val limiter = FrameRateLimiter(targetFps = 10) // 100ms spacing
        assertTrue(limiter.shouldProcess(0))
        assertFalse(limiter.shouldProcess(50))
        assertTrue(limiter.shouldProcess(100))
        assertFalse(limiter.shouldProcess(150))
        assertTrue(limiter.shouldProcess(220))
    }

    // --- Auto-capture trigger condition -------------------------------------

    @Test
    fun autoCaptureFiresOnceWhenDetectionBecomesStable() {
        val session = LiveDetectionSession()
        val frame = rectangleFrame()

        // Frames 1..4: locking but not yet stable -> no capture signal.
        repeat(4) {
            val r = session.process(frame)
            assertFalse(r.shouldCapture)
            assertFalse(r.uiState.readyToCapture)
        }
        // Frame 5: becomes ready -> exactly one capture signal.
        val ready = session.process(frame)
        assertTrue(ready.uiState.readyToCapture)
        assertTrue(ready.shouldCapture)

        // Frame 6: still ready, but edge-triggered so no repeat signal.
        val held = session.process(frame)
        assertTrue(held.uiState.readyToCapture)
        assertFalse(held.shouldCapture)
    }

    // --- Overlay state synchronization --------------------------------------

    @Test
    fun resultUiStateMatchesStabilizerState() {
        val session = LiveDetectionSession()
        val result = session.process(rectangleFrame())
        assertEquals(AutoCapturePolicy.toUiState(result.stabilizerState), result.uiState)
    }

    // --- Deterministic analyzer integration ---------------------------------

    @Test
    fun pipelineIsDeterministicAcrossSessions() {
        fun run(): List<LiveDetectionResult> {
            val session = LiveDetectionSession()
            val frame = rectangleFrame()
            return (1..6).map { session.process(frame) }
        }
        assertEquals(run(), run())
    }
}
