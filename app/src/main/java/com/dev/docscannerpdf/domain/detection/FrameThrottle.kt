package com.dev.docscannerpdf.domain.detection

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Backpressure gate: ensures at most [maxInFlight] frames are being analyzed at once. A new
 * frame that arrives while the budget is full is dropped, so a slow analyzer can never build a
 * backlog. Thread-safe and deterministic per call sequence.
 */
class FrameDropPolicy(private val maxInFlight: Int = 1) {
    private val inFlight = AtomicInteger(0)

    /** Reserves an analysis slot, or returns false (drop this frame) if the budget is full. */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = inFlight.get()
            if (current >= maxInFlight) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    /** Releases a slot reserved by [tryAcquire]. */
    fun release() {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun inFlightCount(): Int = inFlight.get()
}

/**
 * Throttles processing to at most [targetFps] frames per second by spacing accepted frames at a
 * minimum interval. Pure given the supplied timestamps, so it is unit-testable without a clock.
 */
class FrameRateLimiter(targetFps: Int = 12) {
    private val minIntervalMs: Long = (1000L / targetFps.coerceIn(1, 60))
    private val lastAcceptedMs = AtomicLong(Long.MIN_VALUE)

    /** Returns true and records [nowMs] when enough time has passed; false to skip this frame. */
    fun shouldProcess(nowMs: Long): Boolean {
        while (true) {
            val last = lastAcceptedMs.get()
            if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
            if (lastAcceptedMs.compareAndSet(last, nowMs)) return true
        }
    }
}
