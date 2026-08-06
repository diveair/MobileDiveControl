package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.HousingCharacteristic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The vendor base UUID is malformed, so which 128-bit values the housing actually exposes
 * is a prediction until someone connects real hardware. These tests pin the behaviour for
 * every base the housing plausibly uses, so that prediction being wrong costs nothing.
 */
class HousingUuidResolverTest {

    // --- Bluetooth SIG base ---------------------------------------------------------

    @Test
    fun `resolves standard characteristics on the SIG base`() {
        assertEquals(
            HousingCharacteristic.BatteryLevel,
            HousingUuidResolver.resolveCharacteristic("00002A19-0000-1000-8000-00805F9B34FB"),
        )
        assertEquals(
            HousingCharacteristic.ManufacturerName,
            HousingUuidResolver.resolveCharacteristic("00002A29-0000-1000-8000-00805F9B34FB"),
        )
        assertEquals(
            HousingCharacteristic.SerialNumber,
            HousingUuidResolver.resolveCharacteristic("00002A25-0000-1000-8000-00805F9B34FB"),
        )
    }

    @Test
    fun `reports the SIG base as the match source`() {
        val match = HousingUuidResolver.matchCharacteristic("00002A19-0000-1000-8000-00805F9B34FB")
        assertEquals(
            HousingUuidResolver.Match.Unique(
                HousingCharacteristic.BatteryLevel,
                HousingUuidResolver.MatchSource.SigBase,
            ),
            match,
        )
    }

    @Test
    fun `SIG base with an unknown short code does not match`() {
        assertNull(HousingUuidResolver.resolveCharacteristic("00002A00-0000-1000-8000-00805F9B34FB"))
    }

    // --- Nordic LBS base, the predicted real form -----------------------------------

    @Test
    fun `resolves vendor characteristics on the Nordic LED Button Service base`() {
        assertEquals(
            HousingCharacteristic.ButtonEvents,
            HousingUuidResolver.resolveCharacteristic("00001524-1212-EFDE-1523-785FEABCD123"),
        )
        assertEquals(
            HousingCharacteristic.FlashTrigger,
            HousingUuidResolver.resolveCharacteristic("00001525-1212-EFDE-1523-785FEABCD123"),
        )
        assertEquals(
            HousingCharacteristic.WaterPressure,
            HousingUuidResolver.resolveCharacteristic("00001625-1212-EFDE-1523-785FEABCD123"),
        )
        assertEquals(
            HousingCharacteristic.IrFlashlight,
            HousingUuidResolver.resolveCharacteristic("0000162A-1212-EFDE-1523-785FEABCD123"),
        )
        assertEquals(
            HousingCharacteristic.VacuumMotor,
            HousingUuidResolver.resolveCharacteristic("00001624-1212-EFDE-1523-785FEABCD123"),
        )
    }

    /**
     * The Nordic base embeds `1523` — a real service short code — in every UUID on the
     * device. Positional resolution has to win, or every service resolves to the key service.
     */
    @Test
    fun `Nordic base service resolution uses the leading short code, not the embedded 1523`() {
        assertEquals(0x1523, HousingUuidResolver.resolveServiceShortCode("00001523-1212-EFDE-1523-785FEABCD123"))
        assertEquals(0x1623, HousingUuidResolver.resolveServiceShortCode("00001623-1212-EFDE-1523-785FEABCD123"))
        assertEquals(0x1524, HousingUuidResolver.resolveServiceShortCode("00001524-1212-EFDE-1523-785FEABCD123"))
    }

    @Test
    fun `Nordic base service UUID is not mistaken for a characteristic`() {
        assertNull(HousingUuidResolver.resolveCharacteristic("00001523-1212-EFDE-1523-785FEABCD123"))
        assertNull(HousingUuidResolver.resolveCharacteristic("00001623-1212-EFDE-1523-785FEABCD123"))
    }

    // --- The base currently guessed by core -----------------------------------------

    @Test
    fun `resolves the vendor base guessed by HousingBleProfile`() {
        assertEquals(
            HousingCharacteristic.ButtonEvents,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-5F78-2315-DEEF-121215240000"),
        )
        assertEquals(
            HousingCharacteristic.CoverState,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-5F78-2315-DEEF-121216280000"),
        )
    }

    @Test
    fun `matches every vendor characteristic on the guessed base`() {
        val vendorCharacteristics = HousingCharacteristic.entries.filter { it.shortCode.toInt() < 0x2000 }
        for (characteristic in vendorCharacteristics) {
            val code = characteristic.shortCode.toInt().toString(16).padStart(4, '0')
            val uuid = "23D1BCEA-5F78-2315-DEEF-1212${code}0000"
            assertEquals(characteristic, HousingUuidResolver.resolveCharacteristic(uuid), "for $uuid")
        }
    }

    @Test
    fun `matches every characteristic on the predicted Nordic base`() {
        for (characteristic in HousingCharacteristic.entries) {
            val code = characteristic.shortCode.toInt().toString(16).padStart(4, '0')
            val uuid = "0000$code-1212-EFDE-1523-785FEABCD123"
            assertEquals(characteristic, HousingUuidResolver.resolveCharacteristic(uuid), "for $uuid")
        }
    }

    @Test
    fun `matches every characteristic on true 16-bit UUIDs`() {
        for (characteristic in HousingCharacteristic.entries) {
            val code = characteristic.shortCode.toInt().toString(16).padStart(4, '0')
            assertEquals(characteristic, HousingUuidResolver.resolveCharacteristic(code), "for $code")
        }
    }

