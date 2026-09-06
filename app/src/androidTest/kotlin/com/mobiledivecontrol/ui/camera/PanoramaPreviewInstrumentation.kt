package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Runs the real activity and checks the visible full-screen image, including any Compose cover. */
class PanoramaPreviewInstrumentation : Instrumentation() {
    private var restoreExposure: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        restoreExposure = arguments?.getString("restoreExposure")
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val directory = File(targetContext.getExternalFilesDir(null),
            "panorama-preview-validation/${System.currentTimeMillis()}").apply { mkdirs() }
        val report = JSONObject()
        var model: DiveViewModel? = null
        var originalEv: String? = null
        var originalLens: String? = null
        fun dispatch(command: CameraCommand) = runOnMainSync { model!!.dispatch(command) }
        fun select(suffix: String, value: String) {
            val state = model!!.state.value.camera
            val setting = CameraCatalog.settingsFor(state).first { it.id == "panorama.$suffix" }
            check(value in setting.options) { "$value unavailable: ${setting.options}" }
            val current = CameraCatalog.currentValue(state, setting)
            if (current != value) dispatch(CameraCommand.NudgeSetting(setting.id,
                setting.options.indexOf(value) - setting.options.indexOf(current)))
        }
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {
                model = ViewModelProvider(activity)[DiveViewModel::class.java]
                model!!.dismissIntro()
            }
            dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst {
                it.mode == CameraModeId.Panorama
            }))
            SystemClock.sleep(3000)
            val initial = model!!.state.value.camera
            originalEv = CameraCatalog.currentValue(initial,
                CameraCatalog.settingsFor(initial).first { it.id == "panorama.exposure" })
            originalLens = CameraCatalog.currentValue(initial,
                CameraCatalog.settingsFor(initial).first { it.id == "panorama.lens" })
            restoreExposure?.let { originalEv = it }
            val cases = JSONArray()
            report.put("cases", cases)
            val lenses = if (restoreExposure != null) emptyList() else
                listOf(originalLens!!, "1x", "0.6x", "1x", "0.6x")
            for (lens in lenses) {
                select("lens", lens)
                select("exposure", "0.0")
                SystemClock.sleep(3000)
                lateinit var preview: PreviewView
                var firstTimestamp = 0L
                runOnMainSync {
                    preview = descendants(activity.window.decorView).filterIsInstance<PreviewView>().first()
                    firstTimestamp = descendants(preview).filterIsInstance<TextureView>().first()
                        .surfaceTexture!!.timestamp
                }
                val before = checkNotNull(uiAutomation.takeScreenshot())
                select("exposure", "-2.0")
                SystemClock.sleep(2000)
                val after = checkNotNull(uiAutomation.takeScreenshot())
                var lastTimestamp = 0L
                runOnMainSync {
                    lastTimestamp = descendants(preview).filterIsInstance<TextureView>().first()
                        .surfaceTexture!!.timestamp
                }
                val index = cases.length()
                File(directory, "$index-before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(directory, "$index-after.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // This clear patch is outside the mini panorama, text, grid and controls.
                var difference = 0.0
                var samples = 0
                for (y in (before.height * 0.41).toInt() until (before.height * 0.61).toInt() step 4) {
                    for (x in (before.width * 0.035).toInt() until (before.width * 0.16).toInt() step 4) {
                        val a = before.getPixel(x, y)
                        val b = after.getPixel(x, y)
                        for (shift in listOf(0, 8, 16)) difference += abs(((a shr shift) and 255) - ((b shr shift) and 255))
                        samples += 3
                    }
                }
                difference /= samples
                before.recycle(); after.recycle()
                cases.put(JSONObject().put("lens", lens).put("visibleMeanChannelDifference", difference)
                    .put("textureTimestampAdvanceNs", lastTimestamp - firstTimestamp))
                check(lastTimestamp > firstTimestamp) { "Underlying TextureView stalled on $lens" }
                check(difference > 2.0) { "Full-screen image did not react to exposure on $lens: $difference" }
                select("exposure", "0.0")
            }
            output.putString("result", report.toString())
        } catch (error: Throwable) {
            report.put("failure", android.util.Log.getStackTraceString(error))
            output.putString("failure", report.getString("failure"))
        } finally {
            runCatching {
                originalEv?.let { select("exposure", it) }
                originalLens?.let { select("lens", it) }
                // Session persistence is sampled asynchronously. Do not kill the instrumented
                // process while its last temporary exposure value is still on disk.
                SystemClock.sleep(1500)
            }.onFailure {
                report.put("failure", android.util.Log.getStackTraceString(it))
                output.putString("failure", report.getString("failure"))
            }
            File(directory, "report.json").writeText(report.toString(2))
            output.putString("directory", directory.absolutePath)
            finish(if (report.has("failure")) 1 else 0, output)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
