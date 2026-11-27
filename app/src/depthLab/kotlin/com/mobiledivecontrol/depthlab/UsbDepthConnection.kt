package com.mobiledivecontrol.depthlab

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.ceil

/** Connects outward to the wired adb reverse endpoint. Owns no BLE object or hardware write path. */
internal class UsbDepthConnection(
    private val wiredAdb: () -> Boolean,
    private val state: () -> AppState?,
    private val router: LabDepthRouter,
) {
    fun inputs() = flow<LabInput> {
        while (currentCoroutineContext().isActive) {
            if (wiredAdb()) {
                var attached = false
                try {
                    LocalSocket().use { client ->
                        client.connect(LocalSocketAddress("divecontrol_depth_lab", LocalSocketAddress.Namespace.ABSTRACT))
                        if (client.peerCredentials.uid !in setOf(0, 2000)) throw IOException("Expected adb peer")
                        client.soTimeout = 2500
                        val input = DataInputStream(client.inputStream)
                        val output = DataOutputStream(client.outputStream)
                        output.writeBytes("DCLAB2\n")
                        output.flush()
                        attached = true
                        while (currentCoroutineContext().isActive && wiredAdb()) {
                            val command = input.readUnsignedByte().toChar()
                            val value = input.readInt()
                            if (!wiredAdb()) break
                            val report = if (command == 'S') status() else null
                            val result = when (command) {
                                'S' -> report!!.size
                                'D' -> {
                                    if (value !in 0..90_000) throw IOException("Invalid depth")
                                    val kpa = STANDARD_SURFACE_PRESSURE_KPA + value / 1000.0 * FRESHWATER_KPA_PER_METER
                                    // Resolve half a pressure tick toward the surface so 5 m reaches the stop on ascent.
                                    val raw = ceil(kpa * 100 - 0.5 - 1e-9).toInt()
                                    emit(packet(HousingCharacteristic.WaterPressure,
                                        byteArrayOf(raw.toByte(), (raw ushr 8).toByte(), (raw ushr 16).toByte(), 0)))
                                    raw
                                }
                                'K' -> {
                                    if (value !in setOf(0x50, 0x60, 0x30, 0x61, 0x10, 0x40)) throw IOException("Invalid button")
                                    emit(packet(HousingCharacteristic.ButtonEvents, byteArrayOf(value.toByte())))
                                    value
                                }
                                'P' -> { emit(LabInput.MissingDepth); 0 }
                                'R' -> { emit(LabInput.HousingDepth); 0 }
                                else -> throw IOException("Unknown command")
                            }
                            output.writeByte(command.code)
                            output.writeInt(result)
                            if (report != null) output.write(report)
                            output.flush()
                        }
                    }
                } catch (_: IOException) {
                    // A removed tunnel, a closed controller, or a read timeout ends this lease.
                } finally {
                    if (attached && currentCoroutineContext().isActive) emit(LabInput.HousingDepth)
                }
            }
            delay(400)
        }
    }.flowOn(Dispatchers.IO)

    private fun packet(characteristic: HousingCharacteristic, bytes: ByteArray) =
        LabInput.Laptop(HousingLinkEvent.Notification(characteristic.shortHex, bytes, SensorPacketSource.Simulation))

    private fun status(): ByteArray {
        val screen = state()
        val profile = screen?.diveProfile
        val session = profile?.active
        return JSONObject().apply {
            put("source", router.source)
            put("housing", screen?.bleConnectionState?.name)
            put("housingDepth", router.physicalDepth(pressureMonotonicMs()))
            put("depth", profile?.depthMeters?.takeIf { profile.sensorAvailable })
            put("rate", profile?.verticalMetersPerMinute)
            put("phase", session?.phase?.name)
            put("presentation", profile?.stopPresentation?.name)
            put("remaining", session?.remainingSeconds?.takeIf { session.stopStarted })
            put("pending", profile?.continuationPending == true)
            put("confirmations", session?.diverReserveConfirmations ?: 0)
        }.toString().toByteArray(Charsets.UTF_8)
    }
}
