package com.mobiledivecontrol.ui.camera

import android.app.Instrumentation
import android.content.Intent
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercise both recording burst patterns on one session without overriding device thermal status. */
internal fun Instrumentation.runThermalViewfinderProbe(lens: String) {
    val report = Bundle()
    val sensor = when (lens) {
        "0.6x" -> "2"
        "1x" -> "5"
        else -> error("Unsupported UHD 120 lens: $lens")
    }
    val profile = checkNotNull(SamsungUhdSlowMotion.discover(targetContext, "0", sensor, lens))
    val power = targetContext.getSystemService(PowerManager::class.java)
    val activity = startActivitySync(Intent(targetContext, UhdRateTestActivity::class.java)
        .putExtra("profiles", arrayListOf(profile)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val recorder = PreparedCamera2Recorder(activity)
    val samples = ArrayBlockingQueue<TotalCaptureResult>(128)
    val ready = CompletableFuture<Unit>()
    val finished = CompletableFuture<Long>()
    val clip = File.createTempFile("thermal-preview-", ".mp4", targetContext.cacheDir)
    fun collect(count: Int): List<TotalCaptureResult> {
        SystemClock.sleep(400) // Allow queued results from the previous request to drain.
        samples.clear()
        return List(count) { checkNotNull(samples.poll(2, TimeUnit.SECONDS)) { "Camera stopped delivering results" } }
    }
    fun idle(fps: Int) {
        val durations = collect(8).map { checkNotNull(it[CaptureResult.SENSOR_FRAME_DURATION]) }.sorted()
        val measured = 1e9 / durations[durations.size / 2]
        check(kotlin.math.abs(measured - fps) < 2) { "Idle preview requested $fps FPS, sensor delivered $measured" }
        report.putDouble("idle${fps}Fps", measured)
    }
    fun burst(stride: Int) {
        val frames = collect(100)
        val counts = frames.groupingBy { it.request }.eachCount().values.sorted()
        check(counts.size == 2 && kotlin.math.abs(counts.first() - frames.size / stride) <= 1) {
            "Expected one preview in $stride requests, observed $counts"
        }
        val span = checkNotNull(frames.last()[CaptureResult.SENSOR_TIMESTAMP]) - checkNotNull(frames.first()[CaptureResult.SENSOR_TIMESTAMP])
        val fps = (frames.size - 1) * 1e9 / span
        check(fps in 108.0..126.0) { "Sensor cadence dropped to $fps" }
        report.putDouble("captureFpsStride$stride", fps)
    }
    try {
        check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE) { "Phone is cooling" }
        runOnMainSync {
            val host = PreviewView(activity)
            activity.setContentView(host)
            recorder.onCaptureTelemetry = { samples.offer(it) }
            recorder.prepare(host, profile.config(audio = true), PreparedCamera2Recorder.Controls(zoom = profile.zoom),
                onReady = { ready.complete(Unit) }, onPresented = {},
                onError = { ready.completeExceptionally(it); finished.completeExceptionally(it) })
        }
        ready.get(12, TimeUnit.SECONDS)
        idle(30)
        check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE) { "Phone is cooling" }
        runOnMainSync {
            recorder.start(clip, {}, { it.fold(finished::complete, finished::completeExceptionally) })
        }
        burst(4)
        check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) { "Phone is cooling" }
        runOnMainSync { recorder.reduceUhdViewfinderLoad(true) }
        burst(5)
        runOnMainSync { recorder.stop() }
        finished.get(8, TimeUnit.SECONDS)
        idle(24)
        runOnMainSync { recorder.reduceUhdViewfinderLoad(false) }
        idle(30)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(clip.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            check(formats.any { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC })
            val track = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC }
            val video = formats[track]
            check(video.getInteger(MediaFormat.KEY_WIDTH) == 3840 && video.getInteger(MediaFormat.KEY_HEIGHT) == 2160)
            extractor.selectTrack(track)
            val first = extractor.sampleTime
            var last = first
            var count = 1
            while (extractor.advance()) {
                check(extractor.sampleTime > last)
                last = extractor.sampleTime
                count++
            }
            val fps = (count - 1) * 1e6 / (last - first)
            check(fps in 108.0..126.0) { "File averaged $fps FPS during thermal transition" }
            report.putDouble("encodedFps", fps)
            report.putInt("frames", count)
        } finally { extractor.release() }
    } catch (error: Exception) {
        report.putString("failure", error.toString())
    } finally {
        runOnMainSync { recorder.dispose(); activity.finish() }
        clip.delete()
    }
    finish(if (report.containsKey("failure")) 1 else 0, report)
}
