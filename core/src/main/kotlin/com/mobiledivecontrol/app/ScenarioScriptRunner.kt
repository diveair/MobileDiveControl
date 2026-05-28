package com.mobiledivecontrol.app

import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.core.BleTransportRequest
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.HousingProtocolAdapter
import com.mobiledivecontrol.core.PermissionKind
import com.mobiledivecontrol.core.ProcessingOutcome
import com.mobiledivecontrol.core.SafetyCommand
import com.mobiledivecontrol.core.SensorUpdate
import com.mobiledivecontrol.core.TransportOutcome
import com.mobiledivecontrol.core.parseHexByte
import com.mobiledivecontrol.core.parseHexPayload
import com.mobiledivecontrol.core.toHexString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.text.Charsets.UTF_8

data class ScenarioExecution(
    val transcript: List<String>,
    val exportedFiles: Map<String, String>,
) {
    fun render(): String = transcript.joinToString(separator = System.lineSeparator())
}

class ScenarioScriptRunner(
    private val protocolAdapter: HousingProtocolAdapter = HousingProtocolAdapter(),
) {
    fun runFile(path: Path): ScenarioExecution {
        val lines = Files.readAllLines(path)
        return runLines(lines)
    }

    fun runLines(lines: List<String>): ScenarioExecution {
        val transcript = mutableListOf<String>()
        var exportedFiles = emptyMap<String, String>()

        transcript += "START ${renderState()}"

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore("#").trim()
            if (line.isBlank()) {
                return@forEachIndexed
            }

            val result = execute(line)
            if (result.exportedFiles.isNotEmpty()) {
                exportedFiles = result.exportedFiles
            }

            transcript += "${index + 1}. $line"
            result.notes.forEach { transcript += "   note: $it" }
            result.effects.forEach { transcript += "   effect: $it" }
            result.transportRequests.forEach { transcript += "   tx: ${renderTransportRequest(it)}" }
            if (result.exportedFiles.isNotEmpty()) {
                transcript += "   export: ${result.exportedFiles.keys.sorted().joinToString()}"
            }
            transcript += "   state: ${renderState()}"
        }

        transcript += "END ${renderState()}"
        return ScenarioExecution(transcript, exportedFiles)
    }

    private fun execute(line: String): TransportOutcome {
        val tokens = line.split(Regex("\\s+"))
        require(tokens.isNotEmpty()) { "Scenario line cannot be empty." }

        return when (tokens[0].lowercase()) {
            "ble" -> executeBle(tokens)
            "permission" -> executePermission(tokens)
            "mode" -> executeMode(tokens)
            "button" -> executeButton(tokens)
            "notify" -> executeNotify(tokens)
            "notify-text" -> executeNotifyText(line)
            "battery" -> {
                require(tokens.size == 2) { "battery requires a numeric level." }
                protocolAdapter.handleNotification(
                    characteristic = HousingCharacteristic.BatteryLevel.shortHex,
                    payload = byteArrayOf(tokens[1].toInt().toByte()),
                )
            }
            "sensor" -> executeSensor(tokens)
            "vacuum" -> executeVacuum(tokens)
            "export" -> TransportOutcome(
                processingOutcome = ProcessingOutcome(
                    state = protocolAdapter.state,
                    exportedFiles = protocolAdapter.exportDiagnostics(),
                ),
            )
            "state" -> TransportOutcome(
                processingOutcome = ProcessingOutcome(
                    state = protocolAdapter.state,
                    notes = listOf(renderState()),
                ),
            )
            else -> error("Unsupported scenario command: $line")
        }
    }

    private fun executeBle(tokens: List<String>): TransportOutcome {
        require(tokens.size == 2) { "ble requires one argument." }
        val signal = when (tokens[1].lowercase()) {
            "scan" -> BleSignal.StartScan
            "connect" -> BleSignal.Connect
            "discover" -> BleSignal.DiscoverServices
            "subscribe" -> BleSignal.Subscribe
            "ready" -> BleSignal.Ready
            "disconnect" -> BleSignal.Disconnect
            "degrade" -> BleSignal.Degrade
            "fail" -> BleSignal.Fail
            "reset" -> BleSignal.Reset
            else -> error("Unsupported ble command: ${tokens[1]}")
        }
        return protocolAdapter.advanceBle(signal)
    }

    private fun executePermission(tokens: List<String>): TransportOutcome {
        require(tokens.size == 3) { "permission requires a name and on/off flag." }
        val permission = when (tokens[1].lowercase()) {
            "bluetooth" -> PermissionKind.Bluetooth
            "camera" -> PermissionKind.Camera
            "microphone" -> PermissionKind.Microphone
            "overlay" -> PermissionKind.Overlay
            "accessibility" -> PermissionKind.Accessibility
            "foreground-service" -> PermissionKind.ForegroundService
            "notifications" -> PermissionKind.Notifications
            else -> error("Unsupported permission: ${tokens[1]}")
        }
        val granted = when (tokens[2].lowercase()) {
            "on", "true", "enabled" -> true
            "off", "false", "disabled" -> false
            else -> error("Permission state must be on/off.")
        }
        return protocolAdapter.updatePermission(permission, granted)
    }

    private fun executeMode(tokens: List<String>): TransportOutcome {
        require(tokens.size == 2) { "mode requires a target mode." }
        val mode = when (tokens[1].lowercase()) {
            "camera" -> AppMode.CameraLive
            "camera-adjust" -> AppMode.CameraAdjust
            "phone-cursor" -> AppMode.PhoneCursor
            "phone-target" -> AppMode.PhoneTarget
            "safety" -> AppMode.Safety
            "diagnostics" -> AppMode.Diagnostics
            else -> error("Unsupported mode: ${tokens[1]}")
        }
        return protocolAdapter.forceMode(mode, reason = "scenario:mode")
    }

    private fun executeButton(tokens: List<String>): TransportOutcome {
        require(tokens.size == 2) { "button requires a single hex byte or 'malformed'." }
        val payload = if (tokens[1].equals("malformed", ignoreCase = true)) {
            byteArrayOf(0x10.toByte(), 0x20.toByte())
        } else {
            byteArrayOf(parseHexByte(tokens[1]))
        }
        return protocolAdapter.handleNotification(HousingCharacteristic.ButtonEvents.shortHex, payload)
    }

    private fun executeNotify(tokens: List<String>): TransportOutcome {
        require(tokens.size >= 3) { "notify requires a characteristic and payload." }
        return protocolAdapter.handleNotification(
            characteristic = tokens[1],
            payload = parseHexPayload(tokens.drop(2)),
        )
    }

    private fun executeNotifyText(line: String): TransportOutcome {
        val match = Regex("^notify-text\\s+(\\S+)\\s+(.+)$", RegexOption.IGNORE_CASE).find(line)
            ?: error("notify-text requires a characteristic and text payload.")
        return protocolAdapter.handleNotification(
            characteristic = match.groupValues[1],
            payload = match.groupValues[2].toByteArray(UTF_8),
        )
    }

    private fun executeSensor(tokens: List<String>): TransportOutcome {
        require(tokens.size >= 3) { "sensor requires a type and value." }
        val update = when (tokens[1].lowercase()) {
            "cover" -> {
                require(tokens.size == 3) { "sensor cover requires open/closed." }
                SensorUpdate.CoverState(tokens[2].equals("open", ignoreCase = true))
            }
            "barometric" -> {
                require(tokens.size == 3) { "sensor barometric requires a numeric value." }
                SensorUpdate.BarometricPressure(tokens[2].toDouble())
            }
            "water-pressure" -> {
                require(tokens.size == 3) { "sensor water-pressure requires a numeric value." }
                SensorUpdate.WaterPressure(tokens[2].toDouble())
            }
            "water-temp" -> {
                require(tokens.size == 3) { "sensor water-temp requires a numeric value." }
                SensorUpdate.WaterTemperature(tokens[2].toDouble())
            }
            else -> error("Unsupported sensor type: ${tokens[1]}")
        }
        return protocolAdapter.updateSensor(update)
    }

    private fun executeVacuum(tokens: List<String>): TransportOutcome {
        require(tokens.size == 2) { "vacuum requires start/cancel/reset." }
        val command = when (tokens[1].lowercase()) {
            "start" -> SafetyCommand.StartVacuumCheck
            "cancel" -> SafetyCommand.CancelVacuumCheck
            "reset" -> SafetyCommand.ResetSealState
            else -> error("Unsupported vacuum command: ${tokens[1]}")
        }
        return protocolAdapter.dispatch(command)
    }

    private fun renderState(): String {
        val state = protocolAdapter.state
        return buildString {
            append("mode=").append(state.mode.name)
            append(" ble=").append(state.bleConnectionState.name)
            append(" connected=").append(state.housing.connected)
            append(" input=").append(state.housing.inputEnabled)
            append(" battery=").append(state.housing.batteryPercent ?: "unknown")
            append(" cameraMode=").append(state.camera.activeMode.name)
            append(" zone=").append(state.camera.focusedZone.name)
            append(" zoom=").append("%.1f".format(state.camera.zoomFactor))
            append(" recording=").append(state.camera.recording)
            append(" seal=").append(state.safety.sealState.name)
            if (state.safety.coverOpen != null) {
                append(" cover=").append(if (state.safety.coverOpen) "open" else "closed")
            }
            if (state.safety.barometricPressureKpa != null) {
                append(" baroKpa=").append("%.3f".format(state.safety.barometricPressureKpa))
            }
            if (state.safety.waterPressureKpa != null) {
                append(" waterKpa=").append("%.2f".format(state.safety.waterPressureKpa))
            }
            if (state.safety.waterTemperatureC != null) {
                append(" waterC=").append("%.2f".format(state.safety.waterTemperatureC))
            }
            if (state.housing.firmwareVersion != null) {
                append(" fw=").append(state.housing.firmwareVersion)
            }
            if (state.lastWarning != null) {
                append(" warning=").append(state.lastWarning)
            }
        }
    }

    private fun renderTransportRequest(request: BleTransportRequest): String = when (request) {
        is BleTransportRequest.Subscribe -> "subscribe ${request.characteristic.shortHex}"
        is BleTransportRequest.Read -> "read ${request.characteristic.shortHex}"
        is BleTransportRequest.Write -> "write ${request.characteristic.shortHex} ${request.payload.toHexString()}"
    }
}
