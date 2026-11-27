package com.mobiledivecontrol.ui.camera

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UhdPresentationIntervalsTest {
    @Test fun genuine118FpsIsAcceptedWithReorderedPacketsAndAnOccasionalDrop() {
        val reordered = (0L..521L).filter { it != 80L && it != 410L }
            .map { it * 8_436L }.chunked(3).flatMap { it.reversed() }
        assertTrue(matchesUhdPresentationIntervals(120, reordered))
    }

    @Test fun pairedPresentationTimesCannotQualifyFromSampleCountAndSpanAlone() {
        val pairs = (0L until 270L).flatMap { listOf(it * 16_667L, it * 16_667L + 200L) }
        assertTrue(matchesUhdCadence(120, 118.5, pairs.size, pairs.last() - pairs.first(), 4500))
        assertFalse(matchesUhdPresentationIntervals(120, pairs))
    }

    @Test fun twoPicturesScheduledAtTheSameInstantAreRejected() {
        val times = (0L until 500L).map { it * 8_436L }.toMutableList()
        times[431] = times[430]
        assertFalse(matchesUhdPresentationIntervals(120, times))
    }
}
