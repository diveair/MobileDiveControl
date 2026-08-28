package com.mobiledivecontrol.ui.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mobiledivecontrol.core.RecordingSaveLocation
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Joins CameraX's reviewable pause segments without decoding or re-encoding them.
 *
 * Pausing must finalise the active MP4 before Android can preview it. Keeping those files private
 * and rewriting only their container timestamps lets the diver review/resume repeatedly while
 * Stop still publishes one continuous video to MediaStore. The recorder configuration is unchanged
 * during a session, so all segments have compatible encoded tracks.
 */
internal object RecordingSessionMuxer {
    private const val COPY_BUFFER_BYTES = 16 * 1024 * 1024
    private const val DEFAULT_VIDEO_FRAME_US = 33_333L

    fun publish(
        context: Context,
        segmentFiles: List<File>,
        displayName: String,
        location: RecordingSaveLocation,
    ): Result<Uri> {
        // A single CameraX segment is already a finalized MP4. Remuxing it cannot add any
        // information, but it does rewrite every 100 Mb/s sample and temporarily needs space for
        // a second multi-gigabyte file. Publish the original bytes directly; reserve MediaMuxer
        // exclusively for the timestamp repair that a genuinely multi-segment session needs.
        if (segmentFiles.size == 1) {
            return publishPreparedFile(context, segmentFiles.single(), displayName, location)
        }
        return runCatching {
            require(segmentFiles.isNotEmpty()) { "A recording session has no segments" }
            segmentFiles.forEach { file ->
                require(file.isFile && file.length() > 0L) { "Missing recording segment: $file" }
            }

            val resolver = context.contentResolver
            val values = outputValues(displayName, location)
            val outputUri = checkNotNull(
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
            ) { "MediaStore did not create an output row" }

            try {
                resolver.openFileDescriptor(outputUri, "rw")?.use { descriptor ->
                    muxSegments(segmentFiles, descriptor.fileDescriptor)
                } ?: error("MediaStore did not open the output file")

                publishPendingRow(resolver, outputUri)
                outputUri
            } catch (error: Throwable) {
                runCatching { resolver.delete(outputUri, null, null) }
                throw error
            }
        }
    }

    fun buildReview(segmentFiles: List<File>, outputFile: File): Result<Uri> = runCatching {
        require(segmentFiles.isNotEmpty()) { "A recording session has no segments" }
        if (segmentFiles.size == 1) {
            val segment = segmentFiles.single()
            require(segment.isFile && segment.length() > 0L) {
                "Missing recording segment: $segment"
            }
            return@runCatching Uri.fromFile(segment)
        }
        outputFile.parentFile?.mkdirs()
        RandomAccessFile(outputFile, "rw").use { destination ->
            destination.setLength(0L)
            muxSegments(segmentFiles, destination.fd)
        }
        Uri.fromFile(outputFile)
    }.onFailure {
        runCatching { outputFile.delete() }
    }

