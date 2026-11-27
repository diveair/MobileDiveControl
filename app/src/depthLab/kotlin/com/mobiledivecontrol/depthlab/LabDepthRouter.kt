package com.mobiledivecontrol.depthlab

import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.platform.ble.HousingLinkEvent

internal sealed interface LabInput {
    data class Physical(val event: HousingLinkEvent) : LabInput
    data class Laptop(val packet: HousingLinkEvent.Notification) : LabInput
    data object MissingDepth : LabInput
    data object HousingDepth : LabInput
}

/** Selects pressure only downstream of the real BLE link, its raw capture and its pump interlock. */
internal class LabDepthRouter {
    @Volatile var source = "Housing"
        private set
    @Volatile private var latestHousingDepth: HousingLinkEvent.Notification? = null
    private val parser = ProtocolParser()

    fun physicalDepth(now: Long): Double? {
        val last = latestHousingDepth ?: return null
        if (now - last.receivedAtMonotonicMs !in 0 until PRESSURE_STALE_MS) return null
        val reading = parser.decodeWaterPressure(last.payload) as? ParseResult.Success ?: return null
        return pressureDepthMeters(reading.value.kpa)
    }

    fun route(input: LabInput): HousingLinkEvent? = when (input) {
        is LabInput.Physical -> {
            val event = input.event
            when {
                event is HousingLinkEvent.Notification && event.characteristicShortHex == HousingCharacteristic.WaterPressure.shortHex -> {
                    latestHousingDepth = event
                    event.takeIf { source == "Housing" }
                }
                else -> {
                    if (event is HousingLinkEvent.Ble && event.signal != BleSignal.Ready) latestHousingDepth = null
                    event
                }
            }
        }
        is LabInput.Laptop -> {
            if (input.packet.characteristicShortHex == HousingCharacteristic.WaterPressure.shortHex) source = "Laptop"
            input.packet
        }
        LabInput.MissingDepth -> { source = "Depth loss"; null }
        LabInput.HousingDepth -> {
            source = "Housing"
            // Preserve receipt times. Only a new hardware packet may resume pressure credit.
            null
        }
    }
}