    @Test
    fun `reports an embedded short code as the match source on a vendor base`() {
        val match = HousingUuidResolver.matchCharacteristic("23D1BCEA-5F78-2315-DEEF-121215240000")
        assertEquals(
            HousingUuidResolver.Match.Unique(
                HousingCharacteristic.ButtonEvents,
                HousingUuidResolver.MatchSource.EmbeddedShortCode,
            ),
            match,
        )
    }

    // --- A third, entirely different base -------------------------------------------

    @Test
    fun `resolves a base that carries the short code in the second group`() {
        assertEquals(
            HousingCharacteristic.ButtonEvents,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-1524-2315-DEEF-121212120000"),
        )
        assertEquals(
            HousingCharacteristic.BarometricPressure,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-1627-2315-DEEF-121212120000"),
        )
    }

    @Test
    fun `resolves a base that carries the short code in the trailing group`() {
        assertEquals(
            HousingCharacteristic.WaterTemperature,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-5F78-2315-DEEF-121212121626"),
        )
    }

    // --- True 16-bit and 32-bit UUIDs -----------------------------------------------

    @Test
    fun `resolves bare 16-bit UUIDs for vendor short codes`() {
        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic("1524"))
        assertEquals(HousingCharacteristic.SolenoidValve, HousingUuidResolver.resolveCharacteristic("1629"))
        assertEquals(HousingCharacteristic.BatteryLevel, HousingUuidResolver.resolveCharacteristic("0x2A19"))
    }

    @Test
    fun `resolves 32-bit UUIDs`() {
        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic("00001524"))
        assertEquals(0x1523, HousingUuidResolver.resolveServiceShortCode("00001523"))
    }

    @Test
    fun `resolves vendor short codes promoted onto the SIG base`() {
        assertEquals(
            HousingCharacteristic.ButtonEvents,
            HousingUuidResolver.resolveCharacteristic("00001524-0000-1000-8000-00805F9B34FB"),
        )
        assertEquals(
            HousingCharacteristic.WaterTemperature,
            HousingUuidResolver.resolveCharacteristic("00001626-0000-1000-8000-00805F9B34FB"),
        )
    }

    // --- Formatting tolerance -------------------------------------------------------

    @Test
    fun `is case insensitive`() {
        assertEquals(
            HousingCharacteristic.IrFlashlight,
            HousingUuidResolver.resolveCharacteristic("0000162a-1212-efde-1523-785feabcd123"),
        )
        assertEquals(
            HousingCharacteristic.IrFlashlight,
            HousingUuidResolver.resolveCharacteristic("0000162A-1212-EFDE-1523-785FEABCD123"),
        )
    }

    @Test
    fun `accepts UUIDs with and without dashes and with surrounding noise`() {
        val dashed = "00001524-1212-EFDE-1523-785FEABCD123"
        val bare = "000015241212EFDE1523785FEABCD123"

        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic(dashed))
        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic(bare))
        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic("  $dashed  "))
        assertEquals(HousingCharacteristic.ButtonEvents, HousingUuidResolver.resolveCharacteristic("{$dashed}"))
    }

    // --- Refusing to guess ----------------------------------------------------------

    @Test
    fun `unrelated UUIDs do not match`() {
        assertNull(HousingUuidResolver.resolveCharacteristic("d0611e78-bbb4-4591-a5f8-487910ae4366"))
        assertNull(HousingUuidResolver.resolveCharacteristic("8667556c-9a37-4c91-84ed-54ee27d90049"))
        assertNull(HousingUuidResolver.resolveServiceShortCode("d0611e78-bbb4-4591-a5f8-487910ae4366"))
    }

    @Test
    fun `non-hex input does not match`() {
        assertNull(HousingUuidResolver.resolveCharacteristic("not-a-uuid"))
        assertNull(HousingUuidResolver.resolveCharacteristic(""))
        assertNull(HousingUuidResolver.resolveServiceShortCode("DIVE IT"))
    }

    @Test
    fun `two different short codes in one UUID are ambiguous, not a guess`() {
        val uuid = "23D1BCEA-1524-2A19-DEEF-121212120000"

        assertNull(HousingUuidResolver.resolveCharacteristic(uuid))

        val match = HousingUuidResolver.matchCharacteristic(uuid)
        assertTrue(match is HousingUuidResolver.Match.Ambiguous, "expected ambiguity, got $match")
        assertEquals(
            setOf(HousingCharacteristic.ButtonEvents, HousingCharacteristic.BatteryLevel),
            (match as HousingUuidResolver.Match.Ambiguous).candidates.toSet(),
        )
    }

    @Test
    fun `two different service codes in one UUID are ambiguous`() {
        assertNull(HousingUuidResolver.resolveServiceShortCode("23D1BCEA-1523-1623-DEEF-121212120000"))
    }

    @Test
    fun `the same short code repeated is not ambiguous`() {
        assertEquals(
            HousingCharacteristic.ButtonEvents,
            HousingUuidResolver.resolveCharacteristic("23D1BCEA-1524-2315-DEEF-121215240000"),
        )
    }

    // --- Diagnostics ----------------------------------------------------------------

    @Test
    fun `describe explains matches, ambiguity and misses`() {
        assertTrue(
            HousingUuidResolver.describe("00001524-1212-EFDE-1523-785FEABCD123").contains("Button Events"),
        )
        assertTrue(
            HousingUuidResolver.describe("23D1BCEA-1524-2A19-DEEF-121212120000").startsWith("ambiguous"),
        )
        assertEquals("unmapped", HousingUuidResolver.describe("d0611e78-bbb4-4591-a5f8-487910ae4366"))
    }
}
