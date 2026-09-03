package com.mobiledivecontrol.ui.camera

import androidx.camera.core.ImageProxy
import com.mobiledivecontrol.core.LinearRgb
import com.mobiledivecontrol.core.UnderwaterFrameObservation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Allocation-bounded RGBA statistics for AU. This is deliberately not an image enhancer: it
 * extracts a robust candidate illuminant and centre/surround evidence, then leaves every pixel
 * untouched. One 640x480 frame is sparsely sampled; CameraX's latest-only backpressure remains
 * authoritative so colour estimation can never queue behind gesture or autofocus work.
 */
internal class UnderwaterFrameAnalyzer {
    companion object {
        // 96 bins quantised a single step to roughly five percent in R/G or B/G. That is
        // visible as several hundred kelvin near the warm end of the scale. 256 bins still fit
        // in 12 KiB across all six histograms and make the estimator meaningfully continuous.
        private const val HISTOGRAM_BINS = 256
        private const val LOG_CHROMA_MIN = -2.4
        private const val LOG_CHROMA_MAX = 2.4
        private const val SAMPLE_STRIDE = 8

        private val SRGB_TO_LINEAR = DoubleArray(256) { value ->
            val x = value / 255.0
            if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        internal fun linearizeByte(value: Int): Double = SRGB_TO_LINEAR[value.coerceIn(0, 255)]
    }

    private val neutralRg = DoubleArray(HISTOGRAM_BINS)
    private val neutralBg = DoubleArray(HISTOGRAM_BINS)
    private val centreRg = DoubleArray(HISTOGRAM_BINS)
    private val centreBg = DoubleArray(HISTOGRAM_BINS)
    private val surroundRg = DoubleArray(HISTOGRAM_BINS)
    private val surroundBg = DoubleArray(HISTOGRAM_BINS)

    fun analyze(
        image: ImageProxy,
        timestampMillis: Long,
    ): UnderwaterFrameObservation? {
        val reader = AnalysisPixelReader(image)
        // CameraX supplies this analysis leg as 8-bit sRGB even when VideoCapture's independent
        // encoder surface is 10-bit HLG. Analyze the transfer actually present in this buffer.
        val transfer = SRGB_TO_LINEAR

        neutralRg.fill(0.0)
        neutralBg.fill(0.0)
        centreRg.fill(0.0)
        centreBg.fill(0.0)
        surroundRg.fill(0.0)
        surroundBg.fill(0.0)

        val centreX0 = image.width / 5
        val centreX1 = image.width * 4 / 5
        val centreY0 = image.height / 5
        val centreY1 = image.height * 4 / 5
        var attempted = 0
        var accepted = 0
        var clipped = 0
        var starved = 0
        var centreLumaSum = 0.0
        var centreLumaWeight = 0.0
        var surroundLumaSum = 0.0
        var surroundLumaWeight = 0.0

        var y = SAMPLE_STRIDE / 2
        while (y < image.height) {
            var x = SAMPLE_STRIDE / 2
            while (x < image.width) {
                attempted++
                val pixel = reader.argb(x, y)
                if (pixel != null) {
                    val r8 = pixel ushr 16 and 0xff
                    val g8 = pixel ushr 8 and 0xff
                    val b8 = pixel and 0xff
                    val maximum = maxOf(r8, g8, b8)
                    if (maximum >= 250) clipped++
                    // Once a channel lives in the bottom four 8-bit codes its chromaticity is
                    // dominated by quantisation/read noise. Deep-water red often reaches this
                    // state; reporting high confidence there would amplify noise into magenta.
                    if (maximum >= 12 && minOf(r8, g8, b8) <= 3) starved++
                    val r = transfer[r8]
                    val g = transfer[g8]
                    val b = transfer[b8]
                    val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    if (luma in 0.015..0.94 && r > 1e-6 && g > 1e-6 && b > 1e-6) {
                        accepted++
                        val logRg = ln(r / g).coerceIn(LOG_CHROMA_MIN, LOG_CHROMA_MAX)
                        val logBg = ln(b / g).coerceIn(LOG_CHROMA_MIN, LOG_CHROMA_MAX)
                        // Mid-tones carry more chromatic information; strongly coloured pixels
                        // remain represented but cannot dominate a neutral estimate.
                        val lumaWeight = sqrt(luma * (1.0 - luma)).coerceAtLeast(0.02)
                        val chromaWeight = 1.0 / (1.0 + 0.65 * (abs(logRg) + abs(logBg)))
                        val inCentre = x in centreX0 until centreX1 && y in centreY0 until centreY1
                        val spatialWeight = if (inCentre) 1.0 else 0.35
                        val weight = lumaWeight * chromaWeight * spatialWeight
                        add(neutralRg, logRg, weight)
                        add(neutralBg, logBg, weight)
                        if (inCentre) {
                            add(centreRg, logRg, lumaWeight)
                            add(centreBg, logBg, lumaWeight)
                            centreLumaSum += luma * lumaWeight
                            centreLumaWeight += lumaWeight
                        } else {
                            add(surroundRg, logRg, lumaWeight)
                            add(surroundBg, logBg, lumaWeight)
                            surroundLumaSum += luma * lumaWeight
                            surroundLumaWeight += lumaWeight
                        }
                    }
                }
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        if (accepted < 32 || total(neutralRg) <= 0.0 || total(neutralBg) <= 0.0) return null

        val neutralLogRg = percentile(neutralRg, 0.50)
        val neutralLogBg = percentile(neutralBg, 0.50)
        val rgSpread = percentile(neutralRg, 0.85) - percentile(neutralRg, 0.15)
        val bgSpread = percentile(neutralBg, 0.85) - percentile(neutralBg, 0.15)
        val coverage = (accepted / attempted.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
        val clipping = (clipped / attempted.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
        val starvation = (starved / attempted.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
        val concentration = exp(-0.70 * (rgSpread + bgSpread)).coerceIn(0.0, 1.0)
        // Clipping and channel starvation remain separate evidence fields and are applied once
        // by the estimator. Keeping neutralConfidence about coverage/concentration avoids
        // accidentally squaring either penalty.
        val confidence = (coverage * concentration).coerceIn(0.0, 1.0)
        val neutral = ratioRgb(neutralLogRg, neutralLogBg)

        val centre = if (total(centreRg) > 0.0 && total(centreBg) > 0.0) {
            ratioRgb(percentile(centreRg, 0.50), percentile(centreBg, 0.50))
        } else neutral
        val surround = if (total(surroundRg) > 0.0 && total(surroundBg) > 0.0) {
            ratioRgb(percentile(surroundRg, 0.50), percentile(surroundBg, 0.50))
        } else neutral

        return UnderwaterFrameObservation(
            neutralLinearRgb = neutral,
            neutralConfidence = confidence,
            centreLinearRgb = centre,
            surroundLinearRgb = surround,
            centreLuminance = if (centreLumaWeight > 0.0) centreLumaSum / centreLumaWeight else 0.0,
            surroundLuminance = if (surroundLumaWeight > 0.0) surroundLumaSum / surroundLumaWeight else 0.0,
            clippedFraction = clipping,
            sampledPixels = accepted,
            timestampMillis = timestampMillis,
            starvedFraction = starvation,
        )
    }

    private fun ratioRgb(logRg: Double, logBg: Double): LinearRgb =
        LinearRgb(red = exp(logRg), green = 1.0, blue = exp(logBg))

    private fun add(histogram: DoubleArray, value: Double, weight: Double) {
        val normalized = (value - LOG_CHROMA_MIN) / (LOG_CHROMA_MAX - LOG_CHROMA_MIN)
        val position = (normalized * (HISTOGRAM_BINS - 1)).coerceIn(0.0, HISTOGRAM_BINS - 1.0)
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(HISTOGRAM_BINS - 1)
        val fraction = position - lower
        histogram[lower] += weight * (1.0 - fraction)
        if (upper != lower) histogram[upper] += weight * fraction
    }

    private fun percentile(histogram: DoubleArray, fraction: Double): Double {
        val target = total(histogram) * fraction.coerceIn(0.0, 1.0)
        var accumulated = 0.0
        for (index in histogram.indices) {
            accumulated += histogram[index]
            if (accumulated >= target) {
                val t = index.toDouble() / (HISTOGRAM_BINS - 1)
                return LOG_CHROMA_MIN + t * (LOG_CHROMA_MAX - LOG_CHROMA_MIN)
            }
        }
        return LOG_CHROMA_MAX
    }

    private fun total(histogram: DoubleArray): Double = histogram.sum()
}
