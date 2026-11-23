package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.viewmodel.DiveViewModel
import org.json.JSONArray
import org.json.JSONObject

internal object PanoramaControlsProbe {
    fun run(test: Instrumentation, model: DiveViewModel): JSONObject {
        fun dispatch(command: CameraCommand) = test.runOnMainSync { model.dispatch(command) }
        fun select(suffix: String, value: String) {
            val camera = model.state.value.camera
            val spec = CameraCatalog.settingsFor(camera).first { it.id.endsWith(".$suffix") }
            check(value in spec.options)
            val current = CameraCatalog.currentValue(camera, spec)
            if (current != value) dispatch(CameraCommand.NudgeSetting(spec.id, spec.options.indexOf(value) - spec.options.indexOf(current)))
            SystemClock.sleep(2200)
        }
        fun logs(): String = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("logcat -d -s DiveControls:D"))
            .bufferedReader().use { it.readText().takeLast(5000) }
        dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == CameraModeId.Panorama }))
        SystemClock.sleep(3000)
        val rows = JSONArray()
        for ((lens, physical) in listOf("1x" to "5", "0.6x" to "2", "3x" to "6", "1x" to "5")) {
            val before = SystemClock.elapsedRealtime()
            select("lens", lens)
            val sample = checkNotNull(CameraControlTrace.focusSample)
            check(sample.atMs > before && sample.physicalId == physical) { "$lens did not reach $physical: $sample" }
            rows.put(JSONObject().put("lens", lens).put("sample", sample.toString()))
        }
        dispatch(CameraCommand.SetZoom(2.0))
        SystemClock.sleep(2500)
        val zoomLog = logs()
        check(zoomLog.contains("android.control.zoomRatio=2.0")) { "Panorama zoom did not reach camera: $zoomLog" }
        select("white_balance", "5000K")
        val wbLog = logs()
        check(wbLog.contains("android.control.awbMode=0")) { "Panorama WB did not reach camera" }
        return JSONObject().put("lenses", rows).put("zoom", 2.0).put("wb", "5000K").put("passed", true)
    }
}
