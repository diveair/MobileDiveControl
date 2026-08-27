package com.mobiledivecontrol.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/** Linear-light RGB statistics sampled from the camera analysis stream. */
data class LinearRgb(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    fun isUsable(): Boolean =
        red.isFinite() && green.isFinite() && blue.isFinite() &&
            red > 1e-8 && green > 1e-8 && blue > 1e-8
}

/**
 * Robust frame evidence for full white balance. RGB values are linear-light ratios; their
 * absolute scale is deliberately irrelevant. The centre/surround pair is used only to decide
 * how much an artificial near-field light should weaken the ambient-depth prior.
 */
data class UnderwaterFrameObservation(
    val neutralLinearRgb: LinearRgb,
    val neutralConfidence: Double,
    val centreLinearRgb: LinearRgb,
    val surroundLinearRgb: LinearRgb,
    val centreLuminance: Double,
    val surroundLuminance: Double,
    val clippedFraction: Double,
    val sampledPixels: Int,
    val timestampMillis: Long,
    /** Fraction whose weakest channel is at the 8-bit noise floor while another has signal. */
    val starvedFraction: Double = 0.0,
)

data class UnderwaterWhiteBalanceInput(
    val observation: UnderwaterFrameObservation?,
    val currentKelvin: Int,
    val currentTintDuv: Double,
    val autoAnchorKelvin: Int?,
    val autoAnchorAgeMillis: Long?,
    val depthMeters: Double?,
    val depthConfidence: Double,
    val subjectDistanceMeters: Double?,
    val subjectDistanceConfidence: Double,
    val timestampMillis: Long,
)

/** The only camera-look output owned by AU: one physical illuminant white point. */
data class UnderwaterWhiteBalanceSolution(
    val kelvin: Int,
    /** Signed CIE 1960 UCS distance from the daylight/Planckian locus; positive is greenward. */
    val tintDuv: Double,
    val confidence: Double,
    val diveLightProbability: Double,
)

data class ChromaticityXy(val x: Double, val y: Double)
data class ChromaticityUv(val u: Double, val v: Double)
data class CorrelatedTemperature(val kelvin: Double, val tintDuv: Double)

/**
 * Small, deterministic colour-science kernel shared by the estimator and its tests.
 *
 * Tint is represented as signed distance in the CIE 1960 (u,v) diagram. This keeps AU honest:
 * Kelvin moves along the calibrated illuminant locus, while tint moves perpendicular to it.
 */
