package com.mobiledivecontrol.core

/**
 * Housing identity verification per vendor spec §5.2 (Device Information Service).
 *
 * Validates that a discovered housing meets the manufacturer's requirements:
 * - Advertising name matches "DIVE IT" (vendor spec §4.1, Table 3)
 * - Required BLE services are present (0x180F, 0x180A, 0x1523, 0x1623)
 * - Manufacturer name is "UMEING" (vendor spec §5.2, Table 6)
 * - Firmware version is in the supported compatibility list
 *
 * The firmware compatibility list is extensible to support future firmware releases.
 */
class HousingIdentityVerifier(
    /**
     * Extensible set of supported firmware versions.
     * The vendor spec §1 shows version A4.0. Future firmware releases
     * should be added here after validation.
     */
    private val supportedFirmwareVersions: Set<String> = setOf("A4.0"),
) {

    /**
     * Result of identity verification.
     */
    sealed interface VerificationResult {
        data object Verified : VerificationResult
        data class Rejected(val reason: String) : VerificationResult
    }

    /**
     * Validate the advertising name before connecting.
     * Per vendor spec §4.1 Table 3: Advertising_Name = "DIVE IT"
     */
    fun validateAdvertisingName(name: String): VerificationResult {
        return if (name.trim().equals(EXPECTED_ADVERTISING_NAME, ignoreCase = true)) {
            VerificationResult.Verified
        } else {
            VerificationResult.Rejected(
                "Advertising name mismatch: expected '$EXPECTED_ADVERTISING_NAME', got '$name'."
            )
        }
    }

    /**
     * Validate that all required BLE service UUIDs are discovered.
     * Per vendor spec §5.1–5.4:
     *   - 0x180F (Battery Service)
     *   - 0x180A (Device Information Service)
     *   - 0x1523 (Key Service — vendor private)
     *   - 0x1623 (Sensor/Control Service — vendor private)
     */
    fun validateDiscoveredServices(serviceUuids: Set<String>): VerificationResult {
        val normalizedDiscovered = serviceUuids.map { it.lowercase().trim() }.toSet()

        val missing = HousingService.entries.filter { service ->
            normalizedDiscovered.none { discovered ->
                discovered.contains(service.shortHex.lowercase())
            }
        }

        return if (missing.isEmpty()) {
            VerificationResult.Verified
        } else {
            VerificationResult.Rejected(
                "Missing required services: ${missing.joinToString { "${it.label} (0x${it.shortHex})" }}."
            )
        }
    }

    /**
     * Validate the manufacturer name read from Device Information Service.
     * Per vendor spec §5.2 Table 6: ManufacturerName = "UMEING"
     */
    fun validateManufacturer(manufacturerName: String): VerificationResult {
        return if (manufacturerName.trim().equals(EXPECTED_MANUFACTURER, ignoreCase = true)) {
            VerificationResult.Verified
        } else {
            VerificationResult.Rejected(
                "Manufacturer mismatch: expected '$EXPECTED_MANUFACTURER', got '$manufacturerName'."
            )
        }
    }

    /**
     * Validate firmware version against the supported compatibility list.
     * Per vendor spec version history: A1.0, A3.0, A4.0.
     * The app supports a configurable set of firmware versions.
     */
    fun validateFirmwareVersion(firmwareVersion: String): VerificationResult {
        val normalized = firmwareVersion.trim()
        return if (supportedFirmwareVersions.any { it.equals(normalized, ignoreCase = true) }) {
            VerificationResult.Verified
        } else {
            VerificationResult.Rejected(
                "Firmware '$normalized' not in supported list: $supportedFirmwareVersions."
            )
        }
    }

    /**
     * Full verification of a housing after service discovery and device info read.
     * Performs all checks in order, returning the first failure if any.
     */
    fun verifyHousing(
        advertisingName: String,
        discoveredServiceUuids: Set<String>,
        manufacturerName: String,
        firmwareVersion: String,
    ): VerificationResult {
        val checks = listOf(
            { validateAdvertisingName(advertisingName) },
            { validateDiscoveredServices(discoveredServiceUuids) },
            { validateManufacturer(manufacturerName) },
            { validateFirmwareVersion(firmwareVersion) },
        )

        for (check in checks) {
            val result = check()
            if (result is VerificationResult.Rejected) return result
        }

        return VerificationResult.Verified
    }

    /**
     * Check if a newly discovered device might be the same housing
     * under a different name (e.g. firmware update changed name).
     * Returns a warning message if names don't match but should still proceed.
     */
    fun checkMultiDeviceWarning(
        trustedIdentity: String?,
        currentSerialNumber: String?,
        newSerialNumber: String,
    ): String? {
        if (trustedIdentity == null || currentSerialNumber == null) return null

        return if (currentSerialNumber != newSerialNumber) {
            "Different housing detected (serial: $newSerialNumber). " +
                "Previously paired with serial: $currentSerialNumber."
        } else {
            null
        }
    }

    companion object {
        /** Per vendor spec §4.1 Table 3 */
        const val EXPECTED_ADVERTISING_NAME = "DIVE IT"

        /** Per vendor spec §5.2 Table 6 */
        const val EXPECTED_MANUFACTURER = "UMEING"
    }
}

