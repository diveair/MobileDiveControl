package com.mobiledivecontrol.platform.ble

import android.app.Instrumentation
import android.os.Bundle
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.toSpacedHexString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fresh GATT scan independent of HousingLink, ControlCore, the UI and pump state machine. */
class PressureDiscoveryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val report = JSONObject().put("startedAtEpochMs", System.currentTimeMillis())
        val notifications = mutableListOf<JSONObject>()
        val notificationLock = Any()
        val transport = AndroidBleTransport(targetContext, HousingStore(targetContext).lastAddress)
        try {
            runBlocking {
                try {
                    transport.setNotificationListener { sensor, payload ->
                        if (sensor in sensors) synchronized(notificationLock) {
                            notifications += sample(sensor, payload, "Notification")
                        }
                    }
                    val device = checkNotNull(transport.scan(15000)) { "Housing not found during fresh BLE scan" }
                    report.put("advertisedName", device.name)
                    report.put("address", device.macAddress)
                    check(transport.connect(device)) { "Housing connection failed" }
                    report.put("services", JSONArray(transport.discoverServices().toList()))
                    report.put("gatt", JSONArray(transport.discoveryReport))
                    check(sensors.all { it in transport.availableCharacteristics }) { "A documented sensor is absent" }
                    val firmware = transport.readCharacteristic(HousingCharacteristic.FirmwareRevision)
                    report.put("firmware", firmware?.toString(Charsets.UTF_8))
                    for (sensor in sensors) check(transport.subscribe(sensor)) { "Subscription failed: $sensor" }
                    val reads = JSONArray()
                    repeat(20) {
                        for (sensor in sensors) reads.put(sample(sensor, transport.readCharacteristic(sensor), "Read"))
                        delay(500)
                    }
                    report.put("reads", reads)
                    report.put("notifications", synchronized(notificationLock) { JSONArray(notifications.toList()) })
                } finally {
                    transport.disconnect()
                }
            }
        } catch (error: Throwable) {
            report.put("failure", android.util.Log.getStackTraceString(error))
            result.putString("failure", report.getString("failure"))
        } finally {
            val directory = File(targetContext.getExternalFilesDir(null), "pressure-validation").apply { mkdirs() }
            val file = File(directory, "discovery-report.json")
            file.writeText(report.toString(2))
            result.putString("report", file.absolutePath)
            finish(if (report.has("failure")) 1 else 0, result)
        }
    }

    private fun sample(sensor: HousingCharacteristic, payload: ByteArray?, source: String): JSONObject {
        // Decode independently of the application parser; retain the bytes as the primary evidence.
        val raw = if (payload?.size == 4) payload.withIndex().fold(0L) { n, (i, b) ->
            n or ((b.toLong() and 255L) shl (8 * i))
        } else null
        val kpa = when (sensor) {
            HousingCharacteristic.WaterPressure -> raw?.div(100.0)
            HousingCharacteristic.BarometricPressure -> raw?.div(1000.0)
            else -> null
        }
        return JSONObject().put("characteristic", sensor.shortHex).put("source", source)
            .put("epochMs", System.currentTimeMillis())
            .put("rawHex", payload?.toSpacedHexString() ?: JSONObject.NULL)
            .put("rawUnsignedLE32", raw ?: JSONObject.NULL).put("pressureKpa", kpa ?: JSONObject.NULL)
    }

    private companion object {
        val sensors = listOf(HousingCharacteristic.WaterPressure, HousingCharacteristic.BarometricPressure,
            HousingCharacteristic.WaterTemperature, HousingCharacteristic.CoverState)
    }
}
