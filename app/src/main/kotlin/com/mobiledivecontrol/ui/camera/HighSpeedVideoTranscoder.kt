package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Converts the fixed 120/240 fps stream required by Camera2's constrained-high-speed contract
 * into the selected effective capture cadence and playback cadence.
 *
 * Samsung's MediaRecorder accepts setCaptureRate for a Camera2 surface but still writes every
 * constrained-session frame. This explicit hardware-accelerated export is therefore required:
 * it drops frames to the requested output cadence and expands timestamps for slow playback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HighSpeedVideoTranscoder(context: Context) {
    companion object {
        private const val TAG = "DiveHighSpeedConvert"
        private const val FRAME_COUNT_KEY = "frame-count"
    }

    private val appContext = context.applicationContext
    private var activeTransformer: Transformer? = null
    private var activeOutputFile: File? = null

    val isBusy: Boolean
        get() = activeTransformer != null

    fun transcode(
        inputFile: File,
        outputFile: File,
        effectiveCaptureFps: Int,
        playbackFps: Double,
        onCompleted: (Result<File>) -> Unit,
    ) {
        check(!isBusy) { "A high-speed cadence conversion is already active" }
        require(inputFile.isFile && inputFile.length() > 0L) { "Missing high-speed input file" }
        require(effectiveCaptureFps > 0 && playbackFps > 0.0)
        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        activeOutputFile = outputFile

        val playbackRate = playbackFps.toFloat()
        val constrainedSourceFps = effectiveCaptureFps.coerceAtLeast(120)
        val measuredSourceFps = measureFrameRate(inputFile) ?: constrainedSourceFps.toDouble()
        // Samsung's nominal 120 fps stream measures about 118.5 fps on this device. Correct for
        // that clock difference so the exported track lands on the selected playback cadence.
        val speed = (
            playbackFps * constrainedSourceFps /
                (effectiveCaptureFps * measuredSourceFps)
            ).toFloat()
        val speedProvider = object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float = speed

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        // Samsung writes every constrained-session frame. Retain the selected
                        // fractional cadence by count, independent of timestamp-effect ordering.
                        ExactFrameDropEffect(
                            inputFrameRate = constrainedSourceFps,
                            targetFrameRate = effectiveCaptureFps,
                        ),
                        SpeedChangeEffect(speedProvider),
                    ),
                ),
            )
            .build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                activeTransformer = null
                activeOutputFile = null
                if (!outputFile.isFile || outputFile.length() <= 0L) {
                    onCompleted(Result.failure(IllegalStateException("High-speed conversion produced no video")))
                    return
                }
                inspectOutput(outputFile)
                Log.i(
                    TAG,
                    "Converted constrained stream: effectiveCapture=${effectiveCaptureFps}fps " +
                        "playback=${playbackFps}fps speed=$speed bytes=${outputFile.length()} " +
                        "result=$exportResult",
                )
                onCompleted(Result.success(outputFile))
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                activeTransformer = null
                activeOutputFile = null
                outputFile.delete()
                Log.e(TAG, "High-speed cadence conversion failed: $exportResult", exportException)
                onCompleted(Result.failure(exportException))
            }
        }
        val transformer = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(listener)
            .build()
        activeTransformer = transformer
        Log.i(
            TAG,
            "Converting ${inputFile.name}: effectiveCapture=${effectiveCaptureFps}fps " +
                "sourceMeasured=${"%.3f".format(measuredSourceFps)}fps " +
                "playback=${playbackRate}fps speed=$speed",
        )
        transformer.start(edited, outputFile.absolutePath)
    }

    private fun measureFrameRate(file: File): Double? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return@runCatching null
            val format = extractor.getTrackFormat(videoTrack)
            if (
                format.containsKey(FRAME_COUNT_KEY) &&
                format.containsKey(MediaFormat.KEY_DURATION)
            ) {
                val frameCount = format.getInteger(FRAME_COUNT_KEY)
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                if (frameCount > 1 && durationUs > 0L) {
                    return@runCatching frameCount * 1_000_000.0 / durationUs
                }
            }
            extractor.selectTrack(videoTrack)
            var firstUs = -1L
            var lastUs = -1L
            var samples = 0
            while (samples < 480) {
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0L) break
                if (firstUs < 0L) firstUs = sampleUs
                lastUs = sampleUs
                samples++
                if (!extractor.advance()) break
            }
            if (samples > 1 && lastUs > firstUs) {
                (samples - 1) * 1_000_000.0 / (lastUs - firstUs)
            } else {
                null
            }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun inspectOutput(file: File) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: error("Converted file has no video track")
                val format = extractor.getTrackFormat(videoTrack)
                extractor.selectTrack(videoTrack)
                var firstUs = -1L
                var lastUs = -1L
                var samples = 0
                while (samples < 1_000) {
                    val sampleUs = extractor.sampleTime
                    if (sampleUs < 0L) break
                    if (firstUs < 0L) firstUs = sampleUs
                    lastUs = sampleUs
                    samples++
                    if (!extractor.advance()) break
                }
                val measuredFps = if (samples > 1 && lastUs > firstUs) {
                    (samples - 1) * 1_000_000.0 / (lastUs - firstUs)
                } else {
                    null
                }
                val declaredFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    null
                }
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    null
                }
                Log.i(
                    TAG,
                    "Converted stream verified: declaredFps=$declaredFps " +
                        "measuredFps=${measuredFps?.let { "%.3f".format(it) }} " +
                        "durationUs=$durationUs samples=$samples format=$format",
                )
            } finally {
                extractor.release()
            }
        }.onFailure { error -> Log.w(TAG, "Could not inspect converted stream", error) }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
        activeOutputFile?.delete()
        activeOutputFile = null
    }
}
