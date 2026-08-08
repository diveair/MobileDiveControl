package com.mobiledivecontrol.core

import java.time.Duration

class ControlReducer(
    private val safetyStateMachine: SafetyStateMachine = SafetyStateMachine(),
    /** Injectable for tests; the AF rail gate needs wall-clock gaps between inputs. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class ManualFocusPreparation(
        val state: AppState,
        val effects: List<PlatformEffect> = emptyList(),
    )

    fun applyRouteDecision(state: AppState, decision: RouteDecision, repeatCount: Int = 0): Reduction {
        var currentState = state
        val effects = mutableListOf<PlatformEffect>()
        val notes = mutableListOf<String>()

        if (decision.modeOverride != null && decision.modeOverride != currentState.mode) {
            currentState = currentState.copy(mode = decision.modeOverride)
        }

        if (decision.note != null) {
            currentState = currentState.copy(lastWarning = decision.note)
            notes += decision.note
        }

        for (command in decision.commands) {
            val reduction = reduce(currentState, command, repeatCount)
            currentState = reduction.state
            effects += reduction.effects
            notes += reduction.notes
        }

        return Reduction(
            state = currentState,
            effects = effects,
            notes = notes,
        )
    }

    fun reduce(state: AppState, command: ControlCommand, repeatCount: Int = 0): Reduction = when (command) {
        is CameraCommand -> reduceCamera(state, command, repeatCount)
        is PhoneControlCommand -> reducePhoneControl(state, command)
        is SafetyCommand -> reduceSafety(state, command)
        is HousingCommand -> reduceHousing(state, command)
        is SystemCommand -> reduceSystem(state, command)
        is GalleryCommand -> reduceGallery(state, command)
    }

    fun updateBleState(
        state: AppState,
        newState: BleConnectionState,
        reconnectAttempt: Int = 0,
        reconnectDelay: Duration? = null,
    ): Reduction {
        val connected = newState == BleConnectionState.Ready || newState == BleConnectionState.Degraded
        val inputEnabled = newState == BleConnectionState.Ready || newState == BleConnectionState.Degraded

        var nextState = state.copy(
            bleConnectionState = newState,
            housing = state.housing.copy(
                connected = connected,
                inputEnabled = inputEnabled,
            ),
        )

        val effects = mutableListOf<PlatformEffect>()
        val notes = mutableListOf<String>()

        when (newState) {
            BleConnectionState.Reconnecting -> {
                val note = "HOUSING DISCONNECTED"
                nextState = nextState.copy(lastWarning = note)
                notes += note
                if (reconnectDelay != null) {
                    effects += PlatformEffect.ScheduleReconnect(
                        attempt = reconnectAttempt,
                        delay = reconnectDelay,
                    )
                }
            }
            BleConnectionState.Failed -> {
                val note = "Housing connection failed."
                nextState = nextState.copy(lastWarning = note)
                notes += note
            }
            BleConnectionState.Ready -> {
                nextState = nextState.copy(lastWarning = null)
            }
            else -> Unit
        }

        return Reduction(
            state = nextState,
            effects = effects,
            notes = notes,
        )
    }

    fun updatePermission(state: AppState, permission: PermissionKind, granted: Boolean): Reduction {
        var nextState = state.copy(
            permissions = state.permissions.with(permission, granted),
        )

        val effects = mutableListOf<PlatformEffect>()
        val notes = mutableListOf<String>()

        when (permission) {
            PermissionKind.Accessibility -> {
                if (!granted) {
                    val note = "Accessibility Permission: Disabled"
                    nextState = nextState.copy(
                        mode = fallbackMode(nextState),
                        lastWarning = note,
                    )
                    notes += note
                    effects += PlatformEffect.EmitAlert(AlertPriority.High, note)
                }
            }
            PermissionKind.Overlay -> {
                if (!granted && nextState.mode == AppMode.PhoneCursor) {
                    val note = "Overlay Permission: Disabled"
                    nextState = if (nextState.permissions.accessibility && nextState.phoneControl.smartTargetAvailable) {
                        nextState.copy(
                            mode = AppMode.PhoneTarget,
                            lastWarning = "$note. Smart Target fallback enabled.",
                        )
                    } else {
                        nextState.copy(
                            mode = fallbackMode(nextState),
                            lastWarning = note,
                        )
                    }
                    notes += nextState.lastWarning.orEmpty()
                    effects += PlatformEffect.EmitAlert(AlertPriority.High, note)
                }
            }
            PermissionKind.Camera -> {
                if (!granted && nextState.mode in setOf(AppMode.CameraLive, AppMode.CameraAdjust)) {
                    val note = "Camera Permission: Disabled"
                    nextState = nextState.copy(
                        mode = AppMode.Diagnostics,
                        lastWarning = note,
                    )
                    notes += note
                    effects += PlatformEffect.EmitAlert(AlertPriority.High, note)
                }
            }
            else -> Unit
        }

        return Reduction(
            state = nextState,
            effects = effects,
            notes = notes,
        )
    }

    fun updateBatteryLevel(state: AppState, level: Int): Reduction {
        return Reduction(
            state = state.copy(
                housing = state.housing.copy(batteryPercent = level),
            ),
        )
    }

    /**
     * Phone battery, clamped to 0..100.
     *
     * Null in [AppState.phoneBatteryPercent] means "not read yet" and must render as unknown;
     * clamping here keeps an out-of-range platform reading from ever looking like a flat
     * battery, which is the one reading a diver would act on.
     */
    fun primeVerifiedVacuum(
        state: AppState,
        kpa: Double,
        confidence: SealConfidence,
        startedAtEpochMs: Long?,
        recordedAtEpochMs: Long?,
    ): Reduction {
        return Reduction(
            state = state.copy(
                safety = state.safety.copy(
                    verifiedVacuumKpa = kpa,
                    verifiedVacuumConfidence = confidence,
                    verifiedVacuumStartedAtEpochMs = startedAtEpochMs,
                    verifiedVacuumRecordedAtEpochMs = recordedAtEpochMs,
                ),
            ),
        )
    }

    fun updatePhoneBattery(state: AppState, percent: Int): Reduction {
        return Reduction(
            state = state.copy(phoneBatteryPercent = percent.coerceIn(0, 100)),
        )
    }

    fun updateDeviceInfo(state: AppState, update: DeviceInfoUpdate): Reduction {
        val nextHousing = when (update) {
            is DeviceInfoUpdate.ManufacturerName -> state.housing.copy(manufacturerName = update.value)
            is DeviceInfoUpdate.ModelNumber -> state.housing.copy(modelNumber = update.value)
            is DeviceInfoUpdate.SerialNumber -> state.housing.copy(serialNumber = update.value)
            is DeviceInfoUpdate.FirmwareRevision -> state.housing.copy(firmwareVersion = update.value)
            is DeviceInfoUpdate.HardwareRevision -> state.housing.copy(hardwareVersion = update.value)
            is DeviceInfoUpdate.SoftwareRevision -> state.housing.copy(softwareVersion = update.value)
        }

        return Reduction(
            state = state.copy(housing = nextHousing),
        )
    }

    fun updateSensor(state: AppState, sensorUpdate: SensorUpdate): Reduction = when (sensorUpdate) {
        is SensorUpdate.CoverState -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.CoverStateChanged(sensorUpdate.open),
            ),
        )
        is SensorUpdate.BarometricPressure -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.BarometricPressureSample(sensorUpdate.kpa, System.currentTimeMillis()),
            ),
        )
        is SensorUpdate.WaterPressure -> Reduction(
            state = state.copy(
                safety = state.safety.copy(waterPressureKpa = sensorUpdate.kpa),
            ),
        )
        is SensorUpdate.WaterTemperature -> Reduction(
            state = state.copy(
                safety = state.safety.copy(waterTemperatureC = sensorUpdate.celsius),
            ),
        )
    }

    private fun reduceCamera(state: AppState, command: CameraCommand, repeatCount: Int = 0): Reduction = when (command) {
        CameraCommand.CapturePhoto -> {
            val nextCamera = state.camera.copy(captureCounter = state.camera.captureCounter + 1)
            if (!state.permissions.camera) {
                warning(state, "Camera Permission: Disabled")
            } else {
                Reduction(
                    state = state.copy(camera = nextCamera),
                    effects = listOf(PlatformEffect.ExecuteCamera(command)),
                )
            }
        }
        // The router only sends Toggle/Start when nothing is recording, so both mean "begin".
        CameraCommand.ToggleVideoRecording,
        CameraCommand.StartVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recording = true,
                    recordingPaused = false,
                    recordingStopSelected = false,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(CameraCommand.StartVideoRecording)),
        )
        CameraCommand.PauseVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recordingPaused = true,
                    // The chooser always opens on RESUME: the least destructive answer is the
                    // default, and STOP is one deliberate press away.
                    recordingStopSelected = false,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.ResumeVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(recordingPaused = false),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.StopVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recording = false,
                    recordingPaused = false,
                    recordingStopSelected = false,
                    // Bumped so the gallery thumbnail refreshes with the finished video.
                    captureCounter = state.camera.captureCounter + 1,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.NavigateUp -> navigateCameraUp(state, repeatCount)
        CameraCommand.NavigateDown -> navigateCameraDown(state, repeatCount)
        CameraCommand.NavigateLeft -> navigateCameraLeft(state, repeatCount)
        CameraCommand.NavigateRight -> navigateCameraRight(state, repeatCount)
        CameraCommand.Confirm -> confirmCameraSelection(state)
        CameraCommand.Back -> backOutCameraUi(state)
        CameraCommand.ZoomIn -> handleWheel(state, +1, repeatCount)
        CameraCommand.ZoomOut -> handleWheel(state, -1, repeatCount)
        is CameraCommand.SetZoom -> {
            val zoom = command.value.coerceIn(1.0, state.camera.capabilities?.zoomMaxRatio ?: 8.0)
            Reduction(
                state = state.copy(
                    camera = state.camera.copy(zoomFactor = zoom),
                ),
                effects = listOf(PlatformEffect.ExecuteCamera(CameraCommand.SetZoom(zoom))),
            )
        }
        is CameraCommand.SetIso,
        is CameraCommand.SetShutterSpeedNs,
        is CameraCommand.SetManualFocus,
        is CameraCommand.SetWhiteBalanceKelvin,
        is CameraCommand.SetExposureCompensation,
        is CameraCommand.SwitchLens,
        is CameraCommand.SetFlashMode,
        is CameraCommand.SetPhotoResolution,
        is CameraCommand.SetCaptureFormat,
        is CameraCommand.SetHdrLogMode,
        is CameraCommand.SetFilter,
        CameraCommand.OpenGallery,
        CameraCommand.ToggleGrid,
        CameraCommand.ToggleFocusPeaking,
        CameraCommand.RestartCamera -> emitCameraEffect(state, command)
        is CameraCommand.NudgeSetting -> {
            val spec = CameraCatalog.settingsFor(
                state.camera.activeMode,
                state.camera.deviceVariant,
                state.camera.detectedLenses,
                state.camera.capabilities,
            ).firstOrNull { it.id == command.settingId }
            if (spec == null) {
                Reduction(state = state)
            } else {
                val currentValue = state.camera.settingValues[spec.id] ?: spec.defaultValue
                val currentIndex = spec.options.indexOf(currentValue).coerceAtLeast(0)
                val minIndex = if (spec.id.endsWith(".manual_focus") && currentIndex > 0) 1 else 0
                val nextIndex = (currentIndex + command.step).coerceIn(minIndex, spec.options.lastIndex)
                if (nextIndex == currentIndex) {
                    Reduction(state = state)
                } else {
                    val nextValue = spec.options[nextIndex]
                    val effect = cameraEffectForSetting(spec.id, nextValue)
                    Reduction(
                        state = state.copy(camera = applySettingValue(state.camera, spec.id, nextValue)),
                        effects = effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList(),
                    )
                }
            }
        }
        is CameraCommand.UpdateCameraCapabilities -> Reduction(
            state = state.copy(
                camera = state.camera.copy(capabilities = command.capabilities),
            ),
        )
        is CameraCommand.UpdateDetectedLenses -> {
            val nextCamera = state.camera.copy(detectedLenses = command.lenses)
            Reduction(state = state.copy(camera = nextCamera))
        }
    }

    private fun reducePhoneControl(state: AppState, command: PhoneControlCommand): Reduction {
        if (!state.permissions.canUsePhoneControl()) {
            return warning(state, "Accessibility Permission: Disabled")
        }

        return when (command) {
            PhoneControlCommand.IncreaseCursorSpeed -> Reduction(
                state = state.copy(
                    phoneControl = state.phoneControl.copy(
                        cursorSpeedProfile = nextCursorSpeed(state.phoneControl.cursorSpeedProfile),
                    ),
                ),
            )
            PhoneControlCommand.DecreaseCursorSpeed -> Reduction(
                state = state.copy(
                    phoneControl = state.phoneControl.copy(
                        cursorSpeedProfile = previousCursorSpeed(state.phoneControl.cursorSpeedProfile),
                    ),
                ),
            )
            PhoneControlCommand.SwitchCursorMode -> {
                if (state.mode == AppMode.PhoneCursor && state.phoneControl.smartTargetAvailable) {
                    Reduction(
                        state = state.copy(
                            mode = AppMode.PhoneTarget,
                            phoneControl = state.phoneControl.copy(smartTargetEnabled = true),
                        ),
                    )
                } else {
                    Reduction(
                        state = state.copy(
                            mode = AppMode.PhoneCursor,
                            phoneControl = state.phoneControl.copy(smartTargetEnabled = false),
                        ),
                    )
                }
            }
            is PhoneControlCommand.MoveTarget -> Reduction(
                state = state,
                effects = listOf(PlatformEffect.ExecutePhoneControl(command)),
            )
            PhoneControlCommand.MoveCursorUp,
            PhoneControlCommand.MoveCursorDown,
            PhoneControlCommand.MoveCursorLeft,
            PhoneControlCommand.MoveCursorRight,
            PhoneControlCommand.Click,
            PhoneControlCommand.LongClick,
            PhoneControlCommand.ScrollUp,
            PhoneControlCommand.ScrollDown,
            PhoneControlCommand.Back,
            PhoneControlCommand.Home,
            PhoneControlCommand.Recents,
            PhoneControlCommand.NextTarget,
            PhoneControlCommand.PreviousTarget,
                -> {
                    if (state.mode == AppMode.PhoneCursor && !state.permissions.canUseOverlayCursor()) {
                        warning(state, "Overlay Permission: Disabled")
                    } else {
                        Reduction(
                            state = state,
                            effects = listOf(PlatformEffect.ExecutePhoneControl(command)),
                        )
                    }
                }
        }
    }

    /**
     * Housing commands pass straight through to the transport — with one exception.
     *
     * Starting the pump is the only command here that can damage hardware, and it is only
     * safe as part of the workflow that confirmed the cover is open and armed a timeout.
     * [SafetyStateMachine] emits its motor-on effect directly into [Reduction.effects] and
     * never travels this path, so refusing it here costs the workflow nothing and leaves no
     * dispatchable command that can start the motor.
     */
    private fun reduceHousing(state: AppState, command: HousingCommand): Reduction {
        if (command is HousingCommand.SetVacuumMotor && command.enabled) {
            return warning(state, "Vacuum motor starts only from the seal-check workflow.")
        }
        return Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(command)),
        )
    }

    private fun reduceSafety(state: AppState, command: SafetyCommand): Reduction = when (command) {
        SafetyCommand.StartVacuumCheck -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.StartVacuumCheckRequested,
            ),
        )
        SafetyCommand.CancelVacuumCheck -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.CancelVacuumCheckRequested,
            ),
        )
        SafetyCommand.DismissSealCheck -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.DismissSealCheckRequested,
            ),
        )
        SafetyCommand.SkipToResult -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.SkipToResultRequested,
            ),
        )
        SafetyCommand.ResetSealState -> mergeSafetyResult(
            state = state,
            result = safetyStateMachine.apply(
                state = state.safety,
                signal = SafetySignal.ResetSealStateRequested,
            ),
        )
        SafetyCommand.OpenSolenoid -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = true))),
        )
        SafetyCommand.CloseSolenoid -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false))),
        )
        // Deliberately NOT a passthrough. A motor-on effect may only originate inside
        // SafetyStateMachine, which is the only place that has confirmed the cover is open,
        // armed a timeout, and owns the stop condition. A raw start here would run the pump
        // against a sealed shell with nothing to turn it off.
        SafetyCommand.StartVacuumMotor -> warning(
            state,
            "Vacuum motor starts only from the seal-check workflow.",
        )
        SafetyCommand.StopVacuumMotor -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false))),
        )
        SafetyCommand.AcknowledgeWarning -> Reduction(
            state = state.copy(
                safety = state.safety.copy(warning = null),
                lastWarning = null,
            ),
        )
    }

    private fun reduceSystem(state: AppState, command: SystemCommand): Reduction = when (command) {
        SystemCommand.SwitchToCameraMode -> {
            if (!state.permissions.camera) {
                warning(state, "Camera Permission: Disabled")
            } else {
                Reduction(state = state.copy(mode = AppMode.CameraLive, lastWarning = null))
            }
        }
        SystemCommand.SwitchToTransparentPhoneMode -> {
            if (!state.permissions.canUsePhoneControl()) {
                warning(state, "Accessibility Permission: Disabled")
            } else if (state.permissions.canUseOverlayCursor()) {
                Reduction(state = state.copy(mode = AppMode.PhoneCursor, lastWarning = null))
            } else if (state.phoneControl.smartTargetAvailable) {
                Reduction(
                    state = state.copy(
                        mode = AppMode.PhoneTarget,
                        lastWarning = "Overlay Permission: Disabled. Smart Target fallback enabled.",
                    ),
                )
            } else {
                warning(state, "Transparent Phone Mode unavailable.")
            }
        }
        SystemCommand.SwitchToSafetyMode -> Reduction(state = state.copy(mode = AppMode.Safety))
        SystemCommand.SwitchToDiagnosticsMode -> Reduction(state = state.copy(mode = AppMode.Diagnostics))
        SystemCommand.ExportDiagnostics -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExportDiagnostics),
        )
        SystemCommand.LockControls -> Reduction(state = state.copy(controlsLocked = true))
        SystemCommand.UnlockControls -> Reduction(state = state.copy(controlsLocked = false))
    }

    /**
     * The physical wheel. What it adjusts follows the diver's attention:
     *  - inside an open setting menu it edits whichever card UP/DOWN selected, same as
     *    LEFT/RIGHT — value, sensitivity, focus assist, focus curve;
     *  - with a setting tile selected in the bottom bar it edits that tile's value;
     *  - everywhere else — live view, the mode rail, the mode token — it drives the setting
     *    the diver assigned in the Slider tile (Focus by default; Zoom is the one assignment
     *    with no spec behind it and keeps the classic ratio behaviour).
     */
    /**
     * The wheel is never a "held button", whatever the normaliser says.
     *
     * [ButtonEventNormalizer] marks any event arriving inside its repeat window as a repeat, and
     * a fast spin trips that on nearly every detent — so the faster the diver turned, the more of
     * their input was routed to the hold branch, which mints a fixed run-time top-up and hands it
     * to the drain UNPACED. That dumped its distance at the frame ceiling and then idled: a pause
     * that no amount of pacing margin could reach, because the pacing code never ran.
     *
     * A wheel detent is a discrete displacement no matter how quickly the next one follows, so it
     * always takes the fresh-press path and its gearing. D-pad callers keep their real repeat
     * count, because a held button genuinely is a hold.
     */
    private fun handleWheel(state: AppState, step: Int, repeatCount: Int): Reduction {
        @Suppress("NAME_SHADOWING") val repeatCount = 0
        val camera = state.camera
        if (camera.recording && camera.recordingPaused) {
            return Reduction(state = state)
        }
        if (camera.focusedZone == CameraUiZone.SettingsPanel && camera.settingsEditing) {
            // Inverted inside an open menu, by field report: the edit cards lay their value
            // rails out so that wheel-up should travel the same way as LEFT, not RIGHT. The
            // diver's own Wheel Direction preference stacks on top when the focus VALUE is
            // what the wheel is driving.
            val selected = camera.selectedSetting
            val menuStep = if (selected != null && camera.sliderEditTarget == SliderEditTarget.Value) {
                focusAwareStep(camera, selected, -step)
            } else {
                -step
            }
            return adjustSelectedSetting(state, menuStep, repeatCount)
        }
        if (camera.focusedZone == CameraUiZone.SettingsPanel) {
            val selected = camera.selectedSetting
            if (selected != null && !selected.id.endsWith(CameraCatalog.SLIDER_ASSIGNMENT_SUFFIX)) {
                return adjustSetting(state, selected, focusAwareStep(camera, selected, step), repeatCount)
            }
        }
        val assigned = CameraCatalog.assignedSliderSpec(camera)
        if (assigned != null) {
            return adjustSetting(state, assigned, focusAwareStep(camera, assigned, step), repeatCount)
        }
        val maxZoom = camera.capabilities?.zoomMaxRatio ?: 8.0
        val zoom = (camera.zoomFactor + step * 0.1).coerceIn(1.0, maxZoom)
        return reduceCamera(state, CameraCommand.SetZoom(zoom))
    }

    /** While the paused RESUME/STOP chooser is up, LEFT/RIGHT move its selection and nothing else. */
    private fun pausedChooserNavigation(state: AppState, horizontal: Boolean): Reduction? {
        val camera = state.camera
        if (!camera.recording || !camera.recordingPaused) return null
        if (!horizontal) return Reduction(state = state)
        return Reduction(
            state = state.copy(
                camera = camera.copy(recordingStopSelected = !camera.recordingStopSelected),
            ),
        )
    }

    private fun navigateCameraUp(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, horizontal = false)?.let { return it }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> {
                val focused = focusModeRail(camera)
                val size = CameraCatalog.primaryRailEntries.size
                val nextIndex = (focused.highlightedPrimaryIndex - 1 + size) % size
                Reduction(state = state.copy(camera = focused.copy(highlightedPrimaryIndex = nextIndex)))
            }
            CameraUiZone.ModeRail -> {
                val nextCamera = if (camera.railLevel == CameraRailLevel.Primary) {
                    val size = CameraCatalog.primaryRailEntries.size
                    camera.copy(highlightedPrimaryIndex = (camera.highlightedPrimaryIndex - 1 + size) % size)
                } else {
                    camera.copy(highlightedSecondaryIndex = (camera.highlightedSecondaryIndex - 1).coerceAtLeast(0))
                }
                Reduction(state = state.copy(camera = nextCamera))
            }
            CameraUiZone.SettingsPanel -> {
                if (camera.settingsEditing) {
                    moveSliderEditTarget(state, -1)
                } else {
                    when (selectedBottomBarItem(camera)) {
                        is BottomBarItem.ModesButton -> cycleModeFromSettingsBar(state, -1)
                        is BottomBarItem.Setting -> adjustSelectedSetting(state, +1, repeatCount)
                        else -> Reduction(state = state)
                    }
                }
            }
        }
    }

    private fun navigateCameraDown(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, horizontal = false)?.let { return it }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> {
                val focused = focusModeRail(camera)
                val size = CameraCatalog.primaryRailEntries.size
                val nextIndex = (focused.highlightedPrimaryIndex + 1) % size
                Reduction(state = state.copy(camera = focused.copy(highlightedPrimaryIndex = nextIndex)))
            }
            CameraUiZone.ModeRail -> {
                val nextCamera = if (camera.railLevel == CameraRailLevel.Primary) {
                    val size = CameraCatalog.primaryRailEntries.size
                    camera.copy(highlightedPrimaryIndex = (camera.highlightedPrimaryIndex + 1) % size)
                } else {
                    camera.copy(highlightedSecondaryIndex = (camera.highlightedSecondaryIndex + 1).coerceAtMost(CameraCatalog.secondaryModes.lastIndex))
                }
                Reduction(state = state.copy(camera = nextCamera))
            }
            CameraUiZone.SettingsPanel -> {
                if (camera.settingsEditing) {
                    moveSliderEditTarget(state, +1)
                } else {
                    when (selectedBottomBarItem(camera)) {
                        is BottomBarItem.ModesButton -> cycleModeFromSettingsBar(state, +1)
                        is BottomBarItem.Setting -> adjustSelectedSetting(state, -1, repeatCount)
                        else -> Reduction(state = state)
                    }
                }
            }
        }
    }

    private fun navigateCameraLeft(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, horizontal = true)?.let { return it }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> Reduction(state = state)
            CameraUiZone.ModeRail -> {
                if (camera.railLevel == CameraRailLevel.Secondary) {
                    Reduction(
                        state = state.copy(
                            camera = camera.copy(
                                railLevel = CameraRailLevel.Primary,
                                highlightedPrimaryIndex = CameraCatalog.primaryRailEntries.lastIndex,
                            ),
                        ),
                    )
                } else {
                    Reduction(state = state.copy(camera = exitModeRail(camera)))
                }
            }
            CameraUiZone.SettingsPanel -> {
                if (camera.settingsEditing) {
                    adjustSelectedSetting(state, -1, repeatCount)
                } else {
                    moveSettingsCursor(state, -1)
                }
            }
        }
    }

    private fun navigateCameraRight(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, horizontal = true)?.let { return it }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> Reduction(state = state.copy(camera = focusModeRail(camera)))
            CameraUiZone.ModeRail -> enterFromModeRail(state)
            CameraUiZone.SettingsPanel -> {
                if (camera.settingsEditing) {
                    adjustSelectedSetting(state, +1, repeatCount)
                } else {
                    moveSettingsCursor(state, +1)
                }
            }
        }
    }

    private fun confirmCameraSelection(state: AppState): Reduction {
        // OK mirrors the shutter while the paused chooser is up: confirm the selected side.
        if (state.camera.recording && state.camera.recordingPaused) {
            val command = if (state.camera.recordingStopSelected) {
                CameraCommand.StopVideoRecording
            } else {
                CameraCommand.ResumeVideoRecording
            }
            return reduceCamera(state, command)
        }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> openSettingsPanel(state, camera.activeMode)
            CameraUiZone.ModeRail -> confirmModeSelection(state)
            CameraUiZone.SettingsPanel -> {
                if (camera.settingsEditing) {
                    confirmSettingEdit(state)
                } else {
                    activateHighlightedItem(state)
                }
            }
        }
    }

    private fun backOutCameraUi(state: AppState): Reduction {
        // Back from the paused chooser is the safe answer: keep the recording, resume it.
        if (state.camera.recording && state.camera.recordingPaused) {
            return reduceCamera(state, CameraCommand.ResumeVideoRecording)
        }
        val camera = state.camera
        return when {
            camera.focusedZone == CameraUiZone.SettingsPanel && camera.settingsEditing -> {
                if (camera.sliderEditTarget != SliderEditTarget.Value) {
                    Reduction(
                        state = state.copy(
                            camera = camera.copy(
                                sliderEditTarget = SliderEditTarget.Value,
                            ),
                        ),
                    )
                } else {
                    Reduction(
                        state = state.copy(
                            camera = camera.copy(
                                settingsEditing = false,
                                sliderEditTarget = SliderEditTarget.Value,
                            ),
                        ),
                    )
                }
            }
            camera.focusedZone == CameraUiZone.SettingsPanel -> Reduction(
                state = state.copy(camera = modeRailForCurrentMode(camera)),
            )
            camera.focusedZone == CameraUiZone.ModeRail && camera.railLevel == CameraRailLevel.Secondary -> Reduction(
                state = state.copy(
                    camera = camera.copy(
                        railLevel = CameraRailLevel.Primary,
                        highlightedPrimaryIndex = CameraCatalog.primaryRailEntries.lastIndex,
                    ),
                ),
            )
            camera.focusedZone == CameraUiZone.ModeRail -> Reduction(
                state = state.copy(camera = exitModeRail(camera)),
            )
            else -> Reduction(state = state)
        }
    }

    private fun focusModeRail(camera: CameraState): CameraState {
        return camera.copy(
            focusedZone = CameraUiZone.ModeRail,
            modeRailReturnZone = CameraUiZone.LiveView,
            railLevel = CameraRailLevel.Primary,
            highlightedPrimaryIndex = CameraCatalog.primaryIndexForMode(camera.activeMode),
            settingsEditing = false,
            sliderEditTarget = SliderEditTarget.Value,
        )
    }

    private fun modeRailForCurrentMode(
        camera: CameraState,
        returnZone: CameraUiZone = CameraUiZone.LiveView,
    ): CameraState {
        return camera.copy(
            focusedZone = CameraUiZone.ModeRail,
            modeRailReturnZone = returnZone,
            railLevel = if (CameraCatalog.secondaryModes.contains(camera.activeMode)) CameraRailLevel.Secondary else CameraRailLevel.Primary,
            highlightedPrimaryIndex = CameraCatalog.primaryIndexForMode(camera.activeMode),
            highlightedSecondaryIndex = CameraCatalog.secondaryIndexForMode(camera.activeMode),
            settingsEditing = false,
            sliderEditTarget = SliderEditTarget.Value,
        )
    }

    private fun openSettingsPanel(state: AppState, mode: CameraModeId): Reduction {
        val activated = activateModeInternal(state, mode, openSettings = true, returnToLiveView = false)
        return activated ?: Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    focusedZone = CameraUiZone.SettingsPanel,
                    modeRailReturnZone = CameraUiZone.SettingsPanel,
                    settingsCursor = CameraCatalog.defaultSettingsCursor(
                        mode = state.camera.activeMode,
                        variant = state.camera.deviceVariant,
                        showMore = state.camera.showMoreSettings,
                    ),
                    settingsEditing = false,
                    sliderEditTarget = SliderEditTarget.Value,
                ),
            ),
        )
    }

    private fun confirmModeSelection(state: AppState): Reduction {
        val camera = state.camera
        return when (camera.railLevel) {
            CameraRailLevel.Primary -> {
                val entry = camera.primaryHighlightedEntry
                if (entry.opensSecondaryRail) {
                    Reduction(
                        state = state.copy(
                            camera = camera.copy(
                                railLevel = CameraRailLevel.Secondary,
                                highlightedSecondaryIndex = CameraCatalog.secondaryIndexForMode(camera.activeMode),
                            ),
                        ),
                    )
                } else {
                    activateMode(state, entry.mode!!, returnToLiveView = false, openSettings = true)
                }
            }
            CameraRailLevel.Secondary -> activateMode(
                state,
                camera.secondaryHighlightedMode,
                returnToLiveView = false,
                openSettings = true,
            )
        }
    }

    private fun enterFromModeRail(state: AppState): Reduction {
        val camera = state.camera
        return when (camera.railLevel) {
            CameraRailLevel.Primary -> {
                val entry = camera.primaryHighlightedEntry
                if (entry.opensSecondaryRail) {
                    Reduction(
                        state = state.copy(
                            camera = camera.copy(
                                railLevel = CameraRailLevel.Secondary,
                                highlightedSecondaryIndex = CameraCatalog.secondaryIndexForMode(camera.activeMode),
                            ),
                        ),
                    )
                } else {
                    activateMode(state, entry.mode!!, returnToLiveView = false, openSettings = true)
                }
            }
            CameraRailLevel.Secondary -> activateMode(
                state,
                camera.secondaryHighlightedMode,
                returnToLiveView = false,
                openSettings = true,
            )
        }
    }

    private fun activateMode(
        state: AppState,
        mode: CameraModeId,
        returnToLiveView: Boolean,
        openSettings: Boolean,
    ): Reduction {
        val blocked = activateModeInternal(state, mode, openSettings, returnToLiveView)
        return blocked ?: Reduction(state = state)
    }

    private fun activateModeInternal(
        state: AppState,
        mode: CameraModeId,
        openSettings: Boolean,
        returnToLiveView: Boolean,
    ): Reduction? {
        if (state.camera.recording && state.camera.activeMode != mode) {
            val note = "Stop recording before changing mode."
            return Reduction(
                state = state.copy(lastWarning = note),
                notes = listOf(note),
            )
        }

        val nextZone = when {
            openSettings -> CameraUiZone.SettingsPanel
            returnToLiveView -> CameraUiZone.LiveView
            else -> CameraUiZone.ModeRail
        }
        if (state.camera.activeMode == mode && state.camera.focusedZone == nextZone) {
            return null
        }
        val nextRailLevel = if (CameraCatalog.secondaryModes.contains(mode)) CameraRailLevel.Secondary else CameraRailLevel.Primary
        val nextCamera = state.camera.copy(
            activeMode = mode,
            focusedZone = nextZone,
            modeRailReturnZone = if (nextZone == CameraUiZone.SettingsPanel) CameraUiZone.SettingsPanel else CameraUiZone.LiveView,
            railLevel = nextRailLevel,
            highlightedPrimaryIndex = CameraCatalog.primaryIndexForMode(mode),
            highlightedSecondaryIndex = CameraCatalog.secondaryIndexForMode(mode),
            settingsCursor = CameraCatalog.defaultSettingsCursor(mode, state.camera.deviceVariant),
            settingsEditing = false,
            sliderEditTarget = SliderEditTarget.Value,
            showMoreSettings = false,
        )
        return Reduction(state = state.copy(camera = nextCamera, lastWarning = null))
    }

    private fun moveSettingsCursor(state: AppState, delta: Int): Reduction {
        val items = CameraCatalog.settingsBarItems(state.camera)
        val totalItems = items.size
        if (totalItems <= 1) {
            return Reduction(state = state)
        }

        val nextIndex = (state.camera.settingsCursor + delta + totalItems) % totalItems
        return Reduction(
            state = state.copy(
            camera = state.camera.copy(
                    settingsCursor = nextIndex,
                    settingsEditing = false,
                    sliderEditTarget = SliderEditTarget.Value,
                ),
            ),
        )
    }

    private fun beginSettingEdit(state: AppState): Reduction {
        return if (state.camera.selectedSetting == null) {
            Reduction(state = state)
        } else {
            Reduction(
                state = state.copy(
                    camera = state.camera.copy(
                        settingsEditing = true,
                        sliderEditTarget = SliderEditTarget.Value,
                    ),
                ),
            )
        }
    }

    private fun confirmSettingEdit(state: AppState): Reduction {
        return Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    settingsEditing = false,
                    sliderEditTarget = SliderEditTarget.Value,
                ),
            ),
        )
    }

    private fun activateHighlightedItem(state: AppState): Reduction {
        val items = CameraCatalog.settingsBarItems(state.camera)
        val item = items.getOrNull(state.camera.settingsCursor) ?: return Reduction(state = state)
        return when (item) {
            is BottomBarItem.ModesButton -> {
                val nextCamera = modeRailForCurrentMode(state.camera, returnZone = CameraUiZone.SettingsPanel)
                Reduction(state = state.copy(camera = nextCamera))
            }
            is BottomBarItem.LensShortcut -> {
                val lensVal = item.value
                val effect = CameraCommand.SwitchLens(lensVal)
                val settingId = when (state.camera.activeMode) {
                    CameraModeId.Photo -> "photo.lens"
                    CameraModeId.Pro -> "pro.lens"
                    CameraModeId.ExpertRaw -> "expert.lens"
                    CameraModeId.Video -> "video.lens"
                    CameraModeId.ProVideo -> "pro_video.lens"
                    else -> "${state.camera.activeMode.label.lowercase()}.lens"
                }
                val nextCamera = applySettingValue(state.camera, settingId, lensVal)
                Reduction(
                    state = state.copy(camera = nextCamera),
                    effects = listOf(PlatformEffect.ExecuteCamera(effect))
                )
            }
            is BottomBarItem.GalleryShortcut -> Reduction(
                state = state.copy(mode = AppMode.Gallery),
                effects = listOf(PlatformEffect.LoadGalleryItems),
            )
            is BottomBarItem.MoreSettings -> {
                val nextShowMore = !state.camera.showMoreSettings
                val nextItems = CameraCatalog.settingsBarItems(
                    state.camera.copy(showMoreSettings = nextShowMore),
                )
                val nextCursor = state.camera.settingsCursor.coerceAtMost(nextItems.lastIndex)
                val nextCamera = state.camera.copy(
                    showMoreSettings = nextShowMore,
                    settingsCursor = nextCursor
                )
                Reduction(state = state.copy(camera = nextCamera))
            }
            is BottomBarItem.Setting -> {
                val preparation = if (item.spec.id.endsWith(".manual_focus")) {
                    prepareStateForManualFocus(state, item.spec)
                } else {
                    ManualFocusPreparation(state)
                }
                Reduction(
                    state = preparation.state.copy(
                        camera = preparation.state.camera.copy(
                            settingsEditing = true,
                            sliderEditTarget = SliderEditTarget.Value
                        )
                    ),
                    effects = preparation.effects,
                )
            }
        }
    }

    private fun selectedBottomBarItem(camera: CameraState): BottomBarItem? {
        val items = CameraCatalog.settingsBarItems(camera)
        if (items.isEmpty()) {
            return null
        }
        return items.getOrNull(camera.settingsCursor.coerceIn(0, items.lastIndex))
    }

    private fun cycleModeFromSettingsBar(state: AppState, step: Int): Reduction {
        val currentIndex = CameraCatalog.primaryIndexForMode(state.camera.activeMode)
        val size = CameraCatalog.primaryRailEntries.size
        val nextIndex = (currentIndex + step + size) % size
        val nextMode = CameraCatalog.primaryRailEntries[nextIndex].mode ?: return Reduction(state = state)
        return activateMode(state, nextMode, returnToLiveView = false, openSettings = true)
    }

    private fun moveSliderEditTarget(state: AppState, delta: Int): Reduction {
        val camera = state.camera
        val spec = camera.selectedSetting ?: return Reduction(state = state)
        val targets = editTargetsFor(camera, spec)
        if (targets.size <= 1) {
            return Reduction(state = state)
        }

        val currentIndex = targets.indexOf(camera.sliderEditTarget).coerceAtLeast(0)
        val nextIndex = (currentIndex + delta).coerceIn(0, targets.lastIndex)
        return Reduction(
            state = state.copy(
                camera = camera.copy(
                    sliderEditTarget = targets[nextIndex],
                ),
            ),
        )
    }

    private fun exitModeRail(camera: CameraState): CameraState {
        return if (camera.modeRailReturnZone == CameraUiZone.SettingsPanel) {
            camera.copy(
                focusedZone = CameraUiZone.SettingsPanel,
                settingsCursor = CameraCatalog.defaultSettingsCursor(
                    mode = camera.activeMode,
                    variant = camera.deviceVariant,
                    showMore = camera.showMoreSettings,
                ),
                settingsEditing = false,
                sliderEditTarget = SliderEditTarget.Value,
            )
        } else {
            camera.copy(
                focusedZone = CameraUiZone.LiveView,
                settingsEditing = false,
                sliderEditTarget = SliderEditTarget.Value,
            )
        }
    }

    private fun adjustSelectedSetting(state: AppState, step: Int, repeatCount: Int = 0): Reduction {
        val spec = state.camera.selectedSetting ?: return Reduction(state = state)
        return adjustSetting(state, spec, step, repeatCount)
    }

    /** [adjustSelectedSetting] with the spec chosen by the caller — the wheel resolves its own. */
    private fun adjustSetting(state: AppState, spec: CameraSettingSpec, step: Int, repeatCount: Int = 0): Reduction {
        val manualFocusPreparation = if (spec.id.endsWith(".manual_focus")) {
            prepareStateForManualFocus(state, spec)
        } else {
            ManualFocusPreparation(state)
        }
        val preparedState = manualFocusPreparation.state
        val preparedCamera = preparedState.camera
        val editTarget = preparedCamera.sliderEditTarget
        val adjustingSensitivity = preparedCamera.settingsEditing &&
                spec.kind == CameraSettingKind.Slider &&
                spec.supportsSensitivity &&
                editTarget == SliderEditTarget.Sensitivity
        val adjustingFocusAssist = preparedCamera.settingsEditing &&
                spec.id.endsWith(".manual_focus") &&
                editTarget == SliderEditTarget.FocusAssist
        val adjustingFocusCurve = preparedCamera.settingsEditing &&
                spec.id.endsWith(".manual_focus") &&
                editTarget == SliderEditTarget.FocusCurve
        val adjustingFocusDirection = preparedCamera.settingsEditing &&
                spec.id.endsWith(".manual_focus") &&
                editTarget == SliderEditTarget.FocusDirection
        val adjustingRampIn = preparedCamera.settingsEditing &&
                spec.id.endsWith(".manual_focus") &&
                editTarget == SliderEditTarget.FocusRampIn
        val adjustingRampOut = preparedCamera.settingsEditing &&
                spec.id.endsWith(".manual_focus") &&
                editTarget == SliderEditTarget.FocusRampOut

        return if (adjustingSensitivity) {
            if (repeatCount > 0 && repeatCount % 4 != 0) {
                return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
            }
            val current = preparedCamera.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT
            val next = cycleSensitivity(current, step)
            Reduction(
                state = preparedState.copy(
                    camera = preparedCamera.copy(
                        sliderSensitivities = preparedCamera.sliderSensitivities + (spec.id to next),
                    ),
                ),
                effects = manualFocusPreparation.effects,
            )
        } else if (adjustingFocusAssist) {
            if (!supportsManualFocusForSelectedLens(preparedCamera, spec)) {
                return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
            }
            val assistSpec = focusAssistSpec(preparedCamera, spec)
                ?: return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
            val currentValue = preparedCamera.settingValues[assistSpec.id] ?: assistSpec.defaultValue
            val nextValue = advanceOption(
                currentValue = currentValue,
                options = assistSpec.options,
                step = step,
                wrap = true,
            )
            Reduction(
                state = preparedState.copy(
                    camera = applySettingValue(preparedCamera, assistSpec.id, nextValue),
                ),
                effects = manualFocusPreparation.effects,
            )
        } else if (adjustingFocusCurve) {
            if (!supportsManualFocusForSelectedLens(preparedCamera, spec)) {
                return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
            }
            val curveSpec = focusCurveSpec(preparedCamera, spec)
                ?: return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
            val currentValue = preparedCamera.settingValues[curveSpec.id] ?: curveSpec.defaultValue
            val nextValue = advanceOption(
                currentValue = currentValue,
                options = curveSpec.options,
                step = step,
                wrap = true,
            )
            Reduction(
                state = preparedState.copy(
                    camera = applySettingValue(preparedCamera, curveSpec.id, nextValue),
                ),
                effects = manualFocusPreparation.effects,
            )
        } else if (adjustingRampIn || adjustingRampOut) {
            val rampSpec = CameraCatalog.focusRampSpec(spec.id, inward = adjustingRampIn)
            val currentValue = preparedCamera.settingValues[rampSpec.id] ?: rampSpec.defaultValue
            val nextValue = advanceOption(
                currentValue = currentValue,
                options = rampSpec.options,
                step = step,
                wrap = false,
            )
            Reduction(
                state = preparedState.copy(
                    camera = applySettingValue(preparedCamera, rampSpec.id, nextValue),
                ),
                effects = manualFocusPreparation.effects,
            )
        } else if (adjustingFocusDirection) {
            val dirSpec = CameraCatalog.focusDirectionSpec(spec.id)
            val currentValue = preparedCamera.settingValues[dirSpec.id] ?: dirSpec.defaultValue
            val nextValue = advanceOption(
                currentValue = currentValue,
                options = dirSpec.options,
                step = step,
                wrap = true,
            )
            Reduction(
                state = preparedState.copy(
                    camera = applySettingValue(preparedCamera, dirSpec.id, nextValue),
                ),
                effects = manualFocusPreparation.effects,
            )
        } else {
            val isFocusSetting = spec.id.endsWith(".manual_focus")
            val currentSensitivity = preparedCamera.sliderSensitivities[spec.id] ?: SliderSensitivity.DEFAULT

            if (isFocusSetting) {
                if (!supportsManualFocusForSelectedLens(preparedCamera, spec)) {
                    return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
                }
                val currentValue = preparedCamera.settingValues[spec.id] ?: spec.defaultValue
                val currentIndex = spec.options.indexOf(currentValue).coerceAtLeast(0)
                val now = nowMs()
                val gapMs = now - preparedCamera.lastFocusInputAtMs
                val usable = spec.options.size - 1 // the numeric scale, AF excluded
                val motor = sliderMotorFor(usable, currentSensitivity, gapMs, held = repeatCount > 0)

                // A HELD button deposits run-time, not an immediate jump: the motor walks the
                // value tick by tick at this sensitivity's full rate for as long as repeats
                // keep arriving, and the platform discards leftovers when they stop.
                if (repeatCount > 0) {
                    val stamped = preparedCamera.copy(lastFocusInputAtMs = now)
                    return Reduction(
                        state = preparedState.copy(camera = stamped),
                        effects = manualFocusPreparation.effects + listOf(
                            PlatformEffect.RampSetting(
                                settingId = spec.id,
                                steps = motor.creditTicks,
                                step = step,
                                intervalMs = motor.intervalMs,
                                maxTicksPerInterval = motor.maxTicksPerInterval,
                            ),
                        ),
                    )
                }

                // Fresh press: the state moves exactly ONE tick now — 0.01, 0.02, 0.03, every
                // step really visited — and the rest of this detent's worth plays out through
                // the motor at the sensitivity's rate.
                val raw = currentIndex + step
                // AF is behind a REAL stop at both rails: the wheel must rest before further
                // travel may cross into auto. A spin that never pauses parks at the rail.
                val restedAtRail = gapMs >= FOCUS_AF_PAUSE_MS
                val nextIndex = when {
                    // From AF a press enters the scale at whichever end it points to.
                    currentIndex == 0 && step < 0 -> spec.options.lastIndex
                    currentIndex == 0 -> 1
                    // At 0.00 pressing outward: into AF only after the rest.
                    raw == 0 -> if (restedAtRail) 0 else 1
                    // At 1.00 pressing outward: same gate on the infinity side.
                    raw > spec.options.lastIndex ->
                        if (restedAtRail) 0 else spec.options.lastIndex
                    else -> raw
                }
                // `currentIndex > 0` keeps the AF exit exact. Leaving AF lands deliberately on a
                // rail (see the branch at currentIndex == 0 above); banking this click's ticks on
                // top of that landing would carry the lens straight back off it — at sensitivity
                // 100 the 51-tick credit would finish at 0.750 instead of 1.000.
                val rampEffects = if (motor.creditTicks > 1 && nextIndex != currentIndex &&
                    nextIndex > 0 && currentIndex > 0
                ) {
                    // Velocity-matched pacing: this click's ticks are spread across the diver's
                    // own clicking cadence, so slow turning is one continuous creep instead of
                    // a full-rate lurch after every detent. The stop window stretches with the
                    // cadence too — a slow clicker is still "turning" between clicks — while a
                    // fast spin keeps the tight stop-on-stop feel.
                    // Rate first, then a (ticks, interval) pair that actually delivers it.
                    val rate = drainRatePerSecond(motor, gapMs.coerceAtLeast(1L))
                    val (spread, paced) = pacing(rate, motor)
                    listOf(
                        PlatformEffect.RampSetting(
                            settingId = spec.id,
                            steps = motor.creditTicks - 1,
                            step = step,
                            intervalMs = paced,
                            maxTicksPerInterval = spread,
                            // Generous upper bound so a deliberately SLOW turn is not reaped mid-move: at
                            // the old 750 ms ceiling, anything slower than roughly one detent a
                            // second had its remaining distance discarded, which is what made
                            // slow turning feel impossible rather than merely slow.
                            stopTimeoutMs = (gapMs * 3 / 2).coerceIn(250L, 2_500L),
                        ),
                    )
                } else {
                    emptyList()
                }
                val nextValue = spec.options[nextIndex]
                val nextCamera = applySettingValue(preparedCamera, spec.id, nextValue)
                    .copy(lastFocusInputAtMs = now)
                val effect = cameraEffectForSetting(spec.id, nextValue)
                Reduction(
                    state = preparedState.copy(camera = nextCamera),
                    effects = manualFocusPreparation.effects +
                        (effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList()) +
                        rampEffects,
                )
            } else if (spec.kind == CameraSettingKind.Slider) {
                // Every slider rides the same motor as focus — ISO, shutter, white balance,
                // exposure — scaled to its own ladder, per the generalisation rule.
                val now = nowMs()
                val gapMs = now - preparedCamera.lastFocusInputAtMs
                val motor = sliderMotorFor(
                    spec.options.size,
                    currentSensitivity,
                    gapMs,
                    held = repeatCount > 0,
                )
                if (repeatCount > 0) {
                    val stamped = preparedCamera.copy(lastFocusInputAtMs = now)
                    return Reduction(
                        state = preparedState.copy(camera = stamped),
                        effects = manualFocusPreparation.effects + listOf(
                            PlatformEffect.RampSetting(
                                settingId = spec.id,
                                steps = motor.creditTicks,
                                step = step,
                                intervalMs = motor.intervalMs,
                                maxTicksPerInterval = motor.maxTicksPerInterval,
                            ),
                        ),
                    )
                }
                val nextValue = advanceOption(
                    currentValue = preparedCamera.settingValues[spec.id] ?: spec.defaultValue,
                    options = spec.options,
                    step = step,
                    wrap = false,
                )
                val rampEffects = if (motor.creditTicks > 1) {
                    // Rate first, then a (ticks, interval) pair that actually delivers it.
                    val rate = drainRatePerSecond(motor, gapMs.coerceAtLeast(1L))
                    val (spread, paced) = pacing(rate, motor)
                    listOf(
                        PlatformEffect.RampSetting(
                            settingId = spec.id,
                            steps = motor.creditTicks - 1,
                            step = step,
                            intervalMs = paced,
                            maxTicksPerInterval = spread,
                            // Generous upper bound so a deliberately SLOW turn is not reaped mid-move: at
                            // the old 750 ms ceiling, anything slower than roughly one detent a
                            // second had its remaining distance discarded, which is what made
                            // slow turning feel impossible rather than merely slow.
                            stopTimeoutMs = (gapMs * 3 / 2).coerceIn(250L, 2_500L),
                        ),
                    )
                } else {
                    emptyList()
                }
                val nextCamera = applySettingValue(preparedCamera, spec.id, nextValue)
                    .copy(lastFocusInputAtMs = now)
                val effect = cameraEffectForSetting(spec.id, nextValue)
                Reduction(
                    state = preparedState.copy(camera = nextCamera),
                    effects = manualFocusPreparation.effects +
                        (effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList()) +
                        rampEffects,
                )
            } else {
                val shouldWrap = spec.kind != CameraSettingKind.Slider
                val nextValue = advanceOption(
                    currentValue = preparedCamera.settingValues[spec.id] ?: spec.defaultValue,
                    options = spec.options,
                    step = step,
                    wrap = shouldWrap,
                )
                val nextCamera = applySettingValue(preparedCamera, spec.id, nextValue)
                val effect = cameraEffectForSetting(spec.id, nextValue)
                Reduction(
                    state = preparedState.copy(camera = nextCamera),
                    effects = manualFocusPreparation.effects +
                        (effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList()),
                )
            }
        }
    }

    private fun prepareStateForManualFocus(
        state: AppState,
        focusSpec: CameraSettingSpec,
    ): ManualFocusPreparation = ManualFocusPreparation(state)

    private fun applySettingValue(camera: CameraState, settingId: String, value: String): CameraState {
        val updatedValues = camera.settingValues + (settingId to value)
        return if (settingId == "photo.zoom_level" || settingId == "video.zoom") {
            camera.copy(
                zoomFactor = parseZoom(value) ?: camera.zoomFactor,
                settingValues = updatedValues,
            )
        } else {
            camera.copy(settingValues = updatedValues)
        }
    }

    private fun advanceOption(currentValue: String, options: List<String>, step: Int, wrap: Boolean = false): String {
        if (options.isEmpty()) {
            return currentValue
        }
        val currentIndex = options.indexOf(currentValue).takeIf { it >= 0 } ?: 0
        val nextIndex = if (wrap) {
            ((currentIndex + step) % options.size + options.size) % options.size
        } else {
            (currentIndex + step).coerceIn(0, options.lastIndex)
        }
        return options[nextIndex]
    }

    internal companion object {
        /** Fastest ramp cadence: one frame. Slower ladders stretch the interval instead. */
        const val FOCUS_RAMP_TICK_MS = 16L

        /**
         * Ceiling on ticks drained per frame — 8 per 16 ms is 500 core dispatches a second.
         *
         * That is exactly the peak the 101-rung ladder already reached, so doubling focus to 201
         * rungs costs nothing per second; it also halves the app's true peak, since the 162-rung
         * exposure ladder was running at 1000/s. Safe against truncating the tail of a spin only
         * because [ButtonEventNormalizer]'s repeat-continuation window floors the gap between
         * wheel events; shortening that window invalidates this cap.
         */
        const val MAX_TICKS_PER_FRAME = 8

        /**
         * How much longer than the observed wheel cadence a detent's distance is spread over.
         *
         * Pure margin against an irregular wheel: at 1.0 the drain runs dry the moment an event
         * arrives late, which is the stall. The cost is a tail — after the hand stops, the
         * remaining quarter-detent keeps arriving for about a quarter of a wheel period.
         */
        const val UNDER_RUN = 1.7

        /**
         * Floor on the gap between deliveries. Below a frame the display cannot show it anyway,
         * but going finer than 16 ms lets a fast turn arrive as several small steps across the
         * frame instead of one visible lurch, at no extra dispatch cost.
         */
        const val MIN_INTERVAL_MS = 4L

        /** How long the wheel must rest at a rail before further travel may enter AF. */
        const val FOCUS_AF_PAUSE_MS = 600L

        /**
         * Wheel events the housing delivers in a fast quarter turn — the calibration anchor
         * for "quarter turn = full sweep at sensitivity 100". EMPIRICAL, not assumed: two
         * field measurements triangulate it (42 ticks at 21/event pre-dedup-fix => 2 events
         * of which half were dropped; 48 ticks at ~12/event post-fix => 4 events). The
         * housing firmware paces wheel notifications, so this is events, not physical clicks.
         */
        const val QUARTER_TURN_DETENTS = 4.0

        /** A held button tops the motor up with this much run-time per repeat event. */
        const val HOLD_TOPUP_MS = 120L

        /** Wheel rate at or below which a detent is worth its minimum (fine focus). */
        const val VELOCITY_SLOW_DPS = 2.5

        /** Wheel rate at which a detent carries its full sensitivity-scaled worth. */
        const val VELOCITY_FAST_DPS = 12.0
    }

    private data class SliderMotor(
        val creditTicks: Int,
        val intervalMs: Long,
        val maxTicksPerInterval: Int,
    )

    /**
     * How many ticks a frame may spend so a detent's credit lasts until the next detent.
     *
     * The housing paces wheel notifications at only two or three a second. Draining a detent's
     * credit at the motor's top rate spent it in ~160 ms and then stood still for the remaining
     * ~300 ms, which reads as lurch-pause-lurch rather than a turn — the faster the sensitivity,
     * the bigger the lurch, because the credit grows while the wheel's cadence does not.
     * Consumption is therefore matched to production: spread the credit evenly over the gap the
     * wheel is actually delivering, and the lens moves continuously for as long as the diver
     * keeps turning. Capped by the motor's own ceiling so a genuinely fast spin still runs flat
     * out, and floored at one so it always makes progress.
     */
    /**
     * The pace a detent's distance should be delivered at, in rungs per second.
     *
     * UNDER-RUN: finishing exactly when the next detent is due leaves the pipe empty the moment
     * the wheel is slightly irregular, and the lens stalls — measured at 15-49% of every turn
     * spent stationary. Spreading over a longer window keeps distance owed when the next detent
     * lands. Self-stabilising: each gap injects R and drains R/UNDER_RUN, so the debt settles at
     * UNDER_RUN x R and the delivered rate at exactly R/gap — the gearing itself, unchanged.
     */
    private fun drainRatePerSecond(motor: SliderMotor, gapMs: Long): Double =
        motor.creditTicks.toDouble() * 1000.0 / (gapMs * UNDER_RUN)

    /**
     * Split a rate into (ticks per interval, interval) WITHOUT rounding the rate away.
     *
     * A frame can only carry a whole number of ticks, and the previous code took
     * `ceil(rate x frame)` — so a wanted 1.2 ticks/frame became 2, delivering 67% too fast,
     * emptying the pipe early and recreating the exact stall the under-run was added to prevent.
     * Raising the under-run could not help, because ceil ate the increase every time.
     *
     * The rate is what matters, so round the COUNT up and then stretch the INTERVAL to match:
     * 75 rungs/s becomes 2 ticks per 27 ms (74/s) rather than 2 per 16 ms (125/s).
     */
    private fun pacing(ratePerSecond: Double, motor: SliderMotor): Pair<Int, Long> {
        if (ratePerSecond <= 0.0) return 1 to FOCUS_RAMP_TICK_MS
        // Smoothest delivery for a given rate is the SMALLEST batch that fits: one rung as often
        // as needed, rather than eight rungs every 16 ms with a wait between. Same rungs per
        // second and the same dispatch cost, but the lens moves in 0.005 steps instead of 0.04
        // ones. Only widen the batch when the interval would fall below the floor.
        var ticks = 1
        var interval = kotlin.math.round(1000.0 / ratePerSecond).toLong()
        while (interval < MIN_INTERVAL_MS && ticks < motor.maxTicksPerInterval) {
            ticks++
            interval = kotlin.math.round(ticks * 1000.0 / ratePerSecond).toLong()
        }
        return ticks to interval.coerceAtLeast(MIN_INTERVAL_MS)
    }

    /**
     * The one motor behind every slider-kind setting. Three inputs, per the field spec:
     *
     *  - **Wheel velocity**: a slow, deliberate click is always worth exactly one tick —
     *    precision survives every sensitivity — and a click's worth grows with spin speed.
     *  - **Sensitivity**: scales both a click's worth (quadratically, so below 100 the wheel
     *    is more granular and the far end costs more turns) and the playback rate (the full
     *    ladder plays in ~450 ms at 100, stretching toward ~2.4 s at the bottom).
     *  - **The ladder itself**: everything is proportional to the setting's own option count,
     *    so a quarter turn at sensitivity 100 sweeps ISO's nine rungs and focus's two hundred
     *    and one alike, end to end.
     *
     * Held buttons don't deposit distance per event; they top the motor up with enough ticks
     * to run continuously until the next repeat arrives — smooth motion at the sensitivity's
     * full rate, stopping when the button does.
     */
    private fun sliderMotorFor(
        usableTicks: Int,
        sensitivity: SliderSensitivity,
        gapMs: Long,
        held: Boolean,
    ): SliderMotor {
        // Re-centred by field calibration: the full-sweep-per-quarter-turn feel lives at the
        // MIDPOINT (50), with real headroom above it — 100 plays the ladder in ~220 ms.
        val sweepMs = (2400L - (sensitivity.level - 1) * 40L).coerceAtLeast(220L)
        // Double, not integer, division. Truncating here made the pace depend on ladder length:
        // once focus doubled to 201 rungs, level 1 played its sweep in 1608 ms instead of the
        // nominal 2400 ms, because 2400/201 floored to 11 rather than 11.94.
        val perTickMs = (sweepMs.toDouble() / usableTicks.coerceAtLeast(1)).coerceAtLeast(0.001)
        val intervalMs: Long
        val maxTicks: Int
        if (perTickMs >= FOCUS_RAMP_TICK_MS) {
            intervalMs = perTickMs.toLong()
            maxTicks = 1
        } else {
            maxTicks = kotlin.math.ceil(FOCUS_RAMP_TICK_MS / perTickMs).toInt()
                .coerceIn(1, MAX_TICKS_PER_FRAME)
            // Stretch the interval so the ladder still plays in sweepMs. Where the burst is
            // capped the 16 ms floor takes over and the sweep runs a little longer than
            // nominal — that is the deliberate trade for holding the dispatch rate down.
            intervalMs = kotlin.math.max(
                FOCUS_RAMP_TICK_MS,
                kotlin.math.round(maxTicks * perTickMs).toLong(),
            )
        }
        val credit = if (held) {
            (((HOLD_TOPUP_MS + intervalMs - 1) / intervalMs).toInt() * maxTicks).coerceAtLeast(1)
        } else {
            // Turn RATE, as a smooth ramp rather than a staircase — this is the wheel's
            // analogue of the native slider, where a faster drag traverses more of the
            // ruler for the same gesture. Linear, not exponential: the diver must still be
            // able to land on a value with gloves on.
            val detentsPerSecond = if (gapMs <= 0L) VELOCITY_FAST_DPS else 1000.0 / gapMs
            val velocity = ((detentsPerSecond - VELOCITY_SLOW_DPS) /
                (VELOCITY_FAST_DPS - VELOCITY_SLOW_DPS)).coerceIn(0.0, 1.0)
            // Above the midpoint the velocity gate progressively stands down, and at 100 it
            // stands down COMPLETELY: a quarter turn spends the whole ladder however slowly the
            // diver turns. Previously this floor topped out at 0.35, so an unhurried quarter
            // turn carried only ~58% of nominal worth and stalled around the middle of the
            // scale — the diver chose raw sensitivity, and gets it. The one-tick precision
            // guarantee remains absolute at and below 50, where the floor is still zero.
            val sensFloor = ((sensitivity.level - 50).coerceAtLeast(0) / 50.0)
                .let { it * it }
            val effectiveVelocity = maxOf(velocity, sensFloor)
            // Sensitivity 100 spends the WHOLE ladder in one quarter turn, and not a step
            // more: perDetent = ladder / quarter-turn detents. Below that it falls away
            // quadratically, so mid-scale is a full turn end to end and the low end is a
            // fine-focus vernier — while the one-step-per-detent floor guarantees every
            // 0.01 remains reachable at any sensitivity.
            val sensFactor = (sensitivity.level / 100.0).let { it * it }
            val perDetent = usableTicks / QUARTER_TURN_DETENTS * sensFactor * effectiveVelocity
            // Round UP, so the quarter-turn guarantee is exact rather than one step short of
            // the rail (a ladder rarely divides evenly by the quarter-turn detent count).
            kotlin.math.ceil(kotlin.math.max(1.0, perDetent)).toInt()
        }
        return SliderMotor(credit, intervalMs, maxTicks)
    }

    /** The wheel's step over a focus scale, honouring the per-mode Wheel Direction choice. */
    private fun focusAwareStep(camera: CameraState, spec: CameraSettingSpec, base: Int): Int =
        if (spec.id.endsWith(".manual_focus") && CameraCatalog.focusWheelReversed(camera)) -base else base

    private fun cycleSensitivity(current: SliderSensitivity, step: Int): SliderSensitivity {
        return SliderSensitivity.of(current.level + step)
    }

    private fun editTargetsFor(camera: CameraState, spec: CameraSettingSpec): List<SliderEditTarget> {
        val targets = mutableListOf(SliderEditTarget.Value)
        if (spec.supportsSensitivity) {
            targets += SliderEditTarget.Sensitivity
        }
        if (focusAssistSpec(camera, spec) != null) {
            targets += SliderEditTarget.FocusAssist
        }
        if (focusCurveSpec(camera, spec) != null) {
            targets += SliderEditTarget.FocusCurve
        }
        if (spec.id.endsWith(".manual_focus")) {
            targets += SliderEditTarget.FocusDirection
            targets += SliderEditTarget.FocusRampIn
            targets += SliderEditTarget.FocusRampOut
        }
        return targets
    }

    private fun focusAssistSpec(camera: CameraState, focusSpec: CameraSettingSpec): CameraSettingSpec? {
        val assistSettingId = CameraCatalog.focusAssistSettingId(focusSpec.id) ?: return null
        return CameraCatalog.settingsFor(camera.activeMode, camera.deviceVariant)
            .firstOrNull { it.id == assistSettingId }
    }

    private fun focusCurveSpec(camera: CameraState, focusSpec: CameraSettingSpec): CameraSettingSpec? {
        val curveSettingId = CameraCatalog.focusCurveSettingId(focusSpec.id) ?: return null
        return CameraCatalog.settingsFor(camera.activeMode, camera.deviceVariant)
            .firstOrNull { it.id == curveSettingId }
    }

    private fun supportsManualFocusForSelectedLens(
        camera: CameraState,
        @Suppress("UNUSED_PARAMETER") focusSpec: CameraSettingSpec,
    ): Boolean {
        // Determine the current lens from state
        val lensSettingId = when (camera.activeMode) {
            CameraModeId.Photo -> "photo.lens"
            CameraModeId.Pro -> "pro.lens"
            CameraModeId.ExpertRaw -> "expert.lens"
            CameraModeId.ProVideo -> "pro_video.lens"
            else -> return true
        }
        val currentLens = camera.settingValues[lensSettingId] ?: "Auto"
        // "Auto" mode always supports focus (hardware decides)
        // "0.6x" on most phones is fixed focus — let the runtime controller handle
        // the actual check via the detected focus capabilities
        // For the core reducer, we allow all lenses but "fixed" won't produce focus commands
        return currentLens != "fixed"
    }



    private fun cameraEffectForSetting(settingId: String, value: String): CameraCommand? = when (settingId) {
        "photo.flash",
        "expert.flash",
        "pro.flash",
        "night.flash",
        "burst.flash",
        "video.flash",
        "pro_video.flash",
        "portrait_video.flash" -> CameraCommand.SetFlashMode(value)
        "photo.lens",
        "portrait.lens",
        "pro.lens",
        "expert.lens",
        "video.lens",
        "pro_video.lens",
        "night.lens",
        "panorama.lens",
        "burst.lens",
        "single_take.lens",
        "hyperlapse.lens",
        "portrait_video.lens",
        "slow_motion.lens",
        "dual_record.lens",
        "night_video.lens" -> CameraCommand.SwitchLens(value)
        "photo.zoom_level",
        "video.zoom" -> parseZoom(value)?.let { CameraCommand.SetZoom(it) }
        "photo.megapixels",
        "expert.megapixels",
        "night.megapixels",
        "burst.megapixels",
        "single_take.megapixels",
        "video.megapixels" -> CameraCommand.SetPhotoResolution(value)
        "photo.save_format",
        "expert.save_format" -> CameraCommand.SetCaptureFormat(value)
        "pro.iso",
        "expert.iso",
        "pro_video.iso" -> value.toIntOrNull()?.let { CameraCommand.SetIso(it) }
        "pro.shutter_speed",
        "expert.shutter_speed",
        "pro_video.shutter_speed" -> parseShutterSpeedNs(value)?.let { CameraCommand.SetShutterSpeedNs(it) }
        "pro.white_balance",
        "expert.white_balance",
        "pro_video.white_balance" -> value.removeSuffix("K").toIntOrNull()?.let { CameraCommand.SetWhiteBalanceKelvin(it) }
        "photo.manual_focus",
        "pro.manual_focus",
        "expert.manual_focus",
        "pro_video.manual_focus" -> parseManualFocus(value)?.let { CameraCommand.SetManualFocus(it) }
        "photo.exposure_compensation",
        "portrait.exposure",
        "pro.exposure_value",
        "expert.exposure_value",
        "video.exposure",
        "night.exposure",
        "macro.exposure",
        "pro_video.exposure_value" -> parseExposureCompensation(value)?.let { CameraCommand.SetExposureCompensation(it) }
        "expert.focus_peaking",
        "pro.focus_peaking",
        "pro_video.focus_peaking" -> CameraCommand.ToggleFocusPeaking
        "photo.focus_curve",
        "expert.focus_curve",
        "pro.focus_curve",
        "pro_video.focus_curve" -> null // Focus curve is handled locally in the app, no camera command needed
        "photo.hdr_log" -> CameraCommand.SetHdrLogMode(value)
        "video.hdr",
        "night_video.hdr" -> CameraCommand.SetHdrLogMode(if (value == "On") "HDR" else "Off")
        "night_video.log" -> CameraCommand.SetHdrLogMode(if (value == "On") "LOG" else "Off")
        "photo.filters",
        "video.filters" -> CameraCommand.SetFilter(value)
        else -> null
    }

    private fun parseZoom(value: String): Double? = value.removeSuffix("x").toDoubleOrNull()

    private fun parseManualFocus(value: String): Double? {
        return if (value == "AF") 0.0 else value.toDoubleOrNull()
    }

    private fun parseExposureCompensation(value: String): Double? =
        // "Auto" is the explicit zero-offset entry: auto-exposure with no compensation.
        if (value == "Auto") 0.0 else value.replace("+", "").toDoubleOrNull()

    private fun parseShutterSpeedNs(value: String): Long? {
        if (value == "Auto") {
            return null
        }
        return if (value.endsWith("\"")) {
            value.removeSuffix("\"").toDoubleOrNull()?.let { (it * 1_000_000_000L).toLong() }
        } else {
            val pieces = value.split("/")
            if (pieces.size != 2) {
                null
            } else {
                val numerator = pieces[0].toDoubleOrNull() ?: return null
                val denominator = pieces[1].toDoubleOrNull() ?: return null
                ((numerator / denominator) * 1_000_000_000L).toLong()
            }
        }
    }

    private fun emitCameraEffect(state: AppState, command: CameraCommand): Reduction {
        return if (!state.permissions.camera) {
            warning(state, "Camera Permission: Disabled")
        } else {
            Reduction(
                state = state,
                effects = listOf(PlatformEffect.ExecuteCamera(command)),
            )
        }
    }

    private fun mergeSafetyResult(state: AppState, result: SafetyMachineResult): Reduction {
        // When the machine clears its own warning, the banner that warning produced has to go
        // with it — otherwise a resolved seal warning outlives the condition. Warnings from
        // other subsystems (BLE, permissions) are left alone.
        val safetyWarningCleared = state.safety.warning != null && result.state.warning == null
        val carriedWarning = state.lastWarning
            .takeUnless { safetyWarningCleared && it == state.safety.warning }

        val nextState = state.copy(
            safety = result.state,
            lastWarning = result.note ?: result.state.warning ?: carriedWarning,
        )
        return Reduction(
            state = nextState,
            effects = result.effects,
            notes = listOfNotNull(result.note),
        )
    }

    private fun reduceGallery(state: AppState, command: GalleryCommand): Reduction {
        val gallery = state.gallery
        return when (command) {
            GalleryCommand.NavigateUp -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Browser -> {
                        if (gallery.items.isNotEmpty()) {
                            val nextIndex = (gallery.selectedIndex - 1).coerceAtLeast(0)
                            Reduction(state = state.copy(gallery = gallery.copy(selectedIndex = nextIndex)))
                        } else {
                            Reduction(state = state)
                        }
                    }
                    GalleryViewMode.ConfirmDelete, GalleryViewMode.ConfirmFolderDelete, GalleryViewMode.CreateFolder -> {
                        // Toggle between confirm (0) and cancel (1)
                        val next = if (gallery.confirmButtonIndex == 0) 1 else 0
                        Reduction(state = state.copy(gallery = gallery.copy(confirmButtonIndex = next)))
                    }
                    else -> Reduction(state = state)
                }
            }
            GalleryCommand.NavigateDown -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Browser -> {
                        if (gallery.items.isNotEmpty()) {
                            val nextIndex = (gallery.selectedIndex + 1).coerceAtMost(gallery.items.lastIndex)
                            Reduction(state = state.copy(gallery = gallery.copy(selectedIndex = nextIndex)))
                        } else {
                            Reduction(state = state)
                        }
                    }
                    GalleryViewMode.ConfirmDelete, GalleryViewMode.ConfirmFolderDelete, GalleryViewMode.CreateFolder -> {
                        val next = if (gallery.confirmButtonIndex == 0) 1 else 0
                        Reduction(state = state.copy(gallery = gallery.copy(confirmButtonIndex = next)))
                    }
                    else -> Reduction(state = state)
                }
            }
            GalleryCommand.NavigateLeft -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Preview -> {
                        // Navigate to previous item in preview
                        val nextIndex = (gallery.selectedIndex - 1).coerceAtLeast(0)
                        val item = gallery.items.getOrNull(nextIndex)
                        Reduction(
                            state = state.copy(gallery = gallery.copy(selectedIndex = nextIndex, previewExifLines = emptyList())),
                            effects = item?.let { listOf(PlatformEffect.LoadExifData(it)) } ?: emptyList(),
                        )
                    }
                    GalleryViewMode.Browser -> {
                        // Switch tab left
                        val tabs = GalleryTab.entries
                        val currentTabIndex = tabs.indexOf(gallery.tab)
                        val nextTabIndex = (currentTabIndex - 1 + tabs.size) % tabs.size
                        Reduction(
                            state = state.copy(gallery = gallery.copy(
                                tab = tabs[nextTabIndex],
                                selectedIndex = 0,
                                items = emptyList(),
                            )),
                            effects = listOf(PlatformEffect.LoadGalleryItems),
                        )
                    }
                    GalleryViewMode.ConfirmDelete, GalleryViewMode.ConfirmFolderDelete, GalleryViewMode.CreateFolder -> {
                        val next = if (gallery.confirmButtonIndex == 0) 1 else 0
                        Reduction(state = state.copy(gallery = gallery.copy(confirmButtonIndex = next)))
                    }
                    else -> Reduction(state = state)
                }
            }
            GalleryCommand.NavigateRight -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Preview -> {
                        // Navigate to next item in preview
                        val nextIndex = (gallery.selectedIndex + 1).coerceAtMost(gallery.items.lastIndex.coerceAtLeast(0))
                        val item = gallery.items.getOrNull(nextIndex)
                        Reduction(
                            state = state.copy(gallery = gallery.copy(selectedIndex = nextIndex, previewExifLines = emptyList())),
                            effects = item?.let { listOf(PlatformEffect.LoadExifData(it)) } ?: emptyList(),
                        )
                    }
                    GalleryViewMode.Browser -> {
                        // Switch tab right
                        val tabs = GalleryTab.entries
                        val currentTabIndex = tabs.indexOf(gallery.tab)
                        val nextTabIndex = (currentTabIndex + 1) % tabs.size
                        Reduction(
                            state = state.copy(gallery = gallery.copy(
                                tab = tabs[nextTabIndex],
                                selectedIndex = 0,
                                items = emptyList(),
                            )),
                            effects = listOf(PlatformEffect.LoadGalleryItems),
                        )
                    }
                    GalleryViewMode.ConfirmDelete, GalleryViewMode.ConfirmFolderDelete, GalleryViewMode.CreateFolder -> {
                        val next = if (gallery.confirmButtonIndex == 0) 1 else 0
                        Reduction(state = state.copy(gallery = gallery.copy(confirmButtonIndex = next)))
                    }
                    else -> Reduction(state = state)
                }
            }
            GalleryCommand.Confirm -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Browser -> {
                        val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                        if (item.isFolder) {
                            Reduction(
                                state = state.copy(gallery = gallery.copy(
                                    currentFolder = item.path,
                                    selectedIndex = 0,
                                    items = emptyList(),
                                )),
                                effects = listOf(PlatformEffect.LoadGalleryItems),
                            )
                        } else {
                            Reduction(
                                state = state.copy(gallery = gallery.copy(
                                    viewMode = GalleryViewMode.Preview,
                                    previewExifLines = emptyList(),
                                )),
                                effects = listOf(PlatformEffect.LoadExifData(item)),
                            )
                        }
                    }
                    GalleryViewMode.ConfirmDelete -> {
                        if (gallery.confirmButtonIndex == 0) {
                            // Delete confirmed
                            val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                            val nextItems = gallery.items.toMutableList().apply { removeAt(gallery.selectedIndex) }
                            val nextIndex = gallery.selectedIndex.coerceAtMost((nextItems.size - 1).coerceAtLeast(0))
                            Reduction(
                                state = state.copy(gallery = gallery.copy(
                                    viewMode = GalleryViewMode.Browser,
                                    items = nextItems,
                                    selectedIndex = nextIndex,
                                    confirmButtonIndex = 1,
                                )),
                                effects = listOf(PlatformEffect.DeleteGalleryItem(item)),
                            )
                        } else {
                            // Cancel
                            Reduction(state = state.copy(gallery = gallery.copy(
                                viewMode = GalleryViewMode.Browser,
                                confirmButtonIndex = 1,
                            )))
                        }
                    }
                    GalleryViewMode.ConfirmFolderDelete -> {
                        if (gallery.confirmButtonIndex == 0) {
                            val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                            if (item.isFolder) {
                                val nextItems = gallery.items.toMutableList().apply { removeAt(gallery.selectedIndex) }
                                val nextIndex = gallery.selectedIndex.coerceAtMost((nextItems.size - 1).coerceAtLeast(0))
                                Reduction(
                                    state = state.copy(gallery = gallery.copy(
                                        viewMode = GalleryViewMode.Browser,
                                        items = nextItems,
                                        selectedIndex = nextIndex,
                                        confirmButtonIndex = 1,
                                    )),
                                    effects = listOf(PlatformEffect.DeleteGalleryFolder(item.path)),
                                )
                            } else {
                                Reduction(state = state.copy(gallery = gallery.copy(
                                    viewMode = GalleryViewMode.Browser,
                                    confirmButtonIndex = 1,
                                )))
                            }
                        } else {
                            Reduction(state = state.copy(gallery = gallery.copy(
                                viewMode = GalleryViewMode.Browser,
                                confirmButtonIndex = 1,
                            )))
                        }
                    }
                    GalleryViewMode.CreateFolder -> {
                        if (gallery.confirmButtonIndex == 0 && gallery.folderName.isNotBlank()) {
                            Reduction(
                                state = state.copy(gallery = gallery.copy(
                                    viewMode = GalleryViewMode.Browser,
                                    folderName = "",
                                    confirmButtonIndex = 1,
                                )),
                                effects = listOf(
                                    PlatformEffect.CreateGalleryFolder(gallery.folderName),
                                    PlatformEffect.LoadGalleryItems,
                                ),
                            )
                        } else {
                            Reduction(state = state.copy(gallery = gallery.copy(
                                viewMode = GalleryViewMode.Browser,
                                folderName = "",
                                confirmButtonIndex = 1,
                            )))
                        }
                    }
                    else -> Reduction(state = state)
                }
            }
            GalleryCommand.Back -> {
                when (gallery.viewMode) {
                    GalleryViewMode.Preview -> {
                        Reduction(state = state.copy(gallery = gallery.copy(
                            viewMode = GalleryViewMode.Browser,
                            previewExifLines = emptyList(),
                        )))
                    }
                    GalleryViewMode.ConfirmDelete, GalleryViewMode.ConfirmFolderDelete -> {
                        Reduction(state = state.copy(gallery = gallery.copy(viewMode = GalleryViewMode.Browser)))
                    }
                    GalleryViewMode.CreateFolder -> {
                        Reduction(state = state.copy(gallery = gallery.copy(
                            viewMode = GalleryViewMode.Browser,
                            folderName = "",
                        )))
                    }
                    GalleryViewMode.Browser -> {
                        if (gallery.currentFolder != null) {
                            Reduction(
                                state = state.copy(gallery = gallery.copy(
                                    currentFolder = null,
                                    selectedIndex = 0,
                                    items = emptyList(),
                                )),
                                effects = listOf(PlatformEffect.LoadGalleryItems),
                            )
                        } else {
                            // Exit gallery, return to camera
                            Reduction(state = state.copy(mode = AppMode.CameraLive))
                        }
                    }
                }
            }
            GalleryCommand.InitiateDelete -> {
                if (gallery.viewMode == GalleryViewMode.Browser || gallery.viewMode == GalleryViewMode.Preview) {
                    val item = gallery.items.getOrNull(gallery.selectedIndex)
                    if (item != null && !item.isFolder) {
                        Reduction(state = state.copy(gallery = gallery.copy(viewMode = GalleryViewMode.ConfirmDelete, confirmButtonIndex = 1)))
                    } else {
                        Reduction(state = state)
                    }
                } else {
                    Reduction(state = state)
                }
            }
            GalleryCommand.CreateFolder -> {
                if (gallery.viewMode == GalleryViewMode.Browser && gallery.tab == GalleryTab.Folders) {
                    val timestamp = System.currentTimeMillis()
                    val folderName = "Dive_$timestamp"
                    Reduction(
                        state = state.copy(gallery = gallery.copy(
                            viewMode = GalleryViewMode.CreateFolder,
                            folderName = folderName,
                            confirmButtonIndex = 1,
                        )),
                    )
                } else {
                    Reduction(state = state)
                }
            }
            GalleryCommand.DeleteFolder -> {
                if (gallery.viewMode == GalleryViewMode.Browser && gallery.tab == GalleryTab.Folders) {
                    val item = gallery.items.getOrNull(gallery.selectedIndex)
                    if (item != null && item.isFolder) {
                        Reduction(state = state.copy(gallery = gallery.copy(viewMode = GalleryViewMode.ConfirmFolderDelete, confirmButtonIndex = 1)))
                    } else {
                        Reduction(state = state)
                    }
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.LoadItems -> {
                Reduction(state = state.copy(gallery = gallery.copy(items = command.items, selectedIndex = 0)))
            }
            is GalleryCommand.SetExifLines -> {
                Reduction(state = state.copy(gallery = gallery.copy(previewExifLines = command.lines)))
            }
        }
    }

    private fun warning(state: AppState, message: String): Reduction {
        return Reduction(
            state = state.copy(lastWarning = message),
            effects = listOf(PlatformEffect.EmitAlert(AlertPriority.High, message)),
            notes = listOf(message),
        )
    }

    private fun fallbackMode(state: AppState): AppMode {
        return if (state.permissions.camera) {
            AppMode.CameraLive
        } else {
            AppMode.Diagnostics
        }
    }

    private fun nextCursorSpeed(current: CursorSpeedProfile): CursorSpeedProfile = when (current) {
        CursorSpeedProfile.Precision -> CursorSpeedProfile.Normal
        CursorSpeedProfile.Normal -> CursorSpeedProfile.Fast
        CursorSpeedProfile.Fast -> CursorSpeedProfile.Fast
        CursorSpeedProfile.SmartTarget -> CursorSpeedProfile.SmartTarget
    }

    private fun previousCursorSpeed(current: CursorSpeedProfile): CursorSpeedProfile = when (current) {
        CursorSpeedProfile.Precision -> CursorSpeedProfile.Precision
        CursorSpeedProfile.Normal -> CursorSpeedProfile.Precision
        CursorSpeedProfile.Fast -> CursorSpeedProfile.Normal
        CursorSpeedProfile.SmartTarget -> CursorSpeedProfile.SmartTarget
    }
}

