package com.mobiledivecontrol.core

/**
 * Simulated BLE transport for testing the full connection lifecycle
 * without Android dependencies.
 *
 * Mimics a WFH07 housing with vendor spec-conformant responses:
 * - Advertising name: "DIVE IT" (§4.1)
 * - Services: 0x180F, 0x180A, 0x1523, 0x1623 (§5.1–5.4)
 * - Manufacturer: "UMEING" (§5.2)
 * - Firmware: "A4.0" (version history)
 */
class SimulatedBleTransport(
    private val deviceName: String = "DIVE IT",
    private val manufacturerName: String = "UMEING",
    private val firmwareVersion: String = "A4.0",
    private val hardwareVersion: String = "1.0",
    private val softwareVersion: String = "1.0",
    private val serialNumber: String = "SIM-001",
    private val modelNumber: String = "WFH07",
    private val shouldFailConnect: Boolean = false,
    private val shouldFailSubscribe: Set<HousingCharacteristic> = emptySet(),
) : BleTransport {

    private var listener: NotificationListener? = null
    private var connected = false

    override suspend fun scan(timeoutMs: Long): DiscoveredDevice? {
        return DiscoveredDevice(
            name = deviceName,
            macAddress = "AA:BB:CC:DD:EE:FF",
            rssi = -50,
        )
    }

    override suspend fun connect(device: DiscoveredDevice): Boolean {
        if (shouldFailConnect) return false
        connected = true
        return true
    }

    override suspend fun discoverServices(): Set<String> {
        return HousingService.entries.map { it.fullUuid }.toSet()
    }

    override suspend fun readCharacteristic(characteristic: HousingCharacteristic): ByteArray? {
        return when (characteristic) {
            HousingCharacteristic.ManufacturerName -> manufacturerName.toByteArray()
            HousingCharacteristic.FirmwareRevision -> firmwareVersion.toByteArray()
            HousingCharacteristic.HardwareRevision -> hardwareVersion.toByteArray()
            HousingCharacteristic.SoftwareRevision -> softwareVersion.toByteArray()
            HousingCharacteristic.SerialNumber -> serialNumber.toByteArray()
            HousingCharacteristic.ModelNumber -> modelNumber.toByteArray()
            HousingCharacteristic.BatteryLevel -> byteArrayOf(0x64) // 100%
            else -> null
        }
    }

    override suspend fun subscribe(characteristic: HousingCharacteristic): Boolean {
        return characteristic !in shouldFailSubscribe
    }

    override suspend fun writeCharacteristic(
        characteristic: HousingCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (!connected) return false
        return true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override fun setNotificationListener(listener: NotificationListener?) {
        this.listener = listener
    }

    // --- Test helpers: simulate incoming notifications ---

    /**
     * Simulate a button press notification.
     * Per vendor spec §5.3 Table 7.
     */
    fun simulateButtonPress(buttonCode: UByte) {
        listener?.onNotification(HousingCharacteristic.ButtonEvents, byteArrayOf(buttonCode.toByte()))
    }

    /**
     * Simulate a battery level notification.
     * Per vendor spec §5.1 Table 5: 1 byte, 0–100.
     */
    fun simulateBatteryLevel(percent: Int) {
        listener?.onNotification(HousingCharacteristic.BatteryLevel, byteArrayOf(percent.toByte()))
    }

    /**
     * Simulate a cover state notification.
     * Per vendor spec §5.4: 0x00 = open, 0x01 = closed.
     */
    fun simulateCoverState(open: Boolean) {
        val value = if (open) 0x00.toByte() else 0x01.toByte()
        listener?.onNotification(HousingCharacteristic.CoverState, byteArrayOf(value))
    }

    /**
     * Simulate a barometric pressure notification.
     * Per vendor spec §5.4: 4 bytes Little-Endian, 32-bit unsigned integer in 1 Pa.
     * Example from spec: 0x7f8a0100 = 100991 Pa = 0x18A7F
     */
    fun simulateBarometricPressure(pascals: Long) {
        listener?.onNotification(
            HousingCharacteristic.BarometricPressure,
            encodeLittleEndianU32(pascals),
        )
    }

    /**
     * Simulate a water pressure notification.
     * Per vendor spec §5.4: 4 bytes Little-Endian, 32-bit unsigned integer in 0.1 mba.
     * Example from spec: 0x83270000 = 10115 (0x2783) = 1011.5 mba
     */
    fun simulateWaterPressure(tenthMba: Long) {
        listener?.onNotification(
            HousingCharacteristic.WaterPressure,
            encodeLittleEndianU32(tenthMba),
        )
    }

    /**
     * Simulate a water temperature notification.
     * Per vendor spec §5.4: 4 bytes Little-Endian, 32-bit unsigned integer in 0.01°C.
     * Example from spec: 0x580b0000 = 2904 (0xB58) = 29.04°C
     */
    fun simulateWaterTemperature(hundredthDegC: Long) {
        listener?.onNotification(
            HousingCharacteristic.WaterTemperature,
            encodeLittleEndianU32(hundredthDegC),
        )
    }

    companion object {
        fun encodeLittleEndianU32(value: Long): ByteArray {
            return byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            )
        }
    }
}
