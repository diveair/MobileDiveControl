package com.mobiledivecontrol.core

import kotlin.test.*

class SurfaceHousingReconnectTest {
    private fun sealed(depth: Double) = AppState(
        bleConnectionState = BleConnectionState.Ready,
        housing = HousingState(connected = true, inputEnabled = true),
        diveProfile = DiveProfileState(sensorAvailable = true, depthMeters = depth,
            active = DiveSession(1, DiveSettings(), maxDepthMeters = 4.8, elapsedMs = 120_000,
                surfaceElapsedMs = if (depth < 0.8) 12_000 else 0)),
        safety = SafetyState(sealState = SealState.Passed, sealConfidence = SealConfidence.Conservative,
            waterPressureKpa = STANDARD_SURFACE_PRESSURE_KPA + depth * FRESHWATER_KPA_PER_METER,
            barometricPressureKpa = 81.0, baselinePressureKpa = 81.0,
            stabilizationSamples = listOf(81.0, 81.0, 81.0), leakMonitoringStartedAtEpochMs = 1,
            leakMonitoringElapsedMs = 1_800_000, coverOpen = false))

    @Test fun `surface shutdown retires the old leak window and dive then ambient reconnect needs a new check`() {
        val core = ControlCore(sealed(0.2))
        val disconnected = core.advanceBle(BleSignal.Disconnect).state
        assertNull(disconnected.diveProfile.active)
        assertEquals(1, disconnected.diveProfile.logs.size)
        assertEquals(SealState.Unknown, disconnected.safety.sealState)
        assertNull(disconnected.safety.waterPressureKpa)
        assertEquals(81.0, disconnected.safety.verifiedVacuumKpa)
        core.updateSensor(SensorUpdate.WaterPressure(101.325))
        core.updateSensor(SensorUpdate.BarometricPressure(101.0))
        val result = core.updateSensor(SensorUpdate.BarometricPressure(101.1))
        assertNotEquals(SealState.Failed, result.state.safety.sealState)
        assertNotEquals(SealState.Passed, result.state.safety.sealState)
        assertNull(result.state.safety.verifiedVacuumKpa)
        assertTrue(result.effects.none { it is PlatformEffect.EmitAlert })
    }

    @Test fun `surface dropout with vacuum intact restores earned confidence`() {
        val core = ControlCore(sealed(0.2))
        core.advanceBle(BleSignal.Disconnect)
        core.updateSensor(SensorUpdate.WaterPressure(101.325))
        core.updateSensor(SensorUpdate.BarometricPressure(81.0))
        val state = core.updateSensor(SensorUpdate.BarometricPressure(81.0)).state
        assertEquals(SealState.Passed, state.safety.sealState)
        assertEquals(SealConfidence.Conservative, state.safety.sealConfidence)
    }

    @Test fun `pressure expiry before the BLE disconnect retains confirmed surface context`() {
        val original = sealed(0.2)
        val expired = DiveProfileTracker.unavailable(original.diveProfile)
        assertFalse(expired.sensorAvailable)
        assertTrue(expired.surfaceConfirmedBeforeLoss)
        val core = ControlCore(original.copy(diveProfile = expired))
        val disconnected = core.advanceBle(BleSignal.Disconnect).state
        assertEquals(SealState.Unknown, disconnected.safety.sealState)
        assertEquals(81.0, disconnected.safety.verifiedVacuumKpa)
        assertNull(disconnected.safety.leakMonitoringStartedAtEpochMs)
    }

    @Test fun `submerged dropout retains active dive and leak monitoring`() {
        val core = ControlCore(sealed(4.0))
        val state = core.advanceBle(BleSignal.Disconnect).state
        assertNotNull(state.diveProfile.active)
        assertEquals(SealState.Passed, state.safety.sealState)
        assertNotNull(state.safety.leakMonitoringStartedAtEpochMs)
        assertNull(state.safety.verifiedVacuumKpa)
    }

    @Test fun `ambient boot decision waits for external sensor and cannot clear an underwater failure`() {
        val machine = SafetyStateMachine()
        var state = SafetyState(verifiedVacuumKpa = 81.0)
        repeat(3) { state = machine.apply(state, SafetySignal.BarometricPressureSample(101.0, it * 500L)).state }
        assertEquals(81.0, state.verifiedVacuumKpa)
        assertNotEquals(SealState.Failed, state.sealState)
        val underwater = state.copy(waterPressureKpa = 140.0)
        val result = machine.apply(underwater, SafetySignal.BarometricPressureSample(101.0, 2000))
        assertEquals(SealState.Failed, result.state.sealState)
    }
}
