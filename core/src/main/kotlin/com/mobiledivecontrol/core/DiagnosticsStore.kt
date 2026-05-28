package com.mobiledivecontrol.core

import java.time.Instant

class RingBuffer<T>(
    private val capacity: Int,
) {
    private val entries = ArrayDeque<T>()

    fun add(value: T) {
        if (entries.size == capacity) {
            entries.removeFirst()
        }
        entries.addLast(value)
    }

    fun toList(): List<T> = entries.toList()

    val size: Int
        get() = entries.size
}

data class RawPacketRecord(
    val timestamp: Instant,
    val characteristic: String,
    val payloadHex: String,
)

data class ButtonRecord(
    val timestamp: Instant,
    val rawHex: String,
    val event: String,
    val mode: AppMode,
    val repeatCount: Int,
)

data class CommandRecord(
    val timestamp: Instant,
    val command: String,
    val mode: AppMode,
)

data class StateTransitionRecord(
    val timestamp: Instant,
    val fromState: String,
    val toState: String,
    val reason: String,
)

data class ErrorRecord(
    val timestamp: Instant,
    val code: String,
    val message: String,
)

data class LatencyRecord(
    val timestamp: Instant,
    val path: String,
    val latencyMs: Long,
)

class DiagnosticsStore {
    private val rawPackets = RingBuffer<RawPacketRecord>(500)
    private val decodedButtons = RingBuffer<ButtonRecord>(500)
    private val commands = RingBuffer<CommandRecord>(300)
    private val stateTransitions = RingBuffer<StateTransitionRecord>(200)
    private val errors = RingBuffer<ErrorRecord>(100)
    private val latencies = RingBuffer<LatencyRecord>(100)

    fun recordRawPacket(timestamp: Instant, characteristic: String, payload: ByteArray) {
        rawPackets.add(
            RawPacketRecord(
                timestamp = timestamp,
                characteristic = characteristic,
                payloadHex = payload.toHexString(),
            ),
        )
    }

    fun recordDecodedButton(
        timestamp: Instant,
        rawValue: UByte,
        event: HousingButtonEvent,
        mode: AppMode,
        repeatCount: Int,
    ) {
        decodedButtons.add(
            ButtonRecord(
                timestamp = timestamp,
                rawHex = rawValue.toHexString(),
                event = event.toString(),
                mode = mode,
                repeatCount = repeatCount,
            ),
        )
    }

    fun recordCommand(timestamp: Instant, command: ControlCommand, mode: AppMode) {
        commands.add(
            CommandRecord(
                timestamp = timestamp,
                command = command.toString(),
                mode = mode,
            ),
        )
    }

    fun recordStateTransition(timestamp: Instant, from: AppState, to: AppState, reason: String) {
        if (from == to) {
            return
        }

        stateTransitions.add(
            StateTransitionRecord(
                timestamp = timestamp,
                fromState = summarizeState(from),
                toState = summarizeState(to),
                reason = reason,
            ),
        )
    }

    fun recordError(timestamp: Instant, code: String, message: String) {
        errors.add(
            ErrorRecord(
                timestamp = timestamp,
                code = code,
                message = message,
            ),
        )
    }

    fun recordLatency(timestamp: Instant, path: String, latencyMs: Long) {
        latencies.add(
            LatencyRecord(
                timestamp = timestamp,
                path = path,
                latencyMs = latencyMs,
            ),
        )
    }

