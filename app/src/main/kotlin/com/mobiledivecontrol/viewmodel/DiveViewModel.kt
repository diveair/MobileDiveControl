package com.mobiledivecontrol.viewmodel

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobiledivecontrol.DiveControlApp
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.Verification
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.ControlCommand
import com.mobiledivecontrol.core.ControlCore
import com.mobiledivecontrol.core.GalleryCommand
import com.mobiledivecontrol.core.HousingButtonEvent
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.SensorPacketSource
import com.mobiledivecontrol.core.ParseResult
import com.mobiledivecontrol.core.ProtocolParser
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.ProcessingOutcome
import com.mobiledivecontrol.core.SafetyCommand
import com.mobiledivecontrol.core.SealConfidence
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.core.SensorUpdate
import com.mobiledivecontrol.core.ShutOffPrompt
import com.mobiledivecontrol.core.pressureMonotonicMs
import com.mobiledivecontrol.platform.GalleryRepository
import com.mobiledivecontrol.platform.CompassHeadingMonitor
import com.mobiledivecontrol.platform.CompassReading
import com.mobiledivecontrol.platform.HeadingStore
import com.mobiledivecontrol.FirstRunOnboardingStore
import com.mobiledivecontrol.FirstLaunchSealStep
import com.mobiledivecontrol.startupRuntimePermissionGroups
import com.mobiledivecontrol.platform.PhoneBatteryMonitor
import com.mobiledivecontrol.platform.ble.BlePermissions
import com.mobiledivecontrol.platform.ble.HousingFeatureFlags
import com.mobiledivecontrol.platform.ble.HousingLink
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mobiledivecontrol.core.HeadingMath
import com.mobiledivecontrol.ui.camera.PointingGesture
import com.mobiledivecontrol.accessibility.PermissionDialogHousingBridge

/**
 * Bridges the pure Kotlin [ControlCore] (BLE communication layer)
 * to Jetpack Compose's reactive state system.
 *
 * The ViewModel holds the single source of truth [AppState] and
 * exposes it as a [StateFlow] for Compose observation. All state
 * mutations go through the core's pure functional reducers.
 *
 * Platform effects (camera commands, BLE writes, alerts) are
 * collected after each state transition and can be consumed
 * by the Android platform layer.
 */
class DiveViewModel(application: Application) : AndroidViewModel(application) {

    data class GalleryConsentRequest(
        val pendingIntent: PendingIntent,
        val effect: PlatformEffect,
        val successMessage: String,
    )

    private val sessionStore = CameraSessionStore(application)

    /**
     * The newest state awaiting persistence, or null when everything is written.
     *
     * Persisting used to happen inline on every dispatch — three [org.json.JSONObject] builds,
     * one of them over the whole ~110-entry settings map, on the main thread, up to 500 times a
     * second during a focus sweep. A [MutableStateFlow] conflates by nature, so a burst collapses
     * to a single write of the final value.
     *
     * Declared HERE, above the init block, and not beside the coroutine that drains it: Kotlin
     * initialises properties in declaration order, `init` reaches `emitOutcome`, and `emitOutcome`
     * writes this field — declared any lower it is still null when the first state is emitted.
     */
    private val pendingSave = MutableStateFlow<AppState?>(null)

    /**
     * One live ramp per setting. Same-direction requests ADD to the ticks still owed — a fast
     * wheel spin banks distance rather than cancelling its own momentum — while a direction
     * change cancels outright. All touches happen on the main dispatcher.
     *
     * Declared HERE, above the init block: Kotlin initialises properties in declaration order,
     * `init` reaches `emitOutcome`, and `emitOutcome` reads this map to cancel a ramp that has
     * landed on AF. Declared any lower it is still null on the first emission — the same trap
     * that caught `pendingSave`.
     */
    private val ramps = mutableMapOf<String, Ramp>()

