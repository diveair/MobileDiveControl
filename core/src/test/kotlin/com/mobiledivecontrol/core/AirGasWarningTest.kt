package com.mobiledivecontrol.core

import kotlin.test.*

class AirGasWarningTest {
    private val dive = DiveProfileState(sensorAvailable = true, depthMeters = 20.0,
        active = DiveSession(1, DiveSettings(), elapsedMs = 300_000, maxDepthMeters = 20.0))
    private fun air(bar: Double = 200.0) = AirAlertState(
        settings = AirAlertSettings(enabled = true), sessionId = 1, elapsedMs = 300_000,
        ambientMinutes = 15.0, surfaceBarPerMinute = 2.0,
        observation = AirObservation(bar, 300_000, 15.0),
        lastManualObservation = AirObservation(bar, 300_000, 15.0),
        rateAnchor = AirObservation(bar + 30, 0, 0.0))
    private val ascentBudget get() = AirAlertEngine.estimate(air(), dive, 300_000)!!.ascentAndStopBar!!
    private fun atSurface(bar: Double) = air(ascentBudget + bar)
    private fun tick(air: AirAlertState, profile: DiveProfileState = dive) = AirGasWarnings.tick(air, profile, 300_000)
    private fun app(air: AirAlertState) = AppState(mode = AppMode.CameraLive,
        housing = HousingState(inputEnabled = true), diveProfile = dive, airAlerts = air)
    private fun command(state: AppState, command: AirAlertCommand) = reduceAirAlerts(state, command, 300_000).state

    @Test fun reserveTriggersAtAscentPlusReserveEvenAfterOnceOnlySchedule() {
        assertNull(tick(atSurface(50.1)).gasWarning)
        val source = atSurface(50.0).let { it.copy(settings = it.settings.copy(schedule = AirSchedule.Once), delivered = 1) }
        val warned = tick(source)
        assertEquals(AirGasLevel.Reserve, warned.gasWarning!!.level)
        assertEquals(50.0, warned.gasWarning!!.projectedSurfaceBar!!, 1e-9)
        assertEquals("BEGIN PLANNED ASCENT TO SAFETY STOP", airGasTitle(warned.gasWarning!!, dive))
        val started = dive.copy(active = dive.active!!.copy(stopStarted = true))
        assertEquals("CHECK AIR RESERVE", airGasTitle(warned.gasWarning!!, started))
        assertFalse(warned.gasWarning!!.promptsPlannedStopAscent(started))
        assertTrue(warned.gasWarning!!.visible)
    }

    @Test fun skippingKeepsMonitoringAndShortensIntervalsAsGasFalls() {
        val warned = tick(atSurface(45.0))
        val skipped = AirGasWarnings.skip(warned)
        assertFalse(skipped.gasWarning!!.visible)
        assertFalse(tick(skipped.copy(elapsedMs = 329_999)).gasWarning!!.visible)
        assertTrue(tick(skipped.copy(elapsedMs = 330_000)).gasWarning!!.visible)
        val low = tick(atSurface(20.0).copy(gasWarning = skipped.gasWarning))
        assertEquals(AirGasLevel.Low, low.gasWarning!!.level)
        assertEquals(315_000, AirGasWarnings.skip(low).gasWarning!!.nextAtMs)
        val lower = tick(atSurface(10.0).copy(gasWarning = low.gasWarning))
        assertEquals(305_000, AirGasWarnings.skip(lower).gasWarning!!.nextAtMs)
    }

    @Test fun actualReportsUpdateRiskImmediatelyAndKeepDraftsUncommitted() {
        var state = app(tick(atSurface(45.0)))
        state = command(state, AirAlertCommand.GasAction(0))
        val old = state.airAlerts.observation
        state = command(state, AirAlertCommand.SetPressure(20))
        assertEquals(old, state.airAlerts.observation)
        state = command(state, AirAlertCommand.SavePressure)
        assertEquals(20.0, state.airAlerts.observation!!.bar)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
    }