object WhiteBalanceChromaticity {
    private val XYZ_TO_BRADFORD = arrayOf(
        doubleArrayOf(0.8951, 0.2664, -0.1614),
        doubleArrayOf(-0.7502, 1.7135, 0.0367),
        doubleArrayOf(0.0389, -0.0685, 1.0296),
    )
    private val BRADFORD_TO_XYZ = arrayOf(
        doubleArrayOf(0.9869929, -0.1470543, 0.1599627),
        doubleArrayOf(0.4323053, 0.5183603, 0.0492912),
        doubleArrayOf(-0.0085287, 0.0400428, 0.9684867),
    )
    private val LINEAR_SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.4124564, 0.3575761, 0.1804375),
        doubleArrayOf(0.2126729, 0.7151522, 0.0721750),
        doubleArrayOf(0.0193339, 0.1191920, 0.9503041),
    )

    val d65Xy = ChromaticityXy(0.3127, 0.3290)

    fun kelvinToXy(kelvin: Double): ChromaticityXy {
        val t = kelvin.coerceIn(1_667.0, 25_000.0)
        return if (t < 4_000.0) {
            val x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) +
                0.8776956e3 / t + 0.179910
            val y = if (t <= 2_222.0) {
                -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            } else {
                -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            }
            ChromaticityXy(x, y)
        } else {
            // A photographic Kelvin dial follows the daylight locus above 4000 K.
            val x = if (t <= 7_000.0) {
                -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
            } else {
                -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
            }
            ChromaticityXy(x, -3.0 * x * x + 2.87 * x - 0.275)
        }
    }

    fun xyToUv(xy: ChromaticityXy): ChromaticityUv? {
        val denominator = -2.0 * xy.x + 12.0 * xy.y + 3.0
        if (!denominator.isFinite() || abs(denominator) < 1e-12) return null
        val u = 4.0 * xy.x / denominator
        val v = 6.0 * xy.y / denominator
        return ChromaticityUv(u, v).takeIf { u.isFinite() && v.isFinite() }
    }

    fun uvToXy(uv: ChromaticityUv): ChromaticityXy? {
        val denominator = 2.0 * uv.u - 8.0 * uv.v + 4.0
        if (!denominator.isFinite() || abs(denominator) < 1e-12) return null
        val x = 3.0 * uv.u / denominator
        val y = 2.0 * uv.v / denominator
        return ChromaticityXy(x, y).takeIf {
            x.isFinite() && y.isFinite() && x > 0.0 && y > 0.0 && x + y < 1.0
        }
    }

    fun kelvinAndTintToXy(kelvin: Double, tintDuv: Double): ChromaticityXy {
        val base = kelvinToXy(kelvin)
        if (abs(tintDuv) < 1e-12) return base
        val baseUv = xyToUv(base) ?: return base
        val normal = locusNormal(kelvin)
        return uvToXy(
            ChromaticityUv(
                baseUv.u + normal.u * tintDuv,
                baseUv.v + normal.v * tintDuv,
            ),
        ) ?: base
    }

    /** Nearest point on the supported Kelvin locus plus its signed perpendicular residual. */
    fun xyToKelvinAndTint(
        xy: ChromaticityXy,
        minKelvin: Int = CameraCatalog.WB_MIN_KELVIN,
        maxKelvin: Int = CameraCatalog.WB_MAX_KELVIN,
    ): CorrelatedTemperature? {
        val target = xyToUv(xy) ?: return null
        val minMired = 1_000_000.0 / maxKelvin
        val maxMired = 1_000_000.0 / minKelvin
        var bestMired = minMired
        var bestDistance = Double.POSITIVE_INFINITY
        var mired = minMired
        while (mired <= maxMired + 1e-9) {
            val locus = xyToUv(kelvinToXy(1_000_000.0 / mired))
            if (locus == null) {
                mired += 0.5
                continue
            }
            val distance = squaredDistance(target, locus)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMired = mired
            }
            mired += 0.5
        }

        // Golden-section refinement in reciprocal-temperature space.
        var lo = (bestMired - 0.75).coerceAtLeast(minMired)
        var hi = (bestMired + 0.75).coerceAtMost(maxMired)
        repeat(18) {
            val left = hi - (hi - lo) * 0.6180339887498949
            val right = lo + (hi - lo) * 0.6180339887498949
            val leftUv = xyToUv(kelvinToXy(1_000_000.0 / left)) ?: return@repeat
            val rightUv = xyToUv(kelvinToXy(1_000_000.0 / right)) ?: return@repeat
            if (squaredDistance(target, leftUv) <= squaredDistance(target, rightUv)) hi = right else lo = left
        }
        bestMired = (lo + hi) / 2.0
        val kelvin = (1_000_000.0 / bestMired).coerceIn(minKelvin.toDouble(), maxKelvin.toDouble())
        val locus = xyToUv(kelvinToXy(kelvin)) ?: return null
        val normal = locusNormal(kelvin)
        val duv = (target.u - locus.u) * normal.u + (target.v - locus.v) * normal.v
        return CorrelatedTemperature(kelvin, duv)
    }

    fun xyToXyz(xy: ChromaticityXy): DoubleArray? {
        if (!xy.x.isFinite() || !xy.y.isFinite() || xy.y <= 1e-12 || xy.x + xy.y >= 1.0) return null
        return doubleArrayOf(xy.x / xy.y, 1.0, (1.0 - xy.x - xy.y) / xy.y)
    }

    fun xyzToXy(xyz: DoubleArray): ChromaticityXy? {
        if (xyz.size < 3 || xyz.any { !it.isFinite() }) return null
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum <= 1e-12) return null
        val x = xyz[0] / sum
        val y = xyz[1] / sum
        return ChromaticityXy(x, y).takeIf { x > 0.0 && y > 0.0 && x + y < 1.0 }
    }

    fun linearRgbToXyz(rgb: LinearRgb): DoubleArray? {
        if (!rgb.isUsable()) return null
        return multiply(LINEAR_SRGB_TO_XYZ, doubleArrayOf(rgb.red, rgb.green, rgb.blue))
    }

    /** Bradford adaptation of an XYZ colour from one physical white point to another. */
    fun adaptXyz(xyz: DoubleArray, sourceWhite: ChromaticityXy, destinationWhite: ChromaticityXy): DoubleArray? {
        val source = xyToXyz(sourceWhite) ?: return null
        val destination = xyToXyz(destinationWhite) ?: return null
        val sourceCone = multiply(XYZ_TO_BRADFORD, source)
        val destinationCone = multiply(XYZ_TO_BRADFORD, destination)
        if (sourceCone.any { abs(it) < 1e-12 }) return null
        val colourCone = multiply(XYZ_TO_BRADFORD, xyz)
        val adaptedCone = DoubleArray(3) { i -> colourCone[i] * destinationCone[i] / sourceCone[i] }
        return multiply(BRADFORD_TO_XYZ, adaptedCone)
    }

    private fun locusNormal(kelvin: Double): ChromaticityUv {
        val mired = 1_000_000.0 / kelvin
        val cooler = xyToUv(kelvinToXy(1_000_000.0 / (mired - 0.25)))!!
        val warmer = xyToUv(kelvinToXy(1_000_000.0 / (mired + 0.25)))!!
        val du = warmer.u - cooler.u
        val dv = warmer.v - cooler.v
        val length = hypot(du, dv).coerceAtLeast(1e-12)
        var nu = -dv / length
        var nv = du / length
        // Positive Duv is conventionally the greenward/above-locus side.
        if (nv < 0.0) {
            nu = -nu
            nv = -nv
        }
        return ChromaticityUv(nu, nv)
    }

    private fun squaredDistance(a: ChromaticityUv, b: ChromaticityUv): Double {
        val du = a.u - b.u
        val dv = a.v - b.v
        return du * du + dv * dv
    }

    private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
        DoubleArray(3) { row ->
            matrix[row][0] * vector[0] + matrix[row][1] * vector[1] + matrix[row][2] * vector[2]
        }
}

