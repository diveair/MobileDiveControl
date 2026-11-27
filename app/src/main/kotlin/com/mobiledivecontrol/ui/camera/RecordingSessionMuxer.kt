package com.mobiledivecontrol.ui.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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

    fun videoFrameCount(path: String): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val retriever = MediaMetadataRetriever()
            val count = try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            } finally {
                retriever.release()
            }
            if (count != null && count > 0) return@runCatching count
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            format.getInteger("frame-count").takeIf { it > 0 }
        } finally {
            extractor.release()
        }
    }.getOrNull()

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

    /** Copy compressed video at the new clock and interleave audio processed at the same speed. */
    fun retime(
        context: Context,
        inputFile: File,
        outputFile: File,
        timestampScale: Double,
        playbackFrameRate: Double,
    ): Result<File> = runCatching {
        require(inputFile != outputFile && inputFile.isFile)
        require(timestampScale.isFinite() && timestampScale > 0 && playbackFrameRate.isFinite() && playbackFrameRate > 0)
        val video = MediaExtractor()
        val audio = MediaExtractor()
        val audioFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + "-audio.m4a")
        try {
            video.setDataSource(inputFile.absolutePath)
            fun MediaExtractor.track(type: String) = (0 until trackCount).firstOrNull {
                getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(type) == true
            }
            val videoIndex = requireNotNull(video.track("video/"))
            val originalAudio = video.track("audio/")
            val audioStart = originalAudio?.let {
                video.selectTrack(it)
                val start = video.sampleTime.coerceAtLeast(0L)
                video.unselectTrack(it)
                exportPlaybackAudio(context, inputFile, audioFile, 1.0 / timestampScale)
                audio.setDataSource(audioFile.absolutePath)
                val processedAudioTrack = requireNotNull(audio.track("audio/"))
                audio.selectTrack(processedAudioTrack)
                audio.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                check(audio.sampleTrackIndex == processedAudioTrack) { "Playback audio export contains no samples" }
                start
            }
            video.selectTrack(videoIndex)
            video.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val start = minOf(video.sampleTime, audioStart ?: Long.MAX_VALUE).coerceAtLeast(0L)
            val audioOffset = ((audioStart ?: start) - start) * timestampScale
            val firstAudioPts = if (originalAudio == null) 0L else audio.sampleTime
            val format = video.getTrackFormat(videoIndex)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, playbackFrameRate.roundToInt())
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.setLong(MediaFormat.KEY_DURATION,
                (format.getLong(MediaFormat.KEY_DURATION) * timestampScale).toLong())
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                val videoOut = muxer.addTrack(format)
                val audioOut = originalAudio?.let { muxer.addTrack(audio.getTrackFormat(requireNotNull(audio.track("audio/")))) }
                muxer.start()
                val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                // AAC encoder priming may start below zero. Only the track index signals end of stream.
                while (video.sampleTrackIndex >= 0 || (audioOut != null && audio.sampleTrackIndex >= 0)) {
                    val v = if (video.sampleTrackIndex >= 0) ((video.sampleTime - start) * timestampScale).toLong() else Long.MAX_VALUE
                    val a = if (audioOut != null && audio.sampleTrackIndex >= 0) (audio.sampleTime - firstAudioPts + audioOffset).toLong() else Long.MAX_VALUE
                    val source = if (v <= a) video else audio
                    buffer.clear()
                    val size = source.readSampleData(buffer, 0)
                    check(size >= 0) { "Unreadable sample during playback export" }
                    info.set(0, size, minOf(v, a).coerceAtLeast(0L), muxerSampleFlags(source.sampleFlags))
                    muxer.writeSampleData(if (v <= a) videoOut else checkNotNull(audioOut), buffer, info)
                    source.advance()
                }
                muxer.stop()
            } finally { muxer.release() }
        } finally {
            video.release()
            audio.release()
            audioFile.delete()
        }
        outputFile
    }.onFailure { outputFile.delete() }

    /** Preserve every captured frame; changing the MP4 clock does not require decoding video. */
    fun retimeHighSpeed(inputFile: File, outputFile: File, playbackFps: Double): Result<File> = runCatching {
        require(playbackFps.isFinite() && playbackFps > 0)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, playbackFps.roundToInt())
            // The source duration belongs to the sensor clock, not the playback clock.
            format.removeKey(MediaFormat.KEY_DURATION)
            extractor.selectTrack(track)
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outputTrack = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                muxer.start()
                val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                var frames = 0L
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, (frames * 1_000_000.0 / playbackFps).toLong(), muxerSampleFlags(extractor.sampleFlags))
                    muxer.writeSampleData(outputTrack, buffer, info)
                    frames++
                    if (!extractor.advance()) break
                }
                require(frames > 0) { "No high-speed frames captured" }
                muxer.stop()
                android.util.Log.i("DiveHighSpeed", "Lossless playback clock: frames=$frames playbackFps=$playbackFps durationUs=${frames * 1_000_000.0 / playbackFps}")
            } finally { muxer.release() }
        } finally { extractor.release() }
        outputFile
    }.onFailure { outputFile.delete() }

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
                bufferInfo.set(0, size, outputPtsUs, muxerSampleFlags(extractor.sampleFlags))
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

    /** MediaExtractor and MediaCodec reuse bit values for different flags; muxing needs keyframe only. */
    private fun muxerSampleFlags(extractorFlags: Int): Int =
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
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
