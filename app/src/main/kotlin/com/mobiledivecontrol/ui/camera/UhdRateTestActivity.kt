package com.mobiledivecontrol.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

internal fun matchesUhdCadence(fps: Int, sensorFps: Double, frames: Int, sampleSpanUs: Long, elapsedMs: Long): Boolean {
    if (fps <= 0 || frames < 2 || sampleSpanUs <= 0 || elapsedMs !in 3500..6500) return false
    val encodedFps = (frames - 1) * 1e6 / sampleSpanUs
    val wallClockFps = frames * 1000.0 / elapsedMs
    val minRate = if (fps == 120) 116.0 else fps * 0.96
    return sensorFps in minRate..(fps * 1.04) && encodedFps in minRate..(fps * 1.04) &&
        abs(wallClockFps / fps - 1) <= 0.08
}

/** Exposures per second of sensor time: the frame-number span over the timestamp span. */
internal fun sensorFrameRate(firstFrame: Long, lastFrame: Long, firstNs: Long, lastNs: Long): Double =
    if (firstFrame < 0 || lastFrame <= firstFrame || lastNs <= firstNs) 0.0
    else (lastFrame - firstFrame) * 1e9 / (lastNs - firstNs)

/** Presentation order can differ from MediaExtractor's sample order when the encoder uses B-frames. */
internal fun matchesUhdPresentationIntervals(fps: Int, timestampsUs: List<Long>): Boolean {
    if (fps <= 0 || timestampsUs.size < 2) return false
    val times = timestampsUs.sorted()
    val period = 1_000_000.0 / fps
    var irregular = 0
    for (index in 1 until times.size) {
        val gap = times[index] - times[index - 1]
        if (gap <= 0) return false
        if (gap < period * 0.5 || gap > period * 1.5) irregular++
    }
    // Allow occasional drops/jitter, but reject streams that deliver frames in pairs at half rate.
    return irregular * 20 <= times.lastIndex
}

internal data class UhdTrackTiming(val firstUs: Long, val lastUs: Long, val durationUs: Long) {
    fun fitsTake(elapsedMs: Long): Boolean {
        if (elapsedMs !in 3500..6500 || firstUs !in 0L..250_000L || lastUs <= firstUs) return false
        val elapsedUs = elapsedMs * 1000L
        val toleranceUs = maxOf(250_000L, elapsedUs * 8 / 100)
        val expected = (elapsedUs - toleranceUs)..(elapsedUs + toleranceUs)
        return durationUs in expected && lastUs - firstUs in expected
    }

    fun alignsWith(other: UhdTrackTiming) =
        abs(firstUs - other.firstUs) < 250_000 && abs(lastUs - other.lastUs) < 250_000 &&
            abs(durationUs - other.durationUs) < 250_000
}

