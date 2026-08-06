package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyStateMachineTest {
    private val machine = SafetyStateMachine()
    private val thresholds = SafetyThresholds()

    private val motorOff = PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false))
    private val motorOn = PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = true))
    private val solenoidClosed = PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false))
    private val solenoidOpen = PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = true))

    @Test
    fun `vacuum start requires cover open`() {
        val result = machine.apply(
            state = SafetyState(),
            signal = SafetySignal.StartVacuumCheckRequested,
        )

        assertEquals(SealState.Warning, result.state.sealState)
        assertEquals("Cover must be open before vacuum check.", result.note)
        assertTrue(result.effects.isEmpty(), "A rejected start must not touch hardware.")
    }

    @Test
    fun `full vendor 7-step vacuum workflow climbs every confidence tier`() {
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
        assertEquals(listOf(solenoidOpen, motorOn), started.effects)
        assertNotNull(state.motorStartedAtEpochMs)
        // The cover was open and the pump had not started, so this reading is atmosphere.
        assertEquals(101.3, state.surfaceAmbientKpa)

        // A 6 kPa drop is no longer enough — the target is 20 kPa below ambient.
        val stillPumping = machine.apply(state, SafetySignal.BarometricPressureSample(95.0, 1_000L))
        assertEquals(SealState.Vacuuming, stillPumping.state.sealState)

        // Step 4: Pressure reaches the 20 kPa target — motor stops
        val pressureReached = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(81.0, 1_000L),
        )
        state = pressureReached.state
        assertEquals(SealState.MotorStopping, state.sealState)
        assertEquals(listOf(motorOff), pressureReached.effects)
        assertNull(state.motorStartedAtEpochMs)

        // Step 5: Close the cover → Step 6: solenoid closes → Step 7: monitoring starts
        val coverClosed = machine.apply(state, SafetySignal.CoverStateChanged(open = false))
        state = coverClosed.state
        assertEquals(SealState.LeakMonitoring, state.sealState)
        assertEquals(listOf(solenoidClosed), coverClosed.effects)
        assertNotNull(state.leakMonitoringStartedAtEpochMs)

        // Step 7: pressure holds. Confidence climbs; the state only transitions once.
        val monitorStart = state.leakMonitoringStartedAtEpochMs!!
        repeat(3) { index ->
            state = machine.apply(
                state,
                SafetySignal.BarometricPressureSample(81.0, monitorStart + (index + 1) * 1_000L),
            ).state
        }
        assertEquals(SealState.LeakMonitoring, state.sealState)
        assertEquals(SealConfidence.Monitoring, state.sealConfidence)

        val tiers = listOf(
            thresholds.provisionalMs to SealConfidence.Provisional,
            thresholds.manufacturerMinimumMs to SealConfidence.ManufacturerMinimum,
            thresholds.recommendedMs to SealConfidence.Recommended,
            thresholds.conservativeMs to SealConfidence.Conservative,
        )
        for ((elapsed, expected) in tiers) {
            val tick = machine.apply(
                state,
                SafetySignal.BarometricPressureSample(81.0, monitorStart + elapsed + 1),
            )
            state = tick.state
            assertEquals(SealState.Passed, state.sealState, "Passed is reached at Provisional and held")
            assertEquals(expected, state.sealConfidence)
            assertEquals(elapsed + 1, state.leakMonitoringElapsedMs)
            assertNull(state.warning)
            assertTrue(tick.effects.isEmpty(), "A holding seal must not touch hardware.")
        }
    }

    @Test
    fun `a single cap-wait sample does not start monitoring`() {
        var state = SafetyState(
            sealState = SealState.CoverOpen,
            coverOpen = true,
            barometricPressureKpa = 101.3,
        )

        state = machine.apply(state, SafetySignal.StartVacuumCheckRequested).state
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_000L)).state
        assertEquals(SealState.MotorStopping, state.sealState)

        // One reading is not stability evidence — the pressure fallback needs a full
        // stabilization window before it will start the hold without the cover byte.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 400_000L)).state
        assertEquals(SealState.MotorStopping, state.sealState)
        assertNotEquals(SealState.Passed, state.sealState)
    }

    @Test
    fun `the hold starts on stable pressure when the cover byte never arrives`() {
        // The cover byte is the housing's flakiest signal; a pumped-down shell sitting flat at
        // target is better evidence than that byte ever was, and the timer must not be its hostage.
        var state = SafetyState(
            sealState = SealState.WaitingForCoverClosed,
            coverOpen = true,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
        )

        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_000L)).state
        assertEquals(SealState.WaitingForCoverClosed, state.sealState)
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.2, 1_500L)).state
        assertEquals(SealState.WaitingForCoverClosed, state.sealState)

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(81.1, 2_000L))
        assertEquals(SealState.LeakMonitoring, result.state.sealState)
        assertEquals(2_000L, result.state.leakMonitoringStartedAtEpochMs)
        assertTrue(result.state.capCloseReminder, "The diver must still be told to close the cap")
        assertTrue(solenoidClosed in result.effects, "Step 6 still happens on the fallback path")
    }

    @Test
    fun `a shell that gives back too much after the pump keeps waiting`() {
        var state = SafetyState(
            sealState = SealState.MotorStopping,
            coverOpen = true,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
        )

        // Stable, but only an 11 kPa deficit remains of the 20 kPa pull — that is not the
        // vacuum that was just pumped, and it must not be certified quietly.
        for (t in listOf(1_000L, 1_500L, 2_000L, 2_500L)) {
            state = machine.apply(state, SafetySignal.BarometricPressureSample(90.3, t)).state
            assertEquals(SealState.MotorStopping, state.sealState)
        }
    }

    @Test
    fun `two ambient readings during the cap wait step back to Unknown quietly`() {
        var state = SafetyState(
            sealState = SealState.WaitingForCoverClosed,
            coverOpen = true,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
        )

        state = machine.apply(state, SafetySignal.BarometricPressureSample(101.2, 1_000L)).state
        assertEquals(SealState.WaitingForCoverClosed, state.sealState, "One reading could be a glitch")

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.1, 1_500L))
        assertEquals(SealState.Unknown, result.state.sealState)
        assertTrue(
            result.effects.none { it is PlatformEffect.EmitAlert },
            "Abandoning an unstarted check is not a failure",
        )
    }

    @Test
    fun `motor timeout stops the motor and closes the solenoid`() {
        val shortMachine = SafetyStateMachine(SafetyThresholds(motorTimeoutMs = 5_000L))

        val state = shortMachine.apply(
            SafetyState(
                sealState = SealState.CoverOpen,
                coverOpen = true,
                barometricPressureKpa = 101.3,
            ),
            SafetySignal.StartVacuumCheckRequested,
        ).state
        val motorStarted = state.motorStartedAtEpochMs!!

        val result = shortMachine.apply(
            state,
            SafetySignal.BarometricPressureSample(100.0, motorStarted + 5_001L),
        )

        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.state.warning!!.contains("Motor timeout"))
        assertNull(result.state.motorStartedAtEpochMs)
        assertTrue(motorOff in result.effects, "Timeout must stop the motor.")
        assertTrue(solenoidClosed in result.effects, "Timeout must close the solenoid.")
        assertTrue(result.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `gross leak fails within ten seconds instead of five minutes`() {
        val monitorStartMs = 100_000L
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            coverOpen = false,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
            leakMonitoringStartedAtEpochMs = monitorStartMs,
        )

        // Sensor runs at 2 Hz during monitoring; three samples land inside six seconds.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, monitorStartMs + 2_000)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)
        state = machine.apply(state, SafetySignal.BarometricPressureSample(82.0, monitorStartMs + 4_000)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(85.0, monitorStartMs + 6_000))

        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.state.leakMonitoringElapsedMs <= 10_000L)
        assertTrue(result.state.warning!!.contains("leak"))
        assertEquals(SealConfidence.Monitoring, result.state.sealConfidence)
        assertNull(result.state.leakMonitoringStartedAtEpochMs)
        assertTrue(motorOff in result.effects)
        assertTrue(solenoidClosed in result.effects)
        assertTrue(result.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `pressure back at surface ambient acts on the second agreeing sample`() {
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            surfaceAmbientKpa = 101.3,
            leakMonitoringStartedAtEpochMs = 0L,
            stabilizationSamples = listOf(81.0, 81.1),
        )
        // First ambient reading: quarantined, the hold visibly unchanged.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(101.2, 10_000L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)
        // Second agreeing reading: the vent is real and acts immediately.
        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.1, 10_500L))
        assertTrue(result.state.sealState == SealState.Failed || result.state.sealState == SealState.Unknown)
    }

    @Test
    fun `a depressurisation inside the hard verify fails even from Passed`() {
        // Provisional at 4 minutes is still inside the 5-minute hard verify: the released path
        // has not been earned yet, so pressure loss goes to Failed and the UI words it by
        // whether the diver ever claimed the cap was on.
        val monitorStartMs = 100_000L
        var state = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.Provisional,
            coverOpen = false,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
            leakMonitoringStartedAtEpochMs = monitorStartMs,
            stabilizationSamples = listOf(81.0, 81.0),
        )

        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(86.0, monitorStartMs + 240_000),
        )
        state = result.state

        assertEquals(SealState.Failed, state.sealState)
        assertEquals(SealConfidence.Monitoring, state.sealConfidence)
    }

    @Test
    fun `venting a freshly pumped hold early is a failure, not a release`() {
        // The user's exact report: pump the vacuum (hold started by the cap-wait pressure
        // fallback, so capCloseReminder is up), then pull the cap two minutes in. The adopted
        // exemption must NOT apply — this app's clock is the only history this seal has, and
        // two minutes is not enough to earn the VACUUM RELEASED banner.
        var state = SafetyState(
            sealState = SealState.WaitingForCoverClosed,
            coverOpen = true,
            barometricPressureKpa = 81.0,
            surfaceAmbientKpa = 101.3,
        )
        for (t in listOf(1_000L, 1_500L, 2_000L)) {
            state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, t)).state
        }
        assertEquals(SealState.LeakMonitoring, state.sealState)
        assertTrue(state.capCloseReminder)
        assertFalse(state.adoptedHold, "a pumped hold is never an adopted one")

        // Full vent at two minutes: first ambient reading quarantined, second confirms.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(101.2, 120_000L)).state
        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.3, 120_500L))
        assertEquals(SealState.Failed, result.state.sealState)
        assertFalse(result.state.vacuumReleasedPrompt, "no released banner inside the hard verify")
    }

    @Test
    fun `cancel from every seal state stops the motor and closes the solenoid`() {
        for (sealState in SealState.entries) {
            val cancelled = machine.apply(
                SafetyState(
                    sealState = sealState,
                    coverOpen = true,
                    motorStartedAtEpochMs = 1_000L,
                    leakMonitoringStartedAtEpochMs = 2_000L,
                    sealConfidence = SealConfidence.Recommended,
                ),
                SafetySignal.CancelVacuumCheckRequested,
            )

            assertEquals(SealState.Unknown, cancelled.state.sealState, "cancel from $sealState")
            assertEquals(SealConfidence.Monitoring, cancelled.state.sealConfidence, "cancel from $sealState")
            assertNull(cancelled.state.motorStartedAtEpochMs, "cancel from $sealState")
            assertNull(cancelled.state.leakMonitoringStartedAtEpochMs, "cancel from $sealState")
            assertTrue(motorOff in cancelled.effects, "cancel from $sealState must stop the motor")
            assertTrue(solenoidClosed in cancelled.effects, "cancel from $sealState must close the solenoid")
            assertEquals("Vacuum check cancelled.", cancelled.note)
        }
    }

    @Test
    fun `reset never leaves the seal passed and drives hardware to rest`() {
        val reset = machine.apply(
            SafetyState(
                sealState = SealState.Passed,
                sealConfidence = SealConfidence.Conservative,
                coverOpen = false,
                checkDismissed = true,
                leakMonitoringStartedAtEpochMs = 5_000L,
            ),
            SafetySignal.ResetSealStateRequested,
        )

        assertEquals(SealState.Unknown, reset.state.sealState)
        assertEquals(SealConfidence.Monitoring, reset.state.sealConfidence)
        assertFalse(reset.state.checkDismissed)
        assertTrue(motorOff in reset.effects)
        assertTrue(solenoidClosed in reset.effects)
    }

    @Test
    fun `surface ambient is captured while the cover is open`() {
        val state = SafetyState(sealState = SealState.CoverOpen, coverOpen = true)

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.3, 1_000L))

        assertEquals(101.3, result.state.surfaceAmbientKpa)
        assertEquals(101.3, result.state.barometricPressureKpa)
    }

    @Test
    fun `surface ambient is not captured while the shell is sealed`() {
        val state = SafetyState(
            sealState = SealState.LeakMonitoring,
            coverOpen = false,
            surfaceAmbientKpa = 101.3,
            leakMonitoringStartedAtEpochMs = 1_000L,
        )

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 2_000L))

        assertEquals(101.3, result.state.surfaceAmbientKpa, "A sealed reading is not atmosphere.")
        assertEquals(81.0, result.state.barometricPressureKpa)
    }

    @Test
    fun `surface ambient is not captured while a vacuum is held with the cover open`() {
        // Pump stopped, cover still open, shell already 20 kPa down. The port is open but
        // the shell is not vented — capturing here would feed 2 m of phantom depth.
        val state = SafetyState(
            sealState = SealState.MotorStopping,
            coverOpen = true,
            surfaceAmbientKpa = 101.3,
        )

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 2_000L))

        assertEquals(101.3, result.state.surfaceAmbientKpa)
    }

    @Test
    fun `surface ambient is not captured while the pump is running`() {
        val state = SafetyState(
            sealState = SealState.Vacuuming,
            coverOpen = true,
            baselinePressureKpa = 101.3,
            surfaceAmbientKpa = 101.3,
            motorStartedAtEpochMs = 1_000L,
        )

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(90.0, 2_000L))

        assertEquals(101.3, result.state.surfaceAmbientKpa)
    }

    @Test
    fun `skip to result keeps the tier already reached`() {
        val state = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.ManufacturerMinimum,
            coverOpen = false,
            leakMonitoringStartedAtEpochMs = 1_000L,
            leakMonitoringElapsedMs = 320_000L,
        )

        val result = machine.apply(state, SafetySignal.SkipToResultRequested)

        assertEquals(SealState.Passed, result.state.sealState)
        assertEquals(SealConfidence.ManufacturerMinimum, result.state.sealConfidence)
        assertNull(result.state.leakMonitoringStartedAtEpochMs, "Monitoring is over.")
        assertNull(result.state.warning)
        assertTrue(motorOff in result.effects)
        assertTrue(solenoidClosed in result.effects)
    }

    @Test
    fun `skip to result below the provisional tier reports uncertainty rather than a pass`() {
        val state = SafetyState(
            sealState = SealState.LeakMonitoring,
            sealConfidence = SealConfidence.Monitoring,
            coverOpen = false,
            leakMonitoringStartedAtEpochMs = 1_000L,
            leakMonitoringElapsedMs = 40_000L,
        )

        val result = machine.apply(state, SafetySignal.SkipToResultRequested)

        assertEquals(SealState.Warning, result.state.sealState)
        assertNotEquals(SealState.Passed, result.state.sealState)
        assertTrue(result.state.warning!!.contains("not enough evidence"))
        assertNull(result.state.leakMonitoringStartedAtEpochMs)
    }

    @Test
    fun `skip to result outside monitoring does nothing`() {
        val state = SafetyState(sealState = SealState.CoverOpen, coverOpen = true)

        val result = machine.apply(state, SafetySignal.SkipToResultRequested)

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `dismiss silences the prompt and opening the cover restores it`() {
        val dismissed = machine.apply(
            SafetyState(sealState = SealState.CoverOpen, coverOpen = true),
            SafetySignal.DismissSealCheckRequested,
        )
        assertTrue(dismissed.state.checkDismissed)
        assertTrue(dismissed.effects.isEmpty())

        // Closing then reopening the cover re-arms the prompt.
        val closed = machine.apply(dismissed.state, SafetySignal.CoverStateChanged(open = false))
        assertTrue(closed.state.checkDismissed, "Closing the cover alone must not re-prompt.")

        val reopened = machine.apply(closed.state, SafetySignal.CoverStateChanged(open = true))
        assertFalse(reopened.state.checkDismissed)
        assertEquals(SealState.CoverOpen, reopened.state.sealState)
    }

    @Test
    fun `a repeated cover-open notification does not disturb an in-flight pump`() {
        val pumping = machine.apply(
            SafetyState(
                sealState = SealState.CoverOpen,
                coverOpen = true,
                barometricPressureKpa = 101.3,
            ),
            SafetySignal.StartVacuumCheckRequested,
        ).state

        val repeated = machine.apply(pumping, SafetySignal.CoverStateChanged(open = true))

        assertEquals(SealState.Vacuuming, repeated.state.sealState)
        assertEquals(pumping.motorStartedAtEpochMs, repeated.state.motorStartedAtEpochMs)
        assertTrue(repeated.effects.isEmpty())
    }

    @Test
    fun `opening the cover voids a passed verdict and stops monitoring`() {
        val passed = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.Recommended,
            coverOpen = false,
            leakMonitoringStartedAtEpochMs = 1_000L,
            leakMonitoringElapsedMs = 620_000L,
        )

        val opened = machine.apply(passed, SafetySignal.CoverStateChanged(open = true))

        assertEquals(SealState.CoverOpen, opened.state.sealState)
        assertEquals(SealConfidence.Monitoring, opened.state.sealConfidence)
        assertNull(opened.state.leakMonitoringStartedAtEpochMs)
    }

    @Test
    fun `confidence tiers map to the published hold times`() {
        assertEquals(SealConfidence.Monitoring, thresholds.confidenceFor(0L))
        assertEquals(SealConfidence.Monitoring, thresholds.confidenceFor(179_999L))
        assertEquals(SealConfidence.Provisional, thresholds.confidenceFor(180_000L))
        assertEquals(SealConfidence.ManufacturerMinimum, thresholds.confidenceFor(300_000L))
        assertEquals(SealConfidence.Recommended, thresholds.confidenceFor(600_000L))
        assertEquals(SealConfidence.Conservative, thresholds.confidenceFor(1_800_000L))
    }

    @Test
    fun `thresholds clear the barometric sensor resolution`() {
        // 5 kPa resolution: the target must not sit on one quantisation step, and the
        // stability tolerance must exceed half a step's worth of rounding noise.
        assertEquals(20.0, thresholds.vacuumTargetDeltaKpa)
        assertEquals(1.0, thresholds.stabilizationToleranceKpa)
        assertEquals(120_000L, thresholds.motorTimeoutMs)
    }

    @Test
    fun `a deep vacuum the machine never pulled is adopted into leak monitoring`() {
        val state = SafetyState(sealState = SealState.Unknown, surfaceAmbientKpa = 101.0)
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 50_000L),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 50_500L))
        }
        assertEquals(SealState.LeakMonitoring, result.state.sealState)
        assertEquals(50_500L, result.state.leakMonitoringStartedAtEpochMs)
        assertEquals(listOf(81.0), result.state.stabilizationSamples)
        // Adoption is a recognition, not an actuation — no motor or solenoid effect may fire.
        assertEquals(emptyList<PlatformEffect>(), result.effects)
    }

    @Test
    fun `a shallow drop is not mistaken for an established vacuum`() {
        val state = SafetyState(sealState = SealState.Unknown, surfaceAmbientKpa = 101.0)
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 98.5, timestampMs = 50_000L),
        )
        assertEquals(SealState.Unknown, result.state.sealState)
    }

    @Test
    fun `without a captured baseline the fallback threshold is stricter`() {
        // 96 kPa is 5.3 below assumed sea level: over the 4.0 captured-baseline threshold but
        // under the 8.0 fallback one, so with no baseline it must NOT read as a vacuum —
        // that same reading is normal ambient at ~450 m altitude.
        val shallow = machine.apply(
            SafetyState(sealState = SealState.Unknown),
            SafetySignal.BarometricPressureSample(kpa = 96.0, timestampMs = 1_000L),
        )
        assertEquals(SealState.Unknown, shallow.state.sealState)

        val deep = machine.apply(
            SafetyState(sealState = SealState.Unknown),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 1_000L),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 1_500L))
        }
        assertEquals(SealState.LeakMonitoring, deep.state.sealState)
    }

    @Test
    fun `a held vacuum with a stale open cover byte is detected, not self-poisoned`() {
        // Field regression: app restart against a housing holding -20 kPa, cover byte claiming
        // open. The first barometric sample used to be captured as surface ambient, after which
        // detection compared the vacuum against itself, measured zero, and the app prompted the
        // diver to re-evacuate an already-sealed housing. The dry water-pressure sensor is the
        // tie-breaker: it reads true atmosphere, exposing the 20 kPa disagreement.
        val state = SafetyState(
            sealState = SealState.Unknown,
            coverOpen = true,
            waterPressureKpa = 101.2,
        )
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 7_000L),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 7_500L))
        }
        assertEquals(SealState.LeakMonitoring, result.state.sealState)
        // The poisoned capture is the root cause, so pin it directly: the vacuum sample must
        // never become the surface baseline.
        assertEquals(null, result.state.surfaceAmbientKpa)
    }

    @Test
    fun `a vented shell still captures surface ambient when the sensors agree`() {
        val state = SafetyState(
            sealState = SealState.Unknown,
            coverOpen = true,
            waterPressureKpa = 101.2,
        )
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 7_000L),
        )
        assertEquals(100.9, result.state.surfaceAmbientKpa)
        assertEquals(SealState.Unknown, result.state.sealState)
    }

    @Test
    fun `a leak inside the hard verify window is a failure with an alert`() {
        val monitoring = SafetyState(
            sealState = SealState.LeakMonitoring,
            surfaceAmbientKpa = 101.0,
            leakMonitoringStartedAtEpochMs = 0L,
            stabilizationSamples = listOf(81.0),
        )
        // Back at ambient two minutes in — the trial seal let go. Two samples: the first is
        // quarantined by the glitch gate, the second confirms the new regime.
        val quarantined = machine.apply(
            monitoring,
            SafetySignal.BarometricPressureSample(kpa = 100.8, timestampMs = 120_000L),
        ).state
        val result = machine.apply(
            quarantined,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 120_500L),
        )
        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `a vent after the hard verify is treated as deliberate, not a failure`() {
        val monitoring = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.ManufacturerMinimum,
            surfaceAmbientKpa = 101.0,
            leakMonitoringStartedAtEpochMs = 0L,
            stabilizationSamples = listOf(81.0),
        )
        // Back at ambient six minutes in — the diver opened the housing on purpose. First
        // sample quarantined, second confirms.
        val quarantined = machine.apply(
            monitoring,
            SafetySignal.BarometricPressureSample(kpa = 100.8, timestampMs = 360_000L),
        ).state
        val result = machine.apply(
            quarantined,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 360_500L),
        )
        assertEquals(SealState.Unknown, result.state.sealState)
        assertEquals(false, result.state.checkDismissed)
        assertTrue(
            result.effects.none { it is PlatformEffect.EmitAlert },
            "A deliberate opening must not cry wolf",
        )
    }

    @Test
    fun `a deliberate release at the surface raises the released banner, not the doorway`() {
        val monitoring = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.ManufacturerMinimum,
            surfaceAmbientKpa = 101.0,
            waterPressureKpa = 101.2,
            leakMonitoringStartedAtEpochMs = 0L,
            stabilizationSamples = listOf(81.0),
        )
        val quarantined = machine.apply(
            monitoring,
            SafetySignal.BarometricPressureSample(kpa = 100.8, timestampMs = 360_000L),
        ).state
        val result = machine.apply(
            quarantined,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 360_500L),
        )
        assertEquals(SealState.Unknown, result.state.sealState)
        assertTrue(result.state.vacuumReleasedPrompt, "Surface release must offer the released banner")
    }

    @Test
    fun `a deliberate release below the surface never offers to open the housing`() {
        val monitoring = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.ManufacturerMinimum,
            surfaceAmbientKpa = 101.0,
            // ~3 m of water on the external sensor: whatever happened, "you may open the
            // housing" must not be on screen.
            waterPressureKpa = 131.0,
            leakMonitoringStartedAtEpochMs = 0L,
            stabilizationSamples = listOf(81.0),
        )
        val quarantined = machine.apply(
            monitoring,
            SafetySignal.BarometricPressureSample(kpa = 100.8, timestampMs = 360_000L),
        ).state
        val result = machine.apply(
            quarantined,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 360_500L),
        )
        assertEquals(SealState.Unknown, result.state.sealState)
        assertFalse(result.state.vacuumReleasedPrompt)
    }

    @Test
    fun `the released banner lets the pump start despite a stale cover byte`() {
        val state = SafetyState(
            sealState = SealState.Unknown,
            vacuumReleasedPrompt = true,
            coverOpen = false,
            barometricPressureKpa = 101.0,
        )
        val result = machine.apply(state, SafetySignal.StartVacuumCheckRequested)
        assertEquals(SealState.Vacuuming, result.state.sealState)
        assertFalse(result.state.vacuumReleasedPrompt, "Starting the pump answers the banner")
        assertTrue(motorOn in result.effects)
    }

    @Test
    fun `a capped pump is stopped within seconds, not left grinding`() {
        var state = SafetyState(
            sealState = SealState.Vacuuming,
            coverOpen = true,
            motorStartedAtEpochMs = 0L,
            baselinePressureKpa = 101.0,
        )
        // Flat pressure at ~3 Hz. Inside the one-second quick window nothing happens yet…
        for (t in listOf(300L, 600L, 900L, 1_200L)) {
            val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.0, t))
            state = result.state
            assertEquals(SealState.Vacuuming, state.sealState)
            assertTrue(result.effects.none { it is PlatformEffect.ExecuteHousing })
        }
        // …and the first sample a full second after the last progress stops the motor: pumping
        // against a sealed port only spends the pump's service life.
        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.0, 1_400L))
        assertEquals(SealState.CoverOpen, result.state.sealState)
        assertNull(result.state.motorStartedAtEpochMs)
        assertTrue(motorOff in result.effects, "The capped pump must be shut off")
        assertTrue(solenoidClosed in result.effects)
        assertEquals(NO_SUCTION_WARNING, result.state.warning)
    }

    @Test
    fun `a pump that pulled real suction and then stalled is stopped within a rate window`() {
        var state = SafetyState(
            sealState = SealState.Vacuuming,
            coverOpen = true,
            motorStartedAtEpochMs = 0L,
            baselinePressureKpa = 101.0,
        )
        // Real progress to -8 kPa, proving the pump works and ruling the cap out…
        state = machine.apply(state, SafetySignal.BarometricPressureSample(93.0, 2_000L)).state
        // …then flat. One reading inside the rate window changes nothing…
        state = machine.apply(state, SafetySignal.BarometricPressureSample(93.0, 3_000L)).state
        assertEquals(SealState.Vacuuming, state.sealState)
        // …and the first reading past it stops the pump: ~2 s after the stall began, not 10.
        val result = machine.apply(state, SafetySignal.BarometricPressureSample(93.0, 4_200L))
        assertEquals(SealState.CoverOpen, result.state.sealState)
        assertNull(result.state.motorStartedAtEpochMs)
        assertTrue(motorOff in result.effects, "A leak-balanced pump must be shut off")
        assertTrue(solenoidClosed in result.effects)
        assertEquals(VACUUM_NOT_BUILDING_WARNING, result.state.warning)
    }

    @Test
    fun `a slow but healthy pump is never mistaken for a leak`() {
        var state = SafetyState(
            sealState = SealState.Vacuuming,
            coverOpen = true,
            motorStartedAtEpochMs = 0L,
            baselinePressureKpa = 101.0,
        )
        // 0.6 kPa/s — a crawl next to a healthy pump, but real: it clears the 0.5 kPa
        // no-suction bar inside the first second and every rate window after that.
        var kpa = 101.0
        var t = 0L
        repeat(20) {
            t += 1_000L
            kpa -= 0.6
            val result = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t))
            state = result.state
            assertEquals(SealState.Vacuuming, state.sealState, "healthy pump stopped at t=$t")
            assertTrue(result.effects.none { it is PlatformEffect.ExecuteHousing })
        }
    }

    @Test
    fun `an adopted vacuum raises the close-the-cap reminder`() {
        // Two agreeing readings: adoption requires a vacuum that is standing still.
        val first = machine.apply(
            SafetyState(sealState = SealState.Unknown, surfaceAmbientKpa = 101.0),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 50_000L),
        )
        assertEquals(SealState.Unknown, first.state.sealState, "one reading is a candidate, not a vacuum")
        val result = machine.apply(
            first.state,
            SafetySignal.BarometricPressureSample(kpa = 81.1, timestampMs = 50_500L),
        )
        assertEquals(SealState.LeakMonitoring, result.state.sealState)
        assertTrue(result.state.capCloseReminder, "adoption must ask about the cap")
    }

    @Test
    fun `a shell draining through vacuum levels on its way to ambient is never adopted`() {
        // The exact field report: release a 30-minute hold, and mid-drain readings pass through
        // "looks like a vacuum" territory. Every reading is moving, so none may be adopted --
        // no VACUUM REACHED flash between the release and the released banner.
        var state = SafetyState(
            sealState = SealState.Unknown,
            surfaceAmbientKpa = 101.0,
            vacuumReleasedPrompt = true,
        )
        for ((kpa, t) in listOf(86.0 to 0L, 91.0 to 400L, 95.5 to 800L, 99.0 to 1_200L)) {
            state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t)).state
            assertEquals(SealState.Unknown, state.sealState, "adopted a moving reading at $kpa")
        }
        // Settled at ambient: no vacuum, and the candidate is forgotten.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(100.9, 1_600L)).state
        assertEquals(SealState.Unknown, state.sealState)
        assertNull(state.pendingAdoptionKpa)
    }

    @Test
    fun `a persisted verified reading that still matches restores earned trust at boot`() {
        val result = machine.apply(
            SafetyState(
                sealState = SealState.Unknown,
                waterPressureKpa = 101.0,
                verifiedVacuumKpa = 81.4,
            ),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 600_000L),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 81.1, timestampMs = 600_500L))
        }
        assertEquals(SealState.Passed, result.state.sealState)
        assertEquals(SealConfidence.ManufacturerMinimum, result.state.sealConfidence)
        assertFalse(result.state.capCloseReminder, "a shell that held across a reboot has its cap on")
        assertEquals(null, result.state.verifiedVacuumKpa, "the record is consumed, never reused")
        // Backdated so the chip opens on the solid badge rather than a fresh countdown.
        assertEquals(thresholds.manufacturerMinimumMs, result.state.leakMonitoringElapsedMs)
    }

    @Test
    fun `a persisted start time restores the true hold duration, not the tier floor`() {
        // 25 minutes of real hold survive the restart as 25 minutes: the tier comes from the
        // restored clock (Recommended at 10+), and the next promotion arrives at the true
        // 30-minute mark instead of 30 minutes after the restart.
        val holdStartMs = 100_000L
        val nowMs = holdStartMs + 25 * 60_000L
        val result = machine.apply(
            SafetyState(
                sealState = SealState.Unknown,
                waterPressureKpa = 101.0,
                verifiedVacuumKpa = 81.0,
                verifiedVacuumConfidence = SealConfidence.Recommended,
                verifiedVacuumStartedAtEpochMs = holdStartMs,
            ),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = nowMs),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = nowMs + 500L))
        }
        assertEquals(SealState.Passed, result.state.sealState)
        assertEquals(SealConfidence.Recommended, result.state.sealConfidence)
        assertEquals(holdStartMs, result.state.leakMonitoringStartedAtEpochMs)
        assertNull(result.state.verifiedVacuumStartedAtEpochMs, "consumed with the record")

        // Five more minutes of samples reach the true 30-minute mark: Conservative.
        val later = machine.apply(
            result.state,
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = holdStartMs + 30 * 60_000L + 1_000L),
        )
        assertEquals(SealConfidence.Conservative, later.state.sealConfidence)
    }

    @Test
    fun `a persisted reading that no longer matches is a restart seal failure`() {
        val result = machine.apply(
            SafetyState(
                sealState = SealState.Unknown,
                waterPressureKpa = 101.0,
                verifiedVacuumKpa = 87.0,
                verifiedVacuumRecordedAtEpochMs = 60_000L,
            ),
            // Still a real vacuum, but 6 kPa shallower than the verified record: it decayed
            // while the app was away. That is a verdict, not a fresh start.
            SafetySignal.BarometricPressureSample(kpa = 93.0, timestampMs = 600_000L),
        ).let { first ->
            machine.apply(first.state, SafetySignal.BarometricPressureSample(kpa = 93.1, timestampMs = 600_500L))
        }
        assertEquals(SealState.Failed, result.state.sealState)
        assertEquals(9L, result.state.restartFailAgoMinutes, "last verified 9 minutes before the reading")
        assertEquals(null, result.state.verifiedVacuumKpa)
        assertTrue(result.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `a boot record refuted at ambient is a restart seal failure, confirmed by two readings`() {
        var state = SafetyState(
            sealState = SealState.Unknown,
            waterPressureKpa = 101.0,
            verifiedVacuumKpa = 81.0,
            verifiedVacuumConfidence = SealConfidence.ManufacturerMinimum,
            verifiedVacuumRecordedAtEpochMs = 1_000L,
        )
        // First ambient reading: held for confirmation — one glitched packet must never turn
        // last session's earned trust into a red banner.
        state = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 100.8, timestampMs = 3_601_000L),
        ).state
        assertEquals(SealState.Unknown, state.sealState)
        assertEquals(81.0, state.verifiedVacuumKpa, "record still undecided after one reading")

        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 3_601_500L),
        )
        assertEquals(SealState.Failed, result.state.sealState)
        assertEquals(60L, result.state.restartFailAgoMinutes, "an hour since the seal was last good")
        assertEquals(null, result.state.verifiedVacuumKpa)
        assertEquals(null, result.state.verifiedVacuumConfidence)
    }

    @Test
    fun `one glitched sample cannot end a healthy hold`() {
        var state = machine.apply(
            SafetyState(sealState = SealState.Unknown, waterPressureKpa = 101.0),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 0L),
        ).state
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.1, 500L)).state

        // One bad packet reads full ambient — quarantined, nothing changes.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(100.9, 1_000L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)

        // The next real reading disagrees with the outlier: the hold just continues.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_500L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)
        assertEquals(null, state.pendingOutlierKpa)
    }

    @Test
    fun `two agreeing ambient readings still end the hold as a vent`() {
        var state = machine.apply(
            SafetyState(sealState = SealState.Unknown, waterPressureKpa = 101.0),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 0L),
        ).state
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.1, 400L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState, "second agreeing reading adopts")
        state = machine.apply(state, SafetySignal.BarometricPressureSample(100.9, 900L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState, "first ambient reading quarantined")

        val result = machine.apply(state, SafetySignal.BarometricPressureSample(101.0, 1_400L))
        assertEquals(SealState.Unknown, result.state.sealState, "confirmed vent of adopted vacuum")
        assertTrue(result.effects.none { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `venting an adopted vacuum is deliberate even minutes after adoption`() {
        // Adoption at t=0 (no captured baseline — the guard refused it; water gives the reference).
        var state = machine.apply(
            SafetyState(sealState = SealState.Unknown, waterPressureKpa = 101.0),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 0L),
        ).state
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.1, 500L)).state
        assertEquals(SealState.LeakMonitoring, state.sealState)

        // Full vent two minutes later — well inside the app's 5-min hard verify, but the housing
        // had proven this seal long before the app was watching (`adoptedHold`). Quiet path, no
        // alarm. First ambient reading is quarantined; the second confirms the vent.
        state = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 100.9, timestampMs = 120_000L),
        ).state
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 101.0, timestampMs = 120_500L),
        )
        assertEquals(SealState.Unknown, result.state.sealState)
        assertTrue(result.effects.none { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `a drifting adopted seal still fails red`() {
        var state = machine.apply(
            SafetyState(sealState = SealState.Unknown, waterPressureKpa = 101.0),
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 0L),
        ).state
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 500L)).state
        // Slow creep, not a vent: three samples spreading past tolerance while far from ambient.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.8, 30_000L)).state
        val result = machine.apply(state, SafetySignal.BarometricPressureSample(82.6, 60_000L))
        assertEquals(SealState.Failed, result.state.sealState)
        assertTrue(result.effects.any { it is PlatformEffect.EmitAlert })
    }

    @Test
    fun `a hair-fine slow leak fails the seal inside the certification window`() {
        // Field case: -21.2 decaying toward -15.4 with a hair in the O-ring. Each step is far
        // too small for the spread check (which sees ~a second of samples), but the decay
        // accumulates — 2.0 kPa total inside the 5-minute window is a failed seal.
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            surfaceAmbientKpa = 101.0,
            leakMonitoringStartedAtEpochMs = 0L,
        )
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_000L)).state

        var kpa = 81.0
        var t = 1_000L
        var failed = false
        repeat(10) {
            kpa += 0.25
            t += 1_000L
            val result = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t))
            state = result.state
            if (state.sealState == SealState.Failed) {
                failed = true
                assertTrue(
                    state.warning?.contains("slow leak") == true,
                    "decay is the diagnosis: ${state.warning}",
                )
                return@repeat
            }
        }
        assertTrue(failed, "2 kPa of slow decay must fail the certification window")
    }

    @Test
    fun `after the hard verify the decay limit is thermal-tolerant but still catches real loss`() {
        var state = SafetyState(
            sealState = SealState.Passed,
            sealConfidence = SealConfidence.ManufacturerMinimum,
            surfaceAmbientKpa = 101.0,
            leakMonitoringStartedAtEpochMs = 0L,
        )
        // Anchor the hold reference well past the hard verify.
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 400_000L)).state

        // 4.5 kPa of warm-up-scale rise: within the post-verify allowance, still holding.
        var kpa = 81.0
        var t = 400_000L
        repeat(15) {
            kpa += 0.3
            t += 1_000L
            state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t)).state
            assertNotEquals(SealState.Failed, state.sealState, "thermal-scale rise failed at $kpa")
        }
        // Past 5 kPa there is no innocent explanation left — still creeping gently, so it is
        // the decay watch that fires, not the spread check.
        kpa += 0.3
        state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t + 1_000L)).state
        assertNotEquals(SealState.Failed, state.sealState)
        kpa += 0.3
        state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t + 2_000L)).state
        assertEquals(SealState.Failed, state.sealState)
        assertTrue(state.warning?.contains("slow leak") == true)
    }

    @Test
    fun `the decay watch stands down while the housing is submerged`() {
        // Hull compression at depth raises internal pressure without any leak. ~3 m of water on
        // the external sensor suspends the decay verdict; the spread and vent checks still run.
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            surfaceAmbientKpa = 101.0,
            waterPressureKpa = 131.0,
            leakMonitoringStartedAtEpochMs = 0L,
        )
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_000L)).state
        var kpa = 81.0
        var t = 1_000L
        while (kpa < 84.0) {
            kpa += 0.25
            t += 1_000L
            state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t)).state
            assertNotEquals(SealState.Failed, state.sealState, "decay tripped underwater at $kpa")
        }
    }

    @Test
    fun `a dive-cycle cool-down and warm-up never reads as decay`() {
        // The reference is the hold's FIRST reading, deliberately not a ratcheted minimum:
        // cooling deepens the vacuum, and a ratchet would read the normal warm-up back to the
        // starting pressure as a leak.
        var state = SafetyState(
            sealState = SealState.LeakMonitoring,
            surfaceAmbientKpa = 101.0,
            leakMonitoringStartedAtEpochMs = 0L,
        )
        state = machine.apply(state, SafetySignal.BarometricPressureSample(81.0, 1_000L)).state
        var kpa = 81.0
        var t = 1_000L
        // Cool: vacuum deepens to -22.5 equivalent…
        while (kpa > 79.0) {
            kpa -= 0.3
            t += 1_000L
            state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t)).state
        }
        // …then warm back up past the start by 1.5 kPa: inside the window allowance, holding.
        while (kpa < 82.5) {
            kpa += 0.3
            t += 1_000L
            state = machine.apply(state, SafetySignal.BarometricPressureSample(kpa, t)).state
            assertNotEquals(SealState.Failed, state.sealState, "thermal cycle failed at $kpa")
        }
    }

    @Test
    fun `a failed seal is not resurrected by pressure alone`() {
        val state = SafetyState(sealState = SealState.Failed, surfaceAmbientKpa = 101.0)
        val result = machine.apply(
            state,
            SafetySignal.BarometricPressureSample(kpa = 81.0, timestampMs = 50_000L),
        )
        assertEquals(SealState.Failed, result.state.sealState)
    }
}
