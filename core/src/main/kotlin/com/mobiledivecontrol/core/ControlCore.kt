package com.mobiledivecontrol.core

import java.time.Clock
import java.time.Instant

class ControlCore(
    initialState: AppState = AppState(),
    private val clock: Clock = Clock.systemUTC(),
    private val protocolParser: ProtocolParser = ProtocolParser(),
    private val normalizer: ButtonEventNormalizer = ButtonEventNormalizer(),
    private val inputRouter: InputRouter = InputRouter(),
    private val reducer: ControlReducer = ControlReducer(),
    private val bleConnectionMachine: BleConnectionMachine = BleConnectionMachine(),
    private val diagnostics: DiagnosticsStore = DiagnosticsStore(),
    private val monotonicMs: () -> Long = ::pressureMonotonicMs,
    val triggerSafetyStopAtSurface: Boolean = false,
) {
    var state: AppState = if (triggerSafetyStopAtSurface) initialState else initialState.copy(
        diveProfile = DiveProfileTracker.forLiveDiving(initialState.diveProfile))
        private set

    private var reconnectAttempt: Int = 0

    fun handleButtonPayload(
        payload: ByteArray,
        characteristic: String = HousingCharacteristic.ButtonEvents.shortHex,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        return handleNotificationPayload(characteristic, payload, receivedAt)
    }

    fun handleNotificationPayload(
        characteristic: String,
        payload: ByteArray,
        receivedAt: Instant = clock.instant(),
        source: SensorPacketSource = SensorPacketSource.Notification,
        receivedAtMonotonicMs: Long = monotonicMs(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state

        diagnostics.recordRawPacket(receivedAt, characteristic, payload)

        return when (val result = protocolParser.decodeNotification(characteristic, payload)) {
            is ParseResult.Failure -> {
                diagnostics.recordError(receivedAt, result.error.code, result.error.message)
                if (HousingCharacteristic.from(characteristic) == HousingCharacteristic.WaterPressure) {
                    state = state.copy(diveProfile = DiveProfileTracker.unavailable(state.diveProfile))
                }
                completeWithoutStateChange(
                    previousState = state,
                    notes = listOf(result.error.message),
                    path = "notification_packet",
                    receivedAt = receivedAt,
                    startedNanos = startedNanos,
                )
            }
            is ParseResult.Success -> when (val notification = result.value) {
                is DecodedNotification.Button -> processDecodedButton(
                    previousState = previousState,
                    decoded = notification.packet,
                    receivedAt = receivedAt,
                    startedNanos = startedNanos,
                )
                is DecodedNotification.Battery -> {
                    val reduction = reducer.updateBatteryLevel(state, notification.percent)
                    commitReduction(
                        previousState = previousState,
                        reduction = reduction,
                        reason = "battery:${notification.percent}",
                        path = "battery_packet",
                        receivedAt = receivedAt,
                        startedNanos = startedNanos,
                    )
                }
                is DecodedNotification.Sensor -> {
                    val sensorReduction = reducer.updateSensor(state, notification.update)
                    val reduction = sensorReduction.copy(state = withPressureTelemetry(
                        sensorReduction.state, notification.update, payload.toSpacedHexString(),
                        receivedAt, receivedAtMonotonicMs, source,
                    ))
                    commitReduction(
                        previousState = previousState,
                        reduction = reduction,
                        reason = "sensor:${notification.update}",
                        path = "sensor_packet",
                        receivedAt = receivedAt,
                        startedNanos = startedNanos,
                    )
                }
                is DecodedNotification.DeviceInfo -> {
                    val reduction = reducer.updateDeviceInfo(state, notification.update)
                    commitReduction(
                        previousState = previousState,
                        reduction = reduction,
                        reason = "deviceInfo:${notification.update}",
                        path = "device_info_packet",
                        receivedAt = receivedAt,
                        startedNanos = startedNanos,
                    )
                }
            }
        }
    }

    private fun processDecodedButton(
        previousState: AppState,
        decoded: DecodedButtonPacket,
        receivedAt: Instant,
        startedNanos: Long,
    ): ProcessingOutcome {
        val accepted = normalizer.accept(decoded.event, receivedAt)
        if (accepted == null) {
            return completeWithoutStateChange(
                previousState = previousState,
                notes = listOf("Duplicate button filtered."),
                path = "button_packet",
                receivedAt = receivedAt,
                startedNanos = startedNanos,
            )
        }

        diagnostics.recordDecodedButton(
            timestamp = receivedAt,
            rawValue = decoded.rawValue,
            event = accepted.event,
            mode = state.mode,
            repeatCount = accepted.repeatCount,
        )

        val baseState = state.copy(
            housing = state.housing.copy(
                lastButton = accepted.event,
                lastRawButton = decoded.rawValue,
            ),
        )

        val decision = inputRouter.route(baseState, accepted.event)
        decision.commands.forEach { diagnostics.recordCommand(receivedAt, it, baseState.mode) }
        val reduction = reducer.applyRouteDecision(baseState, decision, accepted.repeatCount)

        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "button:${accepted.event}",
            path = "button_packet",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun dispatch(
        command: ControlCommand,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        diagnostics.recordCommand(receivedAt, command, state.mode)

        val reduction = reducer.reduce(state, command)
        val exportedFiles = if (command == SystemCommand.ExportDiagnostics) {
            diagnostics.exportBundle(reduction.state)
        } else {
            emptyMap()
        }

        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "command:$command",
            path = "command_dispatch",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
            exportedFiles = exportedFiles,
        )
    }

    /** Restores an engineering-test camera snapshot as one atomic state transition. */
    fun restoreCameraSnapshot(
        camera: CameraState,
        mode: AppMode,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        return commitReduction(
            previousState = previousState,
            reduction = Reduction(state = state.copy(mode = mode, camera = camera)),
            reason = "camera_stress_snapshot_restore",
            path = "camera_stress_restore",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun advanceBle(
        signal: BleSignal,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state

        if (signal == BleSignal.Disconnect) {
            reconnectAttempt += 1
        }

        val transition = bleConnectionMachine.transition(
            current = state.bleConnectionState,
            signal = signal,
            reconnectAttempt = reconnectAttempt,
        )

        if (transition.state == BleConnectionState.Ready || transition.state == BleConnectionState.Idle) {
            reconnectAttempt = 0
        }

        val bleReduction = reducer.updateBleState(
            state = state,
            newState = transition.state,
            reconnectAttempt = reconnectAttempt,
            reconnectDelay = transition.reconnectDelay,
        )

        // A reconnect must receive its own sensor packets; a previous session cannot appear live.
        val reduction = if (!bleReduction.state.housing.connected) {
            bleReduction.copy(state = bleReduction.state.copy(
                waterPressureTelemetry = null,
                barometricPressureTelemetry = null,
                diveProfile = DiveProfileTracker.unavailable(bleReduction.state.diveProfile),
            ))
        } else bleReduction

        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "ble:$signal",
            path = "ble_state",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun updatePermission(
        permission: PermissionKind,
        granted: Boolean,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = reducer.updatePermission(state, permission, granted)
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "permission:$permission=$granted",
            path = "permission_update",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun updateBatteryLevel(
        level: Int,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = reducer.updateBatteryLevel(state, level)
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "battery:$level",
            path = "battery_update",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    /**
     * Phone battery level, 0..100. The housing battery strands the diver, but a dead phone
     * ends the dive just as hard, so both are first-class state.
     */
    /**
     * Primes the safety state with a persisted hard-verified vacuum reading at launch, so the
     * next adoption decision can tell "the same hold, still strong" from "some vacuum".
     */
    fun primeVerifiedVacuum(
        kpa: Double,
        confidence: SealConfidence,
        startedAtEpochMs: Long? = null,
        recordedAtEpochMs: Long? = null,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = reducer.primeVerifiedVacuum(state, kpa, confidence, startedAtEpochMs, recordedAtEpochMs)
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "verifiedVacuum:%.1f".format(kpa),
            path = "verified_vacuum_prime",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun updatePhoneBattery(
        percent: Int,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = reducer.updatePhoneBattery(state, percent)
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "phoneBattery:$percent",
            path = "phone_battery_update",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    /**
     * Live AE/AWB telemetry off the capture pipe (~2 Hz) — same footing as [updatePhoneBattery],
     * but deliberately NOT routed through [commitReduction]: at 2 Hz a transition-ring entry per
     * reading would evict every real state transition inside two minutes, and a latency row per
     * reading is noise about a path with no work in it. The state commit itself is kept, because
     * the reducer's auto-to-manual seeding reads [CameraState.meteredExposure] from THIS state.
     */
    fun updateMeteredExposure(metered: MeteredExposure): ProcessingOutcome {
        state = reducer.updateMeteredExposure(state, metered).state
        return ProcessingOutcome(state = state)
    }

    fun updateSensor(
        sensorUpdate: SensorUpdate,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome = updateSensorReading(sensorUpdate, receivedAt)

    /** Signed depth for the explicitly requested surface motion exercise, never a BLE reading. */
    fun updateSurfaceMotionDepth(depthMeters: Double, receivedAt: Instant = clock.instant()): ProcessingOutcome {
        require(triggerSafetyStopAtSurface && depthMeters.isFinite() && depthMeters in -1.5..3.0)
        return updateSensorReading(SensorUpdate.WaterPressure(STANDARD_SURFACE_PRESSURE_KPA +
            depthMeters * FRESHWATER_KPA_PER_METER), receivedAt, depthMeters)
    }

    private fun updateSensorReading(
        sensorUpdate: SensorUpdate,
        receivedAt: Instant,
        surfaceMotionDepthMeters: Double? = null,
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val sensorReduction = reducer.updateSensor(state, sensorUpdate)
        val reduction = sensorReduction.copy(state = withPressureTelemetry(
            sensorReduction.state, sensorUpdate, null, receivedAt, monotonicMs(),
            SensorPacketSource.Simulation, surfaceMotionDepthMeters,
        ))
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "sensor:$sensorUpdate",
            path = "sensor_update",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    private fun withPressureTelemetry(
        next: AppState,
        update: SensorUpdate,
        rawHex: String?,
        receivedAt: Instant,
        receivedAtMonotonicMs: Long,
        source: SensorPacketSource,
        surfaceMotionDepthMeters: Double? = null,
    ): AppState {
        val previous = when (update) {
            is SensorUpdate.WaterPressure -> next.waterPressureTelemetry
            is SensorUpdate.BarometricPressure -> next.barometricPressureTelemetry
            else -> return next
        }
        val telemetry = PressureTelemetry(
            rawHex = rawHex,
            receivedAtEpochMs = receivedAt.toEpochMilli(),
            receivedAtMonotonicMs = receivedAtMonotonicMs,
            packetCount = (previous?.packetCount ?: 0L) + 1L,
            source = source,
        )
        return when (update) {
            is SensorUpdate.WaterPressure -> {
                val depth = pressureDepthMeters(update.kpa)
                    ?.takeIf { telemetry.isFresh(monotonicMs()) }
                next.copy(
                    waterPressureTelemetry = telemetry,
                    diveProfile = depth?.let {
                        val motionExercise = triggerSafetyStopAtSurface && source == SensorPacketSource.Simulation &&
                            surfaceMotionDepthMeters != null
                        val decompression = if (motionExercise) DecompressionTracker.unavailable(next.diveProfile.decompression)
                        else DecompressionTracker.sample(next.diveProfile.decompression,
                            it, update.kpa / 100.0, receivedAtMonotonicMs, receivedAt.toEpochMilli(),
                            next.diveProfile.active?.settings?.gas ?: next.diveProfile.settings.gas)
                        DiveProfileTracker.sample(next.diveProfile.copy(decompression = decompression),
                            if (motionExercise) surfaceMotionDepthMeters!! else it,
                            receivedAtMonotonicMs, receivedAt.toEpochMilli(),
                            triggerAtSurface = triggerSafetyStopAtSurface, surfaceMotionExercise = motionExercise)
                    } ?: DiveProfileTracker.unavailable(next.diveProfile),
                    maxDepthMeters = depth?.let { maxOf(next.maxDepthMeters ?: 0.0, it) }
                        ?: next.maxDepthMeters,
                )
            }
            is SensorUpdate.BarometricPressure -> next.copy(barometricPressureTelemetry = telemetry)
            else -> next
        }
    }

    /** Expiry only; a wall-clock tick must NEVER earn stop time without a pressure sample. */
    fun tickDiveProfile(nowMs: Long = monotonicMs()): ProcessingOutcome {
        val available = state.housing.connected || state.waterPressureTelemetry?.source == SensorPacketSource.Simulation
        val dive = DiveProfileTracker.tick(state.diveProfile, nowMs, available)
        if (dive != state.diveProfile) state = state.copy(diveProfile = dive)
        return ProcessingOutcome(state)
    }

    /** Used only by the finite, explicit engineering run to retain real logs and exposure history. */
    fun restoreDiveMonitoring(profile: DiveProfileState, maximumDepth: Double?, waterPressure: Double?): ProcessingOutcome {
        state = state.copy(diveProfile = DiveProfileTracker.unavailable(profile), maxDepthMeters = maximumDepth,
            waterPressureTelemetry = null, safety = state.safety.copy(waterPressureKpa = waterPressure))
        return ProcessingOutcome(state)
    }

    fun forceMode(
        mode: AppMode,
        reason: String = "forced",
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = Reduction(state = state.copy(mode = mode))
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = reason,
            path = "force_mode",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
    }

    fun exportDiagnostics(): Map<String, String> = diagnostics.exportBundle(state)

    fun diagnosticsErrorCount(): Int = diagnostics.errorCount()

    fun diagnosticsRawPacketCount(): Int = diagnostics.rawPacketCount()

    private fun commitReduction(
        previousState: AppState,
        reduction: Reduction,
        reason: String,
        path: String,
        receivedAt: Instant,
        startedNanos: Long,
        exportedFiles: Map<String, String> = emptyMap(),
    ): ProcessingOutcome {
        state = reduction.state
        diagnostics.recordStateTransition(receivedAt, previousState, state, reason)
        diagnostics.recordLatency(receivedAt, path, elapsedMillis(startedNanos))
        return ProcessingOutcome(
            state = state,
            effects = reduction.effects,
            notes = reduction.notes,
            exportedFiles = exportedFiles,
        )
    }

    private fun completeWithoutStateChange(
        previousState: AppState,
        notes: List<String>,
        path: String,
        receivedAt: Instant,
        startedNanos: Long,
    ): ProcessingOutcome {
        diagnostics.recordLatency(receivedAt, path, elapsedMillis(startedNanos))
        return ProcessingOutcome(
            state = previousState,
            notes = notes,
        )
    }

    private fun elapsedMillis(startedNanos: Long): Long {
        return ((System.nanoTime() - startedNanos) / 1_000_000.0).toLong()
    }
}
