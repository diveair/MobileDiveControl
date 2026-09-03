package com.mobiledivecontrol.ui.camera

import com.mobiledivecontrol.core.CameraCaptureType
import com.mobiledivecontrol.core.CameraModeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewRecoveryPolicyTest {
    @Test
    fun `resume never tears down a surface that is still being restored`() {
        assertEquals(
            PreviewRecoveryDecision.Wait,
            previewRecoveryDecision(
                cameraPresent = true,
                heartbeatFresh = false,
                previewAttached = true,
                elapsedSinceResumeMs = 2_500L,
                elapsedSinceBindMs = 60_000L,
            ),
        )
    }

    @Test
    fun `a new camera bind receives its own settle window`() {
        assertEquals(
            PreviewRecoveryDecision.Wait,
            previewRecoveryDecision(
                cameraPresent = true,
                heartbeatFresh = false,
                previewAttached = true,
                elapsedSinceResumeMs = 20_000L,
                elapsedSinceBindMs = 1_000L,
            ),
        )
    }

    @Test
    fun `a genuinely dead attached preview is rebound after both grace periods`() {
        assertEquals(
            PreviewRecoveryDecision.Rebind,
            previewRecoveryDecision(
                cameraPresent = true,
                heartbeatFresh = false,
                previewAttached = true,
                elapsedSinceResumeMs = PREVIEW_RESUME_SURFACE_GRACE_MS,
                elapsedSinceBindMs = PREVIEW_BIND_SETTLE_GRACE_MS,
            ),
        )
    }

    @Test
    fun `a detached preview is never rebound`() {
        assertEquals(
            PreviewRecoveryDecision.Wait,
            previewRecoveryDecision(
                cameraPresent = true,
                heartbeatFresh = false,
                previewAttached = false,
                elapsedSinceResumeMs = 60_000L,
                elapsedSinceBindMs = 60_000L,
            ),
        )
    }

    @Test
    fun `a fresh presented frame is healthy`() {
        assertEquals(
            PreviewRecoveryDecision.Healthy,
            previewRecoveryDecision(
                cameraPresent = true,
                heartbeatFresh = true,
                previewAttached = true,
                elapsedSinceResumeMs = 9_000L,
                elapsedSinceBindMs = 9_000L,
            ),
        )
    }

    @Test
    fun `an EGL heartbeat on a detached PreviewView output is not healthy`() {
        assertFalse(
            previewPresentationHealthy(
                previewStreaming = false,
                effectProcessorPresent = true,
                effectHeartbeatFresh = true,
            ),
        )
    }

    @Test
    fun `an effected preview requires both stream and GL heartbeats`() {
        assertTrue(
            previewPresentationHealthy(
                previewStreaming = true,
                effectProcessorPresent = true,
                effectHeartbeatFresh = true,
            ),
        )
        assertFalse(
            previewPresentationHealthy(
                previewStreaming = true,
                effectProcessorPresent = true,
                effectHeartbeatFresh = false,
            ),
        )
    }

    @Test
    fun `a direct preview only requires PreviewView streaming`() {
        assertTrue(
            previewPresentationHealthy(
                previewStreaming = true,
                effectProcessorPresent = false,
                effectHeartbeatFresh = false,
            ),
        )
    }

    @Test
    fun `ordinary state ticks preserve an owned retry for the same graph`() {
        val graph = graphKey(aspectRatio = "16:9")

        assertTrue(
            shouldDeferCameraBind(
                cameraPresent = false,
                desired = graph,
                pending = graph,
                exhausted = null,
            ),
        )
    }

    @Test
    fun `empty CameraX inventory is availability recovery not a configuration retry`() {
        val error = "No available camera can be found. Cams:0 PhyId:null Filters:2"

        assertTrue(isTransientCameraAvailabilityFailure(error))
        assertTrue(cameraProviderInventoryWasLost(error))
    }

    @Test
    fun `ordinary stream combination rejection does not rebuild CameraX`() {
        val error = "No supported surface combination is found for camera device - Id : 0"

        assertFalse(isTransientCameraAvailabilityFailure(error))
        assertFalse(cameraProviderInventoryWasLost(error))
    }

    @Test
    fun `camera in use waits for availability without assuming provider inventory loss`() {
        val error = "CameraUnavailableException: ERROR_CAMERA_IN_USE"

        assertTrue(isTransientCameraAvailabilityFailure(error))
        assertFalse(cameraProviderInventoryWasLost(error))
    }

    @Test
    fun `graph fallback rebuilds when the old camera never releases`() {
        assertTrue(
            shouldRebuildProviderAtGraphReleaseFallback(
                releasedCameraId = "0",
                cameraReleaseConfirmed = false,
            ),
        )
    }

    @Test
    fun `graph fallback keeps provider after a real availability edge`() {
        assertFalse(
            shouldRebuildProviderAtGraphReleaseFallback(
                releasedCameraId = "0",
                cameraReleaseConfirmed = true,
            ),
        )
        assertFalse(
            shouldRebuildProviderAtGraphReleaseFallback(
                releasedCameraId = null,
                cameraReleaseConfirmed = false,
            ),
        )
    }

    @Test
    fun `a newer graph is coalesced behind an owned retry`() {
        assertTrue(
            shouldDeferCameraBind(
                cameraPresent = false,
                desired = graphKey(aspectRatio = "1:1"),
                pending = graphKey(aspectRatio = "16:9"),
                exhausted = null,
            ),
        )
    }

    @Test
    fun `a rapid graph change waits for the active binding first frame`() {
        assertTrue(
            shouldDeferGraphTransitionUntilPresented(
                cameraPresent = true,
                currentBindingGeneration = 42L,
                lastDisplayedBindingGeneration = 41L,
                elapsedSinceFirstPresentedMs = null,
            ),
        )
    }

    @Test
    fun `a frame tagged with a different generation cannot satisfy the active bind`() {
        assertTrue(
            shouldDeferGraphTransitionUntilPresented(
                cameraPresent = true,
                currentBindingGeneration = 42L,
                lastDisplayedBindingGeneration = 43L,
                elapsedSinceFirstPresentedMs = null,
            ),
        )
    }

    @Test
    fun `a newly presented graph gets a short hardware settle window`() {
        assertTrue(
            shouldDeferGraphTransitionUntilPresented(
                cameraPresent = true,
                currentBindingGeneration = 42L,
                lastDisplayedBindingGeneration = 42L,
                elapsedSinceFirstPresentedMs = 100L,
            ),
        )
    }

    @Test
    fun `a stably presented graph allows the next transition`() {
        assertFalse(
            shouldDeferGraphTransitionUntilPresented(
                cameraPresent = true,
                currentBindingGeneration = 42L,
                lastDisplayedBindingGeneration = 42L,
                elapsedSinceFirstPresentedMs = CAMERA_GRAPH_POST_PRESENT_SETTLE_MS,
            ),
        )
    }

    @Test
    fun `a graph that never presents keeps user transitions queued for watchdog recovery`() {
        assertTrue(
            shouldDeferGraphTransitionUntilPresented(
                cameraPresent = true,
                currentBindingGeneration = 42L,
                lastDisplayedBindingGeneration = 41L,
                elapsedSinceFirstPresentedMs = null,
            ),
        )
    }

    @Test
    fun `rapid graph requests wait for a quiet navigation window`() {
        assertEquals(
            400L,
            cameraGraphRequestQuietRemainingMs(
                latestRequestAtMs = 1_000L,
                nowMs = 1_300L,
            ),
        )
        assertEquals(
            0L,
            cameraGraphRequestQuietRemainingMs(
                latestRequestAtMs = 1_000L,
                nowMs = 1_000L + CAMERA_GRAPH_REQUEST_DEBOUNCE_MS,
            ),
        )
    }

    @Test
    fun `explicit mode navigation bypasses the settings debounce`() {
        assertEquals(
            0L,
            cameraGraphRequestQuietRemainingMs(
                latestRequestAtMs = 1_000L,
                nowMs = 1_000L,
                debounceRequired = false,
            ),
        )
    }

    @Test
    fun `a successful camera never suppresses normal session state updates`() {
        val graph = graphKey(aspectRatio = "16:9")

        assertFalse(
            shouldDeferCameraBind(
                cameraPresent = true,
                desired = graph,
                pending = graph,
                exhausted = null,
            ),
        )
    }

    @Test
    fun `still aspect ratio is a live crop while video aspect ratio owns a graph`() {
        assertEquals(null, cameraGraphAspectRatio(CameraCaptureType.Photo, "16:9"))
        assertEquals("16:9", cameraGraphAspectRatio(CameraCaptureType.Video, "16:9"))
    }

    @Test
    fun `compatible camera modes retain one graph while special topologies remain isolated`() {
        assertEquals("photo", cameraGraphClass(CameraModeId.Photo, false, false))
        assertEquals("photo", cameraGraphClass(CameraModeId.Portrait, false, false))
        assertEquals("photo", cameraGraphClass(CameraModeId.Food, false, false))
        assertEquals("video", cameraGraphClass(CameraModeId.Video, false, false))
        assertEquals("video", cameraGraphClass(CameraModeId.PortraitVideo, false, false))
        assertEquals("panorama", cameraGraphClass(CameraModeId.Panorama, false, false))
        assertEquals("detached-video", cameraGraphClass(CameraModeId.Hyperlapse, true, false))
        assertEquals(
            "maximum-information-video",
            cameraGraphClass(CameraModeId.ProVideo, false, true),
        )
    }

    @Test
    fun `ordinary autofocus uses logical routing while calibrated and manual streams stay pinned`() {
        assertFalse(shouldPinPhysicalCameraStream(CameraModeId.Photo, false))
        assertTrue(shouldPinPhysicalCameraStream(CameraModeId.Panorama, false))
        assertTrue(shouldPinPhysicalCameraStream(CameraModeId.Photo, true))
        assertTrue(shouldPinPhysicalCameraStream(CameraModeId.Pro, false))
        assertTrue(shouldPinPhysicalCameraStream(CameraModeId.ExpertRaw, false))
        assertTrue(shouldPinPhysicalCameraStream(CameraModeId.ProVideo, false))
    }

    private fun graphKey(aspectRatio: String) = CameraGraphKey(
        lensFacing = 1,
        cameraId = "0",
        resolution = null,
        frameRate = null,
        lens = "3x",
        hdrLog = "Off",
        stabilization = "Off",
        captureFormat = "JPEG",
        aspectRatio = aspectRatio,
        mode = "Portrait",
    )
}
