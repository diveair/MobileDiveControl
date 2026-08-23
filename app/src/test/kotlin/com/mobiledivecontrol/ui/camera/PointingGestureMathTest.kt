package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointingGestureMathTest {
    @Test
    fun `recognizes one extended index with folded fingers independent of roll`() {
        val upright = pointingPose()
        assertTrue(isPointingHandLandmarks(upright))

        val rolled = upright.map { (x, y) -> (1.0 - y) to x }
        assertTrue(isPointingHandLandmarks(rolled))
    }

    @Test
    fun `rejects pose with multiple extended fingers`() {
        val landmarks = pointingPose().toMutableList()
        landmarks[10] = 0.50 to 0.51
        landmarks[11] = 0.50 to 0.35
        landmarks[12] = 0.50 to 0.19
        assertFalse(isPointingHandLandmarks(landmarks))
    }

    @Test
    fun `rejects open palm even when the index is straight`() {
        val landmarks = pointingPose().toMutableList()
        listOf(
            listOf(9 to (0.50 to 0.67), 10 to (0.50 to 0.51), 11 to (0.50 to 0.35), 12 to (0.50 to 0.19)),
            listOf(13 to (0.55 to 0.69), 14 to (0.56 to 0.53), 15 to (0.57 to 0.37), 16 to (0.58 to 0.21)),
            listOf(17 to (0.60 to 0.72), 18 to (0.62 to 0.57), 19 to (0.64 to 0.42), 20 to (0.66 to 0.27)),
        ).flatten().forEach { (index, point) -> landmarks[index] = point }

        assertFalse(isPointingHandLandmarks(landmarks))
    }

    @Test
    fun `uses world geometry while projecting aim along the image finger ray`() {
        val image = pointingPose().map { (x, y) -> GestureLandmark(x, y) }.toMutableList()
        image[5] = GestureLandmark(0.35, 0.66)
        image[6] = GestureLandmark(0.40, 0.50)
        image[7] = GestureLandmark(0.45, 0.34)
        image[8] = GestureLandmark(0.50, 0.18)
        val world = pointingPose().map { (x, y) -> GestureLandmark(x, y, z = (x - 0.5) * 0.2) }

        val estimate = estimatePointingGesture(image, world, modelConfidence = 0.82f)

        assertNotNull(estimate)
        assertTrue(estimate!!.normalizedX > image[8].x)
        assertTrue(estimate.confidence >= 0.82f)
        assertTrue(estimate.fastPathEligible)
    }

    @Test
    fun `stable tracker recognizes and reaims after three consistent fast frames`() {
        val tracker = PointingGestureTracker(stableHoldMs = 120L, reaimHoldMs = 120L)
        val centre = PointingPoseEstimate(0.50, 0.90f)

        assertNull(tracker.observe(centre, 0L))
        tracker.markMissing()
        assertNull(tracker.observe(PointingPoseEstimate(0.51, 0.88f), 80L))
        val first = tracker.observe(PointingPoseEstimate(0.50, 0.92f), 160L)
        assertNotNull(first)
        assertEquals(0.50, first!!.normalizedX, 0.01)

        assertNull(tracker.observe(PointingPoseEstimate(0.70, 0.90f), 240L))
        assertNull(tracker.observe(PointingPoseEstimate(0.69, 0.90f), 320L))
        val reaimed = tracker.observe(PointingPoseEstimate(0.70, 0.90f), 400L)
        assertNotNull(reaimed)
        assertTrue(reaimed!!.normalizedX > 0.68)
    }

    @Test
    fun `one noisy pointing frame cannot set or change a heading`() {
        val tracker = PointingGestureTracker(
            stableHoldMs = 0L,
            reaimHoldMs = 0L,
            stableFramesRequired = 3,
            reaimFramesRequired = 3,
        )

        assertNull(tracker.observe(PointingPoseEstimate(0.20, 0.95f), 0L))
        tracker.markMissing()
        tracker.markMissing()
        tracker.markMissing()
        tracker.markMissing()
        assertNull(tracker.observe(PointingPoseEstimate(0.80, 0.95f), 80L))
    }

    @Test
    fun `aim jump restarts confirmation instead of emitting a stale candidate`() {
        val tracker = PointingGestureTracker(
            stableHoldMs = 0L,
            reaimHoldMs = 0L,
            stableFramesRequired = 3,
            reaimFramesRequired = 3,
        )

        assertNull(tracker.observe(PointingPoseEstimate(0.25, 0.90f), 0L))
        assertNull(tracker.observe(PointingPoseEstimate(0.26, 0.90f), 80L))
        assertNull(tracker.observe(PointingPoseEstimate(0.75, 0.90f), 160L))
        assertNull(tracker.observe(PointingPoseEstimate(0.74, 0.90f), 240L))
        assertNotNull(tracker.observe(PointingPoseEstimate(0.75, 0.90f), 320L))
    }

    @Test
    fun `strong geometry takes the single frame sub fifty millisecond path`() {
        val tracker = PointingGestureTracker()

        val accepted = tracker.observe(
            PointingPoseEstimate(
                normalizedX = 0.62,
                confidence = 0.92f,
                fastPathEligible = true,
            ),
            nowMs = 0L,
        )

        assertNotNull(accepted)
        assertEquals(0.62, accepted!!.normalizedX, 0.001)
    }

    @Test
    fun `high reported confidence without fast path evidence still needs confirmation`() {
        val tracker = PointingGestureTracker()

        assertNull(
            tracker.observe(
                PointingPoseEstimate(0.62, confidence = 0.99f, fastPathEligible = false),
                nowMs = 0L,
            ),
        )
    }

    private fun pointingPose(): List<Pair<Double, Double>> {
        val points = MutableList(21) { 0.5 to 0.8 }
        points[0] = 0.50 to 0.90
        points[5] = 0.45 to 0.66
        points[6] = 0.45 to 0.50
        points[7] = 0.45 to 0.34
        points[8] = 0.45 to 0.18

        points[9] = 0.50 to 0.67
        points[10] = 0.49 to 0.56
        points[11] = 0.55 to 0.60
        points[12] = 0.51 to 0.68

        points[13] = 0.55 to 0.69
        points[14] = 0.56 to 0.59
        points[15] = 0.62 to 0.64
        points[16] = 0.56 to 0.71

        points[17] = 0.60 to 0.72
        points[18] = 0.61 to 0.63
        points[19] = 0.66 to 0.68
        points[20] = 0.61 to 0.75
        return points
    }
}
