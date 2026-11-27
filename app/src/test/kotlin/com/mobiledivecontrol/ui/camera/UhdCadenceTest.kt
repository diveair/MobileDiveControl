package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UhdCadenceTest {
    @Test fun nominalRatesAllowSmallClockVariations() {
        assertTrue(matchesUhdCadence(60, 59.6, 269, 4_490_000, 4500))
        assertTrue(matchesUhdCadence(100, 99.5, 448, 4_490_000, 4500))
        assertTrue(matchesUhdCadence(120, 118.5, 533, 4_490_000, 4500))
    }

    @Test fun sensorFileAndElapsedCaptureMustAgree() {
        assertFalse(matchesUhdCadence(120, 60.0, 540, 4_490_000, 4500))
        assertFalse(matchesUhdCadence(120, 115.9, 533, 4_490_000, 4500))
        assertFalse(matchesUhdCadence(120, 120.0, 270, 2_245_000, 4500))
        assertFalse(matchesUhdCadence(100, 120.0, 540, 4_490_000, 4500))
        assertFalse(matchesUhdCadence(120, Double.NaN, 540, 4_490_000, 4500))
        assertFalse(matchesUhdCadence(120, 120.0, 540, 0, 4500))
    }

    @Test fun trackDurationsMustAgreeWithTheActualTakeEvenWithoutAudio() {
        val video = UhdTrackTiming(10_000, 4_490_000, 4_500_000)
        assertTrue(video.fitsTake(4500))
        assertFalse(video.copy(durationUs = 172_804_500_000).fitsTake(4500))
        assertFalse(video.copy(firstUs = 172_800_000_000, lastUs = 172_804_480_000).fitsTake(4500))
        assertFalse(UhdTrackTiming(Long.MAX_VALUE, Long.MIN_VALUE, 4_500_000).fitsTake(4500))
    }

    @Test fun aacMustCoverTheSamePresentationWindowAsVideo() {
        val video = UhdTrackTiming(0, 4_490_000, 4_500_000)
        assertTrue(video.alignsWith(UhdTrackTiming(21_333, 4_480_000, 4_501_333)))
        assertFalse(video.alignsWith(UhdTrackTiming(400_000, 4_890_000, 4_500_000)))
        assertFalse(video.alignsWith(UhdTrackTiming(0, 4_120_000, 4_500_000)))
    }
}
