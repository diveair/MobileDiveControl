package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HousingCommandEncoderTest {
    private val encoder = HousingCommandEncoder()

    @Test
    fun `encodes vacuum motor start command`() {
        val requests = encoder.encode(HousingCommand.SetVacuumMotor(enabled = true))
        val write = requests.single() as BleTransportRequest.Write

        assertEquals(HousingCharacteristic.VacuumMotor, write.characteristic)
        assertContentEquals(byteArrayOf(0x01.toByte()), write.payload)
    }

    @Test
    fun `encodes flashlight focus command`() {
        val requests = encoder.encode(HousingCommand.SendIrFlashlightCommand(IrFlashlightCommand.FocusOrFlash))
        val write = requests.single() as BleTransportRequest.Write

        assertEquals(HousingCharacteristic.IrFlashlight, write.characteristic)
        assertContentEquals(byteArrayOf(0x06.toByte()), write.payload)
    }

    @Test
    fun `expands device info request into ordered read set`() {
        val requests = encoder.encode(HousingCommand.RequestDeviceInfo)

        assertEquals(
            HousingBleProfile.deviceInfoReadOrder,
            requests.map { (it as BleTransportRequest.Read).characteristic },
        )
    }

    @Test
    fun `exposes required subscription order`() {
        assertEquals(
            listOf(
                HousingCharacteristic.ButtonEvents,
                HousingCharacteristic.BatteryLevel,
                HousingCharacteristic.CoverState,
                HousingCharacteristic.BarometricPressure,
                HousingCharacteristic.WaterPressure,
                HousingCharacteristic.WaterTemperature,
            ),
            encoder.subscriptionRequests().map { it.characteristic },
        )
    }
}
