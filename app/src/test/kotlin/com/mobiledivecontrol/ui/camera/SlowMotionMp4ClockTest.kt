package com.mobiledivecontrol.ui.camera

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SlowMotionMp4ClockTest {
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
    private fun atom(type: String, vararg payload: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(8 + payload.sumOf { it.size }); out.writeBytes(type); payload.forEach(out::write)
        }
    }.toByteArray()
    private fun header(version1: Boolean, track: Boolean = false): ByteArray {
        val size = if (version1) 40 else 28
        return ByteBuffer.allocate(size).apply {
            putInt(if (version1) 0x01000000 else 0)
            val offset = if (version1) 20 else 12
            putInt(offset, if (track) 1 else 90000)
            if (version1) putLong(if (track) 28 else 24, 9000) else putInt(if (track) 20 else 16, 9000)
        }.array()
    }
    private fun fixture(version1: Boolean = false, secondTrack: Boolean = false, ctts: Boolean = false): ByteArray {
        val timing = atom("stts", ints(0, 2, 2, 750, 3, 751))
        val table = atom("stbl", timing, atom("stsz", ints(0, 1, 5)),
            if (ctts) atom("ctts", ints(0, 1, 5, 1)) else byteArrayOf())
        val media = atom("mdia", atom("mdhd", header(version1)), atom("hdlr", ints(0, 0, 0x76696465)), atom("minf", table))
        val track = atom("trak", atom("tkhd", header(version1, true)), media,
            atom("edts", atom("elst", ints(0, 1, 9000, 2250, 0x10000))))
        return atom("ftyp", "isom".toByteArray()) + atom("mdat", ByteArray(128) { it.toByte() }) +
            atom("moov", atom("mvhd", header(version1)), track, if (secondTrack) track else byteArrayOf())
    }
    private fun payload(bytes: ByteArray, type: String): Int = bytes.toString(Charsets.ISO_8859_1).indexOf(type) + 4

    @Test fun `retimes variable samples and edit list without changing video payload or atom offsets`() {
        for (longHeaders in listOf(false, true)) {
            val original = fixture(longHeaders)
            val file = Files.createTempFile("slow-clock", ".mp4").toFile()
            try {
                file.writeBytes(original)
                var sourceTiming: SlowMotionMp4Clock.SourceTiming? = null
                assertTrue(SlowMotionMp4Clock.retimeInPlace(file, 30) { sourceTiming = it }.isSuccess)
                assertEquals(90000L, sourceTiming?.timeScale)
                assertEquals(listOf(SlowMotionMp4Clock.TimingRun(0, 2, 750),
                    SlowMotionMp4Clock.TimingRun(2, 3, 751)), sourceTiming?.runs)
                val changed = file.readBytes(); val data = ByteBuffer.wrap(changed)
                assertEquals(original.size, changed.size)
                val movie = payload(original, "moov") - 8
                assertArrayEquals(original.copyOfRange(0, movie), changed.copyOfRange(0, movie))
                val stts = payload(changed, "stts")
                assertEquals(2, data.getInt(stts + 4))
                assertEquals(3000, data.getInt(stts + 12))
                assertEquals(3000, data.getInt(stts + 20))
                val mdhd = payload(changed, "mdhd")
                assertEquals(15000L, if (longHeaders) data.getLong(mdhd + 24) else data.getInt(mdhd + 16).toLong())
                val edit = payload(changed, "elst")
                assertEquals(15000, data.getInt(edit + 8))
                assertEquals(0, data.getInt(edit + 12))
            } finally { file.delete() }
        }
    }

    @Test fun `unsupported layouts fail before changing any bytes`() {
        for (bytes in listOf(fixture(secondTrack = true), fixture(ctts = true), fixture().copyOf(42))) {
            val file = Files.createTempFile("slow-clock-invalid", ".mp4").toFile()
            try {
                file.writeBytes(bytes)
                assertTrue(SlowMotionMp4Clock.retimeInPlace(file, 30).isFailure)
                assertArrayEquals(bytes, file.readBytes())
            } finally { file.delete() }
        }
    }
}
