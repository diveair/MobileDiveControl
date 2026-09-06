package com.mobiledivecontrol.ui.camera

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Change only a private, finalized MP4's timing tables; encoded pictures and color stay intact. */
internal object SlowMotionMp4Clock {
    private const val MAX_METADATA_BYTES = 32 * 1024 * 1024
    data class TimingRun(val firstFrame: Long, val count: Long, val durationTicks: Long)
    data class SourceTiming(val timeScale: Long, val runs: List<TimingRun>)
    private data class Atom(val start: Int, val end: Int, val payload: Int, val type: String)
    private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and 0xffff_ffffL
    private fun ByteBuffer.putU32(at: Int, value: Long) {
        require(value in 0..0xffff_ffffL) { "MP4 duration exceeds its field" }
        putInt(at, value.toInt())
    }

    private fun atoms(data: ByteBuffer, start: Int, end: Int): List<Atom> {
        val result = mutableListOf<Atom>()
        var at = start
        while (at < end) {
            require(end - at >= 8) { "Truncated MP4 atom" }
            val rawSize = data.u32(at)
            val header = if (rawSize == 1L) 16 else 8
            require(end - at >= header)
            val size = when (rawSize) { 0L -> (end - at).toLong(); 1L -> data.getLong(at + 8); else -> rawSize }
            require(size >= header && size <= end - at) { "Invalid MP4 atom size" }
            val type = String(ByteArray(4) { data.get(at + 4 + it) }, Charsets.US_ASCII)
            result += Atom(at, at + size.toInt(), at + header, type)
            at += size.toInt()
        }
        return result
    }

