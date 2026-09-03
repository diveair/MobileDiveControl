package com.mobiledivecontrol.ui.camera

internal const val PREVIEW_RESUME_SURFACE_GRACE_MS = 8_000L
internal const val PREVIEW_BIND_SETTLE_GRACE_MS = 4_000L

internal enum class PreviewRecoveryDecision {
    Healthy,
    Wait,
    Rebind,
}

/**
 * A frame is healthy only when it reached the active PreviewView consumer.
 *
 * eglSwapBuffers can keep succeeding against a stale SurfaceOutput after CameraX has detached
 * that output from PreviewView. Conversely PreviewView's STREAMING state does not prove that a
 * CameraEffect is still swapping. Requiring both signals closes both false-positive paths.
 */
internal fun previewPresentationHealthy(
    previewStreaming: Boolean,
    effectProcessorPresent: Boolean,
    effectHeartbeatFresh: Boolean,
): Boolean = previewStreaming && (!effectProcessorPresent || effectHeartbeatFresh)

/**
 * Decides whether a post-resume preview check may tear down the camera graph.
 *
 * PreviewView restores its Surface asynchronously. During that interval the camera can already
 * be open while the GL output heartbeat is still stale. Unbinding in that state cancels the new
 * downstream SurfaceRequest and strands the camera on an upstream surface with no display target.
 */
internal fun previewRecoveryDecision(
    cameraPresent: Boolean,
    heartbeatFresh: Boolean,
    previewAttached: Boolean,
    elapsedSinceResumeMs: Long,
    elapsedSinceBindMs: Long,
    resumeSurfaceGraceMs: Long = PREVIEW_RESUME_SURFACE_GRACE_MS,
    bindSettleGraceMs: Long = PREVIEW_BIND_SETTLE_GRACE_MS,
): PreviewRecoveryDecision = when {
    cameraPresent && heartbeatFresh -> PreviewRecoveryDecision.Healthy
    !previewAttached -> PreviewRecoveryDecision.Wait
    cameraPresent && elapsedSinceBindMs in 0 until bindSettleGraceMs ->
        PreviewRecoveryDecision.Wait
    cameraPresent && elapsedSinceResumeMs < resumeSurfaceGraceMs ->
        PreviewRecoveryDecision.Wait
    else -> PreviewRecoveryDecision.Rebind
}