/**
 * BLE service containers per vendor spec §5.1–5.4.
 *
 * The vendor private services use a 128-bit base UUID template:
 *   0x23, 0xD1, 0xBC, 0xEA, 0x5F, 0x78, 0x23, 0x15,
 *   0xDE, 0xEF, 0x12, 0x12, 0xxx, 0xxx, 0x00, 0x00, 0x00, 0x00
 *
 * where "0xxx, 0xxx" is the short UUID (e.g. 0x1523, 0x1623).
 */
enum class HousingService(
    val shortHex: String,
    val label: String,
    val isStandard: Boolean,
) {
    /** §5.1 Battery Service — SIG standard */
    Battery("180F", "Battery Service", true),

    /** §5.2 Device Information Service — SIG standard */
    DeviceInformation("180A", "Device Information Service", true),

    /** §5.3 Key Service — vendor private (buttons, flash trigger) */
    KeyInput("1523", "Key Service", false),

    /** §5.4 Sensor/Control Service — vendor private (motor, sensors, solenoid, cover, IR) */
    SensorControl("1623", "Sensor/Control Service", false);

    /** Full 128-bit UUID for this service */
    val fullUuid: String
        get() = if (isStandard) {
            "0000${shortHex}-0000-1000-8000-00805F9B34FB"
        } else {
            "23D1BCEA-5F78-2315-DEEF-1212${shortHex}0000"
        }

    companion object {
        /** All required service UUIDs for a valid housing */
        val requiredServiceUuids: Set<String> =
            entries.map { it.fullUuid.lowercase() }.toSet()

        /** Map from service to its characteristics */
        val serviceCharacteristics: Map<HousingService, List<HousingCharacteristic>> = mapOf(
            Battery to listOf(
                HousingCharacteristic.BatteryLevel,
            ),
            DeviceInformation to listOf(
                HousingCharacteristic.ManufacturerName,
                HousingCharacteristic.SoftwareRevision,
                HousingCharacteristic.HardwareRevision,
                HousingCharacteristic.FirmwareRevision,
                HousingCharacteristic.SerialNumber,
            ),
            KeyInput to listOf(
                HousingCharacteristic.ButtonEvents,
                HousingCharacteristic.FlashTrigger,
            ),
            SensorControl to listOf(
                HousingCharacteristic.VacuumMotor,
                HousingCharacteristic.WaterPressure,
                HousingCharacteristic.WaterTemperature,
                HousingCharacteristic.BarometricPressure,
                HousingCharacteristic.CoverState,
                HousingCharacteristic.SolenoidValve,
                HousingCharacteristic.IrFlashlight,
            ),
        )
    }
}
