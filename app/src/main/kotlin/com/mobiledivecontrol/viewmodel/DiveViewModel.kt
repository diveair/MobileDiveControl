package com.mobiledivecontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.ControlCommand
import com.mobiledivecontrol.core.ControlCore
import com.mobiledivecontrol.core.HousingButtonEvent
import com.mobiledivecontrol.core.PlatformEffect
import com.mobiledivecontrol.core.ProcessingOutcome
import com.mobiledivecontrol.core.SensorUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _state = MutableStateFlow(controlCore.state)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _effects = MutableStateFlow<List<PlatformEffect>>(emptyList())
    val effects: StateFlow<List<PlatformEffect>> = _effects.asStateFlow()

    private val _depthUnitMetric = MutableStateFlow(true)
    val depthUnitMetric: StateFlow<Boolean> = _depthUnitMetric.asStateFlow()

    fun toggleDepthUnit() {
        _depthUnitMetric.value = !_depthUnitMetric.value
    }

    fun dispatch(command: ControlCommand) {
        val outcome = controlCore.dispatch(command)
        emitOutcome(outcome)
    }

    fun onButtonPayload(payload: ByteArray) {
        val outcome = controlCore.handleButtonPayload(payload)
        emitOutcome(outcome)
    }

    fun onNotification(characteristic: String, payload: ByteArray) {
        val outcome = controlCore.handleNotificationPayload(characteristic, payload)
        emitOutcome(outcome)
    }

    fun advanceBle(signal: BleSignal) {
        val outcome = controlCore.advanceBle(signal)
        emitOutcome(outcome)
    }

    fun updateSensor(update: SensorUpdate) {
        val outcome = controlCore.updateSensor(update)
        emitOutcome(outcome)
    }

    fun updateBattery(level: Int) {
        val outcome = controlCore.updateBatteryLevel(level)
        emitOutcome(outcome)
    }

    fun clearEffects() {
        _effects.value = emptyList()
    }

    /**
     * Simulate a button press for UI testing without hardware.
     * Maps [HousingButtonEvent] to its wire byte and processes it.
     */
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
        sessionStore.save(outcome.state)
        if (outcome.effects.isNotEmpty()) {
            _effects.value = outcome.effects
        }
    }
}
