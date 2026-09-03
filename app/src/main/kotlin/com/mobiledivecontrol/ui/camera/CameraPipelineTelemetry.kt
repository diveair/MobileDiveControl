package com.mobiledivecontrol.ui.camera

import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free health counters for the real camera/display path.
 *
 * The stress runner reads these counters, but the camera does not depend on the runner. Updating
 * one timestamp and one counter after a successful EGL swap is deliberately cheap enough to stay
 * in the hot path. A "responsive preview" therefore means a camera frame actually reached the
 * PreviewView surface, not merely that CameraX still reports STREAMING upstream.
 */
object CameraPipelineTelemetry {
    data class Snapshot(
        val capturedAtMs: Long,
        val displayedFrameCount: Long,
        val lastDisplayedFrameAtMs: Long,
        val lastDisplayedBindingGeneration: Long,
        val firstDisplayedFrameAtMs: Long,
        val lastSourceFrameTimestampNs: Long,
        val sourceTimestampStallCount: Long,
        val directPreviewFrameCount: Long,
        val lastDirectPreviewFrameAtMs: Long,
        val bindStartedCount: Long,
        val bindCompletedCount: Long,
        val bindInFlightSinceMs: Long,
        val lastBindDurationMs: Long,
        val maxBindDurationMs: Long,
        val previewSwapFailureCount: Long,
        val runtimeFailureCount: Long,
        val lastBindSignature: String,
        val recentEvents: List<String>,
    )

    private val displayedFrames = AtomicLong()
    private val lastDisplayedFrameAt = AtomicLong()
    private val lastDisplayedBindingGeneration = AtomicLong()
    private val firstDisplayedFrameAt = AtomicLong()
    private val lastSourceFrameTimestamp = AtomicLong()
    private val lastSourceFrameBindingGeneration = AtomicLong()
    private val sourceTimestampStalls = AtomicLong()
    private val directPreviewFrames = AtomicLong()
    private val lastDirectPreviewFrameAt = AtomicLong()
    private val bindsStarted = AtomicLong()
    private val bindsCompleted = AtomicLong()
    private val bindInFlightSince = AtomicLong()
    private val lastBindDuration = AtomicLong()
    private val maxBindDuration = AtomicLong()
    private val previewSwapFailures = AtomicLong()
    private val runtimeFailures = AtomicLong()
    private val events = ConcurrentLinkedDeque<String>()

    @Volatile
    private var lastBindSignature: String = ""

    fun resetForStressRun() {
        displayedFrames.set(0L)
        lastDisplayedFrameAt.set(0L)
        lastDisplayedBindingGeneration.set(0L)
        firstDisplayedFrameAt.set(0L)
        lastSourceFrameTimestamp.set(0L)
        lastSourceFrameBindingGeneration.set(0L)
        sourceTimestampStalls.set(0L)
        directPreviewFrames.set(0L)
        lastDirectPreviewFrameAt.set(0L)
        bindsStarted.set(0L)
        bindsCompleted.set(0L)
        bindInFlightSince.set(0L)
        lastBindDuration.set(0L)
        maxBindDuration.set(0L)
        previewSwapFailures.set(0L)
        runtimeFailures.set(0L)
        lastBindSignature = ""
        events.clear()
    }

    fun recordDisplayedFrame(
        bindingGeneration: Long,
        atMs: Long = SystemClock.elapsedRealtime(),
        sourceFrameTimestampNs: Long = 0L,
    ) {
        if (lastDisplayedBindingGeneration.get() != bindingGeneration) {
            firstDisplayedFrameAt.set(atMs)
        }
        lastDisplayedFrameAt.set(atMs)
        lastDisplayedBindingGeneration.set(bindingGeneration)
        displayedFrames.incrementAndGet()
        if (sourceFrameTimestampNs > 0L) {
            val previousGeneration = lastSourceFrameBindingGeneration.getAndSet(bindingGeneration)
            val previousTimestamp = lastSourceFrameTimestamp.getAndSet(sourceFrameTimestampNs)
            if (previousGeneration == bindingGeneration &&
                previousTimestamp > 0L &&
                sourceFrameTimestampNs <= previousTimestamp
            ) {
                sourceTimestampStalls.incrementAndGet()
            }
        }
    }

    /** Repeating camera-frame heartbeat used only when CameraX forbids a Preview CameraEffect. */
    fun recordDirectPreviewFrame(atMs: Long = SystemClock.elapsedRealtime()) {
        lastDirectPreviewFrameAt.set(atMs)
        directPreviewFrames.incrementAndGet()
    }

    fun recordBindStarted(signature: String, atMs: Long = SystemClock.elapsedRealtime()) {
        bindsStarted.incrementAndGet()
        bindInFlightSince.set(atMs)
        lastBindSignature = signature
        event("BIND_START $signature")
    }

    fun recordBindCompleted(atMs: Long = SystemClock.elapsedRealtime()) {
        val startedAt = bindInFlightSince.getAndSet(0L)
        val duration = if (startedAt > 0L) (atMs - startedAt).coerceAtLeast(0L) else 0L
        lastBindDuration.set(duration)
        maxBindDuration.getAndUpdate { previous -> maxOf(previous, duration) }
        bindsCompleted.incrementAndGet()
        event("BIND_COMPLETE ${duration}ms $lastBindSignature")
    }

    fun recordBindWarning(message: String) {
        event("BIND_WARNING $message")
    }

    fun recordPreviewSwapFailure(message: String) {
        previewSwapFailures.incrementAndGet()
        event("PREVIEW_SWAP_FAILURE $message")
    }

    fun recordRuntimeFailure(message: String) {
        runtimeFailures.incrementAndGet()
        event("RUNTIME_FAILURE $message")
    }

    fun snapshot(capturedAtMs: Long = SystemClock.elapsedRealtime()): Snapshot = Snapshot(
        capturedAtMs = capturedAtMs,
        displayedFrameCount = displayedFrames.get(),
        lastDisplayedFrameAtMs = lastDisplayedFrameAt.get(),
        lastDisplayedBindingGeneration = lastDisplayedBindingGeneration.get(),
        firstDisplayedFrameAtMs = firstDisplayedFrameAt.get(),
        lastSourceFrameTimestampNs = lastSourceFrameTimestamp.get(),
        sourceTimestampStallCount = sourceTimestampStalls.get(),
        directPreviewFrameCount = directPreviewFrames.get(),
        lastDirectPreviewFrameAtMs = lastDirectPreviewFrameAt.get(),
        bindStartedCount = bindsStarted.get(),
        bindCompletedCount = bindsCompleted.get(),
        bindInFlightSinceMs = bindInFlightSince.get(),
        lastBindDurationMs = lastBindDuration.get(),
        maxBindDurationMs = maxBindDuration.get(),
        previewSwapFailureCount = previewSwapFailures.get(),
        runtimeFailureCount = runtimeFailures.get(),
        lastBindSignature = lastBindSignature,
        recentEvents = events.toList(),
    )

    private fun event(message: String) {
        events.addLast("${SystemClock.elapsedRealtime()} $message")
        while (events.size > MAX_EVENTS) events.pollFirst()
    }

    private const val MAX_EVENTS = 80
}
