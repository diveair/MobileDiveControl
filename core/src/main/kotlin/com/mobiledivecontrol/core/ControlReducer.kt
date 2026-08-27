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
        is DiagnosticsCommand -> reduceDiagnostics(state, command)
        is GalleryCommand -> reduceGalleryGrid(state, command)
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
        val wasGranted = when (permission) {
            PermissionKind.Bluetooth -> state.permissions.bluetooth
            PermissionKind.Camera -> state.permissions.camera
            PermissionKind.Microphone -> state.permissions.microphone
            PermissionKind.Overlay -> state.permissions.overlay
            PermissionKind.Accessibility -> state.permissions.accessibility
            PermissionKind.ForegroundService -> state.permissions.foregroundService
            PermissionKind.Notifications -> state.permissions.notifications
        }
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
                // A first-run permission request reports false before the user has answered. That
                // is not a revocation and must not strand a fresh install on Diagnostics. Only a
                // genuine true -> false transition leaves the camera for the safe state screen.
                if (wasGranted && !granted && nextState.mode in setOf(AppMode.CameraLive, AppMode.CameraAdjust)) {
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

    /**
     * Merges the live AE/AWB readings into state. Platform telemetry on the same footing as
     * [updatePhoneBattery]: it never changes a mode, never emits an effect, and arrives outside
     * the housing-input critical path. The HUD prints these beside the automatic modes the way
     * the native chips do. Ring controls deliberately traverse their literal neighbours instead
     * of jumping to these readings when they leave Auto.
     */
    fun updateMeteredExposure(state: AppState, metered: MeteredExposure): Reduction {
        return Reduction(
            state = state.copy(camera = state.camera.copy(meteredExposure = metered)),
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
                    recordingPausedAction = RecordingPausedAction.Resume,
                    recordingPreviewVisible = false,
                    recordingLocationFocused = false,
                    recordingLocationChooserVisible = false,
                    recordingSaveConfirmationVisible = false,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(CameraCommand.StartVideoRecording)),
        )
        CameraCommand.PauseVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recordingPaused = true,
                    // The chooser always opens on RESUME: the least destructive answer remains
                    // the default while CameraX finalises a valid reviewable segment.
                    recordingPausedAction = RecordingPausedAction.Resume,
                    recordingPreviewVisible = false,
                    recordingLocationFocused = false,
                    recordingLocationChooserVisible = false,
                    recordingSaveConfirmationVisible = false,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.ResumeVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recordingPaused = false,
                    recordingPausedAction = RecordingPausedAction.Resume,
                    recordingPreviewVisible = false,
                    recordingLocationFocused = false,
                    recordingLocationChooserVisible = false,
                    recordingSaveConfirmationVisible = false,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.PreviewVideoRecording -> {
            if (!state.camera.recording || !state.camera.recordingPaused) {
                Reduction(state = state)
            } else {
                Reduction(
                    state = state.copy(
                        camera = state.camera.copy(
                            recordingPreviewVisible = !state.camera.recordingPreviewVisible,
                            recordingLocationFocused = false,
                            recordingLocationChooserVisible = false,
                            recordingSaveConfirmationVisible = false,
                        ),
                    ),
                )
            }
        }
        CameraCommand.StopVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recording = false,
                    recordingPaused = false,
                    recordingPausedAction = RecordingPausedAction.Resume,
                    recordingPreviewVisible = false,
                    recordingLocationFocused = false,
                    recordingLocationChooserVisible = false,
                    recordingSaveConfirmationVisible = false,
                    // Bumped so the gallery thumbnail refreshes with the finished video.
                    captureCounter = state.camera.captureCounter + 1,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.DeleteVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    recording = false,
                    recordingPaused = false,
                    recordingPausedAction = RecordingPausedAction.Resume,
                    recordingPreviewVisible = false,
                    recordingLocationFocused = false,
                    recordingLocationChooserVisible = false,
                    recordingSaveConfirmationVisible = false,
                    // Force the gallery shortcut to discard any thumbnail of the deleted clip.
                    captureCounter = state.camera.captureCounter + 1,
                ),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.OpenRecordingSaveLocationChooser -> {
            if (!state.camera.recording || !state.camera.recordingPaused) {
                Reduction(state = state)
            } else {
                val selectedIndex = recordingSaveLocationIndex(
                    state.camera.recordingSaveLocations,
                    state.camera.recordingSaveLocation,
                ).coerceAtLeast(0)
                Reduction(
                    state = state.copy(
                        camera = state.camera.copy(
                            recordingLocationFocused = true,
                            recordingLocationChooserVisible = true,
                            recordingSaveConfirmationVisible = false,
                            recordingSaveConfirmationAction = RecordingSaveConfirmationAction.Confirm,
                            recordingSaveLocationIndex = selectedIndex,
                            recordingPreviewVisible = false,
                        ),
                    ),
                    effects = listOf(PlatformEffect.LoadRecordingSaveLocations),
                )
            }
        }
        is CameraCommand.LoadRecordingSaveLocations -> {
            val locations = command.locations
                .distinctBy { it.relativePath.trimEnd('/').lowercase() }
                .ifEmpty { listOf(RecordingSaveLocation.Default) }
            val selectedIndex = recordingSaveLocationIndex(
                locations,
                state.camera.recordingSaveLocation,
            ).coerceAtLeast(0)
            Reduction(
                state = state.copy(
                    camera = state.camera.copy(
                        recordingSaveLocations = locations,
                        // Refresh cover/count metadata while retaining the selected path.
                        recordingSaveLocation = locations[selectedIndex],
                        recordingSaveLocationIndex = selectedIndex,
                    ),
                ),
            )
        }
        is CameraCommand.HighlightRecordingSaveLocation -> {
            if (
                !state.camera.recordingLocationChooserVisible ||
                state.camera.recordingSaveConfirmationVisible ||
                command.index !in state.camera.recordingSaveLocations.indices
            ) {
                Reduction(state = state)
            } else {
                Reduction(
                    state = state.copy(
                        camera = state.camera.copy(recordingSaveLocationIndex = command.index),
                    ),
                )
            }
        }
        is CameraCommand.OpenRecordingSaveLocationConfirmation -> {
            if (
                !state.camera.recordingLocationChooserVisible ||
                command.index !in state.camera.recordingSaveLocations.indices
            ) {
                Reduction(state = state)
            } else {
                Reduction(
                    state = state.copy(
                        camera = state.camera.copy(
                            recordingSaveLocationIndex = command.index,
                            recordingSaveConfirmationVisible = true,
                            recordingSaveConfirmationAction = RecordingSaveConfirmationAction.Confirm,
                        ),
                    ),
                )
            }
        }
        is CameraCommand.SelectRecordingSaveLocation -> {
            val location = state.camera.recordingSaveLocations.getOrNull(command.index)
            if (location == null) {
                Reduction(state = state)
            } else {
                Reduction(
                    state = state.copy(
                        camera = state.camera.copy(
                            recordingSaveLocation = location,
                            recordingSaveLocationIndex = command.index,
                            recordingLocationChooserVisible = false,
                            recordingSaveConfirmationVisible = false,
                            recordingLocationFocused = state.camera.recording && state.camera.recordingPaused,
                        ),
                    ),
                )
            }
        }
        CameraCommand.NavigateUp -> navigateCameraUp(state, repeatCount)
        CameraCommand.NavigateDown -> navigateCameraDown(state, repeatCount)
        CameraCommand.NavigateLeft -> navigateCameraLeft(state, repeatCount)
        CameraCommand.NavigateRight -> navigateCameraRight(state, repeatCount)
        CameraCommand.Confirm -> confirmCameraSelection(state)
        CameraCommand.Back -> backOutCameraUi(state)
        CameraCommand.OpenModeRail -> Reduction(
            state = state.copy(
                camera = modeRailForCurrentMode(
                    camera = state.camera,
                    returnZone = if (state.camera.focusedZone == CameraUiZone.SettingsPanel) {
                        CameraUiZone.SettingsPanel
                    } else {
                        CameraUiZone.LiveView
                    },
                ),
            ),
        )
        is CameraCommand.ActivateModeRailEntry -> activateModeRailEntry(state, command.index)
        CameraCommand.ToggleOptionsMenu -> toggleOptionsMenu(state)
        is CameraCommand.SelectOptionsItem -> selectOptionsItem(state, command.index)
        is CameraCommand.AdjustOptionsItem -> {
            val selected = selectOptionsItem(state, command.index).state
            adjustSelectedOptionsSetting(selected, command.step)
        }
        CameraCommand.ZoomIn -> handleWheel(state, +1)
        CameraCommand.ZoomOut -> handleWheel(state, -1)
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
            val spec = CameraCatalog.settingsFor(state.camera)
                .firstOrNull { it.id == command.settingId }
            if (spec == null || CameraCatalog.evMeterLocked(state.camera, spec)) {
                // Missing spec, or the native EV meter rule: with both ISO and shutter manual
                // the compensation index does nothing, so the detent is refused, not absorbed.
                Reduction(state = state)
            } else {
                val currentValue = state.camera.settingValues[spec.id] ?: spec.defaultValue
                val nextValue = if (CameraCatalog.isCircularSlider(spec)) {
                    advanceOption(currentValue, spec.options, command.step, wrap = true)
                } else {
                    seededManualValue(state.camera, spec, currentValue, command.step)
                        ?: run {
                            val currentIndex = spec.options.indexOf(currentValue).coerceAtLeast(0)
                            // Focus's AF remains a deliberate mode barrier. Circular exposure
                            // controls take the branch above and intentionally have no rail.
                            val minIndex = if (spec.id.endsWith(".manual_focus") && currentIndex > 0) 1 else 0
                            val nextIndex = (currentIndex + command.step).coerceIn(minIndex, spec.options.lastIndex)
                            spec.options[nextIndex]
                        }
                }
                if (nextValue == currentValue) {
                    Reduction(state = state)
                } else {
                    val effect = cameraEffectForSetting(spec.id, nextValue)
                    Reduction(
                        state = state.copy(camera = applySettingValue(state.camera, spec.id, nextValue)),
                        effects = effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList(),
                    )
                }
            }
        }
        is CameraCommand.UpdateCameraCapabilities -> {
            // Capabilities SHRINK the ladders, so values that were legal against the full native
            // tables must be re-spelled onto the clipped ones the moment the ranges arrive —
            // otherwise the strip keeps showing a rung the write path clamps away, and the next
            // detent resolves the unfound value from index 0.
            val withCaps = state.camera.copy(capabilities = command.capabilities)
            Reduction(
                state = state.copy(camera = CameraCatalog.resnapToClippedLadders(withCaps)),
            )
        }
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
        SystemCommand.SwitchToDiagnosticsMode -> Reduction(
            state = state.copy(
                mode = AppMode.Diagnostics,
                diagnosticsAction = DiagnosticsAction.BackToCamera,
            ),
        )
        SystemCommand.ExportDiagnostics -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExportDiagnostics),
        )
        SystemCommand.LockControls -> Reduction(state = state.copy(controlsLocked = true))
        SystemCommand.UnlockControls -> Reduction(state = state.copy(controlsLocked = false))
    }

    private fun reduceDiagnostics(state: AppState, command: DiagnosticsCommand): Reduction = when (command) {
        DiagnosticsCommand.NavigatePrevious -> Reduction(
            state = state.copy(diagnosticsAction = previousDiagnosticsAction(state.diagnosticsAction)),
        )
        DiagnosticsCommand.NavigateNext -> Reduction(
            state = state.copy(diagnosticsAction = nextDiagnosticsAction(state.diagnosticsAction)),
        )
        DiagnosticsCommand.Confirm -> activateDiagnosticsAction(state, state.diagnosticsAction)
        DiagnosticsCommand.Back -> reduceSystem(state, SystemCommand.SwitchToCameraMode)
        is DiagnosticsCommand.Activate -> activateDiagnosticsAction(
            state.copy(diagnosticsAction = command.action),
            command.action,
        )
    }

    private fun previousDiagnosticsAction(action: DiagnosticsAction): DiagnosticsAction = when (action) {
        DiagnosticsAction.BackToCamera -> DiagnosticsAction.Export
        DiagnosticsAction.Export -> DiagnosticsAction.BackToCamera
    }

    private fun nextDiagnosticsAction(action: DiagnosticsAction): DiagnosticsAction = when (action) {
        DiagnosticsAction.BackToCamera -> DiagnosticsAction.Export
        DiagnosticsAction.Export -> DiagnosticsAction.BackToCamera
    }

    private fun activateDiagnosticsAction(state: AppState, action: DiagnosticsAction): Reduction = when (action) {
        DiagnosticsAction.BackToCamera -> reduceSystem(state, SystemCommand.SwitchToCameraMode)
        DiagnosticsAction.Export -> reduceSystem(state, SystemCommand.ExportDiagnostics)
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
    private fun handleWheel(state: AppState, step: Int): Reduction {
        val repeatCount = 0
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
        if (camera.focusedZone == CameraUiZone.SettingsPanel && camera.showMoreSettings) {
            return adjustSelectedOptionsSetting(state, step)
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

    /** Housing navigation for the paused action rail and its save-location control. */
    private fun pausedChooserNavigation(
        state: AppState,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
    ): Reduction? {
        val camera = state.camera
        if (!camera.recording || !camera.recordingPaused) return null
        if (camera.recordingLocationChooserVisible) {
            if (camera.recordingSaveConfirmationVisible) {
                if (horizontalStep == 0) return Reduction(state = state)
                val actions = RecordingSaveConfirmationAction.entries
                val current = actions.indexOf(camera.recordingSaveConfirmationAction).coerceAtLeast(0)
                return Reduction(
                    state = state.copy(
                        camera = camera.copy(
                            recordingSaveConfirmationAction = actions[
                                ((current + horizontalStep) % actions.size + actions.size) % actions.size
                            ],
                        ),
                    ),
                )
            }
            if (verticalStep == 0 && horizontalStep == 0) return Reduction(state = state)
            val count = camera.recordingSaveLocations.size
            // The destination chooser is a one-dimensional album rail. Both housing axes move
            // one album so the highlighted card and the navigation model cannot diverge.
            val step = if (horizontalStep != 0) horizontalStep else verticalStep
            val next = if (count > 0) {
                ((camera.recordingSaveLocationIndex + step) % count + count) % count
            } else {
                0
            }
            return Reduction(
                state = state.copy(camera = camera.copy(recordingSaveLocationIndex = next)),
            )
        }
        if (verticalStep < 0) {
            return Reduction(
                state = state.copy(camera = camera.copy(recordingLocationFocused = true)),
            )
        }
        if (verticalStep > 0) {
            return Reduction(
                state = state.copy(camera = camera.copy(recordingLocationFocused = false)),
            )
        }
        if (camera.recordingLocationFocused || horizontalStep == 0) return Reduction(state = state)
        val actions = RecordingPausedAction.entries
        val current = actions.indexOf(camera.recordingPausedAction).coerceAtLeast(0)
        return Reduction(
            state = state.copy(
                camera = camera.copy(
                    recordingPausedAction = actions[
                        ((current + horizontalStep) % actions.size + actions.size) % actions.size
                    ],
                ),
            ),
        )
    }

    private fun navigateCameraUp(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, verticalStep = -1)?.let { return it }
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
                if (camera.showMoreSettings) {
                    moveOptionsCursor(state, -1)
                } else if (camera.settingsEditing) {
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
        pausedChooserNavigation(state, verticalStep = +1)?.let { return it }
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
                if (camera.showMoreSettings) {
                    moveOptionsCursor(state, +1)
                } else if (camera.settingsEditing) {
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
        pausedChooserNavigation(state, horizontalStep = -1)?.let { return it }
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
                if (camera.showMoreSettings) {
                    adjustSelectedOptionsSetting(state, -1, repeatCount)
                } else if (camera.settingsEditing) {
                    adjustSelectedSetting(state, -1, repeatCount)
                } else {
                    moveSettingsCursor(state, -1)
                }
            }
        }
    }

    private fun navigateCameraRight(state: AppState, repeatCount: Int = 0): Reduction {
        pausedChooserNavigation(state, horizontalStep = +1)?.let { return it }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> Reduction(
                state = state.copy(camera = focusModeRail(camera).copy(highlightedPrimaryIndex = 0)),
            )
            CameraUiZone.ModeRail -> enterFromModeRail(state)
            CameraUiZone.SettingsPanel -> {
                if (camera.showMoreSettings) {
                    adjustSelectedOptionsSetting(state, +1, repeatCount)
                } else if (camera.settingsEditing) {
                    adjustSelectedSetting(state, +1, repeatCount)
                } else {
                    moveSettingsCursor(state, +1)
                }
            }
        }
    }

    private fun confirmCameraSelection(state: AppState): Reduction {
        // OK mirrors the shutter while the paused chooser is up: confirm the selected action.
        if (state.camera.recording && state.camera.recordingPaused) {
            if (state.camera.recordingLocationChooserVisible) {
                return if (!state.camera.recordingSaveConfirmationVisible) {
                    reduceCamera(
                        state,
                        CameraCommand.OpenRecordingSaveLocationConfirmation(
                            state.camera.recordingSaveLocationIndex,
                        ),
                    )
                } else {
                    when (state.camera.recordingSaveConfirmationAction) {
                        RecordingSaveConfirmationAction.Back -> reduceCamera(state, CameraCommand.Back)
                        RecordingSaveConfirmationAction.Confirm -> reduceCamera(
                            state,
                            CameraCommand.SelectRecordingSaveLocation(
                                state.camera.recordingSaveLocationIndex,
                            ),
                        )
                    }
                }
            }
            if (state.camera.recordingLocationFocused) {
                return reduceCamera(state, CameraCommand.OpenRecordingSaveLocationChooser)
            }
            val command = when (state.camera.recordingPausedAction) {
                RecordingPausedAction.Resume -> CameraCommand.ResumeVideoRecording
                RecordingPausedAction.Preview -> CameraCommand.PreviewVideoRecording
                RecordingPausedAction.Stop -> CameraCommand.StopVideoRecording
                RecordingPausedAction.Delete -> CameraCommand.DeleteVideoRecording
            }
            return reduceCamera(state, command)
        }
        val camera = state.camera
        return when (camera.focusedZone) {
            CameraUiZone.LiveView -> openSettingsPanel(state, camera.activeMode)
            CameraUiZone.ModeRail -> confirmModeSelection(state)
            CameraUiZone.SettingsPanel -> {
                if (camera.showMoreSettings) {
                    // Options is an editor, just like the Focus OK-menu: OK commits the
                    // current state and returns to the unchanged horizontal settings rail.
                    toggleOptionsMenu(state)
                } else if (camera.settingsEditing) {
                    confirmSettingEdit(state)
                } else {
                    activateHighlightedItem(state)
                }
            }
        }
    }

    private fun backOutCameraUi(state: AppState): Reduction {
        // Back closes a clip preview first. From the chooser itself it keeps the session by
        // starting a continuation clip, which is still the least destructive answer.
        if (state.camera.recording && state.camera.recordingPaused) {
            if (state.camera.recordingLocationChooserVisible) {
                return Reduction(
                    state = state.copy(
                        camera = state.camera.copy(
                            recordingLocationChooserVisible = false,
                            recordingSaveConfirmationVisible = false,
                            recordingLocationFocused = true,
                        ),
                    ),
                )
            }
            if (state.camera.recordingPreviewVisible) {
                return Reduction(
                    state = state.copy(
                        camera = state.camera.copy(recordingPreviewVisible = false),
                    ),
                )
            }
            if (state.camera.recordingLocationFocused) {
                return Reduction(
                    state = state.copy(camera = state.camera.copy(recordingLocationFocused = false)),
                )
            }
            return reduceCamera(state, CameraCommand.ResumeVideoRecording)
        }
        val camera = state.camera
        return when {
            camera.focusedZone == CameraUiZone.SettingsPanel && camera.showMoreSettings -> Reduction(
                state = state.copy(camera = camera.copy(showMoreSettings = false)),
            )
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
            // Up/Down from live view means cycle relative to the active capture mode. The
            // explicit Modes-button/right-entry path overrides this to Track Heading (index 0).
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
            railLevel = CameraRailLevel.Primary,
            highlightedPrimaryIndex = 0,
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
            CameraRailLevel.Primary -> activatePrimaryRailEntry(state)
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
            CameraRailLevel.Primary -> activatePrimaryRailEntry(state)
            CameraRailLevel.Secondary -> activateMode(
                state,
                camera.secondaryHighlightedMode,
                returnToLiveView = false,
                openSettings = true,
            )
        }
    }

    private fun activatePrimaryRailEntry(state: AppState): Reduction {
        val camera = state.camera
        val entry = camera.primaryHighlightedEntry
        if (entry.opensSecondaryRail) {
            return Reduction(
                state = state.copy(
                    camera = camera.copy(
                        railLevel = CameraRailLevel.Secondary,
                        highlightedSecondaryIndex = CameraCatalog.secondaryIndexForMode(camera.activeMode),
                    ),
                ),
            )
        }
        if (entry.action == CameraRailAction.TrackHeading) {
            if (camera.recording) {
                val warning = "Stop recording before setting a tracked heading."
                return Reduction(
                    state = state.copy(lastWarning = warning),
                    notes = listOf(warning),
                )
            }
            return Reduction(
                state = state.copy(
                    // Track Heading is an action, not a destination. Return through the rail's
                    // recorded entry path so opening it from the camera navbar restores navbar
                    // focus, while opening it directly from LiveView still returns to LiveView.
                    camera = exitModeRail(camera),
                    lastWarning = null,
                ),
                effects = listOf(PlatformEffect.TrackCurrentHeading),
            )
        }
        if (entry.action == CameraRailAction.Diagnostics) {
            if (camera.recording) {
                val warning = "Stop recording before opening Diagnostics."
                return Reduction(
                    state = state.copy(lastWarning = warning),
                    notes = listOf(warning),
                )
            }
            return Reduction(
                state = state.copy(
                    mode = AppMode.Diagnostics,
                    camera = exitModeRail(camera),
                    diagnosticsAction = DiagnosticsAction.BackToCamera,
                    lastWarning = null,
                ),
            )
        }
        return activateMode(
            state,
            requireNotNull(entry.mode) { "Primary rail entry ${entry.key} has no mode or action" },
            returnToLiveView = false,
            openSettings = true,
        )
    }

    private fun activateModeRailEntry(state: AppState, index: Int): Reduction {
        if (index !in CameraCatalog.primaryRailEntries.indices) return Reduction(state = state)
        val selectedState = state.copy(
            camera = state.camera.copy(
                focusedZone = CameraUiZone.ModeRail,
                railLevel = CameraRailLevel.Primary,
                highlightedPrimaryIndex = index,
                settingsEditing = false,
                sliderEditTarget = SliderEditTarget.Value,
            ),
        )
        return activatePrimaryRailEntry(selectedState)
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
                state = state.copy(
                    mode = AppMode.Gallery,
                    gallery = state.gallery.copy(
                        viewMode = GalleryViewMode.Browser,
                        items = emptyList(),
                        selectedIndex = 0,
                        currentFolder = null,
                        currentFolderName = null,
                        previewExifLines = emptyList(),
                        detailsVisible = false,
                        videoPlaying = false,
                        browserBackFocused = false,
                        browserAction = null,
                        operationMessage = null,
                    ),
                ),
                effects = listOf(PlatformEffect.LoadGalleryItems),
            )
            is BottomBarItem.MoreSettings -> {
                toggleOptionsMenu(state)
            }
            is BottomBarItem.Setting -> {
                val preparation = if (item.spec.id.endsWith(".manual_focus")) {
                    prepareStateForManualFocus(state)
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

    private fun toggleOptionsMenu(state: AppState): Reduction {
        val opening = !state.camera.showMoreSettings
        val optionCount = CameraCatalog.optionsMenuSettings(state.camera).size
        val nextCursor = if (optionCount == 0) 0 else {
            state.camera.optionsMenuCursor.coerceIn(0, optionCount - 1)
        }
        val optionsTileIndex = CameraCatalog.settingsBarItems(state.camera)
            .indexOfFirst { it is BottomBarItem.MoreSettings }
        return Reduction(
            state = state.copy(
                camera = state.camera.copy(
                    showMoreSettings = opening,
                    optionsMenuCursor = nextCursor,
                    // Touch and housing entry converge here. Focus editing leaves Focus selected;
                    // Options likewise owns and returns to its original far-left tile.
                    settingsCursor = if (opening && optionsTileIndex >= 0) {
                        optionsTileIndex
                    } else {
                        state.camera.settingsCursor
                    },
                    settingsEditing = false,
                    sliderEditTarget = SliderEditTarget.Value,
                ),
            ),
            effects = if (opening && state.camera.activeMode in setOf(CameraModeId.Pro, CameraModeId.ProVideo)) {
                listOf(PlatformEffect.LoadRecordingSaveLocations)
            } else {
                emptyList()
            },
        )
    }

    private fun selectOptionsItem(state: AppState, index: Int): Reduction {
        if (!state.camera.showMoreSettings) return Reduction(state = state)
        val lastIndex = CameraCatalog.optionsMenuSettings(state.camera).lastIndex
        if (lastIndex < 0) return Reduction(state = state)
        return Reduction(
            state = state.copy(
                camera = state.camera.copy(optionsMenuCursor = index.coerceIn(0, lastIndex)),
            ),
        )
    }

    private fun moveOptionsCursor(state: AppState, delta: Int): Reduction {
        val settings = CameraCatalog.optionsMenuSettings(state.camera)
        if (settings.size <= 1) return Reduction(state = state)
        // Match the Focus editor: the highlighted card stops at either end instead of
        // unexpectedly wrapping a diver from the first setting to the last.
        val next = (state.camera.optionsMenuCursor + delta).coerceIn(0, settings.lastIndex)
        return Reduction(
            state = state.copy(camera = state.camera.copy(optionsMenuCursor = next)),
        )
    }

    private fun adjustSelectedOptionsSetting(
        state: AppState,
        step: Int,
        repeatCount: Int = 0,
    ): Reduction {
        val spec = CameraCatalog.selectedOptionsSetting(state.camera) ?: return Reduction(state = state)
        if (spec.id.endsWith(".save_location")) {
            val locations = state.camera.recordingSaveLocations
            if (locations.isEmpty()) return Reduction(state = state)
            val current = recordingSaveLocationIndex(locations, state.camera.recordingSaveLocation)
                .takeIf { it >= 0 }
                ?: state.camera.recordingSaveLocationIndex.coerceIn(0, locations.lastIndex)
            val next = ((current + step) % locations.size + locations.size) % locations.size
            return Reduction(
                state = state.copy(
                    camera = state.camera.copy(
                        recordingSaveLocation = locations[next],
                        recordingSaveLocationIndex = next,
                    ),
                ),
            )
        }
        return adjustSetting(state, spec, step, repeatCount)
    }

    private fun recordingSaveLocationIndex(
        locations: List<RecordingSaveLocation>,
        selected: RecordingSaveLocation,
    ): Int {
        val selectedPath = selected.relativePath.trimEnd('/')
        return locations.indexOfFirst {
            it.relativePath.trimEnd('/').equals(selectedPath, ignoreCase = true)
        }
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
            prepareStateForManualFocus(state)
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
                // CLAMPED. lastFocusInputAtMs starts at 0 while the clock is epoch millis, so an
                // untouched dial reports a gap of ~1.8e12 ms and every pacing figure derived from
                // it becomes nonsense — the first ramp slept for roughly two YEARS, never woke,
                // and left its job permanently active so every later detent merged into it and
                // moved a single rung. A rest of even 30 s was enough to reap a whole turn.
                // Anything slower than one detent a second is a fresh press, not a turn in
                // progress, so treating it as the slowest real cadence loses nothing.
                val rawGapMs = now - preparedCamera.lastFocusInputAtMs
                val gapMs = rawGapMs.coerceIn(1L, MAX_WHEEL_GAP_MS)
                val usable = spec.options.size - 1 // the numeric scale, AF excluded
                val motor = sliderMotorFor(usable, currentSensitivity, gapMs, held = repeatCount > 0)
                // A CONTINUOUS blend from a geared turn down to a single-rung click, never a
                // cliff. The gearing table fixes a detent at ~50 rungs at sensitivity 100 — that
                // is what "a quarter turn sweeps the range" means arithmetically — so a turn can
                // never place a value precisely. A hard click/turn boundary was worse: 1799 ms
                // bought ~50 rungs and 1801 ms bought 1, and the middle ground where a diver
                // actually sets focus did not exist at all.
                //
                // Geometric rather than linear, so the midpoint is the geometric mean: the
                // progression reads 1, 7, 50 instead of 1, 25, 50, putting the fine range where
                // the hand naturally slows. A real turn sits at or under TURN_GAP_MS and keeps
                // full gearing, so the sensitivity table and the fast pull are untouched.
                val turnBlend = ((FINE_CLICK_GAP_MS - rawGapMs).toDouble() /
                    (FINE_CLICK_GAP_MS - TURN_GAP_MS).toDouble()).coerceIn(0.0, 1.0)
                val credit = kotlin.math.max(
                    1.0,
                    kotlin.math.round(
                        Math.pow(motor.creditTicks.coerceAtLeast(1).toDouble(), turnBlend),
                    ),
                ).toInt()

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
                // TWO conditions, because divers were crossing into AF by accident. Autofocus
                // is a MODE CHANGE, not the next value along, so arriving at a rail must never
                // be enough — and neither is pausing there, which happens naturally mid-turn.
                // The wheel has to STOP at the rail for [FOCUS_AF_PAUSE_MS], and then keep
                // pushing outward for [AF_BREAKTHROUGH_PRESSES] further detents. A pause is
                // ambiguous; a pause followed by sustained pushing against a rail that is
                // visibly not moving can only be someone asking for autofocus.
                //
                // Measured on the RAW gap, not the pacing-clamped one: gapMs is capped at
                // MAX_WHEEL_GAP_MS so a stale timestamp cannot poison the drain rate, and
                // against that cap this test could never see a pause longer than the cap.
                val restedAtRail = rawGapMs >= FOCUS_AF_PAUSE_MS
                val pushingPastRail = raw == 0 || raw > spec.options.lastIndex
                // Counting is FORGIVING; only crossing is strict. Requiring the pause to land on
                // the very first press was brittle: one stray wheel event as the hand comes to
                // rest re-stamps lastFocusInputAtMs, so a real pause got measured from the stray
                // event and nothing ever armed — the barrier then could not be passed at all.
                // Presses against a rail therefore always accumulate, and a qualifying pause at
                // ANY point in the run arms it.
                val railPresses = when {
                    // Focus moved normally: the run is over and the count means nothing.
                    !pushingPastRail -> 0
                    restedAtRail -> kotlin.math.max(preparedCamera.focusRailPresses, 0) + 1
                    preparedCamera.focusRailPresses > 0 -> preparedCamera.focusRailPresses + 1
                    // Pushing at a rail with no pause yet: parks, and waits to be armed.
                    else -> 0
                }
                val breakIntoAf = railPresses >= AF_BREAKTHROUGH_PRESSES
                // AF DWELL. The housing auto-repeats at roughly 15 Hz, and wheel events are
                // deliberately treated as fresh presses (repeatCount is forced to 0 so gearing
                // applies at speed). One physical input is therefore several events: the first
                // crossed into AF and the next, ~67 ms later, walked straight back out to the
                // opposite rail — the reported "one input, 0.000 to AF to 1.000". Leaving AF
                // requires an input genuinely separated from the one that arrived, so the same
                // gesture cannot pass through. It also stops the repeat stream from racing the
                // rail detection, which made crossings land or miss unpredictably.
                val mayLeaveAf = rawGapMs >= AF_EXIT_GUARD_MS
                val nextIndex = when {
                    // Still inside the dwell: hold AF, whatever the wheel says.
                    currentIndex == 0 && !mayLeaveAf -> 0
                    // From AF a press enters the scale at whichever end it points to.
                    currentIndex == 0 && step < 0 -> spec.options.lastIndex
                    currentIndex == 0 -> 1
                    // At 0.000 pressing outward: into AF only once BOTH conditions are met.
                    raw == 0 -> if (breakIntoAf) 0 else 1
                    // At 1.000 pressing outward: the same barrier on the infinity side.
                    raw > spec.options.lastIndex ->
                        if (breakIntoAf) 0 else spec.options.lastIndex
                    else -> raw
                }
                // `currentIndex > 0` keeps the AF exit exact. Leaving AF lands deliberately on a
                // rail (see the branch at currentIndex == 0 above); banking this click's ticks on
                // top of that landing would carry the lens straight back off it — at sensitivity
                // 100 the 51-tick credit would finish at 0.750 instead of 1.000.
                val rampEffects = if (credit > 1 &&
                    nextIndex != currentIndex && nextIndex > 0 && currentIndex > 0
                ) {
                    // Velocity-matched pacing: this click's ticks are spread across the diver's
                    // own clicking cadence, so slow turning is one continuous creep instead of
                    // a full-rate lurch after every detent. The stop window stretches with the
                    // cadence too — a slow clicker is still "turning" between clicks — while a
                    // fast spin keeps the tight stop-on-stop feel.
                    // Rate first, then a (ticks, interval) pair that actually delivers it.
                    val rate = credit * 1000.0 / (gapMs.coerceAtLeast(1L) * UNDER_RUN)
                    val (spread, paced) = pacing(rate, motor)
                    val span = kotlin.math.round(
                        gapMs.coerceAtLeast(1L) * UNDER_RUN,
                    ).toLong().coerceAtLeast(FOCUS_RAMP_TICK_MS)
                    listOf(
                        PlatformEffect.RampSetting(
                            settingId = spec.id,
                            steps = credit - 1,
                            step = step,
                            intervalMs = paced,
                            maxTicksPerInterval = spread,
                            // Generous upper bound so a deliberately SLOW turn is not reaped mid-move: at
                            // the old 750 ms ceiling, anything slower than roughly one detent a
                            // second had its remaining distance discarded, which is what made
                            // slow turning feel impossible rather than merely slow.
                            stopTimeoutMs = (gapMs * 3 / 2).coerceIn(250L, 2_500L),
                            spanMs = span,
                        ),
                    )
                } else {
                    emptyList()
                }
                val nextValue = spec.options[nextIndex]
                // Stamp only when focus actually MOVED. Every ramp tick re-enters this reducer,
                // so stamping unconditionally let the app's own drain reset the pause clock while
                // parked at a rail — a real, deliberate pause could never accumulate and the
                // crossing never triggered. Time since the last MOVEMENT is also the honest
                // reading of "stopped at the rail for a while".
                val previousValue = preparedCamera.settingValues[spec.id]
                val focusMoved = nextValue != previousValue
                val nextCamera = applySettingValue(preparedCamera, spec.id, nextValue)
                    .copy(
                        lastFocusInputAtMs =
                            if (focusMoved) now else preparedCamera.lastFocusInputAtMs,
                        // Crossing consumes the run, so returning demands the whole deliberate
                        // act again rather than one more nudge.
                        focusRailPresses = if (breakIntoAf) 0 else railPresses,
                    )
                val effect = cameraEffectForSetting(spec.id, nextValue)
                Reduction(
                    state = preparedState.copy(camera = nextCamera),
                    effects = manualFocusPreparation.effects +
                        (effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList()) +
                        rampEffects,
                )
            } else if (spec.kind == CameraSettingKind.Slider) {
                // ISO, shutter, white balance and exposure ride the same MACHINERY as focus —
                // the debt-and-span drain, the under-run, the click-to-turn blend — but not the
                // same gearing: they take [SliderLaw.Discrete], where one detent is one value.
                // See [sliderMotorFor] for why the two laws have to differ.
                if (CameraCatalog.evMeterLocked(preparedCamera, spec)) {
                    // Native EV meter rule: both ISO and shutter manual means the compensation
                    // index does nothing, so the wheel is refused rather than silently absorbed.
                    return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
                }
                val now = nowMs()
                // CLAMPED. lastFocusInputAtMs starts at 0 while the clock is epoch millis, so an
                // untouched dial reports a gap of ~1.8e12 ms and every pacing figure derived from
                // it becomes nonsense — the first ramp slept for roughly two YEARS, never woke,
                // and left its job permanently active so every later detent merged into it and
                // moved a single rung. A rest of even 30 s was enough to reap a whole turn.
                // Anything slower than one detent a second is a fresh press, not a turn in
                // progress, so treating it as the slowest real cadence loses nothing.
                val rawGapMs = now - preparedCamera.lastFocusInputAtMs
                val gapMs = rawGapMs.coerceIn(1L, MAX_WHEEL_GAP_MS)
                val motor = sliderMotorFor(
                    spec.options.size,
                    currentSensitivity,
                    gapMs,
                    held = repeatCount > 0,
                    law = SliderLaw.Discrete,
                )
                // Same continuous click-to-turn blend as the focus branch above.
                val turnBlend = ((FINE_CLICK_GAP_MS - rawGapMs).toDouble() /
                    (FINE_CLICK_GAP_MS - TURN_GAP_MS).toDouble()).coerceIn(0.0, 1.0)
                val credit = kotlin.math.max(
                    1.0,
                    kotlin.math.round(
                        Math.pow(motor.creditTicks.coerceAtLeast(1).toDouble(), turnBlend),
                    ),
                ).toInt()
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
                val sliderCurrentValue = preparedCamera.settingValues[spec.id] ?: spec.defaultValue
                // ISO, shutter, WB and EV are literal rings. Their option order defines the
                // edge transitions, including WB's two automatic modes, and a wheel turn is
                // allowed to traverse that order without pause gates or an auto-to-meter jump.
                val circular = CameraCatalog.isCircularSlider(spec)
                val seededValue = if (circular) {
                    null
                } else {
                    seededManualValue(preparedCamera, spec, sliderCurrentValue, step)
                }
                val nextValue = seededValue
                    ?: advanceOption(
                        currentValue = sliderCurrentValue,
                        options = spec.options,
                        step = step,
                        wrap = circular,
                    )
                // A seeded detent's whole meaning is "land where the meter is" — geared extra
                // rungs on top would overshoot the very value the diver converted to keep.
                val rampEffects = if (seededValue == null && credit > 1) {
                    // Rate first, then a (ticks, interval) pair that actually delivers it.
                    val rate = credit * 1000.0 / (gapMs.coerceAtLeast(1L) * UNDER_RUN)
                    val (spread, paced) = pacing(rate, motor)
                    val span = kotlin.math.round(
                        gapMs.coerceAtLeast(1L) * UNDER_RUN,
                    ).toLong().coerceAtLeast(FOCUS_RAMP_TICK_MS)
                    listOf(
                        PlatformEffect.RampSetting(
                            settingId = spec.id,
                            steps = credit - 1,
                            step = step,
                            intervalMs = paced,
                            maxTicksPerInterval = spread,
                            // Generous upper bound so a deliberately SLOW turn is not reaped mid-move: at
                            // the old 750 ms ceiling, anything slower than roughly one detent a
                            // second had its remaining distance discarded, which is what made
                            // slow turning feel impossible rather than merely slow.
                            stopTimeoutMs = (gapMs * 3 / 2).coerceIn(250L, 2_500L),
                            spanMs = span,
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

    private fun prepareStateForManualFocus(state: AppState): ManualFocusPreparation =
        ManualFocusPreparation(state)

    private fun applySettingValue(camera: CameraState, settingId: String, value: String): CameraState {
        val prefix = settingId.substringBeforeLast('.', "")
        var updatedValues = camera.settingValues + (settingId to value)
        var selectedFps = updatedValues["$prefix.frame_rate"]
            ?.removeSuffix("fps")
            ?.toIntOrNull()
        if (settingId.endsWith(".resolution")) {
            val compatibleRates = camera.capabilities?.videoFrameRatesByResolution
                ?.get(value)
                .orEmpty()
                .sorted()
            if (selectedFps != null && compatibleRates.isNotEmpty() && selectedFps !in compatibleRates) {
                // Preserve the user's temporal intent where possible: take the highest rate no
                // faster than the current selection, otherwise the slowest real rate available.
                val compatibleFps = compatibleRates.lastOrNull { it <= selectedFps!! }
                    ?: compatibleRates.first()
                selectedFps = compatibleFps
                updatedValues = updatedValues + ("$prefix.frame_rate" to "${compatibleFps}fps")
            }
        }
        val highSpeedSelected = selectedFps != null && selectedFps >= HIGH_SPEED_FPS_MIN
        if ((settingId.endsWith(".frame_rate") || settingId.endsWith(".resolution")) && highSpeedSelected) {
            // Android constrained-high-speed sessions force AE/AWB/AF automation and cannot
            // carry CameraEffect or stabilization use cases. Persist the state the session can
            // really honour; leaving manual labels visible here would be a dead-control lie.
            updatedValues = updatedValues +
                ("$prefix.log" to "Off") +
                ("$prefix.hdr" to "Off") +
                ("$prefix.video_stabilization" to "Off") +
                ("$prefix.super_steady" to "Off") +
                ("$prefix.iso" to "Auto") +
                ("$prefix.shutter_speed" to "Auto") +
                ("$prefix.manual_focus" to "AF") +
                ("$prefix.white_balance" to CameraCatalog.WB_AUTO_CONTINUOUS) +
                ("$prefix.focus_peaking" to "Off") +
                ("$prefix.exposure_display" to "Off")
        } else if (settingId.endsWith(".log") && value == "On") {
            updatedValues = updatedValues +
                (prefix + ".hdr" to "Off") +
                (prefix + ".video_stabilization" to "Off") +
                (prefix + ".super_steady" to "Off")
        } else if (settingId.endsWith(".hdr") && value == "On") {
            updatedValues = updatedValues + (prefix + ".log" to "Off")
        } else if (
            (settingId.endsWith(".video_stabilization") && value != "Off") ||
            (settingId.endsWith(".super_steady") && value == "On")
        ) {
            updatedValues = updatedValues + (prefix + ".log" to "Off")
        }
        val incompatibleWithHighSpeed = when {
            settingId.endsWith(".log") && value == "On" -> true
            settingId.endsWith(".hdr") && value == "On" -> true
            settingId.endsWith(".video_stabilization") && value != "Off" -> true
            settingId.endsWith(".super_steady") && value == "On" -> true
            settingId.endsWith(".iso") && value != "Auto" -> true
            settingId.endsWith(".shutter_speed") && value != "Auto" -> true
            settingId.endsWith(".manual_focus") && value != "AF" -> true
            settingId.endsWith(".white_balance") &&
                value != CameraCatalog.WB_AUTO_CONTINUOUS && value != "Auto" -> true
            settingId.endsWith(".focus_peaking") && value == "On" -> true
            settingId.endsWith(".exposure_display") && value != "Off" -> true
            else -> false
        }
        if (incompatibleWithHighSpeed && highSpeedSelected) {
            updatedValues = updatedValues +
                ("$prefix.frame_rate" to preferredNormalFrameRate(camera, prefix))
        }
        val next = if (settingId == "photo.zoom_level" || settingId == "video.zoom") {
            camera.copy(
                zoomFactor = parseZoom(value) ?: camera.zoomFactor,
                settingValues = updatedValues,
            )
        } else {
            camera.copy(settingValues = updatedValues)
        }
        return if (settingId.endsWith(".frame_rate") || settingId.endsWith(".resolution")) {
            demoteShutterToFramePeriod(next, "$prefix.frame_rate")
        } else {
            next
        }
    }

    private fun preferredNormalFrameRate(camera: CameraState, prefix: String): String {
        val capabilities = camera.capabilities
        val resolution = camera.settingValues["$prefix.resolution"]
        val rates = capabilities?.videoFrameRatesByResolution
            ?.get(resolution)
            ?.takeIf { it.isNotEmpty() }
            ?: capabilities?.availableVideoFrameRates.orEmpty()
        val normal = rates.filter { it in 1 until HIGH_SPEED_FPS_MIN }.maxOrNull() ?: 30
        return "${normal}fps"
    }

    /**
     * The native demotion rule (ProVideoPresenter.onStartPreviewCompleted): raising the frame
     * rate past a manual shutter demotes the shutter to the slowest rung the new frame period
     * admits — 1/30 at 30 fps becomes 1/60 at 60 fps — rather than leaving a value the dial can
     * no longer show. Without it the stored value falls off the clipped ladder, the HUD keeps
     * printing it, and the next detent resolves from index 0, which is Auto.
     */
    private fun demoteShutterToFramePeriod(camera: CameraState, frameRateSettingId: String): CameraState {
        val shutterId = frameRateSettingId.substringBeforeLast('.') + ".shutter_speed"
        val current = camera.settingValues[shutterId] ?: return camera
        val ns = CameraCatalog.shutterOptionNanos(current) ?: return camera
        val capNs = CameraCatalog.videoShutterCapNs(camera) ?: return camera
        if (ns <= capNs) return camera
        val admitted = CameraCatalog.shutterLadder.filter { rung ->
            CameraCatalog.shutterOptionNanos(rung)?.let { it <= capNs } == true
        }
        val demoted = CameraCatalog.nearestShutterOption(capNs, admitted) ?: return camera
        return camera.copy(settingValues = camera.settingValues + (shutterId to demoted))
    }

    /**
     * Meter-seeded auto-to-manual handoff for any future non-circular Auto slider. ISO, shutter,
     * WB and EV bypass this helper because their physical wheel contract is an exact ring.
     */
    private fun seededManualValue(
        camera: CameraState,
        spec: CameraSettingSpec,
        currentValue: String,
        step: Int,
    ): String? {
        if (currentValue != "Auto" || step == 0) return null
        return CameraCatalog.meteredSeedValue(camera, spec)
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
        const val HIGH_SPEED_FPS_MIN = 100
        const val ALBUM_GRID_COLUMNS = GALLERY_ALBUM_COLUMNS
        const val MEDIA_GRID_COLUMNS = GALLERY_MEDIA_COLUMNS
        const val MAX_RENAME_LENGTH = 96

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
        const val MAX_TICKS_PER_FRAME = 20

        /**
         * How much longer than the observed wheel cadence a detent's distance is spread over.
         *
         * Pure margin against an irregular wheel: at 1.0 the drain runs dry the moment an event
         * arrives late, which is the stall. The cost is a tail — after the hand stops, the
         * remaining quarter-detent keeps arriving for about a quarter of a wheel period.
         */
        const val UNDER_RUN = 1.15

        /**
         * Longest inter-detent gap treated as a continuous turn. Beyond this the diver has
         * stopped and started again, so the next detent is paced as a fresh press rather than
         * from a stale timestamp.
         */
        const val MAX_WHEEL_GAP_MS = 1_000L

        /**
         * Silence after which the next detent counts as a deliberate single increment rather
         * than part of a turn.
         *
         * This has to clear the SLOWEST continuous turn, not a typical one. At 900 ms it did
         * not: a deliberately slow turn has gaps that long, so every one of its detents was
         * read as an isolated click, dropped to a single rung, and the turn stopped being
         * smooth. The distinction being drawn is isolated-click versus sequence, and a
         * sequence keeps arriving — so the bar belongs above any cadence a turning hand
         * produces, leaving only a real pause on the other side of it.
         */
        const val FINE_CLICK_GAP_MS = 1_800L

        /**
         * At or below this cadence the wheel is unambiguously being TURNED, so full gearing
         * applies and the sensitivity table holds exactly. Above it, gearing tapers toward a
         * single rung as the hand slows, which is the range that makes a value placeable.
         */
        const val TURN_GAP_MS = 500L

        /**
         * Floor on the gap between deliveries. Below a frame the display cannot show it anyway,
         * but going finer than 16 ms lets a fast turn arrive as several small steps across the
         * frame instead of one visible lurch, at no extra dispatch cost.
         */
        const val MIN_INTERVAL_MS = FOCUS_RAMP_TICK_MS

        /** How long the wheel must rest at a rail before further travel may enter AF. */
        const val FOCUS_AF_PAUSE_MS = 700L

        /**
         * Detents pushed against a rail, AFTER the pause, before AF is entered.
         *
         * ONE. A count above one compounds the ways a deliberate attempt can fail — a press
         * landing fractionally early, or a stray auto-repeat re-stamping the timestamp, resets
         * the run and the diver has to start over, which read as the barrier simply ignoring
         * them. The pause carries the whole burden of proving intent, and overshoot is
         * prevented separately by AF cancelling any in-flight ramp rather than by making the
         * crossing itself hard to reach. If accidental crossings return, lengthen
         * [FOCUS_AF_PAUSE_MS] rather than raising this: a longer stop is still one clear
         * gesture, whereas more presses is a sequence that can be broken.
         */
        const val AF_BREAKTHROUGH_PRESSES = 1

        /**
         * Quiet required before AF may be LEFT. Longer than the housing's auto-repeat interval
         * (~67 ms at 15 Hz) so a single held gesture cannot cross in and straight back out,
         * short enough that a genuine second input is never refused.
         */
        const val AF_EXIT_GUARD_MS = 350L

        /**
         * Wheel events the housing delivers in a fast quarter turn — the calibration anchor
         * for "quarter turn = full sweep at sensitivity 100". EMPIRICAL, not assumed: two
         * field measurements triangulate it (42 ticks at 21/event pre-dedup-fix => 2 events
         * of which half were dropped; 48 ticks at ~12/event post-fix => 4 events). The
         * housing firmware paces wheel notifications, so this is events, not physical clicks.
         */
        const val QUARTER_TURN_DETENTS = 4.0

        /**
         * Most rungs a single detent may move a VALUE ladder — ISO, shutter, white balance,
         * exposure — at sensitivity 100 and full spin. Focus is not governed by this.
         *
         * Four, sized to the NATIVE ladders these settings now carry. The native scales are
         * short and coarse by design — ISO has fifteen third-stop rungs, the Pro Video shutter
         * nineteen, white balance seventy-eight — so a native rung is already a big, visible
         * move and skipping many of them per detent would make the dial unusable. Four keeps the
         * fastest gesture worth about a stop on ISO and two on shutter while a slow click stays
         * exactly one rung; a fast spin still crosses the whole ISO dial in four detents and
         * white balance in twenty.
         *
         * Ladder-independent (a stride in RUNGS, not ladder/4) for the same reason as before:
         * tying stride to length is what once made a finer scale harder to use. Peak load is now
         * 4 x 12 detents/s = 48 dispatches a second — a third of the previous 144.
         */
        const val VALUE_MAX_DETENT_RUNGS = 4.0

        /**
         * How fast a HELD direction button walks a value ladder, in rungs per second, at the
         * bottom and the top of the sensitivity dial.
         *
         * Three a second is a readable tick the diver can stop on deliberately; sixteen crosses
         * white balance's 78 native rungs in about 5 s and the fifteen-rung ISO dial in under a
         * second — sized to the native ladders, where every rung is a real photographic step and
         * overshooting one costs a third of a stop. Ladder-independent for the reason in
         * [VALUE_MAX_DETENT_RUNGS].
         */
        const val VALUE_HELD_MIN_RUNGS_PER_SECOND = 3.0
        const val VALUE_HELD_MAX_RUNGS_PER_SECOND = 16.0

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
    /**
     * Which gearing law a slider obeys. Focus and the value ladders want opposite things.
     *
     * [Continuous] is focus: a pull through a physical range, where the diver's target is a
     * PLANE and the rungs are only how finely it is sampled. Sweeping the whole scale in a
     * quarter turn is the right feel, and its arithmetic is signed off — nothing here may
     * change it.
     *
     * [Discrete] is ISO, shutter, white balance and exposure: ladders whose rungs are NAMED
     * values the diver means to land on. "ISO 400" is a destination, not a position along a
     * pull, so a detent that moves twenty-two rungs makes the setting unusable however smooth
     * it feels.
     */
    private enum class SliderLaw { Continuous, Discrete }

    private fun sliderMotorFor(
        usableTicks: Int,
        sensitivity: SliderSensitivity,
        gapMs: Long,
        held: Boolean,
        law: SliderLaw = SliderLaw.Continuous,
    ): SliderMotor {
        val perTickMs = when (law) {
            // Re-centred by field calibration: the full-sweep-per-quarter-turn feel lives at the
            // MIDPOINT (50), with real headroom above it — 100 plays the ladder in ~220 ms.
            //
            // Double, not integer, division. Truncating here made the pace depend on ladder
            // length: once focus doubled to 201 rungs, level 1 played its sweep in 1608 ms
            // instead of the nominal 2400 ms, because 2400/201 floored to 11 rather than 11.94.
            SliderLaw.Continuous -> {
                val sweepMs = (2400L - (sensitivity.level - 1) * 40L).coerceAtLeast(220L)
                (sweepMs.toDouble() / usableTicks.coerceAtLeast(1)).coerceAtLeast(0.001)
            }
            // A RATE, not a sweep — the same reason the detent stride is a rung count.
            //
            // Pacing a value ladder by "play the whole thing in N ms" has the ladder-length
            // pathology in its other form: a held button would walk white balance's 113 rungs
            // and exposure's 41 in the same 220 ms at full sensitivity, so the finer scale would
            // scroll nearly three times faster and be that much harder to stop on. Fixing the
            // rungs PER SECOND instead makes a held button feel identical on every value ladder
            // and makes a longer ladder simply take longer to cross, which is what a diver
            // expects.
            SliderLaw.Discrete -> {
                val sensNorm = (sensitivity.level - 1) / 99.0
                val rungsPerSecond = VALUE_HELD_MIN_RUNGS_PER_SECOND +
                    (VALUE_HELD_MAX_RUNGS_PER_SECOND - VALUE_HELD_MIN_RUNGS_PER_SECOND) *
                    (sensNorm * sensNorm)
                (1000.0 / rungsPerSecond).coerceAtLeast(0.001)
            }
        }
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
            val perDetent = when (law) {
                SliderLaw.Continuous -> {
                    // Above the midpoint the velocity gate progressively stands down, and at 100
                    // it stands down COMPLETELY: a quarter turn spends the whole ladder however
                    // slowly the diver turns. Previously this floor topped out at 0.35, so an
                    // unhurried quarter turn carried only ~58% of nominal worth and stalled
                    // around the middle of the scale — the diver chose raw sensitivity, and gets
                    // it. The one-tick precision guarantee remains absolute at and below 50,
                    // where the floor is still zero.
                    //
                    // This stand-down is exactly what a VALUE ladder must not have, which is why
                    // it lives in this branch and not above the `when`: it is the reason the
                    // wheel could reach only four of white balance's seventy-eight kelvin rungs.
                    val sensFloor = ((sensitivity.level - 50).coerceAtLeast(0) / 50.0)
                        .let { it * it }
                    val effectiveVelocity = maxOf(velocity, sensFloor)
                    // Sensitivity 100 spends the WHOLE ladder in one quarter turn, and not a step
                    // more: perDetent = ladder / quarter-turn detents. Below that it falls away
                    // quadratically, so mid-scale is a full turn end to end and the low end is a
                    // fine-focus vernier — while the one-step-per-detent floor guarantees every
                    // 0.01 remains reachable at any sensitivity.
                    val sensFactor = (sensitivity.level / 100.0).let { it * it }
                    usableTicks / QUARTER_TURN_DETENTS * sensFactor * effectiveVelocity
                }
                // ONE DETENT IS ONE VALUE, and everything else is added on top of that.
                //
                // Two changes from the focus law, and both are needed:
                //
                // The BASE is 1 instead of 0, and the ceiling is a fixed number of rungs
                // instead of ladder/quarter-turn. Tying the stride to the ladder's LENGTH is
                // what made these settings unusable: the same wheel gesture moved 3 rungs when
                // white balance had 10 and 20 once it had 78, so making a scale finer made it
                // harder to use — exactly backwards. A stride measured in rungs means a longer
                // ladder buys resolution and costs nothing else.
                //
                // The velocity gate is NOT stood down at high sensitivity. Focus lets
                // sensitivity 100 sweep the range however slowly the hand moves, and that is
                // precisely what stops a value landing: at 100 the stride was pinned to its
                // maximum at every speed, so white balance could only ever reach four of its
                // kelvin rungs and ISO skipped twenty-one values at a time. Here speed is the
                // only accelerator, so a slow, deliberate click is exactly one rung at EVERY
                // sensitivity — the precision guarantee is absolute rather than conditional —
                // and sensitivity sets how much a fast spin is worth on top.
                //
                // [VALUE_MAX_DETENT_RUNGS] is 4, sized to the native third-stop/half-stop
                // ladders: about a stop per detent at full sensitivity and full speed.
                SliderLaw.Discrete -> {
                    val sensNorm = (sensitivity.level - 1) / 99.0
                    1.0 + (VALUE_MAX_DETENT_RUNGS - 1) * (sensNorm * sensNorm) * velocity
                }
            }
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
        // Manual focus reaches the lens through STATE, never through an effect — the runtime
        // controller has no SetManualFocus branch, so this only ever fell through `else -> Unit`.
        // Emitting it still made outcome.effects non-empty on every one of up to 1250 rungs a
        // second, and that alone opened the whole effects pipeline in the ViewModel, including a
        // Dispatchers.IO coroutine per rung — measured costlier than the reducer dispatch it
        // accompanied, and it kept a second core out of idle for the length of a spin.
        "photo.manual_focus",
        "pro.manual_focus",
        "expert.manual_focus",
        "pro_video.manual_focus" -> null
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

    /**
     * One parser, [CameraCatalog.shutterOptionNanos], shared with capability clipping and with
     * the runtime controller. A private near-copy here disagreed about bare-seconds labels, and a
     * label that clipping accepted but this rejected produced no effect at all — the strip showed
     * a manual shutter while the sensor stayed on auto-exposure.
     */
    private fun parseShutterSpeedNs(value: String): Long? = CameraCatalog.shutterOptionNanos(value)

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

    private fun reduceGalleryGrid(state: AppState, command: GalleryCommand): Reduction {
        val gallery = state.gallery
        return when (command) {
            GalleryCommand.NavigateUp -> navigateGalleryGrid(state, gallery, rowDelta = -1)
            GalleryCommand.NavigateDown -> navigateGalleryGrid(state, gallery, rowDelta = 1)
            GalleryCommand.NavigateLeft -> navigateGalleryGrid(state, gallery, columnDelta = -1)
            GalleryCommand.NavigateRight -> navigateGalleryGrid(state, gallery, columnDelta = 1)
            GalleryCommand.Confirm -> confirmGallerySelection(state, gallery)
            GalleryCommand.Back -> backFromGallery(state, gallery)
            GalleryCommand.InitiateDelete -> initiateGalleryDelete(state, gallery)
            GalleryCommand.CreateFolder -> beginCreateAlbum(state, gallery)
            GalleryCommand.DeleteFolder -> beginDeleteAlbum(state, gallery)

            is GalleryCommand.ActivateBrowserAction -> {
                if (gallery.viewMode == GalleryViewMode.Browser) {
                    activateBrowserAction(state, gallery, command.action)
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.ActivateAlbumAction -> {
                if (gallery.viewMode == GalleryViewMode.AlbumActions) {
                    activateAlbumAction(
                        state,
                        gallery.copy(albumAction = command.action),
                        command.action,
                    )
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.ActivateMediaAction -> {
                if (gallery.viewMode == GalleryViewMode.MediaActions) {
                    activateMediaAction(
                        state,
                        gallery.copy(mediaAction = command.action),
                        command.action,
                    )
                } else {
                    Reduction(state = state)
                }
            }

            is GalleryCommand.OpenItem -> openGalleryItem(state, gallery, command.index)
            is GalleryCommand.ActivatePreviewAction -> {
                if (gallery.viewMode == GalleryViewMode.Preview) {
                    activatePreviewAction(state, gallery.copy(previewAction = command.action), command.action)
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.SelectOption -> {
                if (gallery.viewMode == GalleryViewMode.Options && command.index in 0..2) {
                    activateGalleryOption(state, gallery.copy(optionIndex = command.index), command.index)
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.SelectMoveTarget -> {
                if (gallery.viewMode == GalleryViewMode.Move && command.index in gallery.moveTargets.indices) {
                    confirmGallerySelection(state, gallery.copy(moveTargetIndex = command.index))
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.SelectConfirmation -> {
                if (
                    gallery.viewMode in setOf(
                        GalleryViewMode.ConfirmDelete,
                        GalleryViewMode.ConfirmFolderDelete,
                        GalleryViewMode.CreateFolder,
                    ) && command.index in 0..1
                ) {
                    confirmGallerySelection(state, gallery.copy(confirmButtonIndex = command.index))
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.SetRenameDraft -> {
                if (gallery.viewMode == GalleryViewMode.Rename) {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(renameDraft = command.value.take(MAX_RENAME_LENGTH)),
                        ),
                    )
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.SetFolderName -> {
                if (gallery.viewMode == GalleryViewMode.CreateFolder) {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(folderName = command.value.take(MAX_RENAME_LENGTH)),
                        ),
                    )
                } else {
                    Reduction(state = state)
                }
            }
            is GalleryCommand.LoadItems -> {
                val wasEmpty = gallery.items.isEmpty()
                val selectedItem = gallery.items.getOrNull(gallery.selectedIndex)
                val matchingIndex = selectedItem?.let { selected ->
                    command.items.indexOfFirst { candidate ->
                        if (selected.contentUri.isNotBlank()) {
                            candidate.contentUri == selected.contentUri
                        } else {
                            candidate.id == selected.id && candidate.isVideo == selected.isVideo
                        }
                    }
                } ?: -1
                val nextIndex = when {
                    gallery.pendingMutation == GalleryMutation.CreateAlbum -> 0
                    gallery.pendingMutation == GalleryMutation.Delete &&
                        gallery.confirmationReturnToPreview -> {
                        // At the same index the following item has shifted into the deleted
                        // item's slot. Deleting the last item wraps explicitly to the first.
                        if (gallery.selectedIndex < command.items.size) gallery.selectedIndex else 0
                    }
                    matchingIndex >= 0 -> matchingIndex
                    else -> gallery.selectedIndex.coerceIn(0, command.items.lastIndex.coerceAtLeast(0))
                }
                val isEmpty = command.items.isEmpty()
                val focusLoadedGrid = !isEmpty && (
                    gallery.pendingMutation == GalleryMutation.CreateAlbum ||
                        (wasEmpty && !gallery.browserBackFocused && gallery.browserAction == null)
                    )
                Reduction(
                    state = state.copy(
                        gallery = gallery.copy(
                            items = command.items,
                            selectedIndex = nextIndex,
                            browserBackFocused = when {
                                isEmpty -> true
                                focusLoadedGrid -> false
                                else -> gallery.browserBackFocused
                            },
                            browserAction = when {
                                isEmpty -> GalleryBrowserAction.Back
                                focusLoadedGrid -> null
                                else -> gallery.browserAction
                            },
                        ),
                    ),
                )
            }
            is GalleryCommand.LoadMoveTargets -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        moveTargets = command.items.filter { it.isFolder && it.path != gallery.currentFolder },
                        moveTargetIndex = 0,
                    ),
                ),
            )
            is GalleryCommand.SetExifLines -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        previewExifLines = command.lines,
                        detailsLineIndex = gallery.detailsLineIndex.coerceIn(
                            0,
                            command.lines.lastIndex.coerceAtLeast(0),
                        ),
                    ),
                ),
            )
            is GalleryCommand.OperationSucceeded -> {
                val deletedPreviewHasNext = gallery.pendingMutation == GalleryMutation.Delete &&
                    gallery.confirmationReturnToPreview && gallery.items.isNotEmpty()
                val returnMode = when (gallery.pendingMutation) {
                    GalleryMutation.Rename -> GalleryViewMode.Preview
                    GalleryMutation.Delete -> if (deletedPreviewHasNext) {
                        GalleryViewMode.Preview
                    } else {
                        GalleryViewMode.Browser
                    }
                    GalleryMutation.Move, GalleryMutation.CreateAlbum -> GalleryViewMode.Browser
                    null -> gallery.viewMode
                }
                Reduction(
                    state = state.copy(
                        gallery = gallery.copy(
                            viewMode = returnMode,
                            pendingMutation = null,
                            operationMessage = command.message,
                            confirmButtonIndex = 1,
                            previewAction = if (deletedPreviewHasNext) {
                                GalleryPreviewAction.Delete
                            } else {
                                gallery.previewAction
                            },
                            previewExifLines = if (deletedPreviewHasNext) emptyList() else gallery.previewExifLines,
                            detailsVisible = if (deletedPreviewHasNext) false else gallery.detailsVisible,
                            videoPlaying = if (deletedPreviewHasNext) false else gallery.videoPlaying,
                            confirmationReturnToPreview = false,
                            confirmationReturnToMediaActions = false,
                            folderDeleteReturnToActions = false,
                            moveTargets = emptyList(),
                            renameDraft = "",
                        ),
                    ),
                )
            }
            is GalleryCommand.OperationFailed -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        pendingMutation = null,
                        operationMessage = command.message,
                    ),
                ),
            )
        }
    }

    private fun navigateGalleryGrid(
        state: AppState,
        gallery: GalleryState,
        rowDelta: Int = 0,
        columnDelta: Int = 0,
    ): Reduction = when (gallery.viewMode) {
        GalleryViewMode.Browser -> {
            val showingAlbums = gallery.currentFolder == null
            val columns = if (showingAlbums) ALBUM_GRID_COLUMNS else MEDIA_GRID_COLUMNS
            val activeAction = gallery.browserAction
                ?: GalleryBrowserAction.Back.takeIf { gallery.browserBackFocused }
            when {
                activeAction != null -> {
                    if (gallery.items.isEmpty()) {
                        val nextAction = if (
                            showingAlbums &&
                            activeAction == GalleryBrowserAction.Back &&
                            columnDelta > 0
                        ) {
                            GalleryBrowserAction.CreateAlbum
                        } else {
                            GalleryBrowserAction.Back
                        }
                        Reduction(
                            state = state.copy(
                                gallery = gallery.copy(
                                    selectedIndex = 0,
                                    browserBackFocused = nextAction == GalleryBrowserAction.Back,
                                    browserAction = nextAction,
                                ),
                            ),
                        )
                    } else if (rowDelta != 0) {
                        val nextIndex = if (rowDelta < 0) {
                            0
                        } else {
                            gallery.items.lastIndex.coerceAtLeast(0)
                        }
                        Reduction(
                            state = state.copy(
                                gallery = gallery.copy(
                                    selectedIndex = nextIndex,
                                    browserBackFocused = false,
                                    browserAction = null,
                                ),
                            ),
                        )
                    } else {
                        when {
                            activeAction == GalleryBrowserAction.Back &&
                                columnDelta < 0 &&
                                gallery.items.isNotEmpty() -> returnToAnchoredGridRow(
                                state = state,
                                gallery = gallery,
                                columns = columns,
                                selectRightEdge = false,
                            )
                            activeAction == GalleryBrowserAction.Back &&
                                columnDelta > 0 &&
                                showingAlbums -> Reduction(
                                state = state.copy(
                                    gallery = gallery.copy(
                                        browserBackFocused = false,
                                        browserAction = GalleryBrowserAction.CreateAlbum,
                                    ),
                                ),
                            )
                            columnDelta > 0 && gallery.items.isNotEmpty() -> returnToAnchoredGridRow(
                                state = state,
                                gallery = gallery,
                                columns = columns,
                                selectRightEdge = true,
                            )
                            else -> {
                                val actions = galleryBrowserActions(showingAlbums)
                                val currentIndex = actions.indexOf(activeAction).coerceAtLeast(0)
                                val nextAction = actions[(currentIndex + columnDelta).coerceIn(0, actions.lastIndex)]
                                Reduction(
                                    state = state.copy(
                                        gallery = gallery.copy(
                                            browserBackFocused = nextAction == GalleryBrowserAction.Back,
                                            browserAction = nextAction,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
                navigationLeavesGalleryGrid(
                    current = gallery.selectedIndex,
                    count = gallery.items.size,
                    columns = columns,
                    rowDelta = rowDelta,
                    columnDelta = columnDelta,
                ) -> {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                browserBackFocused = true,
                                browserAction = GalleryBrowserAction.Back,
                            ),
                        ),
                    )
                }
                else -> {
                    val next = moveGalleryGridSelection(
                        current = gallery.selectedIndex,
                        count = gallery.items.size,
                        columns = columns,
                        rowDelta = rowDelta,
                        columnDelta = columnDelta,
                    )
                    Reduction(state = state.copy(gallery = gallery.copy(selectedIndex = next)))
                }
            }
        }
        GalleryViewMode.AlbumActions -> {
            val actions = GalleryAlbumAction.entries
            val delta = if (columnDelta != 0) columnDelta else rowDelta
            val currentIndex = actions.indexOf(gallery.albumAction).coerceAtLeast(0)
            val nextAction = actions[(currentIndex + delta).coerceIn(0, actions.lastIndex)]
            Reduction(state = state.copy(gallery = gallery.copy(albumAction = nextAction)))
        }
        GalleryViewMode.MediaActions -> {
            val actions = GalleryMediaAction.entries
            val delta = if (columnDelta != 0) columnDelta else rowDelta
            val currentIndex = actions.indexOf(gallery.mediaAction).coerceAtLeast(0)
            val nextAction = actions[(currentIndex + delta).coerceIn(0, actions.lastIndex)]
            Reduction(state = state.copy(gallery = gallery.copy(mediaAction = nextAction)))
        }
        GalleryViewMode.Preview -> {
            if (gallery.detailsVisible && rowDelta != 0) {
                val nextLine = (gallery.detailsLineIndex + rowDelta).coerceIn(
                    0,
                    gallery.previewExifLines.lastIndex.coerceAtLeast(0),
                )
                Reduction(
                    state = state.copy(
                        gallery = gallery.copy(detailsLineIndex = nextLine),
                    ),
                )
            } else {
                val nextAction = if (columnDelta != 0) {
                    shiftPreviewAction(gallery, columnDelta)
                } else {
                    gallery.previewAction
                }
                Reduction(state = state.copy(gallery = gallery.copy(previewAction = nextAction)))
            }
        }
        GalleryViewMode.Options -> {
            val delta = if (rowDelta != 0) rowDelta else columnDelta
            Reduction(
                state = state.copy(
                    gallery = gallery.copy(optionIndex = (gallery.optionIndex + delta).coerceIn(0, 2)),
                ),
            )
        }
        GalleryViewMode.Move -> {
            val next = moveGalleryGridSelection(
                current = gallery.moveTargetIndex,
                count = gallery.moveTargets.size,
                columns = ALBUM_GRID_COLUMNS,
                rowDelta = rowDelta,
                columnDelta = columnDelta,
            )
            Reduction(state = state.copy(gallery = gallery.copy(moveTargetIndex = next)))
        }
        GalleryViewMode.ConfirmDelete,
        GalleryViewMode.ConfirmFolderDelete,
        GalleryViewMode.CreateFolder,
        -> Reduction(
            state = state.copy(
                gallery = gallery.copy(confirmButtonIndex = if (gallery.confirmButtonIndex == 0) 1 else 0),
            ),
        )
        GalleryViewMode.Rename -> Reduction(state = state)
    }

    private fun returnToAnchoredGridRow(
        state: AppState,
        gallery: GalleryState,
        columns: Int,
        selectRightEdge: Boolean,
    ): Reduction {
        val safeIndex = gallery.selectedIndex.coerceIn(0, gallery.items.lastIndex)
        val rowStart = (safeIndex / columns) * columns
        val rowEnd = minOf(rowStart + columns - 1, gallery.items.lastIndex)
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    selectedIndex = if (selectRightEdge) rowEnd else rowStart,
                    browserBackFocused = false,
                    browserAction = null,
                ),
            ),
        )
    }

    private fun confirmGallerySelection(state: AppState, gallery: GalleryState): Reduction {
        return when (gallery.viewMode) {
            GalleryViewMode.Browser -> {
                val action = gallery.browserAction
                    ?: GalleryBrowserAction.Back.takeIf { gallery.browserBackFocused }
                if (action != null) {
                    activateBrowserAction(state, gallery, action)
                } else {
                    openGalleryItem(state, gallery, gallery.selectedIndex)
                }
            }
            GalleryViewMode.AlbumActions -> activateAlbumAction(
                state,
                gallery,
                gallery.albumAction,
            )
            GalleryViewMode.MediaActions -> activateMediaAction(
                state,
                gallery,
                gallery.mediaAction,
            )
            GalleryViewMode.Preview -> activatePreviewAction(state, gallery, gallery.previewAction)
            GalleryViewMode.Options -> activateGalleryOption(state, gallery, gallery.optionIndex)
            GalleryViewMode.Move -> {
                if (gallery.pendingMutation != null) return Reduction(state = state)
                val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                val target = gallery.moveTargets.getOrNull(gallery.moveTargetIndex) ?: return Reduction(state = state)
                Reduction(
                    state = state.copy(
                        gallery = gallery.copy(
                            pendingMutation = GalleryMutation.Move,
                            operationMessage = "Moving ${item.name}…",
                        ),
                    ),
                    effects = listOf(PlatformEffect.MoveGalleryItem(item, target)),
                )
            }
            GalleryViewMode.Rename -> {
                if (gallery.pendingMutation != null) return Reduction(state = state)
                val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                val newName = gallery.renameDraft.trim()
                if (newName.isBlank() || newName == item.name) {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                viewMode = GalleryViewMode.Preview,
                                renameDraft = "",
                                operationMessage = null,
                            ),
                        ),
                    )
                } else {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                pendingMutation = GalleryMutation.Rename,
                                operationMessage = "Renaming ${item.name}…",
                            ),
                        ),
                        effects = listOf(PlatformEffect.RenameGalleryItem(item, newName)),
                    )
                }
            }
            GalleryViewMode.ConfirmDelete -> {
                if (gallery.confirmButtonIndex != 0) {
                    val returnMode = when {
                        gallery.confirmationReturnToPreview -> GalleryViewMode.Preview
                        gallery.confirmationReturnToMediaActions -> GalleryViewMode.MediaActions
                        else -> GalleryViewMode.Browser
                    }
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                viewMode = returnMode,
                                confirmButtonIndex = 1,
                                confirmationReturnToPreview = false,
                                confirmationReturnToMediaActions = false,
                            ),
                        ),
                    )
                } else {
                    if (gallery.pendingMutation != null) return Reduction(state = state)
                    val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                pendingMutation = GalleryMutation.Delete,
                                operationMessage = "Deleting ${item.name}…",
                            ),
                        ),
                        effects = listOf(PlatformEffect.DeleteGalleryItem(item)),
                    )
                }
            }
            GalleryViewMode.ConfirmFolderDelete -> {
                if (gallery.confirmButtonIndex != 0) {
                    val returnMode = if (gallery.folderDeleteReturnToActions) {
                        GalleryViewMode.AlbumActions
                    } else {
                        GalleryViewMode.Browser
                    }
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                viewMode = returnMode,
                                confirmButtonIndex = 1,
                                folderDeleteReturnToActions = false,
                                operationMessage = null,
                            ),
                        ),
                    )
                } else {
                    if (gallery.pendingMutation != null) return Reduction(state = state)
                    val album = gallery.items.getOrNull(gallery.selectedIndex)
                        ?.takeIf { it.isFolder }
                        ?: return Reduction(state = state)
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                pendingMutation = GalleryMutation.Delete,
                                operationMessage = "Deleting ${album.name} and its ${album.mediaCount} items…",
                            ),
                        ),
                        effects = listOf(PlatformEffect.DeleteGalleryAlbum(album)),
                    )
                }
            }
            GalleryViewMode.CreateFolder -> {
                if (gallery.confirmButtonIndex != 0) {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                viewMode = GalleryViewMode.Browser,
                                folderName = "",
                                confirmButtonIndex = 1,
                                operationMessage = null,
                            ),
                        ),
                    )
                } else {
                    val name = gallery.folderName.trim()
                    if (name.isBlank()) {
                        Reduction(
                            state = state.copy(
                                gallery = gallery.copy(operationMessage = "Enter an album name."),
                            ),
                        )
                    } else {
                        Reduction(
                            state = state.copy(
                                gallery = gallery.copy(
                                    viewMode = GalleryViewMode.Browser,
                                    folderName = "",
                                    confirmButtonIndex = 1,
                                    pendingMutation = GalleryMutation.CreateAlbum,
                                    selectedIndex = 0,
                                    operationMessage = "Creating $name…",
                                ),
                            ),
                            effects = listOf(PlatformEffect.CreateGalleryFolder(name)),
                        )
                    }
                }
            }
        }
    }

    private fun backFromGallery(state: AppState, gallery: GalleryState): Reduction =
        when (gallery.viewMode) {
            GalleryViewMode.AlbumActions -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Browser,
                        albumAction = GalleryAlbumAction.Preview,
                        browserBackFocused = true,
                        browserAction = GalleryBrowserAction.Back,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.MediaActions -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Browser,
                        mediaAction = GalleryMediaAction.Preview,
                        browserBackFocused = true,
                        browserAction = GalleryBrowserAction.Back,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.Preview -> {
                if (gallery.detailsVisible) {
                    Reduction(state = state.copy(gallery = gallery.copy(detailsVisible = false)))
                } else {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                viewMode = GalleryViewMode.Browser,
                                previewExifLines = emptyList(),
                                detailsVisible = false,
                                detailsLineIndex = 0,
                                videoPlaying = false,
                                browserBackFocused = true,
                                browserAction = GalleryBrowserAction.Back,
                            ),
                        ),
                    )
                }
            }
            GalleryViewMode.Options, GalleryViewMode.Move, GalleryViewMode.Rename -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Preview,
                        moveTargets = emptyList(),
                        renameDraft = "",
                        pendingMutation = null,
                        previewAction = GalleryPreviewAction.Back,
                        browserBackFocused = false,
                        browserAction = null,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.ConfirmDelete -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = when {
                            gallery.confirmationReturnToPreview -> GalleryViewMode.Preview
                            gallery.confirmationReturnToMediaActions -> GalleryViewMode.MediaActions
                            else -> GalleryViewMode.Browser
                        },
                        previewAction = if (gallery.confirmationReturnToPreview) {
                            GalleryPreviewAction.Back
                        } else {
                            gallery.previewAction
                        },
                        mediaAction = if (gallery.confirmationReturnToMediaActions) {
                            GalleryMediaAction.Back
                        } else {
                            gallery.mediaAction
                        },
                        browserBackFocused = !gallery.confirmationReturnToPreview &&
                            !gallery.confirmationReturnToMediaActions,
                        browserAction = GalleryBrowserAction.Back.takeIf {
                            !gallery.confirmationReturnToPreview && !gallery.confirmationReturnToMediaActions
                        },
                        confirmationReturnToPreview = false,
                        confirmationReturnToMediaActions = false,
                        pendingMutation = null,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.ConfirmFolderDelete -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = if (gallery.folderDeleteReturnToActions) {
                            GalleryViewMode.AlbumActions
                        } else {
                            GalleryViewMode.Browser
                        },
                        albumAction = if (gallery.folderDeleteReturnToActions) {
                            GalleryAlbumAction.Back
                        } else {
                            gallery.albumAction
                        },
                        browserBackFocused = !gallery.folderDeleteReturnToActions,
                        browserAction = GalleryBrowserAction.Back.takeIf {
                            !gallery.folderDeleteReturnToActions
                        },
                        folderDeleteReturnToActions = false,
                        confirmButtonIndex = 1,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.CreateFolder -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Browser,
                        folderName = "",
                        confirmButtonIndex = 1,
                        browserBackFocused = true,
                        browserAction = GalleryBrowserAction.Back,
                        operationMessage = null,
                    ),
                ),
            )
            GalleryViewMode.Browser -> {
                if (gallery.currentFolder != null) {
                    Reduction(
                        state = state.copy(
                            gallery = gallery.copy(
                                currentFolder = null,
                                currentFolderName = null,
                                selectedIndex = 0,
                                items = emptyList(),
                                browserBackFocused = true,
                                browserAction = GalleryBrowserAction.Back,
                                operationMessage = null,
                            ),
                        ),
                        effects = listOf(PlatformEffect.LoadGalleryItems),
                    )
                } else {
                    Reduction(state = state.copy(mode = AppMode.CameraLive))
                }
            }
        }

    private fun activateBrowserAction(
        state: AppState,
        gallery: GalleryState,
        action: GalleryBrowserAction,
    ): Reduction = when (action) {
        GalleryBrowserAction.Back -> backFromGallery(state, gallery)
        GalleryBrowserAction.CreateAlbum -> beginCreateAlbum(state, gallery)
    }

    private fun activateAlbumAction(
        state: AppState,
        gallery: GalleryState,
        action: GalleryAlbumAction,
    ): Reduction = when (action) {
        GalleryAlbumAction.Preview -> previewGalleryAlbum(state, gallery)
        GalleryAlbumAction.Delete -> beginDeleteAlbum(state, gallery)
        GalleryAlbumAction.Back -> backFromGallery(state, gallery)
    }

    private fun activateMediaAction(
        state: AppState,
        gallery: GalleryState,
        action: GalleryMediaAction,
    ): Reduction = when (action) {
        GalleryMediaAction.Back -> backFromGallery(state, gallery)
        GalleryMediaAction.Preview -> previewGalleryMedia(state, gallery)
        GalleryMediaAction.Delete -> initiateGalleryDelete(state, gallery)
    }

    private fun beginDeleteAlbum(state: AppState, gallery: GalleryState): Reduction {
        if (
            gallery.viewMode !in setOf(GalleryViewMode.Browser, GalleryViewMode.AlbumActions) ||
            gallery.currentFolder != null
        ) {
            return Reduction(state = state)
        }
        gallery.items.getOrNull(gallery.selectedIndex)
            ?.takeIf { it.isFolder }
            ?: return Reduction(state = state)
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    viewMode = GalleryViewMode.ConfirmFolderDelete,
                    browserBackFocused = false,
                    browserAction = null,
                    confirmButtonIndex = 1,
                    folderDeleteReturnToActions = gallery.viewMode == GalleryViewMode.AlbumActions,
                    operationMessage = null,
                ),
            ),
        )
    }

    private fun beginCreateAlbum(state: AppState, gallery: GalleryState): Reduction {
        if (gallery.viewMode != GalleryViewMode.Browser || gallery.currentFolder != null) {
            return Reduction(state = state)
        }
        val sequence = gallery.items.count { it.name.startsWith("Dive Album") } + 1
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    viewMode = GalleryViewMode.CreateFolder,
                    browserBackFocused = false,
                    browserAction = null,
                    folderName = "Dive Album $sequence",
                    confirmButtonIndex = 1,
                    operationMessage = null,
                ),
            ),
        )
    }

    private fun initiateGalleryDelete(state: AppState, gallery: GalleryState): Reduction {
        if (
            gallery.viewMode !in setOf(
                GalleryViewMode.Browser,
                GalleryViewMode.MediaActions,
                GalleryViewMode.Preview,
            )
        ) {
            return Reduction(state = state)
        }
        val item = gallery.items.getOrNull(gallery.selectedIndex)
        if (item == null || item.isFolder) return Reduction(state = state)
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    viewMode = GalleryViewMode.ConfirmDelete,
                    browserBackFocused = false,
                    browserAction = null,
                    confirmButtonIndex = 1,
                    confirmationReturnToPreview = gallery.viewMode == GalleryViewMode.Preview,
                    confirmationReturnToMediaActions = gallery.viewMode == GalleryViewMode.MediaActions,
                    operationMessage = null,
                ),
            ),
        )
    }

    private fun openGalleryItem(state: AppState, gallery: GalleryState, index: Int): Reduction {
        if (gallery.viewMode != GalleryViewMode.Browser) return Reduction(state = state)
        val item = gallery.items.getOrNull(index) ?: return Reduction(state = state)
        return if (item.isFolder) {
            Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.AlbumActions,
                        selectedIndex = index,
                        albumAction = GalleryAlbumAction.Preview,
                        browserBackFocused = false,
                        browserAction = null,
                        operationMessage = null,
                    ),
                ),
            )
        } else {
            Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.MediaActions,
                        selectedIndex = index,
                        mediaAction = GalleryMediaAction.Preview,
                        browserBackFocused = false,
                        browserAction = null,
                        operationMessage = null,
                    ),
                ),
            )
        }
    }

    private fun previewGalleryMedia(state: AppState, gallery: GalleryState): Reduction {
        if (gallery.viewMode != GalleryViewMode.MediaActions || gallery.currentFolder == null) {
            return Reduction(state = state)
        }
        val item = gallery.items.getOrNull(gallery.selectedIndex)
            ?.takeUnless { it.isFolder }
            ?: return Reduction(state = state)
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    viewMode = GalleryViewMode.Preview,
                    mediaAction = GalleryMediaAction.Preview,
                    previewAction = if (item.isVideo) {
                        GalleryPreviewAction.PlayPause
                    } else {
                        GalleryPreviewAction.Next
                    },
                    previewExifLines = emptyList(),
                    detailsVisible = false,
                    detailsLineIndex = 0,
                    videoPlaying = false,
                    browserBackFocused = false,
                    browserAction = null,
                    operationMessage = null,
                ),
            ),
        )
    }

    private fun previewGalleryAlbum(state: AppState, gallery: GalleryState): Reduction {
        if (gallery.viewMode != GalleryViewMode.AlbumActions || gallery.currentFolder != null) {
            return Reduction(state = state)
        }
        val album = gallery.items.getOrNull(gallery.selectedIndex)
            ?.takeIf { it.isFolder }
            ?: return Reduction(state = state)
        return Reduction(
            state = state.copy(
                gallery = gallery.copy(
                    viewMode = GalleryViewMode.Browser,
                    currentFolder = album.path,
                    currentFolderName = album.name,
                    selectedIndex = 0,
                    items = emptyList(),
                    albumAction = GalleryAlbumAction.Preview,
                    browserBackFocused = false,
                    browserAction = null,
                    operationMessage = null,
                ),
            ),
            effects = listOf(PlatformEffect.LoadGalleryItems),
        )
    }

    private fun activatePreviewAction(
        state: AppState,
        gallery: GalleryState,
        action: GalleryPreviewAction,
    ): Reduction {
        if (gallery.pendingMutation != null) return Reduction(state = state)
        val selectedGallery = gallery.copy(previewAction = action, operationMessage = null)
        if (action == GalleryPreviewAction.Back) {
            // An explicit Back action always leaves preview, even when Details is open. The
            // physical Back/Zoom-Out command still closes Details first for its legacy behavior.
            return backFromGallery(state, selectedGallery.copy(detailsVisible = false))
        }
        val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
        return when (action) {
            GalleryPreviewAction.Back -> error("Handled above")
            GalleryPreviewAction.Delete -> Reduction(
                state = state.copy(
                    gallery = selectedGallery.copy(
                        viewMode = GalleryViewMode.ConfirmDelete,
                        confirmButtonIndex = 1,
                        confirmationReturnToPreview = true,
                    ),
                ),
            )
            GalleryPreviewAction.Options -> Reduction(
                state = state.copy(gallery = selectedGallery.copy(viewMode = GalleryViewMode.Options, optionIndex = 0)),
            )
            GalleryPreviewAction.Previous, GalleryPreviewAction.Next -> {
                val delta = if (action == GalleryPreviewAction.Previous) -1 else 1
                val itemCount = gallery.items.size
                val nextIndex = ((gallery.selectedIndex + delta) % itemCount + itemCount) % itemCount
                Reduction(
                    state = state.copy(
                        gallery = selectedGallery.copy(
                            selectedIndex = nextIndex,
                            previewExifLines = emptyList(),
                            detailsVisible = false,
                            detailsLineIndex = 0,
                            videoPlaying = false,
                        ),
                    ),
                )
            }
            GalleryPreviewAction.PlayPause -> Reduction(
                state = state.copy(
                    gallery = selectedGallery.copy(
                        videoPlaying = if (item.isVideo) !gallery.videoPlaying else false,
                    ),
                ),
            )
            GalleryPreviewAction.Details -> {
                val show = !gallery.detailsVisible
                Reduction(
                    state = state.copy(
                        gallery = selectedGallery.copy(
                            detailsVisible = show,
                            detailsLineIndex = if (show) 0 else gallery.detailsLineIndex,
                        ),
                    ),
                    effects = if (show && gallery.previewExifLines.isEmpty()) {
                        listOf(PlatformEffect.LoadExifData(item))
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    private fun activateGalleryOption(state: AppState, gallery: GalleryState, index: Int): Reduction {
        val item = gallery.items.getOrNull(gallery.selectedIndex) ?: return Reduction(state = state)
        return when (index) {
            0 -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Move,
                        optionIndex = 0,
                        moveTargets = emptyList(),
                        moveTargetIndex = 0,
                        operationMessage = null,
                    ),
                ),
                effects = listOf(PlatformEffect.LoadGalleryMoveTargets),
            )
            1 -> Reduction(
                state = state.copy(
                    gallery = gallery.copy(
                        viewMode = GalleryViewMode.Rename,
                        optionIndex = 1,
                        renameDraft = item.name,
                        operationMessage = null,
                    ),
                ),
            )
            else -> Reduction(
                state = state.copy(gallery = gallery.copy(viewMode = GalleryViewMode.Preview, optionIndex = 0)),
            )
        }
    }

    private fun moveGalleryGridSelection(
        current: Int,
        count: Int,
        columns: Int,
        rowDelta: Int = 0,
        columnDelta: Int = 0,
    ): Int {
        if (count <= 0) return 0
        val safeCurrent = current.coerceIn(0, count - 1)
        val row = safeCurrent / columns
        val column = safeCurrent % columns
        val targetRow = (row + rowDelta).coerceIn(0, (count - 1) / columns)
        val targetRowLastColumn = minOf(columns - 1, count - 1 - targetRow * columns)
        val targetColumn = (column + columnDelta).coerceIn(0, targetRowLastColumn)
        return targetRow * columns + targetColumn
    }

    private fun navigationLeavesGalleryGrid(
        current: Int,
        count: Int,
        columns: Int,
        rowDelta: Int,
        columnDelta: Int,
    ): Boolean {
        if (rowDelta == 0 && columnDelta == 0) return false
        if (count <= 0) return true
        val safeCurrent = current.coerceIn(0, count - 1)
        return when {
            rowDelta < 0 -> safeCurrent < columns
            rowDelta > 0 -> safeCurrent + columns >= count
            columnDelta < 0 -> safeCurrent % columns == 0
            columnDelta > 0 -> safeCurrent % columns == columns - 1 || safeCurrent == count - 1
            else -> false
        }
    }

    private fun shiftPreviewAction(gallery: GalleryState, delta: Int): GalleryPreviewAction {
        val isVideo = gallery.items.getOrNull(gallery.selectedIndex)?.isVideo == true
        val actions = galleryPreviewRailActions(isVideo)
        val currentIndex = actions.indexOf(gallery.previewAction)
        val safeIndex = if (currentIndex >= 0) currentIndex else actions.indexOf(GalleryPreviewAction.Next)
        return actions[(safeIndex + delta).coerceIn(0, actions.lastIndex)]
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