    @Test fun projectedEmptyIsRiskWhileReportedZeroIsAnEmptyTank() {
        assertEquals(AirGasLevel.Critical, tick(atSurface(0.0)).gasWarning!!.level)
        val empty = tick(air(0.0).copy(surfaceBarPerMinute = null))
        assertEquals(AirGasLevel.Empty, empty.gasWarning!!.level)
        assertTrue(airGasSpeech(AirGasLevel.Critical).contains("risk"))
        assertFalse(airGasSpeech(AirGasLevel.Critical).contains("SAFETY STOP NOW"))
    }

    @Test fun criticalSurvivesSkipLostDataAndAlertDisableUntilFreshSurface() {
        val critical = tick(atSurface(-1.0))
        assertTrue(AirGasWarnings.skip(critical).gasWarning!!.visible)
        val lost = critical.copy(surfaceBarPerMinute = null)
        assertTrue(tick(lost, dive.copy(sensorAvailable = false, active = null)).gasWarning!!.level.critical)
        assertTrue(tick(lost, dive.copy(sensorAvailable = false, depthMeters = 0.0)).gasWarning!!.level.critical)
        assertNull(tick(lost, dive.copy(sensorAvailable = true, depthMeters = 0.0, active = null)).gasWarning)
    }

    @Test fun noEstimatedAlarmWithoutContinuousPressureAndDepthData() {
        assertNull(tick(atSurface(0.0).copy(surfaceBarPerMinute = null)).gasWarning)
        assertNull(tick(atSurface(0.0).copy(depthGap = true)).gasWarning)
        assertNull(tick(atSurface(0.0), dive.copy(sensorAvailable = false)).gasWarning)
        assertNull(tick(atSurface(0.0), dive.copy(depthMeters = 1.0)).gasWarning)
        assertNull(tick(atSurface(0.0), dive.copy(active = dive.active!!.copy(limitReached = true))).gasWarning)
    }

    @Test fun verifiedStopTimeReducesRemainingExitBudget() {
        val completed = dive.copy(depthMeters = 5.0,
            active = dive.active!!.copy(stopElapsedMs = 180_000, stopStarted = true, phase = DiveStopPhase.Complete))
        val unstarted = completed.copy(active = completed.active!!.copy(stopElapsedMs = 0, stopStarted = false, phase = DiveStopPhase.Armed))
        val difference = AirGasWarnings.projectedSurfaceBar(air(), completed, 300_000)!! -
            AirGasWarnings.projectedSurfaceBar(air(), unstarted, 300_000)!!
        assertEquals(9.0, difference, 1e-9) // 3 minutes x 1.5 ATA x 2 BAR per surface minute.
    }

    @Test fun openingOrCancellingTankSwitchNeverSilencesCriticalAlarm() {
        var state = app(tick(atSurface(0.0)))
        state = command(state, AirAlertCommand.GasAction(1))
        assertEquals(AirEntryPurpose.NextTank, state.airAlerts.entry!!.purpose)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
        state = command(state, AirAlertCommand.CancelEntry)
        assertEquals(1, state.airAlerts.tankNumber)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
        state = command(state, AirAlertCommand.GasAction(1))
        state = command(state, AirAlertCommand.SetPressure(30))
        state = command(state, AirAlertCommand.SavePressure)
        assertNotNull(state.airAlerts.entry!!.error)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
    }

    @Test fun confirmedSecondTankCreatesSeparateBaselineAndRequestsEarlyReading() {
        val first = tick(atSurface(0.0))
        var state = command(app(first), AirAlertCommand.GasAction(1))
        state = command(state, AirAlertCommand.SetPressure(200))
        state = command(state, AirAlertCommand.SavePressure)
        val second = state.airAlerts
        assertEquals(2, second.tankNumber)
        assertEquals(first.observation, second.previousTanks.single().lastReading)
        assertNull(second.gasWarning)
        assertNull(second.surfaceBarPerMinute)
        assertEquals(200.0, second.observation!!.bar)
        assertNull(AirAlertEngine.estimate(second, dive, 300_000)!!.bottomMinutes)
        assertNotNull(tick(second.copy(elapsedMs = 330_000)).prompt)
        val reported = AirAlertEngine.observe(second.copy(elapsedMs = 330_000, ambientMinutes = 16.5), 190.0)
        assertEquals(10.0 / 1.5, reported.surfaceBarPerMinute!!, 1e-9)
        assertNotNull(AirAlertEngine.estimate(reported, dive, 330_000)!!.bottomMinutes)
        assertEquals(first.settings, second.settings)
    }

