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
import com.mobiledivecontrol.core.BottomBarItem
import com.mobiledivecontrol.ui.currentCameraResolutionStatusLabel
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
        if (arguments.getString("thermalPreview") == "true") runThermalViewfinderProbe(arguments.getString("lens") ?: "1x")
        else runSlowMotionRecordingProbe(arguments)
    }
}

internal fun Instrumentation.runSlowMotionRecordingProbe(arguments: Bundle) {
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
            val mode = CameraModeId.valueOf(arguments.getString("mode") ?: "SlowMotion")
            val prefix = if (mode == CameraModeId.ProVideo) "pro_video" else "slow_motion"
            dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode }))
            SystemClock.sleep(2500)
            fun select(suffix: String, value: String) {
                val state = model.state.value.camera
                val spec = CameraCatalog.settingsFor(state).first { it.id == "$prefix.$suffix" }
                val current = CameraCatalog.currentValue(state, spec)
                require(value in spec.options) { "$suffix=$value unavailable: ${spec.options}" }
                if (current != value) {
                    dispatch(CameraCommand.NudgeSetting(spec.id,
                        spec.options.indexOf(value) - spec.options.indexOf(current)))
                    await("$suffix=$value") {
                        val camera = model.state.value.camera
                        CameraCatalog.settingsFor(camera).firstOrNull { it.id == spec.id }
                            ?.let { CameraCatalog.currentValue(camera, it) == value } == true
                    }
                    SystemClock.sleep(1800)
                }
            }
            val lens = arguments.getString("lens") ?: "1x"
            val initialCamera = model.state.value.camera
            val initialOptions = CameraCatalog.settingsFor(initialCamera).associate { it.id to CameraCatalog.currentValue(initialCamera, it) }
            select("lens", lens)
            await("video capabilities for $lens") { model.state.value.camera.capabilities?.videoCaptureLens == lens }
            if (mode == CameraModeId.ProVideo) { select("log", "Off"); select("hdr", "Off") }
            val requestedResolution = arguments.getString("resolution")?.replace('_', ' ') ?: "FHD"
            select("resolution", requestedResolution)
            if (arguments.getString("qualify") == "true" && 120 in model.state.value.camera.capabilities?.pendingUhdRates.orEmpty()) {
                val monitor = addMonitor(UhdRateTestActivity::class.java.name, null, false)
                val qualification = try {
                    dispatch(CameraCommand.VerifyUhdRates)
                    checkNotNull(waitForMonitorWithTimeout(monitor, 10_000)) { "UHD test screen did not open" }
                } finally { removeMonitor(monitor) }
                val verdict = try { measureUhdRate(qualification, 120) } finally { runOnMainSync { qualification.finish() } }
                report.put("qualification", verdict)
                check(verdict.startsWith("Verified")) { verdict }
                await("verified UHD 120 in the settings strip") {
                    120 in model.state.value.camera.capabilities?.verifiedDirectUhdRates.orEmpty()
                }
            }
            val camera = model.state.value.camera
            val offered = CameraCatalog.settingsFor(camera).single { it.id == "$prefix.frame_rate" }.options
            val rates = arguments.getString("rates")?.split(',')?.map(String::toInt)
                ?: listOf(120, 240, 60).filter { "${it}fps" in offered }
            report.put("lens", lens).put("resolution", requestedResolution)
                .put("requestedRates", JSONArray(rates)).put("offeredRates", JSONArray(offered))
            require(rates.isNotEmpty() && rates.all { "${it}fps" in offered }) {
                "$mode $lens $requestedResolution requested $rates FPS; selectable rates: $offered"
            }
            select(if (mode == CameraModeId.ProVideo) "exposure_value" else "exposure", arguments.getString("exposure") ?: "0.0")
            arguments.getString("torch")?.let { select("flash", it) }
            val clips = JSONArray()
            report.put("clips", clips)
            fun previewPhase(fps: Int, phase: String) {
                if (arguments.getString("previewProbe") == "true") {
                    sendStatus(0, Bundle().apply { putString("stream", "Preview phase $fps $phase\n") })
                    SystemClock.sleep(4500)
                }
            }
            for (fps in rates) {
                select("frame_rate", "${fps}fps")
                arguments.getString("playback")?.let { select("playback_fps", it) }
                val selected = model.state.value.camera
                if (mode == CameraModeId.SlowMotion) {
                    val rail = CameraCatalog.settingsBarItems(selected).filterIsInstance<BottomBarItem.Setting>()
                        .single { it.spec.id == "$prefix.frame_rate" }.spec
                    check(rail.options == offered && CameraCatalog.currentValue(selected, rail) == "${fps}fps")
                    check(currentCameraResolutionStatusLabel(selected)?.contains("$fps FPS ") == true)
                }
                arguments.getString("focus")?.let { select("focus_mode", it.replace('_', ' ')) }
                arguments.getString("zoom")?.toDoubleOrNull()?.let {
                    dispatch(CameraCommand.SetZoom(it))
                    SystemClock.sleep(500)
                }
                check(model.state.value.camera.settingValues["$prefix.lens"] == (arguments.getString("lens") ?: "1x"))
                if (arguments.getString("selectOnly") == "true") {
                    sendStatus(0, Bundle().apply {
                        putString("stream", "$lens: ${currentCameraResolutionStatusLabel(selected)}; rates=$offered\n")
                    })
                    continue
                }
                val previousSaved = latestSaved()
                previewPhase(fps, "before")
                sendStatus(0, Bundle().apply { putString("stream", "Capturing slow motion $fps fps\n") })
                val startAt = SystemClock.elapsedRealtime()
                dispatch(CameraCommand.StartVideoRecording)
                await("recorder start") { !RecordingClock.paused.value && RecordingClock.durationMs.value > 0L }
                val startLatencyMs = SystemClock.elapsedRealtime() - startAt
                SystemClock.sleep((arguments.getString("durationMs")?.toLong() ?: 4000L).coerceIn(1000L, 10000L))
                val selectedLens = arguments.getString("lens") ?: "1x"
                val expectedPhysical = when (selectedLens) { "0.6x" -> "2"; "1x" -> "5"; "3x" -> "6"; else -> null }
                val sample = CameraControlTrace.focusSample
                val sourceFps = if (mode == CameraModeId.SlowMotion) SlowMotionRecordingPolicy.sourceFrameRate(fps) else fps
                check(checkNotNull(sample?.frameDurationNs) <= 1_000_000_000.0 / sourceFps * 1.05) {
                    "Selected $fps fps but sensor cadence is $sample"
                }
                val expectedFocal = when (selectedLens) { "0.6x" -> 2.2f; "1x" -> 5.4f; "3x" -> 7f; else -> null }
                if (expectedPhysical != null) check(sample?.physicalId == expectedPhysical ||
                    (sample?.focalLength != null && kotlin.math.abs(sample.focalLength - checkNotNull(expectedFocal)) < 0.1f)) {
                    "Selected $selectedLens but recorded ${CameraControlTrace.focusSample}"
                }
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
                    val expectedSize = if (requestedResolution == "UHD 4K") 3840 to 2160 else 1920 to 1080
                    check(format.getInteger(MediaFormat.KEY_WIDTH) == expectedSize.first &&
                        format.getInteger(MediaFormat.KEY_HEIGHT) == expectedSize.second) { "Wrong saved resolution: $format" }
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
                    val measured = frames * 1000.0 / RecordingClock.durationMs.value
                    clip.put("captureAverageFps", measured)
                    if (mode == CameraModeId.SlowMotion) {
                        check(RecordingClock.reviewFrameCount.value == frames) { "Review count differs from the MP4's $frames samples" }
                        if (arguments.getString("verifyRate") == "true") {
                            check(kotlin.math.abs(measured / fps - 1.0) <= 0.05) { "Requested $fps FPS, recorded $measured FPS" }
                        }
                    }
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
            if (arguments.getString("restoreSettings") == "true") {
                for (suffix in listOf("lens", "resolution", "frame_rate", "playback_fps", "exposure")) {
                    initialOptions["$prefix.$suffix"]?.let { select(suffix, it) }
                }
                val restored = model.state.value.camera
                report.put("restoredHud", currentCameraResolutionStatusLabel(restored))
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

private fun Instrumentation.latestSaved(): Uri? {
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
