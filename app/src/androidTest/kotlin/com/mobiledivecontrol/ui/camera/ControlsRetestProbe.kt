package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.MediaStore
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object ControlsRetestProbe {
    fun run(test: Instrumentation, model: DiveViewModel, directory: File): JSONObject {
        fun dispatch(command: CameraCommand) = test.runOnMainSync { model.dispatch(command) }
        fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            check(condition()) { "$label: ${model.state.value.lastWarning}" }
        }
        fun select(suffix: String, value: String) {
            val camera = model.state.value.camera
            val spec = CameraCatalog.settingsFor(camera).first { it.id.endsWith(".$suffix") }
            check(value in spec.options) { "$suffix=$value not in ${spec.options}" }
            val current = CameraCatalog.currentValue(camera, spec)
            if (value != current) dispatch(CameraCommand.NudgeSetting(spec.id, spec.options.indexOf(value) - spec.options.indexOf(current)))
            SystemClock.sleep(1800)
            check(CameraCatalog.currentValue(model.state.value.camera, spec) == value) { "$suffix not applied" }
        }
        fun screen(name: String) {
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val resolver = test.targetContext.contentResolver
        fun rows(): Map<Long, String> = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING}=0",
            arrayOf("DiveControl_%"), null)!!.use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) } }
        dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == CameraModeId.Pro }))
        SystemClock.sleep(3000)
        select("lens", "1x")
        select("iso", "Auto")
        select("shutter_speed", "Auto")
        val report = JSONObject().put("photoSizes", JSONArray(CameraCatalog.settingsFor(model.state.value.camera).first { it.id == "pro.megapixels" }.options))
        for (suffix in listOf("iso", "shutter_speed", "manual_focus")) {
            var moves = 0
            while (model.state.value.camera.selectedSetting?.id != "pro.$suffix" && moves++ < 40) dispatch(CameraCommand.NavigateRight)
            check(model.state.value.camera.selectedSetting?.id == "pro.$suffix")
            dispatch(CameraCommand.Confirm)
            SystemClock.sleep(700)
            screen("menu-$suffix")
            dispatch(CameraCommand.Back)
        }
        val captures = JSONArray()
        report.put("captures", captures)
        for ((format, aspect) in listOf("JPEG" to "1:1", "PNG" to "16:9", "HEIF" to "4:3", "RAW + JPEG" to "4:3", "JPEG" to "Full")) {
            select("save_format", format)
            select("aspect_ratio", aspect)
            select("timer", if (format == "JPEG") "2s" else "Off")
            screen("aspect-${aspect.replace(':', '-')}")
            val before = rows().keys
            val started = SystemClock.elapsedRealtime()
            dispatch(CameraCommand.CapturePhoto)
            if (format == "JPEG") {
                await("countdown did not appear", 2000) { PhotoTimerState.remainingSeconds.intValue > 0 }
                screen("countdown")
                SystemClock.sleep(1000)
                check((rows().keys - before).isEmpty()) { "Timer saved a photo too early" }
            }
            val expected = if (format == "RAW + JPEG") 2 else 1
            await("$format output missing", 60_000) { (rows().keys - before).size == expected }
            SystemClock.sleep(500)
            val output = JSONObject().put("format", format).put("aspect", aspect).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            val files = JSONArray()
            for ((id, name) in rows().filterKeys { it !in before }) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val copy = File(directory, name)
                check(copy.extension in when (format) {
                    "PNG" -> setOf("png"); "HEIF" -> setOf("heic"); "RAW + JPEG" -> setOf("dng", "jpg"); else -> setOf("jpg")
                }) { "Selected $format but saved $name" }
                resolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                val header = copy.inputStream().use { it.readNBytes(16) }
                when (copy.extension) {
                    "png" -> check(header.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
                    "jpg" -> check(header[0] == (-1).toByte() && header[1] == (-40).toByte())
                    "heic" -> check(String(header, 4, 4) == "ftyp")
                    "dng" -> check(String(header, 0, 2) in listOf("II", "MM"))
                    else -> error("Unexpected extension $name")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.absolutePath, bounds)
                if (copy.extension != "dng") {
                    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "$name cannot decode" }
                    val actual = maxOf(bounds.outWidth, bounds.outHeight).toDouble() / minOf(bounds.outWidth, bounds.outHeight)
                    val expectedRatio = when (aspect) {
                        "1:1" -> 1.0; "16:9" -> 16.0/9
                        "Full" -> test.targetContext.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels).toDouble() / minOf(it.widthPixels, it.heightPixels) }
                        else -> 4.0/3
                    }
                    check(kotlin.math.abs(actual - expectedRatio) < 0.01) { "$name wrong crop $actual expected $expectedRatio" }
                    if (aspect == "4:3") check(bounds.outWidth.toLong() * bounds.outHeight >= 12_000_000L) { "12MP selected but output is ${bounds.outWidth}x${bounds.outHeight}" }
                }
                files.put(JSONObject().put("name", name).put("width", bounds.outWidth).put("height", bounds.outHeight).put("bytes", copy.length()))
                resolver.delete(uri, null, null)
            }
            output.put("files", files); captures.put(output)
            File(directory, "captures.json").writeText(report.toString(2))
        }
        return report.put("passed", true)
    }
}