    @Test fun oldReceiverCannotReplaceSecondTankManualReading() {
        val receiver = air().copy(settings = air().settings.copy(source = AirSource.Receiver,
            mapping = AirPressureMapping(verified = true)),
            receiver = AirReceiverState(connected = true, liveBar = 40.0, liveAtMs = 300_000))
        val second = AirGasWarnings.switchTank(receiver, 200.0).let {
            it.copy(receiver = it.receiver.copy(liveBar = 35.0, liveAtMs = 300_001))
        }
        assertNull(AirAlertEngine.liveBar(second, 300_001))
        assertEquals(200.0, AirAlertEngine.estimate(second, dive, 300_001)!!.pressureBar)
        assertEquals("TANK 2 · 200 BAR · MANUAL", airCameraReadout(second.copy(
            settings = second.settings.copy(hudMode = AirHudMode.LivePressure)), dive, 300_001)!!.text)
    }

    @Test fun housingCanSelectSecondTankAndCriticalOverridesStopPresentation() {
        var state = app(tick(atSurface(0.0))).copy(diveProfile = dive.copy(
            active = dive.active!!.copy(stopStarted = true, phase = DiveStopPhase.Holding)))
        assertFalse(airAlertPresentationBlocked(state))
        for (button in listOf(HousingButtonEvent.Right, HousingButtonEvent.Ok)) {
            InputRouter().route(state, button).commands.forEach {
                state = command(state, (it as DiveSettingsCommand.Alerts).command)
            }
        }
        assertEquals(AirEntryPurpose.NextTank, state.airAlerts.entry!!.purpose)
    }

    @Test fun debugWarningUsesIsolatedModelAndStopsWithTest() {
        val testAir = atSurface(0.0).copy(sessionId = -1)
        val simulation = AirSimulation(testAir, DiveSettings(), 0, intervalMs = 300_000)
        val host = AirAlertState(debugControls = true, menuOpen = true, simulation = simulation)
        val ticked = AirAlertEngine.tick(host, DiveProfileState(), 1_000)
        assertTrue(ticked.simulation!!.air.gasWarning!!.level.critical)
        assertNull(ticked.gasWarning)
        val stopped = command(AppState(airAlerts = ticked), AirAlertCommand.StopTest).airAlerts
        assertNull(stopped.simulation)
        assertNull(stopped.gasWarning)
    }
    @Test fun criticalPreemptsSettingsEditorsAndUpgradesWhenTankIsReportedEmpty() {
        val critical = tick(atSurface(0.0).copy(entry = AirPressureEntry(AirEntryPurpose.Interval, "60")))
        assertNull(critical.entry)
        val empty = tick(critical.copy(observation = AirObservation(0.0, 300_000, 15.0)))
        assertEquals(AirGasLevel.Empty, empty.gasWarning!!.level)
        assertTrue(empty.gasWarningSequence > critical.gasWarningSequence)
        val editingTank = empty.copy(entry = AirPressureEntry(AirEntryPurpose.NextTank, "200"))
        assertEquals(AirEntryPurpose.NextTank, tick(editingTank).entry!!.purpose)
    }

    @Test fun shallowDiveUsesAscentMessageWithoutInventingSafetyStop() {
        val shallow = dive.copy(depthMeters = 2.0, active = dive.active!!.copy(maxDepthMeters = 2.0))
        assertEquals("BEGIN YOUR PLANNED ASCENT", airGasTitle(
            AirGasWarning(AirGasLevel.Reserve, nextAtMs = 0), shallow))
    }

    @Test fun newTankKeepsRequestingReadingsUntilConsumptionIsKnown() {
        val switched = AirGasWarnings.switchTank(air(), 200.0)
        val first = tick(switched.copy(elapsedMs = 330_000))
        assertNotNull(first.prompt)
        val unchanged = AirAlertEngine.observe(first.copy(ambientMinutes = 16.5), 200.0)
        val next = tick(unchanged.copy(elapsedMs = 360_000, prompt = null))
        assertNotNull(next.prompt)
        val learned = AirAlertEngine.observe(next.copy(ambientMinutes = 18.0), 190.0)
        assertNull(tick(learned).tankCheckDueMs)
    }