    /**
     * Losslessly changes an encoded clip's playback clock. Used for fractional cinema rates
     * that MediaRecorder's integer-only setVideoFrameRate API cannot express (23.976/29.97).
     */
    fun retime(
        inputFile: File,
        outputFile: File,
        timestampScale: Double,
        playbackFrameRate: Double,
    ): Result<File> = runCatching {
        require(inputFile.isFile && inputFile.length() > 0L) {
            "Missing input recording: $inputFile"
        }
        require(inputFile.absolutePath != outputFile.absolutePath) {
            "Retime output must differ from its input"
        }
        require(timestampScale.isFinite() && timestampScale > 0.0) {
            "Invalid timestamp scale $timestampScale"
        }
        require(playbackFrameRate.isFinite() && playbackFrameRate > 0.0) {
            "Invalid playback frame rate $playbackFrameRate"
        }
        outputFile.parentFile?.mkdirs()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            RandomAccessFile(outputFile, "rw").use { destination ->
                destination.setLength(0L)
                val muxer = MediaMuxer(
                    destination.fd,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                try {
                    val inputToOutput = mutableMapOf<Int, Int>()
                    var orientation = 0
                    var hasVideo = false
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            format.setLong(
                                MediaFormat.KEY_DURATION,
                                (format.getLong(MediaFormat.KEY_DURATION) * timestampScale)
                                    .toLong()
                                    .coerceAtLeast(1L),
                            )
                        }
                        if (mime.startsWith("video/")) {
                            hasVideo = true
                            format.setInteger(
                                MediaFormat.KEY_FRAME_RATE,
                                playbackFrameRate.roundToInt().coerceAtLeast(1),
                            )
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                orientation = format.getInteger(MediaFormat.KEY_ROTATION)
                            }
                        }
                        inputToOutput[index] = muxer.addTrack(format)
                        extractor.selectTrack(index)
                    }
                    require(hasVideo) { "Recording has no video track" }
                    if (orientation != 0) muxer.setOrientationHint(orientation)
                    muxer.start()

                    val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                    val info = MediaCodec.BufferInfo()
                    val firstSampleUs = extractor.sampleTime.takeIf { it >= 0L } ?: 0L
                    val lastPtsByTrack = mutableMapOf<Int, Long>()
                    while (true) {
                        val inputTrack = extractor.sampleTrackIndex
                        if (inputTrack < 0) break
                        val outputTrack = inputToOutput[inputTrack]
                        if (outputTrack == null) {
                            if (!extractor.advance()) break
                            continue
                        }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val scaledPtsUs = (
                            (extractor.sampleTime - firstSampleUs).coerceAtLeast(0L) * timestampScale
                        ).toLong()
                        val outputPtsUs = max(
                            scaledPtsUs,
                            (lastPtsByTrack[outputTrack] ?: -1L) + 1L,
                        )
                        info.set(0, size, outputPtsUs, extractor.sampleFlags)
                        muxer.writeSampleData(outputTrack, buffer, info)
                        lastPtsByTrack[outputTrack] = outputPtsUs
                        if (!extractor.advance()) break
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            }
        } finally {
            extractor.release()
        }
        require(outputFile.isFile && outputFile.length() > 0L) {
            "Retimed recording is empty"
        }
        outputFile
    }.onFailure {
        runCatching { outputFile.delete() }
    }

    fun publishPreparedFile(
        context: Context,
        preparedFile: File,
        displayName: String,
        location: RecordingSaveLocation,
    ): Result<Uri> = runCatching {
        require(preparedFile.isFile && preparedFile.length() > 0L) {
            "Missing prepared recording: $preparedFile"
        }
        val resolver = context.contentResolver
        val outputUri = checkNotNull(
            resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                outputValues(displayName, location),
            ),
        ) { "MediaStore did not create an output row" }
        try {
            resolver.openOutputStream(outputUri, "w")?.use { output ->
                preparedFile.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            } ?: error("MediaStore did not open the output stream")
            publishPendingRow(resolver, outputUri)
            outputUri
        } catch (error: Throwable) {
            runCatching { resolver.delete(outputUri, null, null) }
            throw error
        }
    }

    private fun muxSegments(segmentFiles: List<File>, output: java.io.FileDescriptor) {
        val firstExtractor = MediaExtractor()
        val outputTracks = linkedMapOf<String, Int>()
        var orientation = 0
        try {
            firstExtractor.setDataSource(segmentFiles.first().absolutePath)
            val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                for (index in 0 until firstExtractor.trackCount) {
                    val format = firstExtractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        outputTracks[mime.substringBefore('/')] = muxer.addTrack(format)
                        if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                            orientation = format.getInteger(MediaFormat.KEY_ROTATION)
                        }
                    }
                }
                require(outputTracks.containsKey("video")) { "Recording segment has no video track" }
                if (orientation != 0) muxer.setOrientationHint(orientation)
                muxer.start()

                val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                val bufferInfo = MediaCodec.BufferInfo()
                val lastOutputPtsUs = mutableMapOf<Int, Long>()
                var sessionOffsetUs = 0L

                segmentFiles.forEach { file ->
                    sessionOffsetUs += copySegment(
                        file = file,
                        muxer = muxer,
                        outputTracks = outputTracks,
                        buffer = buffer,
                        bufferInfo = bufferInfo,
                        sessionOffsetUs = sessionOffsetUs,
                        lastOutputPtsUs = lastOutputPtsUs,
                    )
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            firstExtractor.release()
        }
    }

    private fun copySegment(
        file: File,
        muxer: MediaMuxer,
        outputTracks: Map<String, Int>,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        sessionOffsetUs: Long,
        lastOutputPtsUs: MutableMap<Int, Long>,
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val inputToOutput = mutableMapOf<Int, Int>()
            var declaredDurationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val kind = mime.substringBefore('/')
                val outputTrack = outputTracks[kind] ?: continue
                validateCompatibleTrack(kind, format)
                extractor.selectTrack(index)
                inputToOutput[index] = outputTrack
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    declaredDurationUs = max(declaredDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            require(inputToOutput.keys.any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }) { "Recording segment ${file.name} has no compatible video track" }

            val firstSampleUs = extractor.sampleTime.takeIf { it >= 0L } ?: 0L
            var greatestLocalPtsUs = 0L
            while (true) {
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break
                val outputTrack = inputToOutput[inputTrack]
                if (outputTrack == null) {
                    if (!extractor.advance()) break
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val localPtsUs = (extractor.sampleTime - firstSampleUs).coerceAtLeast(0L)
                greatestLocalPtsUs = max(greatestLocalPtsUs, localPtsUs)
                val requestedPtsUs = sessionOffsetUs + localPtsUs
                val outputPtsUs = max(requestedPtsUs, (lastOutputPtsUs[outputTrack] ?: -1L) + 1L)
                bufferInfo.set(0, size, outputPtsUs, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                lastOutputPtsUs[outputTrack] = outputPtsUs
                if (!extractor.advance()) break
            }

            // The format duration gives the exact encoded span on CameraX output. The sampled
            // fallback prevents overlap if a vendor omits it and excludes time spent reviewing.
            return max(declaredDurationUs, greatestLocalPtsUs + DEFAULT_VIDEO_FRAME_US)
                .coerceAtLeast(1L)
        } finally {
            extractor.release()
        }
    }

    private fun validateCompatibleTrack(kind: String, format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME)
        require(mime?.startsWith("$kind/") == true) { "Incompatible $kind segment track: $mime" }
    }

    private fun normalizeRelativePath(path: String): String =
        path.replace('\\', '/').trim('/').let { normalized -> "$normalized/" }

    private fun outputValues(
        displayName: String,
        location: RecordingSaveLocation,
    ) = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, normalizeRelativePath(location.relativePath))
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    private fun publishPendingRow(
        resolver: android.content.ContentResolver,
        outputUri: Uri,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }
}
