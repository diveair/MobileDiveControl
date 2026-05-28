package com.mobiledivecontrol.core

import kotlin.text.Charsets.UTF_8

data class ProtocolError(
    val code: String,
    val message: String,
)

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Failure(val error: ProtocolError) : ParseResult<Nothing>
}

data class DecodedButtonPacket(
    val rawValue: UByte,
    val event: HousingButtonEvent,
)

sealed interface DecodedNotification {
    data class Button(val packet: DecodedButtonPacket) : DecodedNotification
    data class Battery(val percent: Int) : DecodedNotification
    data class Sensor(val update: SensorUpdate) : DecodedNotification
    data class DeviceInfo(val update: DeviceInfoUpdate) : DecodedNotification
}

class ProtocolParser {
    fun decodeNotification(characteristic: String, payload: ByteArray): ParseResult<DecodedNotification> {
        val resolved = HousingCharacteristic.from(characteristic)
            ?: return ParseResult.Failure(
                ProtocolError(
                    code = "characteristic_unknown",
                    message = "Unsupported characteristic $characteristic",
                ),
            )

        return when (resolved) {
            HousingCharacteristic.ButtonEvents -> decodeButtonPacket(payload).map { DecodedNotification.Button(it) }
            HousingCharacteristic.BatteryLevel -> decodeBatteryLevel(payload).map { DecodedNotification.Battery(it) }
            HousingCharacteristic.WaterPressure -> decodeWaterPressure(payload).map { DecodedNotification.Sensor(it) }
            HousingCharacteristic.WaterTemperature -> decodeWaterTemperature(payload).map { DecodedNotification.Sensor(it) }
            HousingCharacteristic.BarometricPressure -> decodeBarometricPressure(payload).map { DecodedNotification.Sensor(it) }
            HousingCharacteristic.CoverState -> decodeCoverState(payload).map { DecodedNotification.Sensor(it) }
            HousingCharacteristic.ManufacturerName -> decodeDeviceInfoText(payload, DeviceInfoUpdate::ManufacturerName)
            HousingCharacteristic.ModelNumber -> decodeDeviceInfoText(payload, DeviceInfoUpdate::ModelNumber)
            HousingCharacteristic.SerialNumber -> decodeDeviceInfoText(payload, DeviceInfoUpdate::SerialNumber)
            HousingCharacteristic.FirmwareRevision -> decodeDeviceInfoText(payload, DeviceInfoUpdate::FirmwareRevision)
            HousingCharacteristic.HardwareRevision -> decodeDeviceInfoText(payload, DeviceInfoUpdate::HardwareRevision)
            HousingCharacteristic.SoftwareRevision -> decodeDeviceInfoText(payload, DeviceInfoUpdate::SoftwareRevision)
            HousingCharacteristic.FlashTrigger,
            HousingCharacteristic.VacuumMotor,
            HousingCharacteristic.SolenoidValve,
            HousingCharacteristic.IrFlashlight,
                -> ParseResult.Failure(
                    ProtocolError(
                        code = "characteristic_direction_invalid",
                        message = "Characteristic ${resolved.shortHex} is outbound only.",
                    ),
                )
        }
    }

