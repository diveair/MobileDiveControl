package com.mobiledivecontrol.ui.camera

import com.mobiledivecontrol.core.LinearRgb
import com.mobiledivecontrol.core.UnderwaterFrameObservation
import com.mobiledivecontrol.core.UnderwaterWhiteBalanceSolution
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnderwaterWhiteBalanceTraceTest {
    @Test
    fun `trace is replayable and rate bounded`() {
        val directory = Files.createTempDirectory("au-trace-test").toFile()
        try {
            val trace = UnderwaterWhiteBalanceTrace(directory)
            val file = trace.start(elapsedMillis = 1_000L, wallMillis = 2_000L)
            checkNotNull(file)
            trace.record(sample(1_000L, 2_000L))
            trace.record(sample(1_100L, 2_100L)) // Deliberately below the 250 ms trace cadence.
            trace.record(sample(1_300L, 2_300L))
            trace.close()

            val lines = file.readLines()
            assertEquals(3, lines.size)
            assertTrue(lines.first().startsWith("elapsed_ms,wall_ms,recording,depth_m"))
            assertEquals(33, lines[1].split(',').size)
            assertTrue(lines[1].contains(",6500,0.0010000,0.8000000,"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sample(elapsed: Long, wall: Long): UnderwaterWhiteBalanceTrace.Sample {
        val rgb = LinearRgb(0.8, 1.0, 1.2)
        return UnderwaterWhiteBalanceTrace.Sample(
            elapsedMillis = elapsed,
            wallMillis = wall,
            recording = true,
            depthMeters = 8.5,
            depthConfidence = 0.9,
            rangeMeters = 1.2,
            rangeConfidence = 0.2,
            observation = UnderwaterFrameObservation(
                neutralLinearRgb = rgb,
                neutralConfidence = 0.75,
                centreLinearRgb = rgb,
                surroundLinearRgb = rgb,
                centreLuminance = 0.4,
                surroundLuminance = 0.3,
                clippedFraction = 0.01,
                sampledPixels = 1_024,
                timestampMillis = elapsed,
            ),
            anchorKelvin = 5_600,
            anchorAgeMillis = 400L,
            estimate = UnderwaterWhiteBalanceSolution(6_500, 0.001, 0.8, 0.2),
            command = UnderwaterWhiteBalanceSolution(6_400, 0.0005, 0.7, 0.2),
            appliedRedGain = 2.1,
            appliedBlueGain = 1.4,
            analysisMicros = 850L,
        )
    }
}
