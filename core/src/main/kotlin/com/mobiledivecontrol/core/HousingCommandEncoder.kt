package com.mobiledivecontrol.core

sealed interface BleTransportRequest {
    data class Subscribe(val characteristic: HousingCharacteristic) : BleTransportRequest
    data class Read(val characteristic: HousingCharacteristic) : BleTransportRequest
    data class Write(
        val characteristic: HousingCharacteristic,
        val payload: ByteArray,
    ) : BleTransportRequest
}

class HousingCommandEncoder {
    fun subscriptionRequests(): List<BleTransportRequest.Subscribe> {
        return HousingBleProfile.subscriptionOrder.map(BleTransportRequest::Subscribe)
    }

    fun deviceInfoReadRequests(): List<BleTransportRequest.Read> {
        return HousingBleProfile.deviceInfoReadOrder.map(BleTransportRequest::Read)
    }

    fun encodeEffects(effects: List<PlatformEffect>): List<BleTransportRequest> {
        return effects.flatMap { effect ->
            when (effect) {
                is PlatformEffect.ExecuteHousing -> encode(effect.command)
                else -> emptyList()
            }
        }
    }

    fun encode(command: HousingCommand): List<BleTransportRequest> = when (command) {
        HousingCommand.TriggerFlash -> listOf(
            BleTransportRequest.Write(
                characteristic = HousingCharacteristic.FlashTrigger,
                payload = byteArrayOf(0x01.toByte()),
            ),
        )
        is HousingCommand.SetVacuumMotor -> listOf(
            BleTransportRequest.Write(
                characteristic = HousingCharacteristic.VacuumMotor,
                payload = byteArrayOf(if (command.enabled) 0x01.toByte() else 0x00.toByte()),
            ),
        )
        is HousingCommand.SetSolenoidValve -> listOf(
            BleTransportRequest.Write(
                characteristic = HousingCharacteristic.SolenoidValve,
                payload = byteArrayOf(if (command.open) 0x01.toByte() else 0x00.toByte()),
            ),
        )
        is HousingCommand.SendIrFlashlightCommand -> listOf(
            BleTransportRequest.Write(
                characteristic = HousingCharacteristic.IrFlashlight,
                payload = byteArrayOf(command.command.wireValue.toByte()),
            ),
        )
        HousingCommand.RequestBatteryRead -> listOf(BleTransportRequest.Read(HousingCharacteristic.BatteryLevel))
        HousingCommand.RequestDeviceInfo -> deviceInfoReadRequests()
        HousingCommand.Disconnect,
        HousingCommand.Reconnect,
            -> emptyList()
    }
}
