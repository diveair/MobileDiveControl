package com.mobiledivecontrol.core

import java.time.Instant

data class TransportOutcome(
    val processingOutcome: ProcessingOutcome,
    val transportRequests: List<BleTransportRequest> = emptyList(),
) {
    val state: AppState
        get() = processingOutcome.state

    val effects: List<PlatformEffect>
        get() = processingOutcome.effects

    val notes: List<String>
        get() = processingOutcome.notes

    val exportedFiles: Map<String, String>
        get() = processingOutcome.exportedFiles
}

class HousingProtocolAdapter(
    private val controlCore: ControlCore = ControlCore(),
    private val commandEncoder: HousingCommandEncoder = HousingCommandEncoder(),
) {
    val state: AppState
        get() = controlCore.state

    fun handleNotification(
        characteristic: String,
        payload: ByteArray,
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.handleNotificationPayload(characteristic, payload, receivedAt))
    }

    fun dispatch(
        command: ControlCommand,
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.dispatch(command, receivedAt))
    }

    fun advanceBle(
        signal: BleSignal,
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.advanceBle(signal, receivedAt))
    }

    fun updatePermission(
        permission: PermissionKind,
        granted: Boolean,
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.updatePermission(permission, granted, receivedAt))
    }

    fun updateSensor(
        sensorUpdate: SensorUpdate,
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.updateSensor(sensorUpdate, receivedAt))
    }

    fun forceMode(
        mode: AppMode,
        reason: String = "forced",
        receivedAt: Instant = Instant.now(),
    ): TransportOutcome {
        return wrap(controlCore.forceMode(mode, reason, receivedAt))
    }

    fun exportDiagnostics(): Map<String, String> = controlCore.exportDiagnostics()

    fun diagnosticsErrorCount(): Int = controlCore.diagnosticsErrorCount()

    fun diagnosticsRawPacketCount(): Int = controlCore.diagnosticsRawPacketCount()

    fun subscriptionRequests(): List<BleTransportRequest.Subscribe> = commandEncoder.subscriptionRequests()

    fun deviceInfoReadRequests(): List<BleTransportRequest.Read> = commandEncoder.deviceInfoReadRequests()

    private fun wrap(outcome: ProcessingOutcome): TransportOutcome {
        return TransportOutcome(
            processingOutcome = outcome,
            transportRequests = commandEncoder.encodeEffects(outcome.effects),
        )
    }
}
