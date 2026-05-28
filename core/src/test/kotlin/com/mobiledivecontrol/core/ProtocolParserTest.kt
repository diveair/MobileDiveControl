package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolParserTest {
    private val parser = ProtocolParser()

    @Test
    fun `decodes supported button values`() {
        val result = parser.decodeButtonPacket(byteArrayOf(0x50.toByte()))
        val success = assertIs<ParseResult.Success<DecodedButtonPacket>>(result)
        assertEquals(HousingButtonEvent.Ok, success.value.event)
        assertEquals(0x50u.toUByte(), success.value.rawValue)
    }

    @Test
    fun `maps unknown button values without crashing`() {
        val result = parser.decodeButtonPacket(byteArrayOf(0x7F.toByte()))
        val success = assertIs<ParseResult.Success<DecodedButtonPacket>>(result)
        assertEquals(HousingButtonEvent.Unknown(0x7Fu.toUByte()), success.value.event)
    }

    @Test
    fun `rejects malformed button packets`() {
        val result = parser.decodeButtonPacket(byteArrayOf(0x10.toByte(), 0x20.toByte()))
        val failure = assertIs<ParseResult.Failure>(result)
        assertEquals("button_length_invalid", failure.error.code)
    }

    @Test
    fun `decodes battery notification by characteristic`() {
        val result = parser.decodeNotification(HousingCharacteristic.BatteryLevel.shortHex, byteArrayOf(85.toByte()))
        val success = assertIs<ParseResult.Success<DecodedNotification>>(result)
        assertEquals(DecodedNotification.Battery(85), success.value)
    }

    @Test
    fun `decodes water pressure vendor example into kpa`() {
        val result = parser.decodeNotification(
            HousingCharacteristic.WaterPressure.shortHex,
            byteArrayOf(0x83.toByte(), 0x27.toByte(), 0x00.toByte(), 0x00.toByte()),
        )
        val success = assertIs<ParseResult.Success<DecodedNotification>>(result)
        assertEquals(
            DecodedNotification.Sensor(SensorUpdate.WaterPressure(101.15)),
            success.value,
        )
    }

    @Test
    fun `decodes barometric pressure vendor example into kpa`() {
        val result = parser.decodeNotification(
            HousingCharacteristic.BarometricPressure.shortHex,
            byteArrayOf(0x7F.toByte(), 0x8A.toByte(), 0x01.toByte(), 0x00.toByte()),
        )
        val success = assertIs<ParseResult.Success<DecodedNotification>>(result)
        assertEquals(
            DecodedNotification.Sensor(SensorUpdate.BarometricPressure(100.991)),
            success.value,
        )
    }

    @Test
    fun `decodes cover open with vendor byte mapping`() {
        val result = parser.decodeNotification(HousingCharacteristic.CoverState.shortHex, byteArrayOf(0x00.toByte()))
        val success = assertIs<ParseResult.Success<DecodedNotification>>(result)
        assertEquals(
            DecodedNotification.Sensor(SensorUpdate.CoverState(open = true)),
            success.value,
        )
    }

    @Test
    fun `decodes firmware revision text payload`() {
        val result = parser.decodeNotification(HousingCharacteristic.FirmwareRevision.shortHex, "1.2.3".encodeToByteArray())
        val success = assertIs<ParseResult.Success<DecodedNotification>>(result)
        assertEquals(
            DecodedNotification.DeviceInfo(DeviceInfoUpdate.FirmwareRevision("1.2.3")),
            success.value,
        )
    }
}
