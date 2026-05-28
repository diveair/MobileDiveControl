package com.mobiledivecontrol.core

data class SafetyThresholds(
    val vacuumTargetDeltaKpa: Double = 5.0,
    val stabilizationToleranceKpa: Double = 0.5,
    val requiredStabilizationSamples: Int = 3,
    val motorTimeoutMs: Long = 60_000L,
    val leakMonitoringDurationMs: Long = 300_000L,
)

sealed interface SafetySignal {
    data object StartVacuumCheckRequested : SafetySignal
    data object CancelVacuumCheckRequested : SafetySignal
    data object ResetSealStateRequested : SafetySignal
    data class CoverStateChanged(val open: Boolean) : SafetySignal
    data class BarometricPressureSample(val kpa: Double, val timestampMs: Long) : SafetySignal
}

data class SafetyMachineResult(
    val state: SafetyState,
    val effects: List<PlatformEffect> = emptyList(),
    val note: String? = null,
)

/**
 * Implements the manufacturer's 7-step vacuum workflow:
 *
 * 1. Confirm cover is open
 * 2. Open solenoid valve
 * 3. Turn on motor, start pumping
 * 4. When target pressure reached, stop motor
 * 5. Confirm cover is closed (gate: leak detection is inaccurate with open cover)
 * 6. Close solenoid valve (saves power, allows easy cover opening later)
 * 7. Monitor ≥5 minutes for air/water leakage via pressure
 *
 * Motor timeout: auto-stop after [SafetyThresholds.motorTimeoutMs] if target not reached.
 */
