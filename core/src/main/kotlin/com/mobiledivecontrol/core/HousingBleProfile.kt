package com.mobiledivecontrol.core

enum class HousingCharacteristic(
    val shortCode: UShort,
    val label: String,
    val fullUuid: String,
) {
    BatteryLevel(0x2A19u, "Battery Level", standardUuid(0x2A19u)),
    ModelNumber(0x2A24u, "Model Number", standardUuid(0x2A24u)),
    SerialNumber(0x2A25u, "Serial Number", standardUuid(0x2A25u)),
    FirmwareRevision(0x2A26u, "Firmware Revision", standardUuid(0x2A26u)),
    HardwareRevision(0x2A27u, "Hardware Revision", standardUuid(0x2A27u)),
    SoftwareRevision(0x2A28u, "Software Revision", standardUuid(0x2A28u)),
    ManufacturerName(0x2A29u, "Manufacturer Name", standardUuid(0x2A29u)),
    ButtonEvents(0x1524u, "Button Events", vendorUuid(0x1524u)),
    FlashTrigger(0x1525u, "Flash Trigger", vendorUuid(0x1525u)),
    VacuumMotor(0x1624u, "Vacuum Motor", vendorUuid(0x1624u)),
    WaterPressure(0x1625u, "Water Pressure", vendorUuid(0x1625u)),
    WaterTemperature(0x1626u, "Water Temperature", vendorUuid(0x1626u)),
    BarometricPressure(0x1627u, "Barometric Pressure", vendorUuid(0x1627u)),
    CoverState(0x1628u, "Air Extraction Cover", vendorUuid(0x1628u)),
    SolenoidValve(0x1629u, "Solenoid Valve", vendorUuid(0x1629u)),
    IrFlashlight(0x162Au, "IR Flashlight", vendorUuid(0x162Au));

    val shortHex: String
        get() = shortCode.toHexString()

    companion object {
        private val byFullUuid = entries.associateBy { it.fullUuid.lowercase() }
        private val byShortCode = entries.associateBy { it.shortCode.toInt() }

        fun from(characteristic: String): HousingCharacteristic? {
            val normalized = characteristic.trim()
            if (normalized.isEmpty()) {
                return null
            }

            byFullUuid[normalized.lowercase()]?.let { return it }

            val shortValue = parseShortCode(normalized) ?: return null
            return byShortCode[shortValue]
        }

        private fun parseShortCode(value: String): Int? {
            val normalized = value.removePrefix("0x").removePrefix("0X")
            return if (normalized.length == 4 && normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                normalized.toInt(16)
            } else {
                null
            }
        }
    }
}

object HousingBleProfile {
    const val advertisingName: String = "DIVE IT"

    val subscriptionOrder: List<HousingCharacteristic> = listOf(
        HousingCharacteristic.ButtonEvents,
        HousingCharacteristic.BatteryLevel,
        HousingCharacteristic.CoverState,
        HousingCharacteristic.BarometricPressure,
        HousingCharacteristic.WaterPressure,
        HousingCharacteristic.WaterTemperature,
    )

    val deviceInfoReadOrder: List<HousingCharacteristic> = listOf(
        HousingCharacteristic.ManufacturerName,
        HousingCharacteristic.ModelNumber,
        HousingCharacteristic.SerialNumber,
        HousingCharacteristic.HardwareRevision,
        HousingCharacteristic.FirmwareRevision,
        HousingCharacteristic.SoftwareRevision,
    )
}

private fun standardUuid(shortCode: UShort): String {
    val hex = shortCode.toInt().toString(16).uppercase().padStart(4, '0')
    return "0000${hex}-0000-1000-8000-00805F9B34FB"
}

private fun vendorUuid(shortCode: UShort): String {
    val hex = shortCode.toInt().toString(16).uppercase().padStart(4, '0')
    return "23D1BCEA-5F78-2315-DEEF-1212${hex}0000"
}
