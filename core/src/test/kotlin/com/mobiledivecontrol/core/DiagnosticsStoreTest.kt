package com.mobiledivecontrol.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsStoreTest {
    @Test
    fun `raw packet buffer stays bounded`() {
        val store = DiagnosticsStore()
        repeat(501) { index ->
            store.recordRawPacket(
                timestamp = Instant.parse("2026-05-26T12:00:00Z").plusSeconds(index.toLong()),
                characteristic = "0x1524",
                payload = byteArrayOf(0x50.toByte()),
            )
        }

        assertEquals(500, store.rawPacketCount())
    }

    @Test
    fun `export bundle contains expected files`() {
        val store = DiagnosticsStore()
        val now = Instant.parse("2026-05-26T12:00:00Z")
        store.recordDecodedButton(now, 0x50u.toUByte(), HousingButtonEvent.Ok, AppMode.CameraLive, 0)
        store.recordCommand(now, CameraCommand.CapturePhoto, AppMode.CameraLive)
        store.recordLatency(now, "button_packet", 12)

        val bundle = store.exportBundle(AppState())

        assertTrue(bundle.containsKey("event-log.jsonl"))
        assertTrue(bundle.containsKey("error-log.jsonl"))
        assertTrue(bundle.containsKey("latency-summary.json"))
    }
}
