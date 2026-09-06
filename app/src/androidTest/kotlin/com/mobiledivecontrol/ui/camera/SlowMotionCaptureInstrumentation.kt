package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.content.ContentUris
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Explicit device test: runs the real slow-motion UI/reducer/recorder and retains private samples. */
class SlowMotionCaptureInstrumentation : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = JSONObject()
        val directory = File(targetContext.getExternalFilesDir(null), "slow-motion-validation/${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            val manager = targetContext.getSystemService(CameraManager::class.java)
            report.put("cameras", JSONArray().apply {
                manager.cameraIdList.forEach { id ->
                    val c = manager.getCameraCharacteristics(id)
                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    put(JSONObject().apply {
                        put("id", id)
                        put("zoom", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE).toString())
                        put("af", c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString())
                        put("antibanding", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.joinToString())
                        put("evRange", c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).toString())
                        put("evStep", c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).toString())
                        put("normalRates", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString())
                        put("highSpeed", map?.highSpeedVideoSizes?.joinToString { size ->
                            "$size:${map.getHighSpeedVideoFpsRangesFor(size).joinToString()}"
                        })
                        put("requestKeys", JSONArray(c.availableCaptureRequestKeys.map { it.name }))
                        put("faceDetectModes", c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.joinToString())
                    })
                }
            })
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var model: DiveViewModel
            runOnMainSync { model = ViewModelProvider(activity)[DiveViewModel::class.java] }
            fun dispatch(command: CameraCommand) = runOnMainSync { model.dispatch(command) }
            runOnMainSync { model.dismissIntro() }
            dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst {
                it.mode == CameraModeId.SlowMotion
            }))
            SystemClock.sleep(2500)
            fun select(suffix: String, value: String) {
                val state = model.state.value.camera
                val spec = CameraCatalog.settingsFor(state).first { it.id == "slow_motion.$suffix" }
                val current = CameraCatalog.currentValue(state, spec)
                require(value in spec.options) { "$suffix=$value unavailable: ${spec.options}" }
                if (current != value) dispatch(CameraCommand.NudgeSetting(spec.id,
                    spec.options.indexOf(value) - spec.options.indexOf(current)))
                SystemClock.sleep(1800)
                check(model.state.value.camera.settingValues[spec.id] == value) {
                    "$suffix did not accept $value: ${model.state.value.camera.settingValues[spec.id]}"
                }
            }
            select("lens", arguments.getString("lens") ?: "1x")
            val requestedResolution = arguments.getString("resolution")?.replace('_', ' ') ?: "FHD"
            if (requestedResolution != "FHD") select("frame_rate", "60fps")
            select("resolution", requestedResolution)
            select("exposure", arguments.getString("exposure") ?: "0.0")
            arguments.getString("torch")?.let { select("flash", it) }
            val clips = JSONArray()
            report.put("clips", clips)
            val rates = (arguments.getString("rates") ?: "120,240,60,48").split(',').map(String::toInt)
            fun previewPhase(fps: Int, phase: String) {
                if (arguments.getString("previewProbe") == "true") {
                    sendStatus(0, Bundle().apply { putString("stream", "Preview phase $fps $phase\n") })
                    SystemClock.sleep(4500)
                }
            }
            for (fps in rates) {
                select("frame_rate", "${fps}fps")
                arguments.getString("focus")?.let { select("focus_mode", it.replace('_', ' ')) }
                arguments.getString("zoom")?.toDoubleOrNull()?.let {
                    dispatch(CameraCommand.SetZoom(it))
                    SystemClock.sleep(500)
                }
                val previousSaved = latestSaved()
                previewPhase(fps, "before")
                sendStatus(0, Bundle().apply { putString("stream", "Capturing slow motion $fps fps\n") })
                val startAt = SystemClock.elapsedRealtime()
                dispatch(CameraCommand.StartVideoRecording)
                await("recorder start") { !RecordingClock.paused.value && RecordingClock.durationMs.value > 0L }
                val startLatencyMs = SystemClock.elapsedRealtime() - startAt
                SystemClock.sleep((arguments.getString("durationMs")?.toLong() ?: 4000L).coerceIn(1000L, 10000L))
                val pauseAt = SystemClock.elapsedRealtime()
                dispatch(CameraCommand.PauseVideoRecording)
                await("review", 30000) { RecordingClock.reviewUri.value != null && !RecordingClock.reviewFinalizing.value }
                val reviewLatencyMs = SystemClock.elapsedRealtime() - pauseAt
                if (arguments.getString("resume") == "true") {
                    val firstDuration = RecordingClock.durationMs.value
                    dispatch(CameraCommand.PreviewVideoRecording)
                    SystemClock.sleep(arguments.getString("reviewDelayMs")?.toLong()?.coerceAtLeast(0L) ?: 700L)
                    dispatch(CameraCommand.ResumeVideoRecording)
                    await("resume") { !RecordingClock.paused.value && RecordingClock.durationMs.value > firstDuration }
                    SystemClock.sleep(1500)
                    dispatch(CameraCommand.PauseVideoRecording)
                    await("second review", 30000) { RecordingClock.reviewUri.value != null && !RecordingClock.reviewFinalizing.value }
                    check(RecordingClock.durationMs.value > firstDuration + 1000)
                }
                val source = File(checkNotNull(RecordingClock.reviewUri.value?.path))
                val copy = source.copyTo(File(directory, "${fps}fps-review.mp4"), overwrite = true)
                val extractor = MediaExtractor()
                val clip = JSONObject().put("fps", fps).put("captureDurationMs", RecordingClock.durationMs.value)
                    .put("reviewSpeed", RecordingClock.reviewPlaybackSpeed.value).put("file", copy.name)
                    .put("startLatencyMs", startLatencyMs).put("reviewLatencyMs", reviewLatencyMs)
                try {
                    extractor.setDataSource(copy.absolutePath)
                    val track = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    val format = extractor.getTrackFormat(track)
                    clip.put("format", format.toString())
                    extractor.selectTrack(track)
                    var frames = 0
                    var previousUs = -1L
                    var backwards = 0
                    while (extractor.sampleTime >= 0L) {
                        val time = extractor.sampleTime
                        if (previousUs >= time) backwards++
                        previousUs = time
                        frames++
                        if (!extractor.advance()) break
                    }
                    check(frames > 0) { "Empty $fps fps recording" }
                    check(backwards == 0) { "Non-monotonic playback timestamps at $fps fps" }
                    clip.put("frames", frames).put("lastTimestampUs", previousUs)
                } finally { extractor.release() }
                clips.put(clip)
                report.put("settings", JSONObject(model.state.value.camera.settingValues))
                File(directory, "report.json").writeText(report.toString(2))
                sendStatus(0, Bundle().apply { putString("stream", "$clip\n") })
                dispatch(CameraCommand.PreviewVideoRecording)
                SystemClock.sleep(700)
                if (arguments.getString("save") == "true") {
                    dispatch(CameraCommand.StopVideoRecording)
                    await("save", 90000) { latestSaved()?.let { it != previousSaved } == true }
                    val saved = checkNotNull(latestSaved())
                    val savedFile = File(directory, "${fps}fps-saved.mp4")
                    targetContext.contentResolver.openInputStream(saved)!!.use { input ->
                        savedFile.outputStream().use { input.copyTo(it) }
                    }
                    clip.put("savedFile", savedFile.name)
                    // Only remove the exact row generated by this test after retaining its bytes.
                    targetContext.contentResolver.delete(saved, null, null)
                } else {
                    dispatch(CameraCommand.DeleteVideoRecording)
                    await("delete") { !model.state.value.camera.recording }
                }
                previewPhase(fps, "after")
            }
            report.put("result", "passed")
            File(directory, "report.json").writeText(report.toString(2))
            finish(0, Bundle().apply { putString("report", directory.absolutePath) })
        } catch (error: Throwable) {
            report.put("failure", android.util.Log.getStackTraceString(error))
            File(directory, "report.json").writeText(report.toString(2))
            finish(1, Bundle().apply { putString("failure", error.toString()); putString("report", directory.absolutePath) })
        }
    }

    private fun latestSaved(): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return targetContext.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING}=0",
            arrayOf("DiveControl_%"), "${MediaStore.Video.Media._ID} DESC")?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun await(label: String, timeoutMs: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        check(condition()) { "Timed out waiting for $label" }
    }
}