    @Test fun finiteDebugScheduleStillMonitorsGasAndNewTank() {
        val settings = air().settings.copy(schedule = AirSchedule.Once, intervalSeconds = 300)
        val source = atSurface(51.0).copy(settings = settings)
        val test = AirSimulation(source, DiveSettings(), 0, intervalMs = 0, complete = true)
        val warned = AirSimulationEngine.tick(test, 1_000)
        assertNotNull(warned.air.gasWarning)
        assertTrue(warned.complete)
        assertEquals(0, warned.checks)
        val switched = warned.copy(air = AirGasWarnings.switchTank(warned.air, 200.0))
        val check = AirSimulationEngine.tick(switched, 2_000)
        assertNotNull(check.air.prompt)
        assertTrue(check.air.prompt!!.confirmation)
        assertEquals(2, check.air.tankNumber)
    }

    @Test fun criticalWarningIncludesReactionTimeAndPressureMarginBeforeZero() {
        val threshold = AirGasWarnings.criticalSurfaceMarginBar(air(), dive)
        assertEquals(18.5, threshold, 1e-9) // 1 minute at 20 m x 2 BAR/min + 25% of 50 BAR.
        assertFalse(tick(atSurface(threshold + 0.01)).gasWarning!!.level.critical)
        val warned = tick(atSurface(threshold))
        assertEquals(AirGasLevel.Critical, warned.gasWarning!!.level)
        assertTrue(warned.gasWarning!!.projectedSurfaceBar!! > AirPressureUnit.Psig.toBar(10))
        assertEquals(AirGasLevel.Critical, tick(air(AirPressureUnit.Psig.toBar(10)).copy(
            surfaceBarPerMinute = null)).gasWarning!!.level)
    }

    @Test fun slowerMeasuredAscentWarnsAtHigherTankPressureAndFastAscentCannotDelayIt() {
        val samples = listOf(DiveSample(295_000, 20.25), DiveSample(300_000, 20.0))
        val slow = dive.copy(lastSampleMs = 300_000, rateWindow = samples, verticalMetersPerMinute = -3.0)
        val normal = slow.copy(verticalMetersPerMinute = -6.0)
        val fast = slow.copy(verticalMetersPerMinute = -18.0)
        val boundary = atSurface(AirGasWarnings.criticalSurfaceMarginBar(air(), dive) + 2.0)
        assertFalse(tick(boundary, normal).gasWarning!!.level.critical)
        assertTrue(tick(boundary, slow).gasWarning!!.level.critical)
        assertEquals(tick(boundary, normal).gasWarning, tick(boundary, fast).gasWarning)
    }

    @Test fun buddyAirRequiresConfirmationAndKeepsAlarmWhenCancelled() {
        var state = app(tick(atSurface(10.0)))
        state = command(state, AirAlertCommand.ConfirmBuddyAir)
        assertFalse(state.airAlerts.sharedAir)
        state = command(state, AirAlertCommand.GasAction(2))
        assertTrue(state.airAlerts.buddyConfirmation)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
        state = command(state, AirAlertCommand.CancelBuddyAir)
        assertFalse(state.airAlerts.buddyConfirmation)
        assertTrue(state.airAlerts.gasWarning!!.level.critical)
        state = command(state, AirAlertCommand.GasAction(2))
        val routed = InputRouter().route(state, HousingButtonEvent.Ok).commands.single() as DiveSettingsCommand.Alerts
        state = command(state, routed.command)
        assertTrue(state.airAlerts.sharedAir)
        assertNull(state.airAlerts.gasWarning)
        assertNull(AirAlertEngine.estimate(state.airAlerts, dive, 300_000))
        assertNull(airCameraReadout(state.airAlerts, dive, 300_000))
        assertNull(tick(state.airAlerts).gasWarning)
    }

