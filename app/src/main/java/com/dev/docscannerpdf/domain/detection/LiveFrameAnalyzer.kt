package com.dev.docscannerpdf.domain.detection

/**
 * Per-frame entry point of the live detection pipeline. Wraps [DocumentEdgeDetector] so callers
 * have a stable place to feed camera frames; analysis is stateless and deterministic. Temporal
 * smoothing and the capture gate are handled separately by [QuadStabilizer]/[AutoCapturePolicy].
 */
class LiveFrameAnalyzer(
    private val config: DetectionConfig = DetectionConfig(),
    /**
     * The per-frame detector. Defaults to [DocumentEdgeDetector] so every existing caller keeps its
     * current behaviour unchanged; the Main Scanner injects its own finder, which fits the object
     * rather than taking the extreme points of all foreground pixels.
     */
    private val detect: (LumaFrame, DetectionConfig) -> DetectedQuad? =
        { frame, detectionConfig -> DocumentEdgeDetector.detect(frame, detectionConfig) }
) {
    /** Analyzes a single frame, returning the detected document quad + confidence, or null. */
    fun analyze(frame: LumaFrame): DetectedQuad? = detect(frame, config)
}
