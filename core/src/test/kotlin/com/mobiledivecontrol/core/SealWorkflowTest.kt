package com.mobiledivecontrol.core

import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import java.time.Instant
import kotlin.test.*

class SealWorkflowTest {
    private var now = System.currentTimeMillis()
    private val motorOn = PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(true))
    private val motorOff = PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(false))
    private var visible = true
    private var metric = true
    private val core = ControlCore(monotonicMs = { now }, reducer = ControlReducer(nowMs = { now }),
        displayedSealBanner = { it.safety.sealMonitor.banner.takeIf { visible } },
        useMetricDepth = { metric })
    private val monitor get() = core.state.safety.sealMonitor

    private fun sample(water: Double = 101.3, baro: Double = 101.3) {
        now += 500
        core.updateSensor(SensorUpdate.WaterPressure(water))
        core.updateSensor(SensorUpdate.BarometricPressure(baro))
    }

    private fun button(byte: Int, delayMs: Long = 120): ProcessingOutcome {
        now += delayMs
        return core.handleButtonPayload(byteArrayOf(byte.toByte()), receivedAt = Instant.ofEpochMilli(now))
    }

    private fun surface() {
        core.advanceBle(BleSignal.Ready)
        core.updateSensor(SensorUpdate.CoverState(true))
        repeat(2) { sample() }
    }

    private fun incident() {
        surface()
        sample(water = 140.0)
        assertEquals(Banner.B10, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        button(0x50)
        repeat(2) { sample() }
        assertEquals(Banner.B09, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        assertEquals(Banner.B15, monitor.banner)
    }

    private fun startTest() {
        assertFalse(motorOn in button(0x61).effects)
        assertFalse(motorOn in button(0x30).effects)
        assertTrue(button(0x50).effects.isEmpty())
        assertTrue(monitor.inspected)
        assertEquals(Banner.B03, monitor.banner)
        assertTrue(motorOn in button(0x61).effects)
        assertEquals(Banner.B16, monitor.banner)
    }

    private fun dismissAndOpenModes() {
        assertEquals(Banner.B03, monitor.banner)
        assertFalse(core.state.vacuumMenuAvailable)
        assertTrue(button(0x50).effects.isEmpty())
        assertEquals(Banner.B00, monitor.banner)
        assertTrue(core.state.vacuumMenuAvailable)
        val camera = core.state.camera
        assertEquals(CameraUiZone.SettingsPanel, camera.focusedZone)
        assertEquals(BottomBarItem.ModesButton, CameraCatalog.settingsBarItems(camera)[camera.settingsCursor])
        assertTrue(button(0x50, delayMs = 50).effects.isEmpty())
        assertEquals(CameraUiZone.SettingsPanel, core.state.camera.focusedZone)
        assertTrue(button(0x50).effects.isEmpty())
        assertTrue(core.state.camera.vacuumEntrySelected)
        assertTrue(button(0x50, delayMs = 50).effects.isEmpty())
        assertTrue(core.state.camera.vacuumEntrySelected)
    }

    @Test fun `inspection follows current depth and remembers submersion through resurfacing and reconnect`() {
        incident()
        assertTrue(monitor.submergedSinceSealCheck)
        button(0x50)
        assertEquals(Banner.B03, monitor.banner)
        sample(water = 140.0)
        assertFalse(monitor.inspected)
        assertEquals(Banner.B10, monitor.banner)
        button(0x50) // The existing underwater warning owns the first acknowledgment.
        assertEquals(Banner.B15, monitor.banner)
        assertTrue(button(0x50).effects.isEmpty())
        assertFalse(monitor.inspected)
        assertTrue(button(0x61).effects.isEmpty())
        repeat(2) { sample() }
        assertEquals(Banner.B15, monitor.banner)
        val incidentHistory = monitor.incidents
        assertTrue(button(0x50).effects.isEmpty())
        assertEquals(Banner.B03, monitor.banner)
        assertTrue(button(0x50, 50).effects.isEmpty())
        assertEquals(Banner.B03, monitor.banner)
        dismissAndOpenModes()
        button(0x50)
        assertEquals(Banner.B03, monitor.banner)
        assertEquals(incidentHistory, monitor.incidents)
        core.updateSensor(SensorUpdate.CoverState(false))
        assertFalse(motorOn in button(0x61).effects)
        core.advanceBle(BleSignal.Disconnect)
        surface()
        assertTrue(monitor.submergedSinceSealCheck)
        assertFalse(monitor.inspected)
    }

    @Test fun `vacuum menu reopens with OK shutter and touch and only a separate visible DOWN starts the pump`() {
        surface()
        val initialCamera = core.state.camera
        for (selection in listOf(0x50, 0x20, null)) {
            dismissAndOpenModes()
            val result = if (selection == null) core.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.VACUUM_RAIL_INDEX))
                else button(selection)
            assertTrue(result.effects.isEmpty())
            assertEquals(Banner.B03, monitor.banner)
            assertFalse(core.state.vacuumMenuAvailable)
            assertEquals(CameraUiZone.SettingsPanel, core.state.camera.focusedZone)
            if (selection != null) {
                assertTrue(button(selection, delayMs = 50).effects.isEmpty())
                assertEquals(Banner.B03, monitor.banner)
            }
        }
        assertEquals(initialCamera.activeMode, core.state.camera.activeMode)
        assertEquals(initialCamera.settingValues, core.state.camera.settingValues)
        visible = false
        assertFalse(motorOn in button(0x61).effects)
        visible = true
        assertTrue(motorOn in button(0x61).effects)
        assertEquals(Banner.Pumping, monitor.banner)
    }

    @Test fun `track heading and shutter capture remain accessible beside the dismissed vacuum action`() {
        surface()
        core.updatePermission(PermissionKind.Camera, true)
        dismissAndOpenModes()
        button(0x61)
        assertEquals(CameraRailAction.ShutOff, core.state.camera.primaryHighlightedEntry.action)
        button(0x61)
        assertEquals(CameraRailAction.TrackHeading, core.state.camera.primaryHighlightedEntry.action)
        val capture = button(0x20)
        assertTrue(PlatformEffect.ExecuteCamera(CameraCommand.CapturePhoto) in capture.effects)
        assertTrue(core.state.vacuumMenuAvailable)
        assertTrue(PlatformEffect.TrackCurrentHeading in button(0x50).effects)
        core.dispatch(CameraCommand.OpenModeRail)
        assertTrue(core.state.camera.vacuumEntrySelected)
    }

    @Test fun `loss of surface eligibility withdraws the selected shortcut and ignores a stale tap`() {
        surface()
        dismissAndOpenModes()
        fun checkWithdrawn(expected: Banner) {
            assertEquals(expected, monitor.banner)
            assertFalse(core.state.vacuumMenuAvailable)
            assertFalse(core.state.camera.vacuumEntrySelected)
            val before = core.state.safety
            assertTrue(core.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.VACUUM_RAIL_INDEX)).effects.isEmpty())
            assertEquals(before, core.state.safety)
        }
        now += PRESSURE_STALE_MS
        core.tickDiveProfile(now)
        checkWithdrawn(Banner.B12)
        repeat(2) { sample() }
        assertTrue(core.state.vacuumMenuAvailable)
        core.dispatch(CameraCommand.OpenModeRail)
        core.updateSensor(SensorUpdate.CoverState(false))
        checkWithdrawn(Banner.Cap)
        core.updateSensor(SensorUpdate.CoverState(true))
        dismissAndOpenModes()
        sample(water = 140.0)
        checkWithdrawn(Banner.B10)
        core.advanceBle(BleSignal.Disconnect)
        checkWithdrawn(Banner.B01)
    }

    @Test fun `reopening from video preserves the clip and the next deliberate shutter press pauses it`() {
        surface()
        core.updatePermission(PermissionKind.Camera, true)
        core.updatePermission(PermissionKind.Microphone, true)
        core.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryIndexForMode(CameraModeId.Video)))
        button(0x20)
        assertTrue(core.state.camera.recording)
        dismissAndOpenModes()
        assertTrue(button(0x20).effects.isEmpty())
        assertEquals(Banner.B03, monitor.banner)
        assertTrue(core.state.camera.recording)
        assertFalse(core.state.camera.recordingPaused)
        assertTrue(button(0x20, delayMs = 50).effects.isEmpty())
        button(0x20)
        assertTrue(core.state.camera.recordingPaused)
        assertFalse(core.state.vacuumMenuAvailable)
    }

    @Test fun `restarting with the same surface vacuum resumes monitoring without replaying pump completion`() {
        surface()
        assertTrue(motorOn in button(0x61).effects)
        sample(baro = 80.5)
        assertEquals(Banner.Reached, monitor.banner)
        core.updateSensor(SensorUpdate.CoverState(false))
        repeat(3) { sample(baro = 80.5) }
        var saved = assertNotNull(monitor.last)
        assertEquals(HousingSealTransitions.Vacuum.Stable, saved.observation.vacuum)

        repeat(2) {
            // The app restores this snapshot even before the hold earns a verified-vacuum record.
            val restarted = ControlCore(initialState = AppState(safety = SafetyState(
                sealMonitor = SealMonitor(snapshot = saved, wasDisconnected = true),
            )), monotonicMs = { now }, reducer = ControlReducer(nowMs = { now }))
            restarted.advanceBle(BleSignal.Ready)
            restarted.updateSensor(SensorUpdate.CoverState(true)) // The housing can replay its open byte.
            repeat(3) {
                now += 500
                restarted.updateSensor(SensorUpdate.WaterPressure(101.3))
                val reading = restarted.updateSensor(SensorUpdate.BarometricPressure(80.6))
                assertNotEquals(Banner.Reached, reading.state.safety.sealMonitor.banner)
                assertTrue(reading.effects.none { it is PlatformEffect.ExecuteHousing })
            }
            val safety = restarted.state.safety
            assertEquals(Banner.Status, safety.sealMonitor.banner)
            assertEquals(SealState.LeakMonitoring, safety.sealState)
            assertEquals(SealConfidence.Monitoring, safety.sealConfidence)
            saved = assertNotNull(safety.sealMonitor.last)
        }
    }

    @Test fun `historical verification needs both hold intervals and clears just the seal history`() {
        incident()
        startTest()
        sample(baro = 80.5)
        core.updateSensor(SensorUpdate.CoverState(false))
        repeat(365) { sample(baro = 80.5) }
        assertTrue(monitor.incidents.isNotEmpty())
        assertEquals(Banner.B16, monitor.banner)
        repeat(243) { sample(baro = 80.5) }
        assertEquals(Banner.B18, monitor.banner)
        assertTrue(monitor.incidents.isEmpty())
        assertFalse(monitor.submergedSinceSealCheck)
        assertNull(monitor.snapshot)
        repeat(21) { sample(baro = 80.5) }
        core.updateSensor(SensorUpdate.CoverState(true))
        repeat(4) { sample() }
        assertTrue(monitor.incidents.isEmpty())
        confirmRelease()
        assertEquals(Banner.Release, monitor.banner)
        button(0x10)
        assertFalse(motorOn in button(0x50).effects)
        assertEquals(Banner.B03, monitor.banner)
    }

    @Test fun `pressure started leak check shows the verified result and never replays the cap reminder`() {
        incident()
        startTest()
        repeat(8) { sample(baro = 80.5) } // Some housings never send the cap-closed byte.
        assertTrue(core.state.safety.capCloseReminder)
        assertEquals(Banner.B16, monitor.banner)
        repeat(360) { sample(baro = 80.5) }
        assertEquals(Verification.Testing, monitor.verification)
        repeat(240) { sample(baro = 80.5) }
        assertEquals(Banner.B18, monitor.banner)
        assertFalse(core.state.safety.capCloseReminder)
        repeat(22) { sample(baro = 80.5) }
        assertEquals(Banner.Status, monitor.banner)
        assertEquals(SealState.Passed, core.state.safety.sealState)
    }

    private fun verifiedSurfaceHold() {
        surface()
        assertTrue(motorOn in button(0x61).effects)
        sample(baro = 80.5)
        core.updateSensor(SensorUpdate.CoverState(false))
        repeat(605) { sample(baro = 80.5) }
        assertEquals(SealConfidence.ManufacturerMinimum, core.state.safety.sealConfidence)
    }

    private fun confirmRelease() {
        assertEquals(Banner.ConfirmRelease, monitor.banner)
        assertFalse(monitor.releaseAnswerYes)
        button(0x10)
        assertTrue(button(0x50).effects.isEmpty())
        assertEquals(Banner.Release, monitor.banner)
        button(0x50, delayMs = 50)
        assertFalse(core.state.housingStandby)
    }

    @Test fun `silver valve vent with the blue cap closed skips transient warnings and reconnect restores setup`() {
        verifiedSurfaceHold()
        for (pressure in listOf(87.5, 94.0, 100.7)) {
            sample(baro = pressure)
            assertEquals(Banner.B00, monitor.banner)
            assertTrue(monitor.incidents.isEmpty())
        }
        sample()
        confirmRelease()
        assertEquals(Banner.Release, monitor.banner)
        assertEquals(false, core.state.safety.coverOpen)
        assertTrue(monitor.incidents.isEmpty())
        core.advanceBle(BleSignal.Disconnect)
        assertEquals(Banner.B01, monitor.banner)
        surface()
        assertEquals(Banner.B03, monitor.banner)
    }

    @Test fun `S24 surface pressure ramp at 109 seconds produces one release question`() {
        fun packet(id: String, raw: Int): ProcessingOutcome = core.handleNotificationPayload(id,
            ByteArray(4) { (raw ushr (8 * it)).toByte() }, Instant.ofEpochMilli(now))
        fun barometer(pa: Int) = packet("1627", pa)
        fun depth() = packet("1625", 10079)
        core.advanceBle(BleSignal.Ready)
        core.updateSensor(SensorUpdate.CoverState(true))
        repeat(2) { now += 500; depth(); barometer(100839) }
        assertTrue(motorOn in button(0x61).effects)
        repeat(6) { now += 500; depth(); barometer(79128) }
        assertEquals(Banner.Reached, monitor.banner)
        button(0x50)
        val start = assertNotNull(core.state.safety.leakMonitoringStartedAtEpochMs)
        while (now - start < 108_000) { now += 500; depth(); barometer(79128) }
        assertEquals(SealConfidence.Monitoring, core.state.safety.sealConfidence)
        // The log contains the hold, intermediate failure and ambient end points. The
        // other samples reconstruct a rising pressure trace at the observed cadence.
        val banners = mutableListOf<Banner>()
        for (pa in listOf(88500, 92974, 97900, 100792, 100800)) {
            now += 500
            for (event in listOf(depth(), barometer(pa), core.tickDiveProfile(now))) {
                assertFalse(motorOn in event.effects)
                val banner = event.state.safety.sealMonitor.banner
                if (banner != Banner.B00 && banner != Banner.Status && banners.lastOrNull() != banner)
                    banners += banner
            }
        }
        assertEquals(listOf(Banner.ConfirmRelease), banners)
        assertNull(core.state.safety.leakMonitoringStartedAtEpochMs)
        assertTrue(monitor.incidents.isEmpty())
        assertEquals(Banner.B15, button(0x50).state.safety.sealMonitor.banner)
        assertFalse(motorOn in button(0x61).effects)
    }

    @Test fun `surface loss while disconnected needs confirmation and No preserves an inspection requirement`() {
        verifiedSurfaceHold()
        core.advanceBle(BleSignal.Disconnect)
        assertTrue(assertNotNull(monitor.snapshot).verifiedHold)
        surface()
        sample()
        assertEquals(Banner.ConfirmRelease, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        assertFalse(motorOn in button(0x50).effects)
        assertEquals(Banner.B15, monitor.banner)
        assertTrue(SealIncident.SurfaceLeak in monitor.incidents)
        assertFalse(monitor.inspected)
        assertFalse(monitor.submergedSinceSealCheck)
        assertFalse(motorOn in button(0x61).effects)
    }

    @Test fun `a healthy dive before a later surface loss still counts as prior water exposure`() {
        verifiedSurfaceHold()
        repeat(3) { sample(water = 140.0, baro = 80.5) }
        repeat(3) { sample(baro = 80.5) }
        assertTrue(monitor.incidents.isEmpty())
        assertTrue(monitor.submergedSinceSealCheck)
        repeat(3) { sample() }
        assertEquals(Banner.ConfirmRelease, monitor.banner)
        button(0x50)
        assertEquals(Banner.B15, monitor.banner)
        assertTrue(monitor.submergedSinceSealCheck)
    }

    @Test fun `surface release during second countdown first seen on reconnect can restart only from zero`() {
        surface()
        assertTrue(motorOn in button(0x61).effects)
        repeat(380) { sample(baro = 80.5) }
        assertEquals(SealConfidence.Provisional, core.state.safety.sealConfidence)
        core.advanceBle(BleSignal.Disconnect)
        assertFalse(assertNotNull(monitor.snapshot).verifiedHold)
        surface()
        sample()
        confirmRelease()
        button(0x10)
        assertFalse(motorOn in button(0x50).effects)
        assertEquals(Banner.B03, monitor.banner)
        assertTrue(motorOn in button(0x61).effects)
        repeat(8) { sample(baro = 80.5) }
        assertEquals(SealConfidence.Monitoring, core.state.safety.sealConfidence)
        assertTrue(core.state.safety.leakMonitoringElapsedMs in 0..4_000L)
    }

    @Test fun `Modes Shut Off uses the first real OK deadline and stays asleep until genuine same-link wake`() {
        verifiedSurfaceHold()
        val original = core.state.camera.activeMode
        repeat(2) {
            core.dispatch(CameraCommand.OpenModeRail)
            assertEquals(CameraRailAction.TrackHeading, core.state.camera.primaryHighlightedEntry.action)
            button(0x30)
            assertEquals(CameraRailAction.ShutOff, core.state.camera.primaryHighlightedEntry.action)
            if (it == 0) core.dispatch(CameraCommand.Back)
        }
        assertTrue(button(0x20).effects.isEmpty())
        assertTrue(core.state.housingStandby)
        button(0x20, 50)
        button(0x20, 500)
        assertTrue(core.state.housingStandby)
        repeat(31) {
            now += 100
            core.handleNotificationPayload(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x50),
                receivedAt = Instant.ofEpochMilli(now), source = SensorPacketSource.Simulation)
        }
        core.advanceBle(BleSignal.Ready)
        assertEquals(ShutOffPrompt.AwaitingPress, core.state.shutOffPrompt)
        button(0x50)
        val prompt = assertIs<ShutOffPrompt.MenuPressed>(core.state.shutOffPrompt)
        assertEquals(now + 3_000L, prompt.hideAtMonotonicMs)
        button(0x20, 500)
        assertTrue(core.state.housingStandby)
        repeat(24) { button(0x50, 100) }
        assertSame(prompt, core.state.shutOffPrompt)
        now += 100
        core.expireShutOffPrompt(prompt)
        assertNull(core.state.shutOffPrompt)
        assertTrue(core.state.housingStandby)
        assertTrue(button(0x20).effects.isEmpty())
        assertFalse(core.state.housingStandby)
        assertNull(core.state.shutOffPrompt)
        assertEquals(original, core.state.camera.activeMode)
        assertNull(core.state.barometricPressureTelemetry)
        repeat(4) { sample(baro = 80.5) }
        assertTrue(core.state.safety.sealConfidence >= SealConfidence.ManufacturerMinimum)
        assertTrue(monitor.incidents.isEmpty())
        core.dispatch(CameraCommand.OpenModeRail)
        assertEquals(CameraRailAction.TrackHeading, core.state.camera.primaryHighlightedEntry.action)
    }

    @Test fun `menu standby interrupts pump and either countdown without passing and reconnect restores inspection`() {
        for (samples in listOf(0, 20, 400)) {
            surface()
            if (monitor.verification == Verification.Interrupted) {
                button(0x61)
                button(0x50)
            }
            assertTrue(motorOn in button(0x61).effects)
            if (samples > 0) {
                sample(baro = 80.5)
                core.updateSensor(SensorUpdate.CoverState(false))
                repeat(samples) { sample(baro = 80.5) }
                assertNotNull(core.state.safety.leakMonitoringStartedAtEpochMs)
            }
            core.dispatch(CameraCommand.OpenModeRail)
            button(0x30)
            assertEquals(CameraRailAction.ShutOff, core.state.camera.primaryHighlightedEntry.action)
            val paused = when (samples) {
                0 -> button(0x50)
                20 -> button(0x20)
                else -> core.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.SHUT_OFF_RAIL_INDEX))
            }
            assertEquals(listOf(motorOff, PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(false))), paused.effects)
            assertTrue(paused.state.housingStandby)
            assertNull(paused.state.safety.motorStartedAtEpochMs)
            assertNull(paused.state.safety.leakMonitoringStartedAtEpochMs)
            assertEquals(Verification.Interrupted, monitor.verification)
            assertTrue(SealIncident.IncompleteCheck in monitor.incidents)
            // Some firmware cuts power without delivering a last OK notification.
            if (samples == 0) {
                // Repeats of the MENU/OK that selected Shut Off belong to the menu.
                assertEquals(ShutOffPrompt.AwaitingPress, button(0x50, 60).state.shutOffPrompt)
            }
            val pending = if (samples > 0) assertIs<ShutOffPrompt.MenuPressed>(button(0x50).state.shutOffPrompt) else null
            val dropped = core.advanceBle(BleSignal.Disconnect).state
            assertNull(dropped.shutOffPrompt)
            assertTrue(dropped.housingStandby)
            core.advanceBle(BleSignal.StartScan)
            assertNull(core.state.shutOffPrompt)
            surface()
            if (pending != null) assertSame(core.state, core.expireShutOffPrompt(pending).state)
            assertNull(core.state.shutOffPrompt)
            assertFalse(core.state.housingStandby)
            assertEquals(Banner.B19, monitor.banner)
            assertFalse(monitor.inspected)
        }
    }

    @Test fun `Shut Off with an already disconnected housing goes directly to black standby`() {
        core.dispatch(CameraCommand.OpenModeRail)
        val sleeping = core.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.SHUT_OFF_RAIL_INDEX)).state
        assertTrue(sleeping.housingStandby)
        assertNull(sleeping.shutOffPrompt)
        core.advanceBle(BleSignal.StartScan)
        assertTrue(core.state.housingStandby)
        assertNull(core.state.shutOffPrompt)
        core.advanceBle(BleSignal.Ready)
        assertFalse(core.state.housingStandby)
    }

    @Test fun `menu standby cannot interrupt recordings or discard an unsaved panorama`() {
        for (camera in listOf(CameraState(recording = true), CameraState(panoramaReviewAvailable = true))) {
            val guarded = ControlCore(initialState = AppState(camera = camera.copy(focusedZone = CameraUiZone.ModeRail)))
            val result = guarded.dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.SHUT_OFF_RAIL_INDEX))
            assertFalse(result.state.housingStandby)
            assertNull(result.state.shutOffPrompt)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test fun `confirmed shutdown sleeps through duplicate Ready and wakes on a new ready connection`() {
        verifiedSurfaceHold()
        repeat(2) { sample() }
        confirmRelease()
        button(0x50)
        assertTrue(core.state.housingStandby)
        assertEquals(ShutOffPrompt.AwaitingPress, core.state.shutOffPrompt)
        // Repeats of the press that selected SHUT DOWN must not start the three-second timer.
        assertEquals(ShutOffPrompt.AwaitingPress, button(0x50, 60).state.shutOffPrompt)
        core.advanceBle(BleSignal.Ready)
        assertEquals(ShutOffPrompt.AwaitingPress, core.state.shutOffPrompt)
        assertTrue(button(0x30).effects.isEmpty())
        core.advanceBle(BleSignal.Disconnect)
        assertTrue(core.state.housingStandby)
        assertNull(core.state.shutOffPrompt)
        core.advanceBle(BleSignal.Connect)
        assertTrue(core.state.housingStandby)
        surface()
        assertFalse(core.state.housingStandby)
        assertEquals(Banner.B03, monitor.banner)
    }

    @Test fun `only a live shutter can wake a retained connection and the power press cannot capture`() {
        verifiedSurfaceHold()
        repeat(2) { sample() }
        confirmRelease()
        button(0x50)
        val prompt = assertIs<ShutOffPrompt.MenuPressed>(button(0x50).state.shutOffPrompt)
        assertEquals(now + 3_000L, prompt.hideAtMonotonicMs)
        now += 3_000
        core.expireShutOffPrompt(prompt)
        core.handleNotificationPayload(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x20), source = SensorPacketSource.Simulation)
        assertTrue(core.state.housingStandby)
        assertTrue(button(0x61).effects.isEmpty())
        assertTrue(core.state.housingStandby)
        assertTrue(button(0x20).effects.isEmpty())
        assertFalse(core.state.housingStandby)
        assertFalse(core.state.safety.vacuumReleasedPrompt)
        assertNull(core.state.waterPressureTelemetry)
        assertNull(core.state.barometricPressureTelemetry)
        assertTrue(button(0x20, delayMs = 50).effects.isEmpty())
        core.updateSensor(SensorUpdate.CoverState(true))
        repeat(2) { sample() }
        assertEquals(Banner.B03, monitor.banner)
    }

    @Test fun `a single jump recovers and sustained partial loss still requires inspection`() {
        verifiedSurfaceHold()
        sample()
        sample(baro = 80.5)
        assertEquals(Banner.Status, monitor.banner)
        assertTrue(monitor.incidents.isEmpty())
        repeat(6) { sample(baro = 90.0) }
        assertEquals(Banner.B04, monitor.banner)
        assertTrue(SealIncident.SurfaceLeak in monitor.incidents)
        assertFalse(motorOn in button(0x61).effects)
        assertEquals(Banner.B15, monitor.banner)
    }

    @Test fun `reconnecting a dismissed surface setup or acknowledged incident restores an actionable banner`() {
        surface()
        button(0x50)
        core.advanceBle(BleSignal.Disconnect)
        surface()
        assertEquals(Banner.B03, monitor.banner)
        button(0x61)
        sample(baro = 80.5)
        core.updateSensor(SensorUpdate.CoverState(false))
        repeat(4) { sample(baro = 80.5) }
        repeat(6) { sample(baro = 90.0) } // A sustained partial loss is a real incident.
        assertEquals(Banner.B04, monitor.banner)
        repeat(3) { sample() }
        assertEquals(Banner.B05, monitor.banner)
        button(0x50)
        assertEquals(Banner.B00, monitor.banner)
        core.advanceBle(BleSignal.Disconnect)
        surface()
        assertEquals(Banner.B05, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        assertEquals(Banner.B15, monitor.banner)
    }

    @Test fun `initial failure and three failed rechecks each need a new inspection`() {
        incident()
        repeat(4) { attempt ->
            startTest()
            repeat(5) { sample() }
            assertEquals(Banner.B17, monitor.banner)
            assertEquals(attempt + 1, monitor.failures)
            assertFalse(monitor.inspected)
            assertTrue(monitor.incidents.isNotEmpty())
            assertFalse(motorOn in button(0x61).effects)
            assertEquals(Banner.B15, monitor.banner)
        }
    }

    @Test fun `a surface pressure ramp during a required recheck retains the incident and one failed verdict`() {
        incident()
        startTest()
        repeat(12) { sample(baro = 80.5) }
        val incidents = monitor.incidents
        for (kpa in listOf(88.5, 92.974, 97.9, 100.792, 100.800)) {
            sample(baro = kpa)
            assertTrue(monitor.banner == Banner.B16 || monitor.banner == Banner.B17)
            assertTrue(monitor.incidents.containsAll(incidents))
            assertFalse(monitor.releaseQuestion)
        }
        assertEquals(Banner.B17, monitor.banner)
        assertEquals(1, monitor.failures)
    }

    @Test fun `DOWN requires a displayed zero in the current units and both queued pump writes require live zero depth`() {
        core.advanceBle(BleSignal.Ready)
        core.updateSensor(SensorUpdate.CoverState(true))
        fun pressure(key: HousingCharacteristic, raw: Int) {
            core.handleNotificationPayload(key.shortHex,
                byteArrayOf(raw.toByte(), (raw shr 8).toByte(), (raw shr 16).toByte(), 0),
                receivedAt = Instant.ofEpochMilli(now))
        }
        repeat(2) {
            now += 100
            pressure(HousingCharacteristic.WaterPressure, 10_330) // 0.2 m
            pressure(HousingCharacteristic.BarometricPressure, 101_300)
        }
        assertEquals(Banner.B03, monitor.banner)
        assertTrue(button(0x61).effects.isEmpty())
        assertNull(core.state.safety.motorStartedAtEpochMs)
        pressure(HousingCharacteristic.WaterPressure, 10_163) // 0.0 m but 0.1 ft
        metric = false
        assertTrue(button(0x61).effects.isEmpty())
        metric = true
        assertTrue(button(0x61, 50).effects.isEmpty()) // Holding DOWN cannot start on return.
        assertEquals(listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(true)), motorOn),
            button(0x61).effects)
        val start = assertNotNull(core.state.safety.motorStartedAtEpochMs)
        assertTrue(core.pumpWritePermitted(start, 0.049))
        assertFalse(core.pumpWritePermitted(start, 0.05))
        metric = false
        assertFalse(core.pumpWritePermitted(start, 0.0))
        pressure(HousingCharacteristic.WaterPressure, 10_132)
        assertTrue(core.pumpWritePermitted(start, 0.0))
        assertFalse(core.pumpWritePermitted(start, 0.02))
        now += PRESSURE_STALE_MS
        assertFalse(core.pumpWritePermitted(start, 0.0))
    }

    @Test fun `inspection cannot be confirmed after displayed surface readings expire`() {
        incident()
        now += PRESSURE_STALE_MS
        button(0x50)
        assertFalse(monitor.inspected)
        core.dispatch(SafetyCommand.ConfirmInspection)
        assertFalse(monitor.inspected)
        assertFalse(motorOn in button(0x61).effects)
    }

    @Test fun `stale data aborts an active test and repeated disconnect preserves comparison`() {
        incident()
        startTest()
        now += PRESSURE_STALE_MS
        assertTrue(motorOff in core.tickDiveProfile(now).effects)
        assertEquals(Banner.B12, monitor.banner)
        assertEquals(Verification.Interrupted, monitor.verification)
        assertFalse(motorOn in button(0x61).effects)
        core.advanceBle(BleSignal.Disconnect)
        val saved = assertNotNull(monitor.snapshot)
        repeat(2) {
            core.advanceBle(BleSignal.Connect)
            core.advanceBle(BleSignal.Disconnect)
            assertEquals(saved, monitor.snapshot)
            assertEquals(Banner.B01, monitor.banner)
        }
        assertEquals(DisconnectReason.UnknownDisconnect, monitor.disconnectReason)
        surface()
        assertEquals(Banner.B19, monitor.banner)
        assertTrue(monitor.incidents.isNotEmpty())
    }

    @Test fun `fresh surface command is scoped to displayed banner and stops on first submerged reading`() {
        surface()
        assertEquals(Banner.B03, monitor.banner)
        visible = false
        assertFalse(motorOn in button(0x61).effects)
        visible = true
        assertFalse(motorOn in core.dispatch(SafetyCommand.StartVacuumCheck).effects)
        assertTrue(motorOn in button(0x61).effects)
        assertTrue(motorOff in core.updateSensor(SensorUpdate.WaterPressure(140.0)).effects)
        assertFalse(motorOn in button(0x61).effects)
    }

    @Test fun `a malformed pressure packet inhibits a visible prompt immediately`() {
        surface()
        core.handleNotificationPayload("1627", byteArrayOf(0))
        assertEquals(Banner.B11, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        sample()
        core.handleNotificationPayload("1625", byteArrayOf(0))
        assertEquals(Banner.B12, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
    }

    @Test fun `live underwater decline escalates after acknowledgment and survives resurfacing`() {
        surface()
        assertTrue(motorOn in button(0x61).effects)
        sample(baro = 80.5)
        core.updateSensor(SensorUpdate.CoverState(false))
        repeat(4) { sample(baro = 80.5) }
        repeat(3) { sample(water = 140.0, baro = 80.5) }
        assertEquals(Banner.B00, monitor.banner)
        repeat(3) { sample(water = 140.0, baro = 86.0) }
        assertEquals(Banner.B06, monitor.banner)
        button(0x50)
        assertEquals(Banner.B00, monitor.banner)
        assertTrue(SealIncident.UnderwaterLeak in monitor.incidents)
        repeat(4) { sample(water = 140.0) }
        assertEquals(Banner.B07, monitor.banner)
        repeat(2) { sample() }
        assertEquals(Banner.B14, monitor.banner)
        assertFalse(motorOn in button(0x61).effects)
        assertEquals(Banner.B15, monitor.banner)
    }

    @Test fun `trusted shutdown and a bare link loss retain distinct diagnostic reasons`() {
        surface()
        core.advanceBle(BleSignal.HousingPoweredOff)
        assertEquals(DisconnectReason.ConfirmedUserShutdown, monitor.disconnectReason)
        assertEquals(Banner.B01, monitor.banner)
        surface()
        button(0x50)
        core.advanceBle(BleSignal.Disconnect)
        assertEquals(DisconnectReason.UnknownDisconnect, monitor.disconnectReason)
        assertEquals(Banner.B01, monitor.banner)
    }
}
