package com.mobiledivecontrol.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class UnderwaterWhiteBalanceTest {
    @Test
    fun `kelvin and tint survive chromaticity projection`() {
        for (kelvin in listOf(2_300, 3_200, 5_600, 6_500, 8_000, 10_000)) {
            for (tint in listOf(-0.012, 0.0, 0.009)) {
                val xy = WhiteBalanceChromaticity.kelvinAndTintToXy(kelvin.toDouble(), tint)
                val recovered = WhiteBalanceChromaticity.xyToKelvinAndTint(xy)
                checkNotNull(recovered)
                assertTrue(abs(recovered.kelvin - kelvin) < 3.0, "$kelvin K recovered as ${recovered.kelvin}")
                assertTrue(abs(recovered.tintDuv - tint) < 0.00005, "$tint Duv recovered as ${recovered.tintDuv}")
            }
        }
    }

    @Test
    fun `a neutral output holds the current physical white point`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(6_500, 0.0, 1_000L)
        var result = solution(estimator, LinearRgb(1.0, 1.0, 1.0), startMs = 1_125L)
        repeat(30) { index ->
            result = solution(estimator, LinearRgb(1.0, 1.0, 1.0), startMs = 1_250L + index * 125L, current = result)
        }
        assertTrue(abs(result.kelvin - 6_500) <= 20, "neutral output drifted to ${result.kelvin}K")
        assertTrue(abs(result.tintDuv) < 0.0005, "neutral output invented tint ${result.tintDuv}")
    }

    @Test
    fun `green residual creates a greenward illuminant estimate`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(6_500, 0.0, 1_000L)
        var result = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
        repeat(50) { index ->
            result = solution(
                estimator,
                neutral = LinearRgb(0.78, 1.0, 0.78),
                startMs = 1_125L + index * 125L,
                current = result,
            )
        }
        assertTrue(result.tintDuv > 0.001, "green residual was not represented as positive Duv: $result")
    }

    @Test
    fun `unresolved zero depth cannot move white balance`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(5_600, 0.004, 1_000L)
        var current = UnderwaterWhiteBalanceSolution(5_600, 0.004, 0.0, 0.0)
        repeat(20) { index ->
            current = estimator.update(
                UnderwaterWhiteBalanceInput(
                    observation = null,
                    currentKelvin = current.kelvin,
                    currentTintDuv = current.tintDuv,
                    autoAnchorKelvin = null,
                    autoAnchorAgeMillis = null,
                    depthMeters = 0.0,
                    depthConfidence = 1.0,
                    subjectDistanceMeters = null,
                    subjectDistanceConfidence = 0.0,
                    timestampMillis = 1_125L + index * 125L,
                ),
            )
        }
        assertTrue(abs(current.kelvin - 5_600) <= 2)
        assertTrue(abs(current.tintDuv - 0.004) < 0.00005)
    }

    @Test
    fun `credible depth prior moves correction toward attenuated ambient white`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(6_500, 0.0, 1_000L)
        var current = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
        repeat(80) { index ->
            current = estimator.update(
                UnderwaterWhiteBalanceInput(
                    observation = null,
                    currentKelvin = current.kelvin,
                    currentTintDuv = current.tintDuv,
                    autoAnchorKelvin = null,
                    autoAnchorAgeMillis = null,
                    depthMeters = 10.0,
                    depthConfidence = 1.0,
                    subjectDistanceMeters = null,
                    subjectDistanceConfidence = 0.0,
                    timestampMillis = 1_125L + index * 125L,
                ),
            )
        }
        assertTrue(current.kelvin > 6_500, "depth prior moved the wrong way: $current")
        assertTrue(abs(current.tintDuv) > 0.0005, "off-locus underwater tint was discarded: $current")
    }

    @Test
    fun `clipped frame cannot pull the white point`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(5_600, 0.002, 1_000L)
        var current = UnderwaterWhiteBalanceSolution(5_600, 0.002, 0.0, 0.0)
        repeat(30) { index ->
            current = estimator.update(
                input(
                    timestampMillis = 1_125L + index * 125L,
                    current = current,
                    neutral = LinearRgb(0.2, 1.0, 2.0),
                    neutralConfidence = 1.0,
                    clippedFraction = 1.0,
                ),
            )
        }
        assertTrue(abs(current.kelvin - 5_600) <= 2, "clipped evidence moved Kelvin: $current")
        assertTrue(abs(current.tintDuv - 0.002) < 0.00005, "clipped evidence moved tint: $current")
    }

    @Test
    fun `missing frames hold the last dive light classification`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(6_500, 0.0, 1_000L)
        var current = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
        repeat(20) { index ->
            val timestamp = 1_125L + index * 125L
            current = estimator.update(
                input(
                    timestampMillis = timestamp,
                    current = current,
                    neutral = LinearRgb(1.0, 1.0, 1.0),
                    neutralConfidence = 0.0,
                    centre = LinearRgb(1.8, 1.0, 0.9),
                    surround = LinearRgb(0.7, 1.0, 1.2),
                    centreLuminance = 0.8,
                    surroundLuminance = 0.2,
                ),
            )
        }
        val detected = current.diveLightProbability
        assertTrue(detected > 0.7, "test light was not detected: $current")
        repeat(20) { index ->
            current = estimator.update(
                UnderwaterWhiteBalanceInput(
                    observation = null,
                    currentKelvin = current.kelvin,
                    currentTintDuv = current.tintDuv,
                    autoAnchorKelvin = null,
                    autoAnchorAgeMillis = null,
                    depthMeters = 10.0,
                    depthConfidence = 1.0,
                    subjectDistanceMeters = null,
                    subjectDistanceConfidence = 0.0,
                    timestampMillis = 3_750L + index * 125L,
                ),
            )
        }
        assertTrue(
            abs(current.diveLightProbability - detected) < 0.000001,
            "a missing image was treated as light-off evidence: detected=$detected current=$current",
        )
    }

    @Test
    fun `temporal result is approximately cadence independent`() {
        fun run(stepMillis: Long): UnderwaterWhiteBalanceSolution {
            val estimator = UnderwaterWhiteBalanceEstimator()
            estimator.reset(6_500, 0.0, 1_000L)
            var current = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
            var timestamp = 1_000L + stepMillis
            while (timestamp <= 6_000L) {
                current = estimator.update(
                    input(
                        timestampMillis = timestamp,
                        current = current,
                        neutral = LinearRgb(0.72, 1.0, 1.18),
                    ),
                )
                timestamp += stepMillis
            }
            return current
        }
        val eightHz = run(125L)
        val fourHz = run(250L)
        assertTrue(abs(eightHz.kelvin - fourHz.kelvin) < 120, "cadence changed Kelvin: $eightHz vs $fourHz")
        assertTrue(abs(eightHz.tintDuv - fourHz.tintDuv) < 0.0015, "cadence changed tint: $eightHz vs $fourHz")
    }

    @Test
    fun `closed loop converges to complete off locus white points`() {
        val targets = listOf(
            CorrelatedTemperature(3_200.0, -0.006),
            CorrelatedTemperature(5_600.0, 0.008),
            CorrelatedTemperature(9_000.0, -0.004),
        )
        for (target in targets) {
            val estimator = UnderwaterWhiteBalanceEstimator()
            estimator.reset(6_500, 0.0, 1_000L)
            var current = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
            repeat(160) { index ->
                val renderedNeutral = renderNeutralThroughCurrentWhite(target, current)
                current = estimator.update(
                    input(
                        timestampMillis = 1_125L + index * 125L,
                        current = current,
                        neutral = renderedNeutral,
                        neutralConfidence = 0.95,
                    ),
                )
            }
            assertTrue(
                abs(current.kelvin - target.kelvin) < 120,
                "${target.kelvin}K/${target.tintDuv} converged to $current",
            )
            assertTrue(
                abs(current.tintDuv - target.tintDuv) < 0.0015,
                "${target.kelvin}K/${target.tintDuv} converged to $current",
            )
        }
    }

    @Test
    fun `starved channel cannot masquerade as reliable chromatic evidence`() {
        val estimator = UnderwaterWhiteBalanceEstimator()
        estimator.reset(6_500, 0.0, 1_000L)
        var current = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0)
        repeat(30) { index ->
            val timestamp = 1_125L + index * 125L
            current = estimator.update(
                input(
                    timestampMillis = timestamp,
                    current = current,
                    neutral = LinearRgb(0.05, 1.0, 2.0),
                ).copy(
                    observation = input(
                        timestampMillis = timestamp,
                        current = current,
                        neutral = LinearRgb(0.05, 1.0, 2.0),
                    ).observation?.copy(starvedFraction = 1.0),
                ),
            )
        }
        assertTrue(abs(current.kelvin - 6_500) <= 2, "starved red moved the result: $current")
        assertTrue(abs(current.tintDuv) < 0.00005, "starved red invented tint: $current")
    }

    private fun solution(
        estimator: UnderwaterWhiteBalanceEstimator,
        neutral: LinearRgb,
        startMs: Long,
        current: UnderwaterWhiteBalanceSolution = UnderwaterWhiteBalanceSolution(6_500, 0.0, 0.0, 0.0),
    ): UnderwaterWhiteBalanceSolution = estimator.update(
        input(startMs, current, neutral),
    )

    private fun input(
        timestampMillis: Long,
        current: UnderwaterWhiteBalanceSolution,
        neutral: LinearRgb,
        neutralConfidence: Double = 0.9,
        centre: LinearRgb = neutral,
        surround: LinearRgb = neutral,
        centreLuminance: Double = 0.4,
        surroundLuminance: Double = 0.4,
        clippedFraction: Double = 0.0,
    ): UnderwaterWhiteBalanceInput = UnderwaterWhiteBalanceInput(
        observation = UnderwaterFrameObservation(
            neutralLinearRgb = neutral,
            neutralConfidence = neutralConfidence,
            centreLinearRgb = centre,
            surroundLinearRgb = surround,
            centreLuminance = centreLuminance,
            surroundLuminance = surroundLuminance,
            clippedFraction = clippedFraction,
            sampledPixels = 1_000,
            timestampMillis = timestampMillis,
        ),
        currentKelvin = current.kelvin,
        currentTintDuv = current.tintDuv,
        autoAnchorKelvin = null,
        autoAnchorAgeMillis = null,
        depthMeters = null,
        depthConfidence = 0.0,
        subjectDistanceMeters = null,
        subjectDistanceConfidence = 0.0,
        timestampMillis = timestampMillis,
    )

    /** Simulate the post-ISP neutral patch that AU receives while commanding [current]. */
    private fun renderNeutralThroughCurrentWhite(
        target: CorrelatedTemperature,
        current: UnderwaterWhiteBalanceSolution,
    ): LinearRgb {
        val targetXy = WhiteBalanceChromaticity.kelvinAndTintToXy(target.kelvin, target.tintDuv)
        val targetXyz = checkNotNull(WhiteBalanceChromaticity.xyToXyz(targetXy))
        val currentXy = WhiteBalanceChromaticity.kelvinAndTintToXy(
            current.kelvin.toDouble(),
            current.tintDuv,
        )
        val renderedXyz = checkNotNull(
            WhiteBalanceChromaticity.adaptXyz(
                targetXyz,
                currentXy,
                WhiteBalanceChromaticity.d65Xy,
            ),
        )
        val r = 3.2404542 * renderedXyz[0] - 1.5371385 * renderedXyz[1] - 0.4985314 * renderedXyz[2]
        val g = -0.9692660 * renderedXyz[0] + 1.8760108 * renderedXyz[1] + 0.0415560 * renderedXyz[2]
        val b = 0.0556434 * renderedXyz[0] - 0.2040259 * renderedXyz[1] + 1.0572252 * renderedXyz[2]
        check(r > 0.0 && g > 0.0 && b > 0.0) { "non-positive simulated neutral: $r/$g/$b" }
        return LinearRgb(r / g, 1.0, b / g)
    }
}
