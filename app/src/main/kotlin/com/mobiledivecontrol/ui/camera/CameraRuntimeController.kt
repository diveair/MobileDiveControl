package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.CamcorderProfile
import android.media.MediaCodecList
import android.media.MediaFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.Rational
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mobiledivecontrol.core.CameraCapabilities
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.core.AutofocusHoldPolicy
import com.mobiledivecontrol.core.FocusCurveMode
import com.mobiledivecontrol.core.SamsungLogProfile
import com.mobiledivecontrol.core.UnderwaterFrameObservation
import com.mobiledivecontrol.core.UnderwaterWhiteBalanceEstimator
import com.mobiledivecontrol.core.UnderwaterWhiteBalanceInput
import com.mobiledivecontrol.core.UnderwaterWhiteBalanceSolution
import com.mobiledivecontrol.core.WhiteBalanceChromaticity
import com.mobiledivecontrol.ui.components.depthMetersFromPressure
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
class CameraRuntimeController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveCameraCtrl"
        private const val HIGH_SPEED_FPS_MIN = 100
        private const val METADATA_SIDECAR_RELATIVE_PATH =
            "Download/Mobile Dive Control/Metadata/"
        private val recorderFrameRateCandidates = setOf(24, 25, 30, 48, 50, 60, 90)
        private val recorderQualityLabels = listOf(
            Quality.SD to "SD 480p",
            Quality.HD to "HD 720p",
            Quality.FHD to "FHD",
            Quality.UHD to "UHD 4K",
        )
        private const val RESUME_STREAM_CHECK_DELAY_MS = 2_500L
        private const val DEFAULT_HORIZONTAL_FOV_DEGREES = 70.0

        /** The native app's vendor "manual kelvin" AWB mode (AeAfController branch B). */
        /** XYZ (D65) to linear sRGB. */
        private val XYZ_TO_SRGB = arrayOf(
            doubleArrayOf(3.2404542, -1.5371385, -0.4985314),
            doubleArrayOf(-0.9692660, 1.8760108, 0.0415560),
            doubleArrayOf(0.0556434, -0.2040259, 1.0572252),
        )

        /** Bradford chromatic-adaptation matrix and its inverse. */
        private val BRADFORD = arrayOf(
            doubleArrayOf(0.8951, 0.2664, -0.1614),
            doubleArrayOf(-0.7502, 1.7135, 0.0367),
            doubleArrayOf(0.0389, -0.0685, 1.0296),
        )
        private val BRADFORD_INV = arrayOf(
            doubleArrayOf(0.9869929, -0.1470543, 0.1599627),
            doubleArrayOf(0.4323053, 0.5183603, 0.0492912),
            doubleArrayOf(-0.0085287, 0.0400428, 0.9684867),
        )

        /** The D65 white point in XYZ. */
        private val D65_WHITE = doubleArrayOf(0.95047, 1.0, 1.08883)

        /**
         * How far (kelvin) beyond harvested AWB data the manual dial may still play the
         * harvested curve before handing over to the calibrated pipeline. Wide enough to bridge
         * the estimate's wander, narrow enough that far extrapolation never fakes coverage.
         */
        /**
         * How far (mired) beyond the harvested span the AWB-curve correction takes to fade to
         * zero. The 110-mired version let the harvest centre's correction reach most of the
         * dial (53% weight at 8100K), dragging every distant kelvin toward the auto estimate's
         * own correction — the stable-light native comparison measured that as a systematic
         * 3-10% undershoot across 6800-10000K. With the backbone LUT now fitted from
         * interleaved native pairs the backbone is accurate on its own, so the fade only has
         * to bridge the last few percent around the span — 40 mired keeps the hand-off smooth
         * without exporting the harvest correction across the dial. (The old 45-mired hump at
         * 4300-5300K came from an UNFITTED backbone disagreeing with the harvest, not from the
         * width itself.)
         */
        private const val WB_HARVEST_FADE_MIRED = 40.0

        /** How long WB must have been continuously auto before its results are trusted as AWB truth. */
        private const val WB_AUTO_SETTLE_MS = 1_500L
        /** Never lose a shutter press if a device fails to acknowledge AWB lock promptly. */
        private const val WB_SHUTTER_LOCK_TIMEOUT_MS = 600L
        /** AU colour statistics share the analysis stream without contending with gesture work. */
        private const val UNDERWATER_ANALYSIS_INTERVAL_MS = 125L
        private const val UNDERWATER_REQUEST_INTERVAL_MS = 250L
        private const val UNDERWATER_COMMAND_KELVIN_EPSILON = 25
        private const val UNDERWATER_COMMAND_DUV_EPSILON = 0.00025
        private const val MACRO_STOP_REBIND_DEBOUNCE_MS = 450L

        /** How far the probe steps to decide which way the subject lies. */
        private const val AF_PROBE_STEP = 0.05

        /** Time for the lens and the analysis stream to catch up with the probe. */
        private const val AF_PROBE_SETTLE_MS = 90L

        /** Contrast must fall to this fraction of the best seen before the peak counts as passed. */
        private const val AF_PEAK_DROP = 0.82

        /** ...and it must stay fallen for this many frames, so noise cannot end a search. */
        private const val AF_PEAK_CONFIRM = 4

        /** Subsampling stride of the focus measure. */
        private const val SHARPNESS_STRIDE = 4

        /** Keep native CAF active until the housing has been still for this long. */
        private const val AF_MOTION_SETTLE_MS = 320L

        /** Held-plane monitoring is cheap but still bounded; gesture inference owns the stream. */
        private const val AF_HOLD_MONITOR_INTERVAL_MS =
            AutofocusHoldPolicy.HOLD_MONITOR_INTERVAL_MS

        /** Ignore only the first settling frames after a lock, not an entire subject change. */
        private const val AF_HOLD_MONITOR_GRACE_MS =
            AutofocusHoldPolicy.HOLD_MONITOR_GRACE_MS

        /** Give continuous-video AF a short, smooth convergence window before locking it. */
        private const val AF_LOCK_SETTLE_MS = 650L

        /** Some HALs omit the final AF state; a one-shot START still locks by contract. */
        private const val AF_LOCK_RESULT_TIMEOUT_MS = 900L

        /** One focus tick per frame-ish: the glide reads as continuous motion, not steps. */
        private const val FOCUS_SLEW_TICK_MS = 16L

        /** Below this the move is a single dial step: apply it straight, no glide. */
        private const val GLIDE_MIN_JUMP = 0.015

        /** Full-rack duration at ramp level 100 — as fast as a pull can be and still read as one. */
        private const val RACK_FASTEST_MS = 250L

        /** Full-rack duration at ramp level 1 — a slow, deliberate cinematic rack. */
        private const val RACK_SLOWEST_MS = 6_000L

        /** Even the shortest pull gets this long, so small corrections still glide. */
        private const val MIN_RACK_MS = 160L

        /** Hard ceiling on rack ticks, so a pathological value cannot stall focus. */
        private const val MAX_RACK_TICKS = 120
    }

    private data class SessionSignature(
        val flash: String?,
        val exposure: String?,
        val lens: String?,
        val hdrLog: String?,
        val whiteBalance: String?,
        val filter: String?,
        val manualFocus: String?,
        val iso: String?,
        val shutter: String?,
        val resolution: String?,
        /**
         * In the signature because SENSOR_FRAME_DURATION is derived from it: without this field
         * an fps change under an unchanged manual shutter early-returns on the signature latch
         * and the repeating request keeps the OLD frame pin — a 30 fps stream under a HUD that
         * reads 60fps.
         */
        val frameRate: String?,
        val metering: String?,
        val stabilization: String?,
        val waterPressureKpa: Double?,
        val atmosphericPressureKpa: Double?,
        /** AU's high-rate result is transient, so its commandable values belong in this latch. */
        val underwaterKelvin: Int?,
        val underwaterTintTenThousandths: Int?,
    )

    private data class CaptureMetadataSnapshot(val json: String)

    private data class PhysicalLensProfile(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val facing: Int,
        val focalLengthMm: Float,
        val minFocusDistance: Float,
        val afModes: Set<Int>,
        val vendorFocusLensRange: IntRange?,
        val vendorFocusCalibration: List<Int>,
    ) {
        val supportsManualFocus: Boolean
            get() = minFocusDistance > 0f
    }

    private enum class ManualFocusTransport {
        Fixed,
        PublicDiopter,
        SamsungLensPosition,
        Hybrid,
    }

    private data class LensFocusCapabilityProfile(
        val lensValue: String,
        val transport: ManualFocusTransport,
        val minFocusDistance: Float,
        val vendorFocusLensRange: IntRange? = null,
        val vendorFocusCalibration: List<Int> = emptyList(),
    ) {
        val supportsManualFocus: Boolean
            get() = transport != ManualFocusTransport.Fixed

        val usesPublicDiopters: Boolean
            get() = transport == ManualFocusTransport.PublicDiopter || transport == ManualFocusTransport.Hybrid

        val usesVendorLensPosition: Boolean
            get() = transport == ManualFocusTransport.SamsungLensPosition || transport == ManualFocusTransport.Hybrid
    }

    private data class ManualFocusRequest(
        val normalizedFocus: Double,
        val transport: ManualFocusTransport,
        val diopters: Float? = null,
        val vendorLensPosition: Int? = null,
        val vendorFocusValue: Float? = null,
    )

    private data class BackCameraProfile(
        val logicalCameraId: String,
        val logicalFocalLengthMm: Float,
        val physicalLenses: List<PhysicalLensProfile>,
        val maxSupportedResolution: Size?,
    )

    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val camera2HighSpeedRecorder = Camera2HighSpeedRecorder(context)
    private val highSpeedClockRunnable = object : Runnable {
        override fun run() {
            if (!camera2HighSpeedRecorder.isBusy) return
            currentRecordingSegmentDurationMs = camera2HighSpeedRecorder.elapsedDurationMs
            RecordingClock.durationMs.value =
                completedRecordingDurationMs + currentRecordingSegmentDurationMs
            cameraRequestHandler.postDelayed(this, 100L)
        }
    }
    /** Duration is cumulative across continuation clips within one logical recording session. */
    private var completedRecordingDurationMs = 0L
    private var currentRecordingSegmentDurationMs = 0L
    private var recordingSegmentFinalizingForReview = false
    private var recordingSessionActive = false
    private var recordingSegmentStartGeneration = 0
    private var recordingSessionDirectory: File? = null
    private var recordingSessionDisplayName: String? = null
    private val recordingSegmentFiles = mutableListOf<File>()
    private var activeRecordingSegmentFile: File? = null
    private var recordingReviewFile: File? = null
    private var recordingMetadataSnapshot: CaptureMetadataSnapshot? = null
    private val recordingFinalizeExecutor = Executors.newSingleThreadExecutor()
    /** Resume/Stop/Delete pressed before CameraX returns the finalised segment URI. */
    private var pendingRecordingAction: CameraCommand? = null
    private var onCapabilities: ((CameraCapabilities) -> Unit)? = null
    private var onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null

    /**
     * The EV index window writes are clamped to: the vendor aeCompensationRange where it
     * resolves ([-40,40] = +/-4.0 EV on this hardware, the native Pro dial's real span), else
     * the public range. Kept in INDEX units because CONTROL_AE_EXPOSURE_COMPENSATION is an
     * index; the write path must NOT use CameraX's exposureCompensationRange, which only ever
     * knows the public window.
     */
    @Volatile private var evCompensationIndexRange: android.util.Range<Int>? = null

    /**
     * Samsung vendor RESULT tags behind the native Auto readouts: the HAL's live AWB kelvin
     * estimate and the metered EV deviation. Resolved by name through the global vendor tag
     * descriptor; on hardware where construction or the first read throws, the slot is nulled so
     * the cost is paid once and the readout simply stays absent.
     */
    private var vendorColorTempResultKey: CaptureResult.Key<Int>? = null
    private var vendorEvMeterResultKey: CaptureResult.Key<Int>? = null
    private var vendorResultKeysProbed = false
    private var boundLensFacing: Int? = null
    private var boundLensValue: String? = null
    private var boundResolution: String? = null
    private var boundFrameRate: String? = null
    private var boundHdrLogMode: String? = null
    private var boundCaptureFormat: String? = null
    private var boundAspectRatio: String? = null
    private var boundExpectedPhysicalCameraId: String? = null
    private var boundHdrExtension: Boolean = false
    @Volatile private var boundLogCaptureContractSatisfied: Boolean = false
    @Volatile private var maximumInformationRequestModes = MaximumInformationRequestModes()
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    @Volatile private var latestState: CameraState = CameraState()
    /**
     * Transient capture state, deliberately outside persisted [CameraState]. Auto Shutter meters
     * exactly like continuous AWB until the physical shutter is pressed, then this bit is applied
     * to repeating and single capture requests until the photo completes or recording finalizes.
     */
    @Volatile private var shutterAwbLockActive = false
    private var shutterAwbLockInFlight = false
    private var shutterAwbLockGeneration = 0
    @Volatile private var latestWaterPressureKpa: Double? = null
    @Volatile private var latestAtmosphericPressureKpa: Double? = null
    @Volatile private var latestWaterTemperatureC: Double? = null
    @Volatile private var latestHeadingDegrees: Double? = null

    /** Barometric reading captured with the suction cover open. The only valid depth reference. */
    @Volatile private var latestSurfaceAmbientKpa: Double? = null
    private var frontCameraId: String? = null
    private var frontCameraMinFocusDistance: Float = 0f
    private var backCameraProfile: BackCameraProfile? = null
    private var backLensAssignments: Map<String, PhysicalLensProfile> = emptyMap()
    private var lensFocusCapabilities: Map<String, LensFocusCapabilityProfile> = emptyMap()
    private var activeLensProfile: PhysicalLensProfile? = null
    private val failedDirectPhysicalCameraIds = mutableSetOf<String>()
    private val unsupportedPhysicalRequestTargets = mutableSetOf<String>()

    // Device capabilities detected once at attach time via CameraManager
    private var deviceMinFocusDistance: Float = 10f // Safe default — most phones have ~10 diopters
    private var deviceHasVendorHdr: Boolean = false
    private var deviceMaxSupportedResolution: Size? = null
    private var capabilitiesDetected: Boolean = false
    private var focusAssistEnabled: Boolean = false
    private var lastFocusResultLogAtMs: Long = 0L
    private var lastAppliedSessionSignature: SessionSignature? = null
    private val focusAssistExecutor = Executors.newSingleThreadExecutor()
    private val underwaterTraceExecutor = Executors.newSingleThreadExecutor()
    private val underwaterTrace = UnderwaterWhiteBalanceTrace(
        File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics"),
    )
    private val underwaterFrameAnalyzer = UnderwaterFrameAnalyzer()
    private val underwaterWbEstimator = UnderwaterWhiteBalanceEstimator()
    /** Latest estimate and the separately throttled white point actually commanded to Camera2. */
    @Volatile private var underwaterWbSolution: UnderwaterWhiteBalanceSolution? = null
    @Volatile private var underwaterCommandSolution: UnderwaterWhiteBalanceSolution? = null
    @Volatile private var lastUnderwaterObservation: UnderwaterFrameObservation? = null
    @Volatile private var underwaterApplyPosted = false
    @Volatile private var underwaterCaptureFrozen = false
    private var lastUnderwaterAnalysisAtMs = 0L
    private var lastUnderwaterCommandAtMs = 0L
    // Callback to report detected lenses back to the ViewModel/state
    private var onDetectedLenses: ((List<String>) -> Unit)? = null
    private var onPointingGesture: ((PointingGesture) -> Unit)? = null
    private var pointingRecognizer: PointingGestureRecognizer? = null
    // GPU-accelerated focus peaking via OpenGL shader in the CameraX preview pipeline.
    // Replaces the old CPU bitmap overlay approach which caused jitter and drift.
    private var focusPeakingProcessor: FocusPeakingSurfaceProcessor? = null
    private val cameraRequestHandler = Handler(Looper.getMainLooper())
    private var photoTimerGeneration = 0
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var focusMotionMonitorRegistered = false
    @Volatile private var lastSignificantFocusMotionAtMs = 0L

    private val focusMotionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
            if (!AutofocusHoldPolicy.isIntentionalCameraMotion(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                )
            ) return
            if (manualFocusRequestFor(latestState) != null) return
            val now = SystemClock.elapsedRealtime()
            lastSignificantFocusMotionAtMs = now
            // Release on the first deliberate movement, while the new subject is entering the
            // frame. Waiting until motion stopped was the responsiveness regression: the lens
            // could not even begin following until the entire turn plus 320 ms had elapsed.
            // Subsequent gyro samples only extend the relock deadline; they do not resubmit AF.
            releaseAutofocusForTracking("camera movement", AF_MOTION_SETTLE_MS)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ── Native Camera2 focus control ──────────────────────────────────────
    // When manual focus is active we take FULL ownership of the Camera2
    // CameraCaptureSession repeating request. CameraX is NOT called for any
    // CameraControl operations while in direct mode, so it never fights back.
    // When auto focus is active, CameraX gets full control back.
    @Volatile private var cam2Session: CameraCaptureSession? = null
    @Volatile private var cam2Surfaces: List<android.view.Surface> = emptyList()
    @Volatile private var nativeFocusActive: Boolean = false

    private val sessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            super.onCaptureCompleted(session, request, result)
            // Constrained high-speed capture owns a separate Camera2 session and forces 3A auto.
            // Keep harvesting preview telemetry, but never let a late CameraX callback take over
            // its session or submit a request to a CameraDevice that the rebind already closed.
            val directHighSpeedSelected = isHighSpeedSelection(latestState)
            // Capture session + surfaces on first callback or session change
            if (!directHighSpeedSelected && cam2Session !== session) {
                cam2Session = session
                try {
                    val m = CaptureRequest::class.java.getDeclaredMethod("getTargets")
                    m.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    cam2Surfaces = (m.invoke(request) as? Collection<android.view.Surface>)?.toList() ?: emptyList()
                    Log.d(TAG, "Native: captured session + ${cam2Surfaces.size} surfaces")
                } catch (e: Exception) {
                    Log.w(TAG, "Native: cannot capture surfaces: ${e.message}")
                }
                // Session is now available — re-apply the current state so that
                // manual focus (which requires the native session) takes effect.
                // This fixes the startup race where applySessionState runs before
                // the session exists.
                lastAppliedSessionSignature = null
                applySessionState(latestState, force = true)
                // A session opening in auto has no diver-set plane to preserve, so give it one
                // search; from then on only movement re-triggers.
                if (manualFocusRequestFor(latestState) == null && afHoldDiopters == null) {
                    // Nothing to inherit at session start: track once, then settle and hold.
                    startAutofocusTracking("session start")
                }
            }
            // Live pipe telemetry, sampled EVERY frame (cheap reads): the lens's true plane
            // seeds the AF-to-manual glide, the HAL's auto colour transform anchors manual
            // white balance, and the AE-chosen pair completes semi-manual ISO/shutter.
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastObservedFocusDiopters = it }
            vendorLensCurrentInfoKey()?.let { key ->
                result.get(key)?.let { info ->
                    // currentInfo[3] is the live VCM position — the only truthful readback on
                    // the vendor focus path (LENS_FOCUS_DISTANCE is meaningless while the
                    // vendor key drives the lens). Samsung reads the same element.
                    if (info.size > 3) lastObservedVendorLensPos = info[3]
                }
            }
            // AWB truth is latched ONLY while white balance is genuinely auto. While a manual
            // kelvin or the underwater filter drives the request, these results echo our own
            // writes — latching them would poison the anchor with our own output.
            val observedColorGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            observedColorGains?.let { lastObservedColorGains = it }
            if (wbIsAuto(latestState)) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastAutoColorTransform = it }
                observedColorGains?.let { lastAutoColorGains = it }
            }
            // The exposure envelope CameraX negotiated. Our own request must inherit it, or the
            // frame rate and flicker bounds change the instant we take over the session and the
            // preview visibly jumps in brightness.
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { lastAeFpsRange = it }
            result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)?.let { lastAntibandingMode = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeSensitivity = it }

            val captureNow = SystemClock.elapsedRealtime()
            observeAutofocusState(result, captureNow)

            // Periodic diagnostic logging and the live Auto readouts, on one 2 Hz throttle.
            val now = captureNow
            if (now - lastFocusResultLogAtMs < 500L) return
            lastFocusResultLogAtMs = now

            // Everything the native chips print beside "Auto": the AE-metered ISO/exposure pair
            // (public results), the HAL's live AWB kelvin estimate and the metered EV deviation
            // (Samsung vendor result tags — resolved once, and permanently absent where they do
            // not exist). Merged into state off the critical path, like the phone battery.
            if (!vendorResultKeysProbed) {
                vendorResultKeysProbed = true
                vendorColorTempResultKey = makeVendorResultKey("samsung.android.control.colorTemperature")
                vendorEvMeterResultKey = makeVendorResultKey("samsung.android.control.evCompensationValue")
            }
            val meteredWbKelvin = readVendorResult(result, vendorColorTempResultKey) { vendorColorTempResultKey = null }
            val meteredEvTenths = readVendorResult(result, vendorEvMeterResultKey) { vendorEvMeterResultKey = null }
            val underwaterTelemetry = underwaterCommandSolution.takeIf {
                CameraCatalog.isWhiteBalanceAutoUnderwater(currentValue(latestState, ".white_balance"))
            }
            onMeteredExposure?.invoke(
                com.mobiledivecontrol.core.MeteredExposure(
                    iso = lastAeSensitivity,
                    shutterNs = lastAeExposureNs,
                    wbKelvin = underwaterTelemetry?.kelvin ?: meteredWbKelvin,
                    wbTintDuv = underwaterTelemetry?.tintDuv,
                    wbConfidence = underwaterTelemetry?.confidence,
                    evTenths = meteredEvTenths,
                ),
            )

            // The continuity anchor: the HAL's kelvin estimate with the gains AND transform it
            // applied, all from the same tick, kept fresh for as long as AWB paints the picture.
            // Both the latch and the harvest wait out the manual-to-Auto echo window.
            if (!wbIsAuto(latestState)) {
                wbManualSeenAtMs = now
            } else if (now - wbManualSeenAtMs > WB_AUTO_SETTLE_MS) {
                val gains = lastAutoColorGains
                if (meteredWbKelvin != null && gains != null) {
                    lastAutoWbAnchor = WbAnchor(meteredWbKelvin, gains, lastAutoColorTransform, now)
                    harvestAwbCurvePoint(meteredWbKelvin, gains, lastAutoColorTransform, now)
                }
            }

            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val af = result.get(CaptureResult.CONTROL_AF_MODE)
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val lp = samsungFocusLensPosition(result)
            val physicalId = runningPhysicalCameraId(result)
            val noiseReductionMode = result.get(CaptureResult.NOISE_REDUCTION_MODE)
            val edgeMode = result.get(CaptureResult.EDGE_MODE)
            val videoStabilizationMode = result.get(
                CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE,
            )
            val expectedPhysicalId = boundExpectedPhysicalCameraId
            if (activeRecording != null && boundHdrLogMode == "LOG" &&
                physicalId != null && expectedPhysicalId != null && physicalId != expectedPhysicalId
            ) {
                Log.e(
                    TAG,
                    "Log physical-camera contract changed while recording: " +
                        "expected=$expectedPhysicalId actual=$physicalId",
                )
            }
            // evIdx is the HAL's echo of CONTROL_AE_EXPOSURE_COMPENSATION — the on-metal proof
            // line for the +/-4.0 EV window: past index +/-20 the echo AND the iso x expNs
            // product must keep moving, or the wider dial saturates and must be pulled back.
            val evEcho = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            val resultGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                String.format(java.util.Locale.US, "%.2f/%.2f", it.red, it.blue)
            }
            Log.d(
                TAG,
                "CaptureResult fd=$fd af=$af afState=$afState lpEcho=$lp lpActual=$lastObservedVendorLensPos " +
                    "wanted=$lastCommandedLensPos physical=$physicalId native=$nativeFocusActive " +
                    "iso=$lastAeSensitivity expNs=$lastAeExposureNs evIdx=$evEcho " +
                    "wbK=$meteredWbKelvin evMeter=$meteredEvTenths " +
                    "wbAnchor=${lastAutoWbAnchor?.kelvin} gains=$resultGains " +
                    "nr=$noiseReductionMode edge=$edgeMode eis=$videoStabilizationMode",
            )
        }
    }

    private fun makeVendorResultKey(name: String): CaptureResult.Key<Int>? = try {
        CaptureResult.Key(name, Int::class.javaObjectType)
    } catch (_: Exception) {
        null
    }

    private fun readVendorResult(
        result: CaptureResult,
        key: CaptureResult.Key<Int>?,
        onUnresolvable: () -> Unit,
    ): Int? {
        if (key == null) return null
        return try {
            result.get(key)
        } catch (_: Exception) {
            onUnresolvable()
            null
        }
    }

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        initialState: CameraState,
        onReady: (Boolean) -> Unit,
        onDetectedLenses: ((List<String>) -> Unit)? = null,
        onCapabilities: ((CameraCapabilities) -> Unit)? = null,
        onMeteredExposure: ((com.mobiledivecontrol.core.MeteredExposure) -> Unit)? = null,
        onPointingGesture: ((PointingGesture) -> Unit)? = null,
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        installResumeWatchdog(lifecycleOwner)
        this.onDetectedLenses = onDetectedLenses
        this.onCapabilities = onCapabilities
        this.onMeteredExposure = onMeteredExposure
        this.onPointingGesture = onPointingGesture
        startFocusMotionMonitor()
        wbCalibration = runCatching { loadWbCalibration() }.getOrNull()
        loadAwbCurve()
        latestState = initialState
        focusAssistEnabled = isFocusAssistEnabled(initialState)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                // Detect capabilities using CameraManager (works regardless of extensions)
                if (!capabilitiesDetected) {
                    detectDeviceCapabilitiesViaCameraManager()
                    capabilitiesDetected = true
                }
                // Initialize extensions manager for vendor HDR
                initExtensions {
                    bindCamera(force = true)
                    onReady(camera != null)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private var resumeObserver: LifecycleEventObserver? = null

    private fun startFocusMotionMonitor() {
        if (focusMotionMonitorRegistered) return
        val sensor = gyroscope ?: return
        focusMotionMonitorRegistered = sensorManager.registerListener(
            focusMotionListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
    }

    private fun stopFocusMotionMonitor() {
        if (!focusMotionMonitorRegistered) return
        sensorManager.unregisterListener(focusMotionListener)
        focusMotionMonitorRegistered = false
    }

    /**
     * Eviction-recovery net. When another camera app takes the device and the user returns,
     * CameraX reopens the camera on its own — but a downstream failure (GL pipe, stale native
     * session) leaves the preview frozen or black with no error surfaced anywhere. If frames
     * are not streaming shortly after resume, force one full rebind. Never fires during an
     * active recording, which a rebind would kill.
     */
    private fun installResumeWatchdog(owner: LifecycleOwner) {
        resumeObserver?.let { observer -> lifecycleOwner?.lifecycle?.removeObserver(observer) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraRequestHandler.postDelayed({
                    val pv = previewView ?: return@postDelayed
                    // The GL heartbeat is the authoritative health signal: PreviewView's
                    // stream state tracks only the camera side and stays STREAMING while
                    // the effect stage silently fails to reach the display surface.
                    val healthy = focusPeakingProcessor?.let { processor ->
                        SystemClock.elapsedRealtime() - processor.lastDrawSuccessAtMs < 2_000L
                    } ?: (pv.previewStreamState.value == PreviewView.StreamState.STREAMING)
                    if (!healthy && camera != null && activeRecording == null) {
                        Log.w(TAG, "Preview pipeline dead after resume — forcing camera rebind")
                        bindCamera(force = true)
                    }
                }, RESUME_STREAM_CHECK_DELAY_MS)
            }
        }
        owner.lifecycle.addObserver(observer)
        resumeObserver = observer
    }

    private fun initExtensions(onDone: () -> Unit) {
        val provider = cameraProvider ?: run { onDone(); return }
        val future = ExtensionsManager.getInstanceAsync(context, provider)
        future.addListener(
            {
                try {
                    extensionsManager = future.get()
                    val backSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                    deviceHasVendorHdr = extensionsManager?.isExtensionAvailable(
                        backSelector,
                        ExtensionMode.HDR,
                    ) ?: false
                } catch (_: Exception) {
                    deviceHasVendorHdr = false
                }
                onDone()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun detach() {
        photoTimerGeneration++
        stopFocusMotionMonitor()
        resumeObserver?.let { observer -> lifecycleOwner?.lifecycle?.removeObserver(observer) }
        resumeObserver = null
        cameraProvider?.unbindAll()
        camera2HighSpeedRecorder.release()
        cameraRequestHandler.removeCallbacks(highSpeedClockRunnable)
        camera = null
        imageCapture = null
        imageAnalysis = null
        previewView = null
        lifecycleOwner = null
        boundLensFacing = null
        boundLensValue = null
        boundResolution = null
        boundFrameRate = null
        boundHdrLogMode = null
        boundCaptureFormat = null
        boundAspectRatio = null
        boundExpectedPhysicalCameraId = null
        boundHdrExtension = false
        boundLogCaptureContractSatisfied = false
        maximumInformationRequestModes = MaximumInformationRequestModes()
        boundFocusMode = false
        cam2Session = null
        cam2Surfaces = emptyList()
        nativeFocusActive = false
        afSearchGen++
        afPullActive = false
        afTracking = false
        afLocked = false
        afLockPending = false
        afHoldDiopters = null
        afHoldSharpness = null
        afSharpnessLossSamples = 0
        shutterAwbLockGeneration++
        shutterAwbLockInFlight = false
        shutterAwbLockActive = false
        underwaterWbSolution = null
        underwaterCommandSolution = null
        lastUnderwaterObservation = null
        underwaterApplyPosted = false
        underwaterCaptureFrozen = false
        lastUnderwaterAnalysisAtMs = 0L
        lastUnderwaterCommandAtMs = 0L
        underwaterTraceExecutor.execute { underwaterTrace.close() }
        focusAssistEnabled = false
        lastAppliedSessionSignature = null
        focusPeakingProcessor?.release()
        focusPeakingProcessor = null
        // MediaPipe's GPU delegate is thread-affine. Stop new analyzer work first, then enqueue
        // close behind any inference already running on the same executor that created it.
        onPointingGesture = null
        val recognizerToClose = pointingRecognizer
        pointingRecognizer = null
        recognizerToClose?.let { recognizer ->
            focusAssistExecutor.execute { recognizer.close() }
        }
    }

    /**
     * @param atmosphericPressureKpa the live in-shell barometric reading. Used for the auto filter
     *   only; it is not a depth reference once a vacuum has been pulled.
     * @param surfaceAmbientKpa the baseline captured with the suction cover open. Depth is measured
     *   against this. Defaulted so a caller that has no baseline yet falls back to the standard
     *   atmosphere rather than silently reverting to the live reading.
     */
    fun applyState(
        cameraState: CameraState,
        waterPressureKpa: Double?,
        atmosphericPressureKpa: Double?,
        surfaceAmbientKpa: Double? = null,
        waterTemperatureC: Double? = null,
        headingDegrees: Double? = null,
    ) {
        val previousUnderwater = CameraCatalog.isWhiteBalanceAutoUnderwater(
            currentValue(latestState, ".white_balance"),
        )
        val underwater = CameraCatalog.isWhiteBalanceAutoUnderwater(
            currentValue(cameraState, ".white_balance"),
        )
        val now = SystemClock.elapsedRealtime()
        val pressureChanged = waterPressureKpa != latestWaterPressureKpa
        latestState = cameraState
        if ((shutterAwbLockActive || shutterAwbLockInFlight) &&
            !CameraCatalog.isWhiteBalanceAutoShutter(currentValue(cameraState, ".white_balance"))
        ) {
            // Turning the WB ring away from Auto Shutter is an explicit unlock, including while
            // recording; no stale capture-only lock may survive a visible mode change.
            releaseShutterWhiteBalance()
        }
        latestWaterPressureKpa = waterPressureKpa
        latestAtmosphericPressureKpa = atmosphericPressureKpa
        latestSurfaceAmbientKpa = surfaceAmbientKpa
        latestWaterTemperatureC = waterTemperatureC
        latestHeadingDegrees = headingDegrees
        when {
            underwater && !previousUnderwater -> seedUnderwaterWhiteBalance(cameraState, now)
            !underwater && previousUnderwater -> {
                underwaterWbSolution = null
                underwaterCommandSolution = null
                lastUnderwaterObservation = null
                underwaterCaptureFrozen = false
                underwaterTraceExecutor.execute { underwaterTrace.close() }
            }
            underwater && pressureChanged -> updateUnderwaterWhiteBalance(lastUnderwaterObservation, now)
        }
        focusAssistEnabled = isFocusAssistEnabled(cameraState)
        // Toggle GPU shader peaking — no camera rebinding needed.
        // Peaking works in both AF and manual focus modes.
        focusPeakingProcessor?.peakingEnabled = focusAssistEnabled
        focusPeakingProcessor?.exposureAssistMode = exposureAssistMode(cameraState)
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            Log.d(TAG, "applyState: early return (provider/preview/lifecycle null)")
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        val desiredLens = selectedLensValue(cameraState)
        val desiredFrameRate = currentValue(cameraState, ".frame_rate")
        val desiredHdrLogMode = resolvedSessionHdrLogMode(cameraState)
        val desiredCaptureFormat = currentValue(cameraState, ".save_format")
        val desiredAspectRatio = currentValue(cameraState, ".aspect_ratio")
        // Dynamic range is a stream property. A repeating CaptureRequest cannot turn an 8-bit
        // encoder surface into a 10-bit one, so an HDR/LOG change must create a new session.
        val needsRebind = desiredLensFacing != boundLensFacing ||
                desiredResolution != boundResolution ||
                desiredFrameRate != boundFrameRate ||
                desiredLens != boundLensValue ||
                desiredHdrLogMode != boundHdrLogMode ||
                desiredCaptureFormat != boundCaptureFormat ||
                desiredAspectRatio != boundAspectRatio
        if (needsRebind) {
            Log.d(TAG, "applyState: rebinding camera")
            focusSlewGen++
            bindCamera(force = true)
        } else {
            applySessionStateWithFocusSlew(cameraState)
        }
    }

    // ── Focus slew ────────────────────────────────────────────────────────────────────────
    // The wheel moves the focus TARGET in sensitivity-sized strides, but the lens itself must
    // visit every 0.01 tick on the way — a glide, never a jump. Field-asked: "move through
    // each step, just at a greater rate". ~60 ticks/s, so a max-sensitivity click (11 ticks)
    // glides in ~180 ms and a full sweep takes ~1.7 s of continuous motion.

    private var focusSlewGen = 0
    private var lensFocusApplied: String? = null

    /** The exposure envelope the running pipeline negotiated, inherited on session takeover. */
    @Volatile private var lastAeFpsRange: android.util.Range<Int>? = null
    @Volatile private var lastAntibandingMode: Int? = null

    /** Last 3A settings actually pushed, so re-applying an unchanged value never disturbs them. */
    private var lastAppliedFlash: String? = null

    /** The last lens position we asked for — the thing [lastObservedVendorLensPos] must converge to. */
    @Volatile private var lastCommandedLensPos: Int? = null

    /**
     * Autofocus that HOLDS.
     *
     * Continuous AF re-hunts on its own, which underwater reads as the camera second-guessing
     * the diver: hand focus to auto and the plane you carefully set drifts away while nothing
     * in front of the lens has changed. Instead auto means "keep this plane until the camera
     * actually moves, then find the subject again" — the housing's own motion is the cue, taken
     * from the gyroscope, because it is the one signal that is about the CAMERA rather than
     * about the scene (fish swim past; that is not a reason to refocus).
     */
    /**
     * The plane autofocus is holding, in diopters, or null while a search is in flight.
     *
     * Once a search converges we stop asking the HAL to autofocus and simply command the plane
     * it found. That is what makes "auto" actually hold: a repeating request carrying AF_MODE
     * AUTO gets rebuilt whenever anything else in the session changes — a depth reading, a
     * battery tick — and each rebuild restarts the search, which walked the lens to infinity
     * while the housing sat still. A commanded distance is immune to that.
     */
    @Volatile private var afHoldDiopters: Float? = null

    /** True only while our smooth contrast pull is running; steady auto focus always holds. */
    @Volatile private var afTracking = false

    /** Camera2 AF trigger state: continuous while tracking, locked while the scene is steady. */
    @Volatile private var afLocked = false
    @Volatile private var afLockPending = false
    @Volatile private var afLockRequestedAtMs = 0L
    @Volatile private var afLockEligibleAtMs = 0L

    /** Contrast of the plane we deliberately landed on, used to detect a genuinely new scene. */
    @Volatile private var afHoldSharpness: Double? = null
    @Volatile private var afHoldStartedAtMs = 0L
    @Volatile private var lastAfHoldMonitorAtMs = 0L
    private var afSharpnessLossSamples = 0

    /**
     * A capture request kept alive for the duration of a ramp, so a focus step costs one key
     * write instead of rebuilding white balance, exposure, tonemap and zoom every frame. That
     * rebuild measured ~45 ms per step, which stretched every 16 ms tick to ~60 ms and made
     * every ramp run roughly three times slower than the rate the diver had chosen.
     */
    private var focusRampBuilder: CaptureRequest.Builder? = null
    private var focusRampSession: CameraCaptureSession? = null

    /**
     * Command a focus plane cheaply, reusing the ramp builder. False if no session owns it.
     *
     * The cached builder carries the SAME look as the full repeating request — white balance,
     * ISO/shutter, EV, tonemap, flash — via [populateSessionLook]. It used to carry a bare
     * template, so every focus step wiped the manual exposure settings off the frame (AWB back
     * to auto, AE back on) until the next full rebuild. The cache is invalidated whenever the
     * full request is submitted, so a settings change costs one rebuild on the next focus step
     * and each step after that is still a single key write.
     */
    private fun commandFocusDistance(diopters: Float): Boolean {
        val session = cam2Session ?: return false
        if (focusRampBuilder == null || focusRampSession !== session) {
            focusRampBuilder = runCatching {
                session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).also { b ->
                    cam2Surfaces.forEach { surface -> b.addTarget(surface) }
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    b.set(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW,
                    )
                    lastAeFpsRange?.let { r -> b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r) }
                    lastAntibandingMode?.let { m ->
                        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, m)
                    }
                    camera?.let { cam ->
                        applyNativeZoom(b, latestState, cam)
                        populateSessionLook(b, latestState, cam)
                    }
                }
            }.getOrNull()
            focusRampSession = session
        }
        val builder = focusRampBuilder ?: return false
        return runCatching {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            session.setRepeatingRequest(builder.build(), sessionCaptureCallback, cameraRequestHandler)
            true
        }.getOrDefault(false)
    }

    /** Newest focus measure from the analysis stream: higher means more contrast, i.e. sharper. */
    @Volatile private var latestSharpness: Double = 0.0

    private fun seedUnderwaterWhiteBalance(cameraState: CameraState, nowMs: Long): Boolean {
        // On a persisted-AU app restart there is no same-session anchor yet. Stay on OEM AWB
        // until it reports a real value; commanding an arbitrary 6500 K first caused the most
        // visible colour jump in the device trace and contaminated the estimator's first frame.
        val anchorKelvin = lastAutoWbAnchor?.kelvin
        val meteredKelvin = cameraState.meteredExposure.wbKelvin
        val seedKelvin = (anchorKelvin ?: meteredKelvin)?.coerceIn(
            CameraCatalog.WB_MIN_KELVIN,
            CameraCatalog.WB_MAX_KELVIN,
        ) ?: return false
        underwaterWbEstimator.reset(seedKelvin, 0.0, nowMs)
        underwaterWbSolution = UnderwaterWhiteBalanceSolution(
            kelvin = seedKelvin,
            tintDuv = 0.0,
            confidence = if (lastAutoWbAnchor != null) 0.35 else 0.0,
            diveLightProbability = 0.0,
        )
        underwaterCommandSolution = underwaterWbSolution
        lastUnderwaterAnalysisAtMs = 0L
        lastUnderwaterCommandAtMs = nowMs
        underwaterTraceExecutor.execute {
            underwaterTrace.start(nowMs, System.currentTimeMillis())?.let { file ->
                Log.i(TAG, "AU trace started: ${file.absolutePath}")
            }
        }
        Log.i(TAG, "AU seeded at ${seedKelvin}K from OEM AWB")
        return true
    }

    /** Fuse one fresh frame with the low-confidence depth/range prior, then coalesce request writes. */
    private fun updateUnderwaterWhiteBalance(
        observation: UnderwaterFrameObservation?,
        nowMs: Long,
        analysisMicros: Long? = null,
    ) {
        if (!CameraCatalog.isWhiteBalanceAutoUnderwater(currentValue(latestState, ".white_balance"))) return
        val current = underwaterWbSolution ?: run {
            if (!seedUnderwaterWhiteBalance(latestState, nowMs)) return
            underwaterWbSolution ?: return
        }
        val freshObservation = observation?.takeIf { nowMs - it.timestampMillis <= 750L }
        val anchor = lastAutoWbAnchor
        val focusCapability = selectedFocusCapability(latestState)
        val focusDiopters = lastObservedFocusDiopters
            ?.takeIf { focusCapability?.usesPublicDiopters == true && it > 0.05f }
        val subjectDistance = focusDiopters?.let { (1.0 / it).coerceIn(0.2, 5.0) }
        val depth = currentDepthMeters()
        val depthConfidence = currentDepthConfidence()
        val rangeConfidence = if (subjectDistance != null) 0.20 else 0.0
        val anchorAge = anchor?.let { (nowMs - it.capturedAtMs).coerceAtLeast(0L) }
        val observedGains = lastObservedColorGains
        val solution = underwaterWbEstimator.update(
            UnderwaterWhiteBalanceInput(
                observation = freshObservation,
                currentKelvin = current.kelvin,
                currentTintDuv = current.tintDuv,
                autoAnchorKelvin = anchor?.kelvin,
                autoAnchorAgeMillis = anchorAge,
                depthMeters = depth,
                depthConfidence = depthConfidence,
                subjectDistanceMeters = subjectDistance,
                // Public focus distance is a useful ordering cue, but the housing port has not
                // yet been metrically calibrated in water, so it remains a deliberately weak prior.
                subjectDistanceConfidence = rangeConfidence,
                timestampMillis = nowMs,
            ),
        )
        underwaterWbSolution = solution
        val commanded = underwaterCommandSolution ?: current
        val recording = latestState.recording
        val wallMillis = System.currentTimeMillis()
        underwaterTraceExecutor.execute {
            underwaterTrace.record(
                UnderwaterWhiteBalanceTrace.Sample(
                    elapsedMillis = nowMs,
                    wallMillis = wallMillis,
                    recording = recording,
                    depthMeters = depth,
                    depthConfidence = depthConfidence,
                    rangeMeters = subjectDistance,
                    rangeConfidence = rangeConfidence,
                    observation = freshObservation,
                    anchorKelvin = anchor?.kelvin,
                    anchorAgeMillis = anchorAge,
                    estimate = solution,
                    command = commanded,
                    appliedRedGain = observedGains?.red?.toDouble(),
                    appliedBlueGain = observedGains?.blue?.toDouble(),
                    analysisMicros = analysisMicros,
                ),
            )
        }
        val commandChanged = abs(solution.kelvin - commanded.kelvin) >= UNDERWATER_COMMAND_KELVIN_EPSILON ||
            abs(solution.tintDuv - commanded.tintDuv) >= UNDERWATER_COMMAND_DUV_EPSILON
        if (commandChanged && !underwaterCaptureFrozen) requestUnderwaterWhiteBalanceApply()
    }

    private fun currentDepthConfidence(): Double {
        val depth = currentDepthMeters() ?: return 0.0
        // Below roughly one pressure-sensor quantisation step, the image estimator must lead.
        val resolvedDepth = (depth / 0.75).coerceIn(0.0, 1.0)
        val baseline = if (latestSurfaceAmbientKpa != null) 1.0 else 0.45
        // SafetyState currently carries no packet timestamp. Do not invent staleness from an
        // unchanged pressure value: a stationary diver legitimately produces the same sample.
        // Even if an old value survives a disconnect, this remains a weak prior and live image
        // evidence continues to lead the estimate.
        return resolvedDepth * baseline
    }

    private fun requestUnderwaterWhiteBalanceApply() {
        if (underwaterApplyPosted || underwaterCaptureFrozen) return
        underwaterApplyPosted = true
        val now = SystemClock.elapsedRealtime()
        val delay = (lastUnderwaterCommandAtMs + UNDERWATER_REQUEST_INTERVAL_MS - now).coerceAtLeast(0L)
        cameraRequestHandler.postDelayed(underwaterApply@{
            underwaterApplyPosted = false
            if (!underwaterCaptureFrozen && CameraCatalog.isWhiteBalanceAutoUnderwater(
                    currentValue(latestState, ".white_balance"),
                )
            ) {
                val estimate = underwaterWbSolution ?: return@underwaterApply
                val commanded = underwaterCommandSolution
                val changed = commanded == null ||
                    abs(estimate.kelvin - commanded.kelvin) >= UNDERWATER_COMMAND_KELVIN_EPSILON ||
                    abs(estimate.tintDuv - commanded.tintDuv) >= UNDERWATER_COMMAND_DUV_EPSILON
                if (changed) {
                    // Publish the command atomically before building its SessionSignature. Any
                    // unrelated state tick now sees the same stable command instead of bypassing
                    // this throttle with the newest sub-threshold estimate.
                    underwaterCommandSolution = estimate
                    lastUnderwaterCommandAtMs = SystemClock.elapsedRealtime()
                    applySessionState(latestState)
                }
            }
        }, delay)
    }

    /**
     * Whether a contrast pull is actually reading [latestSharpness] right now.
     *
     * The metric is cheap per frame but it ran on EVERY frame forever, and its value is read
     * only while a pull is searching. Cheap-times-always is the shape that cost the most heat in
     * this file already, so the analyzer skips the measurement entirely when nothing wants it.
     */
    @Volatile private var afPullActive = false

    /**
     * Contrast in the middle of the frame, from the RGBA analysis plane.
     *
     * Deliberately cheap: a horizontal gradient over a subsampled centre crop. It is not an
     * image-quality metric, only a monotone "is this sharper than that" signal for finding the
     * plane a subject sits on, so precision matters far less than costing nothing per frame.
     */
    private fun centreSharpness(image: androidx.camera.core.ImageProxy): Double {
        return try {
            val plane = image.planes.firstOrNull() ?: return latestSharpness
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(4)
            val x0 = image.width / 3
            val x1 = image.width * 2 / 3
            val y0 = image.height / 3
            val y1 = image.height * 2 / 3
            var sum = 0L
            var count = 0
            var y = y0
            while (y < y1) {
                val row = y * rowStride
                var x = x0
                while (x < x1 - SHARPNESS_STRIDE) {
                    fun luma(pixelX: Int): Int {
                        val base = row + pixelX * pixelStride
                        val r = buffer.get(base).toInt() and 0xFF
                        val g = buffer.get(base + 1).toInt() and 0xFF
                        val b = buffer.get(base + 2).toInt() and 0xFF
                        return (77 * r + 150 * g + 29 * b) ushr 8
                    }
                    val a = luma(x)
                    val b = luma(x + SHARPNESS_STRIDE)
                    sum += kotlin.math.abs(a - b)
                    count++
                    x += SHARPNESS_STRIDE
                }
                y += SHARPNESS_STRIDE
            }
            if (count == 0) latestSharpness else sum.toDouble() / count
        } catch (_: Exception) {
            latestSharpness
        }
    }

    /**
     * Release a held plane only when the centre crop stays dramatically softer for three
     * consecutive 5 Hz samples. A single swimmer, caustic or compression wobble cannot restart
     * focus; a real subject-distance change can, including straight translation with no gyro cue.
     */
    private fun observeHeldFocusSharpness(sharpness: Double, sampledAtMs: Long) {
        cameraRequestHandler.post {
            if (manualFocusRequestFor(latestState) != null || !afLocked || afTracking) {
                afSharpnessLossSamples = 0
                return@post
            }
            if (sampledAtMs - afHoldStartedAtMs < AF_HOLD_MONITOR_GRACE_MS) return@post

            val baseline = afHoldSharpness
            if (baseline == null || baseline <= 0.0) {
                afHoldSharpness = sharpness.takeIf { it > 0.0 }
                return@post
            }
            if (AutofocusHoldPolicy.isSustainedFocusLossSample(baseline, sharpness)) {
                afSharpnessLossSamples++
                if (afSharpnessLossSamples >= AutofocusHoldPolicy.SHARPNESS_RELEASE_SAMPLES) {
                    afSharpnessLossSamples = 0
                    if (sampledAtMs - lastSignificantFocusMotionAtMs >= AF_MOTION_SETTLE_MS) {
                        startAutofocusTracking("sustained scene focus loss")
                    }
                }
            } else {
                afSharpnessLossSamples = 0
                if (sharpness > baseline) {
                    // Learn a better reference slowly; never chase ordinary downward noise.
                    afHoldSharpness = baseline * 0.8 + sharpness * 0.2
                }
            }
        }
    }

    /** Horizontal optical field of view for the lens currently feeding the analysis stream. */
    private fun horizontalFovDegrees(): Double = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = if (boundLensFacing == CameraSelector.LENS_FACING_FRONT) {
            frontCameraId
        } else {
            activeLensProfile?.physicalCameraId
                ?: activeLensProfile?.logicalCameraId
                ?: backCameraProfile?.logicalCameraId
        } ?: return@runCatching DEFAULT_HORIZONTAL_FOV_DEGREES
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorWidthMm = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.width
            ?.toDouble()
            ?: return@runCatching DEFAULT_HORIZONTAL_FOV_DEGREES
        val focalLengthMm = if (boundLensFacing == CameraSelector.LENS_FACING_FRONT) {
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()?.toDouble()
        } else {
            activeLensProfile?.focalLengthMm?.toDouble()
                ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()?.toDouble()
        } ?: return@runCatching DEFAULT_HORIZONTAL_FOV_DEGREES
        val effectiveFocal = focalLengthMm * latestState.zoomFactor.coerceAtLeast(1.0)
        2.0 * atan(sensorWidthMm / (2.0 * effectiveFocal)) * 180.0 / Math.PI
    }.getOrDefault(DEFAULT_HORIZONTAL_FOV_DEGREES)

    /** Start Samsung's damped CAF from its current plane, then lock it after convergence. */
    private fun startAutofocusTracking(reason: String) {
        if (manualFocusRequestFor(latestState) != null) return
        val capability = selectedFocusCapability(latestState)
        if (capability?.supportsManualFocus == false) {
            afLocked = true
            afTracking = false
            return
        }
        releaseAutofocusForTracking(reason, AF_LOCK_SETTLE_MS)
    }

    /**
     * CANCEL releases a prior one-shot lock; the unchanged CONTINUOUS_VIDEO repeating request
     * then tracks smoothly. Repeated gyro events only extend the settle deadline — no repeated
     * trigger is submitted and no focus operation is restarted while the housing is moving.
     */
    private fun releaseAutofocusForTracking(reason: String, settleMs: Long) {
        if (manualFocusRequestFor(latestState) != null) return
        val wasLocked = afLocked || afLockPending
        val eligibleAt = SystemClock.elapsedRealtime() + settleMs
        if (!wasLocked && afTracking) {
            // A gyro stream may report 50 times per second. Extending one deadline is enough;
            // rebuilding the repeating request for every sample would itself look like pulsing.
            afLockEligibleAtMs = eligibleAt
            return
        }
        afLocked = false
        afLockPending = false
        afTracking = true
        afHoldDiopters = null
        afHoldSharpness = null
        afSharpnessLossSamples = 0
        afLockEligibleAtMs = eligibleAt
        if (wasLocked) submitAutofocusTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        camera?.let { submitNativeRepeatingRequest(latestState, it) }
        if (wasLocked || reason == "session start" || reason == "entered auto") {
            Log.d(TAG, "Autofocus: tracking ($reason), lock after ${settleMs}ms settle")
        }
    }

    /** One trigger frame; repeating stays IDLE, as required by the Camera2 AF state machine. */
    private fun submitAutofocusTrigger(trigger: Int): Boolean {
        val session = cam2Session ?: return false
        if (cam2Surfaces.isEmpty()) return false
        return try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            cam2Surfaces.forEach { builder.addTarget(it) }
            builder.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW,
            )
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            lastAeFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            lastAntibandingMode?.let {
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it)
            }
            camera?.let { bound ->
                applyNativeZoom(builder, latestState, bound)
                populateSessionLook(builder, latestState, bound)
            }
            session.capture(builder.build(), sessionCaptureCallback, cameraRequestHandler)
            if (trigger == CameraMetadata.CONTROL_AF_TRIGGER_START) {
                afLockPending = true
                afLockRequestedAtMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "Autofocus: one-shot lock requested")
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "Autofocus trigger $trigger failed: ${error.message}")
            false
        }
    }

    /** Lock only after CAF reports a settled plane; a timeout covers HALs omitting the state. */
    private fun observeAutofocusState(result: TotalCaptureResult, now: Long) {
        if (manualFocusRequestFor(latestState) != null) return
        val state = result.get(CaptureResult.CONTROL_AF_STATE)
        if (afLockPending) {
            val lockedByHal = state == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                state == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            val timedOut = now - afLockRequestedAtMs >= AF_LOCK_RESULT_TIMEOUT_MS
            if (lockedByHal || timedOut) {
                afLockPending = false
                afLocked = true
                afTracking = false
                afHoldStartedAtMs = now
                afHoldSharpness = latestSharpness.takeIf { it > 0.0 }
                afSharpnessLossSamples = 0
                Log.d(
                    TAG,
                    "Autofocus: locked state=$state${if (timedOut && !lockedByHal) " (HAL timeout)" else ""}",
                )
            }
            return
        }
        if (afLocked || !afTracking || now < afLockEligibleAtMs) return

        val passivelyFocused = state == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
        val settleTimedOut = now - afLockEligibleAtMs >= AF_LOCK_RESULT_TIMEOUT_MS
        if (passivelyFocused || settleTimedOut) {
            submitAutofocusTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START)
        }
    }

    /** Generation counter so a new search or a manual input cancels one in flight. */
    private var afSearchGen = 0

    /**
     * Autofocus that travels at the DIVER'S pace.
     *
     * The HAL's own search cannot be slowed — its speed keys are not exposed to us — and left to
     * itself it snaps the lens onto the subject, which is exactly the jolt the ramp settings
     * exist to prevent. So the decision stays with contrast (the same signal autofocus uses) but
     * the travel is ours: the lens pulls at the Inward or Outward Focus Ramp rate, watching the
     * centre of the frame sharpen, and stops on the peak. Overshooting slightly and settling
     * back is what a focus puller does, and it reads far better than arriving abruptly.
     */
    private fun runRampedAutofocus(reason: String) {
        val capability = selectedFocusCapability(latestState) ?: return
        val maxD = reachableDiopters(capability)?.takeIf { it > 0f } ?: return
        if (manualFocusRequestFor(latestState) != null) return
        val startValue = dialValueForCurrentLens(capability)
            ?: afHoldDiopters?.let { (1.0 - it / maxD).coerceIn(0.0, 1.0) }
            ?: return

        afSearchGen++
        afPullActive = true
        val gen = afSearchGen
        afTracking = true

        fun command(value: Double) {
            val diopters = ((1.0 - value.coerceIn(0.0, 1.0)) * maxD).toFloat()
            afHoldDiopters = diopters
            if (!commandFocusDistance(diopters)) {
                camera?.let { submitNativeRepeatingRequest(latestState, it) }
            }
        }

        // Which way is the subject? A short probe outward, judged on contrast.
        val probe = (startValue + AF_PROBE_STEP).coerceAtMost(1.0)
        val baseline = latestSharpness
        command(probe)
        cameraRequestHandler.postDelayed({
            if (gen != afSearchGen) return@postDelayed
            val outward = latestSharpness >= baseline
            val direction = if (outward) 1.0 else -1.0
            val level = manualFocusSettingKey(latestState)?.let { key ->
                CameraCatalog.focusRampLevel(latestState, key, inward = !outward)
            } ?: 60
            // Rate comes from the CLOCK, not from counting ticks. Each tick does real work
            // (building and submitting a capture request), so a tick is never exactly 16 ms;
            // accumulating a fixed step per tick therefore ran the pull slower than the setting
            // asked for. Interpolating against elapsed time makes the travel rate exact, and
            // identical to the rate the AF-to-rail pull already uses.
            val durationMs = rampDurationForLevel(level).toDouble()
            val startedAtMs = SystemClock.elapsedRealtime()

            var best = latestSharpness
            var bestValue = startValue
            var falling = 0

            fun step() {
                if (gen != afSearchGen) return
                val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).toDouble()
                val value = startValue + direction * (elapsed / durationMs)
                if (value !in 0.0..1.0) {
                    finishRampedAutofocus(gen, bestValue, best, maxD, reason)
                    return
                }
                command(value)
                val sharp = latestSharpness
                if (sharp > best) {
                    best = sharp
                    bestValue = value
                    falling = 0
                } else if (sharp < best * AF_PEAK_DROP) {
                    falling++
                    if (falling >= AF_PEAK_CONFIRM) {
                        finishRampedAutofocus(gen, bestValue, best, maxD, reason)
                        return
                    }
                } else {
                    falling = 0
                }
                cameraRequestHandler.postDelayed({ step() }, FOCUS_SLEW_TICK_MS)
            }
            step()
        }, AF_PROBE_SETTLE_MS)
    }

    /**
     * Land on the sharpest plane and keep it there. Samsung's continuous-video AF kept scanning
     * after convergence on the test device (the vendor lens position oscillated while the scene
     * and public focus distance were unchanged), producing the visible pulse this search exists
     * to avoid. Gyroscope motion or sustained sharpness loss starts the next smooth pull.
     */
    private fun finishRampedAutofocus(
        gen: Int,
        bestValue: Double,
        bestSharpness: Double,
        maxD: Float,
        reason: String,
    ) {
        if (gen != afSearchGen) return
        // The search is over; stop measuring until the next one asks.
        afPullActive = false
        val landed = ((1.0 - bestValue.coerceIn(0.0, 1.0)) * maxD).toFloat()
        afHoldDiopters = landed
        afHoldSharpness = bestSharpness.takeIf { it > 0.0 }
        afHoldStartedAtMs = SystemClock.elapsedRealtime()
        afSharpnessLossSamples = 0
        afTracking = false
        camera?.let { submitNativeRepeatingRequest(latestState, it) }
        Log.d(
            TAG,
            "Autofocus: held at ${"%.2f".format(landed)} dpt (dial ${"%.2f".format(bestValue)}, $reason)",
        )
    }

    /** True while a cinematic AF<->manual pull is in flight, so the wheel may retarget it. */
    @Volatile private var cinematicPull = false

    /** The value an in-flight glide is racking toward; null when no glide is running. */
    @Volatile private var focusSlewTarget: String? = null

    /** The lens's last reported plane, for seeding the AF→manual glide. */
    @Volatile private var lastObservedFocusDiopters: Float? = null

    /** The lens's last reported VCM position, for seeding the AF→manual handoff. */
    @Volatile private var lastObservedVendorLensPos: Int? = null

    /** The HAL's own sensor→sRGB matrix while AWB ran auto — the anchor for manual kelvin WB. */
    @Volatile private var lastAutoColorTransform: android.hardware.camera2.params.ColorSpaceTransform? = null

    /**
     * The HAL's OWN white-balance rendering, latched only while white balance is genuinely auto
     * (no manual kelvin, no underwater filter — otherwise the result would echo our own writes
     * back to us as if they were the HAL's truth).
     *
     * [lastAutoWbAnchor] pairs the HAL's AWB kelvin estimate with the gains it applied on the
     * same telemetry tick. That pair is a measured point on THIS sensor's true CCT-to-gains
     * curve, and it is what makes manual white balance colour-match the Auto readout: "A 4500K"
     * converting to manual 4500K must render the identical frame, which no idealised black-body
     * fit can promise but the HAL's own gains at 4500K do by definition.
     */
    @Volatile private var lastAutoColorGains: RggbChannelVector? = null
    /** Actual gains echoed by the HAL, including in AU; used to diagnose device-side clamping. */
    @Volatile private var lastObservedColorGains: RggbChannelVector? = null
    @Volatile private var lastAutoWbAnchor: WbAnchor? = null

    /**
     * The HAL's own white-balance rendering at one known kelvin: the AWB estimate with the gains
     * AND colour transform it applied on the same telemetry tick. This is the continuity anchor
     * for manual white balance — at the anchor kelvin the manual pipeline must reproduce this
     * frame exactly, or Auto-to-manual conversion visibly jumps.
     *
     * A NOTE ON THE VENDOR ROUTE, tried and rejected: writing the native pair
     * (CONTROL_AWB_MODE=101 + samsung.android.control.colorTemperature) from our session IS
     * accepted by this HAL — the result echoes the commanded kelvin and real gains are applied —
     * but only Samsung's GAINS arrive; the illuminant-matched colour matrix their own pipeline
     * adds does not run for third-party clients, so 10000K rendered yellow where the native app
     * renders magenta-warm. Gains can only scale channels; the green-to-magenta axis lives in
     * the TRANSFORM, so the route can never visually match native for us and manual WB now
     * computes the full pipeline itself instead.
     */
    private class WbAnchor(
        val kelvin: Int,
        val gains: RggbChannelVector,
        val transform: android.hardware.camera2.params.ColorSpaceTransform?,
        val capturedAtMs: Long,
    )

    /**
     * A MEASURED point of the native camera's own kelvin-to-gains curve, harvested from the
     * AsShotNeutral tags of DNGs the stock Pro mode saved at known manual kelvins. This is the
     * objective ground truth the user asked for: not a model of what Samsung should do, but a
     * record of what it did, on this exact unit. When the table is present it REPLACES the
     * computed gains (the transform math then derives from the same measured values), pinned by
     * mired-linear interpolation between points and clamped at the measured ends.
     */
    private class WbCalPoint(
        val kelvin: Double,
        val rGain: Double,
        val bGain: Double,
        /** Post-transform diagonal correction fitted from rendered-output measurements. */
        val tr: Double = 1.0,
        val tb: Double = 1.0,
    )

    /**
     * The HAL's OWN white-balance curve, harvested live: while WB is on Auto, every telemetry
     * tick pairs the AWB's kelvin estimate with the exact gains and colour transform it applied,
     * EMA-smoothed into 100K buckets and persisted across sessions.
     *
     * This is the curve the field asked for by name: "manual should correct just like auto, but
     * manually." Manual playback inside the harvested span IS the auto correction pinned by
     * hand — converting at the estimate is seamless by identity, and every covered kelvin
     * renders exactly as Auto would render a scene it estimated at that kelvin. Coverage grows
     * with use (dives sweep the cold half naturally); outside it, the calibrated pipeline
     * stands in.
     */
    private class AwbCurvePoint(
        var rGain: Float,
        var greenGain: Float,
        var bGain: Float,
        var transform: FloatArray?,
        var samples: Int,
    )

    private val awbCurve = java.util.concurrent.ConcurrentHashMap<Int, AwbCurvePoint>()
    @Volatile private var awbCurveDirty = false
    @Volatile private var awbCurveSavedAtMs = 0L

    /**
     * When WB was last seen NOT genuinely auto. Harvesting (and anchor latching) waits
     * [WB_AUTO_SETTLE_MS] after this: at the manual-to-Auto switch the first results still ECHO
     * our manual gains while the kelvin estimate already reads the scene — folding those frames
     * into the curve poisoned the buckets around the scene's estimate with manual-dial colour
     * ("why is 5200 so blue": blue-boosting warm-manual gains keyed under the ~5200 estimate).
     */
    @Volatile private var wbManualSeenAtMs = 0L

    @Volatile private var wbCalibration: List<WbCalPoint>? = null

    /**
     * Loads the measured curve from the app's external-files dir, where adb can push it without
     * any permission dance:
     *   /sdcard/Android/data/com.mobiledivecontrol/files/wb_calibration.json
     *   { "points": [ { "kelvin": 2300, "rGain": 1.62, "bGain": 2.88 }, ... ] }
     * Gains are sensor-space, green-normalised — exactly what 1/AsShotNeutral yields.
     */
    private fun loadWbCalibration(): List<WbCalPoint>? {
        val file = java.io.File(context.getExternalFilesDir(null), "wb_calibration.json")
        if (!file.exists()) return null
        val array = org.json.JSONObject(file.readText()).getJSONArray("points")
        val points = (0 until array.length()).map { index ->
            val point = array.getJSONObject(index)
            WbCalPoint(
                point.getDouble("kelvin"),
                point.optDouble("rGain", Double.NaN),
                point.optDouble("bGain", Double.NaN),
                point.optDouble("tr", 1.0),
                point.optDouble("tb", 1.0),
            )
        }.sortedBy { it.kelvin }
        return points.takeIf { it.size >= 2 }?.also {
            Log.i(
                TAG,
                "WB calibration loaded: ${it.size} measured points " +
                    "${it.first().kelvin.toInt()}K..${it.last().kelvin.toInt()}K",
            )
        }
    }

    /** EMA-fold one live AWB sample into its 100K bucket; persist the curve at most every 20 s. */
    private fun harvestAwbCurvePoint(
        estimateKelvin: Int,
        gains: RggbChannelVector,
        transform: android.hardware.camera2.params.ColorSpaceTransform?,
        nowMs: Long,
    ) {
        if (estimateKelvin < 1500 || estimateKelvin > 12000) return
        // Plausibility gate against the sensor model: an AWB sample whose channel ratios sit
        // more than ~50% from the calibrated prediction at its own estimate is an echo of our
        // manual writes or a mid-transition frame, never a real AWB point — reject it rather
        // than average it in.
        sensorCalibratedGains(estimateKelvin)?.let { model ->
            val sampleR = gains.red / ((gains.greenEven + gains.greenOdd) / 2f)
            val sampleB = gains.blue / ((gains.greenEven + gains.greenOdd) / 2f)
            val modelR = model.red / ((model.greenEven + model.greenOdd) / 2f)
            val modelB = model.blue / ((model.greenEven + model.greenOdd) / 2f)
            if (sampleR !in modelR * 0.5f..modelR * 1.5f) return
            if (sampleB !in modelB * 0.5f..modelB * 1.5f) return
        }
        val bucket = ((estimateKelvin + 50) / 100) * 100
        val flat = transform?.let { t ->
            FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() }
        }
        val green = (gains.greenEven + gains.greenOdd) / 2f
        val existing = awbCurve[bucket]
        if (existing == null) {
            awbCurve[bucket] = AwbCurvePoint(gains.red, green, gains.blue, flat, 1)
        } else {
            val a = 0.1f
            existing.rGain += a * (gains.red - existing.rGain)
            existing.greenGain += a * (green - existing.greenGain)
            existing.bGain += a * (gains.blue - existing.bGain)
            if (flat != null) {
                val t = existing.transform
                if (t == null) existing.transform = flat
                else for (i in 0..8) t[i] += a * (flat[i] - t[i])
            }
            existing.samples++
        }
        awbCurveDirty = true
        if (nowMs - awbCurveSavedAtMs > 20_000L) {
            awbCurveSavedAtMs = nowMs
            persistAwbCurveAsync()
        }
    }

    private fun awbCurveFile() = java.io.File(context.getExternalFilesDir(null), "wb_awb_curve.json")

    private fun persistAwbCurveAsync() {
        if (!awbCurveDirty) return
        awbCurveDirty = false
        val snapshot = awbCurve.entries.associate { (k, v) ->
            k.toString() to org.json.JSONObject().apply {
                put("r", v.rGain.toDouble()); put("g", v.greenGain.toDouble()); put("b", v.bGain.toDouble())
                put("n", v.samples)
                v.transform?.let { t -> put("t", org.json.JSONArray(t.map { it.toDouble() })) }
            }
        }
        Thread {
            runCatching {
                awbCurveFile().writeText(org.json.JSONObject(snapshot).toString())
            }
        }.start()
    }

    private fun loadAwbCurve() {
        runCatching {
            val file = awbCurveFile()
            if (!file.exists()) return
            val json = org.json.JSONObject(file.readText())
            json.keys().forEach { key ->
                val bucket = key.toIntOrNull() ?: return@forEach
                val o = json.getJSONObject(key)
                val t = o.optJSONArray("t")?.let { arr -> FloatArray(9) { arr.getDouble(it).toFloat() } }
                awbCurve[bucket] = AwbCurvePoint(
                    o.getDouble("r").toFloat(), o.getDouble("g").toFloat(), o.getDouble("b").toFloat(),
                    t, o.optInt("n", 1),
                )
            }
            if (awbCurve.isNotEmpty()) {
                Log.i(TAG, "AWB curve loaded: ${awbCurve.size} buckets ${awbCurve.keys.min()}K..${awbCurve.keys.max()}K")
            }
        }
    }

    /**
     * The harvested AWB curve evaluated at one kelvin, with the DISTANCE (in mired) from the
     * covered span. Inside the span the value interpolates between buckets (interior gaps lerp
     * across, so the span is continuous); beyond either end the edge value holds flat and the
     * distance grows — the caller uses that distance to FADE the harvested correction out
     * smoothly rather than switching curves on a cliff. One smooth spectrum, no seams.
     */
    private class HarvestedWb(
        val rGain: Float,
        val greenGain: Float,
        val bGain: Float,
        val transform: FloatArray?,
        val distanceMired: Double,
    )

    private fun harvestedWbAt(kelvin: Int): HarvestedWb? {
        if (awbCurve.isEmpty()) return null
        val keys = awbCurve.keys.sorted()
        val lower = keys.lastOrNull { it <= kelvin }
        val upper = keys.firstOrNull { it >= kelvin }
        fun point(bucket: Int) = awbCurve[bucket]!!
        return when {
            lower != null && upper != null && lower != upper -> {
                val lo = point(lower); val hi = point(upper)
                val f = (((1e6 / kelvin) - (1e6 / lower)) / ((1e6 / upper) - (1e6 / lower))).toFloat()
                HarvestedWb(
                    lo.rGain + f * (hi.rGain - lo.rGain),
                    lo.greenGain + f * (hi.greenGain - lo.greenGain),
                    lo.bGain + f * (hi.bGain - lo.bGain),
                    if (lo.transform != null && hi.transform != null) {
                        FloatArray(9) { i -> lo.transform!![i] + f * (hi.transform!![i] - lo.transform!![i]) }
                    } else lo.transform ?: hi.transform,
                    0.0,
                )
            }
            lower != null && upper != null -> point(lower).let {
                HarvestedWb(it.rGain, it.greenGain, it.bGain, it.transform, 0.0)
            }
            else -> {
                val edge = lower ?: upper ?: return null
                val p = point(edge)
                HarvestedWb(
                    p.rGain, p.greenGain, p.bGain, p.transform,
                    kotlin.math.abs(1e6 / kelvin - 1e6 / edge),
                )
            }
        }
    }

    /** Mired-linear interpolation over the measured table, clamped at the measured ends. */
    private fun interpolateWbCal(kelvin: Double, select: (WbCalPoint) -> Double): Double? {
        val lut = wbCalibration ?: return null
        if (kelvin <= lut.first().kelvin) return select(lut.first())
        if (kelvin >= lut.last().kelvin) return select(lut.last())
        val upper = lut.first { it.kelvin >= kelvin }
        val lower = lut.last { it.kelvin <= kelvin }
        if (upper === lower) return select(lower)
        val t = ((1e6 / kelvin) - (1e6 / lower.kelvin)) / ((1e6 / upper.kelvin) - (1e6 / lower.kelvin))
        return select(lower) + t * (select(upper) - select(lower))
    }

    /** The measured gains curve at one kelvin; null when the table is tr/tb-only. */
    private fun measuredWbGains(kelvin: Double): Pair<Double, Double>? {
        val r = interpolateWbCal(kelvin) { it.rGain } ?: return null
        val b = interpolateWbCal(kelvin) { it.bGain } ?: return null
        if (r.isNaN() || b.isNaN()) return null
        return r to b
    }

    /** The measured rendered-output diagonal at one kelvin, when the table carries one. */
    private fun measuredWbTransformFix(kelvin: Double): Pair<Double, Double>? {
        val tr = interpolateWbCal(kelvin) { it.tr } ?: return null
        val tb = interpolateWbCal(kelvin) { it.tb } ?: return null
        if (tr == 1.0 && tb == 1.0) return null
        return tr to tb
    }

    /**
     * This sensor's factory colour calibration, DNG-style: the XYZ-to-sensor matrix at each of
     * the two reference illuminants (calibration transform folded in where present), plus each
     * illuminant's correlated colour temperature. Read once at probe time. This is the same
     * per-unit data Samsung's own pipeline is calibrated from, and it is what lets the manual
     * kelvin dial follow the SENSOR's real colour locus instead of an idealised sRGB black body
     * — the black-body model pins green and so cannot express the green-to-magenta axis at all,
     * which is why 10000K used to render yellow where the native app renders magenta-warm.
     */
    private class SensorColorCalibration(
        val cct1: Double,
        val m1: Array<DoubleArray>,
        val cct2: Double,
        val m2: Array<DoubleArray>,
    )

    @Volatile private var sensorColorCalibration: SensorColorCalibration? = null

    /** AE's last chosen pair, so a lone manual ISO or shutter inherits a sane partner. */
    @Volatile private var lastAeExposureNs: Long? = null
    @Volatile private var lastAeSensitivity: Int? = null

    private fun manualFocusSettingKey(cameraState: CameraState): String? =
        CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
            .firstOrNull { it.id.endsWith(".manual_focus") }?.id

    private fun applySessionStateWithFocusSlew(cameraState: CameraState) {
        val key = manualFocusSettingKey(cameraState)
        val target = key?.let { cameraState.settingValues[it] ?: "AF" }
        val toNum = target?.toDoubleOrNull()
        // AF -> manual: seed the glide from where the lens ACTUALLY is (last observed
        // diopters, linearly normalised), so manual focus picks up from AF's plane instead
        // of jumping from wherever the value happened to sit before AF took over.
        val fromNum = lensFocusApplied?.toDoubleOrNull()
            ?: if (toNum != null) {
                run {
                    // Where the lens actually IS, inverted through the same linear law, so
                    // leaving AF glides from the plane AF chose instead of jumping.
                    val capability = selectedFocusCapability(cameraState)
                    val range = capability?.vendorFocusLensRange?.takeIf { it.last > it.first }
                    @Suppress("UNUSED_EXPRESSION")
                    range
                    // Actuator first — it is exact and never saturates. The reported distance is
                    // only a fallback for hardware that publishes no calibration table.
                    capability?.let { dialValueForCurrentLens(it) }
                        ?: lastObservedFocusDiopters?.let { fd ->
                            val maxD = capability?.let { reachableDiopters(it) }?.takeIf { it > 0f }
                                ?: return@let null
                            (1.0 - (fd / maxD).toDouble()).coerceIn(0.0, 1.0)
                        }
                }
            } else {
                null
            }
        // The dial is linear in lens position (and therefore in diopters), so a glide in
        // value space is a glide in optical space: uniform, no acceleration artefacts.
        // Only JUMPS glide — a wheel walking 0.01 at a time is already continuous, and
        // re-smoothing it would let the glass lag the readout.
        // A glide already running toward THIS target must not be restarted: only the target
        // changing may interrupt it. Otherwise the rest of the state still lands, carrying the
        // glide's current focus value so the two never contradict each other.
        val activeGlideTarget = focusSlewTarget
        if (activeGlideTarget != null && activeGlideTarget == target && key != null) {
            val holdValue = lensFocusApplied ?: activeGlideTarget
            applySessionState(
                cameraState.copy(settingValues = cameraState.settingValues + (key to holdValue)),
            )
            return
        }
        // PROVENANCE, NOT MAGNITUDE, decides whether to glide. The Inward/Outward Focus Ramp
        // settings exist for AF<->rail transitions; a wheel-driven walk inside the manual scale
        // must land on the glass as fast as it lands on the readout. With ticks aggregated per
        // frame a fast spin moves further than GLIDE_MIN_JUMP each frame, so a magnitude test
        // would rack every frame at the cinematic rate and leave the lens permanently trailing
        // the diver's hand.
        val fromAf = lensFocusApplied == null || lensFocusApplied == "AF"
        val cinematic = (fromAf != (target == "AF")) || cinematicPull
        if (key == null || target == null || fromNum == null || toNum == null ||
            !cinematic || kotlin.math.abs(toNum - fromNum) <= GLIDE_MIN_JUMP
        ) {
            focusSlewGen++
            focusSlewTarget = null
            cinematicPull = false
            lensFocusApplied = target
            applySessionState(cameraState)
            return
        }
        focusSlewGen++
        focusSlewTarget = target
        cinematicPull = true
        val gen = focusSlewGen
        // A focus PULL, not a jump: the lens walks from where it is to where it is going at a
        // CONSTANT rate, one step per frame, through every plane in between. Even speed is the
        // point — easing was tried and read as sluggish at the ends rather than cinematic.
        //
        // The pace is the diver's to choose, and separately per direction: Inward Focus Ramp
        // governs far-to-near, Outward Focus Ramp near-to-far, each 1 (slowest) to 100
        // (fastest). Interruptible throughout: turning the wheel retargets the pull mid-flight.
        val travel = kotlin.math.abs(toNum - fromNum)
        val inward = toNum < fromNum
        val level = CameraCatalog.focusRampLevel(cameraState, key, inward)
        val fullRackMs = rampDurationForLevel(level)
        val durationMs = (travel * fullRackMs).toLong().coerceIn(MIN_RACK_MS, fullRackMs)
        // Paced by the CLOCK rather than by counting ticks: a tick's work is never free, so
        // accumulating a fixed step per tick ran every ramp slower than the chosen rate.
        val startedAtMs = SystemClock.elapsedRealtime()
        val rampCapability = selectedFocusCapability(cameraState)
        val rampMaxDiopters = rampCapability?.let { reachableDiopters(it) }?.takeIf { it > 0f }

        fun stepAt() {
            if (gen != focusSlewGen) return
            val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).toDouble()
            if (elapsed >= durationMs || rampMaxDiopters == null) {
                lensFocusApplied = target
                focusSlewTarget = null
                cinematicPull = false
                val base = latestState
                applySessionState(base.copy(settingValues = base.settingValues + (key to target)))
                return
            }
            val value = fromNum + (toNum - fromNum) * (elapsed / durationMs)
            val label = String.format(java.util.Locale.US, "%.4f", value)
            lensFocusApplied = label
            val diopters = ((1.0 - value.coerceIn(0.0, 1.0)) * rampMaxDiopters).toFloat()
            if (!commandFocusDistance(diopters)) {
                val base = latestState
                applySessionState(base.copy(settingValues = base.settingValues + (key to label)))
            }
            cameraRequestHandler.postDelayed({ stepAt() }, FOCUS_SLEW_TICK_MS)
        }
        stepAt()
    }

    fun execute(command: CameraCommand) {
        when (command) {
            CameraCommand.CapturePhoto -> capturePhoto()
            CameraCommand.OpenGallery -> openGallery()
            CameraCommand.RestartCamera -> bindCamera(force = true)
            CameraCommand.ToggleVideoRecording,
            CameraCommand.StartVideoRecording -> startVideoRecording()
            CameraCommand.PauseVideoRecording -> pauseVideoRecording()
            CameraCommand.ResumeVideoRecording -> resumeVideoRecording()
            CameraCommand.StopVideoRecording -> stopVideoRecording()
            CameraCommand.DeleteVideoRecording -> deleteVideoRecording()
            // Preview visibility is reducer state. The URI arrives through RecordingClock when
            // CameraX finalises the segment, so no imperative camera call is required here.
            CameraCommand.PreviewVideoRecording -> Unit
            is CameraCommand.SetZoom -> applyLiveZoom(command.value)
            else -> Unit
        }
    }

    private fun bindCamera(force: Boolean = false) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val previewSurface = previewView ?: return
        val desiredLensFacing = desiredLensFacing(latestState)
        val desiredResolution = desiredResolutionValue(latestState)
        val desiredFrameRate = currentValue(latestState, ".frame_rate")
        val desiredFrameRateFps = desiredFrameRate?.removeSuffix("fps")?.toIntOrNull()
        val highSpeedRecording = desiredFrameRateFps != null && desiredFrameRateFps >= HIGH_SPEED_FPS_MIN
        val desiredHdrLogMode = resolvedSessionHdrLogMode(latestState)
        val desiredCaptureFormat = currentValue(latestState, ".save_format")
        val desiredAspectRatio = currentValue(latestState, ".aspect_ratio")
        val maximumInformationLog =
            VideoDynamicRangePolicy.usesMaximumInformationStreamGraph(desiredHdrLogMode)
        activeLensProfile = selectedLensProfile(latestState)
        val focusCapability = selectedFocusCapability(latestState)
        val manualFocusRequest = manualFocusRequestFor(latestState)
        if (desiredLensFacing == CameraSelector.LENS_FACING_BACK) {
            deviceMinFocusDistance = focusCapability?.minFocusDistance ?: activeLensProfile?.minFocusDistance ?: 0f
        } else {
            deviceMinFocusDistance = focusCapability?.minFocusDistance ?: frontCameraMinFocusDistance
        }

        if (!force && desiredLensFacing == boundLensFacing &&
            desiredResolution == boundResolution &&
            desiredFrameRate == boundFrameRate &&
            selectedLensValue(latestState) == boundLensValue &&
            desiredHdrLogMode == boundHdrLogMode &&
            desiredCaptureFormat == boundCaptureFormat &&
            desiredAspectRatio == boundAspectRatio) {
            applySessionState(latestState)
            return
        }

        boundLogCaptureContractSatisfied = false
        maximumInformationRequestModes = MaximumInformationRequestModes()

        val selectorBuilder = CameraSelector.Builder()
            .requireLensFacing(desiredLensFacing)
        val selectedCameraId = selectedCameraIdForBinding(latestState, desiredLensFacing)
        selectedCameraId?.let {
            selectorBuilder.addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    Camera2CameraInfo.from(cameraInfo).cameraId == it
                }
            }
        }
        val selector = selectorBuilder.build()
        // Note: vendor HDR extension (ExtensionMode.HDR) is intentionally NOT used.
        // It blocks Camera2 interop, preventing manual focus/ISO/shutter from working.
        // HDR is applied via Camera2 SCENE_MODE_HDR in applyCamera2Options instead.

        // Check if manual focus is active — if so, disable AF at bind time via Camera2Interop
        val isManualFocus = manualFocusRequest != null
        val physicalCameraId = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
            selectedCameraId == backCameraProfile?.logicalCameraId
        ) {
            activeLensProfile?.physicalCameraId
        } else {
            null
        }
        Log.d(
            TAG,
            "bindCamera: lens=${selectedLensValue(latestState)} cameraId=$selectedCameraId physical=$physicalCameraId minFocus=$deviceMinFocusDistance manual=$isManualFocus transport=${manualFocusRequest?.transport}",
        )

        // The session is configured for the near limit by NOT baking AF_MODE_OFF into it; the
        // one-shot AF sequence then owns focus until the dial leaves 0.00.
        boundMacroStop = false
        val previewBuilder = Preview.Builder()
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setOutputFormat(
                if (desiredCaptureFormat == "Ultra HDR JPEG") {
                    ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                } else {
                    ImageCapture.OUTPUT_FORMAT_JPEG
                },
            )
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // MediaPipe accepts RGB/RGBA. Keeping this one shared 640x480 stream avoids a second
            // CameraX use case and its extra ISP/scaler cost.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
        if (physicalCameraId != null) {
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
            Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(physicalCameraId)
            Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physicalCameraId)
        }
        if (isManualFocus) {
            // Set AF_MODE_OFF in the repeating request template at bind time.
            // This is the only reliable way to disable Samsung's AF pipeline -
            // runtime Camera2 interop is accepted but Samsung HAL ignores AF_MODE changes.
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            Camera2Interop.Extender(imageCaptureBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            // Same rule as the live request: at the macro stop the public key stands down so
            // the vendor session parameter below can reach inside its clamp.
            if (manualFocusRequest?.vendorLensPosition == null) {
                manualFocusRequest?.diopters?.let { focusDistance ->
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    Camera2Interop.Extender(imageCaptureBuilder)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                }
            }
            manualFocusRequest?.vendorLensPosition?.let { lensPosition ->
                vendorFocusLensPositionKey()?.let { key ->
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(key, lensPosition)
                    Camera2Interop.Extender(imageCaptureBuilder).setCaptureRequestOption(key, lensPosition)
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(key, lensPosition)
                }
            }
            boundMacroStop = false
            lastAppliedFlash = null
            focusRampBuilder = null
            Log.d(
                TAG,
                "bindCamera: AF_MODE_OFF + diopters=${manualFocusRequest?.diopters} vendorLensPos=${manualFocusRequest?.vendorLensPosition} set at bind time via Extender",
            )
        }
        Camera2Interop.Extender(previewBuilder)
            .setSessionCaptureCallback(sessionCaptureCallback)
        // ALSO on ImageCapture: when the stream budget forces CameraX into StreamSharing,
        // Preview is re-parented onto a virtual camera and its interop session callback never
        // fires — the native manual-focus takeover silently died the day VideoCapture joined
        // the group. ImageCapture is never a sharing child, so its callback always reaches the
        // real session; the identity guard inside makes the duplicate registration harmless.
        Camera2Interop.Extender(imageCaptureBuilder)
            .setSessionCaptureCallback(sessionCaptureCallback)
        // AND on ImageAnalysis: ImageCapture only produces results for still captures, so it
        // is silent during preview. ImageAnalysis rides the repeating request and is never a
        // stream-sharing child, which makes it the one reliable per-frame telemetry tap —
        // the source of the live lens position the AF handoff is seeded from.
        Camera2Interop.Extender(analysisBuilder)
            .setSessionCaptureCallback(sessionCaptureCallback)

        val preview = previewBuilder
            .build()
            .also { it.setSurfaceProvider(previewSurface.surfaceProvider) }
        // Use ResolutionSelector for better resolution support including high-res modes
        val targetSize = resolutionFor(desiredResolution)
        if (targetSize != null) {
            try {
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                imageCaptureBuilder.setResolutionSelector(resolutionSelector)
            } catch (_: Exception) {
                // Fall back to target resolution if ResolutionSelector not supported
                @Suppress("DEPRECATION")
                imageCaptureBuilder.setTargetResolution(targetSize)
            }
        }

        val capture = imageCaptureBuilder.build().also { imageCapture ->
            photoCropRatio(desiredAspectRatio)?.let(imageCapture::setCropAspectRatio)
        }
        val analysis = analysisBuilder.build().also { it ->
            // A do-nothing analyzer, kept for one reason: an ImageAnalysis with no analyzer
            // stays INACTIVE and contributes nothing to the repeating request, so its
            // interop capture callback never fires. Active, it is the pipeline's per-frame
            // telemetry tap — the live lens position that seeds the AF handoff comes from
            // here. The frame itself is closed immediately; nothing is read or retained.
            it.setAnalyzer(focusAssistExecutor) { image ->
                val now = SystemClock.elapsedRealtime()
                if (CameraCatalog.isWhiteBalanceAutoUnderwater(
                        currentValue(latestState, ".white_balance"),
                    ) && now - lastUnderwaterAnalysisAtMs >= UNDERWATER_ANALYSIS_INTERVAL_MS
                ) {
                    lastUnderwaterAnalysisAtMs = now
                    val analysisStartedNs = SystemClock.elapsedRealtimeNanos()
                    val observation = underwaterFrameAnalyzer.analyze(
                        image,
                        now,
                    )
                    if (observation != null) lastUnderwaterObservation = observation
                    updateUnderwaterWhiteBalance(
                        observation,
                        now,
                        analysisMicros = (SystemClock.elapsedRealtimeNanos() - analysisStartedNs) / 1_000L,
                    )
                }
                val monitorHeldPlane = afLocked && !afTracking &&
                    now - lastAfHoldMonitorAtMs >= AF_HOLD_MONITOR_INTERVAL_MS
                if (afPullActive || monitorHeldPlane) {
                    val sharpness = centreSharpness(image)
                    latestSharpness = sharpness
                    if (monitorHeldPlane) {
                        lastAfHoldMonitorAtMs = now
                        observeHeldFocusSharpness(sharpness, now)
                    }
                }
                val callback = onPointingGesture
                if (!latestState.recording && callback != null) {
                    val recognizer = pointingRecognizer ?: PointingGestureRecognizer(context) { x, confidence ->
                        // An inference submitted just before Record may complete just after it;
                        // the second gate makes "not recording only" an invariant, not a race.
                        if (!latestState.recording) {
                            callback(
                                PointingGesture(
                                    normalizedX = x,
                                    horizontalFovDegrees = horizontalFovDegrees(),
                                    confidence = confidence,
                                ),
                            )
                        }
                    }.also { pointingRecognizer = it }
                    recognizer.analyze(image, frontCamera = boundLensFacing == CameraSelector.LENS_FACING_FRONT)
                } else {
                    image.close()
                }
            }
        }

        // GPU focus peaking effect — sits in the preview pipeline as a CameraEffect.
        // The shader runs on every preview frame; when peaking is off it's a trivial
        // pass-through (one texture fetch per pixel, negligible cost).
        // Reuse the live GL processor across rebinds: it re-inits per SurfaceRequest
        // (each request's release callback frees only its own surfaces), and keeping it
        // alive means a rebind freezes on the last frame briefly instead of flashing
        // black — this happens on every macro-tail boundary crossing. Only detach()
        // releases it for real.
        val processor = focusPeakingProcessor
            ?: FocusPeakingSurfaceProcessor(ContextCompat.getMainExecutor(context)).also {
                focusPeakingProcessor = it
            }
        processor.peakingEnabled = focusAssistEnabled
        processor.exposureAssistMode = exposureAssistMode(latestState)

        val effect = object : CameraEffect(
            PREVIEW,
            focusAssistExecutor,
            processor,
            { error -> Log.e(TAG, "Focus peaking effect error", error) },
        ) {}

        // Real video: a Recorder-backed VideoCapture rides in the same group. Highest
        // quality the device offers, falling down the ladder rather than failing the bind.
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(
                videoQualitySelector(desiredResolution),
            )
            .setAspectRatio(
                if (desiredAspectRatio == "4:3") AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9,
            )
        VideoDynamicRangePolicy.targetVideoBitrate(desiredHdrLogMode)?.let { bitrate ->
            recorderBuilder.setTargetVideoEncodingBitRate(bitrate)
        }
        val recorder = recorderBuilder.build()
        val supportedDynamicRanges = if (desiredHdrLogMode == "LOG") {
            val selectedCameraInfo = try {
                selector.filter(provider.availableCameraInfos).firstOrNull()
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Could not resolve the selected camera's video capabilities", error)
                null
            }
            selectedCameraInfo
                ?.let { Recorder.getVideoCapabilities(it) }
                ?.supportedDynamicRanges
                .orEmpty()
        } else {
            emptySet()
        }
        val requestedDynamicRange = VideoDynamicRangePolicy.select(
            requestedMode = desiredHdrLogMode,
            supported = supportedDynamicRanges,
        )
        val videoBuilder = VideoCapture.Builder(recorder)
            .setDynamicRange(requestedDynamicRange)
        // In the maximum-information graph VideoCapture replaces ImageAnalysis as the
        // repeating telemetry/session tap. Put every session-critical option on the encoded
        // stream itself so removing the auxiliary surfaces cannot break live manual control.
        val videoInterop = Camera2Interop.Extender(videoBuilder)
        physicalCameraId?.let(videoInterop::setPhysicalCameraId)
        if (isManualFocus) {
            videoInterop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF,
            )
            if (manualFocusRequest?.vendorLensPosition == null) {
                manualFocusRequest?.diopters?.let { focusDistance ->
                    videoInterop.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        focusDistance,
                    )
                }
            }
            manualFocusRequest?.vendorLensPosition?.let { lensPosition ->
                vendorFocusLensPositionKey()?.let { key ->
                    videoInterop.setCaptureRequestOption(key, lensPosition)
                }
            }
        }
        videoInterop.setSessionCaptureCallback(sessionCaptureCallback)
        val video = videoBuilder.build()
        Log.i(TAG, "Video dynamic range=$requestedDynamicRange requestedMode=$desiredHdrLogMode")
        videoCapture = video.takeUnless { highSpeedRecording }

        val useCaseGroup = if (highSpeedRecording) {
            null
        } else {
            UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(video)
                .addEffect(effect)
                .also { builder ->
                    if (!maximumInformationLog) {
                        builder.addUseCase(capture).addUseCase(analysis)
                    }
                }
                .build()
        }
        if (maximumInformationLog) {
            Log.i(
                TAG,
                "Log maximum-information graph: Preview + HLG10 VideoCapture; " +
                    "still/8-bit analysis surfaces omitted",
            )
        }

        // Reset native session state before rebinding. A stale nativeFocusActive
        // from a previous lens would trigger cancelFocusAndMetering() on the new
        // camera, causing CameraX to switch physical cameras.
        nativeFocusActive = false
        cam2Session = null
        cam2Surfaces = emptyList()
        lastAppliedSessionSignature = null
        provider.unbindAll()
        camera = try {
            if (highSpeedRecording) {
                // CameraX needs a high-speed CamcorderProfile. Several Samsung devices omit it
                // despite publishing a valid constrained-high-speed Camera2 map. Keep a genuine
                // preview here; the shutter swaps briefly to Camera2HighSpeedRecorder, which owns
                // the encoder surface and the constrained session for the recording itself.
                Log.i(
                    TAG,
                    "Armed direct constrained-high-speed capture: " +
                        "quality=$desiredResolution fps=$desiredFrameRateFps",
                )
                provider.bindToLifecycle(owner, selector, preview)
            } else {
                provider.bindToLifecycle(owner, selector, requireNotNull(useCaseGroup))
            }
        } catch (error: IllegalArgumentException) {
            val triedDirectPhysicalCamera = desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
                selectedCameraId != null &&
                selectedCameraId == activeLensProfile?.physicalCameraId &&
                selectedCameraId != backCameraProfile?.logicalCameraId
            if (!triedDirectPhysicalCamera && !maximumInformationLog && !highSpeedRecording) {
                // Preview + ImageCapture + ImageAnalysis + VideoCapture can exceed a device's
                // stream-combination budget. Shed the analysis leg — the least critical — and
                // try once more before giving up.
                val reduced = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addUseCase(video)
                    .addEffect(effect)
                    .build()
                try {
                    provider.bindToLifecycle(owner, selector, reduced).also {
                        Log.w(TAG, "Bound without ImageAnalysis: full use-case set over stream budget.")
                    }
                } catch (_: IllegalArgumentException) {
                    throw error
                }
            } else if (triedDirectPhysicalCamera) {
                Log.w(
                    TAG,
                    "Direct physical camera binding failed for cameraId=$selectedCameraId, falling back to logical multi-camera binding.",
                    error,
                )
                selectedCameraId?.let { failedDirectPhysicalCameraIds += it }
                bindCamera(force = true)
                return
            } else {
                // The minimal Log graph has no lower-information surface to shed. Propagate the
                // bind failure instead of quietly adding 8-bit analysis/still streams back in.
                throw error
            }
        }
        // Report this only after a candidate actually binds. The S24 physical camera selector
        // advertises SDR and then rejects binding; its logical-camera fallback advertises HLG10.
        // Logging the rejected candidate as a final error produced a false alarm beside a valid
        // HLG recording.
        boundLogCaptureContractSatisfied = VideoDynamicRangePolicy.isCaptureContractSatisfied(
            requestedMode = desiredHdrLogMode,
            selected = requestedDynamicRange,
        )
        if (!boundLogCaptureContractSatisfied) {
            Log.e(
                TAG,
                "The bound camera has no public 10-bit HLG encoder surface; Log recording is blocked.",
            )
        }
        imageCapture = capture.takeUnless { maximumInformationLog }
            .takeUnless { highSpeedRecording }
        imageAnalysis = analysis.takeUnless { maximumInformationLog || highSpeedRecording }
        camera?.let { refreshBoundCameraCapabilities(it) }
        boundLensFacing = desiredLensFacing
        boundLensValue = selectedLensValue(latestState)
        boundResolution = desiredResolution
        boundFrameRate = desiredFrameRate
        boundHdrLogMode = desiredHdrLogMode
        boundCaptureFormat = desiredCaptureFormat
        boundAspectRatio = desiredAspectRatio
        boundExpectedPhysicalCameraId = activeLensProfile?.physicalCameraId

        // Detect device capabilities from the bound camera

        // Apply zoom once at bind time. setZoomRatio on a logical multi-camera
        // can switch the active physical camera, so we must NOT call it from
        // applySessionState (which runs on every settings change).
        camera?.let { applyZoom(latestState, it) }
        applySessionState(latestState, force = true)
    }


    // ── Video recording ──────────────────────────────────────────────────────────────

    private fun startVideoRecording() {
        if (activeRecording != null || camera2HighSpeedRecorder.isBusy || recordingSegmentFinalizingForReview) return
        if (resolvedHdrLogMode(latestState) == "LOG" && !boundLogCaptureContractSatisfied) {
            Log.e(TAG, "Refusing Log recording because the bound stream is not public HLG10")
            Toast.makeText(
                context,
                "Log recording unavailable: this camera path is not 10-bit HLG",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val startedAt = System.currentTimeMillis()
        val sessionDirectory = File(
            // Recording segments are temporary, but they are not disposable: CameraX must
            // finalise them before preview/publish. Android may evict cacheDir files while a
            // high-bitrate Log recording is still active, so keep them in the non-backed-up
            // private files area and delete them explicitly when the session is finished.
            context.noBackupFilesDir,
            "recording-segments/session-$startedAt-${System.nanoTime()}",
        )
        if (!sessionDirectory.mkdirs() && !sessionDirectory.isDirectory) {
            Log.e(TAG, "Could not create private recording workspace: $sessionDirectory")
            return
        }
        recordingSessionDirectory = sessionDirectory
        recordingSessionDisplayName = "DiveControl_$startedAt.mp4"
        recordingMetadataSnapshot = captureMetadataSnapshot()
        recordingSegmentFiles.clear()
        activeRecordingSegmentFile = null
        recordingReviewFile = null
        recordingSessionActive = true
        completedRecordingDurationMs = 0L
        currentRecordingSegmentDurationMs = 0L
        pendingRecordingAction = null
        RecordingClock.durationMs.value = 0L
        RecordingClock.paused.value = false
        RecordingClock.reviewUri.value = null
        RecordingClock.reviewFinalizing.value = false
        startRecordingSegment()
    }

    /**
     * A paused CameraX MP4 is not guaranteed to be playable until Finalize writes its index. Each
     * review pause is therefore a private segment boundary. The segments are losslessly remuxed
     * for cumulative preview, then Stop publishes one continuous MediaStore video.
     */
    private fun startRecordingSegment() {
        if (activeRecording != null || camera2HighSpeedRecorder.isBusy || recordingSegmentFinalizingForReview) return
        currentRecordingSegmentDurationMs = 0L
        RecordingClock.reviewUri.value = null
        RecordingClock.reviewFinalizing.value = false
        val generation = ++recordingSegmentStartGeneration
        withShutterWhiteBalance {
            if (generation == recordingSegmentStartGeneration &&
                recordingSessionActive &&
                !RecordingClock.paused.value
            ) {
                startRecordingSegmentNow()
            } else {
                releaseShutterWhiteBalance()
            }
        }
    }

    private fun startRecordingSegmentNow() {
        if (activeRecording != null || camera2HighSpeedRecorder.isBusy) return
        if (isHighSpeedSelection(latestState)) {
            startCamera2HighSpeedSegment()
            return
        }
        val capture = videoCapture ?: run {
            Log.w(TAG, "Record requested but VideoCapture is not bound — rebinding.")
            bindCamera(force = true)
            videoCapture
        } ?: run {
            releaseShutterWhiteBalance()
            return
        }
        val sessionDirectory = recordingSessionDirectory ?: run {
            releaseShutterWhiteBalance()
            return
        }
        val segmentFile = File(
            sessionDirectory,
            "segment-${(recordingSegmentFiles.size + 1).toString().padStart(4, '0')}.mp4",
        )
        activeRecordingSegmentFile = segmentFile
        val options = FileOutputOptions.Builder(segmentFile).build()
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED &&
            currentValue(latestState, ".audio_recording") != "Off"
        val pending = capture.output.prepareRecording(context, options).apply {
            if (hasAudio) withAudioEnabled()
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    currentRecordingSegmentDurationMs =
                        event.recordingStats.recordedDurationNanos / 1_000_000L
                    RecordingClock.durationMs.value =
                        completedRecordingDurationMs + currentRecordingSegmentDurationMs
                }
                is VideoRecordEvent.Finalize -> {
                    val reviewBoundary = recordingSegmentFinalizingForReview
                    activeRecording = null
                    if (activeRecordingSegmentFile == segmentFile) {
                        activeRecordingSegmentFile = null
                    }
                    if (event.hasError()) {
                        Log.e(TAG, "Recording finalize error ${event.error}", event.cause)
                        runCatching { segmentFile.delete() }
                        RecordingClock.reviewUri.value = null
                    } else {
                        val finalizedDurationMs = maxOf(
                            currentRecordingSegmentDurationMs,
                            event.recordingStats.recordedDurationNanos / 1_000_000L,
                        )
                        completedRecordingDurationMs += finalizedDurationMs
                        RecordingClock.durationMs.value = completedRecordingDurationMs
                        if (segmentFile.isFile && segmentFile.length() > 0L) {
                            recordingSegmentFiles += segmentFile
                        }
                        Log.i(TAG, "Recording review segment finalized: $segmentFile")
                    }
                    currentRecordingSegmentDurationMs = 0L
                    releaseShutterWhiteBalance()
                    if (reviewBoundary) {
                        if (recordingSegmentFiles.isEmpty()) {
                            recordingSegmentFinalizingForReview = false
                            RecordingClock.reviewFinalizing.value = false
                            runPendingRecordingAction()
                        } else {
                            buildCumulativeRecordingReview()
                        }
                    } else {
                        // An unsolicited finalisation ends the logical session too; leaving a
                        // stale URI/clock behind would imply that Resume can still append.
                        recordingSegmentFinalizingForReview = false
                        finishRecordingSession(deleteLatest = false)
                    }
                }
                else -> Unit
            }
        }
        RecordingClock.paused.value = false
        Log.i(TAG, "Private recording segment started: $segmentFile audio=$hasAudio")
    }

    private fun startCamera2HighSpeedSegment() {
        val sessionDirectory = recordingSessionDirectory ?: run {
            releaseShutterWhiteBalance()
            return
        }
        val fps = currentValue(latestState, ".frame_rate")
            ?.removeSuffix("fps")
            ?.toIntOrNull()
            ?: return
        val size = highSpeedResolutionSize(desiredResolutionValue(latestState)) ?: run {
            Log.e(TAG, "No direct high-speed size for ${desiredResolutionValue(latestState)}")
            releaseShutterWhiteBalance()
            return
        }
        val boundCamera = camera ?: run {
            Log.w(TAG, "High-speed record requested without a bound preview; rebinding")
            bindCamera(force = true)
            camera
        } ?: run {
            releaseShutterWhiteBalance()
            return
        }
        val cameraId = Camera2CameraInfo.from(boundCamera.cameraInfo).cameraId
        val segmentFile = File(
            sessionDirectory,
            "segment-${(recordingSegmentFiles.size + 1).toString().padStart(4, '0')}.mp4",
        )
        activeRecordingSegmentFile = segmentFile
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED &&
            currentValue(latestState, ".audio_recording") != "Off"
        val ev = currentValue(latestState, ".exposure_value", ".exposure_compensation")
            ?.replace("+", "")
            ?.toDoubleOrNull()
        val evIndex = ev?.let { evCompensationIndex(boundCamera, it) }
        val host = previewView ?: run {
            releaseShutterWhiteBalance()
            return
        }

        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
        imageCapture = null
        imageAnalysis = null
        try {
            camera2HighSpeedRecorder.start(
                Camera2HighSpeedRecorder.Request(
                    previewHost = host,
                    cameraId = cameraId,
                    size = size,
                    fps = fps,
                    outputFile = segmentFile,
                    audioEnabled = hasAudio,
                    exposureCompensationIndex = evIndex,
                    zoomRatio = latestState.zoomFactor.toFloat().coerceAtLeast(1f),
                    torchEnabled = currentValue(latestState, ".flash") in setOf("On", "Torch"),
                ),
                onStarted = {
                    currentRecordingSegmentDurationMs = 0L
                    RecordingClock.paused.value = false
                    cameraRequestHandler.removeCallbacks(highSpeedClockRunnable)
                    cameraRequestHandler.post(highSpeedClockRunnable)
                    Log.i(TAG, "Direct high-speed segment started: $segmentFile audio=$hasAudio")
                },
                onFinalized = { result ->
                    finalizeCamera2HighSpeedSegment(segmentFile, result)
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start direct high-speed recording", error)
            activeRecordingSegmentFile = null
            releaseShutterWhiteBalance()
            finishRecordingSession(deleteLatest = false)
            bindCamera(force = true)
        }
    }

    private fun finalizeCamera2HighSpeedSegment(segmentFile: File, result: Result<Long>) {
        cameraRequestHandler.removeCallbacks(highSpeedClockRunnable)
        val reviewBoundary = recordingSegmentFinalizingForReview
        if (activeRecordingSegmentFile == segmentFile) activeRecordingSegmentFile = null
        result.onSuccess { durationMs ->
            currentRecordingSegmentDurationMs = durationMs
            completedRecordingDurationMs += durationMs
            RecordingClock.durationMs.value = completedRecordingDurationMs
            if (segmentFile.isFile && segmentFile.length() > 0L) recordingSegmentFiles += segmentFile
            Log.i(TAG, "Direct high-speed segment finalized: $segmentFile durationMs=$durationMs")
        }.onFailure { error ->
            Log.e(TAG, "Direct high-speed segment finalize failed", error)
            segmentFile.delete()
            RecordingClock.reviewUri.value = null
            Toast.makeText(context, "High-speed recording failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
        currentRecordingSegmentDurationMs = 0L
        releaseShutterWhiteBalance()
        bindCamera(force = true)
        if (reviewBoundary) {
            if (recordingSegmentFiles.isEmpty()) {
                recordingSegmentFinalizingForReview = false
                RecordingClock.reviewFinalizing.value = false
                runPendingRecordingAction()
            } else {
                buildCumulativeRecordingReview()
            }
        } else {
            recordingSegmentFinalizingForReview = false
            finishRecordingSession(deleteLatest = false)
        }
    }

    private fun buildCumulativeRecordingReview() {
        val sessionDirectory = recordingSessionDirectory ?: run {
            recordingSegmentFinalizingForReview = false
            RecordingClock.reviewFinalizing.value = false
            runPendingRecordingAction()
            return
        }
        val segments = recordingSegmentFiles.toList()
        val reviewFile = File(sessionDirectory, "review.mp4")
        RecordingClock.reviewUri.value = null
        RecordingClock.reviewFinalizing.value = true
        recordingFinalizeExecutor.execute {
            val result = RecordingSessionMuxer.buildReview(segments, reviewFile)
            ContextCompat.getMainExecutor(context).execute completion@{
                if (recordingSessionDirectory != sessionDirectory || !recordingSessionActive) {
                    return@completion
                }
                result
                    .onSuccess { uri ->
                        // A single finalized CameraX segment is already reviewable and is
                        // deliberately returned directly to avoid a lossless-but-expensive full
                        // remux. Multi-segment sessions still return the cumulative review file.
                        recordingReviewFile = uri.path
                            ?.let(::File)
                            ?.takeIf { it.isFile && it.length() > 0L }
                        RecordingClock.reviewUri.value = uri
                        Log.i(TAG, "Cumulative recording preview ready: $uri")
                    }
                    .onFailure { error ->
                        recordingReviewFile = null
                        RecordingClock.reviewUri.value = null
                        Log.e(TAG, "Could not build cumulative recording preview", error)
                    }
                recordingSegmentFinalizingForReview = false
                RecordingClock.reviewFinalizing.value = false
                runPendingRecordingAction()
            }
        }
    }

    private fun pauseVideoRecording() {
        recordingSegmentStartGeneration++
        RecordingClock.paused.value = true
        RecordingClock.reviewUri.value = null
        RecordingClock.reviewFinalizing.value = true
        pendingRecordingAction = null
        if (camera2HighSpeedRecorder.isBusy) {
            recordingSegmentFinalizingForReview = true
            camera2HighSpeedRecorder.stop()
            return
        }
        val recording = activeRecording
        if (recording == null) {
            RecordingClock.reviewFinalizing.value = false
            releaseShutterWhiteBalance()
            return
        }
        recordingSegmentFinalizingForReview = true
        // stop(), rather than pause(), is load-bearing: it writes the MP4 index and yields the
        // output URI required by both preview and deletion.
        recording.stop()
    }

    private fun resumeVideoRecording() {
        RecordingClock.paused.value = false
        if (recordingSegmentFinalizingForReview) {
            pendingRecordingAction = CameraCommand.ResumeVideoRecording
            return
        }
        pendingRecordingAction = null
        if (!recordingSessionActive) recordingSessionActive = true
        startRecordingSegment()
    }

    private fun runPendingRecordingAction() {
        val action = pendingRecordingAction
        pendingRecordingAction = null
        when (action) {
            CameraCommand.ResumeVideoRecording -> resumeVideoRecording()
            CameraCommand.StopVideoRecording -> finishRecordingSession(deleteLatest = false)
            CameraCommand.DeleteVideoRecording -> finishRecordingSession(deleteLatest = true)
            else -> Unit
        }
    }

    private fun stopVideoRecording() {
        if (recordingSegmentFinalizingForReview) {
            pendingRecordingAction = CameraCommand.StopVideoRecording
            return
        }
        if (camera2HighSpeedRecorder.isBusy) {
            pendingRecordingAction = CameraCommand.StopVideoRecording
            recordingSegmentFinalizingForReview = true
            RecordingClock.reviewFinalizing.value = true
            camera2HighSpeedRecorder.stop()
            return
        }
        val recording = activeRecording
        if (recording != null) {
            pendingRecordingAction = CameraCommand.StopVideoRecording
            recordingSegmentFinalizingForReview = true
            RecordingClock.reviewFinalizing.value = true
            recording.stop()
            return
        }
        finishRecordingSession(deleteLatest = false)
    }

    private fun deleteVideoRecording() {
        if (recordingSegmentFinalizingForReview) {
            pendingRecordingAction = CameraCommand.DeleteVideoRecording
            return
        }
        if (camera2HighSpeedRecorder.isBusy) {
            pendingRecordingAction = CameraCommand.DeleteVideoRecording
            recordingSegmentFinalizingForReview = true
            RecordingClock.reviewFinalizing.value = true
            camera2HighSpeedRecorder.stop()
            return
        }
        finishRecordingSession(deleteLatest = true)
    }

    private fun finishRecordingSession(deleteLatest: Boolean) {
        recordingSegmentStartGeneration++
        val sessionDirectory = recordingSessionDirectory
        val segments = recordingSegmentFiles.toList()
        val preparedReview = recordingReviewFile
        val displayName = recordingSessionDisplayName
            ?: "DiveControl_${System.currentTimeMillis()}.mp4"
        val saveLocation = latestState.recordingSaveLocation
        val metadataSnapshot = recordingMetadataSnapshot
        recordingSessionActive = false
        pendingRecordingAction = null
        recordingSegmentFinalizingForReview = false
        recordingSessionDirectory = null
        recordingSessionDisplayName = null
        recordingSegmentFiles.clear()
        activeRecordingSegmentFile = null
        recordingReviewFile = null
        recordingMetadataSnapshot = null
        completedRecordingDurationMs = 0L
        currentRecordingSegmentDurationMs = 0L
        RecordingClock.durationMs.value = 0L
        RecordingClock.paused.value = false
        RecordingClock.reviewUri.value = null
        RecordingClock.reviewFinalizing.value = false
        releaseShutterWhiteBalance()

        if (deleteLatest) {
            sessionDirectory?.let { directory ->
                runCatching { directory.deleteRecursively() }
                    .onSuccess { Log.i(TAG, "Deleted private recording session: $directory") }
                    .onFailure { error -> Log.e(TAG, "Could not delete recording session", error) }
            }
            return
        }
        if (segments.isEmpty()) {
            Log.w(TAG, "Stop requested but the recording session has no valid segments")
            sessionDirectory?.deleteRecursively()
            return
        }

        recordingFinalizeExecutor.execute {
            val result = if (preparedReview?.isFile == true && preparedReview.length() > 0L) {
                RecordingSessionMuxer.publishPreparedFile(
                    context = context,
                    preparedFile = preparedReview,
                    displayName = displayName,
                    location = saveLocation,
                )
            } else {
                RecordingSessionMuxer.publish(
                    context = context,
                    segmentFiles = segments,
                    displayName = displayName,
                    location = saveLocation,
                )
            }
            result
                .onSuccess { uri ->
                    sessionDirectory?.deleteRecursively()
                    Log.i(TAG, "Recording session published to ${saveLocation.relativePath}: $uri")
                    metadataSnapshot?.let {
                        writeMetadataSidecar(displayName, saveLocation, it)
                    }
                }
                .onFailure { error ->
                    Log.e(
                        TAG,
                        "Could not publish recording; private recovery files retained at $sessionDirectory",
                        error,
                    )
                }
        }
    }

    /**
     * Live wheel zoom. CameraX owns the ratio; skipped while the native manual-focus session
     * has the pipe, because a CameraControl call there would tear that session down.
     */
    private fun applyLiveZoom(ratio: Double) {
        if (nativeFocusActive) return
        try {
            camera?.cameraControl?.setZoomRatio(ratio.toFloat())
        } catch (e: Exception) {
            Log.w(TAG, "setZoomRatio failed: ${e.message}")
        }
    }

    /**
     * The capability probe: what THIS phone's back camera actually offers, read once from
     * Camera2 and reported into the core so the catalog can clip its option ladders to
     * reality instead of trusting a hand-written table.
     */
    private fun reportCameraCapabilities() {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val selectedCameraInfo = selectedCameraInfoForCapabilities()
            val cameraId = selectedCameraInfo
                ?.let { Camera2CameraInfo.from(it).cameraId }
                ?: selectedCameraIdForBinding(latestState, desiredLensFacing(latestState))
                ?: backCameraProfile?.logicalCameraId
                ?: manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: return
            val chars = manager.getCameraCharacteristics(cameraId)
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.let { it.numerator.toDouble() / it.denominator.toDouble() }
                ?: 0.1
            // The native Pro camera sizes its rulers from Samsung's VENDOR characteristics, not
            // the public ones, and both diverge on this hardware: aeCompensationRange is [-40,40]
            // (+/-4.0 EV) against the public [-20,20], and the vendor exposureTimeRange floor is
            // 1/12000 against the public ~1/20000 — the stock dial's actual fast stop. Reading
            // them here is what lets the catalog clip its native tables to the native window.
            // Both are CameraCharacteristics values resolved through the global vendor tag
            // descriptor; on hardware where they do not resolve, the public window stands and
            // the dials honestly show the narrower range.
            val vendorEvRange = vendorCharacteristic(chars, "samsung.android.control.aeCompensationRange", IntArray::class.java)
                ?.takeIf { it.size >= 2 }
            val vendorExposureRange = vendorCharacteristic(chars, "samsung.android.sensor.info.exposureTimeRange", LongArray::class.java)
                ?.takeIf { it.size >= 2 }
            val evIndexLower = vendorEvRange?.get(0) ?: evRange?.lower
            val evIndexUpper = vendorEvRange?.get(1) ?: evRange?.upper
            evCompensationIndexRange = if (evIndexLower != null && evIndexUpper != null) {
                android.util.Range(evIndexLower, evIndexUpper)
            } else {
                null
            }
            // Fast floor: the native dial is trimmed by the vendor floor (1/12000 here), never by
            // the public one. Fall back to the same constant the native table implies, but never
            // below what this sensor can actually do.
            val shutterFloorNs = (vendorExposureRange?.get(0) ?: CameraCatalog.SHUTTER_NATIVE_MIN_NS)
                .coerceAtLeast(exposureRange?.lower ?: CameraCatalog.SHUTTER_NATIVE_MIN_NS)
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val zoomMax: Double? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper?.toDouble()
            } else {
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble()
            }
            val videoCapabilities = selectedCameraInfo?.let(::probeRecorderVideoCapabilities)
            val videoFrameRates = videoCapabilities
                ?.frameRatesByResolution
                ?.values
                ?.flatten()
                ?.distinct()
                ?.sorted()
                .orEmpty()
            val videoResolutions = videoCapabilities?.resolutions.orEmpty()
            val stabilizationModes = chars.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            )?.toSet().orEmpty()
            val ultraHdrSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.ImageFormat.JPEG_R)
                    ?.isNotEmpty() == true
            } else {
                false
            }
            val caps = CameraCapabilities(
                isoMin = isoRange?.lower,
                isoMax = isoRange?.upper,
                exposureMinNs = if (exposureRange != null) shutterFloorNs else null,
                // The public ceiling stands: the vendor 30 s tail is the stock app's private
                // still-capture pipeline, not a live-preview range this session can hold.
                exposureMaxNs = exposureRange?.upper,
                evMin = evIndexLower?.let { it * evStep },
                evMax = evIndexUpper?.let { it * evStep },
                manualFocusSupported = minFocus > 0f,
                zoomMaxRatio = zoomMax,
                availableVideoFrameRates = videoFrameRates,
                availableVideoResolutions = videoResolutions,
                videoFrameRatesByResolution = videoCapabilities?.frameRatesByResolution.orEmpty(),
                videoStabilizationSupported =
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in stabilizationModes,
                ultraHdrJpegSupported = ultraHdrSupported,
            )
            sensorColorCalibration = runCatching { readSensorColorCalibration(chars) }.getOrNull()
            Log.i(
                TAG,
                "Camera capabilities probed: $caps (vendorEv=${vendorEvRange?.contentToString()} " +
                    "vendorExp=${vendorExposureRange?.contentToString()} evStep=$evStep " +
                    "sensorColorCal=${sensorColorCalibration?.let { "${it.cct1.toInt()}K/${it.cct2.toInt()}K" }})",
            )
            onCapabilities?.invoke(caps)
        } catch (e: Exception) {
            Log.w(TAG, "Capability probe failed: ${e.message}")
        }
    }

    private data class RecorderVideoCapabilities(
        val resolutions: List<String>,
        val frameRatesByResolution: Map<String, List<Int>>,
    )

    /**
     * The menu is derived from the same Recorder and session types used to capture. Querying
     * Camera2 sizes alone would advertise streams the encoder cannot consume, while querying
     * CamcorderProfile alone cannot prove a high-speed camera session for that quality.
     */
    private fun probeRecorderVideoCapabilities(cameraInfo: androidx.camera.core.CameraInfo): RecorderVideoCapabilities {
        val normalCapabilities = Recorder.getVideoCapabilities(cameraInfo)
        val normalQualities = normalCapabilities.getSupportedQualities(DynamicRange.SDR)
        val highSpeedCapabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)
        val highSpeedQualities = highSpeedCapabilities
            ?.getSupportedQualities(DynamicRange.SDR)
            .orEmpty()
        val cameraNumericId = Camera2CameraInfo.from(cameraInfo).cameraId.toIntOrNull()
        val camcorderHighSpeedProfiles = cameraNumericId?.let { id ->
            listOf(
                "720p" to CamcorderProfile.QUALITY_HIGH_SPEED_720P,
                "1080p" to CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
                "2160p" to CamcorderProfile.QUALITY_HIGH_SPEED_2160P,
            ).filter { (_, quality) -> CamcorderProfile.hasProfile(id, quality) }
                .associate { (label, quality) ->
                    val profile = CamcorderProfile.get(id, quality)
                    label to "${profile.videoFrameWidth}x${profile.videoFrameHeight}@${profile.videoFrameRate}"
                }
        }.orEmpty()
        Log.i(
            TAG,
            "High-speed capability inputs cameraId=${Camera2CameraInfo.from(cameraInfo).cameraId}: " +
                "cameraXQualities=$highSpeedQualities camcorderProfiles=$camcorderHighSpeedProfiles",
        )
        val supportedQualities = recorderQualityLabels.filter { (quality, _) -> quality in normalQualities }
        val camera2HighSpeedRates = probeCamera2HighSpeedRates(cameraInfo)
        val ratesByResolution = linkedMapOf<String, List<Int>>()

        supportedQualities.forEach { (quality, label) ->
            val normalRecorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            val normalVideo = VideoCapture.withOutput(normalRecorder)
            val normalConfig = SessionConfig.Builder(normalVideo).build()
            val normalRates = cameraInfo.getSupportedFrameRateRanges(normalConfig)
                .map { it.upper }
                .filter { it in recorderFrameRateCandidates && it < HIGH_SPEED_FPS_MIN }

            val highSpeedRates = if (quality in highSpeedQualities) {
                val highSpeedRecorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality))
                    .build()
                val highSpeedVideo = VideoCapture.withOutput(highSpeedRecorder)
                val highSpeedPreview = Preview.Builder().build()
                val highSpeedConfig = HighSpeedVideoSessionConfig.Builder(highSpeedVideo)
                    .setPreview(highSpeedPreview)
                    .setSlowMotionEnabled(false)
                    .build()
                cameraInfo.getSupportedFrameRateRanges(highSpeedConfig)
                    .map { it.upper }
                    .filter { it >= HIGH_SPEED_FPS_MIN }
            } else {
                emptyList()
            }
            ratesByResolution[label] = (
                normalRates + highSpeedRates + camera2HighSpeedRates[label].orEmpty()
                ).distinct().sorted()
        }
        camera2HighSpeedRates.forEach { (label, rates) ->
            if (rates.isNotEmpty() && label !in ratesByResolution) {
                ratesByResolution[label] = rates
            }
        }
        val resolutions = ratesByResolution.keys.toList()
        Log.i(
            TAG,
            "Recorder capabilities cameraId=${Camera2CameraInfo.from(cameraInfo).cameraId}: " +
                "resolutions=$resolutions fpsByResolution=$ratesByResolution",
        )
        return RecorderVideoCapabilities(resolutions, ratesByResolution)
    }

    /**
     * Some Samsung devices publish the standard Camera2 constrained-high-speed map but omit the
     * high-speed CamcorderProfile that CameraX requires. In that case CameraX correctly returns
     * no high-speed capability even though a hardware encoder and camera stream form a valid
     * pair. Keep only the intersection; the direct Camera2 fallback records exactly these pairs.
     */
    private fun probeCamera2HighSpeedRates(
        cameraInfo: androidx.camera.core.CameraInfo,
    ): Map<String, List<Int>> {
        val streamMap = Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        ) ?: return emptyMap()
        val ratesByLabel = linkedMapOf<String, MutableSet<Int>>()
        streamMap.highSpeedVideoSizes.orEmpty().forEach { size ->
            val label = highSpeedResolutionLabel(size) ?: return@forEach
            val rates = runCatching { streamMap.getHighSpeedVideoFpsRangesFor(size) }
                .getOrDefault(emptyArray())
            rates.map { it.upper }
                .filter { fps ->
                    fps >= HIGH_SPEED_FPS_MIN && encoderSupportsHighSpeed(size, fps)
                }
                .forEach { fps -> ratesByLabel.getOrPut(label) { linkedSetOf() } += fps }
        }
        return ratesByLabel.mapValues { (_, rates) -> rates.sorted() }.also { result ->
            Log.i(TAG, "Camera2 + encoder high-speed intersection: $result")
        }
    }

    private fun encoderSupportsHighSpeed(size: Size, fps: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { mime ->
                mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                    runCatching {
                        codec.getCapabilitiesForType(mime)
                            .videoCapabilities
                            ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
                    }.getOrDefault(false)
            }
        }
    }.getOrDefault(false)

    private fun highSpeedResolutionLabel(size: Size): String? = when (size.width to size.height) {
        1280 to 720 -> "HD 720p"
        1920 to 1080 -> "FHD"
        1920 to 824 -> "FHD 1920×824"
        3840 to 2160 -> "UHD 4K"
        else -> null
    }

    private fun selectedCameraInfoForCapabilities(): androidx.camera.core.CameraInfo? {
        camera?.cameraInfo?.let { return it }
        val provider = cameraProvider ?: return null
        val lensFacing = desiredLensFacing(latestState)
        val selectedId = selectedCameraIdForBinding(latestState, lensFacing)
        val selectorBuilder = CameraSelector.Builder().requireLensFacing(lensFacing)
        selectedId?.let { id ->
            selectorBuilder.addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == id }
            }
        }
        return runCatching { selectorBuilder.build().filter(provider.availableCameraInfos).firstOrNull() }
            .getOrNull()
    }

    /** A Samsung vendor characteristic by name, or null wherever the tag does not resolve. */
    private fun <T> vendorCharacteristic(
        chars: android.hardware.camera2.CameraCharacteristics,
        name: String,
        type: Class<T>,
    ): T? = try {
        chars.get(CameraCharacteristics.Key(name, type))
    } catch (_: Exception) {
        null
    }

    private fun detectDeviceCapabilitiesViaCameraManager() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            frontCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            frontCameraId?.let { fId ->
                frontCameraMinFocusDistance = cameraManager.getCameraCharacteristics(fId)
                    .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                Log.d(TAG, "Front camera $fId minFocusDistance=$frontCameraMinFocusDistance")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                backCameraProfile = cameraManager.cameraIdList
                    .mapNotNull { cameraId ->
                        val chars = cameraManager.getCameraCharacteristics(cameraId)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing != CameraCharacteristics.LENS_FACING_BACK) {
                            return@mapNotNull null
                        }
                        val physicalIds = chars.physicalCameraIds
                        if (physicalIds.isEmpty()) {
                            return@mapNotNull null
                        }
                        val logicalFocalLength = chars.get(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                        )?.firstOrNull() ?: return@mapNotNull null
                        val physicalLenses = physicalIds.mapNotNull physicalLoop@{ physicalId ->
                            val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                            val physicalFocalLength = physicalChars.get(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                            )?.firstOrNull() ?: return@physicalLoop null
                            PhysicalLensProfile(
                                logicalCameraId = cameraId,
                                physicalCameraId = physicalId,
                                facing = physicalChars.get(CameraCharacteristics.LENS_FACING)
                                    ?: CameraCharacteristics.LENS_FACING_BACK,
                                focalLengthMm = physicalFocalLength,
                                minFocusDistance = physicalChars.get(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                                ) ?: 0f,
                                afModes = physicalChars.get(
                                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                                )?.toSet().orEmpty(),
                                vendorFocusLensRange = vendorFocusLensRange(physicalChars),
                                vendorFocusCalibration = vendorFocusCalibration(physicalChars),
                            )
                        }.distinctBy { it.physicalCameraId }
                        if (physicalLenses.isEmpty()) {
                            return@mapNotNull null
                        }
                        val streamConfigMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val maxResolution = streamConfigMap
                            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                            ?.maxByOrNull { it.width * it.height }
                        BackCameraProfile(
                            logicalCameraId = cameraId,
                            logicalFocalLengthMm = logicalFocalLength,
                            physicalLenses = physicalLenses,
                            maxSupportedResolution = maxResolution,
                        )
                    }
                    .maxWithOrNull(
                        compareBy<BackCameraProfile> { it.physicalLenses.size }
                            .thenBy { profile ->
                                (profile.physicalLenses.maxOfOrNull { lens -> lens.focalLengthMm }
                                    ?: 0f) - (profile.physicalLenses.minOfOrNull { lens -> lens.focalLengthMm } ?: 0f)
                            }
                            .thenBy { profile -> profile.physicalLenses.count { lens -> lens.supportsManualFocus } },
                    )
            }

            backLensAssignments = backCameraProfile?.let(::assignBackLensLabels).orEmpty()
            lensFocusCapabilities = buildLensFocusCapabilities(backLensAssignments)
            backCameraProfile?.let { profile ->
                deviceMaxSupportedResolution = profile.maxSupportedResolution
                deviceMinFocusDistance = (backLensAssignments["1x"] ?: profile.physicalLenses.maxByOrNull { lens ->
                    lens.minFocusDistance
                })?.minFocusDistance ?: 0f
                Log.d(
                    TAG,
                    "Detected back lenses: ${
                        backLensAssignments.entries.joinToString { (label, lens) ->
                            "$label=${lens.physicalCameraId ?: lens.logicalCameraId}@${lens.focalLengthMm}mm minFocus=${lens.minFocusDistance}"
                        }
                    }",
                )
                // ── Detailed diagnostics for each physical lens ──
                for ((label, lens) in backLensAssignments) {
                    try {
                        val camId = lens.physicalCameraId ?: lens.logicalCameraId
                        val chars = cameraManager.getCameraCharacteristics(camId)
                        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        val minFd = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        Log.d(
                            TAG,
                            "DIAG [$label] camId=$camId focal=${focalLengths?.toList()} minFocusDist=$minFd afModes=${afModes?.toList()} hwLevel=$hwLevel vendorRange=${lens.vendorFocusLensRange} transport=${lensFocusCapabilities[label]?.transport}",
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "DIAG [$label] failed: ${e.message}")
                    }
                }
                // Also check the logical camera's AF/focus capabilities
                try {
                    val logicalChars = cameraManager.getCameraCharacteristics(profile.logicalCameraId)
                    val afModes = logicalChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    val minFd = logicalChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    Log.d(TAG, "DIAG [logical] camId=${profile.logicalCameraId} minFocusDist=$minFd afModes=${afModes?.toList()}")
                } catch (_: Exception) {}
                // Front camera diagnostics
                frontCameraId?.let { fId ->
                    try {
                        val fChars = cameraManager.getCameraCharacteristics(fId)
                        val afModes = fChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        val minFd = fChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val focalLengths = fChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        Log.d(TAG, "DIAG [front] camId=$fId focal=${ focalLengths?.toList() } minFocusDist=$minFd afModes=${afModes?.toList()}")
                    } catch (_: Exception) {}
                }
                // ── Report detected lenses back to the UI state ──
                reportDetectedLenses()
                return
            }

            // Fallback for devices where CameraManager does not expose logical physical lens info.
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    deviceMinFocusDistance = chars.get(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                    ) ?: 10f

                    val streamConfigMap = chars.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                    )
                    if (streamConfigMap != null) {
                        val outputSizes = streamConfigMap.getOutputSizes(android.graphics.ImageFormat.JPEG)
                        deviceMaxSupportedResolution = outputSizes?.maxByOrNull { it.width * it.height }
                    }
                    break
                }
            }
            // Report even for fallback devices
            reportDetectedLenses()
        } catch (_: Exception) {
            // Keep safe defaults
        }
    }

    /**
     * Build the dynamic lens list from detected hardware and report it via callback.
     * This list includes "Auto" as the first option, then all detected physical lenses,
     * plus "2x" (digital crop on main) if a main lens exists, and "front" if detected.
     */
    private fun reportDetectedLenses() {
        val lenses = mutableListOf("Auto")
        // Add in optical order: 0.6x, 1x, 2x (digital), 3x, 5x
        if (backLensAssignments.containsKey("0.6x")) lenses.add("0.6x")
        if (backLensAssignments.containsKey("1x")) {
            lenses.add("1x")
            lenses.add("2x") // Digital crop on main lens
        }
        if (backLensAssignments.containsKey("3x")) lenses.add("3x")
        if (backLensAssignments.containsKey("5x")) lenses.add("5x")
        if (frontCameraId != null) lenses.add("front")
        Log.d(TAG, "Detected lenses for UI: $lenses")
        onDetectedLenses?.invoke(lenses)
        reportCameraCapabilities()
    }

    private fun applySessionState(cameraState: CameraState, force: Boolean = false) {
        if (force) {
            focusSlewGen++
            lensFocusApplied = manualFocusSettingKey(cameraState)
                ?.let { cameraState.settingValues[it] ?: "AF" }
        }
        val boundCamera = camera ?: return
        val filterValue = currentValue(cameraState, ".filters")
        val underwater = CameraCatalog.isWhiteBalanceAutoUnderwater(
            currentValue(cameraState, ".white_balance"),
        )
        val underwaterSolution = underwaterCommandSolution.takeIf { underwater }
        val signature = SessionSignature(
            flash = currentValue(cameraState, ".flash"),
            exposure = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure"),
            lens = currentValue(cameraState, ".lens"),
            hdrLog = resolvedHdrLogMode(cameraState),
            whiteBalance = currentValue(cameraState, ".white_balance"),
            filter = filterValue,
            manualFocus = currentValue(cameraState, ".manual_focus"),
            iso = currentValue(cameraState, ".iso"),
            shutter = currentValue(cameraState, ".shutter_speed"),
            resolution = desiredResolutionValue(cameraState),
            frameRate = currentValue(cameraState, ".frame_rate"),
            metering = currentValue(cameraState, ".metering"),
            stabilization = currentValue(cameraState, ".video_stabilization"),
            waterPressureKpa = latestWaterPressureKpa?.takeIf { filterValue == "Auto" },
            atmosphericPressureKpa = latestAtmosphericPressureKpa?.takeIf { filterValue == "Auto" },
            underwaterKelvin = underwaterSolution?.kelvin,
            underwaterTintTenThousandths = underwaterSolution?.let {
                (it.tintDuv * 10_000.0).roundToInt()
            },
        )
        if (!force && signature == lastAppliedSessionSignature) {
            return
        }

        // WHEEL FAST PATH: the focus plane moved and nothing else did, from one manual value to
        // another. Rebuilding the whole capture request for that — white balance, exposure,
        // tonemap, zoom, colour correction — measured ~45 ms, far longer than the 16 ms cadence
        // the wheel delivers, so continuous turning stalled and caught up in visible bursts.
        // Write just the focus key through the cached ramp builder, exactly as the glide does.
        //
        // Requiring BOTH sides to be numeric keeps every AF transition on the full path, where
        // the autofocus bookkeeping below lives.
        val previous = lastAppliedSessionSignature
        if (!force && previous != null &&
            previous.manualFocus != signature.manualFocus &&
            previous.manualFocus?.toDoubleOrNull() != null &&
            signature.manualFocus?.toDoubleOrNull() != null &&
            previous.copy(manualFocus = signature.manualFocus) == signature
        ) {
            val capability = selectedFocusCapability(cameraState)
            val maxDiopters = capability?.let { reachableDioptersCached(it) }?.takeIf { it > 0f }
            val dial = signature.manualFocus.toDouble().coerceIn(0.0, 1.0)
            if (maxDiopters != null && commandFocusDistance(((1.0 - dial) * maxDiopters).toFloat())) {
                lastAppliedSessionSignature = signature
                lensFocusApplied = signature.manualFocus
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "wheelFocus ${signature.manualFocus}")
                }
                return
            }
            // No session to write to — fall through and rebuild properly.
        }

        val manualFocusRequest = manualFocusRequestFor(cameraState)
        val isManualFocus = manualFocusRequest != null
        if (isManualFocus) {
            // Leaving auto: forget the held plane, the dial owns focus again.
            afSearchGen++
            afPullActive = false
            afLocked = false
            afLockPending = false
            afHoldDiopters = null
            afHoldSharpness = null
            afSharpnessLossSamples = 0
            afTracking = false
        } else if (afHoldDiopters == null && !afTracking && lensFocusApplied?.toDoubleOrNull() != null) {
            // Entering auto from a plane the diver set: start the search THERE, so the pull runs
            // from their plane to the subject at the ramp rate rather than jumping.
            afHoldDiopters = manualFocusRequestFor(
                cameraState.copy(
                    settingValues = cameraState.settingValues +
                        (manualFocusSettingKey(cameraState).orEmpty() to lensFocusApplied.orEmpty()),
                ),
            )?.diopters
            afHoldSharpness = null
            cameraRequestHandler.post { startAutofocusTracking("entered auto") }
        }

        if (isManualFocus) {
            // ── NATIVE MODE: one writer, and it must be this one ─────────
            // Our own repeating request is what actually moves the lens between frames. Routing
            // focus through CameraX's option bundle instead looked right in the logs but only
            // ever took effect at bind time, so focus changed on restart and nowhere else.
            //
            // It also has to be the ONLY writer: when both drive the session they alternate with
            // slightly different 3A state and the preview pulses in brightness as focus moves.
            // So nothing else is applied here — this request already carries flash, exposure,
            // ISO, shutter, white balance and tonemap alongside the focus distance.
            nativeFocusActive = true
            if (!submitNativeRepeatingRequest(cameraState, boundCamera)) {
                // Do not latch the signature on failure — the next state tick must retry
                // instead of being suppressed by an "already applied" latch.
                return
            }
        } else if (cam2Session != null && cam2Surfaces.isNotEmpty()) {
            // ── AUTOFOCUS, still on OUR request ──────────────────────────
            // Keeping the same writer across the AF/manual boundary is what makes the crossing
            // invisible: only CONTROL_AF_MODE changes, inside a request whose exposure envelope,
            // white balance and tonemap all stay exactly as they were.
            nativeFocusActive = true
            if (!submitNativeRepeatingRequest(cameraState, boundCamera)) {
                return
            }
        } else {
            // ── CAMERAX MODE (no session captured yet) ───────────────────
            // EV rides applyCamera2Options' bundle, not a separate CameraX call: two writers
            // alternating on the same session is what used to make the preview pulse.
            applyFlash(cameraState)
            applyCamera2Options(cameraState, boundCamera)
            if (nativeFocusActive) {
                nativeFocusActive = false
                boundCamera.cameraControl.cancelFocusAndMetering()
                Log.d(TAG, "Returning to CameraX control — forced re-establish")
            }
        }
        lastAppliedSessionSignature = signature
    }

    /** Whether the SESSION currently bound was configured with the near limit engaged. */
    private var boundMacroStop: Boolean = false
    @Volatile private var pendingMacroStop: Boolean = false
    private val macroStopRebindRunnable = Runnable {
        if (pendingMacroStop != boundMacroStop) {
            Log.d(TAG, "Macro stop " + (if (pendingMacroStop) "engaged" else "released") + " — rebinding session")
            bindCamera(force = true)
        }
    }

    /**
     * The macro stop is a session parameter, so entering or leaving it needs a rebind. Debounced,
     * so a wheel spinning through 0.00 does not pay for it — only settling there does.
     */
    private fun scheduleMacroStopRebindIfNeeded(engaged: Boolean) {
        pendingMacroStop = engaged
        cameraRequestHandler.removeCallbacks(macroStopRebindRunnable)
        if (engaged != boundMacroStop) {
            cameraRequestHandler.postDelayed(macroStopRebindRunnable, MACRO_STOP_REBIND_DEBOUNCE_MS)
        }
    }

    /**
     * Submit a repeating request directly on the Camera2 CameraCaptureSession.
     * Includes ALL camera settings (focus, AE, AWB, ISO, shutter, HDR).
     * CameraX is completely bypassed — no CameraControl calls while this is active.
     */
    /**
     * Everything about the frame's LOOK — tonemap, white balance, ISO/shutter, EV, flash — in
     * one writer, shared by the full repeating request AND the cached focus-ramp builder.
     *
     * It exists because these used to live inline in [submitNativeRepeatingRequest] only, while
     * [commandFocusDistance]'s cached builder carried a bare TEMPLATE_PREVIEW: the moment any
     * focus step ran, the repeating request silently reverted to template defaults — AWB auto,
     * AE on, no EV, no LOG curve — and every manual exposure setting fell off the frame until
     * the next full rebuild. "Adjusting focus changes my white balance" was exactly this.
     */
    private fun populateSessionLook(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        // ── HDR / public 10-bit wide-dynamic-range video ──
        when (resolvedHdrLogMode(cameraState)) {
            "HDR" -> {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
            }
            "LOG" -> {
                // Samsung Log is not exposed to third-party Camera2 clients. The LOG control
                // therefore uses the public 10-bit HLG stream selected at bind time and never
                // installs a fabricated SDR tonemap curve.
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }
            else -> builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }
        applyMaximumInformationLogControls(builder, cameraState)
        applyRequestedVideoStabilization(builder, cameraState, boundCamera)

        // ── White Balance ──
        val wbValue = currentValue(cameraState, ".white_balance")
        val autoUnderwater = CameraCatalog.isWhiteBalanceAutoUnderwater(wbValue)
        val filterProfile = if (autoUnderwater) null else underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (autoUnderwater && underwaterCommandSolution == null) {
            // Persisted AU starts in this bootstrap state. Let Samsung converge first, then the
            // estimator seeds from that exact frame and takes over without a white-point jump.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        } else if (autoUnderwater) {
            val colour = underwaterWhiteBalanceColour(cameraState)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colour.first)
            colour.second?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else if (filterProfile != null) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
            lastAutoColorTransform?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            val kelvin = wbValue?.removeSuffix("K")?.toIntOrNull()
            if (kelvin != null) {
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                val colour = manualWbColour(kelvin)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colour.first)
                colour.second?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                builder.set(
                    CaptureRequest.CONTROL_AWB_LOCK,
                    shutterAwbLockActive && CameraCatalog.isWhiteBalanceAutoShutter(wbValue),
                )
            }
        }

        // ── AE / ISO / Shutter ──
        // Manual exposure per the Camera2 contract: AE OFF requires BOTH a sensitivity
        // and an exposure time in the request, or the missing half falls to a template
        // default. Like the native Pro camera, a lone manual ISO (or shutter) keeps its
        // partner at whatever auto-exposure last chose, observed live off the pipe.
        val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
        val shutterNs = currentValue(cameraState, ".shutter_speed")?.let { parseShutterNs(it) }
        // Native video rule (AeAfController): a manual shutter in a recording mode pins
        // SENSOR_FRAME_DURATION to one frame period, so a slow shutter can never stretch
        // the frame and sag the recording's rate; the exposure is clamped to the same
        // period. The catalog already clips the DIAL to the frame period — this is the
        // write-time backstop for values arriving from persistence or mid-change states.
        val framePeriodNs = if (shutterNs != null) CameraCatalog.videoShutterCapNs(cameraState) else null
        if (isoValue != null || shutterNs != null) {
            val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val iso = isoValue ?: lastAeSensitivity ?: 800
            val exposure = (shutterNs ?: lastAeExposureNs ?: 16_666_666L)
                .let { ns -> framePeriodNs?.let { ns.coerceAtMost(it) } ?: ns }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            if (isoRange != null) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
            }
            if (exposureRange != null) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceIn(exposureRange.lower, exposureRange.upper))
            }
            framePeriodNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        val aeOff = isoValue != null || shutterNs != null
        applyRequestedFrameRate(builder, cameraState, boundCamera)
        if (!aeOff) applyRequestedMetering(builder, cameraState, boundCamera)

        // ── Exposure Compensation (pass through when AE is ON) ──
        // With AE off the index means nothing and the native app flips EV to a read-only
        // meter — the reducer refuses the detents, and nothing is written here.
        if (!aeOff) {
            val userEv = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                ?.replace("+", "")?.toDoubleOrNull()
            if (userEv != null) {
                val effectiveEv = SamsungLogProfile.effectiveAutoExposureEv(
                    userEv = userEv,
                    calibration = samsungLogAcquisitionCalibration(cameraState),
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evCompensationIndex(boundCamera, effectiveEv),
                )
            }
        }

        // ── Flash ──
        val flashValue = currentValue(cameraState, ".flash")
        if (flashValue == "On" || flashValue == "Torch") {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
    }

    private fun applyMaximumInformationLogControls(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
    ) {
        if (resolvedHdrLogMode(cameraState) != "LOG") return
        maximumInformationRequestModes.noiseReductionMode?.let { mode ->
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, mode)
        }
        maximumInformationRequestModes.edgeMode?.let { mode ->
            builder.set(CaptureRequest.EDGE_MODE, mode)
        }
        maximumInformationRequestModes.videoStabilizationMode?.let { mode ->
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
        }
    }

    private fun applyMaximumInformationLogControls(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
    ) {
        if (resolvedHdrLogMode(cameraState) != "LOG") return
        maximumInformationRequestModes.noiseReductionMode?.let { mode ->
            builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, mode)
        }
        maximumInformationRequestModes.edgeMode?.let { mode ->
            builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, mode)
        }
        maximumInformationRequestModes.videoStabilizationMode?.let { mode ->
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
        }
    }

    private fun requestedFpsRange(cameraState: CameraState, boundCamera: Camera): android.util.Range<Int>? {
        val fps = currentValue(cameraState, ".frame_rate")
            ?.removeSuffix("fps")
            ?.toIntOrNull()
            ?: return null
        if (fps >= HIGH_SPEED_FPS_MIN) return null
        val ranges = Camera2CameraInfo.from(boundCamera.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        ).orEmpty()
        return ranges
            .filter { fps in it.lower..it.upper }
            .minWithOrNull(
                compareBy<android.util.Range<Int>>(
                    { if (it.lower == fps && it.upper == fps) 0 else 1 },
                    { it.upper - it.lower },
                    { kotlin.math.abs(it.upper - fps) },
                ),
            )
    }

    private fun applyRequestedFrameRate(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedFpsRange(cameraState, boundCamera)?.let {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
    }

    private fun applyRequestedFrameRate(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedFpsRange(cameraState, boundCamera)?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
    }

    private fun requestedMeteringRegion(
        cameraState: CameraState,
        boundCamera: Camera,
    ): MeteringRectangle? {
        val mode = currentValue(cameraState, ".metering")
        if (mode == null || mode == "Matrix") return null
        val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
        val maxRegions = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxRegions <= 0) return null
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val fraction = if (mode == "Spot") 0.14f else 0.52f
        val regionWidth = (active.width() * fraction).roundToInt().coerceAtLeast(1)
        val regionHeight = (active.height() * fraction).roundToInt().coerceAtLeast(1)
        val left = active.centerX() - regionWidth / 2
        val top = active.centerY() - regionHeight / 2
        val region = Rect(
            left.coerceAtLeast(active.left),
            top.coerceAtLeast(active.top),
            (left + regionWidth).coerceAtMost(active.right),
            (top + regionHeight).coerceAtMost(active.bottom),
        )
        return MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun applyRequestedMetering(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedMeteringRegion(cameraState, boundCamera)?.let {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
    }

    private fun applyRequestedMetering(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedMeteringRegion(cameraState, boundCamera)?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
    }

    private fun requestedVideoStabilizationMode(
        cameraState: CameraState,
        boundCamera: Camera,
    ): Int? {
        if (resolvedHdrLogMode(cameraState) == "LOG") return null
        val supported = Camera2CameraInfo.from(boundCamera.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
        )?.toSet().orEmpty()
        val requested = if (
            currentValue(cameraState, ".video_stabilization") == "Standard" ||
            currentValue(cameraState, ".super_steady") == "On"
        ) {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        return requested.takeIf { it in supported }
    }

    private fun applyRequestedVideoStabilization(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedVideoStabilizationMode(cameraState, boundCamera)?.let {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
        }
    }

    private fun applyRequestedVideoStabilization(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        requestedVideoStabilizationMode(cameraState, boundCamera)?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
        }
    }

    private fun submitNativeRepeatingRequest(cameraState: CameraState, boundCamera: Camera): Boolean {
        val session = cam2Session
        val surfaces = cam2Surfaces
        if (session == null || surfaces.isEmpty()) {
            Log.w(TAG, "Native focus: no session yet, applying via Camera2CameraControl")
            // Session not ready — use CameraX's Camera2CameraControl to set
            // AF_MODE_OFF + focus distance. This goes through CameraX's own
            // pipeline so it won't be overridden.
            try {
                val manualFocusRequest = manualFocusRequestFor(cameraState) ?: return true
                val builder = CaptureRequestOptions.Builder()
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyNativeZoom(builder, cameraState, boundCamera)
                manualFocusRequest.diopters?.let { focusDiopters ->
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
                }
                manualFocusRequest.vendorLensPosition?.let { lensPosition ->
                    vendorFocusLensPositionKey()?.let { key ->
                        builder.setCaptureRequestOption(key, lensPosition)
                    }
                    vendorFocusLensPosStallKey()?.let { key ->
                        builder.setCaptureRequestOption(key, lensPosition)
                    }
                }
                manualFocusRequest.vendorFocusValue?.let { focusValue ->
                    vendorFocusValueKey()?.let { key ->
                        builder.setCaptureRequestOption(key, focusValue)
                    }
                }
                manualFocusRequest.vendorLensPosition?.let {
                    vendorFocusMapEnabledKey()?.let { key ->
                        builder.setCaptureRequestOption(key, 1.toByte())
                    }
                }
                applyMaximumInformationLogControls(builder, cameraState)
                val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
                cam2Control.setCaptureRequestOptions(builder.build())
                Log.d(
                    TAG,
                    "Camera2CameraControl fallback: zoom=${nativeZoomRatio(cameraState, boundCamera)} diopters=${manualFocusRequest.diopters} vendorLensPos=${manualFocusRequest.vendorLensPosition} vendorFocus=${manualFocusRequest.vendorFocusValue}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera2CameraControl fallback failed", e)
                return false
            }
            return true
        }
        try {
            // The cached focus-step builder snapshots the look; a full rebuild means the look
            // may have changed, so the snapshot is dropped and the next focus step re-takes it.
            focusRampBuilder = null
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
            // Inherit the running exposure envelope so taking over the session is invisible.
            lastAeFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            lastAntibandingMode?.let { builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }

            // ── Focus ──
            // Autofocus is served from this same request rather than by handing the session
            // back to CameraX. Swapping authority is what made the preview flicker when the
            // dial crossed between AF and the rail: the two requests carry subtly different
            // 3A state, so the HAL re-converges exposure at the moment of the swap even when
            // the focal plane does not move at all.
            val manualFocusRequest = manualFocusRequestFor(cameraState)
            applyNativeZoom(builder, cameraState, boundCamera)
            if (manualFocusRequest == null) {
                val held = afHoldDiopters
                if (held != null) {
                    // Auto, holding: the plane is commanded, so nothing that rebuilds this
                    // request can disturb it. Only movement starts a new search.
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, held)
                } else {
                    // Auto, steady state: the HAL keeps the subject sharp. CONTINUOUS_VIDEO
                    // damps its own travel and takes over from wherever our pull left the lens.
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    )
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyNativeFocusRequest(builder, cameraState, manualFocusRequest, session.device.id)
            }

            populateSessionLook(builder, cameraState, boundCamera)

            session.setRepeatingRequest(builder.build(), sessionCaptureCallback, cameraRequestHandler)
            // Gated, and the capability lookup moved INSIDE the guard: composing this line cost
            // three more full catalog builds (selectedFocusCapability, selectedLensValue,
            // nativeZoomRatio) on a path that runs on every focus step. The arguments to a
            // Log.d are evaluated whether or not the line is ever written.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val capability = selectedFocusCapability(cameraState)
                Log.d(
                    TAG,
                    "Native focus applied: af=${if (manualFocusRequest == null) "auto" else "manual"} lens=${selectedLensValue(cameraState)} sessionCameraId=${session.device.id} zoom=${nativeZoomRatio(cameraState, boundCamera)} diopters=${manualFocusRequest?.diopters} minFocusDist=${capability?.minFocusDistance} transport=${capability?.transport} vendorLensPos=${manualFocusRequest?.vendorLensPosition} (norm=${manualFocusRequest?.normalizedFocus})",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native focus FAILED: ${e.message}", e)
            // A dead (evicted) session must not be held: clearing it routes the next apply
            // through the Camera2CameraControl fallback — which works on the reopened
            // CameraX camera — and the fresh session recaptures on its first frame.
            cam2Session = null
            cam2Surfaces = emptyList()
            return false
        }
        return true
    }

    private fun refreshBoundCameraCapabilities(boundCamera: Camera) {
        val cameraInfo = Camera2CameraInfo.from(boundCamera.cameraInfo)
        maximumInformationRequestModes = if (boundHdrLogMode == "LOG" ||
            resolvedHdrLogMode(latestState) == "LOG"
        ) {
            VideoDynamicRangePolicy.maximumInformationRequestModes(
                availableNoiseReductionModes = cameraInfo.getCameraCharacteristic(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                ),
                availableEdgeModes = cameraInfo.getCameraCharacteristic(
                    CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                ),
                availableVideoStabilizationModes = cameraInfo.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                ),
            ).also { modes ->
                Log.i(
                    TAG,
                    "Log information-preserving request modes: nr=${modes.noiseReductionMode} " +
                        "edge=${modes.edgeMode} eis=${modes.videoStabilizationMode}",
                )
            }
        } else {
            MaximumInformationRequestModes()
        }
        val focusCapability = selectedFocusCapability(latestState)
        if (focusCapability != null) {
            deviceMinFocusDistance = focusCapability.minFocusDistance
        } else {
            deviceMinFocusDistance = cameraInfo.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f
        }
        // Re-probe after every successful lens/session bind. Recorder qualities and high-speed
        // ranges are camera-specific, so a list captured from the previous lens is invalid.
        reportCameraCapabilities()
    }

    /**
     * Auto Shutter is a one-touch neutral-slate calibration: Samsung AWB keeps metering during
     * preview, the physical shutter locks its converged solution, and CameraX carries that lock
     * on both repeating and single capture requests. Auto Continuous bypasses this entirely.
     */
    private fun withShutterWhiteBalance(action: () -> Unit) {
        val wbValue = currentValue(latestState, ".white_balance")
        if (!CameraCatalog.isWhiteBalanceAutoShutter(wbValue)) {
            action()
            return
        }
        val boundCamera = camera
        if (boundCamera == null) {
            action()
            return
        }
        val lockAvailable = runCatching {
            Camera2CameraInfo.from(boundCamera.cameraInfo).getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
            ) == true
        }.getOrDefault(false)
        if (!lockAvailable) {
            // Honest fallback: capture with continuous Samsung AWB. Inventing a manual gain
            // from an unsupported lock would imply a calibration the hardware never confirmed.
            Log.w(TAG, "Auto Shutter requested, but CONTROL_AWB_LOCK is unavailable")
            action()
            return
        }
        // A second physical event while the first lock is being acknowledged must not create a
        // second photo/recording start. The original event completes or times out exactly once.
        if (shutterAwbLockInFlight) return

        val generation = ++shutterAwbLockGeneration
        shutterAwbLockInFlight = true
        shutterAwbLockActive = true
        // Direct-session users get the lock now; Camera2CameraControl below makes the same key
        // part of every CameraX repeating AND one-shot request, including ImageCapture.
        lastAppliedSessionSignature = null
        applySessionState(latestState, force = true)

        fun continueOnce(source: String) {
            if (generation != shutterAwbLockGeneration || !shutterAwbLockInFlight) return
            shutterAwbLockInFlight = false
            Log.d(TAG, "Auto Shutter AWB locked ($source)")
            action()
        }

        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()
            val future = Camera2CameraControl.from(boundCamera.cameraControl)
                .addCaptureRequestOptions(options)
            future.addListener(
                {
                    val source = runCatching { future.get(); "result" }
                        .getOrElse { error ->
                            Log.w(TAG, "AWB lock acknowledgement failed: ${error.message}")
                            "fallback"
                        }
                    continueOnce(source)
                },
                ContextCompat.getMainExecutor(context),
            )
        } catch (error: Exception) {
            Log.w(TAG, "AWB lock request failed: ${error.message}")
            continueOnce("fallback")
        }
        cameraRequestHandler.postDelayed(
            { continueOnce("timeout") },
            WB_SHUTTER_LOCK_TIMEOUT_MS,
        )
    }

    private fun releaseShutterWhiteBalance() {
        if (!shutterAwbLockActive && !shutterAwbLockInFlight) return
        shutterAwbLockGeneration++
        shutterAwbLockInFlight = false
        shutterAwbLockActive = false
        val boundCamera = camera ?: return
        try {
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()
            Camera2CameraControl.from(boundCamera.cameraControl).addCaptureRequestOptions(options)
        } catch (error: Exception) {
            Log.w(TAG, "AWB unlock request failed: ${error.message}")
        }
        lastAppliedSessionSignature = null
        applySessionState(latestState, force = true)
    }

    private fun capturePhoto() {
        val requestedMode = latestState.activeMode
        val delayMs = currentValue(latestState, ".timer")
            ?.removeSuffix("s")
            ?.toLongOrNull()
            ?.times(1_000L)
            ?: 0L
        if (delayMs > 0L) {
            val generation = photoTimerGeneration
            cameraRequestHandler.postDelayed({
                if (generation == photoTimerGeneration && latestState.activeMode == requestedMode) {
                    beginPhotoCapture()
                }
            }, delayMs)
        } else {
            beginPhotoCapture()
        }
    }

    private fun beginPhotoCapture() {
        underwaterCaptureFrozen = CameraCatalog.isWhiteBalanceAutoUnderwater(
            currentValue(latestState, ".white_balance"),
        )
        withShutterWhiteBalance(::capturePhotoNow)
    }

    private fun releaseUnderwaterCaptureFreeze() {
        if (!underwaterCaptureFrozen) return
        underwaterCaptureFrozen = false
        requestUnderwaterWhiteBalanceApply()
    }

    private fun capturePhotoNow() {
        val capture = imageCapture ?: run {
            releaseShutterWhiteBalance()
            releaseUnderwaterCaptureFreeze()
            return
        }
        val name = "DiveControl_${System.currentTimeMillis()}.jpg"
        val saveLocation = latestState.recordingSaveLocation
        val metadataSnapshot = captureMetadataSnapshot()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    saveLocation.relativePath,
                )
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    metadataSnapshot?.let { snapshot ->
                        recordingFinalizeExecutor.execute {
                            writeMetadataSidecar(name, saveLocation, snapshot)
                        }
                    }
                    releaseShutterWhiteBalance()
                    releaseUnderwaterCaptureFreeze()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    releaseShutterWhiteBalance()
                    releaseUnderwaterCaptureFreeze()
                }
            },
        )
    }

    /**
     * Snapshot only the fields the diver enabled. A JSON sidecar is used for both JPEG and MP4
     * because Android exposes no standard MP4 keys for dive depth, water temperature or heading;
     * the sidecar keeps the values machine-readable without rewriting or recompressing media.
     */
    private fun captureMetadataSnapshot(): CaptureMetadataSnapshot? {
        fun enabled(suffix: String) = currentValue(latestState, suffix) == "On"
        val depth = enabled(".metadata_depth")
        val temperature = enabled(".metadata_temperature")
        val heading = enabled(".metadata_heading")
        val pressure = enabled(".metadata_pressure")
        val exposure = enabled(".metadata_exposure")
        if (!depth && !temperature && !heading && !pressure && !exposure) return null

        val json = JSONObject()
            .put("schema", "com.mobiledivecontrol.capture-metadata.v1")
            .put("captured_at_epoch_ms", System.currentTimeMillis())
            .put("camera_mode", latestState.activeMode.name)
        if (depth) currentDepthMeters()?.let { json.put("dive_depth_m", it) }
        if (temperature) latestWaterTemperatureC?.let { json.put("water_temperature_c", it) }
        if (heading) latestHeadingDegrees?.let { json.put("heading_degrees_magnetic", it) }
        if (pressure) {
            val values = JSONObject()
            latestWaterPressureKpa?.let { values.put("water_kpa", it) }
            latestAtmosphericPressureKpa?.let { values.put("housing_kpa", it) }
            latestSurfaceAmbientKpa?.let { values.put("surface_reference_kpa", it) }
            if (values.length() > 0) json.put("pressure", values)
        }
        if (exposure) {
            val values = JSONObject()
            currentValue(latestState, ".iso")?.let { values.put("iso_setting", it) }
            currentValue(latestState, ".shutter_speed")?.let { values.put("shutter_setting", it) }
            currentValue(latestState, ".exposure_value", ".exposure_compensation")
                ?.let { values.put("ev_setting", it) }
            currentValue(latestState, ".white_balance")?.let { values.put("white_balance_setting", it) }
            latestState.meteredExposure.iso?.let { values.put("metered_iso", it) }
            latestState.meteredExposure.shutterNs?.let { values.put("metered_shutter_ns", it) }
            latestState.meteredExposure.wbKelvin?.let { values.put("metered_wb_kelvin", it) }
            json.put("exposure", values)
        }
        return CaptureMetadataSnapshot(json.toString(2))
    }

    private fun writeMetadataSidecar(
        mediaDisplayName: String,
        location: com.mobiledivecontrol.core.RecordingSaveLocation,
        snapshot: CaptureMetadataSnapshot,
    ) {
        val displayName = mediaDisplayName.substringBeforeLast('.') + ".metadata.json"
        // Android only permits generic application/json files under Download or Documents;
        // inserting them into the media file's DCIM path throws on Android 10+. Preserve an
        // explicit link to the media while publishing sidecars in a legal public collection.
        val sidecarJson = runCatching {
            JSONObject(snapshot.json)
                .put("media_display_name", mediaDisplayName)
                .put("media_relative_path", location.relativePath)
                .toString(2)
        }.getOrElse { snapshot.json }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, METADATA_SIDECAR_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = runCatching { resolver.insert(collection, values) }
            .onFailure { error ->
                Log.e(TAG, "Could not create metadata sidecar for $mediaDisplayName", error)
            }
            .getOrNull() ?: run {
            Log.e(TAG, "Could not create metadata sidecar for $mediaDisplayName")
            return
        }
        runCatching {
            resolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(sidecarJson)
            } ?: error("MediaStore returned no output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            Log.i(
                TAG,
                "Capture metadata written for $mediaDisplayName to " +
                    "$METADATA_SIDECAR_RELATIVE_PATH: $uri",
            )
        }.onFailure { error ->
            resolver.delete(uri, null, null)
            Log.e(TAG, "Could not write metadata sidecar for $mediaDisplayName", error)
        }
    }

    private fun applyFlash(cameraState: CameraState) {
        val flashValue = currentValue(cameraState, ".flash") ?: return
        val capture = imageCapture ?: return
        // Re-asserting the torch on every focus step disturbs 3A, so unchanged values cost nothing.
        if (flashValue == lastAppliedFlash) return
        lastAppliedFlash = flashValue
        capture.flashMode = when (flashValue) {
            "Auto" -> ImageCapture.FLASH_MODE_AUTO
            "On", "Torch" -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flashValue == "On" || flashValue == "Torch")
    }

    private fun applyZoom(cameraState: CameraState, boundCamera: Camera) {
        val lensValue = currentValue(cameraState, ".lens") ?: "1x"
        val requestedZoom = requestedZoomRatio(lensValue)
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 8.0
        val clamped = requestedZoom.coerceIn(minZoom, maxZoom)
        boundCamera.cameraControl.setZoomRatio(clamped.toFloat())
    }

    private fun applyCamera2Options(cameraState: CameraState, boundCamera: Camera) {
        // This method is only called in CameraX mode (auto focus).
        // When nativeFocusActive is true, submitNativeRepeatingRequest handles everything.
        val builder = CaptureRequestOptions.Builder()

        // Use AF_MODE_OFF for fixed-focus lenses (e.g. 0.6x ultrawide) that only
        // support mode 0. Setting CONTINUOUS_PICTURE on these can cause CameraX to
        // internally switch physical cameras.
        val manualFocusRequest = manualFocusRequestFor(cameraState)
        val vendorLensPosition = manualFocusRequest?.vendorLensPosition
        val isFixedFocus = selectedFocusCapability(cameraState)?.supportsManualFocus == false
        if (manualFocusRequest != null) {
            // Manual focus, Samsung's way: AF off + vendor lens position, in the same request.
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            // The public key is the ONLY control that moves the lens frame to frame on this
            // HAL. Tried and rejected: the vendor lens-position key in a repeating request is
            // echoed back in every result while the lens stays put, with or without
            // samsung.android.control.shootingMode declared alongside it.
            //
            if (vendorLensPosition == null) {
                manualFocusRequest.diopters?.let { d ->
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
                }
            }
            vendorLensPosition?.let { pos ->
                vendorFocusLensPositionKey()?.let { key -> trySetVendorOption(builder, key, pos) }
            }
            lastCommandedLensPos = vendorLensPosition
            Log.d(TAG, "CameraX manual focus: vendorLensPos=$vendorLensPosition norm=${manualFocusRequest.normalizedFocus}")
        } else if (isFixedFocus) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        } else {
            // CONTINUOUS_VIDEO, not CONTINUOUS_PICTURE: its contract is exactly the product's
            // requirement — AF engages from the lens's CURRENT plane and racks smoothly to
            // the subject, no sudden jumps. And no LENS_FOCUS_DISTANCE is ever written in AF
            // mode: the old unconditional 0f here was a live snap-to-infinity on HALs that
            // half-honour AF-mode semantics.
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // --- Effect mode ---
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // --- HDR / public 10-bit wide-dynamic-range video / Off ---
        if (!boundHdrExtension) {
            when (resolvedHdrLogMode(cameraState)) {
                "HDR" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                }
                "LOG" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
                else -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
            }
        }
        applyMaximumInformationLogControls(builder, cameraState)
        applyRequestedVideoStabilization(builder, cameraState, boundCamera)

        // --- White Balance ---
        val wbValue = currentValue(cameraState, ".white_balance")
        val autoUnderwater = CameraCatalog.isWhiteBalanceAutoUnderwater(wbValue)
        val filterProfile = if (autoUnderwater) null else underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (autoUnderwater && underwaterCommandSolution == null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
        } else if (autoUnderwater) {
            val colour = underwaterWhiteBalanceColour(cameraState)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, colour.first)
            colour.second?.let {
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
            }
        } else if (filterProfile == null) {
            val kelvin = wbValue?.removeSuffix("K")?.toIntOrNull()
            if (kelvin != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                val colour = manualWbColour(kelvin)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, colour.first)
                colour.second?.let {
                    builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                }
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_LOCK,
                    shutterAwbLockActive && CameraCatalog.isWhiteBalanceAutoShutter(wbValue),
                )
            }
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
            lastAutoColorTransform?.let {
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
            }
        }

        // --- ISO / Shutter: manual exposure needs the full pair (see native path) ---
        val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
        val shutterNs = currentValue(cameraState, ".shutter_speed")?.let { parseShutterNs(it) }
        val framePeriodNs = if (shutterNs != null) CameraCatalog.videoShutterCapNs(cameraState) else null
        if (isoValue != null || shutterNs != null) {
            try {
                val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = isoValue ?: lastAeSensitivity ?: 800
                val exposure = (shutterNs ?: lastAeExposureNs ?: 16_666_666L)
                    .let { ns -> framePeriodNs?.let { ns.coerceAtMost(it) } ?: ns }
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                if (isoRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
                }
                if (exposureRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceIn(exposureRange.lower, exposureRange.upper))
                }
                framePeriodNs?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, it) }
            } catch (_: Exception) { }
        } else {
            // --- Exposure Compensation (only meaningful while AE is ON) ---
            // Written through THIS bundle, never through CameraX's
            // setExposureCompensationIndex: that API validates against the public range and
            // rejects the +/-4.0 half of the native Pro window outright, while the interop
            // bundle is unvalidated and wins the request merge — the same channel that carries
            // the vendor focus key. One writer, one bundle, no 3A tug-of-war.
            val userEv = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                ?.replace("+", "")?.toDoubleOrNull()
            if (userEv != null) {
                val effectiveEv = SamsungLogProfile.effectiveAutoExposureEv(
                    userEv = userEv,
                    calibration = samsungLogAcquisitionCalibration(cameraState),
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evCompensationIndex(boundCamera, effectiveEv),
                )
            }
        }
        applyRequestedFrameRate(builder, cameraState, boundCamera)
        if (isoValue == null && shutterNs == null) {
            applyRequestedMetering(builder, cameraState, boundCamera)
        }

        try {
            val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
            cam2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 options FAILED", e)
        }
    }

    /**
     * EV in stops -> the compensation INDEX, clamped to the window the capability probe read —
     * the vendor aeCompensationRange ([-40,40] = +/-4.0 EV here, the native Pro dial's true
     * span) where it resolves, the public range otherwise. Deliberately NOT
     * ExposureState.exposureCompensationRange, which only ever knows the public window.
     */
    private fun evCompensationIndex(boundCamera: Camera, ev: Double): Int {
        val exposureState = boundCamera.cameraInfo.exposureState
        val step = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.1f
        val idx = (ev / step).roundToInt()
        val widened = evCompensationIndexRange
        return if (widened != null) {
            idx.coerceIn(widened.lower, widened.upper)
        } else {
            idx.coerceIn(
                exposureState.exposureCompensationRange.lower,
                exposureState.exposureCompensationRange.upper,
            )
        }
    }

    /**
     * One parser, [CameraCatalog.shutterOptionNanos], shared with the reducer's effect mapper and
     * with capability clipping. This used to be a private near-copy that rejected bare-seconds
     * labels, so a rung clipping had accepted could reach the strip and never reach the capture
     * request — auto-exposure silently left on under a manual-looking HUD.
     */
    private fun parseShutterNs(value: String): Long? = CameraCatalog.shutterOptionNanos(value)

    private fun openGallery() {
        val intents = buildList {
            add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY))
            add(
                Intent(
                    Intent.ACTION_VIEW,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ),
            )
            add(
                Intent(
                    Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ),
            )
            add(
                Intent(
                    Intent.ACTION_VIEW,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ),
            )
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }

    private fun desiredLensFacing(cameraState: CameraState): Int {
        return if (currentValue(cameraState, ".lens") == "front") {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    private fun desiredResolutionValue(cameraState: CameraState): String? {
        return currentValue(cameraState, ".resolution", ".megapixels")
    }

    private fun isHighSpeedSelection(cameraState: CameraState): Boolean =
        currentValue(cameraState, ".frame_rate")
            ?.removeSuffix("fps")
            ?.toIntOrNull()
            ?.let { it >= HIGH_SPEED_FPS_MIN } == true

    private fun highSpeedResolutionSize(value: String?): Size? = when (value) {
        "HD 720p" -> Size(1280, 720)
        "FHD 1920×824" -> Size(1920, 824)
        "FHD" -> Size(1920, 1080)
        "UHD 4K" -> Size(3840, 2160)
        else -> null
    }

    private fun videoQualitySelector(value: String?): QualitySelector {
        val requested = when (value) {
            "SD 480p" -> Quality.SD
            "HD 720p" -> Quality.HD
            "FHD" -> Quality.FHD
            "UHD 4K" -> Quality.UHD
            else -> Quality.HIGHEST
        }
        return QualitySelector.from(
            requested,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
    }

    private fun photoCropRatio(value: String?): Rational? = when (value) {
        "4:3" -> Rational(4, 3)
        "16:9" -> Rational(16, 9)
        "1:1" -> Rational(1, 1)
        else -> null
    }

    private fun exposureAssistMode(cameraState: CameraState): Int = when (
        currentValue(cameraState, ".exposure_display")
    ) {
        "Zebra ≥70 IRE" -> 1
        "Zebra ≥95 IRE" -> 2
        "False colour" -> 3
        else -> 0
    }

    private fun resolvedHdrLogMode(cameraState: CameraState): String {
        currentValue(cameraState, ".hdr_log")?.let { return it }
        if (currentValue(cameraState, ".log") == "On") {
            return "LOG"
        }
        val hdr = currentValue(cameraState, ".hdr")
        return if (hdr == "On" || hdr == "HDR") "HDR" else "Off"
    }

    private fun resolvedSessionHdrLogMode(cameraState: CameraState): String {
        val fps = currentValue(cameraState, ".frame_rate")
            ?.removeSuffix("fps")
            ?.toIntOrNull()
        return if (fps != null && fps >= HIGH_SPEED_FPS_MIN) "Off" else resolvedHdrLogMode(cameraState)
    }

    private fun samsungLogAcquisitionCalibration(cameraState: CameraState) =
        SamsungLogProfile.acquisitionCalibration(
            deviceModel = Build.MODEL.orEmpty(),
            lensValue = currentValue(cameraState, ".lens"),
        ).takeIf { resolvedHdrLogMode(cameraState) == "LOG" }

    private fun currentValue(cameraState: CameraState, vararg suffixes: String): String? {
        val settings = CameraCatalog.settingsFor(cameraState.activeMode, cameraState.deviceVariant)
        val spec = settings.firstOrNull { setting -> suffixes.any { suffix -> setting.id.endsWith(suffix) } }
            ?: return null
        return CameraCatalog.currentValue(cameraState, spec)
    }

    private fun assignBackLensLabels(profile: BackCameraProfile): Map<String, PhysicalLensProfile> {
        val sortedLenses = profile.physicalLenses.sortedBy { lens -> lens.focalLengthMm }
        if (sortedLenses.isEmpty()) {
            return emptyMap()
        }
        val mainLens = sortedLenses.minByOrNull { lens ->
            abs(lens.focalLengthMm - profile.logicalFocalLengthMm) + if (lens.supportsManualFocus) 0f else 100f
        } ?: sortedLenses.first()
        val assignments = linkedMapOf<String, PhysicalLensProfile>()
        assignments["1x"] = mainLens
        // 2x is a digital crop on the main lens sensor — same physical camera
        assignments["2x"] = mainLens

        sortedLenses
            .filter { lens -> lens.focalLengthMm < mainLens.focalLengthMm - 0.1f }
            .minByOrNull { lens -> lens.focalLengthMm }
            ?.let { ultraWideLens -> assignments["0.6x"] = ultraWideLens }

        val teleLenses = sortedLenses.filter { lens -> lens.focalLengthMm > mainLens.focalLengthMm + 0.25f }
        when (teleLenses.size) {
            0 -> Unit
            1 -> assignments["3x"] = teleLenses.first()
            else -> {
                assignments["3x"] = teleLenses.first()
                assignments["5x"] = teleLenses.last()
            }
        }
        return assignments
    }

    private fun buildLensFocusCapabilities(
        assignments: Map<String, PhysicalLensProfile>,
    ): Map<String, LensFocusCapabilityProfile> {
        return assignments.mapValues { (lensValue, profile) ->
            LensFocusCapabilityProfile(
                lensValue = lensValue,
                transport = resolveManualFocusTransport(lensValue, profile),
                minFocusDistance = profile.minFocusDistance,
                vendorFocusLensRange = profile.vendorFocusLensRange,
            )
        }
    }

    private fun resolveManualFocusTransport(
        lensValue: String,
        profile: PhysicalLensProfile,
    ): ManualFocusTransport {
        val hasVendorRange = profile.vendorFocusLensRange?.let { it.last > it.first } == true
        val hasAutofocusMotor = profile.afModes.any { mode ->
            mode == CameraMetadata.CONTROL_AF_MODE_AUTO ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                mode == CameraMetadata.CONTROL_AF_MODE_MACRO
        }
        return when {
            // Main lens and its 2x crop: prefer Hybrid when a vendor lens-position range
            // exists. The public diopter key is HAL-clamped to LENS_INFO_MINIMUM_FOCUS_DISTANCE
            // (10 diopters = 10 cm on the S24 main lens) while the vendor key drives the full
            // VCM travel — the native camera's closer minimum focus lives in that gap. The
            // diopter leg stays in every request as fallback for HALs ignoring vendor tags.
            lensValue == "1x" && profile.minFocusDistance > 0f && hasVendorRange -> ManualFocusTransport.Hybrid
            lensValue == "1x" && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            lensValue == "2x" && isMainLensProfile(profile) && profile.minFocusDistance > 0f && hasVendorRange -> ManualFocusTransport.Hybrid
            lensValue == "2x" && isMainLensProfile(profile) && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            // Telephoto lenses: Samsung HAL requires BOTH diopter + vendor lens position
            // to reliably control the full focus range. PublicDiopter alone is ignored/clamped
            // by the HAL, causing blurry images at close focus distances.
            lensValue in setOf("3x", "5x") && profile.minFocusDistance > 0f && hasVendorRange -> ManualFocusTransport.Hybrid
            lensValue in setOf("3x", "5x") && profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            lensValue in setOf("3x", "5x") && hasVendorRange -> ManualFocusTransport.SamsungLensPosition
            // Other lenses: use diopter when available
            profile.minFocusDistance > 0f -> ManualFocusTransport.PublicDiopter
            hasVendorRange && hasAutofocusMotor -> ManualFocusTransport.SamsungLensPosition
            else -> ManualFocusTransport.Fixed
        }
    }

    private fun isMainLensProfile(profile: PhysicalLensProfile): Boolean {
        val mainLens = backLensAssignments["1x"] ?: return false
        return (profile.physicalCameraId ?: profile.logicalCameraId) ==
            (mainLens.physicalCameraId ?: mainLens.logicalCameraId)
    }

    private fun selectedFocusCapability(cameraState: CameraState): LensFocusCapabilityProfile? {
        val lensValue = selectedLensValue(cameraState)
        if (lensValue == "front") {
            return LensFocusCapabilityProfile(
                lensValue = lensValue,
                transport = if (frontCameraMinFocusDistance > 0f) {
                    ManualFocusTransport.PublicDiopter
                } else {
                    ManualFocusTransport.Fixed
                },
                minFocusDistance = frontCameraMinFocusDistance,
            )
        }
        if (lensValue == "Auto") {
            // Auto mode uses the logical camera — focus through logical camera's capabilities.
            if (backCameraProfile == null) return null
            val mainProfile = backLensAssignments["1x"]
            val logicalMinFocus = mainProfile?.minFocusDistance ?: deviceMinFocusDistance
            // The logical camera routes 1x-zoom requests to the main lens, so Auto inherits
            // the main lens's vendor focus range — without it, Auto (the default lens) stays
            // clamped to the public 10 cm floor that the dedicated 1x escapes.
            val vendorRange = mainProfile?.vendorFocusLensRange?.takeIf { it.last > it.first }
            return LensFocusCapabilityProfile(
                lensValue = "Auto",
                transport = when {
                    logicalMinFocus > 0f && vendorRange != null -> ManualFocusTransport.Hybrid
                    logicalMinFocus > 0f -> ManualFocusTransport.PublicDiopter
                    else -> ManualFocusTransport.Fixed
                },
                minFocusDistance = logicalMinFocus,
                vendorFocusLensRange = vendorRange,
                vendorFocusCalibration = mainProfile?.vendorFocusCalibration.orEmpty(),
            )
        }
        return lensFocusCapabilities[lensValue]
            ?: selectedLensProfile(cameraState)?.let { profile ->
                LensFocusCapabilityProfile(
                    lensValue = lensValue,
                    transport = resolveManualFocusTransport(lensValue, profile),
                    minFocusDistance = profile.minFocusDistance,
                    vendorFocusLensRange = profile.vendorFocusLensRange,
                    vendorFocusCalibration = profile.vendorFocusCalibration,
                )
            }
    }

    private fun manualFocusRequestFor(cameraState: CameraState): ManualFocusRequest? {
        val normalizedFocus = manualFocusNormalized(cameraState) ?: return null
        val capability = selectedFocusCapability(cameraState) ?: return null
        if (!capability.supportsManualFocus) {
            return null
        }
        // NATIVE-PARITY FOCUS LAW — established by decompiling the Samsung Camera app
        // (Pro / Pro Video manual focus) and verified key by key:
        //
        //   * It NEVER writes CaptureRequest.LENS_FOCUS_DISTANCE (the public key is assigned
        //     to a dead local and dropped). It writes the vendor key
        //     samsung.android.lens.focusLensPos, paired with CONTROL_AF_MODE = OFF.
        //   * Its endpoints come from samsung.android.lens.info.focusLensInfo (1..4095 here),
        //     NOT from LENS_INFO_MINIMUM_FOCUS_DISTANCE — that is exactly how it focuses
        //     closer (~5.8 cm) than the public key's 10 cm clamp.
        //   * Its dial is a NORMALIZED INDEX mapped LINEARLY onto that actuator range
        //     (MakerParameter.getAfLensPosition): step 0 -> max (macro), last -> min (far).
        //
        // The device's own focusCalibration table proves the actuator is linear in DIOPTERS
        // (DAC ~= 666 + 198.5 x diopters), so a linear dial gives uniform optical change per
        // step — which is why native feels even end to end. A metres dial (1/v) does not: it
        // moves ~90x more optically per step at 0.10 than at 0.90.
        val vendorRange = capability.vendorFocusLensRange?.takeIf { it.last > it.first }
        // The vendor lens-position key is a SESSION parameter on this HAL: written into a
        // repeating request it is faithfully echoed back in every CaptureResult while the lens
        // never moves (proven by watching samsung.android.lens.info.currentInfo[3], which does
        // track under AF). So it rides the bind-time Extender only, and the LIVE control — the
        // one the wheel drives frame to frame — is the public key, which does move the lens.
        // DISABLED, deliberately: writing it at bind time while the public key drives the live
        // request gives the HAL two authorities for one actuator, and the lens then lands
        // somewhere between them (measured: dial 0.00 reporting 5.07 D, dial 0.75 reporting
        // 10 D — scrambled). One authority only. The mapping helper is kept for the day the
        // vendor path can be driven per-frame (see samsung.android.control.shootingMode).
        // The vendor lens-position key is unreachable from here, and that is a platform limit,
        // not a tuning choice: in a repeating request this HAL echoes it back untouched while
        // the lens ignores it (with or without samsung.android.control.shootingMode), and
        // CameraX's interop options are folded into the repeating request rather than the
        // session parameters, so there is no way to install it at configure time either.
        // Consequence: our near limit is LENS_INFO_MINIMUM_FOCUS_DISTANCE (10 cm here) while
        // the stock app's privileged path reaches ~5.8 cm. Everything from 10 cm to infinity
        // is ours, linear in diopters, and moves live.
        // Every route to the vendor lens-position key has been tried on this HAL and all are
        // echoed-but-ignored: repeating request (ours and CameraX's), bind-time interop options,
        // the focusLensPosStall variant, samsung.android.control.shootingMode = 35 alongside, and
        // samsung.android.control.cameraClient = 0 (the class Samsung's own camera declares).
        // The gate is not a request tag. Until a route is found, the public key owns focus and
        // our near limit is LENS_INFO_MINIMUM_FOCUS_DISTANCE.
        @Suppress("UNUSED_EXPRESSION")
        vendorRange
        val vendorLensPosition: Int? = null
        // Linear in DIOPTERS, which is the same law native's dial follows (its actuator range
        // is linear in diopters), so the feel matches: uniform optical change per step.
        // The dial spans the lens's REAL travel, not its declared travel.
        //
        // LENS_INFO_MINIMUM_FOCUS_DISTANCE (10 dpt = 10 cm here) turns out to be a declaration,
        // not an enforced ceiling: this HAL passes larger values straight through to the
        // actuator. Measured — asking 17.3 dpt parks the lens at DAC 4088 ≈ 5.8 cm, which is
        // the stock camera's own bottom stop. We had never seen it because we coerced our own
        // request to the declared value before sending it; the clamp was ours, not the HAL's.
        val diopters = reachableDiopters(capability)?.let { maxDiopters ->
            ((1.0 - normalizedFocus.coerceIn(0.0, 1.0)) * maxDiopters).toFloat().coerceAtLeast(0f)
        }
        // Samsung writes no Qualcomm focus-value key; neither do we.
        val vendorFocusValue: Float? = null
        if (diopters == null && vendorLensPosition == null) {
            return null
        }
        return ManualFocusRequest(
            normalizedFocus = normalizedFocus,
            transport = capability.transport,
            diopters = diopters,
            vendorLensPosition = vendorLensPosition,
            vendorFocusValue = vendorFocusValue,
        )
    }

    private fun vendorFocusLensInfoKey(): CameraCharacteristics.Key<IntArray>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CameraCharacteristics.Key("samsung.android.lens.info.focusLensInfo", IntArray::class.java)
    }

    private fun vendorFocusCalibrationKey(): CameraCharacteristics.Key<IntArray>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CameraCharacteristics.Key("samsung.android.lens.info.focusCalibration", IntArray::class.java)
    }

    private fun vendorFocusLensRange(characteristics: CameraCharacteristics): IntRange? {
        val info = vendorFocusLensInfoKey()?.let(characteristics::get) ?: return null
        if (info.size < 2) {
            return null
        }
        val start = info[0]
        val end = info[1]
        return if (end > start) start..end else null
    }

    private fun vendorFocusCalibration(characteristics: CameraCharacteristics): List<Int> {
        return vendorFocusCalibrationKey()?.let(characteristics::get)?.toList().orEmpty()
    }

    private fun vendorFocusValueKey(): CaptureRequest.Key<Float>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key("org.quic.camera.focusvalue.FocusValue", Float::class.javaObjectType)
    }

    /**
     * Per-request lens drive. Field-verified on the S24: plain focusLensPos only takes
     * effect as a SESSION parameter (bind-time Camera2Interop) and is echoed but ignored
     * in mid-session repeating requests — the lens froze on every wheel change until the
     * Stall variant was added. focusLensPosStall moves the lens per request.
     */
    private fun vendorFocusLensPosStallKey(): CaptureRequest.Key<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            CaptureRequest.Key("samsung.android.lens.focusLensPosStall", Int::class.javaObjectType)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `samsung.android.control.cameraClient` — the HAL classifies its callers. Samsung's own
     * camera declares CAMERA_CLIENT_SAMSUNG_DEFAULT(0); a CameraX app is CAMERA_CLIENT_CAMERA_X(3)
     * (DeviceConfiguration.Parameters.CameraClient in the stock app; the public Samsung camera SDK
     * declares 0 for SDK clients and 4 for VIP clients).
     */
    private fun vendorCameraClientKey(): CaptureRequest.Key<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            CaptureRequest.Key("samsung.android.control.cameraClient", Int::class.javaObjectType)
        } catch (_: Exception) {
            null
        }
    }

    /** `samsung.android.control.shootingMode` — 5 = Pro, 35 = Pro Video in Samsung's own app. */
    private fun vendorShootingModeKey(): CaptureRequest.Key<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            CaptureRequest.Key("samsung.android.control.shootingMode", Int::class.javaObjectType)
        } catch (_: Exception) {
            null
        }
    }

    /** `samsung.android.lens.info.currentInfo` — element 3 is the live lens position. */
    private fun vendorLensCurrentInfoKey(): CaptureResult.Key<IntArray>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            CaptureResult.Key("samsung.android.lens.info.currentInfo", IntArray::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun vendorFocusLensPositionKey(): CaptureRequest.Key<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key("samsung.android.lens.focusLensPos", Int::class.javaObjectType)
    }

    private fun vendorFocusMapEnabledKey(): CaptureRequest.Key<Byte>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return CaptureRequest.Key(
            "org.codeaurora.qcamera3.sessionParameters.EnableAFFocusMap",
            Byte::class.javaObjectType,
        )
    }

    private fun runningPhysicalCameraId(result: TotalCaptureResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val key = CaptureResult.Key("samsung.android.control.runningPhysicalId", ByteArray::class.java)
        return result.get(key)?.decodeAsciiId()
    }

    private fun samsungFocusLensPosition(result: TotalCaptureResult): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val key = CaptureResult.Key("samsung.android.lens.focusLensPos", Int::class.javaObjectType)
        return result.get(key)
    }

    private fun selectedCameraIdForBinding(cameraState: CameraState, lensFacing: Int): String? {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return frontCameraId
        }
        val lensValue = selectedLensValue(cameraState)
        // "Auto" mode uses the logical camera — let Samsung's HAL manage lens switching
        if (lensValue == "Auto") {
            return backCameraProfile?.logicalCameraId
        }
        val physicalCameraId = selectedLensProfile(cameraState)?.physicalCameraId
        return if (physicalCameraId != null && physicalCameraId !in failedDirectPhysicalCameraIds) {
            physicalCameraId
        } else {
            backCameraProfile?.logicalCameraId
        }
    }

    private fun selectedLensValue(cameraState: CameraState): String {
        return currentValue(cameraState, ".lens") ?: "Auto"
    }

    private fun selectedLensProfile(cameraState: CameraState): PhysicalLensProfile? {
        val lensValue = selectedLensValue(cameraState)
        if (lensValue == "front") {
            return null
        }
        // "Auto" mode: use no specific physical lens — let the logical camera manage it
        if (lensValue == "Auto") {
            return null
        }
        return when (lensValue) {
            "0.6x" -> backLensAssignments["0.6x"] ?: backLensAssignments["1x"]
            "1x" -> backLensAssignments["1x"] ?: backLensAssignments["2x"] ?: backLensAssignments["3x"] ?: backLensAssignments["0.6x"]
            "2x" -> backLensAssignments["2x"] ?: backLensAssignments["1x"] ?: backLensAssignments["3x"]
            "3x" -> backLensAssignments["3x"] ?: backLensAssignments["2x"] ?: backLensAssignments["1x"]
            "5x" -> backLensAssignments["5x"] ?: backLensAssignments["3x"] ?: backLensAssignments["2x"] ?: backLensAssignments["1x"]
            else -> backLensAssignments["1x"] ?: backLensAssignments.values.firstOrNull()
        }
    }

    private fun effectiveManualFocusNormalized(cameraState: CameraState): Double? {
        return manualFocusRequestFor(cameraState)?.normalizedFocus
    }

    private fun requestedZoomRatio(lensValue: String): Double {
        if (lensValue == "front") {
            return 1.0
        }
        // When binding to the logical camera with setPhysicalCameraId, Samsung's HAL
        // still needs CONTROL_ZOOM_RATIO to match the physical sensor so that
        // LENS_FOCUS_DISTANCE and AF commands are routed to the correct sensor.
        // Without this, focus commands go to the 1x wide sensor even when viewing 3x.
        return when (lensValue) {
            "0.6x" -> 1.0  // ultrawide: fixed focus, CameraX manages routing via physicalCameraId
            "1x" -> 1.0
            "2x" -> 2.0    // digital crop on main sensor
            "3x" -> 3.0    // telephoto: must match so HAL routes focus to telephoto sensor
            "5x" -> 5.0    // periscope: must match so HAL routes focus to periscope sensor
            "Auto" -> 1.0  // Auto mode: HAL manages lens switching, start at 1x
            else -> 1.0
        }
    }

    private fun nativeZoomRatio(cameraState: CameraState, boundCamera: Camera): Float {
        val requestedZoom = requestedZoomRatio(selectedLensValue(cameraState))
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio?.toDouble() ?: 1.0
        val maxZoom = zoomState?.maxZoomRatio?.toDouble() ?: 8.0
        return requestedZoom.coerceIn(minZoom, maxZoom).toFloat()
    }

    private fun applyNativeZoom(
        builder: CaptureRequestOptions.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        val zoomRatio = nativeZoomRatio(cameraState, boundCamera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }
        cameraActiveArrayRect(boundCamera)?.let { activeArray ->
            builder.setCaptureRequestOption(
                CaptureRequest.SCALER_CROP_REGION,
                cropRegionForZoom(activeArray, zoomRatio),
            )
        }
    }

    private fun applyNativeZoom(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        boundCamera: Camera,
    ) {
        val zoomRatio = nativeZoomRatio(cameraState, boundCamera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }
        cameraActiveArrayRect(boundCamera)?.let { activeArray ->
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                cropRegionForZoom(activeArray, zoomRatio),
            )
        }
    }

    private fun manualFocusNormalized(cameraState: CameraState): Double? {
        return currentValue(cameraState, ".manual_focus")
            ?.takeUnless { it == "AF" }
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
    }

    /**
     * Map normalized focus (0=close/macro, 1=far/infinity) to diopter value.
     * Applies the user-selected focus curve (Linear, SquareRoot, or Logarithmic)
     * for perceptually smooth control.
     */
    private fun focusDistanceFor(
        normalizedFocus: Double,
        minFocusDistance: Float = deviceMinFocusDistance,
        curveMode: FocusCurveMode = currentFocusCurveMode(),
    ): Float {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        // 0 = close (macro, max diopters), 1 = far (infinity, 0 diopters)
        val curved = focusCurveFraction(1.0 - clamped, curveMode)
        return (minFocusDistance * curved.toFloat())
            .coerceIn(0f, minFocusDistance)
    }

    /**
     * The dial value is the subject distance in meters; 1.00 means infinity. Values
     * closer than the lens's public floor clamp to it (the vendor macro tail takes over
     * below on lenses that have one). This is the Samsung native MF slider's law.
     */
    private fun metersLawDiopters(normalizedFocus: Double, minFocusDistance: Float): Float {
        val v = normalizedFocus.coerceIn(0.0, 1.0)
        if (v >= 1.0) return 0f
        return (1.0 / v.coerceAtLeast(1.0 / minFocusDistance))
            .toFloat()
            .coerceIn(0f, minFocusDistance)
    }

    /**
     * Ramp level (1 slowest … 100 fastest) to a full-rack duration.
     *
     * Geometric rather than linear, so the slider's feel is even: each step changes the pace by
     * the same PROPORTION. A linear map would spend most of its travel among durations the eye
     * cannot tell apart and cram the interesting slow end into a few clicks.
     */
    private fun rampDurationForLevel(level: Int): Long {
        val t = (level.coerceIn(1, 100) - 1) / 99.0
        val ratio = RACK_FASTEST_MS.toDouble() / RACK_SLOWEST_MS.toDouble()
        return (RACK_SLOWEST_MS * Math.pow(ratio, t)).toLong().coerceIn(RACK_FASTEST_MS, RACK_SLOWEST_MS)
    }

    /**
     * Diopters at the far end of this lens's mechanical travel.
     *
     * Samsung publishes a per-unit calibration table (distance-cm, actuator-code pairs) and the
     * actuator's own range. Fitting the pairs gives code = a + b·diopters, and evaluating it at
     * the range's near end gives the closest plane the glass can actually reach — the same one
     * the stock app's bottom stop lands on. Devices without the table fall back to the declared
     * LENS_INFO_MINIMUM_FOCUS_DISTANCE, which is the honest limit when we cannot do better.
     */
    /** Least-squares fit of the lens's calibration table: actuatorCode = intercept + slope·diopters. */
    private fun focusCalibrationFit(capability: LensFocusCapabilityProfile): Pair<Double, Double>? {
        val points = capability.vendorFocusCalibration
            .chunked(2)
            .filter { it.size == 2 && it[0] > 0 && it[1] > 0 }
            .map { pair -> 100.0 / pair[0] to pair[1].toDouble() }
        if (points.size < 2) return null
        val meanD = points.sumOf { it.first } / points.size
        val meanC = points.sumOf { it.second } / points.size
        val varD = points.sumOf { (it.first - meanD) * (it.first - meanD) }
        if (varD <= 0.0) return null
        val slope = points.sumOf { (it.first - meanD) * (it.second - meanC) } / varD
        if (slope <= 0.0) return null
        return slope to (meanC - slope * meanD)
    }

    /**
     * Where the lens actually is, as a dial value.
     *
     * Read from the actuator position rather than the reported focus distance: while autofocus
     * runs, the HAL reports distance saturated at its DECLARED minimum (10 dpt) even though the
     * glass is well past it. Seeding a pull from that number made leaving AF lurch outward to
     * 10 cm and then come back — the judder. The actuator position never lies.
     */
    private fun dialValueForCurrentLens(capability: LensFocusCapabilityProfile): Double? {
        val pos = lastObservedVendorLensPos ?: return null
        val (slope, intercept) = focusCalibrationFit(capability) ?: return null
        val maxD = reachableDiopters(capability)?.takeIf { it > 0f } ?: return null
        val diopters = (pos - intercept) / slope
        return (1.0 - diopters / maxD).coerceIn(0.0, 1.0)
    }

    private var reachableCacheKey: LensFocusCapabilityProfile? = null
    private var reachableCacheValue: Float? = null

    /**
     * [reachableDiopters] memoised for the per-frame path.
     *
     * It runs a least-squares fit over the lens calibration table, which is why the glide hoists
     * it out of its step loop. The wheel path needs the same value every frame, and the lens
     * capability only changes when the bound lens does.
     */
    private fun reachableDioptersCached(capability: LensFocusCapabilityProfile): Float? {
        if (reachableCacheKey != capability) {
            reachableCacheKey = capability
            reachableCacheValue = reachableDiopters(capability)
        }
        return reachableCacheValue
    }

    private fun reachableDiopters(capability: LensFocusCapabilityProfile): Float? {
        val declared = capability.minFocusDistance.takeIf { it > 0f }
        val range = capability.vendorFocusLensRange?.takeIf { it.last > it.first }
        val fit = focusCalibrationFit(capability)
        if (range == null || fit == null) return declared
        val (slope, intercept) = fit
        val maxDiopters = ((range.last - intercept) / slope).toFloat()
        // Never go BELOW the declared limit, and refuse a nonsensical fit.
        return if (maxDiopters.isFinite() && declared != null && maxDiopters > declared) {
            maxDiopters
        } else {
            declared
        }
    }

    /**
     * Dial value -> VCM position, byte-identical to Samsung's own mapping:
     * `((steps - step - 1) / (steps - 1)) * (max - min) + min`, with the exact endpoint
     * short-circuits so 0.00 and 1.00 command precisely the mechanical stops.
     */
    private fun nativeParityLensPosition(normalizedFocus: Double, range: IntRange): Int {
        val v = normalizedFocus.coerceIn(0.0, 1.0)
        if (v <= 0.0) return range.last
        if (v >= 1.0) return range.first
        return (range.first + (1.0 - v) * (range.last - range.first))
            .roundToInt()
            .coerceIn(range.first, range.last)
    }

    /** Shared response curve: fraction of full travel toward NEAR, for t in [0,1]. */
    private fun focusCurveFraction(t: Double, curveMode: FocusCurveMode = currentFocusCurveMode()): Double =
        when (curveMode) {
            FocusCurveMode.Linear -> t
            FocusCurveMode.SquareRoot -> sqrt(t)
            // ln(1 + t*e) / ln(1+e) maps [0,1] -> [0,1] with more range near macro
            FocusCurveMode.Logarithmic -> ln(1.0 + t * Math.E) / ln(1.0 + Math.E)
        }

    /** Inverse of [focusCurveFraction] — travel fraction back to t. */
    private fun inverseFocusCurveFraction(c: Double, curveMode: FocusCurveMode = currentFocusCurveMode()): Double =
        when (curveMode) {
            FocusCurveMode.Linear -> c
            FocusCurveMode.SquareRoot -> c * c
            FocusCurveMode.Logarithmic -> (kotlin.math.exp(c * ln(1.0 + Math.E)) - 1.0) / Math.E
        }.coerceIn(0.0, 1.0)

    /**
     * Read the current focus curve mode from the latest camera state.
     */
    private fun currentFocusCurveMode(): FocusCurveMode {
        val focusCurveSettingId = when (latestState.activeMode) {
            com.mobiledivecontrol.core.CameraModeId.Photo -> "photo.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.Pro -> "pro.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.ExpertRaw -> "expert.focus_curve"
            com.mobiledivecontrol.core.CameraModeId.ProVideo -> "pro_video.focus_curve"
            else -> return FocusCurveMode.SquareRoot
        }
        val curveValue = latestState.settingValues[focusCurveSettingId] ?: "SquareRoot"
        return when (curveValue) {
            "Linear" -> FocusCurveMode.Linear
            "SquareRoot" -> FocusCurveMode.SquareRoot
            "Logarithmic" -> FocusCurveMode.Logarithmic
            else -> FocusCurveMode.SquareRoot
        }
    }

    /**
     * Native-parity dial law, matching the Samsung Pro camera MF slider exactly: the
     * value IS the subject distance in meters (their rail labels 0.10 / 0.30 / 0.90 are
     * meters), and distance converts to a VCM DAC position through the device's own
     * per-unit calibration table (samsung.android.lens.info.focusCalibration, pairs of
     * distance-cm to DAC). Field-derived on the S24 main lens: 10cm=2657, 30cm=1306,
     * 40cm=1159, 94cm=896; extrapolating the same line puts the mechanical stop 4095 at
     * ~5.8cm, matching the observed macro plane. Interpolation is linear in DIOPTERS
     * (VCM displacement is diopter-proportional), so the law self-calibrates on any
     * Samsung unit that publishes the table. 1.00 = infinity, like their top stop.
     * Without a usable table, falls back to a linear sweep across the DAC range.
     */
    private fun vendorLensPositionFor(
        normalizedFocus: Double,
        lensRange: IntRange,
        calibration: List<Int> = emptyList(),
    ): Int {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        val points = calibration
            .chunked(2)
            .filter { it.size == 2 && it[0] > 0 && it[1] in lensRange }
            .map { pair -> 100.0 / pair[0] to pair[1].toDouble() }
            .sortedBy { it.first }
        if (points.size < 2) {
            val span = lensRange.last - lensRange.first
            return (lensRange.first + span * (1.0 - clamped))
                .roundToInt()
                .coerceIn(lensRange.first, lensRange.last)
        }
        val dacPerDiopter = (points.last().second - points.first().second) /
            (points.last().first - points.first().first)
        // Nearest reachable plane = the mechanical stop, projected along the same line.
        val maxDiopters = points.last().first + (lensRange.last - points.last().second) / dacPerDiopter
        val targetDiopters = if (clamped >= 1.0) {
            0.0
        } else {
            (1.0 / clamped.coerceAtLeast(1.0 / maxDiopters)).coerceAtMost(maxDiopters)
        }
        val dac = when {
            targetDiopters <= points.first().first ->
                points.first().second + (targetDiopters - points.first().first) * dacPerDiopter
            targetDiopters >= points.last().first ->
                points.last().second + (targetDiopters - points.last().first) * dacPerDiopter
            else -> {
                val hi = points.first { it.first >= targetDiopters }
                val lo = points.last { it.first <= targetDiopters }
                if (hi.first == lo.first) {
                    lo.second
                } else {
                    lo.second + (targetDiopters - lo.first) * (hi.second - lo.second) / (hi.first - lo.first)
                }
            }
        }
        return dac.roundToInt().coerceIn(lensRange.first, lensRange.last)
    }

    private fun vendorFocusValueFor(normalizedFocus: Double): Float {
        val clamped = normalizedFocus.coerceIn(0.0, 1.0)
        return (1.0 - clamped).toFloat()
    }

    /** Vendor tags this HAL rejected — remembered so one bad key can't spam or abort requests. */
    private val unsupportedVendorKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Same guard as [trySetVendorKey], for CameraX's option-bundle builder. */
    private fun <T : Any> trySetVendorOption(
        builder: CaptureRequestOptions.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
    ) {
        if (key.name in unsupportedVendorKeys) return
        try {
            builder.setCaptureRequestOption(key, value)
        } catch (e: IllegalArgumentException) {
            if (unsupportedVendorKeys.add(key.name)) {
                Log.w(TAG, "Vendor key unsupported on this HAL, disabled: " + key.name)
            }
        }
    }

    private fun <T> trySetVendorKey(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T): Boolean {
        if (key.name in unsupportedVendorKeys) return false
        return try {
            builder.set(key, value)
            true
        } catch (e: IllegalArgumentException) {
            // Exynos and Snapdragon S24s expose different vendor tag sets; an unknown tag
            // throws from set() and would otherwise abort the entire repeating request,
            // killing manual focus outright instead of just skipping the extra key.
            if (unsupportedVendorKeys.add(key.name)) {
                Log.w(TAG, "Vendor key unsupported on this HAL, disabled: " + key.name)
            }
            false
        }
    }

    private fun applyNativeFocusRequest(
        builder: CaptureRequest.Builder,
        cameraState: CameraState,
        request: ManualFocusRequest,
        sessionDeviceId: String?,
    ) {
        val physicalCameraId = physicalFocusCameraId(cameraState)
        var usePhysicalKeys = canUsePhysicalRequestKeys(sessionDeviceId, physicalCameraId)

        request.diopters?.let { focusDiopters ->
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
            if (usePhysicalKeys) {
                usePhysicalKeys = trySetPhysicalCameraKey(
                    builder = builder,
                    key = CaptureRequest.LENS_FOCUS_DISTANCE,
                    value = focusDiopters,
                    sessionDeviceId = sessionDeviceId,
                    physicalCameraId = physicalCameraId!!,
                )
            }
        }
        request.vendorLensPosition?.let { lensPosition ->
            vendorFocusLensPosStallKey()?.let { stallKey ->
                trySetVendorKey(builder, stallKey, lensPosition)
            }
            vendorFocusLensPositionKey()?.let { key ->
                if (!trySetVendorKey(builder, key, lensPosition)) return@let
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = lensPosition,
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
        request.vendorFocusValue?.let { focusValue ->
            vendorFocusValueKey()?.let { key ->
                if (!trySetVendorKey(builder, key, focusValue)) return@let
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = focusValue,
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
        request.vendorLensPosition?.let {
            vendorFocusMapEnabledKey()?.let { key ->
                if (!trySetVendorKey(builder, key, 1.toByte())) return@let
                if (usePhysicalKeys) {
                    usePhysicalKeys = trySetPhysicalCameraKey(
                        builder = builder,
                        key = key,
                        value = 1.toByte(),
                        sessionDeviceId = sessionDeviceId,
                        physicalCameraId = physicalCameraId!!,
                    )
                }
            }
        }
    }

    private fun canUsePhysicalRequestKeys(
        sessionDeviceId: String?,
        physicalCameraId: String?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || sessionDeviceId == null || physicalCameraId == null) {
            return false
        }
        if (sessionDeviceId == physicalCameraId) {
            return false
        }
        return physicalRequestTargetKey(sessionDeviceId, physicalCameraId) !in unsupportedPhysicalRequestTargets
    }

    private fun physicalRequestTargetKey(sessionDeviceId: String, physicalCameraId: String): String {
        return "$sessionDeviceId->$physicalCameraId"
    }

    private fun <T> trySetPhysicalCameraKey(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        sessionDeviceId: String?,
        physicalCameraId: String,
    ): Boolean {
        val deviceId = sessionDeviceId ?: return false
        val targetKey = physicalRequestTargetKey(deviceId, physicalCameraId)
        if (targetKey in unsupportedPhysicalRequestTargets) {
            return false
        }
        return try {
            builder.setPhysicalCameraKey(key, value, physicalCameraId)
            true
        } catch (error: IllegalArgumentException) {
            unsupportedPhysicalRequestTargets += targetKey
            Log.w(
                TAG,
                "Physical request keys disabled for sessionCameraId=$deviceId physicalCameraId=$physicalCameraId: ${error.message}",
            )
            false
        }
    }

    private fun physicalFocusCameraId(cameraState: CameraState): String? {
        if (selectedLensValue(cameraState) == "front") {
            return null
        }
        val profile = selectedLensProfile(cameraState) ?: return null
        if (isMainLensProfile(profile)) {
            return null
        }
        return profile.physicalCameraId
    }

    private fun cameraActiveArrayRect(boundCamera: Camera): Rect? {
        return Camera2CameraInfo.from(boundCamera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    }

    private fun cropRegionForZoom(activeArray: Rect, zoomRatio: Float): Rect {
        if (zoomRatio <= 1f) {
            return Rect(activeArray)
        }
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        val cropWidth = (activeArray.width() / zoomRatio).roundToInt().coerceAtLeast(1)
        val cropHeight = (activeArray.height() / zoomRatio).roundToInt().coerceAtLeast(1)
        val left = (centerX - cropWidth / 2).coerceAtLeast(activeArray.left)
        val top = (centerY - cropHeight / 2).coerceAtLeast(activeArray.top)
        val right = (left + cropWidth).coerceAtMost(activeArray.right)
        val bottom = (top + cropHeight).coerceAtMost(activeArray.bottom)
        return Rect(left, top, right, bottom)
    }

    private fun isFocusAssistEnabled(cameraState: CameraState): Boolean {
        return currentValue(cameraState, ".focus_peaking") == "On"
    }

    // Focus peaking is now handled entirely by FocusPeakingSurfaceProcessor
    // (GPU shader in the CameraX preview pipeline). No CPU bitmap overlay needed.

    private fun ByteArray.decodeAsciiId(): String {
        val endIndex = indexOf(0).let { if (it >= 0) it else size }
        return copyOfRange(0, endIndex).toString(Charsets.US_ASCII)
    }

    private fun resolutionFor(value: String?): Size? = when (value) {
        "12MP" -> Size(4000, 3000)
        "24MP" -> Size(6000, 4000)
        "50MP" -> Size(8160, 6120)
        "200MP" -> {
            // Use detected max resolution if it's >= 200MP equivalent, otherwise
            // request the largest known 200MP size and let CameraX fall back
            val maxRes = deviceMaxSupportedResolution
            if (maxRes != null && maxRes.width.toLong() * maxRes.height >= 100_000_000L) {
                maxRes // Use the actual max resolution reported by the device
            } else {
                Size(16320, 12240) // Standard 200MP 4:3
            }
        }
        else -> null
    }

    private fun underwaterFilterProfile(value: String?, depthMeters: Double?): RggbChannelVector? {
        val targetDepth = when {
            value == null || value == "Off" -> return null
            value == "Auto" -> depthMeters ?: 0.0
            value.endsWith("m") -> value.removeSuffix("m").toDoubleOrNull() ?: return null
            else -> return null
        }.coerceIn(0.0, 50.0)

        // Beer-Lambert attenuation model for clear seawater.
        val redAttenuation = 0.15
        val greenAttenuation = 0.07
        val blueAttenuation = 0.03

        val redGain = kotlin.math.exp((redAttenuation - blueAttenuation) * targetDepth).toFloat()
            .coerceIn(1.0f, 8.0f)
        val greenGain = kotlin.math.exp((greenAttenuation - blueAttenuation) * targetDepth).toFloat()
            .coerceIn(1.0f, 4.0f)
        val blueGain = 1.0f
        return RggbChannelVector(redGain, greenGain, greenGain, blueGain)
    }

    /**
     * Depth from the water-pressure sensor, referenced to the captured surface baseline.
     *
     * Deliberately *not* the live barometric reading. That sensor is inside the shell, so once the
     * vacuum check pulls 20 kPa out of it the reading drops to ~81 kPa and the subtraction reports
     * about two metres of depth while the housing is still on the boat. That number feeds the
     * underwater colour correction, which would then start compensating for a dive that has not
     * happened. `surfaceAmbientKpa` is captured while the suction cover is open, when the shell is
     * vented and the sensor really is reading atmosphere; with no capture yet, the standard
     * atmosphere is wrong by altitude and weather but never by the whole vacuum.
     */
    private fun currentDepthMeters(): Double? {
        return depthMetersFromPressure(latestWaterPressureKpa, latestSurfaceAmbientKpa)
    }

    /** The mode's manual kelvin, or null when white balance sits on Auto (or has no dial). */
    private fun manualWbKelvin(cameraState: CameraState): Int? {
        val wb = currentValue(cameraState, ".white_balance") ?: return null
        if (CameraCatalog.isWhiteBalanceAuto(wb)) return null
        return wb.removeSuffix("K").toIntOrNull()
    }

    /** White balance is genuinely auto: no manual kelvin, and no underwater filter owning the gains. */
    private fun wbIsAuto(cameraState: CameraState): Boolean {
        val filters = currentValue(cameraState, ".filters")
        if (filters != null && filters != "Off") return false
        val wb = currentValue(cameraState, ".white_balance")
        if (CameraCatalog.isWhiteBalanceAutoUnderwater(wb)) return underwaterCommandSolution == null
        return wb == null || CameraCatalog.isWhiteBalanceOemAuto(wb)
    }

    /**
     * AU owns one complete physical white point. The calibrated sensor pipeline remains the only
     * renderer; AU merely supplies its two coordinates (CCT and signed Duv) instead of inventing
     * independent saturation, exposure or tone controls.
     */
    private fun underwaterWhiteBalanceColour(
        cameraState: CameraState,
    ): Pair<RggbChannelVector, android.hardware.camera2.params.ColorSpaceTransform?> {
        val solution = underwaterCommandSolution ?: underwaterWbSolution ?: run {
            val seed = cameraState.meteredExposure.wbKelvin ?: lastAutoWbAnchor?.kelvin ?: 6_500
            UnderwaterWhiteBalanceSolution(
                seed.coerceIn(CameraCatalog.WB_MIN_KELVIN, CameraCatalog.WB_MAX_KELVIN),
                0.0,
                0.0,
                0.0,
            )
        }
        return computedWbColour(solution.kelvin, solution.tintDuv)
            ?: manualWbColour(solution.kelvin)
    }

    /**
     * The complete manual white-balance rendering for a kelvin: gains AND colour transform.
     *
     * Preferred route computes BOTH halves from the sensor's factory calibration —
     * [wbPipeline] — with a continuity correction folded into the transform so that at the
     * anchor kelvin the output is EXACTLY the frame the HAL's own AWB was rendering (that is
     * what makes Auto-to-manual seamless). Away from the anchor, the hue follows the
     * illuminant-matched matrix: warm renderings pull green DOWN toward magenta, which channel
     * gains alone can never do — the reason both the raw-gains route and Samsung's
     * gains-only vendor route read yellow at 10000K where the native app reads magenta.
     *
     * Fallback (no calibration matrices): the anchor-ratio gains with the AWB's latched
     * transform, which still colour-matches the conversion point.
     */
    private fun manualWbColour(
        kelvin: Int,
    ): Pair<RggbChannelVector, android.hardware.camera2.params.ColorSpaceTransform?> {
        // ONE CONTINUOUS CURVE from 2300K to 10000K — the field's requirement, verbatim: "one
        // smooth spectrum". The smooth analytic backbone (sensor calibration + measured LUT)
        // covers the whole dial; the harvested AWB curve — "corrects just like auto, but
        // manually" — BLENDS over it by a weight that is 1 inside the harvested span and fades
        // to 0 over [WB_HARVEST_FADE_MIRED] beyond its ends. No priority switch, no seam:
        // inside coverage manual IS the auto correction, far outside it is the calibrated
        // curve, and in between every rung moves a little of both.
        val backbone = computedWbColour(kelvin)
            ?: (anchoredWbGains(kelvin) to lastAutoColorTransform)
        val harvested = harvestedWbAt(kelvin) ?: return backbone
        val weight = (1.0 - harvested.distanceMired / WB_HARVEST_FADE_MIRED).coerceIn(0.0, 1.0).toFloat()
        if (weight <= 0f) return backbone
        val baseGains = backbone.first
        val gains = RggbChannelVector(
            baseGains.red + weight * (harvested.rGain - baseGains.red),
            baseGains.greenEven + weight * (harvested.greenGain - baseGains.greenEven),
            baseGains.greenOdd + weight * (harvested.greenGain - baseGains.greenOdd),
            baseGains.blue + weight * (harvested.bGain - baseGains.blue),
        )
        val harvestedT = harvested.transform
        val baseT = backbone.second
        val transform = when {
            harvestedT == null -> baseT
            baseT == null || weight >= 1f -> {
                val elements = IntArray(18)
                var i = 0
                for (v in harvestedT) {
                    if (!v.isFinite()) return backbone
                    elements[i++] = Math.round(v * 10_000f); elements[i++] = 10_000
                }
                android.hardware.camera2.params.ColorSpaceTransform(elements)
            }
            else -> {
                val elements = IntArray(18)
                var i = 0
                for (idx in 0..8) {
                    val baseV = baseT.getElement(idx % 3, idx / 3).toFloat()
                    val v = baseV + weight * (harvestedT[idx] - baseV)
                    if (!v.isFinite()) return backbone
                    elements[i++] = Math.round(v * 10_000f); elements[i++] = 10_000
                }
                android.hardware.camera2.params.ColorSpaceTransform(elements)
            }
        }
        return gains to transform
    }

    private class WbPipeline(val gains: RggbChannelVector, val transform: Array<DoubleArray>)

    /**
     * The DNG-style rendering pipeline at one kelvin, in Camera2's decomposition:
     * raw -> diag(gains) -> transform -> linear sRGB.
     *
     *   gains: reciprocal of the sensor's response to the illuminant, green-normalised.
     *   transform: XYZtoSRGB x Bradford(WP_K -> D65) x inv(M_K) x inv(diag(gains))
     *
     * so that for ANY scene colour, out = XYZtoSRGB x CAT x XYZ — the correct rendering under
     * the declared illuminant, hue axis included.
     */
    private fun wbPipeline(
        cal: SensorColorCalibration,
        kelvin: Int,
        tintDuv: Double = 0.0,
    ): WbPipeline? {
        val k = kelvin.coerceIn(2300, 10000).toDouble()
        val (x, y) = whitePointXy(k, tintDuv)
        if (y <= 1e-6) return null
        val whiteXyz = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
        val m = interpolatedSensorMatrix(cal, k)
        val r = dot(m[0], whiteXyz)
        val g = dot(m[1], whiteXyz)
        val b = dot(m[2], whiteXyz)
        if (r <= 1e-6 || g <= 1e-6 || b <= 1e-6) return null
        val (baseX, baseY) = whitePointXy(k, 0.0)
        val baseWhiteXyz = doubleArrayOf(baseX / baseY, 1.0, (1.0 - baseX - baseY) / baseY)
        val baseR = dot(m[0], baseWhiteXyz)
        val baseG = dot(m[1], baseWhiteXyz)
        val baseB = dot(m[2], baseWhiteXyz)
        if (baseR <= 1e-6 || baseG <= 1e-6 || baseB <= 1e-6) return null
        // The MEASURED native curve wins where it exists; the matrix-derived ratio is the model
        // on the zero-Duv locus. Off-locus, preserve that measured baseline and multiply only
        // the sensor-model ratio produced by the physical tint displacement. At Duv=0 this is
        // exactly the old/native-calibrated curve; tint therefore cannot disturb manual Kelvin.
        val measured = measuredWbGains(k)
        val baseRRatio = measured?.first ?: (baseG / baseR)
        val baseBRatio = measured?.second ?: (baseG / baseB)
        val rTrue = (baseRRatio * ((g / r) / (baseG / baseR))).coerceIn(0.1, 10.0)
        val bTrue = (baseBRatio * ((g / b) / (baseG / baseB))).coerceIn(0.1, 10.0)
        // MIN-NORMALISED, scalar folded into the transform. Real HALs accept only gains >= 1,
        // and matrix hardware clamps CCM entries near +/-4 — so neither half may carry the whole
        // warm-end correction alone. Normalising the gain triple to min = 1 keeps every channel
        // gain hardware-legal (2300K becomes ~(1.0, 2.5, 7.8) instead of an illegal 0.4 red),
        // and multiplying the transform by the SAME min scales its rows DOWN into range —
        // net rendering identical, nothing clipped. The old green-normalised form crushed red
        // to black at 2300K twice over: first through the sub-1 gain, then through the 1/gain
        // row the matrix had to carry.
        val minGain = minOf(rTrue, 1.0, bTrue)
        val mInv = invert3x3(m) ?: return null
        val cat = bradfordCat(whiteXyz, D65_WHITE)
        val trueGainsInv = diag3(1.0 / rTrue, 1.0, 1.0 / bTrue)
        var transform = multiply3x3(
            multiply3x3(XYZ_TO_SRGB, cat),
            multiply3x3(mInv, trueGainsInv),
        )
        transform = Array(3) { row -> DoubleArray(3) { col -> transform[row][col] * minGain } }
        // The measured rendered-output correction: a per-kelvin diagonal fitted from the
        // native-vs-ours patch measurements, folded into the transform. This is what makes the
        // dial numerically identical to the native app's rendering at the measured points.
        measuredWbTransformFix(k)?.let { (tr, tb) ->
            transform = multiply3x3(diag3(tr, 1.0, tb), transform)
        }
        // REBALANCE: matrix hardware clamps CCM entries near +/-4, and at the warm extreme the
        // adaptation still overflows that even min-normalised — which is what kept crushing red
        // at 2300K. Any overflow is traded into the gains (headroom to 16): scale the matrix
        // down into range, scale every gain up by the same factor — the rendering is identical
        // and every register stays legal.
        var gainScale = 1.0
        val maxAbs = transform.maxOf { row -> row.maxOf { kotlin.math.abs(it) } }
        if (maxAbs > 3.9) {
            gainScale = maxAbs / 3.9
            transform = Array(3) { row -> DoubleArray(3) { col -> transform[row][col] / gainScale } }
        }
        val gains = RggbChannelVector(
            (rTrue / minGain * gainScale).coerceIn(1.0, 16.0).toFloat(),
            (1.0 / minGain * gainScale).coerceIn(1.0, 16.0).toFloat(),
            (1.0 / minGain * gainScale).coerceIn(1.0, 16.0).toFloat(),
            (bTrue / minGain * gainScale).coerceIn(1.0, 16.0).toFloat(),
        )
        // Calibration-fit visibility: whether a channel hit the 16x gain ceiling or the CCM
        // rebalance is active decides whether a LUT tr/tb correction can actually be DELIVERED
        // at this kelvin — saturation here means the fit must move to the rGain/bGain rows.
        return WbPipeline(gains, transform)
    }

    /** [wbPipeline] with the anchor-continuity correction, quantised for the capture request. */
    private fun computedWbColour(
        kelvin: Int,
        tintDuv: Double = 0.0,
    ): Pair<RggbChannelVector, android.hardware.camera2.params.ColorSpaceTransform>? {
        val cal = sensorColorCalibration ?: return null
        val base = wbPipeline(cal, kelvin, tintDuv) ?: return null
        var transform = base.transform
        // A measured calibration SUPERSEDES the AWB-continuity correction, by the field's own
        // requirement: our MANUAL curve must equal the native app's manual rendering at every
        // kelvin, and our AUTO is the same HAL AWB native uses — so the Auto-to-manual seam is
        // then identical in both apps by construction. A seam-hiding correction here would make
        // our manual deviate from native's manual near the conversion point, which is exactly
        // what the requirement forbids.
        val anchor = if (wbCalibration != null) null else lastAutoWbAnchor
        val anchorTransform = anchor?.transform
        if (anchor != null && anchorTransform != null) {
            wbPipeline(cal, anchor.kelvin)?.let { reference ->
                val halTotal = multiply3x3(toMatrix(anchorTransform), rggbDiag(anchor.gains))
                invert3x3(multiply3x3(reference.transform, rggbDiag(reference.gains)))?.let { refInv ->
                    // C x model(K_anchor) == HAL's rendering at the anchor, and C rides along
                    // the whole dial: continuity exactly where the diver converted, calibrated
                    // trajectory everywhere else.
                    transform = multiply3x3(multiply3x3(halTotal, refInv), transform)
                }
            }
        }
        val quantised = toColorSpaceTransform(transform) ?: return null
        return base.gains to quantised
    }

    private fun dot(row: DoubleArray, v: DoubleArray): Double =
        row[0] * v[0] + row[1] * v[1] + row[2] * v[2]

    private fun diag3(a: Double, b: Double, c: Double): Array<DoubleArray> = arrayOf(
        doubleArrayOf(a, 0.0, 0.0),
        doubleArrayOf(0.0, b, 0.0),
        doubleArrayOf(0.0, 0.0, c),
    )

    private fun rggbDiag(gains: RggbChannelVector): Array<DoubleArray> = diag3(
        gains.red.toDouble(),
        ((gains.greenEven + gains.greenOdd) / 2.0).toDouble(),
        gains.blue.toDouble(),
    )

    private fun invert3x3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-9) return null
        val inv = 1.0 / det
        return arrayOf(
            doubleArrayOf((e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv),
            doubleArrayOf((f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv),
            doubleArrayOf((d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv),
        )
    }

    private fun bradfordCat(fromWhiteXyz: DoubleArray, toWhiteXyz: DoubleArray): Array<DoubleArray> {
        val from = DoubleArray(3) { dot(BRADFORD[it], fromWhiteXyz) }
        val to = DoubleArray(3) { dot(BRADFORD[it], toWhiteXyz) }
        val scale = diag3(to[0] / from[0], to[1] / from[1], to[2] / from[2])
        return multiply3x3(BRADFORD_INV, multiply3x3(scale, BRADFORD))
    }

    /** Row-major (numerator, denominator) pairs; null when the matrix has run away. */
    private fun toColorSpaceTransform(
        m: Array<DoubleArray>,
    ): android.hardware.camera2.params.ColorSpaceTransform? {
        val elements = IntArray(18)
        var index = 0
        for (row in 0..2) {
            for (col in 0..2) {
                val value = m[row][col]
                if (!value.isFinite() || kotlin.math.abs(value) > 30.0) return null
                elements[index++] = Math.round(value * 10_000).toInt()
                elements[index++] = 10_000
            }
        }
        return android.hardware.camera2.params.ColorSpaceTransform(elements)
    }

    /**
     * Fallback gains when the device withholds its calibration matrices: the black-body (or
     * calibrated, when available) curve re-based through the HAL's own AWB sample so the
     * conversion point still colour-matches Auto:
     *
     *   gains(K) = halGains(K_anchor) * model(K) / model(K_anchor)
     */
    private fun anchoredWbGains(kelvin: Int): RggbChannelVector {
        val model = colorGainsForKelvin(kelvin)
        val anchor = lastAutoWbAnchor ?: return model
        val modelAtAnchor = colorGainsForKelvin(anchor.kelvin)
        fun calibrated(anchorGain: Float, target: Float, atAnchor: Float): Float =
            (anchorGain * (target / atAnchor)).coerceIn(0.1f, 8.0f)
        return RggbChannelVector(
            calibrated(anchor.gains.red, model.red, modelAtAnchor.red),
            calibrated(anchor.gains.greenEven, model.greenEven, modelAtAnchor.greenEven),
            calibrated(anchor.gains.greenOdd, model.greenOdd, modelAtAnchor.greenOdd),
            calibrated(anchor.gains.blue, model.blue, modelAtAnchor.blue),
        )
    }

    /** The two reference-illuminant matrices, calibration transform folded in where present. */
    private fun readSensorColorCalibration(
        chars: android.hardware.camera2.CameraCharacteristics,
    ): SensorColorCalibration? {
        val ct1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
        val ct2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) ?: return null
        val cct1 = illuminantCct(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: return null)
            ?: return null
        val cct2 = illuminantCct(
            (chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: return null).toInt(),
        ) ?: return null
        if (cct1 == cct2) return null
        val cal1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)
        val cal2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)
        val m1 = cal1?.let { multiply3x3(toMatrix(it), toMatrix(ct1)) } ?: toMatrix(ct1)
        val m2 = cal2?.let { multiply3x3(toMatrix(it), toMatrix(ct2)) } ?: toMatrix(ct2)
        return SensorColorCalibration(cct1, m1, cct2, m2)
    }

    /** CCTs of the EXIF illuminant codes Camera2 uses; null for codes we will not guess at. */
    private fun illuminantCct(code: Int): Double? = when (code) {
        1 -> 5503.0 // DAYLIGHT
        2 -> 4230.0 // FLUORESCENT
        3 -> 2856.0 // TUNGSTEN
        17 -> 2856.0 // STANDARD_A
        18 -> 4874.0 // STANDARD_B
        19 -> 6774.0 // STANDARD_C
        20 -> 5503.0 // D55
        21 -> 6504.0 // D65
        22 -> 7504.0 // D75
        23 -> 5003.0 // D50
        24 -> 3200.0 // ISO_STUDIO_TUNGSTEN
        else -> null
    }

    /** ColorSpaceTransform stores getElement(column, row); we work row-major. */
    private fun toMatrix(t: android.hardware.camera2.params.ColorSpaceTransform): Array<DoubleArray> =
        Array(3) { row -> DoubleArray(3) { col -> t.getElement(col, row).toDouble() } }

    private fun multiply3x3(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { r ->
            DoubleArray(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] }
        }

    /**
     * The white point's chromaticity at a colour temperature: the Planckian locus (Kim cubic
     * approximation) below 4000K, the CIE DAYLIGHT locus at and above it. The daylight locus
     * sits BELOW the Planckian one in y — toward magenta — and photographic kelvin dials mean
     * D-series illuminants at the cold end, which is exactly the magenta component the pure
     * black-body model could never produce at 10000K.
     */
    private fun whitePointXy(kelvin: Double, tintDuv: Double = 0.0): Pair<Double, Double> {
        val xy = WhiteBalanceChromaticity.kelvinAndTintToXy(kelvin, tintDuv)
        return xy.x to xy.y
    }

    /** DNG interpolation of the two reference matrices: weights linear in inverse CCT, clamped. */
    private fun interpolatedSensorMatrix(cal: SensorColorCalibration, kelvin: Double): Array<DoubleArray> {
        val w = (((1.0 / kelvin) - (1.0 / cal.cct2)) / ((1.0 / cal.cct1) - (1.0 / cal.cct2)))
            .coerceIn(0.0, 1.0)
        return Array(3) { r ->
            DoubleArray(3) { c -> w * cal.m1[r][c] + (1.0 - w) * cal.m2[r][c] }
        }
    }

    /** The gains half of [wbPipeline] alone, for the black-body fallback's calibrated cousin. */
    private fun sensorCalibratedGains(kelvin: Int): RggbChannelVector? =
        sensorColorCalibration?.let { cal -> wbPipeline(cal, kelvin)?.gains }

    /**
     * Manual white balance done to the Camera2 contract: AWB OFF is undefined without explicit
     * colour-correction gains. First choice is [sensorCalibratedGains] — the sensor's own
     * factory colour locus; the black-body fit below is only the fallback for hardware that
     * withholds its matrices. Never applied raw any more either way: [anchoredWbGains] re-bases
     * whichever curve is in use through the HAL's live AWB sample.
     */
    private fun colorGainsForKelvin(kelvin: Int): RggbChannelVector {
        sensorCalibratedGains(kelvin)?.let { return it }
        val t = kelvin.coerceIn(2300, 10000) / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (t <= 66.0) {
            r = 255.0
            g = (99.4708025861 * ln(t) - 161.1195681661).coerceIn(1.0, 255.0)
            b = if (t <= 19.0) {
                1.0
            } else {
                (138.5177312231 * ln(t - 10.0) - 305.0447927307).coerceIn(1.0, 255.0)
            }
        } else {
            r = (329.698727446 * Math.pow(t - 60.0, -0.1332047592)).coerceIn(1.0, 255.0)
            g = (288.1221695283 * Math.pow(t - 60.0, -0.0755148492)).coerceIn(1.0, 255.0)
            b = 255.0
        }
        val rGain = (g / r).toFloat().coerceIn(0.3f, 4.0f)
        val bGain = (g / b).toFloat().coerceIn(0.3f, 4.0f)
        return RggbChannelVector(rGain, 1f, 1f, bGain)
    }
}
