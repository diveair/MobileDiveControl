package com.mobiledivecontrol.ui.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.FirstRunOnboardingStore
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.service.HousingLinkService
import com.mobiledivecontrol.viewmodel.DiveViewModel
import kotlin.math.abs

/** Exercise actual recording, pause/finalize and review without publishing test footage. */
class VideoRateInstrumentation : Instrumentation() {
    private var cases = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cases = requireNotNull(arguments?.getString("cases"))
        start()
    }

    override fun onStart() {
        val summary = Bundle()
        var activity: MainActivity? = null
        try {
            check(targetContext.packageName.endsWith(".videoprobe"))
            targetContext.packageManager.setComponentEnabledSetting(ComponentName(targetContext, HousingLinkService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            FirstRunOnboardingStore(targetContext).markComplete()
            val screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            lateinit var vm: DiveViewModel
            runOnMainSync {
                vm = ViewModelProvider(screen)[DiveViewModel::class.java]
                vm.finishWelcome()
                vm.dismissIntro()
                vm.dismissCapPrompt()
            }
            fun dispatch(command: CameraCommand) = runOnMainSync { vm.dispatch(command) }
            fun await(label: String, predicate: () -> Boolean) {
                val limit = SystemClock.elapsedRealtime() + 12_000
                while (!predicate() && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(80)
                check(predicate()) { "$label: ${vm.state.value.lastWarning}" }
            }
            if ("qualifyUhd60" in cases.split(',')) {
                val profile = UhdCaptureProfile("0", null, "1x", 1f, 60, false, TimeLapseVideoCodec.HEVC)
                targetContext.getSharedPreferences("verified_uhd_capture", 0).edit().clear().commit()
                val prompt = startActivitySync(Intent(targetContext, UhdRateTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("profiles", arrayListOf(profile))) as UhdRateTestActivity
                try {
                    runOnMainSync {
                        val matches = arrayListOf<View>()
                        prompt.window.decorView.findViewsWithText(matches, "Test 60 FPS", View.FIND_VIEWS_WITH_TEXT)
                        check(matches.single().performClick())
                    }
                    await("UHD 60 qualification") { profile.verified(targetContext) }
                    summary.putString("qualifyUhd60", "PASS sensor and encoded UHD 60 cadence verified")
                } finally { runOnMainSync { prompt.finish() } }
            }
            val menuOnly = cases == "s24Menus" || cases == "s24SlowMenu"
            val selections = if (cases == "s24SlowMenu") listOf("SlowMotion:FHD:120:120") else if (menuOnly) listOf("Video:FHD:30:30", "ProVideo:FHD:30:30", "SlowMotion:FHD:120:120")
                else cases.split(',').filter { it != "qualifyUhd60" }
            for (case in selections) {
                val outcome = runCatching {
                    val (modeName, size, rate, playback) = case.split(':')
                    val lens = case.split(':').getOrNull(4) ?: "1x"
                    val mode = CameraModeId.valueOf(modeName)
                    val prefix = when (mode) { CameraModeId.ProVideo -> "pro_video"; CameraModeId.Video -> "video"; else -> "slow_motion" }
                    dispatch(CameraCommand.ActivateModeRailEntry(CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode }))
                    await("mode/capabilities") { vm.state.value.camera.let { it.activeMode == mode && !it.capabilities?.videoFrameRatesByResolution.isNullOrEmpty() } }
                    fun select(key: String, target: String) {
                        val camera = vm.state.value.camera
                        val spec = CameraCatalog.settingsFor(camera).first { it.id == "$prefix.$key" }
                        val before = CameraCatalog.currentValue(camera, spec)
                        check(target in spec.options) { "$key=$target unavailable at $size: ${spec.options}" }
                        if (target != before) dispatch(CameraCommand.NudgeSetting(spec.id, spec.options.indexOf(target) - spec.options.indexOf(before)))
                        check(vm.state.value.camera.settingValues[spec.id] == target || before == target)
                    }
                    select("lens", lens)
                    await("$lens capabilities") { vm.state.value.camera.capabilities?.videoCaptureLens == lens }
                    if (menuOnly) {
                        val snapshot = vm.state.value.camera
                        check(snapshot.capabilities?.galaxyS24VideoModes == true)
                        val sizes = CameraCatalog.settingsFor(snapshot).single { it.id == "$prefix.resolution" }.options
                        val menu = sizes.associateWith { selectedSize ->
                            select("resolution", selectedSize)
                            CameraCatalog.settingsFor(vm.state.value.camera).single { it.id == "$prefix.frame_rate" }.options
                        }
                        val expected = if (mode == CameraModeId.SlowMotion) buildMap {
                            put("FHD", listOf("120fps", "240fps"))
                            put("UHD 4K", listOf("60fps"))
                        } else mapOf("HD 720p" to listOf("30fps"), "FHD" to listOf("24fps", "30fps", "60fps", "120fps"),
                            "UHD 4K" to listOf("24fps", "30fps", "60fps"), "8K" to listOf("24fps", "30fps"))
                        check(menu == expected) { menu.toString() }
                        return@runCatching menu.toString()
                    }
                    val resolution = when(size) { "UHD" -> "UHD 4K"; "HD" -> "HD 720p"; else -> size }
                    select("resolution", resolution)
                    case.split(':').getOrNull(5)?.let { select("video_format", it) }
                    if (mode != CameraModeId.SlowMotion) select("aspect_ratio", "16:9")
                    select("frame_rate", rate + "fps")
                    select("playback_fps", if (playback == rate) CameraCatalog.MATCH_CAPTURE else playback + "fps")
                    SystemClock.sleep(1400)
                    dispatch(CameraCommand.StartVideoRecording)
                    await("record start") { RecordingClock.durationMs.value > 0 && !RecordingClock.paused.value }
                    SystemClock.sleep(if (lens in setOf("3x", "front") && size == "UHD") 4500 else 2100)
                    val sensor = CameraControlTrace.focusSample
                    dispatch(CameraCommand.PauseVideoRecording)
                    await("review") { RecordingClock.reviewUri.value != null && !RecordingClock.reviewFinalizing.value }
                    val reader = MediaExtractor()
                    try {
                        reader.setDataSource(targetContext, checkNotNull(RecordingClock.reviewUri.value), null)
                        val formats = (0 until reader.trackCount).map(reader::getTrackFormat)
                        val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        val audio = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        val fmt = formats[video]
                        reader.selectTrack(video)
                        var count = 0
                        var first = Long.MAX_VALUE
                        var last = Long.MIN_VALUE
                        var settledFirst = Long.MAX_VALUE
                        var settledCount = 0
                        while (reader.sampleTime >= 0) {
                            first = minOf(first, reader.sampleTime); last = maxOf(last, reader.sampleTime)
                            if (reader.sampleTime >= first + 500_000) {
                                settledFirst = minOf(settledFirst, reader.sampleTime)
                                settledCount++
                            }
                            count++
                            reader.advance()
                        }
                        val cadence = (count - 1) * 1e6 / (last - first)
                        val audioMs = audio.getLong(MediaFormat.KEY_DURATION) / 1000
                        val videoMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
                        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        val settledFps = if (settledCount < 2) null else (settledCount - 1) * 1e6 / (last - settledFirst)
                        val measured = "${w}x$h ${"%.2f".format(cadence)} playback FPS, $count frames, video/audio ${videoMs}/${audioMs}ms, sensor ${sensor?.frameDurationNs}ns, physical=${sensor?.physicalId}, focal=${sensor?.focalLength}mm, settled FPS=${settledFps?.let { "%.2f".format(it) }}"
                        check(w to h == when(size) { "UHD" -> 3840 to 2160; "8K" -> 7680 to 4320; "HD" -> 1280 to 720; else -> 1920 to 1080 } &&
                            abs(cadence - playback.toDouble()) < playback.toDouble() * 0.06 &&
                            sensor?.frameDurationNs?.let { abs(1e9 / it - rate.toDouble()) < rate.toDouble() * 0.06 } != false &&
                            abs(audioMs - videoMs) < maxOf(260L, videoMs / 12) &&
                            (lens != "3x" || sensor?.physicalId == "6")) { measured }
                        measured
                    } finally { reader.release() }
                }
                val line = outcome.fold({ "PASS $it" }, { "FAIL ${it.message}" })
                summary.putString(case, line)
                sendStatus(0, Bundle().apply { putString("stream", "$case $line\n") })
                if (vm.state.value.camera.recording) {
                    dispatch(CameraCommand.DeleteVideoRecording)
                    SystemClock.sleep(500)
                }
            }
        } catch (e: Throwable) { summary.putString("failure", e.stackTraceToString()) }
        finally { activity?.let { runOnMainSync { it.finish() } } }
        finish(if (summary.keySet().any { summary.getString(it)?.startsWith("PASS") != true }) Activity.RESULT_CANCELED else Activity.RESULT_OK, summary)
    }
}