    fun retimeInPlace(
        file: File,
        playbackFps: Int,
        onSourceTiming: ((SourceTiming) -> Unit)? = null,
    ): Result<File> = runCatching {
        require(playbackFps > 0)
        RandomAccessFile(file, "rw").use { stream ->
            var at = 0L
            var movieOffset = -1L
            var movieSize = 0
            var hasMedia = false
            while (at < stream.length()) {
                require(stream.length() - at >= 8)
                stream.seek(at)
                val rawSize = stream.readInt().toLong() and 0xffff_ffffL
                val type = ByteArray(4).also(stream::readFully).toString(Charsets.US_ASCII)
                val header = if (rawSize == 1L) 16 else 8
                val size = when (rawSize) { 0L -> stream.length() - at; 1L -> stream.readLong(); else -> rawSize }
                require(size >= header && size <= stream.length() - at)
                require(type != "moof") { "Fragmented MP4 needs remuxing" }
                if (type == "mdat") hasMedia = true
                if (type == "moov") {
                    require(movieOffset < 0 && size <= MAX_METADATA_BYTES)
                    movieOffset = at
                    movieSize = size.toInt()
                }
                at += size
            }
            require(hasMedia && movieOffset >= 0) { "Missing MP4 movie/media" }
            val original = ByteArray(movieSize)
            stream.seek(movieOffset)
            stream.readFully(original)
            val updated = original.copyOf()
            val data = ByteBuffer.wrap(updated).order(ByteOrder.BIG_ENDIAN)
            fun children(atom: Atom) = atoms(data, atom.payload, atom.end)
            fun child(atom: Atom, type: String) = children(atom).single { it.type == type }
            fun version(atom: Atom): Int = (data.get(atom.payload).toInt() and 0xff).also { require(it in 0..1) }
            fun putDuration(atom: Atom, offset0: Int, offset1: Int, value: Long) {
                require(value >= 0)
                val long = version(atom) == 1
                val offset = atom.payload + if (long) offset1 else offset0
                require(offset + (if (long) 8 else 4) <= atom.end)
                if (long) data.putLong(offset, value) else data.putU32(offset, value)
            }
            val movie = atoms(data, 0, movieSize).single()
            require(children(movie).none { it.type == "mvex" })
            val track = child(movie, "trak") // Only our single-video, no-audio slow-motion files.
            val media = child(track, "mdia")
            val handler = child(media, "hdlr")
            require(handler.payload + 12 <= handler.end)
            require(data.getInt(handler.payload + 8) == 0x76696465) { "Track is not video" }
            val table = child(child(media, "minf"), "stbl")
            val timing = child(table, "stts")
            require(timing.payload + 8 <= timing.end)
            require(version(timing) == 0)
            val entries = data.u32(timing.payload + 4)
            require(entries > 0 && entries <= (timing.end - timing.payload - 8) / 8)
            val sizes = child(table, "stsz")
            require(sizes.payload + 12 <= sizes.end)
            val frameCount = data.u32(sizes.payload + 8)
            var timedFrames = 0L
            val sourceRuns = if (onSourceTiming != null) mutableListOf<TimingRun>() else null
            repeat(entries.toInt()) { i ->
                val count = data.u32(timing.payload + 8 + i * 8)
                require(count > 0)
                sourceRuns?.add(TimingRun(timedFrames, count, data.u32(timing.payload + 12 + i * 8)))
                timedFrames += count
            }
            require(frameCount > 0 && frameCount == timedFrames) { "Inconsistent MP4 sample count" }
            children(table).firstOrNull { it.type == "ctts" }?.let { composition ->
                require(composition.payload + 8 <= composition.end)
                val count = data.u32(composition.payload + 4)
                require(count <= (composition.end - composition.payload - 8) / 8)
                repeat(count.toInt()) { i ->
                    require(data.getInt(composition.payload + 12 + i * 8) == 0) { "Reordered video needs remuxing" }
                }
            }
            val mediaHeader = child(media, "mdhd")
            val movieHeader = child(movie, "mvhd")
            fun timeScale(header: Atom): Long {
                val offset = header.payload + if (version(header) == 1) 20 else 12
                require(offset + 4 <= header.end)
                return data.u32(offset)
            }
            val mediaScale = timeScale(mediaHeader)
            val movieScale = timeScale(movieHeader)
            require(movieScale > 0 && mediaScale >= playbackFps && mediaScale % playbackFps == 0L)
            val delta = mediaScale / playbackFps
            val mediaDuration = Math.multiplyExact(frameCount, delta)
            val movieDuration = Math.addExact(Math.multiplyExact(frameCount, movieScale), playbackFps - 1L) / playbackFps
            repeat(entries.toInt()) { i -> data.putU32(timing.payload + 12 + i * 8, delta) }
            putDuration(mediaHeader, 16, 24, mediaDuration)
            putDuration(movieHeader, 16, 24, movieDuration)
            putDuration(child(track, "tkhd"), 20, 28, movieDuration)
            children(track).firstOrNull { it.type == "edts" }?.let { edits ->
                val edit = child(edits, "elst")
                require(data.u32(edit.payload + 4) == 1L) { "Complex edit list needs remuxing" }
                val long = version(edit) == 1
                require(edit.payload + (if (long) 28 else 20) <= edit.end)
                val mediaTime = edit.payload + if (long) 16 else 12
                require((if (long) data.getLong(mediaTime) else data.getInt(mediaTime).toLong()) >= 0)
                require(data.getInt(edit.payload + if (long) 24 else 16) == 0x10000)
                putDuration(edit, 8, 8, movieDuration)
                if (long) data.putLong(mediaTime, 0) else data.putInt(mediaTime, 0)
            }
            // All validation precedes the write. Atom lengths, sample offsets, and mdat bytes
            // remain identical; restore the original header if the write itself fails.
            try {
                stream.seek(movieOffset)
                stream.write(updated)
                stream.fd.sync()
            } catch (error: Throwable) {
                runCatching { stream.seek(movieOffset); stream.write(original); stream.fd.sync() }
                throw error
            }
            // Observe original capture timing before the playback clock hides gaps. Diagnostics
            // must neither read picture payloads nor turn a successful retime into a failure.
            sourceRuns?.let { runCatching { onSourceTiming?.invoke(SourceTiming(mediaScale, it)) } }
        }
        file
    }
}
