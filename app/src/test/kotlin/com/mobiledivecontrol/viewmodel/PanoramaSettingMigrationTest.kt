package com.mobiledivecontrol.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PanoramaSettingMigrationTest {
    @Test
    fun `legacy log wins when collapsing the two panorama toggles`() {
        val migrated = migrateLegacyPanoramaDynamicRange(
            mapOf(
                "panorama.hdr" to "On",
                "panorama.log" to "On",
                "panorama.grid" to "Phi Grid",
            ),
        )

        assertEquals("LOG", migrated["panorama.hdr_log"])
        assertEquals("Phi Grid", migrated["panorama.grid"])
        assertFalse("panorama.hdr" in migrated)
        assertFalse("panorama.log" in migrated)
    }

    @Test
    fun `legacy hdr and off states collapse without changing unrelated values`() {
        assertEquals(
            "HDR",
            migrateLegacyPanoramaDynamicRange(mapOf("panorama.hdr" to "On"))["panorama.hdr_log"],
        )
        assertEquals(
            "Off",
            migrateLegacyPanoramaDynamicRange(
                mapOf("panorama.hdr" to "Off", "panorama.log" to "Off"),
            )["panorama.hdr_log"],
        )
    }

    @Test
    fun `new exclusive value is already idempotent`() {
        val current = mapOf("panorama.hdr_log" to "HDR")
        assertEquals(current, migrateLegacyPanoramaDynamicRange(current))
    }
}