/** User-initiated, foreground qualification with the same recorder, surfaces and codec as capture. */
class UhdRateTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private lateinit var recorder: PreparedCamera2Recorder
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private var profiles = emptyList<UhdCaptureProfile>()
    @Volatile private var generation = 0
    private var testing = false
    private var output: File? = null
    private val recordTestAudio: Boolean
        get() = intent.getBooleanExtra("recordAudio", true) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val heatListener = PowerManager.OnThermalStatusChangedListener {
        if (testing && it >= PowerManager.THERMAL_STATUS_MODERATE) cancelTest("Test stopped while the phone cools.")
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = (intent.getSerializableExtra("profiles") as? ArrayList<UhdCaptureProfile>).orEmpty()
        if (profiles.isEmpty()) { finish(); return }
        recorder = PreparedCamera2Recorder(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "Test UHD on the ${profiles.first().lens} lens. Aim at a moving subject in good light. Each test records a short private clip and deletes it afterwards." }
        layout.addView(status)
        preview = PreviewView(this)
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        buttons = LinearLayout(this)
        layout.addView(buttons)
        setContentView(layout)
        showButtons()
        power.addThermalStatusListener(mainExecutor, heatListener)
    }

    private fun showButtons() {
        buttons.removeAllViews()
        val audio = recordTestAudio
        val pending = profiles.groupBy { it.fps }.filterValues { routes -> routes.none { it.verified(this, audio) } }
        pending.forEach { (fps, routes) ->
            buttons.addView(Button(this).apply {
                text = "Test $fps FPS"
                setOnClickListener { tryRoute(routes.sortedBy { it.relay }) }
            })
        }
        buttons.addView(Button(this).apply { text = "Return to camera"; setOnClickListener { finish() } })
        buttons.visibility = View.VISIBLE
    }

    private fun tryRoute(routes: List<UhdCaptureProfile>) {
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            cancelTest("Let the phone cool before testing UHD."); return
        }
        val profile = routes.first()
        val token = ++generation
        testing = true
        buttons.visibility = View.GONE
        status.text = "Measuring UHD ${profile.fps} FPS on ${profile.lens}…"
        val audio = recordTestAudio
        val file = File.createTempFile("uhd-rate-", ".mp4", cacheDir)
        output = file
        var start = 0L
        var measuring = false
        var firstNs = 0L
        var lastNs = 0L
        var firstFrame = -1L
        var lastFrame = -1L
        var frames = 0
        val needsLensCheck = profile.fps >= 100 && profile.lens != "Auto"
        val manager = getSystemService(CameraManager::class.java)
        val children = if (needsLensCheck) runCatching {
            manager.getCameraCharacteristics(profile.cameraId).physicalCameraIds
        }.getOrElse { cancelTest("Camera information is unavailable. Try again when the camera is ready."); return } else emptySet()
        val expected = profile.expectedSensorId
        val focalLengths = if (needsLensCheck && expected in children) children.associateWith { id ->
            runCatching { manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
                ?.toList().orEmpty() }.getOrDefault(emptyList())
        } else emptyMap()
        val expectedFocals = focalLengths[expected].orEmpty()
        val otherFocals = focalLengths.filterKeys { it != expected }.values.flatten()
        val knownFocals = expectedFocals.isNotEmpty() && focalLengths.values.none { it.isEmpty() }
        var lensConfirmed = !needsLensCheck || (children.isEmpty() && (expected == null || expected == profile.cameraId))
        var lensMismatch = false
        recorder.onCaptureTelemetry = { result ->
            if (measuring && SystemClock.elapsedRealtime() - start >= 500) {
                if (needsLensCheck && expected != null && children.isNotEmpty()) {
                    val active = result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
                    val focal = result[CaptureResult.LENS_FOCAL_LENGTH]
                    if (profile.physicalId == expected && expected in result.physicalCameraResults) lensConfirmed = true
                    else if (active != null) {
                        if (active == expected) lensConfirmed = true else lensMismatch = true
                    } else if (focal != null) {
                        val matchesExpected = expectedFocals.any { abs(it - focal) < 0.05f }
                        val matchesOther = otherFocals.any { abs(it - focal) < 0.05f }
                        if (matchesExpected && !matchesOther && knownFocals) lensConfirmed = true
                        else if (!matchesExpected && matchesOther && expectedFocals.isNotEmpty()) lensMismatch = true
                    }
                }
                val ns = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0
                // High-speed bursts can report derived timestamps; frame numbers count exposures.
                if (ns > lastNs && result.frameNumber > lastFrame) {
                    if (frames == 0) { firstNs = ns; firstFrame = result.frameNumber }
                    lastNs = ns
                    lastFrame = result.frameNumber
                    frames++
                }
            }
        }
        fun finishAttempt(passed: Boolean, reason: String, tryNext: Boolean) {
            if (token != generation) return
            // Disarm all queued callbacks before releasing either surface.
            cancelTest(reason)
            if (passed) {
                profile.markVerified(this, audio)
                showButtons()
            } else if (tryNext && routes.size > 1) tryRoute(routes.drop(1))
        }
        handler.postDelayed({ if (token == generation) cancelTest("Camera unavailable or timed out. Try again when it is available.") }, 15_000)
        recorder.prepare(preview, profile.config(audio), PreparedCamera2Recorder.Controls(zoom = profile.zoom),
            onReady = {
                if (token == generation && start == 0L) recorder.start(file, onStarted = {
                    start = SystemClock.elapsedRealtime()
                    measuring = true
                    handler.postDelayed({ if (token == generation) {
                        measuring = false
                        // The take is complete. Release the camera and encoder before decoding it.
                        recorder.suspendPreview()
                    } }, 4500)
                }, onFinalized = { result ->
                    val elapsed = result.getOrNull()
                    if (token == generation && elapsed != null) {
                        if (profile.fps >= 100 && lastNs - firstNs < 2_000_000_000L) {
                            finishAttempt(false, "Insufficient sensor timing to verify UHD ${profile.fps} FPS.", true)
                            return@start
                        }
                        val sensorFps = sensorFrameRate(firstFrame, lastFrame, firstNs, lastNs)
                        if (!lensConfirmed || lensMismatch) {
                            finishAttempt(false, "Could not verify the selected ${profile.lens} lens.", true)
                            return@start
                        }
                        // Capture has finished. The decoder has its own deadline and cancellation check.
                        handler.removeCallbacksAndMessages(null)
                        reader.execute {
                            val timing = runCatching { verifyFile(file, profile, audio, elapsed, sensorFps) }.getOrDefault(false)
                            val motion = if (timing && profile.fps >= 100) runCatching {
                                checkUhdMotion(file, profile.fps) { token != generation }
                            }.getOrDefault(UhdMotionCheck.Inconclusive) else null
                            val passed = timing && (motion == null || motion == UhdMotionCheck.Passed)
                            val message = when {
                                passed -> "Verified UHD ${profile.fps} FPS on ${profile.lens}."
                                motion == UhdMotionCheck.TooLittleMotion -> "More movement needed. Aim at a moving subject in good light and test again."
                                motion == UhdMotionCheck.RepeatedFrames -> "The recording contains repeated frames at UHD ${profile.fps} FPS."
                                motion == UhdMotionCheck.Inconclusive -> "Could not verify distinct frames at UHD ${profile.fps} FPS."
                                else -> "This recording did not deliver UHD ${profile.fps} FPS."
                            }
                            handler.post { finishAttempt(passed, message, motion != UhdMotionCheck.TooLittleMotion) }
                        }
                    } else if (token == generation) finishAttempt(false, "Test recording could not be finalized.", false)
                })
            },
            onPresented = {},
            onError = { error -> finishAttempt(false, error.message ?: "Camera unavailable.",
                error is IllegalArgumentException || error.message == "Prepared camera session rejected") })
    }

    private fun verifyFile(file: File, profile: UhdCaptureProfile, audio: Boolean, elapsedMs: Long, sensorFps: Double): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val mime = if (profile.codec == TimeLapseVideoCodec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == mime }
            if (video < 0) return false
            val fmt = formats[video]
            val aac = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (audio != (aac >= 0) || (aac >= 0 && formats[aac].getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC)) return false
            if (!fmt.containsKey(MediaFormat.KEY_DURATION) ||
                (aac >= 0 && !formats[aac].containsKey(MediaFormat.KEY_DURATION))) return false
            extractor.selectTrack(video)
            if (aac >= 0) extractor.selectTrack(aac)
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            var audioFirst = Long.MAX_VALUE
            var audioLast = Long.MIN_VALUE
            var samples = 0
            val presentationTimes = if (profile.fps >= 100) ArrayList<Long>(profile.fps * 5) else null
            while (extractor.sampleTime >= 0) {
                val time = extractor.sampleTime
                if (extractor.sampleTrackIndex == video) {
                    first = minOf(first, time)
                    last = maxOf(last, time)
                    samples++
                    presentationTimes?.add(time)
                } else {
                    audioFirst = minOf(audioFirst, time)
                    audioLast = maxOf(audioLast, time)
                }
                extractor.advance()
            }
            val videoTiming = UhdTrackTiming(first, last, fmt.getLong(MediaFormat.KEY_DURATION))
            val audioTiming = if (aac >= 0) UhdTrackTiming(audioFirst, audioLast, formats[aac].getLong(MediaFormat.KEY_DURATION)) else null
            android.util.Log.i("DiveUhdTest", "UHD ${profile.fps} on ${profile.lens} relay=${profile.relay}: " +
                "size=${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)} sensorFps=$sensorFps " +
                "samples=$samples elapsedMs=$elapsedMs video=$videoTiming audio=$audioTiming")
            if (fmt.getInteger(MediaFormat.KEY_WIDTH) != 3840 || fmt.getInteger(MediaFormat.KEY_HEIGHT) != 2160) return false
            if (!videoTiming.fitsTake(elapsedMs)) return false
            if (audioTiming != null && (!audioTiming.fitsTake(elapsedMs) || !videoTiming.alignsWith(audioTiming))) return false
            return matchesUhdCadence(profile.fps, sensorFps, samples, last - first, elapsedMs) &&
                (presentationTimes == null || matchesUhdPresentationIntervals(profile.fps, presentationTimes))
        } finally { extractor.release() }
    }

    private fun cancelTest(message: String) {
        generation++
        testing = false
        handler.removeCallbacksAndMessages(null)
        recorder.release()
        output?.delete()
        output = null
        status.text = message
        showButtons()
    }

    override fun onPause() {
        if (testing) cancelTest("Test interrupted. Return to the camera or try again.")
        super.onPause()
    }

    override fun onDestroy() {
        if (::recorder.isInitialized) {
            power.removeThermalStatusListener(heatListener)
            recorder.dispose()
        }
        reader.shutdown()
        super.onDestroy()
    }
}
