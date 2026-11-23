package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.selectedSetting
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.File
import org.json.JSONObject

/** Uses real reducer inputs, sensor results and screenshots; no mocked camera or synthetic pass flag. */
class FocusRegressionInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        if (args.getString("recording") == "true") { runSlowMotionRecordingProbe(args); return }
        val report = JSONObject()
        val mode = CameraModeId.valueOf(args.getString("mode") ?: "Hyperlapse")
        val directory = File(targetContext.getExternalFilesDir(null), "focus-validation/${mode}-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            if (args.getString("highSpeedLenses") == "true") {
                report.put("lenses", HighSpeedLensProbe.run(targetContext))
                return
            }
            if (args.getString("capabilities") == "true") {
                report.put("cameras", DeviceCapabilityProbe.run(targetContext)).put("passed", true)
                return
            }
            if (args.getString("probeInfinity") == "true") {
                report.put("probe", InfinityFocusProbe.run(targetContext, args.getString("physical") ?: "5"))
                report.put("passed", true)
                return
            }
            if (args.getString("switches") == "true") {
                val prefs = targetContext.getSharedPreferences("camera_session", 0)
                val values = JSONObject(prefs.getString("setting_values", "{}")!!)
                for (key in values.keys().asSequence().toList()) when {
                    key.endsWith(".lens") -> values.put(key, "1x")
                    key.endsWith(".resolution") -> values.put(key, "FHD")
                    key.endsWith(".manual_focus") -> values.put(key, "0.500")
                    key.endsWith(".hdr") || key.endsWith(".log") -> values.put(key, "Off")
                }
                values.put("pro_video.frame_rate", CameraCatalog.proVideoFrameRateOption(30))
                prefs.edit().putString("setting_values", values.toString()).putString("active_mode", "Pro").commit()
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var model: DiveViewModel
            runOnMainSync { model = ViewModelProvider(activity)[DiveViewModel::class.java]; model.dismissIntro() }
            fun dispatch(command: CameraCommand) = runOnMainSync { model.dispatch(command) }
            // Let the persisted launch graph present before navigating to the test mode.
            // A cold launch includes capability discovery and can outlast Activity.onResume.
            val launchDeadline = SystemClock.elapsedRealtime() + 15000
            while (CameraControlTrace.focusSample == null && SystemClock.elapsedRealtime() < launchDeadline)
                SystemClock.sleep(100)
            if (args.getString("controls") == "true") {
                report.put("controls", ControlsRetestProbe.run(this, model, directory)).put("passed", true)
                return
            }
            if (args.getString("panoramaControls") == "true") {
                report.put("panorama", PanoramaControlsProbe.run(this, model)).put("passed", true)
                return
            }
            if (args.getString("switches") == "true") {
                val switches = ModeSwitchProbe.run(this, model, directory)
                report.put("switches", switches)
                ModeSwitchProbe.verify(switches)
                report.put("passed", true)
                return
            }
            dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode }))
            SystemClock.sleep(3000)
            fun select(suffix: String, value: String) {
                val state = model.state.value.camera
                val spec = CameraCatalog.settingsFor(state).first { it.id.endsWith(".$suffix") }
                check(value in spec.options) { "$suffix=$value unavailable: ${spec.options}" }
                val current = CameraCatalog.currentValue(state, spec)
                if (current != value) dispatch(CameraCommand.NudgeSetting(spec.id, spec.options.indexOf(value) - spec.options.indexOf(current)))
                SystemClock.sleep(1200)
                check(CameraCatalog.currentValue(model.state.value.camera, spec) == value) { "$suffix did not accept $value" }
            }
            select("lens", args.getString("lens") ?: "1x")
            if (mode == CameraModeId.ProVideo) {
                select("log", "Off")
                select("hdr", if (args.getString("hdr") == "true") "On" else "Off")
                select("frame_rate", CameraCatalog.proVideoFrameRateOption(30))
            }
            if (mode.captureType == com.mobiledivecontrol.core.CameraCaptureType.Video)
                select("resolution", args.getString("resolution")?.replace('_', ' ') ?: "FHD")
            if (mode == CameraModeId.SlowMotion) select("frame_rate", "60fps")
            if (mode == CameraModeId.SlowMotion) select("exposure", "0.0")
            if (mode == CameraModeId.Hyperlapse) select("day_night", if (args.getString("night") == "true") "Night" else "Day")
            if (args.getString("routeOnly") == "true") {
                check(args.getString("lens") == "0.6x")
                val manager = targetContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val expected = manager.getCameraCharacteristics("0").physicalCameraIds.minBy { id ->
                    manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.min()
                }
                val started = SystemClock.elapsedRealtime()
                SystemClock.sleep(3000)
                val result = checkNotNull(CameraControlTrace.focusSample)
                report.put("route", result.toString()).put("expectedPhysical", expected)
                check(result.atMs > started && result.physicalId == expected) { "Ultrawide route did not present: $result" }
                val frames = CameraPipelineTelemetry.snapshot()
                check(SystemClock.elapsedRealtime() - maxOf(frames.lastDisplayedFrameAtMs, frames.lastDirectPreviewFrameAtMs) < 250)
                report.put("passed", true)
                return
            }
            fun sample(value: String): CameraControlTrace.FocusSample {
                select("manual_focus", value)
                val started = SystemClock.elapsedRealtime()
                SystemClock.sleep(4500)
                val deadline = started + 15000
                while ((CameraControlTrace.focusSample?.atMs ?: 0L) <= started && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(100)
                val result = checkNotNull(CameraControlTrace.focusSample) { "No capture telemetry" }
                check(result.atMs > started) { "Stale focus result: $result" }
                check(result.afMode == 0) { "Manual focus did not disable AF: $result" }
                check(result.actual != null) { "No physical lens distance: $result" }
                return result
            }
            val endpoint = args.getString("endpoint") == "true"
            val near = sample(if (endpoint) "0.000" else "0.200")
            val far = sample("0.800")
            report.put("near", near.toString()).put("far", far.toString())
            check(kotlin.math.abs(near.actual!! - far.actual!!) > 1f) { "Lens did not move between focus positions" }
            if (endpoint) {
                val manager = targetContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val chars = manager.getCameraCharacteristics(checkNotNull(near.physicalId))
                val range = checkNotNull(chars.get(android.hardware.camera2.CameraCharacteristics.Key(
                    "samsung.android.lens.info.focusLensInfo", IntArray::class.java)))
                check(range.size >= 2)
                val infinity = sample("1.000")
                // The S24 reports approximately 0.1 D even at its zero-diopter actuator position.
                // Compare against an independent physical-camera probe, not a fabricated zero readback.
                val infinityActuator = checkNotNull(args.getString("infinityActuator")) {
                    "Supply the actuator measured by the independent zero-diopter hardware probe"
                }.toInt()
                check(infinity.requested == 0f && infinity.actual!! in 0f..0.11f &&
                    kotlin.math.abs(checkNotNull(infinity.actuator) - infinityActuator) <= 8) {
                    "1.000 missed the independently measured infinity position $infinityActuator: $infinity"
                }
                report.put("infinity", infinity.toString())
                val returned = sample("0.000")
                report.put("returnedToZero", returned.toString()).put("nearActuatorStop", range[1])
                for (reading in listOf(near, returned)) {
                    check(kotlin.math.abs(checkNotNull(reading.actuator) - range[1]) <= 32) {
                        "0.000 missed physical near stop ${range[1]}: $reading"
                    }
                }
                report.put("passed", true)
                return
            }
            // Keep a known manual plane for pixel comparison; AF is behind the deliberate wheel rail gate.
            fun screen(name: String): Int {
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                var green = 0
                // Scene only: exclude the green battery HUD and the bottom control strip.
                for (y in bitmap.height / 5 until bitmap.height * 2 / 3)
                    for (x in bitmap.width / 10 until bitmap.width * 9 / 10) {
                        val c = bitmap.getPixel(x, y)
                        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                        if (g > 90 && g > r * 1.5 && g > b * 1.5) green++
                    }
                bitmap.recycle()
                return green
            }
            select("focus_peaking", "Off")
            select("manual_focus", args.getString("assistFocus") ?: "0.200")
            SystemClock.sleep(1500)
            val off = screen("assist-off")
            select("focus_peaking", "On")
            SystemClock.sleep(2000)
            val on = screen("assist-on")
            report.put("greenOff", off).put("greenOn", on)
            check(on > off + 25) { "No visible focus overlay: off=$off on=$on" }
            if (args.getString("wheel") == "true") {
                var moves = 0
                while (model.state.value.camera.selectedSetting?.id?.endsWith(".manual_focus") != true && moves++ < 30)
                    dispatch(CameraCommand.NavigateRight)
                check(model.state.value.camera.selectedSetting?.id?.endsWith(".manual_focus") == true)
                val focus = checkNotNull(model.state.value.camera.selectedSetting)
                val beforeWheel = CameraCatalog.currentValue(model.state.value.camera, focus).toDouble()
                dispatch(CameraCommand.ZoomOut)
                SystemClock.sleep(2500)
                val afterWheel = CameraCatalog.currentValue(model.state.value.camera, focus)
                check(afterWheel.toDoubleOrNull() != null && afterWheel.toDouble() != beforeWheel) { "Wheel did not retain manual focus: $afterWheel" }
                val wheelSample = checkNotNull(CameraControlTrace.focusSample)
                check(wheelSample.afMode == 0 && kotlin.math.abs(wheelSample.actual!! - far.actual!!) > 0.01f)
                report.put("wheelValue", afterWheel).put("wheelSensor", wheelSample.toString())
                select("manual_focus", "1.000")
                SystemClock.sleep(1000)
                dispatch(if (CameraCatalog.focusWheelReversed(model.state.value.camera)) CameraCommand.ZoomOut else CameraCommand.ZoomIn)
                SystemClock.sleep(4500)
                check(CameraCatalog.currentValue(model.state.value.camera, focus) == "AF") { "Wheel failed to enter AF" }
                report.put("autoSensor", CameraControlTrace.focusSample.toString())
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("failure", error.stackTraceToString())
        } finally {
            File(directory, "report.json").writeText(report.toString(2))
            finish(if (report.has("failure")) 1 else 0, Bundle().apply {
                putString("report", report.toString()); putString("directory", directory.absolutePath)
            })
        }
    }
}
