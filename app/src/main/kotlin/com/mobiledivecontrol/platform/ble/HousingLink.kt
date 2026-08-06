package com.mobiledivecontrol.platform.ble

import android.content.Context
import android.util.Log
import com.mobiledivecontrol.core.BleConnectionMachine
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.BleTransportRequest
import com.mobiledivecontrol.core.DiscoveredDevice
import com.mobiledivecontrol.core.HousingBleProfile
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.HousingCommand
import com.mobiledivecontrol.core.HousingCommandEncoder
import com.mobiledivecontrol.core.HousingIdentityVerifier
import com.mobiledivecontrol.core.ParseResult
import com.mobiledivecontrol.core.ProtocolParser
import com.mobiledivecontrol.core.SensorUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Everything the app learns from the housing radio, in the order it happened.
 *
 * [Notification] carries the raw characteristic payload rather than a decoded value on purpose.
 * Decoding, debouncing, routing and diagnostics all live behind `ControlCore`; a value decoded
 * out here would reach the UI without ever passing through the input router, which is the one
 * component that turns a button into a command.
 */
sealed interface HousingLinkEvent {

    /** A step in the connection state machine. Drives `ControlCore.advanceBle`. */
    data class Ble(val signal: BleSignal) : HousingLinkEvent

    /** Raw bytes from a notification or a read, keyed by 16-bit short code (e.g. `"1524"`). */
    data class Notification(
        val characteristicShortHex: String,
        val payload: ByteArray,
    ) : HousingLinkEvent {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Notification &&
                characteristicShortHex == other.characteristicShortHex &&
                payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * characteristicShortHex.hashCode() + payload.contentHashCode()
    }

    /** An advisory the diver or a support engineer needs to see. Never a hard failure. */
    data class Warning(val message: String) : HousingLinkEvent

    /** Identity of the housing that just reached Ready, after it has been persisted. */
    data class Identity(
        val address: String,
        val serial: String?,
        val firmware: String?,
    ) : HousingLinkEvent
}

/**
 * Owns the housing connection for the lifetime of the process.
 *
 * The diver seals the phone in the housing on the boat and has no touchscreen afterwards, so the
 * link cannot ever require a tap: it scans, connects, verifies, subscribes and — on any drop —
 * does it all again, forever. The supervisor coroutine is written so that no failure can end it;
 * a terminated supervisor would leave the housing buttons permanently dead with no way back.
 *
 * Identity checks are advisory by design. The only binding requirement is that the device
 * exposes the button characteristic, because that is the functional definition of "our housing".
 * A manufacturer string or firmware revision outside the hardcoded supported set warns and
 * connects anyway; blocking there would make real hardware unusable for no safety benefit.
 */
