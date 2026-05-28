package com.mobiledivecontrol.core

/**
 * Orchestrates the full BLE connection lifecycle per vendor spec §3 working diagram:
 *   Scan → Connect → Discover Services → Validate Identity → Subscribe → Ready
 *
 * This is the glue between:
 *   - [BleTransport] (platform-agnostic GATT operations)
 *   - [BleConnectionMachine] (connection state machine)
 *   - [HousingIdentityVerifier] (vendor spec identity checks)
 *   - [ProtocolParser] (wire-format decoding)
 *   - [HousingCommandEncoder] (wire-format encoding)
 *
 * The Android module calls this orchestrator's methods; all BLE I/O is delegated
 * to the injected [BleTransport] implementation.
 */
class BleTransportOrchestrator(
    private val transport: BleTransport,
    private val connectionMachine: BleConnectionMachine = BleConnectionMachine(),
    private val identityVerifier: HousingIdentityVerifier = HousingIdentityVerifier(),
    private val protocolParser: ProtocolParser = ProtocolParser(),
    private val commandEncoder: HousingCommandEncoder = HousingCommandEncoder(),
    private val onStateChanged: (BleConnectionState) -> Unit = {},
    private val onNotificationDecoded: (DecodedNotification) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private var currentState = BleConnectionState.Idle
    private var connectedDevice: DiscoveredDevice? = null

    val state: BleConnectionState get() = currentState

    init {
        transport.setNotificationListener { characteristic, value ->
            handleNotification(characteristic, value)
        }
    }

    /**
     * Run the full connection sequence per vendor spec §3:
     * 1. Scan for "DIVE IT" devices (§4.1)
     * 2. Validate advertising name
     * 3. Connect with spec parameters (§4.2)
     * 4. Discover all 4 required services (§5.1–5.4)
     * 5. Validate discovered services
     * 6. Read device information (§5.2)
     * 7. Validate manufacturer + firmware
     * 8. Subscribe to notification characteristics
     * 9. Transition to Ready
     */
    suspend fun connectToHousing(): ConnectionResult {
        // Step 1: Scan
        transitionTo(BleSignal.StartScan)
        val device = transport.scan()
            ?: return fail("No housing device found during scan.")

        // Step 2: Validate advertising name
        val nameCheck = identityVerifier.validateAdvertisingName(device.name)
        if (nameCheck is HousingIdentityVerifier.VerificationResult.Rejected) {
            return fail(nameCheck.reason)
        }

        // Step 3: Connect
        transitionTo(BleSignal.Connect)
        val connected = transport.connect(device)
        if (!connected) {
            return fail("Failed to connect to housing '${device.name}'.")
        }
        connectedDevice = device

        // Step 4: Discover services
        transitionTo(BleSignal.DiscoverServices)
        val serviceUuids = transport.discoverServices()

        // Step 5: Validate services
        val serviceCheck = identityVerifier.validateDiscoveredServices(serviceUuids)
        if (serviceCheck is HousingIdentityVerifier.VerificationResult.Rejected) {
            transport.disconnect()
            return fail(serviceCheck.reason)
        }

        // Step 6: Read device information (§5.2 Table 6)
        val deviceInfo = readDeviceInformation()

        // Step 7: Validate manufacturer + firmware
        val manufacturerName = deviceInfo[HousingCharacteristic.ManufacturerName] ?: ""
        val firmwareVersion = deviceInfo[HousingCharacteristic.FirmwareRevision] ?: ""

        val mfgCheck = identityVerifier.validateManufacturer(manufacturerName)
        if (mfgCheck is HousingIdentityVerifier.VerificationResult.Rejected) {
            transport.disconnect()
            return fail(mfgCheck.reason)
        }

        val fwCheck = identityVerifier.validateFirmwareVersion(firmwareVersion)
        if (fwCheck is HousingIdentityVerifier.VerificationResult.Rejected) {
            transport.disconnect()
            return fail(fwCheck.reason)
        }

        // Step 8: Subscribe to notification characteristics
        transitionTo(BleSignal.Subscribe)
        for (characteristic in HousingBleProfile.subscriptionOrder) {
            val subscribed = transport.subscribe(characteristic)
            if (!subscribed) {
                onError("Warning: failed to subscribe to ${characteristic.label}")
            }
        }

        // Step 9: Ready
        transitionTo(BleSignal.Ready)

        return ConnectionResult.Connected(
            device = device,
            manufacturerName = manufacturerName,
            firmwareVersion = firmwareVersion,
            serialNumber = deviceInfo[HousingCharacteristic.SerialNumber],
        )
    }

    /**
     * Send a housing command to the connected device.
     * Uses [HousingCommandEncoder] for wire-format encoding per vendor spec §5.3–5.4.
     */
    suspend fun sendCommand(command: HousingCommand): Boolean {
        val requests = commandEncoder.encode(command)

        var allOk = true
        for (request in requests) {
            when (request) {
                is BleTransportRequest.Write -> {
                    val success = transport.writeCharacteristic(request.characteristic, request.payload)
                    if (!success) allOk = false
                }
                is BleTransportRequest.Read -> {
                    transport.readCharacteristic(request.characteristic)
                }
                is BleTransportRequest.Subscribe -> {
                    val success = transport.subscribe(request.characteristic)
                    if (!success) allOk = false
                }
            }
        }

        return allOk
    }

    /**
     * Disconnect from the housing.
     */
    suspend fun disconnect() {
        transport.disconnect()
        transitionTo(BleSignal.Disconnect)
        connectedDevice = null
    }

    /**
     * Signal that the housing powered off (OK long press detected via BLE disconnect
     * without reconnection per vendor spec §5.3 Table 7).
     */
    fun housingPoweredOff() {
        transitionTo(BleSignal.HousingPoweredOff)
        connectedDevice = null
    }

    // --- Private helpers ---

    private suspend fun readDeviceInformation(): Map<HousingCharacteristic, String?> {
        val results = mutableMapOf<HousingCharacteristic, String?>()

        for (characteristic in HousingBleProfile.deviceInfoReadOrder) {
            val bytes = transport.readCharacteristic(characteristic)
            val value = bytes?.toString(Charsets.UTF_8)?.trimEnd('\u0000')?.trim()
            results[characteristic] = value

            // Decode and emit via ProtocolParser
            if (bytes != null) {
                val parseResult = protocolParser.decodeNotification(characteristic.fullUuid, bytes)
                if (parseResult is ParseResult.Success) {
                    onNotificationDecoded(parseResult.value)
                }
            }
        }

        return results
    }

    /**
     * Handle incoming characteristic notification.
     * Uses [ProtocolParser] which already handles all vendor spec wire formats:
     *   - Button events (§5.3): 1 byte
     *   - Battery level (§5.1): 1 byte, 0–100
     *   - Water pressure (§5.4): 4 bytes LE, 0.1 mba
     *   - Water temperature (§5.4): 4 bytes LE, 0.01°C
     *   - Barometric pressure (§5.4): 4 bytes LE, 1 Pa
     *   - Cover state (§5.4): 1 byte, 0x00=open 0x01=closed
     */
    private fun handleNotification(characteristic: HousingCharacteristic, value: ByteArray) {
        val parseResult = protocolParser.decodeNotification(characteristic.fullUuid, value)
        when (parseResult) {
            is ParseResult.Success -> onNotificationDecoded(parseResult.value)
            is ParseResult.Failure -> onError(
                "Notification decode error for ${characteristic.label}: ${parseResult.error.message}"
            )
        }
    }

    private fun transitionTo(signal: BleSignal) {
        val result = connectionMachine.transition(currentState, signal)
        currentState = result.state
        onStateChanged(result.state)
    }

    private fun fail(reason: String): ConnectionResult.Failed {
        transitionTo(BleSignal.Fail)
        onError(reason)
        return ConnectionResult.Failed(reason)
    }
}

/**
 * Result of a connection attempt.
 */
sealed interface ConnectionResult {
    data class Connected(
        val device: DiscoveredDevice,
        val manufacturerName: String,
        val firmwareVersion: String,
        val serialNumber: String?,
    ) : ConnectionResult

    data class Failed(val reason: String) : ConnectionResult
}
