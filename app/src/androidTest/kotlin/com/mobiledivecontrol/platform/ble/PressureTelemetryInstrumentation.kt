package com.mobiledivecontrol.platform.ble

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.DiveControlApp
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.SystemCommand
import com.mobiledivecontrol.core.HousingCharacteristic
import com.mobiledivecontrol.core.toSpacedHexString
import com.mobiledivecontrol.viewmodel.DiveViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only housing probe. Uses the existing GATT queue; never issues actuator commands. */
class PressureTelemetryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val report = JSONObject()
        val directory = File(targetContext.getExternalFilesDir(null), "pressure-validation").apply { mkdirs() }
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val link = (targetContext.applicationContext as DiveControlApp).housingLink
            lateinit var model: DiveViewModel
            runOnMainSync { model = ViewModelProvider(activity)[DiveViewModel::class.java] }
            runBlocking {
                val notifications = JSONArray()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    link.events.collect { event ->
                        if (event is HousingLinkEvent.Notification &&
                            HousingCharacteristic.from(event.characteristicShortHex) in sensors
                        ) {
                            notifications.put(JSONObject()
                                .put("characteristic", event.characteristicShortHex)
                                .put("source", event.source.name)
                                .put("rawHex", event.payload.toSpacedHexString())
                                .put("receivedAtEpochMs", event.receivedAtEpochMs))
                        }
                    }
                }
                try {
                    withTimeout(20000) {
                        while (!model.state.value.housing.connected) delay(100)
                    }
                    runOnMainSync { model.dispatch(SystemCommand.SwitchToDiagnosticsMode) }
                    // Observe the connected session without adding a production debugging API.
                    val sessionField = HousingLink::class.java.getDeclaredField("session").apply { isAccessible = true }
                    val session = checkNotNull(sessionField.get(link))
                    val transportField = session.javaClass.getDeclaredField("transport").apply { isAccessible = true }
                    val transport = transportField.get(session) as HousingTransport
                    report.put("gatt", JSONArray(transport.discoveryReport))
                    val reads = JSONArray()
                    repeat(10) {
                        for (sensor in sensors) {
                            val raw = transport.readCharacteristic(sensor)
                            reads.put(JSONObject().put("characteristic", sensor.shortHex)
                                .put("rawHex", raw?.toSpacedHexString() ?: JSONObject.NULL)
                                .put("receivedAtEpochMs", System.currentTimeMillis()))
                        }
                        delay(500)
                    }
                    report.put("reads", reads)
                    report.put("notifications", notifications)
                    runOnMainSync {
                        val state = model.state.value
                        report.put("firmware", state.housing.firmwareVersion)
                        report.put("waterPressureKpa", state.safety.waterPressureKpa)
                        report.put("surfaceBaselineKpa", state.safety.surfaceAmbientKpa)
                        report.put("barometricPressureKpa", state.safety.barometricPressureKpa)
                        report.put("waterTemperatureC", state.safety.waterTemperatureC)
                        report.put("coverOpen", state.safety.coverOpen)
                        report.put("waterPacketCount", state.waterPressureTelemetry?.packetCount)
                        report.put("waterSource", state.waterPressureTelemetry?.source?.name)
                    }
                    check(reads.length() == 40) { "Incomplete read probe" }
                    delay(1200) // allow the batched recorder to flush
                    val log = File(targetContext.getExternalFilesDir(null), "pressure-logs/pressure-current.jsonl")
                    val records = log.readLines().map { JSONObject(it) }
                    val capture = records.last { it.optString("type") == "capture_started" }.getString("captureId")
                    val captured = records.filter { it.optString("captureId") == capture }
                    check(captured.any { it.optString("characteristic") == "0x1625" }) { "No water log" }
                    check(captured.any { it.optString("characteristic") == "0x1627" }) { "No internal-pressure log" }
                    report.put("continuousLogVerified", true)
                    report.put("continuousLogCaptureId", capture)
                    runOnMainSync { model.dispatch(SystemCommand.SwitchToCameraMode) }
                    delay(1200)
                    check(JSONObject(log.readLines().last()).optString("type") == "capture_stopped")
                    val stoppedLength = log.length()
                    delay(1200)
                    check(log.length() == stoppedLength) { "Capture continued after leaving Diagnostics" }
                    report.put("continuousLogStopVerified", true)
                } finally {
                    collector.cancelAndJoin()
                }
            }
        } catch (error: Throwable) {
            report.put("failure", android.util.Log.getStackTraceString(error))
            result.putString("failure", report.getString("failure"))
        } finally {
            val file = File(directory, "report.json")
            file.writeText(report.toString(2))
            result.putString("report", file.absolutePath)
            finish(if (report.has("failure")) 1 else 0, result)
        }
    }

    private companion object {
        val sensors = listOf(HousingCharacteristic.WaterPressure, HousingCharacteristic.BarometricPressure,
            HousingCharacteristic.WaterTemperature, HousingCharacteristic.CoverState)
    }
}