    @Test fun sharedAirIgnoresOwnReceiverAndCanReturnToConfirmedTank() {
        var state = command(command(app(tick(atSurface(10.0))), AirAlertCommand.GasAction(2)), AirAlertCommand.ConfirmBuddyAir)
        val shared = state.airAlerts.copy(settings = state.airAlerts.settings.copy(source = AirSource.Receiver,
            mapping = AirPressureMapping(verified = true)), receiver = AirReceiverState(connected = true, liveBar = 0.0, liveAtMs = 300_000))
        assertNull(AirAlertEngine.liveBar(shared, 300_000))
        assertNull(tick(shared).gasWarning)
        assertEquals(shared.observation, AirAlertEngine.observe(shared, 0.0).observation)
        val ticked = AirAlertEngine.tick(shared.copy(lastTickMs = 300_000, lastAmbient = 3.0), dive, 301_000)
        assertTrue(ticked.sharedAir)
        assertNull(ticked.prompt)
        state = command(state, AirAlertCommand.GasAction(1))
        assertEquals(AirEntryPurpose.NextTank, state.airAlerts.entry!!.purpose)
        state = command(command(state, AirAlertCommand.SetPressure(200)), AirAlertCommand.SavePressure)
        assertFalse(state.airAlerts.sharedAir)
        assertNull(state.airAlerts.surfaceBarPerMinute)
        assertNotNull(tick(state.airAlerts.copy(elapsedMs = 330_000)).prompt)
        assertFalse(tick(shared, dive.copy(depthMeters = 0.0)).sharedAir)
    }

    @Test fun criticalMinimizesOnlyOptionalPanelAndBuddyAirRestoresOriginalPresentation() {
        val holding = dive.copy(active = dive.active!!.copy(stopStarted = true, phase = DiveStopPhase.Holding, stopElapsedMs = 45_000))
        val original = app(tick(atSurface(10.0))).copy(diveProfile = holding)
        val shown = airWarningStopProfile(original)
        assertEquals(DiveStopPresentation.Minimized, shown.stopPresentation)
        assertEquals(holding.active, shown.active)
        val obligation = original.copy(diveProfile = holding.copy(active = holding.active!!.copy(limitReached = true)))
        assertEquals(obligation.diveProfile, airWarningStopProfile(obligation))
        val paused = original.copy(diveProfile = holding.copy(stopDecompressionPaused = true))
        assertEquals(paused.diveProfile, airWarningStopProfile(paused))
        val shared = command(command(original, AirAlertCommand.GasAction(2)), AirAlertCommand.ConfirmBuddyAir)
        assertEquals(holding, airWarningStopProfile(shared))
        assertEquals(180, shared.diveProfile.active!!.settings.stopDurationSeconds)
        assertEquals(45_000, shared.diveProfile.active!!.stopElapsedMs)
    }

    @Test fun sharedAirAndConfirmationStayInsideDebugSimulation() {
        val test = AirSimulation(tick(atSurface(10.0)), DiveSettings(), 0, intervalMs = 300_000)
        var state = AppState(airAlerts = AirAlertState(debugControls = true, menuOpen = true, simulation = test))
        state = command(command(state, AirAlertCommand.GasAction(2)), AirAlertCommand.ConfirmBuddyAir)
        assertTrue(state.airAlerts.simulation!!.air.sharedAir)
        assertFalse(state.airAlerts.sharedAir)
        val advanced = AirAlertEngine.tick(state.airAlerts, DiveProfileState(), 20_000)
        assertNull(advanced.simulation!!.air.prompt)
        assertNull(advanced.simulation!!.air.gasWarning)
        assertNull(command(state, AirAlertCommand.StopTest).airAlerts.simulation)
    }

    @Test fun confirmedEmptyStartsAlarmEvenIfDepthSensorFailsDuringDive() {
        val lost = dive.copy(sensorAvailable = false, depthMeters = null)
        val state = app(air().copy(entry = AirPressureEntry(AirEntryPurpose.Reading, "0")))
            .copy(diveProfile = lost)
        val reported = command(state, AirAlertCommand.SavePressure)
        assertEquals(AirGasLevel.Empty, reported.airAlerts.gasWarning!!.level)
        val nearEmpty = air(AirPressureUnit.Psig.toBar(10)).copy(surfaceBarPerMinute = null)
        assertEquals(AirGasLevel.Critical, tick(nearEmpty, lost).gasWarning!!.level)
        assertEquals(AirGasLevel.Critical, tick(nearEmpty, dive.copy(depthMeters = 0.5)).gasWarning!!.level)
        assertNull(tick(nearEmpty, dive.copy(depthMeters = 0.0)).gasWarning)
    }

}