/**
 * Confidence-weighted full-white-point controller. The optics prior is intentionally weak:
 * fixed RGB coefficients cannot identify arbitrary water, range, reflectance and backscatter.
 * Its job is to make depth informative when image evidence is weak, never to overrule observed
 * neutral evidence or manufacture colour that the sensor did not see.
 */
class UnderwaterWhiteBalanceEstimator {
    companion object {
        private const val MIN_KELVIN = CameraCatalog.WB_MIN_KELVIN
        private const val MAX_KELVIN = CameraCatalog.WB_MAX_KELVIN
        private const val MAX_ABS_DUV = 0.040

        // Conservative clear-water seed, in inverse metres, retained only as a low-weight prior.
        private val AMBIENT_ATTENUATION = LinearRgb(0.15, 0.07, 0.03)
        private val RETURN_ATTENUATION = LinearRgb(0.10, 0.045, 0.025)
    }

    private var filteredUv: ChromaticityUv? = null
    private var lastTimestampMillis: Long? = null
    private var filteredDiveLightProbability = 0.0

    @Synchronized
    fun reset(kelvin: Int, tintDuv: Double, timestampMillis: Long) {
        filteredUv = WhiteBalanceChromaticity.xyToUv(
            WhiteBalanceChromaticity.kelvinAndTintToXy(kelvin.toDouble(), tintDuv),
        )
        lastTimestampMillis = timestampMillis
        filteredDiveLightProbability = 0.0
    }

