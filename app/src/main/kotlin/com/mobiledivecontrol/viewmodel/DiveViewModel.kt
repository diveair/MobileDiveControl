package com.mobiledivecontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobiledivecontrol.DiveControlApp
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.ControlCommand
import com.mobiledivecontrol.core.ControlCore
import com.mobiledivecontrol.core.GalleryCommand
import com.mobiledivecontrol.core.HousingButtonEvent
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.ProcessingOutcome
import com.mobiledivecontrol.core.SafetyCommand
import com.mobiledivecontrol.core.SealConfidence
import com.mobiledivecontrol.core.SealState
import com.mobiledivecontrol.core.SensorUpdate
import com.mobiledivecontrol.platform.GalleryRepository
import com.mobiledivecontrol.platform.PhoneBatteryMonitor
import com.mobiledivecontrol.platform.ble.HousingFeatureFlags
import com.mobiledivecontrol.platform.ble.HousingLink
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

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

    private val sessionStore = CameraSessionStore(application)
    private val controlCore = ControlCore(initialState = sessionStore.restoreAppState())
    private val galleryRepository = GalleryRepository(application)
    private val diveApp: DiveControlApp? = application as? DiveControlApp
    private val housingLink: HousingLink? = diveApp?.housingLink
    private val phoneBatteryMonitor = PhoneBatteryMonitor(application)
    private val vacuumStore = com.mobiledivecontrol.platform.VacuumStore(application)

    /**
     * Read once, before [_introVisible] initialises below: a persisted hold that reached its
     * 3-minute mark is proof this diver has already been through the button lesson with a housing
     * that works, and the intro must not stand between them and a sealed camera.
     */
    private val persistedVacuum = vacuumStore.read()

    private val _state = MutableStateFlow(controlCore.state)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _effects = MutableStateFlow<List<PlatformEffect>>(emptyList())
    val effects: StateFlow<List<PlatformEffect>> = _effects.asStateFlow()

    private val _depthUnitMetric = MutableStateFlow(true)
    val depthUnitMetric: StateFlow<Boolean> = _depthUnitMetric.asStateFlow()

    /**
     * Whether the intro carousel is on screen.
     *
     * Scoped to this view model — deliberately not to the process. The foreground housing service
     * keeps the process alive long after the diver swipes the app away, so a process-lifetime
     * "seen it" flag meant closing and reopening the app never replayed the intro. The view model
     * dies with the activity and survives a configuration change, which is exactly the lifetime
     * the intro wants: replay on every real reopen, no replay on a rotation. While it is true the
     * housing buttons belong to the intro and nothing else — see [interceptForIntro].
     */
    private val _introVisible = MutableStateFlow(persistedVacuum == null)
    val introVisible: StateFlow<Boolean> = _introVisible.asStateFlow()

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
        collectHousingLink()
        collectPhoneBattery()
    }

    override fun onCleared() {
        phoneBatteryMonitor.stop()
        super.onCleared()
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
        phoneBatteryMonitor.start()
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

    /**
     * Pumps raw radio events into the core critical path.
     *
     * Payloads arrive as bytes and are decoded by `ControlCore`, never here: decoding outside the
     * core would skip packet validation, the button debounce and the input router, so a button
     * press would update the display without ever becoming a command.
     */
    private fun collectHousingLink() {
        val link = housingLink ?: return
        viewModelScope.launch {
            // The link outlives this view model, so by the time we subscribe it may already be
            // connected. `onSubscription` replays what it knows after the subscription exists but
            // before collection starts, which closes the window where a signal could slip past.
            link.events
                .onSubscription { link.snapshot().forEach { emit(it) } }
                .collect { event -> applyLinkEvent(event) }
        }
    }

    private fun applyLinkEvent(event: HousingLinkEvent) {
        when (event) {
            is HousingLinkEvent.Ble -> advanceBle(event.signal)
            is HousingLinkEvent.Notification ->
                onNotification(event.characteristicShortHex, event.payload)
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
        if (command is SafetyCommand) {
            HousingFeatureFlags.rejectionReason(command)?.let { reason ->
                surfaceWarning(reason)
                return
            }
        }
        val outcome = controlCore.dispatch(command)
        emitOutcome(outcome)
    }

    fun onButtonPayload(payload: ByteArray) {
        if (interceptForIntro(HousingCharacteristic.ButtonEvents.shortHex, payload)) return
        if (interceptForCapPrompt(HousingCharacteristic.ButtonEvents.shortHex, payload)) return
        val outcome = controlCore.handleButtonPayload(payload)
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

    fun onNotification(characteristic: String, payload: ByteArray) {
        if (interceptForIntro(characteristic, payload)) return
        if (interceptForCapPrompt(characteristic, payload)) return
        val outcome = controlCore.handleNotificationPayload(characteristic, payload)
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
     * Swallows housing button packets while the intro is up, and lets the first one end it.
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
        if (HousingCharacteristic.from(characteristic) != HousingCharacteristic.ButtonEvents) {
            return false
        }
        if (!_introVisible.value) {
            val sinceDismiss = System.currentTimeMillis() - introDismissedAtMs
            return sinceDismiss in 0 until INTRO_DISMISS_GUARD_MS
        }
        // Same bound the protocol parser applies. A malformed packet must not count as a press.
        if (payload.size != 1) return true

        android.util.Log.i(
            "DiveControl",
            "Intro dismissed by button byte 0x%02X".format(payload[0]),
        )
        dismissIntro()
        return true
    }

    /**
     * Ends the intro for the rest of the process.
     *
     * The flag lives on the application rather than here so an activity recreation — a rotation, a
     * theme change, a process-kept restart — does not put the diver back at the start of a carousel
     * they have already skipped.
     */
    fun dismissIntro() {
        if (!_introVisible.value) return
        introDismissedAtMs = System.currentTimeMillis()
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
        val show = _state.value.safety.sealState !in SEAL_ENGAGED_STATES
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

    /** One-shot: the intro may be reinstated at most once, when the boot record is refuted. */
    private var introReinstated: Boolean = false

    /** When this view model came up, for the intro-reinstatement window. */
    private val vmStartedAtMs: Long = System.currentTimeMillis()

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

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()

    fun updateSensor(update: SensorUpdate) {
        val outcome = controlCore.updateSensor(update)
        emitOutcome(outcome)
    }

    fun updateBattery(level: Int) {
        val outcome = controlCore.updateBatteryLevel(level)
        emitOutcome(outcome)
    }

    private class Ramp(var remaining: Int, val step: Int) {
        var job: kotlinx.coroutines.Job? = null
        var lastFedAtMs: Long = System.currentTimeMillis()
    }

    /**
     * One live ramp per setting. Same-direction requests ADD to the ticks still owed — a fast
     * wheel spin banks distance rather than cancelling its own momentum — while a direction
     * change cancels outright. All touches happen on the main dispatcher.
     */
    private val ramps = mutableMapOf<String, Ramp>()

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
                existing.lastFedAtMs = System.currentTimeMillis()
                return@forEach
            }
            existing?.job?.cancel()
            val fresh = Ramp(ramp.steps, ramp.step)
            ramps[ramp.settingId] = fresh
            fresh.job = viewModelScope.launch {
                while (fresh.remaining > 0) {
                    kotlinx.coroutines.delay(ramp.intervalMs)
                    // The hard rule: when the wheel stops, the value stops. Any ticks still
                    // owed are DISCARDED the moment the wheel goes quiet — banked distance
                    // must never keep the focus moving on its own.
                    if (System.currentTimeMillis() - fresh.lastFedAtMs > ramp.stopTimeoutMs) {
                        fresh.remaining = 0
                        break
                    }
                    // Fast but bounded drain: at most 3 ticks a frame (~180 steps/s). Enough
                    // that a quarter-turn at max sensitivity completes its full sweep inside
                    // the spin plus the timeout window, yet every step is still individually
                    // applied — an unbounded burst collapsed dozens of steps into one visible
                    // jump, which defeated the whole traversal contract.
                    var burst = minOf(fresh.remaining, ramp.maxTicksPerInterval)
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
     * Simulate a button press for UI testing without hardware.
     * Maps [HousingButtonEvent] to its wire byte and processes it.
     */
    /**
     * Bring the link to Ready without hardware, so the button simulator can actually drive the
     * control loop. Without this the simulator is inert exactly when it is needed — with no
     * housing attached, InputRouter refuses every simulated press ("Housing input is disabled"),
     * which is right for the housing but wrong for the bench. Debug-panel only; the real link
     * still governs itself through [advanceBle] from the BLE layer.
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
        onButtonPayload(byteArrayOf(wireByte.toByte()))
    }

    private fun emitOutcome(outcome: ProcessingOutcome) {
        android.util.Log.d("DiveControl", "emitOutcome: mode=${outcome.state.mode} cameraMode=${outcome.state.camera.activeMode} focusedZone=${outcome.state.camera.focusedZone} settingsEditing=${outcome.state.camera.settingsEditing} sliderEditTarget=${outcome.state.camera.sliderEditTarget} notes=${outcome.notes}")
        _state.value = outcome.state
        // The cap doorway's hardware exits: the cap visibly CAME OFF (a cover transition to open,
        // never the level — this housing latches stale "open" for whole sessions), or the shell
        // turned out to be holding vacuum all along. Either way the screen's ask is moot.
        if (_capPromptVisible.value) {
            val safety = outcome.state.safety
            // Strictly false→true. A first report after a fresh launch arrives as null→true and is
            // a LEVEL, not an event — treating it as "the cap just came off" closed this doorway
            // within a second of it being raised, every time the process had restarted.
            val coverJustOpened = safety.coverOpen == true && lastCoverOpen == false
            if (coverJustOpened || safety.sealState in SEAL_ENGAGED_STATES) {
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
        // A vacuum detected while the intro is still up ends the intro: the housing is sealed
        // and ready, and a button lesson standing in front of a working camera helps nobody.
        if (_introVisible.value &&
            (sealNow == SealState.LeakMonitoring || sealNow == SealState.Passed)
        ) {
            dismissIntro()
        }

        val safetyNow = outcome.state.safety

        // The mirror case: the intro was SKIPPED on the boot record's promise of a sealed
        // housing, and the first real reading just refuted that promise. The skip has lost its
        // justification, so the intro comes back — a diver holding a vented housing wants the
        // button lesson and the sealing flow, this launch, not the next one. One shot, and only
        // in the launch window: a housing switched on vented ten minutes into a session must
        // not push a tutorial over a camera already in use.
        if (!introReinstated && persistedVacuum != null &&
            !_introVisible.value && !_capPromptVisible.value &&
            safetyNow.verifiedVacuumKpa == null &&
            sealNow != SealState.LeakMonitoring && sealNow != SealState.Passed &&
            // A refuted record now renders as SEAL FAILED (TIME); the tutorial must not cover
            // that verdict. It returns after the diver acknowledges with UP.
            sealNow != SealState.Failed &&
            safetyNow.barometricPressureKpa != null &&
            System.currentTimeMillis() - vmStartedAtMs < INTRO_REINSTATE_WINDOW_MS
        ) {
            introReinstated = true
            _introVisible.value = true
        }
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
        sessionStore.save(outcome.state)
        if (outcome.effects.isNotEmpty()) {
            _effects.value = outcome.effects
            processGalleryEffects(outcome.effects)
            processHousingEffects(outcome.effects)
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
    private fun processHousingEffects(effects: List<PlatformEffect>) {
        val link = housingLink ?: return
        val commands = effects.filterIsInstance<PlatformEffect.ExecuteHousing>().map { it.command }
        if (commands.isEmpty()) return

        viewModelScope.launch {
            for (command in commands) {
                val rejection = HousingFeatureFlags.rejectionReason(command)
                if (rejection != null) {
                    surfaceWarning(rejection)
                    continue
                }
                link.send(command)
            }
        }
    }

    private fun processGalleryEffects(effects: List<PlatformEffect>) {
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
                    is PlatformEffect.LoadExifData -> {
                        val lines = galleryRepository.loadExifData(effect.item)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.SetExifLines(lines))
                        }
                    }
                    is PlatformEffect.DeleteGalleryItem -> {
                        galleryRepository.deleteItem(effect.item)
                        val gallery = controlCore.state.gallery
                        val items = galleryRepository.loadItems(gallery.tab, gallery.currentFolder)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.LoadItems(items))
                        }
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
                        galleryRepository.createFolder(effect.name)
                        val gallery = controlCore.state.gallery
                        val items = galleryRepository.loadItems(gallery.tab, gallery.currentFolder)
                        launch(Dispatchers.Main) {
                            dispatch(GalleryCommand.LoadItems(items))
                        }
                    }
                    else -> { /* handled elsewhere */ }
                }
            }
        }
    }

    private companion object {
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
        const val INTRO_REINSTATE_WINDOW_MS = 120_000L



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

