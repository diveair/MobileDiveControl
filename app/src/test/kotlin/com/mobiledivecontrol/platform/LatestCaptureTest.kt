package com.mobiledivecontrol.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LatestCaptureTest {
    private fun capture(id: Long, name: String, time: Long = id, size: Long = 100,
                        path: String = "DCIM/Camera/", video: Boolean = false) =
        LatestCapture(id, "content://media/$id", name, path, video, time, size)

    @Test fun `empty library and incomplete files have no cover`() {
        assertNull(latestCaptureCover(emptyList()))
        assertNull(latestCaptureCover(listOf(capture(1, "empty.jpg", size = 0))))
    }

    @Test fun `newest photo or video wins independently of collection order`() {
        val photo = capture(2, "photo.jpg", time = 200)
        val video = capture(1, "video.mp4", time = 300, video = true)
        assertEquals(video, latestCaptureCover(listOf(photo, video)))
        assertEquals(photo, latestCaptureCover(listOf(video.copy(capturedAtMillis = 100), photo)))
    }

    @Test fun `raw completing after jpeg retains jpeg of the same capture`() {
        val jpeg = capture(1, "DiveControl_100.jpg", time = 100)
        val raw = capture(2, "DiveControl_100.dng", time = 100)
        assertEquals(jpeg, latestCaptureCover(listOf(raw, jpeg)))
        assertEquals(jpeg, latestCaptureCover(listOf(jpeg, raw)))
    }

    @Test fun `raw only does not fall back to unrelated jpeg or another album`() {
        val raw = capture(5, "capture.DNG", time = 200)
        val otherAlbum = capture(4, "capture.jpg", time = 100, path = "DCIM/Other/")
        assertEquals(raw, latestCaptureCover(listOf(otherAlbum, raw, capture(3, "older.jpg"))))
    }

    @Test fun `fast consecutive exposures are ordered by milliseconds then id`() {
        val newer = capture(9, "new.jpg", time = 1001)
        val lateOldRaw = capture(10, "old.dng", time = 1000)
        assertEquals(newer, latestCaptureCover(listOf(lateOldRaw, newer)))
        assertEquals(newer, latestCaptureCover(listOf(capture(8, "other.jpg", time = 1001), newer)))
    }
}