    @Synchronized
    fun update(input: UnderwaterWhiteBalanceInput): UnderwaterWhiteBalanceSolution {
        val previousTimestamp = lastTimestampMillis ?: input.timestampMillis
        val dt = ((input.timestampMillis - previousTimestamp).coerceAtLeast(0L) / 1_000.0)
            .coerceIn(0.016, 1.0)
        lastTimestampMillis = input.timestampMillis
        val currentXy = WhiteBalanceChromaticity.kelvinAndTintToXy(
            input.currentKelvin.toDouble(),
            input.currentTintDuv,
        )
        val currentUv = WhiteBalanceChromaticity.xyToUv(currentXy)!!
        if (filteredUv == null) filteredUv = currentUv

        val previousDiveLightProbability = filteredDiveLightProbability
        val lightMeasurement = input.observation?.let(::diveLightProbability)
        if (lightMeasurement != null) {
            // Time-based filtering makes the answer independent of analysis cadence. A pressure
            // packet without a fresh frame must not be interpreted as evidence that a dive light
            // turned off; it simply carries no new lighting evidence.
            val lightAlpha = 1.0 - exp(-dt / 0.45)
            filteredDiveLightProbability +=
                lightAlpha * (lightMeasurement - filteredDiveLightProbability)
        }

        val candidates = mutableListOf<Pair<ChromaticityUv, Double>>()
        val observation = input.observation
        if (observation != null && observation.neutralLinearRgb.isUsable()) {
            val observedXyz = WhiteBalanceChromaticity.linearRgbToXyz(observation.neutralLinearRgb)
            val actualXyz = observedXyz?.let {
                // The output neutral has already been adapted current-white -> D65. Undo that
                // adaptation before estimating the physical illuminant that entered the lens.
                WhiteBalanceChromaticity.adaptXyz(
                    it,
                    WhiteBalanceChromaticity.d65Xy,
                    currentXy,
                )
            }
            val imageXy = actualXyz?.let(WhiteBalanceChromaticity::xyzToXy)
            val imageUv = imageXy?.let(WhiteBalanceChromaticity::xyToUv)
            val imageConfidence = (
                observation.neutralConfidence.coerceIn(0.0, 1.0) *
                    (1.0 - observation.clippedFraction.coerceIn(0.0, 1.0)) *
                    (1.0 - observation.starvedFraction.coerceIn(0.0, 1.0))
                ).coerceIn(0.0, 1.0)
            if (imageUv != null && imageConfidence > 0.01) {
                candidates += clampAround(imageUv, currentUv, 0.065) to (2.4 * imageConfidence)
            }
        }

        val depth = input.depthMeters?.takeIf { it.isFinite() && it >= 0.0 }
        if (depth != null && input.depthConfidence > 0.0) {
            val range = input.subjectDistanceMeters
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(0.2, 5.0)
                ?: 0.0
            val rangeWeight = input.subjectDistanceConfidence.coerceIn(0.0, 1.0)
            fun channel(ambient: Double, returned: Double): Double =
                exp(-(ambient * depth + returned * range * rangeWeight))
            val ambientRgb = LinearRgb(
                channel(AMBIENT_ATTENUATION.red, RETURN_ATTENUATION.red),
                channel(AMBIENT_ATTENUATION.green, RETURN_ATTENUATION.green),
                channel(AMBIENT_ATTENUATION.blue, RETURN_ATTENUATION.blue),
            )
            val physicsUv = WhiteBalanceChromaticity.linearRgbToXyz(ambientRgb)
                ?.let(WhiteBalanceChromaticity::xyzToXy)
                ?.let(WhiteBalanceChromaticity::xyToUv)
            if (physicsUv != null) {
                val usefulDepth = (depth / 3.0).coerceIn(0.0, 1.0)
                val weight = 0.45 * input.depthConfidence.coerceIn(0.0, 1.0) * usefulDepth *
                    (1.0 - filteredDiveLightProbability)
                if (weight > 0.001) candidates += clampAround(physicsUv, currentUv, 0.070) to weight
            }
        }

        input.autoAnchorKelvin?.let { kelvin ->
            val age = input.autoAnchorAgeMillis?.coerceAtLeast(0L) ?: Long.MAX_VALUE
            val weight = 0.24 * exp(-age.toDouble() / 12_000.0)
            if (weight > 0.002) {
                val uv = WhiteBalanceChromaticity.xyToUv(
                    WhiteBalanceChromaticity.kelvinAndTintToXy(kelvin.toDouble(), 0.0),
                )
                if (uv != null) candidates += uv to weight
            }
        }

        // Holding the current solution is an explicit prior. With no credible evidence AU is
        // stable, rather than chasing the average colour of a fish or a blue empty-water frame.
        val externalWeight = candidates.sumOf { it.second }
        val currentWeight = 0.35 + 0.75 / (1.0 + externalWeight)
        var weightedU = currentUv.u * currentWeight
        var weightedV = currentUv.v * currentWeight
        var totalWeight = currentWeight
        for ((uv, weight) in candidates) {
            weightedU += uv.u * weight
            weightedV += uv.v * weight
            totalWeight += weight
        }
        val measurement = ChromaticityUv(weightedU / totalWeight, weightedV / totalWeight)
        val confidence = (externalWeight / (externalWeight + currentWeight)).coerceIn(0.0, 1.0)

        val previous = filteredUv ?: currentUv
        val lightStep = lightMeasurement?.let { abs(it - previousDiveLightProbability) } ?: 0.0
        val tau = if (lightStep > 0.30) 0.25 else 0.40 + (1.0 - confidence) * 2.6
        val alpha = 1.0 - exp(-dt / tau)
        var du = (measurement.u - previous.u) * alpha
        var dv = (measurement.v - previous.v) * alpha
        val distance = hypot(du, dv)
        val maximumStep = (0.004 + 0.016 * confidence) * dt
        if (distance > maximumStep && distance > 1e-12) {
            val scale = maximumStep / distance
            du *= scale
            dv *= scale
        }
        val next = if (hypot(du, dv) < 0.00001) previous else {
            ChromaticityUv(previous.u + du, previous.v + dv)
        }
        filteredUv = next

        val nextXy = WhiteBalanceChromaticity.uvToXy(next) ?: currentXy
        val correlated = WhiteBalanceChromaticity.xyToKelvinAndTint(nextXy)
            ?: CorrelatedTemperature(input.currentKelvin.toDouble(), input.currentTintDuv)
        val kelvin = correlated.kelvin.roundToInt().coerceIn(MIN_KELVIN, MAX_KELVIN)
        val tint = correlated.tintDuv.coerceIn(-MAX_ABS_DUV, MAX_ABS_DUV)

        // Keep the internal state on exactly the hardware-commandable white point. Otherwise a
        // prolonged out-of-range estimate could wind up behind the clamp and jump when released.
        filteredUv = WhiteBalanceChromaticity.xyToUv(
            WhiteBalanceChromaticity.kelvinAndTintToXy(kelvin.toDouble(), tint),
        )
        return UnderwaterWhiteBalanceSolution(
            kelvin = kelvin,
            tintDuv = tint,
            confidence = confidence,
            diveLightProbability = filteredDiveLightProbability.coerceIn(0.0, 1.0),
        )
    }

    private fun diveLightProbability(observation: UnderwaterFrameObservation): Double {
        if (!observation.centreLinearRgb.isUsable() || !observation.surroundLinearRgb.isUsable()) return 0.0
        val deltaLuma = ln(
            (observation.centreLuminance + 1e-6) /
                (observation.surroundLuminance + 1e-6),
        )
        val centreRed = ln(observation.centreLinearRgb.red / observation.centreLinearRgb.green)
        val surroundRed = ln(observation.surroundLinearRgb.red / observation.surroundLinearRgb.green)
        val restoredRed = centreRed - surroundRed
        val brightnessEvidence = smoothStep(0.12, 0.85, deltaLuma)
        val redEvidence = smoothStep(0.025, 0.50, restoredRed)
        return (brightnessEvidence * redEvidence).coerceIn(0.0, 1.0)
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun clampAround(value: ChromaticityUv, centre: ChromaticityUv, radius: Double): ChromaticityUv {
        val du = value.u - centre.u
        val dv = value.v - centre.v
        val distance = hypot(du, dv)
        if (distance <= radius || distance < 1e-12) return value
        val scale = radius / distance
        return ChromaticityUv(centre.u + du * scale, centre.v + dv * scale)
    }
}