    fun decodeButtonPacket(payload: ByteArray): ParseResult<DecodedButtonPacket> {
        if (payload.size != 1) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "button_length_invalid",
                    message = "Expected 1 byte for button packet but received ${payload.size}",
                ),
            )
        }

        val raw = payload[0].toUByte()
        val event = when (raw) {
            0x10u.toUByte() -> HousingButtonEvent.Right
            0x20u.toUByte() -> HousingButtonEvent.Shutter
            0x30u.toUByte() -> HousingButtonEvent.Up
            0x40u.toUByte() -> HousingButtonEvent.Left
            0x50u.toUByte() -> HousingButtonEvent.Ok
            0x60u.toUByte() -> HousingButtonEvent.BackOrSafety
            0x61u.toUByte() -> HousingButtonEvent.Down
            0x70u.toUByte() -> HousingButtonEvent.ZoomIn
            0x80u.toUByte() -> HousingButtonEvent.ZoomOut
            else -> HousingButtonEvent.Unknown(raw)
        }

        return ParseResult.Success(DecodedButtonPacket(rawValue = raw, event = event))
    }

    fun decodeBatteryLevel(payload: ByteArray): ParseResult<Int> {
        if (payload.size != 1) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "battery_length_invalid",
                    message = "Expected 1 byte for battery level but received ${payload.size}",
                ),
            )
        }

        val level = payload[0].toInt() and 0xFF
        if (level !in 0..100) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "battery_range_invalid",
                    message = "Battery level must be between 0 and 100 but was $level",
                ),
            )
        }

        return ParseResult.Success(level)
    }

    fun decodeWaterPressure(payload: ByteArray): ParseResult<SensorUpdate.WaterPressure> {
        return decodeUnsignedSensor(
            payload = payload,
            label = "water pressure",
            transform = { raw -> SensorUpdate.WaterPressure(kpa = raw / 100.0) },
        )
    }

    fun decodeWaterTemperature(payload: ByteArray): ParseResult<SensorUpdate.WaterTemperature> {
        return decodeUnsignedSensor(
            payload = payload,
            label = "water temperature",
            transform = { raw -> SensorUpdate.WaterTemperature(celsius = raw / 100.0) },
        )
    }

    fun decodeBarometricPressure(payload: ByteArray): ParseResult<SensorUpdate.BarometricPressure> {
        return decodeUnsignedSensor(
            payload = payload,
            label = "barometric pressure",
            transform = { raw -> SensorUpdate.BarometricPressure(kpa = raw / 1000.0) },
        )
    }

    fun decodeCoverState(payload: ByteArray): ParseResult<SensorUpdate.CoverState> {
        if (payload.size != 1) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "cover_length_invalid",
                    message = "Expected 1 byte for cover state but received ${payload.size}",
                ),
            )
        }

        return when (payload[0].toInt() and 0xFF) {
            0x00 -> ParseResult.Success(SensorUpdate.CoverState(open = true))
            0x01 -> ParseResult.Success(SensorUpdate.CoverState(open = false))
            else -> ParseResult.Failure(
                ProtocolError(
                    code = "cover_value_invalid",
                    message = "Cover state byte must be 0x00 or 0x01.",
                ),
            )
        }
    }

    private fun <T : DeviceInfoUpdate> decodeDeviceInfoText(
        payload: ByteArray,
        factory: (String) -> T,
    ): ParseResult<DecodedNotification.DeviceInfo> {
        if (payload.isEmpty()) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "device_info_empty",
                    message = "Device info payload cannot be empty.",
                ),
            )
        }

        val decoded = payload.toString(UTF_8).trimEnd('\u0000').trim()
        if (decoded.isEmpty()) {
            return ParseResult.Failure(
                ProtocolError(
                    code = "device_info_empty",
                    message = "Device info payload cannot be blank.",
                ),
            )
        }

        return ParseResult.Success(DecodedNotification.DeviceInfo(factory(decoded)))
    }

    private fun <T> decodeUnsignedSensor(
        payload: ByteArray,
        label: String,
        transform: (Double) -> T,
    ): ParseResult<T> {
        val rawValue = decodeUnsignedInt32(payload)
            ?: return ParseResult.Failure(
                ProtocolError(
                    code = "${label.replace(' ', '_')}_length_invalid",
                    message = "Expected 4 bytes for $label but received ${payload.size}",
                ),
            )
        return ParseResult.Success(transform(rawValue.toDouble()))
    }

    private fun decodeUnsignedInt32(payload: ByteArray): Long? {
        if (payload.size != 4) {
            return null
        }

        return (payload[0].toLong() and 0xFF) or
            ((payload[1].toLong() and 0xFF) shl 8) or
            ((payload[2].toLong() and 0xFF) shl 16) or
            ((payload[3].toLong() and 0xFF) shl 24)
    }
}

private fun <T, R> ParseResult<T>.map(transform: (T) -> R): ParseResult<R> = when (this) {
    is ParseResult.Success -> ParseResult.Success(transform(value))
    is ParseResult.Failure -> this
}
