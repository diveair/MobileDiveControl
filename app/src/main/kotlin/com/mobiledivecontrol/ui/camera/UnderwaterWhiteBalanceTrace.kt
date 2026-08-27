package com.mobiledivecontrol.ui.camera

import com.mobiledivecontrol.core.UnderwaterFrameObservation
import com.mobiledivecontrol.core.UnderwaterWhiteBalanceSolution
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Compact, image-free AU evidence recorder. One dive is expensive; these samples make its exact
 * estimator inputs replayable on a workstation so coefficient and stability changes do not
 * require another dive merely to diagnose what happened. Calls are made on a dedicated executor.
 */
internal class UnderwaterWhiteBalanceTrace(
    private val directory: File,
) : AutoCloseable {
    companion object {
        private const val MIN_SAMPLE_INTERVAL_MS = 250L
        private const val FLUSH_EVERY_ROWS = 20

        private const val HEADER =
            "elapsed_ms,wall_ms,recording,depth_m,depth_conf,range_m,range_conf," +
                "neutral_r,neutral_g,neutral_b,neutral_conf,centre_r,centre_g,centre_b," +
                "surround_r,surround_g,surround_b,centre_luma,surround_luma,clipped,starved,samples," +
                "anchor_k,anchor_age_ms,estimate_k,estimate_duv,estimate_conf,dive_light," +
                "command_k,command_duv,applied_r_gain,applied_b_gain,analysis_us\n"
    }

    data class Sample(
        val elapsedMillis: Long,
        val wallMillis: Long,
        val recording: Boolean,
        val depthMeters: Double?,
        val depthConfidence: Double,
        val rangeMeters: Double?,
        val rangeConfidence: Double,
        val observation: UnderwaterFrameObservation?,
        val anchorKelvin: Int?,
        val anchorAgeMillis: Long?,
        val estimate: UnderwaterWhiteBalanceSolution,
        val command: UnderwaterWhiteBalanceSolution?,
        val appliedRedGain: Double?,
        val appliedBlueGain: Double?,
        val analysisMicros: Long?,
    )

    private var writer: BufferedWriter? = null
    private var startElapsedMillis = 0L
    private var lastSampleElapsedMillis = Long.MIN_VALUE
    private var rowsSinceFlush = 0
    var currentFile: File? = null
        private set

    @Synchronized
    fun start(elapsedMillis: Long, wallMillis: Long): File? {
        close()
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, "au-${wallMillis}.csv")
        return runCatching {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 16 * 1024)
            writer?.write(HEADER)
            writer?.flush()
            startElapsedMillis = elapsedMillis
            lastSampleElapsedMillis = Long.MIN_VALUE
            rowsSinceFlush = 0
            currentFile = file
            file
        }.onFailure {
            close()
            currentFile = null
        }.getOrNull()
    }

    @Synchronized
    fun record(sample: Sample) {
        val output = writer ?: return
        if (lastSampleElapsedMillis != Long.MIN_VALUE &&
            sample.elapsedMillis - lastSampleElapsedMillis < MIN_SAMPLE_INTERVAL_MS
        ) return
        lastSampleElapsedMillis = sample.elapsedMillis
        val observation = sample.observation
        val row = listOf(
            (sample.elapsedMillis - startElapsedMillis).coerceAtLeast(0L).toString(),
            sample.wallMillis.toString(),
            if (sample.recording) "1" else "0",
            number(sample.depthMeters),
            number(sample.depthConfidence),
            number(sample.rangeMeters),
            number(sample.rangeConfidence),
            number(observation?.neutralLinearRgb?.red),
            number(observation?.neutralLinearRgb?.green),
            number(observation?.neutralLinearRgb?.blue),
            number(observation?.neutralConfidence),
            number(observation?.centreLinearRgb?.red),
            number(observation?.centreLinearRgb?.green),
            number(observation?.centreLinearRgb?.blue),
            number(observation?.surroundLinearRgb?.red),
            number(observation?.surroundLinearRgb?.green),
            number(observation?.surroundLinearRgb?.blue),
            number(observation?.centreLuminance),
            number(observation?.surroundLuminance),
            number(observation?.clippedFraction),
            number(observation?.starvedFraction),
            observation?.sampledPixels?.toString().orEmpty(),
            sample.anchorKelvin?.toString().orEmpty(),
            sample.anchorAgeMillis?.toString().orEmpty(),
            sample.estimate.kelvin.toString(),
            number(sample.estimate.tintDuv),
            number(sample.estimate.confidence),
            number(sample.estimate.diveLightProbability),
            sample.command?.kelvin?.toString().orEmpty(),
            number(sample.command?.tintDuv),
            number(sample.appliedRedGain),
            number(sample.appliedBlueGain),
            sample.analysisMicros?.toString().orEmpty(),
        ).joinToString(separator = ",", postfix = "\n")
        runCatching {
            output.write(row)
            rowsSinceFlush++
            if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                output.flush()
                rowsSinceFlush = 0
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        rowsSinceFlush = 0
        currentFile = null
    }

    private fun number(value: Double?): String =
        value?.takeIf(Double::isFinite)?.let { String.format(Locale.US, "%.7f", it) }.orEmpty()
}
