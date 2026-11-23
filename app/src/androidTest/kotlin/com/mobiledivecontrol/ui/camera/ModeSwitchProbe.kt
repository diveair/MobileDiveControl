package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.os.SystemClock
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.viewmodel.DiveViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Measures real preview delivery, including gaps hidden by the continuity bitmap. */
internal object ModeSwitchProbe {
    fun run(instrumentation: Instrumentation, model: DiveViewModel, directory: java.io.File): JSONArray {
        val results = JSONArray()
        SystemClock.sleep(2000)
        val timingModes = listOf(CameraModeId.ProVideo, CameraModeId.Pro, CameraModeId.Photo,
            CameraModeId.ProVideo, CameraModeId.Hyperlapse, CameraModeId.ProVideo,
            CameraModeId.PortraitVideo, CameraModeId.ProVideo, CameraModeId.ExpertRaw,
            CameraModeId.Pro, CameraModeId.ProVideo, CameraModeId.Pro)
        // Screenshot readback can block this sampling thread for hundreds of milliseconds.
        // Verify visible continuity separately so it cannot inflate measured frame gaps.
        for ((index, mode) in (timingModes + listOf(CameraModeId.ProVideo, CameraModeId.Hyperlapse,
            CameraModeId.ProVideo, CameraModeId.Pro)).withIndex()) {
            val visualPass = index >= timingModes.size
            val before = CameraPipelineTelemetry.snapshot()
            val start = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { model.dispatch(CameraCommand.ActivateModeRailEntry(
                CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode })) }
            var last = maxOf(before.lastDisplayedFrameAtMs, before.lastDirectPreviewFrameAtMs)
            var maxGap = 0L
            var firstReplacement = 0L
            var checkedTransition = false
            var transitionBlackFraction = 0.0
            while (SystemClock.elapsedRealtime() - start < 4000) {
                val snapshot = CameraPipelineTelemetry.snapshot()
                val frame = maxOf(snapshot.lastDisplayedFrameAtMs, snapshot.lastDirectPreviewFrameAtMs)
                if (frame > last) {
                    if (last > 0) maxGap = maxOf(maxGap, frame - last)
                    last = frame
                    if (firstReplacement == 0L && snapshot.lastDisplayedBindingGeneration != before.lastDisplayedBindingGeneration)
                        firstReplacement = frame - start
                }
                if (visualPass && !checkedTransition && SystemClock.elapsedRealtime() - start >= 350) {
                    checkedTransition = true
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(directory, "switch-${results.length()}-${mode.name}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    var black = 0
                    var pixels = 0
                    // Above the housing-disconnected banner; below the corner HUDs.
                    for (y in bitmap.height / 5 until bitmap.height / 3 step 4)
                        for (x in bitmap.width / 3 until bitmap.width * 2 / 3 step 4) {
                            val color = bitmap.getPixel(x, y)
                            if (android.graphics.Color.red(color) < 5 && android.graphics.Color.green(color) < 5 &&
                                android.graphics.Color.blue(color) < 5) black++
                            pixels++
                        }
                    transitionBlackFraction = black.toDouble() / pixels
                    bitmap.recycle()
                }
                SystemClock.sleep(10)
            }
            val after = CameraPipelineTelemetry.snapshot()
            val result = JSONObject().put("mode", mode.name).put("maxFrameGapMs", maxGap)
                .put("timingMeasured", !visualPass)
                .put("firstReplacementMs", firstReplacement).put("binds", after.bindStartedCount - before.bindStartedCount)
                .put("frameAgeMs", SystemClock.elapsedRealtime() - last)
                .put("transitionBlackFraction", if (visualPass) transitionBlackFraction else JSONObject.NULL)
                .put("freshFocus", (CameraControlTrace.focusSample?.atMs ?: 0L) > start)
                .put("manualAf", CameraControlTrace.focusSample?.afMode)
                .put("events", JSONArray(after.recentEvents.filter { it.substringBefore(' ').toLongOrNull()?.let { time -> time >= start } == true }))
            results.put(result)
            android.util.Log.i("ModeSwitchProbe", result.toString())
        }
        val rapidStart = SystemClock.elapsedRealtime()
        for (mode in listOf(CameraModeId.ProVideo, CameraModeId.Hyperlapse, CameraModeId.Pro,
            CameraModeId.Hyperlapse, CameraModeId.ProVideo, CameraModeId.Pro)) {
            instrumentation.runOnMainSync { model.dispatch(CameraCommand.ActivateModeRailEntry(
                CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode })) }
            SystemClock.sleep(250)
        }
        SystemClock.sleep(5000)
        val final = CameraPipelineTelemetry.snapshot()
        results.put(JSONObject().put("mode", "RapidToPro")
            .put("frameAgeMs", SystemClock.elapsedRealtime() - maxOf(final.lastDisplayedFrameAtMs, final.lastDirectPreviewFrameAtMs))
            .put("selectedMode", model.state.value.camera.activeMode.name)
            .put("freshFocus", (CameraControlTrace.focusSample?.atMs ?: 0L) > rapidStart)
            .put("manualAf", CameraControlTrace.focusSample?.afMode))
        return results
    }

    fun verify(results: JSONArray) {
        for (i in 0 until results.length()) {
            val row = results.getJSONObject(i)
            check(row.getLong("frameAgeMs") < 250) { "Preview stopped after switching: $row" }
            if (!row.isNull("transitionBlackFraction"))
                check(row.getDouble("transitionBlackFraction") < 0.95) { "Black transition: $row" }
            check(row.getBoolean("freshFocus") && row.getInt("manualAf") == 0) {
                "Switch lost manual focus: $row"
            }
            if (row.getString("mode") == "ProVideo")
                check(!row.getJSONArray("events").toString().contains("RUNTIME_FAILURE")) {
                    "Pro Video failed its capture configuration: $row"
                }
            if (row.has("selectedMode")) {
                check(row.getString("selectedMode") == "Pro") {
                    "Rapid switching failed to restore the selected manual-focus mode: $row"
                }
            }
        }
    }
}
