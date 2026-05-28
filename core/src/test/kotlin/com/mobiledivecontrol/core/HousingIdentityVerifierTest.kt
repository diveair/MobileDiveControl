package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HousingIdentityVerifierTest {
    private val verifier = HousingIdentityVerifier()

    // --- Advertising name (§4.1 Table 3) ---

    @Test
    fun `accepts correct advertising name DIVE IT`() {
        val result = verifier.validateAdvertisingName("DIVE IT")
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `accepts advertising name case-insensitively`() {
        val result = verifier.validateAdvertisingName("dive it")
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `rejects wrong advertising name`() {
        val result = verifier.validateAdvertisingName("NOT-A-HOUSING")
        assertTrue(result is HousingIdentityVerifier.VerificationResult.Rejected)
        assertTrue(result.reason.contains("DIVE IT"))
    }

    // --- Service UUID validation (§5.1–5.4) ---

    @Test
    fun `accepts all 4 required service UUIDs`() {
        val discovered = HousingService.entries.map { it.fullUuid }.toSet()
        val result = verifier.validateDiscoveredServices(discovered)
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `rejects when key service is missing`() {
        val discovered = setOf(
            HousingService.Battery.fullUuid,
            HousingService.DeviceInformation.fullUuid,
            // Missing: KeyInput (0x1523) and SensorControl (0x1623)
        )
        val result = verifier.validateDiscoveredServices(discovered)
        assertTrue(result is HousingIdentityVerifier.VerificationResult.Rejected)
        assertTrue(result.reason.contains("1523"))
    }

    // --- Manufacturer validation (§5.2 Table 6) ---

    @Test
    fun `accepts UMEING manufacturer`() {
        val result = verifier.validateManufacturer("UMEING")
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `rejects wrong manufacturer`() {
        val result = verifier.validateManufacturer("OtherBrand")
        assertTrue(result is HousingIdentityVerifier.VerificationResult.Rejected)
        assertTrue(result.reason.contains("UMEING"))
    }

    // --- Firmware version (extensible list) ---

    @Test
    fun `accepts A4 0 firmware by default`() {
        val result = verifier.validateFirmwareVersion("A4.0")
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `rejects unknown firmware version`() {
        val result = verifier.validateFirmwareVersion("A2.0")
        assertTrue(result is HousingIdentityVerifier.VerificationResult.Rejected)
    }

    @Test
    fun `extensible firmware list accepts multiple versions`() {
        val multiVerifier = HousingIdentityVerifier(
            supportedFirmwareVersions = setOf("A3.0", "A4.0", "A5.0"),
        )
        assertEquals(
            HousingIdentityVerifier.VerificationResult.Verified,
            multiVerifier.validateFirmwareVersion("A3.0"),
        )
        assertEquals(
            HousingIdentityVerifier.VerificationResult.Verified,
            multiVerifier.validateFirmwareVersion("A5.0"),
        )
    }

    // --- Full verification flow ---

    @Test
    fun `full verification passes with valid housing`() {
        val result = verifier.verifyHousing(
            advertisingName = "DIVE IT",
            discoveredServiceUuids = HousingService.entries.map { it.fullUuid }.toSet(),
            manufacturerName = "UMEING",
            firmwareVersion = "A4.0",
        )
        assertEquals(HousingIdentityVerifier.VerificationResult.Verified, result)
    }

    @Test
    fun `full verification fails on first bad check`() {
        val result = verifier.verifyHousing(
            advertisingName = "WRONG",
            discoveredServiceUuids = HousingService.entries.map { it.fullUuid }.toSet(),
            manufacturerName = "UMEING",
            firmwareVersion = "A4.0",
        )
        assertTrue(result is HousingIdentityVerifier.VerificationResult.Rejected)
        // Should fail on advertising name, not firmware
        assertTrue(result.reason.contains("Advertising"))
    }

    // --- Multi-device warning ---

    @Test
    fun `warns when different serial number detected`() {
        val warning = verifier.checkMultiDeviceWarning(
            trustedIdentity = "AA:BB:CC:DD:EE:FF",
            currentSerialNumber = "SN-001",
            newSerialNumber = "SN-002",
        )
        assertNotNull(warning)
        assertTrue(warning.contains("SN-002"))
    }

    @Test
    fun `no warning for same serial number`() {
        val warning = verifier.checkMultiDeviceWarning(
            trustedIdentity = "AA:BB:CC:DD:EE:FF",
            currentSerialNumber = "SN-001",
            newSerialNumber = "SN-001",
        )
        assertNull(warning)
    }
}
