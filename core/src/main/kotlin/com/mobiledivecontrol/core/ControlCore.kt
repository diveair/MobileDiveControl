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
) {
    var state: AppState = initialState
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
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state

        diagnostics.recordRawPacket(receivedAt, characteristic, payload)

        return when (val result = protocolParser.decodeNotification(characteristic, payload)) {
            is ParseResult.Failure -> {
                diagnostics.recordError(receivedAt, result.error.code, result.error.message)
                completeWithoutStateChange(
                    previousState = previousState,
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
                    val reduction = reducer.updateSensor(state, notification.update)
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

        val reduction = reducer.updateBleState(
            state = state,
            newState = transition.state,
            reconnectAttempt = reconnectAttempt,
            reconnectDelay = transition.reconnectDelay,
        )

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

    fun updateSensor(
        sensorUpdate: SensorUpdate,
        receivedAt: Instant = clock.instant(),
    ): ProcessingOutcome {
        val startedNanos = System.nanoTime()
        val previousState = state
        val reduction = reducer.updateSensor(state, sensorUpdate)
        return commitReduction(
            previousState = previousState,
            reduction = reduction,
            reason = "sensor:$sensorUpdate",
            path = "sensor_update",
            receivedAt = receivedAt,
            startedNanos = startedNanos,
        )
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
