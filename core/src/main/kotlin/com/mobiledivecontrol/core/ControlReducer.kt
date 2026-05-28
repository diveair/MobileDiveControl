package com.mobiledivecontrol.core

import java.time.Duration

class ControlReducer(
    private val safetyStateMachine: SafetyStateMachine = SafetyStateMachine(),
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
        is HousingCommand -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(command)),
        )
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
        CameraCommand.ToggleVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(recording = !state.camera.recording),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.StartVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(recording = true),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.StopVideoRecording -> Reduction(
            state = state.copy(
                camera = state.camera.copy(recording = false),
            ),
            effects = listOf(PlatformEffect.ExecuteCamera(command)),
        )
        CameraCommand.NavigateUp -> navigateCameraUp(state, repeatCount)
        CameraCommand.NavigateDown -> navigateCameraDown(state, repeatCount)
        CameraCommand.NavigateLeft -> navigateCameraLeft(state, repeatCount)
        CameraCommand.NavigateRight -> navigateCameraRight(state, repeatCount)
        CameraCommand.Confirm -> confirmCameraSelection(state)
        CameraCommand.Back -> backOutCameraUi(state)
        CameraCommand.ZoomIn -> reduceCamera(state, CameraCommand.SetZoom(state.camera.zoomFactor + 0.1))
        CameraCommand.ZoomOut -> reduceCamera(state, CameraCommand.SetZoom(state.camera.zoomFactor - 0.1))
        is CameraCommand.SetZoom -> {
            val zoom = command.value.coerceIn(1.0, 8.0)
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
        SafetyCommand.StartVacuumMotor -> Reduction(
            state = state,
            effects = listOf(PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = true))),
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

    private fun navigateCameraUp(state: AppState, repeatCount: Int = 0): Reduction {
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
        val items = CameraCatalog.settingsBarItems(
            state.camera.activeMode,
            state.camera.deviceVariant,
            state.camera.showMoreSettings
        )
        val totalItems = items.size
        if (totalItems <= 1) {
            return Reduction(state = state)
        }

        val nextIndex = (state.camera.settingsCursor + delta + totalItems) % totalItems
        val movedState = state.copy(
            camera = state.camera.copy(
                settingsCursor = nextIndex,
                settingsEditing = false,
                sliderEditTarget = SliderEditTarget.Value,
            ),
        )
        val highlightedItem = items.getOrNull(nextIndex)
        val preparation = if (highlightedItem is BottomBarItem.Setting &&
            highlightedItem.spec.id.endsWith(".manual_focus")) {
            prepareStateForManualFocus(movedState, highlightedItem.spec)
        } else {
            ManualFocusPreparation(movedState)
        }
        return Reduction(
            state = preparation.state,
            effects = preparation.effects,
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
        val items = CameraCatalog.settingsBarItems(
            state.camera.activeMode,
            state.camera.deviceVariant,
            state.camera.showMoreSettings
        )
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
                    state.camera.activeMode,
                    state.camera.deviceVariant,
                    nextShowMore
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
        val items = CameraCatalog.settingsBarItems(
            camera.activeMode,
            camera.deviceVariant,
            camera.showMoreSettings,
        )
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
                wrap = false,
            )
            Reduction(
                state = preparedState.copy(
                    camera = applySettingValue(preparedCamera, assistSpec.id, nextValue),
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
                if (repeatCount > 0 && !shouldApplyFocusRepeat(currentSensitivity, repeatCount)) {
                    return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
                }
                val currentValue = preparedCamera.settingValues[spec.id] ?: spec.defaultValue
                val currentIndex = spec.options.indexOf(currentValue).coerceAtLeast(0)
                // During hold (repeatCount > 0), don't cross into AF (index 0).
                // AF is only reachable on a fresh press (repeatCount == 0).
                val minIndex = if (repeatCount > 0 && currentIndex > 0) 1 else 0
                val rawNext = currentIndex + step
                val clampedIndex = rawNext.coerceIn(minIndex, spec.options.lastIndex)
                val nextValue = spec.options[clampedIndex]
                val nextCamera = applySettingValue(preparedCamera, spec.id, nextValue)
                val effect = cameraEffectForSetting(spec.id, nextValue)
                Reduction(
                    state = preparedState.copy(camera = nextCamera),
                    effects = manualFocusPreparation.effects +
                        (effect?.let { listOf(PlatformEffect.ExecuteCamera(it)) } ?: emptyList()),
                )
            } else {
                // Other sliders: sensitivity controls rate-limiting during holds
                if (repeatCount > 0) {
                    val skipInterval = ((100 - currentSensitivity.level) / 10).coerceAtLeast(1)
                    val shouldApply = repeatCount % skipInterval == 0
                    if (!shouldApply) {
                        return Reduction(state = preparedState, effects = manualFocusPreparation.effects)
                    }
                }

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
    ): ManualFocusPreparation {
        if (supportsManualFocusForSelectedLens(state.camera, focusSpec)) {
            return ManualFocusPreparation(state)
        }
        val lensSettingId = focusSpec.id.substringBeforeLast(".") + ".lens"
        val fallbackLens = fallbackManualFocusLens(state.camera, focusSpec)
            ?: return ManualFocusPreparation(state)
        val currentLens = state.camera.settingValues[lensSettingId]
        if (fallbackLens == currentLens) {
            return ManualFocusPreparation(state)
        }
        val nextCamera = applySettingValue(state.camera, lensSettingId, fallbackLens)
        return ManualFocusPreparation(
            state = state.copy(camera = nextCamera),
            effects = listOf(PlatformEffect.ExecuteCamera(CameraCommand.SwitchLens(fallbackLens))),
        )
    }

    private fun applySettingValue(camera: CameraState, settingId: String, value: String): CameraState {
        val updatedValues = if (settingId.endsWith(".lens") && value == "0.6x") {
            val baseId = settingId.removeSuffix(".lens")
            camera.settingValues +
                    (settingId to value) +
                    ("$baseId.manual_focus" to "AF") +
                    ("$baseId.focus_peaking" to "Off")
        } else {
            camera.settingValues + (settingId to value)
        }
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
        return targets
    }

    private fun focusAssistSpec(camera: CameraState, focusSpec: CameraSettingSpec): CameraSettingSpec? {
        val assistSettingId = CameraCatalog.focusAssistSettingId(focusSpec.id) ?: return null
        return CameraCatalog.settingsFor(camera.activeMode, camera.deviceVariant)
            .firstOrNull { it.id == assistSettingId }
    }

    private fun supportsManualFocusForSelectedLens(camera: CameraState, focusSpec: CameraSettingSpec): Boolean {
        val lensSettingId = focusSpec.id.substringBeforeLast(".") + ".lens"
        val lensValue = camera.settingValues[lensSettingId]
        return lensValue != null && lensValue != "0.6x" && lensValue != "front"
    }

    private fun fallbackManualFocusLens(camera: CameraState, focusSpec: CameraSettingSpec): String? {
        val lensSettingId = focusSpec.id.substringBeforeLast(".") + ".lens"
        val lensSpec = CameraCatalog.settingsFor(camera.activeMode, camera.deviceVariant)
            .firstOrNull { it.id == lensSettingId }
            ?: return null
        val candidates = lensSpec.options.filter { it != "0.6x" && it != "front" }
        return listOf("1x", "2x", "3x", "5x").firstOrNull { it in candidates } ?: candidates.firstOrNull()
    }

    private fun shouldApplyFocusRepeat(
        sensitivity: SliderSensitivity,
        repeatCount: Int,
    ): Boolean {
        if (repeatCount <= 0) {
            return true
        }
        val repeatStride = 1 + ((SliderSensitivity.MAX.level - sensitivity.level) / 5)
        return repeatCount % repeatStride == 0
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
        value.replace("+", "").toDoubleOrNull()

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
        val nextState = state.copy(
            safety = result.state,
            lastWarning = result.note ?: result.state.warning ?: state.lastWarning,
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