class HousingLink(
    private val context: Context,
    private val store: HousingStore,
    private val transportFactory: (String?) -> HousingTransport,
    private val connectionMachine: BleConnectionMachine = BleConnectionMachine(),
    private val identityVerifier: HousingIdentityVerifier = HousingIdentityVerifier(),
    private val encoder: HousingCommandEncoder = HousingCommandEncoder(),
    private val parser: ProtocolParser = ProtocolParser(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<HousingLinkEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of link activity. Drops oldest under back pressure rather than blocking GATT. */
    val events: SharedFlow<HousingLinkEvent> = _events.asSharedFlow()

    /**
     * Last connection signal and last value of each telemetry characteristic.
     *
     * [events] has no replay, deliberately — replaying a button press on every resubscribe would
     * fire phantom input. But that leaves a subscriber created *after* the link came up with no way
     * to learn it is connected: the one-shot `Ready` signal is long gone, while periodic
     * notifications keep arriving. The result is an app showing "housing not connected" next to
     * live water temperature, with its buttons dead because the state machine never armed.
     *
     * A view model can be recreated at any time, so the link has to be able to answer "what do you
     * already know" rather than assuming anyone was listening when it happened.
     */
    @Volatile
    private var lastSignal: BleSignal? = null

    private val telemetry = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /**
     * Everything a fresh subscriber needs to catch up, in the order it should be applied.
     *
     * Replay is restricted to values that will *not* arrive again on their own, because a replayed
     * reading is indistinguishable from a live one once it reaches the safety state machine:
     *
     *  - Cover state only notifies on change, so without it a housing already sitting cover-open
     *    would never offer the seal-check prompt. It must be replayed.
     *  - Battery is read once at connect and then notifies rarely. Replayed.
     *  - Pressure is deliberately NOT replayed. Barometric arrives at 2–5 Hz, so a live sample lands
     *    within a few hundred milliseconds and nothing is lost — while a *stale* one fed into leak
     *    detection during a seal check would look like pressure that had moved, and fail a good
     *    seal. The whole point of that check is that every sample is evidence; a replayed sample is
     *    not evidence, it is an echo.
     *
     * Button events are never included either: they are input, not state, and replaying one would
     * move the camera without anybody touching the housing.
     */
    fun snapshot(): List<HousingLinkEvent> = buildList {
        lastSignal?.let { add(HousingLinkEvent.Ble(it)) }
        telemetry.forEach { (shortHex, payload) ->
            if (HousingCharacteristic.from(shortHex) in REPLAYABLE_ON_RESUBSCRIBE) {
                add(HousingLinkEvent.Notification(shortHex, payload))
            }
        }
    }

    private val lifecycleLock = Any()
    private var supervisorJob: Job? = null

    @Volatile
    private var session: Session? = null

    private val writeLock = Mutex()

    /**
     * Whether the housing has ever reported its suction cover open since the app started.
     *
     * This is the last gate in front of the pump. Running the motor against a sealed shell is the
     * one command here with a physical consequence that outlives the app — it stalls against a
     * closed system and there is no software fix afterwards. The state machine already refuses to
     * start without a confirmed cover-open, but the state machine is one code path and this is the
     * only chokepoint every path shares, so the guarantee is repeated where the write actually
     * happens. Volatile because the GATT callback thread sets it and a caller coroutine reads it.
     */
    @Volatile
    private var coverOpenSeen: Boolean = false

    /** True between a successful motor-on write and a successful motor-off write. */
    @Volatile
    private var motorBelievedRunning: Boolean = false

    private var motorWatchdog: Job? = null

    /** Begins (or resumes) the connection supervisor. Safe to call repeatedly. */
    fun start() {
        synchronized(lifecycleLock) {
            if (supervisorJob?.isActive == true) return
            supervisorJob = scope.launch { supervise() }
        }
    }

    /** Tears the link down and stops reconnecting. Only the foreground service calls this. */
    fun stop() {
        val running = synchronized(lifecycleLock) {
            val current = supervisorJob
            supervisorJob = null
            current
        }
        running?.cancel()

        // A new session must re-earn the right to run the pump; the housing may have been closed,
        // handed over or swapped while the link was down.
        coverOpenSeen = false
        telemetry.clear()
        lastSignal = null
        motorWatchdog?.cancel()
        motorWatchdog = null

        val ending = session
        session = null
        if (ending != null) {
            scope.launch {
                withContext(NonCancellable) { closeQuietly(ending.transport) }
            }
        }
        emit(HousingLinkEvent.Ble(BleSignal.Reset))
    }

    /**
     * Sends a housing command, returning whether every underlying GATT operation succeeded.
     *
     * Never throws. A command issued while the link is down is a warning, not a crash — the
     * housing buttons keep working the moment the link comes back.
     */
    suspend fun send(command: HousingCommand): Boolean {
        HousingFeatureFlags.rejectionReason(command)?.let { reason ->
            Log.w(TAG, "Blocked high-risk housing command $command: $reason")
            emit(HousingLinkEvent.Warning(reason))
            return false
        }

        if (command is HousingCommand.SetVacuumMotor && command.enabled && !coverOpenSeen) {
            Log.w(TAG, "Refused motor start: no cover-open notification seen this session")
            emit(HousingLinkEvent.Warning(MOTOR_WITHOUT_COVER))
            return false
        }

        when (command) {
            HousingCommand.Disconnect, HousingCommand.Reconnect -> {
                val active = session ?: return false
                active.ended.complete(DisconnectCause.LocalClosed)
                scope.launch {
                    withContext(NonCancellable) { runCatching { active.transport.disconnect() } }
                }
                return true
            }
            else -> Unit
        }

        val active = session
        if (active == null) {
            emit(HousingLinkEvent.Warning("Housing not connected. Command ignored: $command"))
            return false
        }

        val requests = encoder.encode(command)
        if (requests.isEmpty()) return true

        val succeeded = runCatching {
            writeLock.withLock { execute(active.transport, requests) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Housing command $command failed", error)
            emit(HousingLinkEvent.Warning("Housing command failed: ${describe(error)}"))
            false
        }

        if (succeeded && command is HousingCommand.SetVacuumMotor) {
            if (command.enabled) armMotorWatchdog() else disarmMotorWatchdog()
        }
        return succeeded
    }

    /**
     * Unconditional backstop against a pump that never gets told to stop.
     *
     * The state machine's own 120 s motor timeout is evaluated when a barometric sample arrives,
     * so a notification stall — a dropped subscription, a wedged peripheral, a GATT queue that
     * timed out — would leave the motor running with nothing counting. This timer does not depend
     * on the housing saying anything at all.
     *
     * A vacuum pump stuck on is one of the few failures in this product that damages hardware, so
     * the backstop is deliberately dumber and more stubborn than the logic it protects.
     */
    private fun armMotorWatchdog() {
        motorBelievedRunning = true
        motorWatchdog?.cancel()
        motorWatchdog = scope.launch {
            delay(MOTOR_WATCHDOG_MS)
            Log.w(TAG, "Motor watchdog tripped after ${MOTOR_WATCHDOG_MS}ms — forcing pump off")
            emit(HousingLinkEvent.Warning(MOTOR_WATCHDOG_TRIPPED))
            forceMotorRest()
        }
    }

    private fun disarmMotorWatchdog() {
        motorBelievedRunning = false
        motorWatchdog?.cancel()
        motorWatchdog = null
    }

    /**
     * Drives the pump and valve to rest, bypassing the workflow.
     *
     * Stopping is always safe and is never gated: the guards in [send] exist to stop the motor
     * being *started* outside the state machine, and applying them to a stop would be exactly
     * backwards.
     */
    private suspend fun forceMotorRest() {
        val active = session ?: return
        val requests = encoder.encode(HousingCommand.SetVacuumMotor(enabled = false)) +
            encoder.encode(HousingCommand.SetSolenoidValve(open = false))
        runCatching {
            withContext(NonCancellable) {
                writeLock.withLock { execute(active.transport, requests) }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to force pump to rest", error)
        }
        motorBelievedRunning = false
    }

    /**
     * Reconciles pump state after a link drop.
     *
     * If the link died while the motor was believed to be running, the app has no idea what the
     * housing did in the interim and cannot have sent a stop. The safe assumption on reconnect is
     * that it is still pumping, so stop it unconditionally and let the diver restart the check.
     */
    private suspend fun reconcileMotorAfterReconnect() {
        if (!motorBelievedRunning) return
        Log.w(TAG, "Link returned with motor believed running — forcing pump off")
        emit(HousingLinkEvent.Warning(MOTOR_RECONCILED))
        motorWatchdog?.cancel()
        motorWatchdog = null
        forceMotorRest()
    }

    private suspend fun execute(
        transport: HousingTransport,
        requests: List<BleTransportRequest>,
    ): Boolean {
        var allSucceeded = true
        for (request in requests) {
            val characteristic = when (request) {
                is BleTransportRequest.Read -> request.characteristic
                is BleTransportRequest.Write -> request.characteristic
                is BleTransportRequest.Subscribe -> request.characteristic
            }
            if (characteristic !in transport.availableCharacteristics) {
                emit(HousingLinkEvent.Warning("${characteristic.label} is not available on this housing."))
                allSucceeded = false
                continue
            }

            val succeeded = when (request) {
                is BleTransportRequest.Write ->
                    transport.writeCharacteristic(characteristic, request.payload)
                is BleTransportRequest.Subscribe ->
                    transport.subscribe(characteristic)
                is BleTransportRequest.Read -> {
                    val value = transport.readCharacteristic(characteristic)
                    if (value != null && value.isNotEmpty()) {
                        emit(HousingLinkEvent.Notification(characteristic.shortHex, value))
                    }
                    value != null
                }
            }
            if (!succeeded) allSucceeded = false
        }
        return allSucceeded
    }

    /**
     * The reconnect supervisor.
     *
     * Every attempt is wrapped so no exception can escape and kill the loop. Backoff follows the
     * shared `BleConnectionMachine` schedule (immediate, 500ms, 1s, 2s, then 5s capped) and the
     * attempt counter resets whenever a session actually reached Ready, so a link that drops once
     * after an hour retries immediately rather than inheriting an old penalty.
     */
    private suspend fun supervise() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val result = try {
                runSession()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Housing link attempt failed", error)
                emit(HousingLinkEvent.Warning("Housing link error: ${describe(error)}"))
                SessionResult(reachedReady = false, kind = SessionResult.Kind.Retry)
            }

            when (result.kind) {
                SessionResult.Kind.Unavailable -> delay(PREREQUISITE_POLL_MS)
                SessionResult.Kind.PoweredOff -> {
                    attempt = 0
                    delay(POWERED_OFF_RESCAN_MS)
                }
                SessionResult.Kind.Retry -> {
                    if (result.reachedReady) attempt = 0
                    attempt += 1
                    delay(connectionMachine.reconnectDelay(attempt).toMillis())
                }
            }
        }
    }

    private suspend fun runSession(): SessionResult {
        BlePermissions.blockingReason(context)?.let { reason ->
            emit(HousingLinkEvent.Ble(BleSignal.Fail))
            emit(HousingLinkEvent.Warning(reason))
            return SessionResult(reachedReady = false, kind = SessionResult.Kind.Unavailable)
        }

        val transport = transportFactory(store.lastAddress)
        val current = Session(transport)
        session = current

        try {
            transport.setDisconnectListener { cause -> current.ended.complete(cause) }
            transport.setNotificationListener { characteristic, value ->
                observeCoverState(characteristic, value)
                emit(HousingLinkEvent.Notification(characteristic.shortHex, value))
            }

            emit(HousingLinkEvent.Ble(BleSignal.StartScan))
            val device = transport.scan(SCAN_WINDOW_MS)
                ?: return SessionResult(reachedReady = false, kind = SessionResult.Kind.Retry)

            emit(HousingLinkEvent.Ble(BleSignal.Connect))
            if (!transport.connect(device)) {
                emit(HousingLinkEvent.Warning("Could not connect to ${device.macAddress}."))
                return SessionResult(reachedReady = false, kind = SessionResult.Kind.Retry)
            }

            emit(HousingLinkEvent.Ble(BleSignal.DiscoverServices))
            val services = transport.discoverServices()
            reportDiscovery(transport, services)

            val available = transport.availableCharacteristics
            if (HousingCharacteristic.ButtonEvents !in available) {
                emit(
                    HousingLinkEvent.Warning(
                        "${device.macAddress} exposes no button characteristic " +
                            "(0x${HousingCharacteristic.ButtonEvents.shortHex}). Not a housing — ignoring.",
                    ),
                )
                return SessionResult(reachedReady = false, kind = SessionResult.Kind.Retry)
            }
            reportUnavailableFeatures(available)

            val deviceInfo = readDeviceInfo(transport, available)
            runAdvisoryChecks(device, deviceInfo)

            emit(HousingLinkEvent.Ble(BleSignal.Subscribe))
            if (!subscribeAll(transport, available)) {
                return SessionResult(reachedReady = false, kind = SessionResult.Kind.Retry)
            }
            primeSensorState(transport, available)

            val serial = deviceInfo[HousingCharacteristic.SerialNumber]
            val firmware = deviceInfo[HousingCharacteristic.FirmwareRevision]
            store.remember(device.macAddress, serial, firmware)
            emit(HousingLinkEvent.Identity(device.macAddress, serial, firmware))
            emit(HousingLinkEvent.Ble(BleSignal.Ready))
            reconcileMotorAfterReconnect()

            return when (current.ended.await()) {
                DisconnectCause.RemoteClosed -> {
                    emit(HousingLinkEvent.Ble(BleSignal.HousingPoweredOff))
                    emit(HousingLinkEvent.Warning("Housing powered off."))
                    SessionResult(reachedReady = true, kind = SessionResult.Kind.PoweredOff)
                }
                else -> {
                    emit(HousingLinkEvent.Ble(BleSignal.Disconnect))
                    SessionResult(reachedReady = true, kind = SessionResult.Kind.Retry)
                }
            }
        } finally {
            session = null
            withContext(NonCancellable) { closeQuietly(transport) }
        }
    }

    /**
     * Arms the motor guard the first time the housing says its suction cover is open.
     *
     * Decoding goes through [ProtocolParser] rather than reading `payload[0]` here — the cover byte
     * is inverted (`0x00` means OPEN) and a second, local copy of that convention is exactly how a
     * safety interlock ends up meaning the opposite of what it reads. A malformed packet leaves the
     * guard armed as it was: nothing about a bad byte is evidence that a cover opened.
     */
    private fun observeCoverState(characteristic: HousingCharacteristic, payload: ByteArray) {
        if (characteristic != HousingCharacteristic.CoverState) return
        val decoded = parser.decodeCoverState(payload)
        if (decoded is ParseResult.Success<SensorUpdate.CoverState> && decoded.value.open) {
            coverOpenSeen = true
        }
    }

    private suspend fun readDeviceInfo(
        transport: HousingTransport,
        available: Set<HousingCharacteristic>,
    ): Map<HousingCharacteristic, String> {
        val values = mutableMapOf<HousingCharacteristic, String>()
        for (characteristic in HousingBleProfile.deviceInfoReadOrder) {
            if (characteristic !in available) continue
            val payload = runCatching { transport.readCharacteristic(characteristic) }.getOrNull()
            if (payload == null || payload.isEmpty()) continue

            emit(HousingLinkEvent.Notification(characteristic.shortHex, payload))
            val text = payload.toString(Charsets.UTF_8).trimEnd(Char(0)).trim()
            if (text.isNotEmpty()) values[characteristic] = text
        }
        return values
    }

    /**
     * Reads the battery once on connect.
     *
     * The battery characteristic notifies on change, so without a priming read the HUD would show
     * an unknown level until the housing happened to move a percentage point.
     */
    private suspend fun primeSensorState(
        transport: HousingTransport,
        available: Set<HousingCharacteristic>,
    ) {
        for (characteristic in PRIMED_ON_CONNECT) {
            if (characteristic !in available) continue
            val payload = runCatching {
                transport.readCharacteristic(characteristic)
            }.getOrNull() ?: continue
            if (payload.isNotEmpty()) {
                emit(HousingLinkEvent.Notification(characteristic.shortHex, payload))
            }
        }
    }

    private suspend fun subscribeAll(
        transport: HousingTransport,
        available: Set<HousingCharacteristic>,
    ): Boolean {
        for (characteristic in HousingBleProfile.subscriptionOrder) {
            if (characteristic !in available) continue

            val subscribed = runCatching { transport.subscribe(characteristic) }.getOrDefault(false)
            if (subscribed) continue

            if (characteristic == HousingCharacteristic.ButtonEvents) {
                emit(
                    HousingLinkEvent.Warning(
                        "Housing button notifications could not be enabled. Reconnecting.",
                    ),
                )
                return false
            }
            emit(HousingLinkEvent.Warning("${characteristic.label} notifications unavailable."))
        }
        return true
    }

    /**
     * Runs the identity checks that are advisory rather than binding.
     *
     * Each mismatch is reported and then ignored. The housing in the diver's hands is the ground
     * truth; the hardcoded expectations are documentation of one firmware build.
     */
    private fun runAdvisoryChecks(
        device: DiscoveredDevice,
        deviceInfo: Map<HousingCharacteristic, String>,
    ) {
        advisory(identityVerifier.validateAdvertisingName(device.name))

        deviceInfo[HousingCharacteristic.ManufacturerName]?.let { manufacturer ->
            advisory(identityVerifier.validateManufacturer(manufacturer))
        }
        deviceInfo[HousingCharacteristic.FirmwareRevision]?.let { firmware ->
            advisory(identityVerifier.validateFirmwareVersion(firmware))
        }
        deviceInfo[HousingCharacteristic.SerialNumber]?.let { serial ->
            identityVerifier.checkMultiDeviceWarning(
                trustedIdentity = store.lastAddress,
                currentSerialNumber = store.lastSerialNumber,
                newSerialNumber = serial,
            )?.let { message -> emit(HousingLinkEvent.Warning(message)) }
        }
    }

    private fun advisory(result: HousingIdentityVerifier.VerificationResult) {
        if (result is HousingIdentityVerifier.VerificationResult.Rejected) {
            Log.w(TAG, "Advisory identity check: ${result.reason}")
            emit(HousingLinkEvent.Warning("Advisory: ${result.reason}"))
        }
    }

    /**
     * Dumps the discovered GATT tree once per connection.
     *
     * The vendor base UUID in the protocol document is malformed, so this dump is how a single
     * session with real hardware settles what the housing actually exposes.
     */
    private fun reportDiscovery(transport: HousingTransport, services: Set<String>) {
        Log.i(TAG, "GATT discovery: ${services.size} services")
        transport.discoveryReport.forEach { line -> Log.i(TAG, "GATT  $line") }

        val resolved = transport.availableCharacteristics
        emit(
            HousingLinkEvent.Warning(
                "GATT discovery: ${services.size} services, ${resolved.size} housing " +
                    "characteristics resolved. Full tree in logcat tag $TAG.",
            ),
        )
    }

    private fun reportUnavailableFeatures(available: Set<HousingCharacteristic>) {
        val missing = HousingCharacteristic.entries.filter { it !in available }
        if (missing.isEmpty()) return
        Log.i(TAG, "Housing lacks: ${missing.joinToString { it.label }}")
        emit(
            HousingLinkEvent.Warning(
                "Housing does not expose: ${missing.joinToString { it.label }}. " +
                    "Those features are unavailable.",
            ),
        )
    }

    private suspend fun closeQuietly(transport: HousingTransport?) {
        if (transport == null) return
        runCatching { transport.setNotificationListener(null) }
        runCatching { transport.setDisconnectListener(null) }
        runCatching { transport.disconnect() }
    }

    /**
     * Non-suspending emit so GATT callback threads never block on a slow collector.
     *
     * The buffer drops oldest on overflow: a stale telemetry sample is worth less than the button
     * press behind it.
     */
    private fun emit(event: HousingLinkEvent) {
        when (event) {
            is HousingLinkEvent.Ble -> lastSignal = event.signal
            is HousingLinkEvent.Notification ->
                if (event.characteristicShortHex != BUTTON_SHORT_HEX) {
                    telemetry[event.characteristicShortHex] = event.payload
                    // Arm the motor interlock here rather than in the notification listener: the
                    // read that primes state at connect does not pass through that listener, so a
                    // housing already sitting cover-open would offer the start prompt while the
                    // interlock still refused the pump.
                    HousingCharacteristic.from(event.characteristicShortHex)?.let {
                        observeCoverState(it, event.payload)
                    }
                }
            else -> Unit
        }
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "Dropped housing link event: $event")
        }
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private class Session(val transport: HousingTransport) {
        val ended = CompletableDeferred<DisconnectCause>()
    }

    private data class SessionResult(
        val reachedReady: Boolean,
        val kind: Kind,
    ) {
        enum class Kind {
            /** Permissions or the adapter are missing. Poll, do not back off. */
            Unavailable,

            /** Try the whole sequence again after the reconnect backoff. */
            Retry,

            /** The diver powered the housing down. Rescan slowly rather than spinning. */
            PoweredOff,
        }
    }

    private companion object {
        const val TAG = "HousingLink"

        val BUTTON_SHORT_HEX: String = HousingCharacteristic.ButtonEvents.shortHex

        /**
         * Telemetry safe to hand a resubscribing view model.
         *
         * Everything here either notifies only on change or notifies rarely, so replaying it
         * restores display state that would otherwise be blank. Pressure characteristics are
         * excluded on purpose — see [snapshot].
         */
        val REPLAYABLE_ON_RESUBSCRIBE = setOf(
            HousingCharacteristic.BatteryLevel,
            HousingCharacteristic.CoverState,
            HousingCharacteristic.WaterTemperature,
        )

        const val MOTOR_WITHOUT_COVER =
            "Vacuum motor refused: open the blue air-extraction cap first. " +
                "Pumping against a sealed housing can damage the pump."

        const val MOTOR_WATCHDOG_TRIPPED =
            "Vacuum pump stopped by safety timer — it ran longer than expected. " +
                "Check the cap and o-ring, then run the seal check again."

        const val MOTOR_RECONCILED =
            "Housing reconnected while the pump was running. Pump stopped for safety — " +
                "run the seal check again."

        /**
         * Hard ceiling on pump run time, independent of telemetry.
         *
         * Sits above the state machine's 120 s motor timeout so the normal path always wins and
         * this only fires when that path has been starved of pressure samples.
         */
        const val MOTOR_WATCHDOG_MS = 150_000L

        /**
         * Characteristics read once at connect to seed state that notifications alone cannot give.
         *
         * A notify characteristic only fires when its value *changes*, so anything already settled
         * when the link comes up stays invisible. That leaves the seal check unable to tell a
         * closed cap from an unknown one, and the depth reference with no ambient sample to latch,
         * until the diver happens to move something.
         *
         * Button events are deliberately excluded: reading that characteristic returns the last
         * byte the housing latched, and replaying it would fire a phantom press on every connect.
         */
        val PRIMED_ON_CONNECT = listOf(
            HousingCharacteristic.BatteryLevel,
            HousingCharacteristic.CoverState,
            // Water pressure BEFORE barometric, deliberately: the dry water sensor is the
            // cross-check that stops a held vacuum from being captured as surface ambient, so
            // it has to be in state before the first barometric sample is judged.
            HousingCharacteristic.WaterPressure,
            HousingCharacteristic.BarometricPressure,
            HousingCharacteristic.WaterTemperature,
        )

        /** Deep enough to hold a burst of button repeats plus telemetry without dropping. */
        const val EVENT_BUFFER = 128

        /** One active low-latency scan window per attempt, per the scan power budget. */
        const val SCAN_WINDOW_MS = 12_000L

        /** Permissions and the adapter can be granted or switched on while the app is running. */
        const val PREREQUISITE_POLL_MS = 2_000L

        /** A deliberately powered-off housing should not be scanned for at full rate. */
        const val POWERED_OFF_RESCAN_MS = 5_000L
    }
}