class SafetyStateMachine(
    private val thresholds: SafetyThresholds = SafetyThresholds(),
) {
    fun apply(state: SafetyState, signal: SafetySignal): SafetyMachineResult = when (signal) {
        SafetySignal.StartVacuumCheckRequested -> startVacuumCheck(state)
        SafetySignal.CancelVacuumCheckRequested -> cancelVacuumCheck(state)
        SafetySignal.ResetSealStateRequested -> resetSealState(state)
        is SafetySignal.CoverStateChanged -> handleCoverChange(state, signal.open)
        is SafetySignal.BarometricPressureSample -> handlePressureSample(state, signal.kpa, signal.timestampMs)
    }

    // --- Step 1: Confirm cover is open, then open solenoid + start motor ---

    private fun startVacuumCheck(state: SafetyState): SafetyMachineResult {
        if (state.coverOpen != true) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Warning,
                    warning = "Cover must be open before vacuum check.",
                ),
                note = "Cover must be open before vacuum check.",
            )
        }

        // Steps 2+3: open solenoid, start motor
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Vacuuming,
                baselinePressureKpa = state.barometricPressureKpa,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = System.currentTimeMillis(),
                leakMonitoringStartedAtEpochMs = null,
                warning = null,
            ),
            effects = listOf(
                PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = true)),
                PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = true)),
            ),
        )
    }

    // --- Pressure sample handling: drives the state machine forward ---

    private fun handlePressureSample(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val updated = state.copy(barometricPressureKpa = kpa)

        return when (updated.sealState) {
            // Step 4: Motor is running — check if target pressure reached or motor timed out
            SealState.Vacuuming -> handleVacuumingPressure(updated, kpa, timestampMs)

            // Step 7: Leak monitoring — collect samples over 5+ minutes
            SealState.LeakMonitoring -> handleLeakMonitoringPressure(updated, kpa, timestampMs)

            // Other states: just update pressure reading
            else -> SafetyMachineResult(state = updated)
        }
    }

    private fun handleVacuumingPressure(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val baseline = state.baselinePressureKpa ?: kpa
        val pressureDrop = baseline - kpa

        // Motor timeout check
        val motorStarted = state.motorStartedAtEpochMs
        if (motorStarted != null && (timestampMs - motorStarted) >= thresholds.motorTimeoutMs) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Failed,
                    warning = "Motor timeout: target pressure not reached within ${thresholds.motorTimeoutMs / 1000}s.",
                ),
                effects = listOf(
                    PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false)),
                    PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
                    PlatformEffect.EmitAlert(
                        priority = AlertPriority.Critical,
                        message = "Motor timeout: vacuum target not reached.",
                    ),
                ),
                note = "Motor timeout: vacuum target not reached.",
            )
        }

        // Step 4: Target pressure reached — stop motor, move to MotorStopping
        if (pressureDrop >= thresholds.vacuumTargetDeltaKpa) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.MotorStopping,
                    baselinePressureKpa = kpa,
                    motorStartedAtEpochMs = null,
                    warning = null,
                ),
                effects = listOf(
                    PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false)),
                ),
                note = "Target pressure reached. Motor stopped. Close the cover to continue.",
            )
        }

        // Still vacuuming — update baseline
        return SafetyMachineResult(
            state = state.copy(baselinePressureKpa = baseline),
        )
    }

    // --- Step 5: Wait for cover to close ---

    private fun handleCoverChange(state: SafetyState, open: Boolean): SafetyMachineResult {
        return when {
            // Cover opened — always update state
            open -> {
                val nextSealState = when (state.sealState) {
                    SealState.Passed -> SealState.Passed
                    SealState.Failed -> SealState.Failed
                    else -> SealState.CoverOpen
                }
                SafetyMachineResult(
                    state = state.copy(
                        coverOpen = true,
                        sealState = nextSealState,
                        warning = null,
                    ),
                )
            }

            // Cover closed during MotorStopping or WaitingForCoverClosed:
            // Step 5 confirmed → Step 6: close solenoid → Step 7: start leak monitoring
            state.sealState == SealState.MotorStopping ||
            state.sealState == SealState.WaitingForCoverClosed -> {
                SafetyMachineResult(
                    state = state.copy(
                        coverOpen = false,
                        sealState = SealState.LeakMonitoring,
                        stabilizationSamples = emptyList(),
                        leakMonitoringStartedAtEpochMs = System.currentTimeMillis(),
                        warning = null,
                    ),
                    effects = listOf(
                        // Step 6: close solenoid after cover is confirmed closed
                        PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
                    ),
                    note = "Cover closed. Solenoid closed. Leak monitoring started (${thresholds.leakMonitoringDurationMs / 1000}s).",
                )
            }

            // Cover closed in other states
            else -> {
                val nextSealState = when (state.sealState) {
                    SealState.Passed -> SealState.Passed
                    SealState.Failed -> SealState.Failed
                    SealState.CoverOpen -> SealState.ReadyToVacuum
                    else -> state.sealState
                }
                SafetyMachineResult(
                    state = state.copy(
                        coverOpen = false,
                        sealState = nextSealState,
                        warning = null,
                    ),
                )
            }
        }
    }

    // --- Step 7: Monitor ≥5 minutes for leaks ---

    private fun handleLeakMonitoringPressure(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val samples = (state.stabilizationSamples + kpa).takeLast(thresholds.requiredStabilizationSamples)
        val monitoringStarted = state.leakMonitoringStartedAtEpochMs ?: timestampMs

        val elapsedMs = timestampMs - monitoringStarted
        val timeThresholdMet = elapsedMs >= thresholds.leakMonitoringDurationMs

        if (timeThresholdMet && samples.size >= thresholds.requiredStabilizationSamples) {
            val spread = samples.maxOrNull().orZero() - samples.minOrNull().orZero()

            return if (spread <= thresholds.stabilizationToleranceKpa) {
                SafetyMachineResult(
                    state = state.copy(
                        sealState = SealState.Passed,
                        stabilizationSamples = samples,
                        warning = null,
                    ),
                    note = "Seal check passed after ${elapsedMs / 1000}s monitoring.",
                )
            } else {
                SafetyMachineResult(
                    state = state.copy(
                        sealState = SealState.Failed,
                        stabilizationSamples = samples,
                        warning = "Pressure unstable after ${elapsedMs / 1000}s: leak detected.",
                    ),
                    effects = listOf(
                        PlatformEffect.EmitAlert(
                            priority = AlertPriority.Critical,
                            message = "Seal failure: pressure leak detected.",
                        ),
                    ),
                    note = "Seal failure: pressure leak detected.",
                )
            }
        }

        // Still monitoring — accumulate samples
        return SafetyMachineResult(
            state = state.copy(
                stabilizationSamples = samples,
                leakMonitoringStartedAtEpochMs = monitoringStarted,
            ),
        )
    }

    // --- Cancel and Reset ---

    private fun cancelVacuumCheck(state: SafetyState): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Unknown,
                baselinePressureKpa = null,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = null,
                leakMonitoringStartedAtEpochMs = null,
                warning = "Vacuum check cancelled.",
            ),
            effects = listOf(
                PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false)),
                PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
            ),
            note = "Vacuum check cancelled.",
        )
    }

    private fun resetSealState(state: SafetyState): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Unknown,
                baselinePressureKpa = null,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = null,
                leakMonitoringStartedAtEpochMs = null,
                warning = null,
            ),
        )
    }
}

private fun Double?.orZero(): Double = this ?: 0.0
