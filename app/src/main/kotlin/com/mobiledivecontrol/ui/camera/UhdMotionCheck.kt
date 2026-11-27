package com.mobiledivecontrol.ui.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import kotlin.math.abs

/** Applied only to a short, user-initiated high-rate qualification, after file and sensor timing pass. */
internal enum class UhdMotionCheck { Passed, TooLittleMotion, RepeatedFrames, Inconclusive }

internal fun classifyUhdMotion(differences: List<Double>, requiredPairs: Int): UhdMotionCheck {
    if (requiredPairs < 1 || differences.size < requiredPairs) return UhdMotionCheck.Inconclusive
    val median = differences.sorted()[differences.size / 2]
    if (median < 1.0) return UhdMotionCheck.TooLittleMotion
    val duplicates = differences.count { it < 0.15 || it < 0.2 * median }
    return if (duplicates * 20 > differences.size) UhdMotionCheck.RepeatedFrames else UhdMotionCheck.Passed
}

private const val GRID_W = 96
private const val GRID_H = 54

private fun readGrid(image: Image, grid: IntArray) {
    val plane = image.planes[0]
    val buf = plane.buffer
    val crop = image.cropRect
    for (row in 0 until GRID_H) {
        val y = crop.top + row * crop.height() / GRID_H
        val line = buf.position() + y * plane.rowStride
        for (col in 0 until GRID_W) {
            val x = crop.left + col * crop.width() / GRID_W
            grid[row * GRID_W + col] = buf.get(line + x * plane.pixelStride).toInt() and 255
        }
    }
}

internal fun checkUhdMotion(file: File, fps: Int, cancelled: () -> Boolean): UhdMotionCheck {
    val reader = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        reader.setDataSource(file.absolutePath)
        val track = (0 until reader.trackCount).first { reader.getTrackFormat(it)
            .getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val format = reader.getTrackFormat(track)
        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isHardwareAccelerated && mime in it.supportedTypes &&
                it.getCapabilitiesForType(mime).isFormatSupported(format)
        } ?: return UhdMotionCheck.Inconclusive
        val codec = MediaCodec.createByCodecName(hardware.name)
        decoder = codec
        codec.configure(format, null, null, 0)
        codec.start()
        reader.selectTrack(track)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputFrames = 0
        var havePrevious = false
        var previous = IntArray(GRID_W * GRID_H)
        var current = IntArray(previous.size)
        val pairs = fps * 2
        val differences = ArrayList<Double>(pairs)
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (differences.size < pairs && SystemClock.elapsedRealtime() < deadline && !cancelled()) {
            if (!inputEnded) {
                val slot = codec.dequeueInputBuffer(5000)
                if (slot >= 0) {
                    val bytes = reader.readSampleData(checkNotNull(codec.getInputBuffer(slot)), 0)
                    inputEnded = bytes < 0
                    if (inputEnded) codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        codec.queueInputBuffer(slot, 0, bytes, reader.sampleTime, 0)
                        reader.advance()
                    }
                }
            }
            val slot = codec.dequeueOutputBuffer(info, 5000)
            if (slot < 0) continue
            try {
                if (info.size > 0 && ++outputFrames > fps / 2) {
                    val image = codec.getOutputImage(slot) ?: return UhdMotionCheck.Inconclusive
                    image.use { readGrid(it, current) }
                    if (havePrevious) {
                        var total = 0
                        for (i in current.indices) total += abs(current[i] - previous[i])
                        differences.add(total.toDouble() / current.size)
                    }
                    val swap = previous
                    previous = current
                    current = swap
                    havePrevious = true
                }
            } finally { codec.releaseOutputBuffer(slot, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return classifyUhdMotion(differences, pairs)
    } finally {
        try { decoder?.release() } finally { reader.release() }
    }
}
