package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.SensorPacketSource
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PressurePacketRecorderTest {
    @TempDir lateinit var directory: File
    private fun packet(sensor: String, raw: Int, source: SensorPacketSource = SensorPacketSource.Notification) =
        HousingLinkEvent.Notification(sensor, ByteArray(4) { (raw ushr (it * 8)).toByte() }, source, 1234, 5678)

    @Test
    fun `captures only while diagnostics is active and preserves both units and original bytes`() = runTest {
        val recorder = PressurePacketRecorder(directory, backgroundScope)
        recorder.record(packet("1625", 9999))
        recorder.setEnabled(true, 101.15)
        recorder.setEnabled(true, 101.15) // ordinary state updates must not restart capture
        val water = packet("1625", 10606, SensorPacketSource.Read)
        recorder.record(water)
        water.payload[0] = 0 // caller-owned array reuse cannot corrupt the saved evidence
        recorder.record(packet("1627", 78496))
        recorder.record(HousingLinkEvent.Notification("1625", byteArrayOf(1, 2)))
        recorder.setEnabled(false)
        recorder.record(packet("1627", 90000))
        recorder.close()
        val lines = File(directory, "pressure-current.jsonl").readLines()
        assertEquals(1, lines.count { it.contains("\"type\":\"capture_started\"") })
        assertEquals(1, lines.count { it.contains("\"type\":\"capture_stopped\"") })
        val packets = lines.filter { it.contains("\"type\":\"sensor_packet\"") }
        assertEquals(3, packets.size)
        assertTrue(packets[0].contains("\"rawHex\":\"6E 29 00 00\""))
        assertTrue(packets[0].contains("\"bytes\":[110, 41, 0, 0]"))
        assertTrue(packets[0].contains("\"pressureKpa\":106.06"))
        assertTrue(packets[0].contains("\"source\":\"Read\""))
        assertTrue(packets[0].contains("\"epochMs\":1234"))
        assertTrue(packets[0].contains("\"monotonicMs\":5678"))
        assertTrue(packets[1].contains("\"pressureKpa\":78.496"))
        assertTrue(packets[2].contains("\"rawHex\":\"01 02\""))
        assertTrue(packets[2].contains("\"valid\":false"))
        assertTrue(packets[2].contains("\"pressureKpa\":null"))
    }

    @Test
    fun `bounded rotation keeps the newest packets across two files`() = runTest {
        val recorder = PressurePacketRecorder(directory, backgroundScope, maxFileBytes = 1600)
        recorder.setEnabled(true)
        repeat(30) { recorder.record(packet("1625", 10115 + it)) }
        recorder.setEnabled(false)
        recorder.close()
        val files = directory.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("pressure-current.jsonl", "pressure-previous.jsonl"), files.map { it.name })
        assertTrue(files.all { it.length() <= 1600 })
        assertTrue(files.joinToString { it.readText() }.contains("\"rawUnsignedLE32\":10144"))
        assertTrue(File(directory, "pressure-current.jsonl").readLines().last().contains("capture_stopped"))
    }

    @Test
    fun `writer backpressure never blocks reception and lost samples are reported`() = runTest {
        val recorder = PressurePacketRecorder(directory, backgroundScope)
        recorder.setEnabled(true)
        repeat(600) { recorder.record(packet("1625", 10115)) }
        recorder.close()
        val lines = File(directory, "pressure-current.jsonl").readLines()
        val saved = lines.count { it.contains("sensor_packet") }
        assertEquals(255, saved) // one queue slot held the start marker
        assertTrue(lines.any { it.contains("dropped_samples") && it.contains("\"count\":345") })
    }

    @Test
    fun `logs the exact calculated and displayed depth throughout a four metre descent and ascent`() = runTest {
        val recorder = PressurePacketRecorder(directory, backgroundScope)
        recorder.setEnabled(true, 101.325)
        for (raw in listOf(10115, 10623, 14057, 10623, 10115)) recorder.record(packet("1625", raw))
        recorder.record(packet("1627", 84570))
        recorder.record(HousingLinkEvent.Notification("1625", byteArrayOf(1, 2)))
        recorder.close()
        val rows = File(directory, "pressure-current.jsonl").readLines().filter { it.contains("sensor_packet") }
        val expected = listOf("0.0 m", "0.5 m", "4.0 m", "0.5 m", "0.0 m")
        expected.forEachIndexed { index, display ->
            assertTrue(rows[index].contains("\"depthDisplay\":\"$display\""))
            assertTrue(rows[index].contains("\"depthReferenceKpa\":101.325"))
        }
        assertTrue(rows[2].contains("\"rawPressureLE24\":14057"))
        assertTrue(rows[2].contains("\"calculatedDepthMeters\":4.000509"))
        assertTrue(rows[5].contains("\"calculatedDepthMeters\":null"))
        assertTrue(rows[6].contains("\"depthDisplay\":null"))
    }

    @Test
    fun `storage failure is contained and reported without throwing into Bluetooth`() = runTest {
        val impossibleDirectory = File(directory, "ordinary-file").apply { writeText("existing") }
        val failures = mutableListOf<Exception>()
        val recorder = PressurePacketRecorder(impossibleDirectory, backgroundScope, onFailure = { failures += it })
        recorder.setEnabled(true)
        recorder.record(packet("1625", 10115))
        recorder.close()
        recorder.record(packet("1627", 78496))
        assertEquals(1, failures.size)
        assertEquals("existing", impossibleDirectory.readText())
    }
}