    private val airAlertStore = com.mobiledivecontrol.platform.AirAlertStore(application)
    private val diveProfileStore = com.mobiledivecontrol.platform.DiveProfileStore(application)
    private val _diveLogWarning = MutableStateFlow<String?>(null)
    val diveLogWarning: StateFlow<String?> = _diveLogWarning.asStateFlow()
    private val restoredDiveProfile = runCatching { diveProfileStore.read() }.getOrElse {
        _diveLogWarning.value = "Saved dive log could not be read. New monitoring starts without prior history."
        com.mobiledivecontrol.core.DiveProfileState()
    }
    private val pendingDiveSave = MutableStateFlow<com.mobiledivecontrol.core.DiveProfileState?>(null)
    private val diveStopNotifier = com.mobiledivecontrol.platform.DiveStopNotifier(application)
    private val housingShutdownPrefs = application.getSharedPreferences("housing_shutdown", android.content.Context.MODE_PRIVATE)
    private var safetyStopMotionRunning = false
    private var safetyStopMotionBaseline: com.mobiledivecontrol.core.DiveProfileState? = null
    private val sealStore = com.mobiledivecontrol.platform.HousingSealStore(application)
    private var displayedSeal: Banner? = null
    private var windowFocused = false
    fun setSealBannerDisplayed(banner: Banner?) { displayedSeal = banner }
    fun setSealWindowFocused(focused: Boolean) { windowFocused = focused }
    fun interruptSurfaceTest() {
        val safety = controlCore.state.safety
        if (safety.sealMonitor.verification == Verification.Testing || safety.sealState == SealState.Vacuuming)
            dispatch(SafetyCommand.CancelVacuumCheck)
    }
    private val _depthUnitMetric = MutableStateFlow(true)
    val depthUnitMetric: StateFlow<Boolean> = _depthUnitMetric.asStateFlow()
    private val controlCore = ControlCore(
        initialState = sessionStore.restoreAppState().let { restored -> restored.copy(diveProfile = restoredDiveProfile,
            housingStandby = housingShutdownPrefs.getBoolean("standby", false),
            airAlerts = com.mobiledivecontrol.core.AirAlertState(settings = airAlertStore.read()),
            safety = restored.safety.copy(
                sealMonitor = sealStore.read(),
                vacuumReleasedPrompt = housingShutdownPrefs.getBoolean("pending", false),
                vacuumReleaseDisconnectObserved = housingShutdownPrefs.getBoolean("disconnected", false))) },
        triggerSafetyStopAtSurface = false,
        displayedSealBanner = { displayedSeal.takeIf { windowFocused } },
        useMetricDepth = { _depthUnitMetric.value },
    )
    private val galleryRepository = GalleryRepository(application)
    private val diveApp: DiveControlApp? = application as? DiveControlApp
    private val housingLink: HousingLink? = diveApp?.housingLink
    private var shutOffPromptTimer: Job? = null
    private val phoneBatteryMonitor = PhoneBatteryMonitor(application)
    private val vacuumStore = com.mobiledivecontrol.platform.VacuumStore(application)
    private val compassMonitor = CompassHeadingMonitor(application)
    private var compassRequested = false
    private val headingStore = HeadingStore(application)
    private val firstRunOnboardingStore = FirstRunOnboardingStore(application)
    private val introButtonParser = ProtocolParser()
    val needsPermissionWelcome = firstRunOnboardingStore.beginLaunch(
        permissionsGranted = startupRuntimePermissionGroups().all { group ->
            group.all { (permission, _) -> application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
        },
    )
    private val _firstLaunchSealStep = MutableStateFlow(
        if (needsPermissionWelcome) FirstLaunchSealStep.WaitingForDialogs else FirstLaunchSealStep.Done,
    )
    val firstLaunchSealStep = _firstLaunchSealStep.asStateFlow()

    fun updateSealInstructionWindow(ready: Boolean) {
        _firstLaunchSealStep.update { it.permissionWindowChanged(ready) }
    }

    fun finishIntroPrompt(shown: FirstLaunchSealStep) {
        _firstLaunchSealStep.compareAndSet(shown, shown.afterPrompt(_state.value.bleConnectionState))
    }

    val compassReading: StateFlow<CompassReading> = compassMonitor.reading
    private val _targetHeading = MutableStateFlow(headingStore.read())
    val targetHeading: StateFlow<Double?> = _targetHeading.asStateFlow()

    /** Read once to restore earned seal confidence; it no longer decides onboarding visibility. */
    private val persistedVacuum = vacuumStore.read()

    private val _state = MutableStateFlow(controlCore.state)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _effects = MutableStateFlow<List<PlatformEffect>>(emptyList())
    val effects: StateFlow<List<PlatformEffect>> = _effects.asStateFlow()

    private val _runtimePermissionRequest = MutableStateFlow<RuntimePermissionRequest?>(null)
    val runtimePermissionRequest: StateFlow<RuntimePermissionRequest?> =
        _runtimePermissionRequest.asStateFlow()
    private var nextRuntimePermissionRequestId = 1L
    private var permissionBlockedHousingEffects: List<PlatformEffect> = emptyList()

    private val _galleryConsentRequest = MutableStateFlow<GalleryConsentRequest?>(null)
    val galleryConsentRequest: StateFlow<GalleryConsentRequest?> = _galleryConsentRequest.asStateFlow()

    /**
     * Whether the intro carousel is on screen.
     *
     * First install teaches permissions and housing attachment. Later starts are requested by
     * the housing link only after more than an hour disconnected. Seal state never starts or
     * dismisses the animation. While visible it owns housing input — see [interceptForIntro].
     */
    private val _introVisible = MutableStateFlow(
        (needsPermissionWelcome || !firstRunOnboardingStore.isComplete()) && !housingShutdownPrefs.getBoolean("pending", false),
    )
    val introVisible: StateFlow<Boolean> = _introVisible.asStateFlow()

    /**
     * Whether the NAUTICASE welcome card is on screen.
     *
     * Raised on every app launch (each new app process; a rotation or other Activity recreation
     * keeps this ViewModel and does not replay it), and it always comes first: Android's
     * permission prompts are held back until it finishes, so a first launch reads
     * welcome -> permissions -> insert and seal on the animated housing. Lowered by [finishWelcome].
     */
    private val _welcomeVisible = MutableStateFlow(true)
    val welcomeVisible: StateFlow<Boolean> = _welcomeVisible.asStateFlow()

    fun finishWelcome() {
        _welcomeVisible.value = false
    }

    /** Debug-build preview hook: replays the welcome card without clearing app data. */
    fun previewWelcome() {
        _welcomeVisible.value = true
    }

    /**
     * Whether the remove-the-blue-cap doorway is on screen.
     *
     * Raised once, when the intro ends — the moment the diver stops learning buttons and starts
     * sealing — and only if there is anything left to seal: a housing already holding vacuum, or
     * one with its cap already off, skips straight past it. It also lowers itself the instant
     * the housing reports either of those states, because the diver obeying the *housing* must
     * count exactly the same as the diver obeying the screen.
     *
     * Declared here, above the `init` block, on purpose: `emitOutcome` reads it, the phone-battery
     * StateFlow emits synchronously into `emitOutcome` during construction, and Kotlin initialises
     * properties in declaration order — below `init` this is a guaranteed launch NPE.
     */
    private val _capPromptVisible = MutableStateFlow(false)
    val capPromptVisible: StateFlow<Boolean> = _capPromptVisible.asStateFlow()

    private var capPromptDismissedAtMs: Long = 0L

    /**
     * When the intro was dismissed, for the one place the repeat guard still earns its keep.
     *
     * A held housing button repeats at roughly 15 Hz once the firmware's auto-repeat kicks in. The
     * press that dismisses the intro is swallowed, but its repeats would land on a camera that has
     * just appeared — so the diver's "get me past this screen" press would also fire the shutter or
     * walk the control strip. Everything else about the old per-step guard is gone with the
     * per-step advance.
     */
    private var introDismissedAtMs: Long = 0L

    private val airReceiver = com.mobiledivecontrol.platform.ble.AirPressureReceiver(application,
        housingAddress = { diveApp?.housingStore?.lastAddress },
        emit = { command -> viewModelScope.launch { dispatch(com.mobiledivecontrol.core.DiveSettingsCommand.Alerts(command)) } })

    init {
        // Before any telemetry can arrive: the persisted verified reading has to be in the
        // safety state before the first barometric sample makes the adoption decision.
        persistedVacuum?.let { record ->
            val tier = SealConfidence.entries.getOrElse(record.confidenceOrdinal) {
                SealConfidence.ManufacturerMinimum
            }
            emitOutcome(
                controlCore.primeVerifiedVacuum(
                    record.kpa,
                    tier,
                    record.startedAtEpochMs,
                    record.recordedAtEpochMs,
                ),
            )
        }
        housingLink?.setStandby(controlCore.state.housingStandby)
        collectHousingLink()
        collectPhoneBattery()
        housingLink?.setSensorReadsPaused(controlCore.state.safety.sealState == SealState.Vacuuming)
        diveApp?.setPressureCapture(controlCore.state.mode == AppMode.Diagnostics, com.mobiledivecontrol.core.STANDARD_SURFACE_PRESSURE_KPA)
        collectPendingSaves()
        collectDiveProfile()
        val airSettings = controlCore.state.airAlerts.settings
        if (!controlCore.state.housingStandby && airSettings.source == com.mobiledivecontrol.core.AirSource.Receiver && airSettings.mapping.verified) {
            airReceiver.execute(com.mobiledivecontrol.core.AirReceiverRequest(0, com.mobiledivecontrol.core.AirReceiverAction.Connect), airSettings)
        }
    }

    override fun onCleared() {
        airReceiver.close()
        airAlertStore.save(controlCore.state.airAlerts.settings)
        diveApp?.setPressureCapture(false)
        if (bluetoothReceiverRegistered) {
            runCatching {
                getApplication<android.app.Application>().unregisterReceiver(bluetoothStateReceiver)
            }
        }
        // Whatever the rate limiter has not written yet goes to disk now, synchronously, so
        // teardown can never lose a setting the diver changed.
        flushCameraSettings()
        runCatching { diveProfileStore.save(safetyStopMotionBaseline ?: controlCore.state.diveProfile) }
        diveStopNotifier.close()
        phoneBatteryMonitor.stop()
        compassMonitor.stop()
        super.onCleared()
    }

    private fun collectDiveProfile() {
        viewModelScope.launch {
            while (true) {
                if (controlCore.state.housingStandby) state.first { !it.housingStandby }
                kotlinx.coroutines.delay(500)
                if (controlCore.state.housingStandby) continue
                val previousState = controlCore.state
                val outcome = controlCore.tickDiveProfile()
                if (outcome.state !== previousState || outcome.effects.isNotEmpty()) emitOutcome(outcome)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            pendingDiveSave.collect { profile ->
                if (profile == null) return@collect
                runCatching { diveProfileStore.save(profile) }
                    .onSuccess { _diveLogWarning.value = null }
                    .onFailure { _diveLogWarning.value = "Dive log could not be saved on this phone." }
                pendingDiveSave.compareAndSet(profile, null)
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    private fun collectPendingSaves() {
        viewModelScope.launch(Dispatchers.IO) {
            // Rate-limited but always progressing: write, then hold off briefly while the flow
            // conflates whatever arrives. Deliberately NOT a trailing debounce — a diver spinning
            // the dial for thirty seconds would then persist nothing until they stopped.
            pendingSave.collect { state ->
                if (state == null) return@collect
                flushCameraSettings()
                kotlinx.coroutines.delay(SAVE_MIN_INTERVAL_MS)
            }
        }
    }

    /** Read the latest pending snapshot under the same lock for background and lifecycle writes. */
    @Synchronized
    fun flushCameraSettings() {
        val latest = pendingSave.value ?: return
        runCatching { sessionStore.save(latest) }
            .onSuccess { pendingSave.compareAndSet(latest, null) }
            .onFailure { android.util.Log.w("DiveControl", "Could not persist camera settings", it) }
    }

    /**
     * Mirrors the phone's own charge into [AppState].
     *
     * This is platform telemetry, not control input, so it does not go through the core reducer:
     * the phone battery can never change a mode, a command or a safety decision, and pushing it
     * through the critical path would put a system broadcast on the same route as a button press
     * for no benefit. It is merged on top of every core transition instead, so a reducer output
     * can never blank it back to unknown.
     */
    private fun collectPhoneBattery() {
        if (!controlCore.state.housingStandby) phoneBatteryMonitor.start()
        viewModelScope.launch {
            phoneBatteryMonitor.percent.collect { percent ->
                if (percent != null) {
                    emitOutcome(controlCore.updatePhoneBattery(percent))
                }
            }
        }
    }

    fun toggleDepthUnit() {
        _depthUnitMetric.value = !_depthUnitMetric.value
    }

    fun setCompassMonitoring(active: Boolean) {
        compassRequested = active
        if (active) compassMonitor.start() else compassMonitor.stop()
    }

    /**
     * Live AE/AWB readings from the capture pipe, throttled to ~2 Hz by the controller. Same
     * non-critical-path footing as [collectPhoneBattery]: telemetry that feeds the HUD's Auto
     * readouts and the auto-to-manual seeding, and can never change a mode or issue a command.
     *
     * Two deliberate differences from [dispatch]:
     *  - The controller invokes this from CameraX's camera executor, and everything the core
     *    touches is main-confined (ControlCore.state is an unsynchronized var; a camera-thread
     *    read-modify-write racing a wheel detent would silently revert housing input). The
     *    launch hops to the main dispatcher first.
     *  - It bypasses [emitOutcome]: metered values are never persisted, so refilling
     *    [pendingSave] every 500 ms would write SharedPreferences continuously for nothing, and
     *    this reducer path can produce no effects to run.
     */
    fun updateMeteredExposure(metered: com.mobiledivecontrol.core.MeteredExposure) {
        viewModelScope.launch {
            _state.value = controlCore.updateMeteredExposure(metered).state
        }
    }

    /** Replace the persisted target with the ray selected by a stable pointing gesture. */
    fun setTargetHeadingFromPoint(gesture: PointingGesture) {
        // The analyzer calls from its CameraX executor; ControlCore is deliberately main-confined.
        viewModelScope.launch {
            // A frame submitted just before the HUD disappeared can finish recognition afterwards.
            if (!compassRequested || controlCore.state.camera.recording) return@launch
            val cameraHeading = compassReading.value.headingDegrees
            if (cameraHeading == null) {
                surfaceWarning("Hold the camera level enough to acquire a compass heading.")
                return@launch
            }
            setTargetHeading(
                HeadingMath.targetFromImageRay(
                    cameraHeadingDegrees = cameraHeading,
                    normalizedX = gesture.normalizedX,
                    horizontalFovDegrees = gesture.horizontalFovDegrees,
                ),
            )
        }
    }

    /** Housing menu action: store exactly where the camera is pointing now. */
    private fun trackCurrentHeading() {
        if (controlCore.state.camera.recording) return
        val heading = compassReading.value.headingDegrees
        if (heading == null) {
            surfaceWarning("Compass heading unavailable — hold the camera level and try again.")
            return
        }
        setTargetHeading(heading)
    }

    private fun setTargetHeading(degrees: Double) {
        val normalized = HeadingMath.normalize(degrees)
        headingStore.write(normalized)
        _targetHeading.value = normalized
        android.util.Log.i("DiveHeading", "Tracked heading set to ${normalized.toInt()} degrees magnetic")
    }

    /**
     * Pumps raw radio events into the core critical path.
     *
     * Payloads arrive as bytes and are decoded by `ControlCore`, never here: decoding outside the
     * core would skip packet validation, the button debounce and the input router, so a button
     * press would update the display without ever becoming a command.
     */
    private fun collectHousingLink() {
        val app = diveApp ?: return
        viewModelScope.launch {
            app.housingEventsForDisplay().collect(::applyLinkEvent)
        }
    }

    private fun applyLinkEvent(event: HousingLinkEvent) {
        val sleeping = controlCore.state.housingStandby
        if (sleeping && event !is HousingLinkEvent.Ble) {
            if (event is HousingLinkEvent.Notification) onNotification(event.characteristicShortHex, event.payload,
                event.source, event.receivedAtEpochMs, event.receivedAtMonotonicMs)
            return
        }
        when (event) {
            is HousingLinkEvent.Ble -> {
                if (sleeping && event.signal == BleSignal.StartScan &&
                    controlCore.state.bleConnectionState == com.mobiledivecontrol.core.BleConnectionState.Scanning) return
                advanceBle(event.signal)
                if (sleeping && !controlCore.state.housingStandby) {
                    housingLink?.snapshot()?.filterIsInstance<HousingLinkEvent.Notification>()?.forEach(::applyLinkEvent)
                }
                if (!benchLinkForced && event.signal == BleSignal.Ready &&
                    housingLink?.consumeStartupRequest() == true && !firstRunOnboardingStore.isComplete()
                ) {
                    _capPromptVisible.value = false
                    _introVisible.value = true
                }
            }
            is HousingLinkEvent.Notification ->
                onNotification(
                    event.characteristicShortHex, event.payload, event.source,
                    event.receivedAtEpochMs, event.receivedAtMonotonicMs,
                )
            is HousingLinkEvent.Warning -> surfaceWarning(event.message)
            is HousingLinkEvent.Identity -> android.util.Log.i(
                "DiveControl",
                "Housing identity: address=${event.address} serial=${event.serial} firmware=${event.firmware}",
            )
        }
    }

    /**
     * Surfaces a link advisory without inventing new state plumbing.
     *
     * The next core transition overwrites it, which is the intended lifetime: an advisory
     * describes the moment it was raised, not a persistent condition.
     */
    private fun surfaceWarning(message: String) {
        android.util.Log.w("DiveControl", "Housing link warning: $message")
        _state.value = _state.value.copy(lastWarning = message)
    }

    /**
     * The single entry point for commands from the input router and the UI.
     *
     * Raw safety passthroughs are refused here rather than downstream because this is the last
     * point at which the *origin* of a hardware write is still visible. Once the reducer has turned
     * a command into a `PlatformEffect.ExecuteHousing`, one that skipped the seal-check workflow
     * looks exactly like one the state machine produced.
     */
    fun dispatch(command: ControlCommand) {
        if (controlCore.state.housingStandby) return
        if (command is SafetyCommand) {
            HousingFeatureFlags.rejectionReason(command)?.let { reason ->
                surfaceWarning(reason)
                return
            }
        }
        val outcome = controlCore.dispatch(command)
        emitOutcome(outcome)
    }

    /**
     * Whether the PHONE's Bluetooth radio is on.
     *
     * Without this the link layer cannot tell "the housing is dead" from "the radio is off", and it
     * reported the former — telling a diver to charge a housing that is fine and sitting right
     * there, while the actual fix was two taps away in quick settings. A scan that cannot run is
     * not evidence about the housing.
     */
    private val bluetoothAdapter: android.bluetooth.BluetoothAdapter? =
        (getApplication<android.app.Application>()
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter

    private val _bluetoothEnabled = MutableStateFlow(
        BlePermissions.isAdapterEnabled(getApplication()),
    )
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                // Do not trust an exported broadcast's extras. Sampling the real adapter makes a
                // spoofed intent harmless while still accepting Bluetooth's privileged broadcast.
                refreshBluetoothState()
            }
        }
    }

    /**
     * Registered here rather than in an init block so it runs AFTER the receiver it registers.
     * Bluetooth is a highly privileged framework app but does not broadcast as the system UID.
     * Android's RECEIVER_NOT_EXPORTED therefore drops its state broadcasts on affected devices;
     * RECEIVER_EXPORTED is required here. The receiver performs no action from intent data and
     * samples the adapter itself, so third-party broadcasts cannot forge the state.
     */
    private val bluetoothReceiverRegistered: Boolean = run {
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                getApplication(),
                bluetoothStateReceiver,
                android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { error ->
            android.util.Log.e("DiveBluetooth", "Bluetooth state receiver registration failed", error)
        }.isSuccess
    }

    fun refreshBluetoothState() {
        val enabled = runCatching { bluetoothAdapter?.isEnabled == true }
            .onFailure { error ->
                android.util.Log.w("DiveBluetooth", "Could not read Bluetooth adapter state", error)
            }
            .getOrDefault(false)
        if (_bluetoothEnabled.value != enabled) {
            android.util.Log.i(
                "DiveBluetooth",
                "Phone Bluetooth radio changed to ${if (enabled) "ON" else "OFF"}",
            )
            _bluetoothEnabled.value = enabled
        }
    }

    fun onButtonPayload(payload: ByteArray, source: SensorPacketSource = SensorPacketSource.Notification) {
        if (controlCore.state.housingStandby) return
        if (PermissionDialogHousingBridge.handleButtonPayload(payload)) return
        if (interceptForIntro(HousingCharacteristic.ButtonEvents.shortHex, payload)) return
        if (interceptForCapPrompt(HousingCharacteristic.ButtonEvents.shortHex, payload)) return
        val outcome = controlCore.handleNotificationPayload(HousingCharacteristic.ButtonEvents.shortHex, payload, source = source)
        // One line per housing press, carrying what the press did to focus: the whole
        // input->value half of the control loop, readable live from logcat.
        val camera = outcome.state.camera
        val focusKey = camera.settingValues.keys.firstOrNull { it.endsWith(".manual_focus") }
        android.util.Log.d(
            "DiveInput",
            "byte=0x%02X focus=%s routed=%s".format(
                payload.firstOrNull() ?: 0,
                focusKey?.let { camera.settingValues[it] } ?: "?",
                outcome.notes.isEmpty(),
            ),
        )
        emitOutcome(outcome)
    }

    fun onNotification(
        characteristic: String,
        payload: ByteArray,
        source: com.mobiledivecontrol.core.SensorPacketSource = com.mobiledivecontrol.core.SensorPacketSource.Notification,
        receivedAtEpochMs: Long = System.currentTimeMillis(),
        receivedAtMonotonicMs: Long = com.mobiledivecontrol.core.pressureMonotonicMs(),
    ) {
        if (controlCore.state.housingStandby) {
            if (source == SensorPacketSource.Notification && HousingCharacteristic.from(characteristic) == HousingCharacteristic.ButtonEvents) {
                val before = controlCore.state
                val outcome = controlCore.handleNotificationPayload(characteristic, payload,
                    java.time.Instant.ofEpochMilli(receivedAtEpochMs), source, receivedAtMonotonicMs)
                if (outcome.state !== before || outcome.effects.isNotEmpty()) emitOutcome(outcome)
            }
            return
        }
        if (safetyStopMotionRunning && HousingCharacteristic.from(characteristic) == HousingCharacteristic.WaterPressure) return
        if (HousingCharacteristic.from(characteristic) == HousingCharacteristic.ButtonEvents &&
            PermissionDialogHousingBridge.handleButtonPayload(payload)
        ) return
        if (interceptForIntro(characteristic, payload)) return
        if (interceptForCapPrompt(characteristic, payload)) return
        val outcome = controlCore.handleNotificationPayload(
            characteristic, payload, java.time.Instant.ofEpochMilli(receivedAtEpochMs),
            source, receivedAtMonotonicMs,
        )
        emitOutcome(outcome)
    }

    fun advanceBle(signal: BleSignal) {
        if (benchLinkForced) {
            // A bench link is latched: ignore the real radio's scan/reconnect churn, which
            // would otherwise drop input back to disabled a few hundred ms after every
            // simulated connect. Tapping LINK again hands control back to the real link.
            return
        }
        val outcome = controlCore.advanceBle(signal)
        emitOutcome(outcome)
    }

    // ---------------------------------------------------------------------------------------
    // Intro carousel
    // ---------------------------------------------------------------------------------------

    /**
     * Swallows housing button packets while the intro is up, and lets the first one end it after
     * permission onboarding is complete.
     *
     * The interception lives here rather than in `InputRouter` because the intro is not a control
     * mode: the core's job is to turn a button into a camera or safety action, and adding a
     * screen-specific bypass to the router would put presentation state on the critical path that
     * every real press has to walk through.
     *
     * Only button packets are taken. Battery, pressure, temperature and cover-state notifications
     * still reach the core, so the safety telemetry the diver depends on keeps updating behind the
     * intro and the seal state cannot go stale while someone watches it.
     *
     * The guard window after dismissal swallows the auto-repeat of the very press that dismissed
     * the intro. Without it the diver's "skip this" press also lands on the camera.
     *
     * @return true when the packet was consumed and must not reach [ControlCore].
     */
    private fun interceptForIntro(characteristic: String, payload: ByteArray): Boolean {
        if (foregroundStopOwnsOk(characteristic, payload)) return false
        if (HousingCharacteristic.from(characteristic) != HousingCharacteristic.ButtonEvents) {
            return false
        }
        val welcome = _welcomeVisible.value
        val sealStep = _firstLaunchSealStep.value
        if (sealStep != FirstLaunchSealStep.PowerOn &&
            System.currentTimeMillis() - introDismissedAtMs in 0 until INTRO_DISMISS_GUARD_MS) return true
        if (welcome || (_introVisible.value && sealStep != FirstLaunchSealStep.Done)) {
            val button = introButtonParser.decodeButtonPacket(payload)
            if (button is ParseResult.Success) {
                val event = button.value.event
                if (welcome && event !is HousingButtonEvent.Unknown) {
                    finishWelcome()
                    introDismissedAtMs = System.currentTimeMillis()
                } else if (!welcome) {
                    val next = sealStep.onButton(event)
                    if (next != sealStep && _firstLaunchSealStep.compareAndSet(sealStep, next)) {
                        introDismissedAtMs = System.currentTimeMillis()
                    }
                }
            }
            return true
        }
        if (!_introVisible.value) return false
        // Same bound the protocol parser applies. A malformed packet must not count as a press.
        if (payload.size != 1) return true

        // A connected housing reaches this branch in the brief beat before the next Android
        // permission prompt is launched. That press belongs to permission onboarding; treating it
        // as "skip intro" drops the only explanation and makes the app look bricked. Once the
        // native prompt owns the screen, PermissionDialogHousingBridge consumes these packets
        // before they arrive here.
        // This flow is intentionally runtime-only. Accessibility popup control is not a runtime
        // grant and must never keep a visible carousel from accepting its promised button press.
        if (_missingPermissions.value.isNotEmpty()) {
            android.util.Log.i(
                "DiveControl",
                "Intro retained during permission setup for button byte 0x%02X".format(payload[0]),
            )
            return true
        }

        android.util.Log.i(
            "DiveControl",
            "Intro dismissed by button byte 0x%02X".format(payload[0]),
        )
        dismissIntro()
        return true
    }

    /**
     * Completes the first-install walkthrough. The no-backup marker survives ordinary launches and
     * app updates but is removed by uninstall/clear-data, which is exactly the definition of first
     * launch the user sees.
     */
    fun dismissIntro() {
        if (!_introVisible.value) return
        introDismissedAtMs = System.currentTimeMillis()
        _firstLaunchSealStep.value = FirstLaunchSealStep.Done
        firstRunOnboardingStore.markComplete()
        _introVisible.value = false
        maybeShowCapPrompt()
    }

    // ---------------------------------------------------------------------------------------
    // Sealing hand-off
    // ---------------------------------------------------------------------------------------

    fun dismissCapPrompt() {
        if (!_capPromptVisible.value) return
        capPromptDismissedAtMs = System.currentTimeMillis()
        _capPromptVisible.value = false
    }

    private fun maybeShowCapPrompt() {
        // Deliberately NOT gated on the cover byte: this housing has reported a stale "open" for
        // whole sessions at a time, and a doorway that trusts that level either never appears or
        // dies the instant telemetry arrives. The only reliable skip is a seal already engaged —
        // pumping, holding or passed — which the pressure-based detection establishes honestly.
        lastCoverOpen = _state.value.safety.coverOpen
        val show = _state.value.safety.sealMonitor.banner == Banner.Cap
        if (show) capPromptRaisedAtMs = System.currentTimeMillis()
        android.util.Log.i(
            "DiveControl",
            "CapPrompt decision: show=$show seal=${_state.value.safety.sealState} " +
                "cover=${_state.value.safety.coverOpen} dismissedFlag=${_state.value.safety.checkDismissed}",
        )
        _capPromptVisible.value = show
    }

    private var capPromptRaisedAtMs: Long = 0L

    /** Cover state at the last look, so the doorway reacts to the cap *coming off*, not to a level. */
    private var lastCoverOpen: Boolean? = null

    /** Last reading written to [vacuumStore], so a 2-5 Hz sensor cannot hammer SharedPreferences. */
    private var lastPersistedVacuumKpa: Double? = null

    /**
     * Whether [vacuumStore] currently holds a record — seeded from the boot read, not just from
     * this session's own writes. The distinction is the whole point: a record inherited from a
     * previous session must be clearable too, or it outlives its vacuum.
     */
    private var recordInStore: Boolean = persistedVacuum != null

    private var lastPersistedTier: SealConfidence? = null

    /** Seal state at the last look, so a hard-verified vacuum being vented re-raises the doorway. */
    private var lastSealState: SealState = SealState.Unknown

    /**
     * Swallows button packets while the cap doorway is up. UP walks through it, per its own
     * instruction; everything else is consumed so a stray press cannot reach a camera nobody
     * can see. Telemetry passes untouched — the cover-open and vacuum-detected notifications
     * are the doorway's other two exits and they arrive through [onNotification].
     */
    private fun interceptForCapPrompt(characteristic: String, payload: ByteArray): Boolean {
        if (foregroundStopOwnsOk(characteristic, payload)) return false
        if (HousingCharacteristic.from(characteristic) != HousingCharacteristic.ButtonEvents) {
            return false
        }
        if (!_capPromptVisible.value) {
            val sinceDismiss = System.currentTimeMillis() - capPromptDismissedAtMs
            return sinceDismiss in 0 until INTRO_DISMISS_GUARD_MS
        }
        if (payload.size != 1) return true
        // Armed only after a beat. The press that dismissed the intro is usually still repeating
        // when this doorway is raised — the housing re-sends a held button every ~60-90 ms and the
        // intro's own guard is shorter than a human press — and an UP that walks through a door
        // the diver has not yet seen is exactly the bug this screen exists to prevent.
        val sinceRaised = System.currentTimeMillis() - capPromptRaisedAtMs
        if (sinceRaised < CAP_PROMPT_ARM_MS) return true
        if (payload[0].toInt() and 0xFF == DOWN_WIRE_BYTE) dismissCapPrompt()
        return true
    }

    /** Permissions still outstanding, phrased by purpose rather than by Android's names. */
    fun setMissingPermissions(missing: List<String>) {
        _missingPermissions.value = missing
    }

    /** Raises a fresh event even if the same capability was denied on the previous attempt. */
    fun requestRuntimePermissions(needs: Set<RuntimePermissionNeed>) {
        if (needs.isEmpty()) return
        val active = _runtimePermissionRequest.value
        if (active?.needs == needs) return
        _runtimePermissionRequest.value = RuntimePermissionRequest(
            id = nextRuntimePermissionRequestId++,
            needs = needs,
        )
    }

    /**
     * Completes the Activity-owned native prompt and resumes only work that is safe to replay.
     * Camera shutter/record commands are intentionally not replayed: a permission decision must
     * never take a photo or start recording without a second deliberate housing press.
     */
    fun resolveRuntimePermissionRequest(id: Long, granted: Boolean) {
        if (_runtimePermissionRequest.value?.id != id) return
        _runtimePermissionRequest.value = null
        val housingEffects = permissionBlockedHousingEffects
        permissionBlockedHousingEffects = emptyList()
        if (!granted) return
        if (housingEffects.isNotEmpty()) processHousingEffects(housingEffects)
    }

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()

    fun updateSensor(update: SensorUpdate) {
        if (controlCore.state.housingStandby) return
        val outcome = controlCore.updateSensor(update)
        emitOutcome(outcome)
    }

    private fun foregroundStopOwnsOk(characteristic: String, payload: ByteArray): Boolean =
        (_state.value.diveProfile.stopExpanded || _state.value.diveProfile.continuationPending) &&
            HousingCharacteristic.from(characteristic) == HousingCharacteristic.ButtonEvents &&
            payload.size == 1 && payload[0] == 0x50.toByte()

    /** One explicit, finite hardware-UI exercise through the normal required-stop path. */
    fun runSafetyStopMotion() {
        val app = getApplication<Application>()
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || safetyStopMotionRunning) return
        val baseline = controlCore.state
        if (baseline.diveProfile.active != null) return
        safetyStopMotionBaseline = baseline.diveProfile
        safetyStopMotionRunning = true
        pendingDiveSave.value = baseline.diveProfile
        emitOutcome(controlCore.restoreDiveMonitoring(com.mobiledivecontrol.core.DiveProfileState(
            settings = baseline.diveProfile.settings,
            logs = baseline.diveProfile.logs, alertSequence = baseline.diveProfile.alertSequence), 0.0, null))
        viewModelScope.launch {
            try {
                val target = controlCore.state.diveProfile.settings.stopDepthMeters.toDouble()
                // A 30 m profile requires the stop. Cross its real configured depth on ascent,
                // show red, recover through yellow to green, then end at the surface once.
                val depths = listOf(0.0, 2.0, 30.0, target + 1.0, target) + List(24) { target } +
                    List(64) { target - (it + 1) * 0.025 } + List(16) { target - 1.6 } +
                    List(64) { target - 1.6 + (it + 1) * 0.025 } + List(32) { target } + 0.0
                for (depth in depths) {
                    updateSensor(SensorUpdate.WaterPressure(com.mobiledivecontrol.core.STANDARD_SURFACE_PRESSURE_KPA +
                        depth * com.mobiledivecontrol.core.FRESHWATER_KPA_PER_METER))
                    kotlinx.coroutines.delay(250L)
                }
            } finally {
                val restored = baseline.diveProfile.copy(alertSequence = controlCore.state.diveProfile.alertSequence,
                    lastAlert = null)
                val outcome = controlCore.restoreDiveMonitoring(restored, baseline.maxDepthMeters,
                    baseline.safety.waterPressureKpa)
                safetyStopMotionRunning = false
                emitOutcome(outcome)
                safetyStopMotionBaseline = null
            }
        }
    }
    fun updateBattery(level: Int) {
        val outcome = controlCore.updateBatteryLevel(level)
        emitOutcome(outcome)
    }

    /**
     * Pacing lives on the ramp, not captured in its coroutine.
     *
     * The first detent of a spin comes from rest, so its velocity-matched interval is slack —
     * and the coroutine used to close over that first effect and hold it for the whole quarter
     * turn, draining at roughly a quarter of the intended rate. The lens then kept walking long
     * after the diver's hand stopped. Refreshing these on every merge lets the events that
     * follow re-pace the drain to the speed actually being turned.
     */
    private class Ramp(var remaining: Int, val step: Int) {
        @Volatile var intervalMs: Long = 16L
        @Volatile var maxTicksPerInterval: Int = 1
        @Volatile var stopTimeoutMs: Long = 250L
        /** Rungs per second, re-derived from the OUTSTANDING debt at every wheel event. */
        @Volatile var rungsPerSecond: Double = 0.0
        /** Fractional accumulator, so a sub-frame rate still advances rather than rounding away. */
        var carry: Double = 0.0
        var job: kotlinx.coroutines.Job? = null
        var lastFedAtMs: Long = System.currentTimeMillis()
    }


    /**
     * Drains a [PlatformEffect.RampSetting] as real single-tick commands, one per interval, so
     * the focus value genuinely visits every step at the requested rate. Each tick re-enters
     * the core as [CameraCommand.NudgeSetting] — state, readout and lens all walk together.
     */
    private fun processRampEffects(effects: List<PlatformEffect>) {
        effects.filterIsInstance<PlatformEffect.RampSetting>().forEach { ramp ->
            val existing = ramps[ramp.settingId]
            if (existing != null && existing.step == ramp.step && existing.job?.isActive == true) {
                existing.remaining += ramp.steps
                // Adopt the newest pacing: the spin has a speed now, where the first detent
                // only had a standing start.
                existing.intervalMs = ramp.intervalMs
                existing.maxTicksPerInterval = ramp.maxTicksPerInterval
                existing.stopTimeoutMs = ramp.stopTimeoutMs
                // From the DEBT, not from this detent alone: pacing on the newest credit
                // under-delivers every gap, so a fast spin compounds a lag it never repays.
                existing.rungsPerSecond = existing.remaining * 1000.0 / ramp.spanMs.coerceAtLeast(1L)
                existing.lastFedAtMs = System.currentTimeMillis()
                return@forEach
            }
            existing?.job?.cancel()
            val fresh = Ramp(ramp.steps, ramp.step)
            fresh.intervalMs = ramp.intervalMs
            fresh.maxTicksPerInterval = ramp.maxTicksPerInterval
            fresh.stopTimeoutMs = ramp.stopTimeoutMs
            fresh.rungsPerSecond = fresh.remaining * 1000.0 / ramp.spanMs.coerceAtLeast(1L)
            ramps[ramp.settingId] = fresh
            fresh.job = viewModelScope.launch {
                while (fresh.remaining > 0) {
                    kotlinx.coroutines.delay(fresh.intervalMs)
                    // The hard rule: when the wheel stops, the value stops. Any ticks still
                    // owed are DISCARDED the moment the wheel goes quiet — banked distance
                    // must never keep the focus moving on its own.
                    if (System.currentTimeMillis() - fresh.lastFedAtMs > fresh.stopTimeoutMs) {
                        fresh.remaining = 0
                        break
                    }
                    // Fast but bounded drain: at most MAX_TICKS_PER_FRAME ticks a frame,
                    // ~500 steps/s. Enough that a quarter turn at max sensitivity completes its
                    // full sweep inside the spin plus the timeout window, yet every step is
                    // still individually applied — an unbounded burst collapsed dozens of steps
                    // into one visible jump, which defeated the whole traversal contract.
                    // Spend the debt at the derived rate, carrying the fraction so slow rates
                    // still advance. One wake-up per frame keeps the SoC's idle time intact:
                    // sub-frame delivery is invisible on a 60 Hz preview and only costs power.
                    fresh.carry += fresh.rungsPerSecond * fresh.intervalMs / 1000.0
                    var burst = minOf(
                        fresh.remaining,
                        fresh.carry.toInt(),
                        MAX_RUNGS_PER_FRAME,
                    )
                    if (burst <= 0) continue
                    fresh.carry -= burst
                    while (burst > 0 && fresh.remaining > 0) {
                        fresh.remaining -= 1
                        burst -= 1
                        emitOutcome(
                            controlCore.dispatch(CameraCommand.NudgeSetting(ramp.settingId, ramp.step)),
                        )
                    }
                }
                if (ramps[ramp.settingId] === fresh) ramps.remove(ramp.settingId)
            }
        }
    }

    fun clearEffects() {
        _effects.value = emptyList()
    }

    fun updatePermission(permission: com.mobiledivecontrol.core.PermissionKind, granted: Boolean) {
        val outcome = controlCore.updatePermission(permission, granted)
        emitOutcome(outcome)
    }

    /**
     * Explicit LINK toggle for testing connection-dependent behavior without hardware.
     */
    fun simulateHousingLink() {
        if (benchLinkForced) {
            benchLinkForced = false
            android.util.Log.d("DiveControl", "simulateHousingLink: released; the real link governs again")
            emitOutcome(controlCore.advanceBle(BleSignal.Disconnect))
            return
        }
        benchLinkForced = true
        listOf(
            BleSignal.StartScan,
            BleSignal.Connect,
            BleSignal.DiscoverServices,
            BleSignal.Subscribe,
            BleSignal.Ready,
        ).forEach { signal -> emitOutcome(controlCore.advanceBle(signal)) }
        android.util.Log.d("DiveControl", "simulateHousingLink: link forced to Ready for bench testing")
    }

    /** Debug-panel latch: hold the simulated link up despite the real radio (see LINK button). */
    private var benchLinkForced = false

    fun simulateButton(event: HousingButtonEvent) {
        android.util.Log.d("DiveControl", "simulateButton: event=$event")
        val wireByte = when (event) {
            HousingButtonEvent.Right -> 0x10
            HousingButtonEvent.Shutter -> 0x20
            HousingButtonEvent.Up -> 0x30
            HousingButtonEvent.Left -> 0x40
            HousingButtonEvent.Ok -> 0x50
            HousingButtonEvent.BackOrSafety -> 0x60
            HousingButtonEvent.Down -> 0x61
            HousingButtonEvent.ZoomIn -> 0x70
            HousingButtonEvent.ZoomOut -> 0x80
            is HousingButtonEvent.Unknown -> event.rawValue.toInt()
        }
        onButtonPayload(byteArrayOf(wireByte.toByte()), source = SensorPacketSource.Simulation)
    }

    /** One emission avoids replaying every mode (and rebuilding every camera graph) after stress. */
    internal fun restoreCameraStressSnapshot(camera: CameraState, mode: AppMode) {
        emitOutcome(controlCore.restoreCameraSnapshot(camera, mode))
    }

    private fun emitOutcome(outcome: ProcessingOutcome) {
        val sleeping = outcome.state.housingStandby
        val standbyChanged = sleeping != _state.value.housingStandby
        val saveCamera = !sleeping || standbyChanged || _state.value.camera !== outcome.state.camera
        if (outcome.state.safety.vacuumReleasedPrompt != _state.value.safety.vacuumReleasedPrompt ||
            outcome.state.safety.vacuumReleaseDisconnectObserved != _state.value.safety.vacuumReleaseDisconnectObserved ||
            standbyChanged) {
            housingShutdownPrefs.edit().putBoolean("standby", sleeping).putBoolean("pending", outcome.state.safety.vacuumReleasedPrompt)
                .putBoolean("disconnected", outcome.state.safety.vacuumReleaseDisconnectObserved).apply()
        }
        // Every dispatch reaches here — up to 500 a second during a focus sweep — and the
        // interpolated string was being built even though nothing reads it in the field.
        if (android.util.Log.isLoggable("DiveControl", android.util.Log.DEBUG)) {
            android.util.Log.d("DiveControl", "emitOutcome: mode=${outcome.state.mode} cameraMode=${outcome.state.camera.activeMode} focusedZone=${outcome.state.camera.focusedZone} settingsEditing=${outcome.state.camera.settingsEditing} sliderEditTarget=${outcome.state.camera.sliderEditTarget} notes=${outcome.notes}")
        }
        when {
            "Camera Permission: Disabled" in outcome.notes ->
                requestRuntimePermissions(setOf(RuntimePermissionNeed.Camera))
            "Microphone Permission: Disabled" in outcome.notes ->
                requestRuntimePermissions(setOf(RuntimePermissionNeed.Microphone))
        }
        // Yield the shared GATT queue before submitting valve/motor writes. Restore fallback
        // polling only after the workflow leaves Vacuuming and the motor-off write completes.
        housingLink?.setSensorReadsPaused(outcome.state.safety.sealState == SealState.Vacuuming)
        if (_state.value.safety.sealState != outcome.state.safety.sealState ||
            _state.value.safety.warning != outcome.state.safety.warning) {
            android.util.Log.i("DivePump", "state=${outcome.state.safety.sealState} pressureKpa=${outcome.state.safety.barometricPressureKpa} baselineKpa=${outcome.state.safety.baselinePressureKpa} warning=${outcome.state.safety.warning} notes=${outcome.notes}")
        }
        diveApp?.setPressureCapture(outcome.state.mode == AppMode.Diagnostics, com.mobiledivecontrol.core.STANDARD_SURFACE_PRESSURE_KPA)
        val previousProfile = _state.value.diveProfile
        val nextProfile = outcome.state.diveProfile
        if (previousProfile != nextProfile) {
            if (!safetyStopMotionRunning) pendingDiveSave.value = nextProfile
            if (previousProfile.alertSequence != nextProfile.alertSequence) {
                nextProfile.lastAlert?.let(diveStopNotifier::notify)
            }
        }
        val oldAir = _state.value.airAlerts
        val newAir = outcome.state.airAlerts
        val audibleAir = newAir.simulation?.air ?: newAir
        val gasWarning = audibleAir.gasWarning?.takeIf {
            it.visible && !com.mobiledivecontrol.core.airAlertPresentationBlocked(outcome.state)
        }
        diveStopNotifier.updateAirWarning(gasWarning?.level,
            "${audibleAir.sessionId}:${audibleAir.tankNumber}:${audibleAir.gasWarningSequence}:${newAir.simulation != null}")
        if (oldAir.settings != newAir.settings) {
            airAlertStore.save(newAir.settings)
            airReceiver.configure(newAir.settings)
        }
        if (_state.value.safety.sealMonitor !== outcome.state.safety.sealMonitor)
            sealStore.update(_state.value.safety.sealMonitor, outcome.state.safety.sealMonitor)
        val previousLink = _state.value.bleConnectionState
        val previousPrompt = _state.value.shutOffPrompt
        _state.value = outcome.state
        val prompt = outcome.state.shutOffPrompt
        if (previousPrompt !== prompt) {
            shutOffPromptTimer?.cancel()
            shutOffPromptTimer = if (prompt is ShutOffPrompt.MenuPressed) {
                housingLink?.checkLinkAfterPowerPress()
                viewModelScope.launch(Dispatchers.Main) {
                    delay(prompt.hideAtMonotonicMs - pressureMonotonicMs())
                    emitOutcome(controlCore.expireShutOffPrompt(prompt))
                }
            } else null
        }
        val stopBeforeRadioStandby = standbyChanged && sleeping && outcome.effects.any { it is PlatformEffect.ExecuteHousing }
        if (standbyChanged) {
            if (stopBeforeRadioStandby) {
                val stopWrites = processHousingEffects(outcome.effects)
                viewModelScope.launch {
                    stopWrites?.join()
                    if (controlCore.state.housingStandby) housingLink?.setStandby(true)
                }
            } else housingLink?.setStandby(sleeping)
            if (sleeping) {
                phoneBatteryMonitor.stop()
                setCompassMonitoring(false)
                airReceiver.disconnect()
                ramps.values.forEach { it.job?.cancel() }
                ramps.clear()
                _effects.value = emptyList()
            } else {
                phoneBatteryMonitor.start()
                if (newAir.settings.source == com.mobiledivecontrol.core.AirSource.Receiver && newAir.settings.mapping.verified)
                    airReceiver.execute(com.mobiledivecontrol.core.AirReceiverRequest(0, com.mobiledivecontrol.core.AirReceiverAction.Connect), newAir.settings)
            }
        }
        if (previousLink != outcome.state.bleConnectionState) {
            _firstLaunchSealStep.update { it.linkChanged(outcome.state.bleConnectionState) }
        }
        if (newAir.receiverRequest != oldAir.receiverRequest) newAir.receiverRequest?.let {
            airReceiver.execute(it, newAir.settings)
        }
        // The cap doorway's hardware exits: the cap visibly CAME OFF (a cover transition to open,
        // never the level — this housing latches stale "open" for whole sessions), or the shell
        // turned out to be holding vacuum all along. Either way the screen's ask is moot.
        if (_capPromptVisible.value) {
            val safety = outcome.state.safety
            // Strictly false→true. A first report after a fresh launch arrives as null→true and is
            // a LEVEL, not an event — treating it as "the cap just came off" closed this doorway
            // within a second of it being raised, every time the process had restarted.
            val coverJustOpened = safety.coverOpen == true && lastCoverOpen == false
            if (coverJustOpened || safety.sealMonitor.banner != Banner.Cap) {
                _capPromptVisible.value = false
            }
        }
        lastCoverOpen = outcome.state.safety.coverOpen

        // A vacuum released after the hard verify is a deliberate opening, and the machine has
        // quietly stepped back to Unknown. At the surface the machine raises the VACUUM RELEASED
        // banner instead (`vacuumReleasedPrompt`) — the cap is already off, and a doorway asking
        // the diver to remove it would be nonsense. Only the depth case still gets the doorway.
        // An early leak goes to Failed instead and never passes through here.
        val sealNow = outcome.state.safety.sealState
        if (!_introVisible.value && !_capPromptVisible.value &&
            sealNow == SealState.Unknown &&
            !outcome.state.safety.vacuumReleasedPrompt &&
            (lastSealState == SealState.LeakMonitoring || lastSealState == SealState.Passed)
        ) {
            maybeShowCapPrompt()
        }
        // Persistence of the earned trust: once the hold has passed the hard verify, keep the
        // stored reading fresh (throttled — SharedPreferences is not a 5 Hz sink); the moment the
        // hold ends for any reason, forget it. A record that outlives its vacuum would greet the
        // NEXT vacuum with unearned confidence.
        val safetyNow = outcome.state.safety

        // Seal revalidation is handled by the seal UI. Telemetry must never replay
        // the startup animation over an already-running camera session.

        val holdVerified = (sealNow == SealState.Passed) &&
            safetyNow.sealConfidence >= SealConfidence.Provisional &&
            safetyNow.leakMonitoringStartedAtEpochMs != null
        val baroNow = safetyNow.barometricPressureKpa
        if (holdVerified && baroNow != null) {
            val tierChanged = lastPersistedTier != safetyNow.sealConfidence
            if (tierChanged || lastPersistedVacuumKpa == null ||
                kotlin.math.abs(baroNow - lastPersistedVacuumKpa!!) > PERSIST_DELTA_KPA
            ) {
                vacuumStore.record(
                    baroNow,
                    safetyNow.sealConfidence.ordinal,
                    safetyNow.leakMonitoringStartedAtEpochMs,
                )
                recordInStore = true
                lastPersistedVacuumKpa = baroNow
                lastPersistedTier = safetyNow.sealConfidence
            }
        } else if (!holdVerified && recordInStore &&
            // Still undecided: the boot record is waiting for its first pressure sample, and
            // clearing it now would erase last session's earned trust before the housing even
            // got a chance to prove it. The first sample decides — adoption keeps it, a
            // refuting reading nulls it, and either way this guard opens.
            safetyNow.verifiedVacuumKpa == null &&
            sealNow != SealState.Passed && sealNow != SealState.LeakMonitoring
        ) {
            // A record whose hold is over — released this session, or written by a PREVIOUS
            // session and refuted by today's first reading. Both must clear the store, or the
            // stale record skips the intro and primes a phantom vacuum on every launch until
            // its 7-day expiry. Field-found: the tutorial vanished for good on a vented housing.
            vacuumStore.clear()
            recordInStore = false
            lastPersistedVacuumKpa = null
            lastPersistedTier = null
        }
        lastSealState = sealNow
        if (saveCamera) pendingSave.value = outcome.state
        // AF IS A TERMINUS. Crossing into it must consume the gesture: any distance still owed
        // from the approach would otherwise keep dispatching, and each queued tick leaving AF
        // walks onward — 1.000 into AF and straight out to 0.000 in one motion. Landing on AF
        // therefore cancels the ramp, so leaving again takes a fresh, separate input.
        if (ramps.isNotEmpty()) {
            val values = outcome.state.camera.settingValues
            val landedOnAf = ramps.keys.filter { values[it] == "AF" }
            landedOnAf.forEach { id -> ramps.remove(id)?.job?.cancel() }
        }
        if (outcome.effects.isNotEmpty()) {
            _effects.value = outcome.effects
            if (PlatformEffect.TrackCurrentHeading in outcome.effects) trackCurrentHeading()
            processGalleryEffects(outcome.effects)
            if (!stopBeforeRadioStandby) processHousingEffects(outcome.effects)
            processRampEffects(outcome.effects)
        }
    }

    /**
     * Forwards housing writes to the radio.
     *
     * Every write is re-checked against [HousingFeatureFlags] here even though `HousingLink.send`
     * checks again. The duplication is deliberate: a refusal has to be reported to the diver, and
     * a command that silently does nothing is the failure mode the product forbids.
     */
    private fun processHousingEffects(effects: List<PlatformEffect>): kotlinx.coroutines.Job? {
        val commands = effects.filterIsInstance<PlatformEffect.ExecuteHousing>().map { it.command }
        if (commands.isEmpty()) return null

        val application = getApplication<Application>()
        if (!BlePermissions.allGranted(application)) {
            permissionBlockedHousingEffects = effects.takeUnless { commands.contains(com.mobiledivecontrol.core.HousingCommand.SetVacuumMotor(true)) } ?: emptyList()
            requestRuntimePermissions(setOf(RuntimePermissionNeed.Bluetooth))
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Notification visibility is recoverable independently; never suppress an already-safe
            // housing write merely because Android hid the foreground-service notification.
            requestRuntimePermissions(setOf(RuntimePermissionNeed.Notifications))
        }

        val link = housingLink ?: return null

        val pumpStart = controlCore.state.safety.motorStartedAtEpochMs
        val startsPump = com.mobiledivecontrol.core.HousingCommand.SetVacuumMotor(true) in commands
        val permitPump: ((Double) -> Boolean)? = if (startsPump) { depth ->
            windowFocused && controlCore.pumpWritePermitted(pumpStart, depth)
        } else null
        return viewModelScope.launch {
            for (command in commands) {
                val rejection = HousingFeatureFlags.rejectionReason(command)
                if (rejection != null) {
                    surfaceWarning(rejection)
                    continue
                }
                val sent = link.send(command, permitPump)
                if (!sent && startsPump) {
                    if (pumpStart == controlCore.state.safety.motorStartedAtEpochMs) interruptSurfaceTest()
                    break
                }
            }
        }
    }

    private fun processGalleryEffects(effects: List<PlatformEffect>) {
        // Gate BEFORE launching. This is reached for every outcome carrying any effect at all,
        // which a ladder spin makes hundreds of times a second; each one launched an IO
        // coroutine whose body fell straight through its `else -> { }`.
        val hasGalleryWork = effects.any {
                it is PlatformEffect.LoadGalleryItems ||
                it is PlatformEffect.LoadGalleryMoveTargets ||
                it is PlatformEffect.LoadRecordingSaveLocations ||
                it is PlatformEffect.LoadExifData ||
                it is PlatformEffect.DeleteGalleryItem ||
                it is PlatformEffect.DeleteGalleryAlbum ||
                it is PlatformEffect.MoveGalleryItem ||
                it is PlatformEffect.RenameGalleryItem ||
                it is PlatformEffect.DeleteGalleryFolder ||
                it is PlatformEffect.CreateGalleryFolder
        }
        if (!hasGalleryWork) return

        viewModelScope.launch(Dispatchers.IO) {
            for (effect in effects) {
                when (effect) {
                    is PlatformEffect.LoadGalleryItems -> {
                        val gallery = controlCore.state.gallery
                        val items = galleryRepository.loadItems(gallery.tab, gallery.currentFolder)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.LoadItems(items))
                        }
                    }
                    is PlatformEffect.LoadGalleryMoveTargets -> {
                        val albums = galleryRepository.loadItems(
                            com.mobiledivecontrol.core.GalleryTab.Folders,
                            currentFolder = null,
                        )
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.LoadMoveTargets(albums))
                        }
                    }
                    is PlatformEffect.LoadRecordingSaveLocations -> {
                        val locations = galleryRepository.loadRecordingSaveLocations()
                        launch(Dispatchers.Main) {
                            dispatch(CameraCommand.LoadRecordingSaveLocations(locations))
                        }
                    }
                    is PlatformEffect.LoadExifData -> {
                        val lines = galleryRepository.loadExifData(effect.item)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.SetExifLines(lines))
                        }
                    }
                    is PlatformEffect.DeleteGalleryItem -> {
                        val result = galleryRepository.deleteItem(effect.item)
                        completeGalleryMutation(result, "Deleted ${effect.item.name}", effect)
                    }
                    is PlatformEffect.DeleteGalleryAlbum -> {
                        val result = galleryRepository.deleteAlbum(effect.album)
                        completeGalleryMutation(result, "Deleted album ${effect.album.name}", effect)
                    }
                    is PlatformEffect.MoveGalleryItem -> {
                        val result = galleryRepository.moveItem(effect.item, effect.targetAlbum)
                        completeGalleryMutation(
                            result,
                            "Moved ${effect.item.name} to ${effect.targetAlbum.name}",
                            effect,
                        )
                    }
                    is PlatformEffect.RenameGalleryItem -> {
                        val result = galleryRepository.renameItem(effect.item, effect.newName)
                        completeGalleryMutation(result, "Renamed ${effect.item.name}", effect)
                    }
                    is PlatformEffect.DeleteGalleryFolder -> {
                        galleryRepository.deleteFolder(effect.path)
                        val gallery = controlCore.state.gallery
                        val items = galleryRepository.loadItems(gallery.tab, gallery.currentFolder)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.LoadItems(items))
                        }
                    }
                    is PlatformEffect.CreateGalleryFolder -> {
                        completeGalleryMutation(
                            result = galleryRepository.createFolder(effect.name),
                            successMessage = "Created album ${effect.name}",
                            effect = effect,
                        )
                    }
                    else -> { /* handled elsewhere */ }
                }
            }
        }
    }

    /** Runs on the gallery IO coroutine and commits UI state only after MediaStore confirms success. */
    private fun completeGalleryMutation(
        result: Result<Unit>,
        successMessage: String,
        effect: PlatformEffect,
        allowConsentRequest: Boolean = true,
    ) {
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (allowConsentRequest && error is SecurityException) {
                val pendingIntent = runCatching {
                    when (effect) {
                        is PlatformEffect.DeleteGalleryAlbum ->
                            galleryRepository.createAlbumDeleteRequest(effect.album)
                        else -> galleryMutationItem(effect)?.let { item ->
                            galleryRepository.createConsentRequest(
                                item = item,
                                delete = effect is PlatformEffect.DeleteGalleryItem,
                                cause = error,
                            )
                        }
                    }
                }.getOrNull()
                if (pendingIntent != null) {
                    _galleryConsentRequest.value = GalleryConsentRequest(
                        pendingIntent = pendingIntent,
                        effect = effect,
                        successMessage = successMessage,
                    )
                    return
                }
            }
            val message = error?.message ?: "The media operation failed."
            viewModelScope.launch(Dispatchers.Main) {
                dispatch(GalleryCommand.OperationFailed(message))
            }
            return
        }

        val gallery = controlCore.state.gallery
        val items = galleryRepository.loadItems(gallery.tab, gallery.currentFolder)
        viewModelScope.launch(Dispatchers.Main) {
            dispatch(GalleryCommand.LoadItems(items))
            dispatch(GalleryCommand.OperationSucceeded(successMessage))
        }
    }

    /** Called by MainActivity after Android's per-file MediaStore confirmation dialog closes. */
    fun resolveGalleryConsent(granted: Boolean) {
        val request = _galleryConsentRequest.value ?: return
        _galleryConsentRequest.value = null
        if (!granted) {
            dispatch(GalleryCommand.OperationFailed("Media permission was not granted."))
            return
        }

        // createDeleteRequest performs the deletion itself before RESULT_OK is delivered.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (request.effect is PlatformEffect.DeleteGalleryItem ||
                request.effect is PlatformEffect.DeleteGalleryAlbum)
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                if (request.effect is PlatformEffect.DeleteGalleryAlbum) {
                    galleryRepository.finishAlbumDeletion(request.effect.album)
                }
                completeGalleryMutation(
                    result = Result.success(Unit),
                    successMessage = request.successMessage,
                    effect = request.effect,
                    allowConsentRequest = false,
                )
            }
            return
        }

        // createWriteRequest (and Android 10's recoverable action) grants access; retry the write.
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (val effect = request.effect) {
                is PlatformEffect.DeleteGalleryItem -> galleryRepository.deleteItem(effect.item)
                is PlatformEffect.DeleteGalleryAlbum -> galleryRepository.deleteAlbum(effect.album)
                is PlatformEffect.MoveGalleryItem -> galleryRepository.moveItem(effect.item, effect.targetAlbum)
                is PlatformEffect.RenameGalleryItem -> galleryRepository.renameItem(effect.item, effect.newName)
                else -> Result.failure(IllegalArgumentException("Unsupported gallery operation."))
            }
            completeGalleryMutation(
                result = result,
                successMessage = request.successMessage,
                effect = request.effect,
                allowConsentRequest = false,
            )
        }
    }

    private fun galleryMutationItem(effect: PlatformEffect) = when (effect) {
        is PlatformEffect.DeleteGalleryItem -> effect.item
        is PlatformEffect.MoveGalleryItem -> effect.item
        is PlatformEffect.RenameGalleryItem -> effect.item
        else -> null
    }

    private companion object {
        /**
         * Floor between persistence writes. Short enough that a crash loses at most this much,
         * long enough that a 500 Hz dial spin collapses into two writes a second instead of 500.
         */
        const val SAVE_MIN_INTERVAL_MS = 400L

        /**
         * Ceiling on rungs delivered in one frame — the heat budget, mirroring the reducer's own
         * cap. Eight per 16 ms is 500 core dispatches a second, which is what the catalog
         * memoisation work was sized against.
         */
        const val MAX_RUNGS_PER_FRAME = 20

        /** Comfortably longer than any firmware auto-repeat interval, short enough to be unfelt. */
        const val INTRO_DISMISS_GUARD_MS = 350L

        /**
         * How long the cap doorway ignores buttons after being raised. Longer than any plausible
         * hold-repeat tail from the press that dismissed the intro, shorter than a deliberate
         * "read it, pressed UP" response.
         */
        const val CAP_PROMPT_ARM_MS = 900L

        /** Re-persist the verified reading only when it has moved this much. */
        const val PERSIST_DELTA_KPA = 0.3

        /** How long after launch a refuted boot record may still bring the intro back. */



        /** The wire byte for a short Up press — the cap doorway's own instruction. */
        const val DOWN_WIRE_BYTE = 0x61

        /**
         * Seal states in which the housing is already sealed or actively being sealed, so the
         * remove-the-cap doorway has nothing left to ask.
         */
        val SEAL_ENGAGED_STATES = setOf(
            SealState.Vacuuming,
            SealState.MotorStopping,
            SealState.WaitingForCoverClosed,
            SealState.LeakMonitoring,
            SealState.Passed,
        )
    }
}