    fun exportBundle(state: AppState): Map<String, String> {
        val eventLines = buildList {
            addAll(decodedButtons.toList().map { record ->
                TimedJsonLine(
                    timestamp = record.timestamp,
                    json = jsonObject(
                        "timestamp" to record.timestamp.toString(),
                        "source" to "button",
                        "raw" to record.rawHex,
                        "event" to record.event,
                        "mode" to record.mode.name,
                        "repeatCount" to record.repeatCount,
                    ),
                )
            })
            addAll(commands.toList().map { record ->
                TimedJsonLine(
                    timestamp = record.timestamp,
                    json = jsonObject(
                        "timestamp" to record.timestamp.toString(),
                        "source" to "command",
                        "command" to record.command,
                        "mode" to record.mode.name,
                    ),
                )
            })
            addAll(stateTransitions.toList().map { record ->
                TimedJsonLine(
                    timestamp = record.timestamp,
                    json = jsonObject(
                        "timestamp" to record.timestamp.toString(),
                        "source" to "state",
                        "from" to record.fromState,
                        "to" to record.toState,
                        "reason" to record.reason,
                    ),
                )
            })
        }.sortedBy { it.timestamp }

        val errorLines = errors.toList().joinToString(separator = "\n") { record ->
            jsonObject(
                "timestamp" to record.timestamp.toString(),
                "source" to "error",
                "code" to record.code,
                "message" to record.message,
            )
        }

        val latencyValues = latencies.toList().map { it.latencyMs }
        val latencyAverage = if (latencyValues.isEmpty()) 0.0 else latencyValues.average()
        val latencySummary = jsonObject(
            "count" to latencyValues.size,
            "maxMs" to latencyValues.maxOrNull(),
            "minMs" to latencyValues.minOrNull(),
            "avgMs" to latencyAverage,
        )

        return mapOf(
            "device-info.json" to jsonObject(
                "mode" to state.mode.name,
                "bleState" to state.bleConnectionState.name,
                "cameraTier" to state.camera.capabilityTier,
            ),
            "housing-info.json" to jsonObject(
                "advertisingName" to state.housing.advertisingName,
                "trustedIdentity" to state.housing.trustedIdentity,
                "batteryPercent" to state.housing.batteryPercent,
                "firmwareVersion" to state.housing.firmwareVersion,
                "hardwareVersion" to state.housing.hardwareVersion,
                "softwareVersion" to state.housing.softwareVersion,
                "manufacturerName" to state.housing.manufacturerName,
                "modelNumber" to state.housing.modelNumber,
                "serialNumber" to state.housing.serialNumber,
            ),
            "camera-capabilities.json" to jsonObject(
                "capabilityTier" to state.camera.capabilityTier,
                "deviceVariant" to state.camera.deviceVariant.name,
                "activeMode" to state.camera.activeMode.name,
                "focusedZone" to state.camera.focusedZone.name,
                "railLevel" to state.camera.railLevel.name,
                "settingsCount" to CameraCatalog.settingsFor(state.camera.activeMode, state.camera.deviceVariant).size,
            ),
            "permission-state.json" to jsonObject(
                "bluetooth" to state.permissions.bluetooth,
                "camera" to state.permissions.camera,
                "microphone" to state.permissions.microphone,
                "overlay" to state.permissions.overlay,
                "accessibility" to state.permissions.accessibility,
                "foregroundService" to state.permissions.foregroundService,
                "notifications" to state.permissions.notifications,
            ),
            "compatibility-info.json" to jsonObject(
                "transparentPhoneMode" to if (state.permissions.canUsePhoneControl()) "Supported" else "Unavailable",
                "overlayCursor" to if (state.permissions.canUseOverlayCursor()) "Supported" else "Unavailable",
                "sealState" to state.safety.sealState.name,
            ),
            "event-log.jsonl" to eventLines.joinToString(separator = "\n") { it.json },
            "error-log.jsonl" to errorLines,
            "latency-summary.json" to latencySummary,
        )
    }

    fun rawPacketCount(): Int = rawPackets.size

    fun errorCount(): Int = errors.size

    private fun summarizeState(state: AppState): String {
        return "mode=${state.mode.name},ble=${state.bleConnectionState.name},cameraMode=${state.camera.activeMode.name},zone=${state.camera.focusedZone.name},seal=${state.safety.sealState.name},recording=${state.camera.recording}"
    }
}

private data class TimedJsonLine(
    val timestamp: Instant,
    val json: String,
)
