@file:Suppress("MissingPermission", "DEPRECATION")
package com.mobiledivecontrol.platform.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import com.mobiledivecontrol.core.*
import kotlinx.coroutines.*
import java.util.UUID

/** A separate GATT client/queue. Never reuses, reconfigures, or closes the housing connection. */
class AirPressureReceiver(
    private val context: Context,
    private val housingAddress: () -> String?,
    private val emit: (AirAlertCommand) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var queue: GattQueue? = null
    private var scan: ScanCallback? = null
    private var scanJob: Job? = null
    private var watchJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var desiredConnection = false
    private var receiverSource = false
    private var snapshot = AirReceiverState()
    private var mapping = AirPressureMapping()
    private var watched: Set<BluetoothGattCharacteristic> = emptySet()
    private var labelledValues: List<AirReceiverValue> = emptyList()
    @Volatile private var generation = 0L
    private var closed = false

    private fun update(change: (AirReceiverState) -> AirReceiverState) {
        snapshot = change(snapshot)
        emit(AirAlertCommand.ReceiverUpdate(snapshot))
    }
    private fun status(message: String) = update { it.copy(status = message) }
    private fun failed(message: String) {
        update { it.copy(phase = AirReceiverPhase.Failed, status = message) }
    }
    fun configure(settings: AirAlertSettings) {
        mapping = settings.mapping
        labelledValues = settings.receiverValues
        receiverSource = settings.source == AirSource.Receiver
    }
    fun execute(request: AirReceiverRequest, settings: AirAlertSettings) {
        if (closed) return
        configure(settings)
        if (request.action == AirReceiverAction.Disconnect) { disconnect(); return }
        if (!BlePermissions.allGranted(context) || !BlePermissions.isAdapterEnabled(context)) {
            disconnect(); failed("Connection unavailable · enable Bluetooth and nearby-device permissions"); return
        }
        runCatching {
            when (request.action) {
                AirReceiverAction.Scan -> scan()
                AirReceiverAction.Connect -> { reconnectAttempts = 0; connect() }
                AirReceiverAction.Watch -> watch()
                AirReceiverAction.Disconnect -> disconnect()
            }
        }.onFailure { disconnect(); failed("Receiver connection failed · ${it.message ?: "try again"}") }
    }

    private fun stopScan() {
        scanJob?.cancel(); scanJob = null
        scan?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        scan = null
    }
    private fun scan() {
        stopScan()
        update { it.copy(devices = emptyList(), selectedDevice = 0, phase = AirReceiverPhase.Scanning, status = "Looking for receivers…") }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val owner = this
                scope.launch {
                    if (scan !== owner) return@launch
                    val address = runCatching { result.device.address }.getOrNull() ?: return@launch
                    val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "Unnamed device"
                    if (address.equals(housingAddress(), true) || name.replace(" ", "").equals("DIVEIT", true)) return@launch
                    if (snapshot.devices.none { it.address == address } && snapshot.devices.size < 64)
                        update { it.copy(devices = it.devices + AirDevice(address, name)) }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                val owner = this
                scope.launch { if (scan === owner) { stopScan(); failed("Receiver search failed ($errorCode) · try again") } }
            }
        }
        scan = callback
        val scanner = adapter?.bluetoothLeScanner ?: run { scan = null; failed("Receiver search unavailable · check Bluetooth"); return }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        scanJob = scope.launch {
            delay(12_000); stopScan()
            if (snapshot.devices.isEmpty()) failed("No receiver found · turn it on nearby and try again")
            else update { it.copy(phase = AirReceiverPhase.Idle, status = "Choose a device to connect") }
        }
    }

    private fun connect() {
        val address = mapping.address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { status("Select a device first"); return }
        if (address.equals(housingAddress(), true)) { status("The camera housing cannot be used as a tank-pressure receiver"); return }
        stopScan(); disconnect()
        desiredConnection = true
        update { it.copy(phase = AirReceiverPhase.Connecting, status = "Connecting to ${mapping.deviceName.ifBlank { "receiver" }}…") }
        val id = ++generation
        queue = GattQueue(scope)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(client: BluetoothGatt, result: Int, newState: Int) { scope.launch {
                if (id != generation || gatt !== client) return@launch
                if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect(allowRetry = true); failed("Receiver connection failed or disconnected ($result) · try again"); return@launch
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    update { it.copy(connected = false, phase = AirReceiverPhase.Connecting, status = "Checking receiver data…") }
                    if (!runCatching { client.discoverServices() }.getOrDefault(false)) { disconnect(); failed("Connection failed · could not read receiver services") }
                }
            } }
            override fun onServicesDiscovered(client: BluetoothGatt, result: Int) { scope.launch {
                if (id != generation || gatt !== client) return@launch
                connectJob?.cancel()
                if (result != BluetoothGatt.GATT_SUCCESS) { disconnect(); failed("Connection failed · service discovery error $result"); return@launch }
                reconnectAttempts = 0
                val characteristics = client.services.flatMap { service -> service.characteristics.mapNotNull { characteristic ->
                    val p = characteristic.properties
                    val readable = p and BluetoothGattCharacteristic.PROPERTY_READ != 0
                    val notify = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (readable || notify) AirCharacteristic(service.uuid.toString(), characteristic.uuid.toString(), readable, notify) else null
                } }.take(128)
                if (characteristics.isEmpty()) {
                    disconnect(); failed("Receiver has no readable data · this connection cannot be used"); return@launch
                }
                update { it.copy(connected = true, phase = AirReceiverPhase.Ready, characteristics = characteristics, selectedCharacteristic = characteristics.indexOfFirst { c ->
                    c.service.equals(mapping.service, true) && c.uuid.equals(mapping.characteristic, true)
                }.coerceAtLeast(0), status = "Receiver connected · ${characteristics.size} available signals") }

            } }
            override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
                read(client, characteristic, characteristic.value ?: byteArrayOf(), result)
            }
            override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, result: Int) {
                read(client, characteristic, value, result)
            }
            private fun read(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, result: Int) {
                if (id != generation || gatt !== client) return
                queue?.complete(GattQueue.Kind.Read, characteristic.uuid.toString(), result, value)
                if (result == BluetoothGatt.GATT_SUCCESS) packet(client, characteristic, value)
            }
            override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                packet(client, characteristic, characteristic.value ?: byteArrayOf())
            }
            override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                packet(client, characteristic, value)
            }
            override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) {
                if (id == generation && gatt === client) queue?.complete(GattQueue.Kind.DescriptorWrite,
                    descriptor.characteristic.uuid.toString(), result, null)
            }
        }
        gatt = adapter?.getRemoteDevice(address)?.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        connectJob = scope.launch { delay(20_000); if (id == generation) { disconnect(allowRetry = true); failed("Receiver connection timed out · move it nearby and try again") } }
    }

    private fun packet(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val bytes = value.take(512).map { it.toInt() and 255 }
        val received = pressureMonotonicMs()
        scope.launch {
            if (gatt !== client || characteristic !in watched) return@launch
            emit(AirAlertCommand.Packet(characteristic.service.uuid.toString(), characteristic.uuid.toString(), bytes, received))
        }
    }
    private fun watch() {
        watchJob?.cancel()
        val client = gatt ?: return status("Connect a receiver first")
        val ops = queue ?: return
        val maps = listOf(mapping) + labelledValues.filter { it.mapping.address.equals(mapping.address, true) }.map { it.mapping }
        val targets = maps.mapNotNull { map -> runCatching {
            client.getService(UUID.fromString(map.service))?.getCharacteristic(UUID.fromString(map.characteristic))
        }.getOrNull() }.toSet()
        if (targets.isEmpty()) return status("Select an available signal")
        val previous = watched
        watched = targets
        watchJob = scope.launch {
            for (old in previous - targets) {
                runCatching { client.setCharacteristicNotification(old, false) }
                old.getDescriptor(CCCD)?.let { ops.writeDescriptor(client, it, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }
            }
            // Reapply subscriptions on an explicit retry, including ones that failed previously.
            for (characteristic in targets) {
                val properties = characteristic.properties
                val notify = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (notify) {
                    val cccd = characteristic.getDescriptor(CCCD)
                    val registered = runCatching { client.setCharacteristicNotification(characteristic, true) }.getOrDefault(false)
                    if (cccd == null || !registered) { status("Signal subscription unavailable · try another signal"); continue }
                    val bytes = if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    val result = ops.writeDescriptor(client, cccd, bytes)
                    if (!result.isSuccess) status("Signal subscription failed: ${result.describe()}")
                }
            }
            val readable = targets.filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            while (isActive && gatt === client && readable.isNotEmpty()) {
                for (characteristic in readable) {
                    val result = ops.readCharacteristic(client, characteristic)
                    if (!result.isSuccess) status("Signal read failed: ${result.describe()} · try another signal")
                }
                delay(2_000)
            }
        }
    }
    fun disconnect(allowRetry: Boolean = false) {
        stopScan()
        val retry = allowRetry && desiredConnection && receiverSource && mapping.verified && reconnectAttempts < 3 && !closed
        reconnectJob?.cancel(); reconnectJob = null
        if (!allowRetry) desiredConnection = false
        generation++
        connectJob?.cancel(); connectJob = null
        watchJob?.cancel(); watchJob = null; watched = emptySet()
        queue?.close(); queue = null
        val previous = gatt; gatt = null
        runCatching { previous?.disconnect() }; runCatching { previous?.close() }
        update { it.copy(connected = false, phase = AirReceiverPhase.Idle, signals = emptyMap(), characteristics = emptyList(), payload = emptyList(), liveBar = null, liveAtMs = null, status = "Not connected · use manual gauge") }
        if (retry) {
            reconnectAttempts++
            reconnectJob = scope.launch {
                delay(5_000L * reconnectAttempts)
                reconnectJob = null
                if (BlePermissions.allGranted(context) && BlePermissions.isAdapterEnabled(context)) runCatching { connect() }
                    .onFailure { failed("Receiver reconnection failed · try again") }
            }
        }
    }
    fun close() { closed = true; stopScan(); disconnect(); scope.cancel() }
    companion object { private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
