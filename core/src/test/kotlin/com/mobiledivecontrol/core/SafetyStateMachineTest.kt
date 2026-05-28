package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyStateMachineTest {
    private val machine = SafetyStateMachine()

    @Test
    fun `vacuum start requires cover open`() {
        val result = machine.apply(
            state = SafetyState(),
            signal = SafetySignal.StartVacuumCheckRequested,
        )

        assertEquals(SealState.Warning, result.state.sealState)
        assertEquals("Cover must be open before vacuum check.", result.note)
    }

    @Test
    fun `full vendor 7-step vacuum workflow passes`() {
        val baseTimeMs = 1_000_000L

        // Step 1: Cover is open
        var state = SafetyState(
            sealState = SealState.CoverOpen,
            coverOpen = true,
            barometricPressureKpa = 101.3,
        )

        // Steps 2+3: Start vacuum — solenoid opens, motor starts
        val started = machine.apply(state, SafetySignal.StartVacuumCheckRequested)
        state = started.state
        assertEquals(SealState.Vacuuming, state.sealState)
        assertEquals(2, started.effects.size) // solenoid + motor
        assertNotNull(state.motorStartedAtEpochMs)

        // Step 4: Pressure drops to target — motor stops
        val pressureReached = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(95.0, baseTimeMs),
        )
        state = pressureReached.state
        assertEquals(SealState.MotorStopping, state.sealState)
        assertEquals(1, pressureReached.effects.size) // motor stop only
        assertNull(state.motorStartedAtEpochMs) // motor timestamp cleared

        // Step 5: Close the cover
        val coverClosed = machine.apply(
            state,
            SafetySignal.CoverStateChanged(open = false),
        )
        state = coverClosed.state
        assertEquals(SealState.LeakMonitoring, state.sealState)
        // Step 6: Solenoid should close when cover closes
        assertEquals(1, coverClosed.effects.size) // solenoid close
        assertNotNull(state.leakMonitoringStartedAtEpochMs)

        // Step 7: Monitor 5+ minutes with stable pressure
        val monitorStart = state.leakMonitoringStartedAtEpochMs!!
        val fiveMinutesLater = monitorStart + 300_001L

        // Accumulate samples before time threshold
        state = machine.apply(state, SafetySignal.BarometricPressureSample(95.0, monitorStart + 60_000)).state
        assertEquals(SealState.LeakMonitoring, state.sealState) // still monitoring
        state = machine.apply(state, SafetySignal.BarometricPressureSample(95.1, monitorStart + 120_000)).state
        assertEquals(SealState.LeakMonitoring, state.sealState) // still monitoring

        // Final sample at 5+ minutes — should pass
        val passed = machine.apply(state, SafetySignal.BarometricPressureSample(95.0, fiveMinutesLater))
        assertEquals(SealState.Passed, passed.state.sealState)
        assertNull(passed.state.warning)
    }

    @Test
    fun `cover must be closed before leak monitoring starts`() {
        var state = SafetyState(
            sealState = SealState.CoverOpen,
            coverOpen = true,
            barometricPressureKpa = 101.3,
        )

        // Start vacuum
        state = machine.apply(state, SafetySignal.StartVacuumCheckRequested).state

        // Pressure drops — motor stops
        state = machine.apply(state, SafetySignal.BarometricPressureSample(95.0, 1_000L)).state
        assertEquals(SealState.MotorStopping, state.sealState)

        // Pressure samples while cover is still open should NOT advance to Passed
        state = machine.apply(state, SafetySignal.BarometricPressureSample(95.0, 400_000L)).state
        // MotorStopping doesn't handle pressure samples — stays in MotorStopping
        assertEquals(SealState.MotorStopping, state.sealState)
    }

    @Test
    fun `motor timeout auto-stops and fails`() {
        val shortTimeoutThresholds = SafetyThresholds(motorTimeoutMs = 5_000L)
        val shortMachine = SafetyStateMachine(shortTimeoutThresholds)

        var state = SafetyState(
            sealState = SealState.CoverOpen,
            coverOpen = true,
            barometricPressureKpa = 101.3,
        )

        // Start vacuum
        val started = shortMachine.apply(state, SafetySignal.StartVacuumCheckRequested)
        state = started.state
        val motorStarted = state.motorStartedAtEpochMs!!

        // Pressure hasn't dropped enough, but motor timeout reached
        val result = shortMachine.apply(
            state,
            SafetySignal.BarometricPressureSample(100.0, motorStarted + 5_001L),
        )

        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.state.warning!!.contains("Motor timeout"))
        assertEquals(3, result.effects.size) // motor off + solenoid off + alert
    }

    @Test
    fun `leak detected during monitoring fails`() {
        val fastThresholds = SafetyThresholds(
            leakMonitoringDurationMs = 1_000L,
            stabilizationToleranceKpa = 0.5,
            requiredStabilizationSamples = 3,
        )
        val fastMachine = SafetyStateMachine(fastThresholds)

        // Start in leak monitoring state directly
        val monitorStartMs = 100_000L
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            coverOpen = false,
            barometricPressureKpa = 95.0,
            leakMonitoringStartedAtEpochMs = monitorStartMs,
            stabilizationSamples = emptyList(),
        )

        // Add unstable pressure samples
        state = fastMachine.apply(state, SafetySignal.BarometricPressureSample(95.0, monitorStartMs + 500)).state
        state = fastMachine.apply(state, SafetySignal.BarometricPressureSample(93.0, monitorStartMs + 800)).state

        // After time threshold with unstable pressure
        val result = fastMachine.apply(state, SafetySignal.BarometricPressureSample(91.0, monitorStartMs + 1_500))
        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.state.warning!!.contains("leak"))
    }

    @Test
    fun `cancel vacuum check stops motor and solenoid`() {
        var state = SafetyState(
            sealState = SealState.Vacuuming,
            coverOpen = true,
            motorStartedAtEpochMs = 1_000L,
        )

        val cancelled = machine.apply(state, SafetySignal.CancelVacuumCheckRequested)
        assertEquals(SealState.Unknown, cancelled.state.sealState)
        assertEquals(2, cancelled.effects.size) // motor off + solenoid off
        assertNull(cancelled.state.motorStartedAtEpochMs)
        assertNull(cancelled.state.leakMonitoringStartedAtEpochMs)
        assertEquals("Vacuum check cancelled.", cancelled.note)
    }
}
