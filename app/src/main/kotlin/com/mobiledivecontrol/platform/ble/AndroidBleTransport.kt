package com.mobiledivecontrol.platform.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.mobiledivecontrol.core.DiscoveredDevice
import com.mobiledivecontrol.core.HousingBleProfile
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.NotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The real radio: [HousingTransport] over Android's `BluetoothGatt`.
 *
 * Everything here exists to keep one promise — a button press inside a sealed housing
 * becomes a command on the phone, or the diver is told plainly that it will not. So the
 * failure paths are as deliberate as the happy path: a missing permission is caught rather
 * than thrown, a stalled GATT operation is failed rather than awaited forever, and the GATT
 * client is closed on every exit including the ones that look like they cannot happen.
 * Leaked GATT clients are cumulative and process-wide; roughly thirty of them and no app on
 * the phone can open a BLE connection again until it reboots.
 *
 * @param preferredAddress MAC of the housing the diver already trusts. When it is seen the
 *   scan short-circuits immediately, because on the boat there is no screen to tap.
 */
class AndroidBleTransport(
    private val context: Context,
    private val preferredAddress: String?,
) : HousingTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val handles = ConcurrentHashMap<HousingCharacteristic, BluetoothGattCharacteristic>()
    private val characteristicByUuid = ConcurrentHashMap<String, HousingCharacteristic>()
    private val reportLines = CopyOnWriteArrayList<String>()

    private val activeScan = AtomicReference<ScanCallback?>(null)
    private val localDisconnect = AtomicBoolean(false)
    private val disconnectReported = AtomicBoolean(true)

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var queue: GattQueue? = null

    @Volatile
    private var connectionResult: CompletableDeferred<Int>? = null

    @Volatile
    private var discoveryResult: CompletableDeferred<Int>? = null

    @Volatile
    private var notificationListener: NotificationListener? = null

    @Volatile
    private var disconnectListener: ((DisconnectCause) -> Unit)? = null

    @Volatile
    private var resolvedCharacteristics: Set<HousingCharacteristic> = emptySet()

    override val availableCharacteristics: Set<HousingCharacteristic>
        get() = resolvedCharacteristics

    override val discoveryReport: List<String>
        get() = reportLines.toList()

    override fun setNotificationListener(listener: NotificationListener?) {
        notificationListener = listener
    }

    override fun setDisconnectListener(listener: ((DisconnectCause) -> Unit)?) {
        disconnectListener = listener
    }

    // --- Scanning -------------------------------------------------------------------

    /**
     * Scans for the housing.
     *
     * The remembered address wins instantly; otherwise every device advertising `DIVE IT`
     * is collected for the full window and the strongest signal is returned, because a
     * dive boat can have several housings within a metre of each other and the nearest one
     * is the one in the diver's hands. The name is only a filter here — identity is settled
     * after connecting, by whether the device actually exposes the button characteristic.
     */
    override suspend fun scan(timeoutMs: Long): DiscoveredDevice? {
        val adapter = adapter ?: return null
        if (!adapter.isEnabled) {
            note("Scan skipped: Bluetooth adapter is off")
            return null
        }
        if (!hasScanPermission()) {
            note("Scan skipped: BLUETOOTH_SCAN / location permission not granted")
            return null
        }

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (error: SecurityException) {
            note("Scan denied: ${error.message}")
            null
        } ?: return null

        val candidates = ConcurrentHashMap<String, DiscoveredDevice>()
        val seen = ConcurrentHashMap<String, String>()
        val finished = CompletableDeferred<DiscoveredDevice?>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                record(result, candidates, seen, finished)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { record(it, candidates, seen, finished) }
            }

            override fun onScanFailed(errorCode: Int) {
                note("BLE scan failed (error $errorCode)")
                finished.complete(null)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        stopScan()
        activeScan.set(callback)

        return try {
            scanner.startScan(emptyList(), settings, callback)
            val found = withTimeoutOrNull(timeoutMs) { finished.await() }
                ?: candidates.values.maxByOrNull { it.rssi }
            if (found == null) reportUnmatchedScan(seen)
            found
        } catch (error: SecurityException) {
            note("Scan denied: ${error.message}")
            null
        } finally {
            stopScan()
        }
    }

    /**
     * Records what the scan actually saw when nothing matched.
     *
     * Without this a failed first-time scan is silent, and the diver cannot tell a housing
     * that is switched off from one advertising under an unexpected name. The housing is
     * usually the strongest unnamed device in the list, so the RSSI ordering is the clue.
     */
    private fun reportUnmatchedScan(seen: Map<String, String>) {
        if (seen.isEmpty()) {
            note("Scan found no BLE devices at all — is the housing powered on and in range?")
            return
        }
        note(
            "Scan found no device named '${HousingBleProfile.advertisingName}'. " +
                "Saw ${seen.size} device(s): " + seen.values.joinToString(separator = "; "),
        )
    }

    private fun record(
        result: ScanResult,
        candidates: MutableMap<String, DiscoveredDevice>,
        seen: MutableMap<String, String>,
        finished: CompletableDeferred<DiscoveredDevice?>,
    ) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = advertisedName(result).trim()

        seen[address] = "$address '${name.ifEmpty { "<no name>" }}' ${result.rssi}dBm"

        if (preferredAddress != null && address.equals(preferredAddress, ignoreCase = true)) {
            val known = DiscoveredDevice(
                name = name.ifEmpty { HousingBleProfile.advertisingName },
                macAddress = address,
                rssi = result.rssi,
            )
            finished.complete(known)
            return
        }

        if (!matchesHousingName(name)) return

        val existing = candidates[address]
        if (existing == null || result.rssi > existing.rssi) {
            candidates[address] = DiscoveredDevice(name, address, result.rssi)
        }
    }

    /**
     * Matches the housing's advertised name ignoring case, spacing and punctuation.
     *
     * The protocol document specifies `DIVE IT`; real hardware advertises `DIVEIT`. An exact
     * comparison rejects the very device it is looking for, and the failure is invisible — the
     * housing simply never appears. Since the name is only a coarse filter here (identity is
     * settled after connecting, by whether the button characteristic exists), matching loosely
     * costs nothing and removes a whole class of firmware-revision surprises.
     */
    private fun matchesHousingName(name: String): Boolean {
        val expected = HousingBleProfile.advertisingName.filter(Char::isLetterOrDigit)
        return name.filter(Char::isLetterOrDigit).equals(expected, ignoreCase = true)
    }

    private fun advertisedName(result: ScanResult): String {
        result.scanRecord?.deviceName?.let { return it }
        return try {
            result.device?.name.orEmpty()
        } catch (error: SecurityException) {
            ""
        }
    }

    private fun stopScan() {
        val callback = activeScan.getAndSet(null) ?: return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (error: SecurityException) {
            note("Stop scan denied: ${error.message}")
        } catch (error: IllegalStateException) {
            note("Stop scan rejected: ${error.message}")
        }
    }

    // --- Connection -----------------------------------------------------------------

    /**
     * Connects, retrying transient failures.
     *
     * Status 133 is Android's catch-all GATT error and is frequently nothing more than a
     * busy controller; closing and retrying clears it far more often than it does not. A
     * hard failure is only reported after [MAX_CONNECT_ATTEMPTS] tries.
     */
    override suspend fun connect(device: DiscoveredDevice): Boolean {
        val adapter = adapter ?: return false
        if (!adapter.isEnabled) {
            note("Connect skipped: Bluetooth adapter is off")
            return false
        }
        if (!hasConnectPermission()) {
            note("Connect skipped: BLUETOOTH_CONNECT permission not granted")
            return false
        }

        stopScan()

        val remote = try {
            adapter.getRemoteDevice(device.macAddress)
        } catch (error: IllegalArgumentException) {
            note("Connect skipped: '${device.macAddress}' is not a valid address")
            return false
        }

        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            val status = attemptConnect(remote)
            if (status == BluetoothGatt.GATT_SUCCESS) return true

            note("Connect attempt ${attempt + 1}/$MAX_CONNECT_ATTEMPTS failed (${statusLabel(status)})")
            releaseSession()
            if (attempt < MAX_CONNECT_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }

        return false
    }

    private suspend fun attemptConnect(remote: BluetoothDevice): Int {
        releaseSession()
        prepareSession()

        val result = CompletableDeferred<Int>()
        connectionResult = result

        val client = try {
            // connectGatt must be issued from the main thread on several OEM stacks.
            withContext(Dispatchers.Main) {
                remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        } catch (error: SecurityException) {
            note("Connect denied: ${error.message}")
            null
        }

        if (client == null) return STATUS_LOCAL_FAILURE
        gatt = client

        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { result.await() } ?: STATUS_TIMEOUT
    }

    private fun prepareSession() {
        localDisconnect.set(false)
        disconnectReported.set(false)
        handles.clear()
        characteristicByUuid.clear()
        resolvedCharacteristics = emptySet()

        val sessionScope = CoroutineScope(SupervisorJob() + bleDispatcher)
        scope = sessionScope
        queue = GattQueue(sessionScope, onDiagnostic = ::note)
    }

    /**
     * Tears the session down. Idempotent, and safe to call from a binder thread — the GATT
     * client is closed here and nowhere else, so every exit path funnels through it.
     */
    private fun releaseSession() {
        queue?.close("link closed")
        queue = null

        val client = gatt
        gatt = null
        if (client != null) {
            try {
                client.close()
            } catch (error: SecurityException) {
                note("Close denied: ${error.message}")
            }
        }

        scope?.cancel()
        scope = null

        connectionResult = null
        discoveryResult = null
    }

    override suspend fun disconnect() {
        localDisconnect.set(true)
        stopScan()

        val client = gatt
        if (client != null) {
            try {
                client.disconnect()
            } catch (error: SecurityException) {
                note("Disconnect denied: ${error.message}")
            }
        }

        releaseSession()
        notifyDisconnect(DisconnectCause.LocalClosed)
    }

    // --- Discovery ------------------------------------------------------------------

    override suspend fun discoverServices(): Set<String> {
        val client = gatt ?: return emptySet()
        if (!hasConnectPermission()) return emptySet()

        val result = CompletableDeferred<Int>()
        discoveryResult = result

        val started = try {
            client.discoverServices()
        } catch (error: SecurityException) {
            note("Service discovery denied: ${error.message}")
            false
        }
        if (!started) {
            note("Service discovery could not be started")
            return emptySet()
        }

        val status = withTimeoutOrNull(SERVICE_DISCOVERY_TIMEOUT_MS) { result.await() } ?: STATUS_TIMEOUT
        if (status != BluetoothGatt.GATT_SUCCESS) {
            note("Service discovery failed (${statusLabel(status)})")
            return emptySet()
        }

        return indexServices(client)
    }

    /**
     * Records the whole discovered tree and binds each logical characteristic to the real
     * `BluetoothGattCharacteristic` the device exposes.
     *
     * The dump is the point: one connection with real hardware turns the malformed base
     * UUID in the vendor document into a settled fact, and until then nothing in the app
     * depends on which reading of it is correct.
     */
    private fun indexServices(client: BluetoothGatt): Set<String> {
        val services = try {
            client.services
        } catch (error: SecurityException) {
            note("Service list denied: ${error.message}")
            null
        }.orEmpty()

        val lines = mutableListOf<String>()
        val resolved = LinkedHashMap<HousingCharacteristic, BluetoothGattCharacteristic>()
        val sources = HashMap<HousingCharacteristic, HousingUuidResolver.MatchSource>()
        val serviceUuids = LinkedHashSet<String>()

        for (service in services) {
            val serviceUuid = service.uuid.toString()
            serviceUuids += serviceUuid
            val shortCode = HousingUuidResolver.resolveServiceShortCode(serviceUuid)
            lines += "SERVICE $serviceUuid -> ${shortCode?.let { shortHex(it) } ?: "unmapped"}"

            for (characteristic in service.characteristics.orEmpty()) {
                val uuid = characteristic.uuid.toString()
                val properties = characteristic.properties
                val match = HousingUuidResolver.matchCharacteristic(uuid)
                lines += "  CHAR $uuid props=${propertiesLabel(properties)} -> ${HousingUuidResolver.describe(uuid)}"

                if (match !is HousingUuidResolver.Match.Unique) continue

                val previous = resolved[match.characteristic]
                val previousSource = sources[match.characteristic]
                val replace = previous == null ||
                    (previousSource != HousingUuidResolver.MatchSource.SigBase &&
                        match.source == HousingUuidResolver.MatchSource.SigBase)

                if (previous != null) {
                    lines += "  DUPLICATE ${match.characteristic.label}: $uuid vs ${previous.uuid}" +
                        if (replace) " (using $uuid, SIG base wins)" else " (keeping ${previous.uuid})"
                }
                if (replace) {
                    resolved[match.characteristic] = characteristic
                    sources[match.characteristic] = match.source
                }
            }
        }

        handles.clear()
        handles.putAll(resolved)
        characteristicByUuid.clear()
        resolved.forEach { (logical, characteristic) ->
            characteristicByUuid[characteristic.uuid.toString().lowercase()] = logical
        }
        resolvedCharacteristics = resolved.keys.toSet()

        val missing = HousingCharacteristic.entries.filterNot { it in resolved.keys }
        lines += "RESOLVED ${resolved.size}/${HousingCharacteristic.entries.size} characteristics"
        if (missing.isNotEmpty()) {
            lines += "MISSING ${missing.joinToString { "${it.label} (${it.shortHex})" }}"
        }

        reportLines.clear()
        reportLines.addAll(lines)
        lines.forEach { Log.i(TAG, it) }

        return serviceUuids
    }

    // --- Characteristic access ------------------------------------------------------

    override suspend fun readCharacteristic(characteristic: HousingCharacteristic): ByteArray? {
        val client = gatt ?: return null
        val worker = queue ?: return null
        val handle = handles[characteristic] ?: run {
            note("Read skipped: ${characteristic.label} is not exposed by this housing")
            return null
        }

        val outcome = worker.readCharacteristic(client, handle)
        inspectSecurityStatus(outcome, "read ${characteristic.label}")
        if (!outcome.isSuccess) {
            note("Read ${characteristic.label} failed: ${outcome.describe()}")
            return null
        }
        return outcome.value
    }

    override suspend fun writeCharacteristic(
        characteristic: HousingCharacteristic,
        value: ByteArray,
    ): Boolean {
        val client = gatt ?: return false
        val worker = queue ?: return false
        val handle = handles[characteristic] ?: run {
            note("Write skipped: ${characteristic.label} is not exposed by this housing")
            return false
        }

        val outcome = worker.writeCharacteristic(client, handle, value, writeTypeFor(handle, characteristic))
        inspectSecurityStatus(outcome, "write ${characteristic.label}")
        if (!outcome.isSuccess) {
            note("Write ${characteristic.label} failed: ${outcome.describe()}")
            return false
        }
        return true
    }

    private fun writeTypeFor(
        handle: BluetoothGattCharacteristic,
        characteristic: HousingCharacteristic,
    ): Int = when {
        handle.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        handle.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        else -> {
            note("${characteristic.label} advertises no write property; attempting an acknowledged write")
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    /**
     * Turns notifications on.
     *
     * Two steps, both mandatory: `setCharacteristicNotification` only tells the local stack
     * to stop discarding the packets, and the CCCD write is what tells the housing to send
     * them. Doing the first alone is the classic "notifications never arrive" bug.
     */
    override suspend fun subscribe(characteristic: HousingCharacteristic): Boolean {
        val client = gatt ?: return false
        val worker = queue ?: return false
        val handle = handles[characteristic] ?: run {
            note("Subscribe skipped: ${characteristic.label} is not exposed by this housing")
            return false
        }

        val notify = handle.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = handle.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!notify && !indicate) {
            note("Subscribe skipped: ${characteristic.label} supports neither notify nor indicate")
            return false
        }

        val enabledLocally = try {
            client.setCharacteristicNotification(handle, true)
        } catch (error: SecurityException) {
            note("Subscribe denied: ${error.message}")
            false
        }
        if (!enabledLocally) {
            note("Subscribe failed: local notification enable rejected for ${characteristic.label}")
            return false
        }

        val descriptor = handle.getDescriptor(CCCD_UUID) ?: run {
            note("Subscribe failed: ${characteristic.label} has no client configuration descriptor")
            return false
        }

        val value = if (notify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val outcome = worker.writeDescriptor(client, descriptor, value)
        inspectSecurityStatus(outcome, "subscribe ${characteristic.label}")
        if (!outcome.isSuccess) {
            note("Subscribe ${characteristic.label} failed: ${outcome.describe()}")
            return false
        }
        return true
    }

    // --- GATT callbacks -------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                requestHighPriority(client)
                connectionResult?.complete(status)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTING ||
                newState == BluetoothProfile.STATE_DISCONNECTING
            ) {
                return
            }

            queue?.cancelAll("link down (${statusLabel(status)})")
            discoveryResult?.complete(STATUS_LINK_LOST)

            val pending = connectionResult
            val duringConnect = pending != null && !pending.isCompleted
            val cause = causeFor(status)
            val tracked = gatt

            releaseSession()
            if (tracked !== client) {
                try {
                    client.close()
                } catch (error: SecurityException) {
                    note("Close denied: ${error.message}")
                }
            }

            if (duringConnect) {
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) STATUS_LINK_LOST else status)
                return
            }
            notifyDisconnect(cause)
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            discoveryResult?.complete(status)
        }

        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            queue?.complete(GattQueue.Kind.Read, characteristic.uuid.toString(), status, value.copyOf())
        }

        /** The framework calls this variant below API 33; the newer one above it. */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue?.complete(
                GattQueue.Kind.Read,
                characteristic.uuid.toString(),
                status,
                characteristic.value?.copyOf(),
            )
        }

        override fun onCharacteristicWrite(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue?.complete(GattQueue.Kind.Write, characteristic.uuid.toString(), status, null)
        }

        override fun onDescriptorWrite(
            client: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue?.complete(
                GattQueue.Kind.DescriptorWrite,
                descriptor.characteristic?.uuid?.toString().orEmpty(),
                status,
                null,
            )
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(characteristic.uuid.toString(), value.copyOf())
        }

        /** The framework calls this variant below API 33; the newer one above it. */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            dispatchNotification(
                characteristic.uuid.toString(),
                characteristic.value?.copyOf() ?: ByteArray(0),
            )
        }
    }

    /**
     * Hands a notification to the listener on the transport's own thread.
     *
     * Callbacks arrive on binder threads, so the hop is what keeps every consumer off them;
     * it costs tens of microseconds against a 100ms button-to-event budget.
     */
    private fun dispatchNotification(uuid: String, value: ByteArray) {
        val characteristic = characteristicByUuid[uuid.lowercase()]
            ?: HousingUuidResolver.resolveCharacteristic(uuid)
            ?: return
        val listener = notificationListener ?: return

        val sessionScope = scope
        if (sessionScope == null) {
            listener.onNotification(characteristic, value)
            return
        }
        sessionScope.launch { listener.onNotification(characteristic, value) }
    }

    private fun notifyDisconnect(cause: DisconnectCause) {
        if (!disconnectReported.compareAndSet(false, true)) return
        disconnectListener?.invoke(cause)
    }

    /**
     * Status 19 is the housing closing the link itself, which is what a long OK press does
     * — telling the diver "reconnecting" when the housing is off would be a lie they cannot
     * act on. Status 0 without a local request means the link simply ended, which from the
     * diver's side is indistinguishable from swimming out of range.
     */
    private fun causeFor(status: Int): DisconnectCause = when {
        localDisconnect.get() -> DisconnectCause.LocalClosed
        status == GATT_CONN_TERMINATE_PEER_USER -> DisconnectCause.RemoteClosed
        status == GATT_CONN_TIMEOUT || status == GATT_ERROR -> DisconnectCause.LinkLoss
        status == BluetoothGatt.GATT_SUCCESS -> DisconnectCause.LinkLoss
        else -> DisconnectCause.Error
    }

    private fun requestHighPriority(client: BluetoothGatt) {
        try {
            client.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (error: SecurityException) {
            note("Connection priority request denied: ${error.message}")
        } catch (error: IllegalArgumentException) {
            note("Connection priority request rejected: ${error.message}")
        }
    }

    /**
     * The protocol defines no encrypted characteristics, so an authentication demand means
     * the housing is not the device the app thinks it is, or its firmware changed. Surfacing
     * it beats silently starting a bond the diver never agreed to.
     */
    private fun inspectSecurityStatus(outcome: GattOutcome, operation: String) {
        when (outcome.status) {
            GATT_INSUFFICIENT_AUTHENTICATION ->
                note("$operation demanded authentication (status 5) — bonding is deliberately not attempted")

            GATT_INSUFFICIENT_ENCRYPTION ->
                note("$operation demanded encryption (status 15) — bonding is deliberately not attempted")
        }
    }

    // --- Permissions ----------------------------------------------------------------

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.BLUETOOTH_ADMIN)
    }

    private fun hasConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.BLUETOOTH)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // --- Diagnostics ----------------------------------------------------------------

    private fun note(message: String) {
        Log.w(TAG, message)
        if (reportLines.size >= MAX_REPORT_LINES) {
            reportLines.removeAt(0)
        }
        reportLines.add("NOTE $message")
    }

    private fun statusLabel(status: Int): String = when (status) {
        STATUS_LOCAL_FAILURE -> "not started"
        STATUS_TIMEOUT -> "timeout"
        STATUS_LINK_LOST -> "link lost"
        GATT_ERROR -> "status 133 (generic GATT error)"
        GATT_CONN_TIMEOUT -> "status 8 (connection timeout)"
        GATT_CONN_TERMINATE_PEER_USER -> "status 19 (closed by housing)"
        else -> "status $status"
    }

    private fun shortHex(code: Int): String = "0x" + code.toString(16).uppercase().padStart(4, '0')

    private fun propertiesLabel(properties: Int): String {
        val names = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }
        val bitmask = "0x" + properties.toString(16).uppercase().padStart(2, '0')
        return if (names.isEmpty()) bitmask else "$bitmask [${names.joinToString("|")}]"
    }

    private companion object {
        const val TAG = "HousingBle"

        /** CLAUDE.md §18.4: connect attempt 10s. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** CLAUDE.md §18.4: service discovery 8s. */
        const val SERVICE_DISCOVERY_TIMEOUT_MS = 8_000L

        const val MAX_CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
        const val MAX_REPORT_LINES = 200

        const val STATUS_LOCAL_FAILURE = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_LINK_LOST = -3

        const val GATT_ERROR = 133
        const val GATT_CONN_TIMEOUT = 8
        const val GATT_CONN_TERMINATE_PEER_USER = 19
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * One daemon thread for all housing GATT work, process-wide. Callbacks are confined
         * to it, and sharing it means repeated connect attempts cannot accumulate threads.
         */
        val bleDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "housing-ble").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
