package com.dev.docscannerpdf.domain.detection

/**
 * Per-frame entry point of the live detection pipeline. Wraps [DocumentEdgeDetector] so callers
 * have a stable place to feed camera frames; analysis is stateless and deterministic. Temporal
 * smoothing and the capture gate are handled separately by [QuadStabilizer]/[AutoCapturePolicy].
 */
class LiveFrameAnalyzer(
    private val config: DetectionConfig = DetectionConfig()
) {
    /** Analyzes a single frame, returning the detected document quad + confidence, or null. */
    fun analyze(frame: LumaFrame): DetectedQuad? = DocumentEdgeDetector.detect(frame, config)
}
