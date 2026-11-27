package com.mobiledivecontrol.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoFrameRateMigrationTest {
    @Test fun combinedRateMigratesBeforePlaybackDefaultsAreApplied() {
        val saved = mapOf("pro_video.frame_rate" to "120fps/29.97fps playback")
        val migrated = migrateVideoFrameRates(saved)
        assertEquals("120fps", migrated["pro_video.frame_rate"])
        assertEquals("29.97fps", migrated["pro_video.playback_fps"])
        assertEquals(migrated, migrateVideoFrameRates(migrated))
        val explicit = migrateVideoFrameRates(saved + ("pro_video.playback_fps" to "60fps"))
        assertEquals("60fps", explicit["pro_video.playback_fps"])
    }
}
