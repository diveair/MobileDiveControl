package com.mobiledivecontrol.core

/**
 * Platform-agnostic BLE transport interface.
 *
 * This interface abstracts the GATT operations that the vendor spec requires
 * (see §3 Bluetooth Working Diagram: Power On → Initiating → Advertising → Connection).
 *
 * The JVM core defines the contract; the Android module implements it using
 * Android's BluetoothGatt API. A [SimulatedBleTransport] is provided for testing.
 *
 * All methods are suspend functions because real BLE operations are asynchronous.
 */
interface BleTransport {

    /**
     * Start scanning for BLE devices advertising as "DIVE IT".
     * Per vendor spec §4.1 Table 3:
     *   - Advertising_Type: ConnectableUndirected
     *   - Advertising_Name: DIVE IT
     *   - Advertising_Data: MAC address (6 bytes)
     *   - Advertising_Address_Type: StaticDeviceAddress
     *   - Advertising interval: 200ms
     *   - Advertising duration: 180s
     *
     * @return discovered device info, or null if none found
     */
    suspend fun scan(timeoutMs: Long = 10_000L): DiscoveredDevice?

    /**
     * Connect to a discovered housing device.
     * Per vendor spec §4.2 Table 4 (connection parameters):
     *   - min conn interval: 25ms (can be specified)
     *   - max conn interval: 125ms (can be specified)
     *   - Slave Latency: 0
     *   - Supervision Timeout: 4s
     *
     * iPhone constraints (§4.2):
     *   ① Max_conn_interval × (SlaveLatency + 1) <= 2s
     *   ② Min_conn_interval >= 20ms
     *   ③ Min_conn_interval + 20ms <= max_conn_interval
     *   ④ SlaveLatency >= 4  [NOTE: spec says >=4 but current is 0; may be a typo]
     *   ⑤ SupervisionTimeout <= 6s
     *   ⑥ max_conn_interval × (SlaveLatency + 1) × 3 < SupervisionTimeout
     */
    suspend fun connect(device: DiscoveredDevice): Boolean

    /**
     * Discover GATT services after connection.
     * Per vendor spec §5.1–5.4, must discover all 4 services:
     *   - 0x180F (Battery)
     *   - 0x180A (Device Information)
     *   - 0x1523 (Key — vendor private)
     *   - 0x1623 (Sensor/Control — vendor private)
     *
     * @return set of discovered service UUIDs
     */
    suspend fun discoverServices(): Set<String>

    /**
     * Read a characteristic value.
     * Used for Device Information Service characteristics (§5.2 Table 6):
     *   - 0x2A29 ManufacturerName (expect "UMEING")
     *   - 0x2A28 SoftwareRevision
     *   - 0x2A27 HardwareRevision
     *   - 0x2A26 FirmwareRevision
     *   - 0x2A25 SerialNumber
     */
    suspend fun readCharacteristic(characteristic: HousingCharacteristic): ByteArray?

    /**
     * Subscribe to notifications on a characteristic.
     * Used for (per vendor spec):
     *   - 0x2A19 Battery Level (§5.1): Read + Notification, 1 byte, 0–100%
     *   - 0x1524 Button Events (§5.3): Read + Notification, 1 byte
     *   - 0x1625 Water Pressure (§5.4): Read + Notification, 4 bytes LE
     *   - 0x1626 Water Temperature (§5.4): Read + Notification, 4 bytes LE
     *   - 0x1627 Barometric Pressure (§5.4): Read + Notification, 4 bytes LE
     *   - 0x1628 Cover State (§5.4): Read + Notification, 1 byte
     */
    suspend fun subscribe(characteristic: HousingCharacteristic): Boolean

    /**
     * Write a value to a characteristic.
     * Used for (per vendor spec):
     *   - 0x1525 Trigger Flash LED (§5.3): Write, 1 byte (0x01 = trigger)
     *   - 0x1624 Motor Control (§5.4): Write, 1 byte (0x01 = start, 0x00 = stop)
     *   - 0x1629 Solenoid Valve (§5.4): Write, 1 byte (0x01 = open, 0x00 = stop)
     *   - 0x162A IR Remote Control (§5.4): Write, 1 byte (0x01–0x06)
     */
    suspend fun writeCharacteristic(characteristic: HousingCharacteristic, value: ByteArray): Boolean

    /**
     * Disconnect from the housing.
     */
    suspend fun disconnect()

    /**
     * Register a callback for incoming characteristic notifications.
     */
    fun setNotificationListener(listener: NotificationListener?)
}

/**
 * Callback for BLE notifications received from the housing.
 */
fun interface NotificationListener {
    fun onNotification(characteristic: HousingCharacteristic, value: ByteArray)
}

/**
 * Discovered BLE device from scanning.
 */
data class DiscoveredDevice(
    /** The advertising name (expected: "DIVE IT") */
    val name: String,
    /** MAC address as 6 bytes */
    val macAddress: String,
    /** RSSI signal strength */
    val rssi: Int = 0,
)

/**
 * BLE connection parameters per vendor spec §4.2 Table 4.
 */
object BleConnectionParams {
    /** Current min connection interval per vendor spec */
    const val MIN_CONN_INTERVAL_MS = 25
    /** Current max connection interval per vendor spec */
    const val MAX_CONN_INTERVAL_MS = 125
    /** Current slave latency per vendor spec */
    const val SLAVE_LATENCY = 0
    /** Current supervision timeout per vendor spec */
    const val SUPERVISION_TIMEOUT_MS = 4000
}
