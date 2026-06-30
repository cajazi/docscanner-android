package com.dev.docscannerpdf.domain.detection

/** Output of one processed frame: the overlay state, the raw stabilizer state, and whether an
 *  auto-capture signal should fire this frame (edge-triggered). */
data class LiveDetectionResult(
    val uiState: LiveDetectionUiState,
    val stabilizerState: StabilizerState,
    val shouldCapture: Boolean
)

/**
 * The live detection loop's logic, isolated from CameraX so it is deterministic and testable:
 *
 *     frame -> LiveFrameAnalyzer -> QuadStabilizer -> AutoCapturePolicy -> result
 *
 * The detector and stabilizer are reused unchanged. Auto-capture is edge-triggered: [process]
 * reports `shouldCapture = true` only on the frame where readiness first becomes true, so the
 * camera layer emits a single capture event rather than one per stable frame.
 */
class LiveDetectionSession(
    private val analyzer: LiveFrameAnalyzer = LiveFrameAnalyzer(),
    private val stabilizationConfig: StabilizationConfig = StabilizationConfig()
) {
    private var state = StabilizerState()
    private var wasReady = false

    fun process(frame: LumaFrame): LiveDetectionResult {
        val detection = analyzer.analyze(frame)
        state = QuadStabilizer.update(state, detection, stabilizationConfig)
        val uiState = AutoCapturePolicy.toUiState(state, stabilizationConfig)
        val shouldCapture = uiState.readyToCapture && !wasReady
        wasReady = uiState.readyToCapture
        return LiveDetectionResult(uiState, state, shouldCapture)
    }

    fun currentState(): StabilizerState = state

    fun reset() {
        state = StabilizerState()
        wasReady = false
    }
}
