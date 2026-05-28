package com.mobiledivecontrol.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HousingProtocolAdapterTest {
    @Test
    fun `vacuum start emits housing writes after cover open notification`() {
        val adapter = HousingProtocolAdapter()
        adapter.advanceBle(BleSignal.Ready)
        adapter.forceMode(AppMode.Safety)
        adapter.handleNotification(HousingCharacteristic.CoverState.shortHex, byteArrayOf(0x00.toByte()))

        val outcome = adapter.dispatch(SafetyCommand.StartVacuumCheck)

        assertEquals(SealState.Vacuuming, outcome.state.safety.sealState)
        val writes = outcome.transportRequests.map { it as BleTransportRequest.Write }
        assertEquals(
            listOf(HousingCharacteristic.SolenoidValve, HousingCharacteristic.VacuumMotor),
            writes.map { it.characteristic },
        )
        assertContentEquals(byteArrayOf(0x01.toByte()), writes[0].payload)
        assertContentEquals(byteArrayOf(0x01.toByte()), writes[1].payload)
    }

    @Test
    fun `firmware revision notification updates housing metadata`() {
        val adapter = HousingProtocolAdapter()

        val outcome = adapter.handleNotification(HousingCharacteristic.FirmwareRevision.shortHex, "A4.0".encodeToByteArray())

        assertEquals("A4.0", outcome.state.housing.firmwareVersion)
    }
}
