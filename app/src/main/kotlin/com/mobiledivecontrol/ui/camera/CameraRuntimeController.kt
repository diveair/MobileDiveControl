package com.mobiledivecontrol.ui.camera

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
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
import com.mobiledivecontrol.core.FocusCurveMode
import com.mobiledivecontrol.ui.components.STANDARD_ATMOSPHERE_KPA
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.io.File
import java.util.concurrent.Executors

class CameraRuntimeController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveCameraCtrl"
        private const val RESUME_STREAM_CHECK_DELAY_MS = 2_500L

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
        private const val MACRO_STOP_REBIND_DEBOUNCE_MS = 450L

        /** A beat on the landed plane before the HAL takes the lens, so the arrival reads. */
        private const val AF_HANDOVER_DELAY_MS = 220L

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
        val waterPressureKpa: Double?,
        val atmosphericPressureKpa: Double?,
    )

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
    private var boundHdrExtension: Boolean = false
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    private var latestState: CameraState = CameraState()
    /**
     * Transient capture state, deliberately outside persisted [CameraState]. Auto Shutter meters
     * exactly like continuous AWB until the physical shutter is pressed, then this bit is applied
     * to repeating and single capture requests until the photo completes or recording finalizes.
     */
    @Volatile private var shutterAwbLockActive = false
    private var shutterAwbLockInFlight = false
    private var shutterAwbLockGeneration = 0
    private var latestWaterPressureKpa: Double? = null
    private var latestAtmosphericPressureKpa: Double? = null

    /** Barometric reading captured with the suction cover open. The only valid depth reference. */
    private var latestSurfaceAmbientKpa: Double? = null
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
    // Callback to report detected lenses back to the ViewModel/state
    private var onDetectedLenses: ((List<String>) -> Unit)? = null
    // GPU-accelerated focus peaking via OpenGL shader in the CameraX preview pipeline.
    // Replaces the old CPU bitmap overlay approach which caused jitter and drift.
    private var focusPeakingProcessor: FocusPeakingSurfaceProcessor? = null
    private val cameraRequestHandler = Handler(Looper.getMainLooper())

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
            // Capture session + surfaces on first callback or session change
            if (cam2Session !== session) {
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
            if (wbIsAuto(latestState)) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastAutoColorTransform = it }
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastAutoColorGains = it }
            }
            // The exposure envelope CameraX negotiated. Our own request must inherit it, or the
            // frame rate and flicker bounds change the instant we take over the session and the
            // preview visibly jumps in brightness.
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { lastAeFpsRange = it }
            result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)?.let { lastAntibandingMode = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeSensitivity = it }

            // Periodic diagnostic logging and the live Auto readouts, on one 2 Hz throttle.
            val now = SystemClock.elapsedRealtime()
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
            onMeteredExposure?.invoke(
                com.mobiledivecontrol.core.MeteredExposure(
                    iso = lastAeSensitivity,
                    shutterNs = lastAeExposureNs,
                    wbKelvin = meteredWbKelvin,
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
                    lastAutoWbAnchor = WbAnchor(meteredWbKelvin, gains, lastAutoColorTransform)
                    harvestAwbCurvePoint(meteredWbKelvin, gains, lastAutoColorTransform, now)
                }
            }

            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val af = result.get(CaptureResult.CONTROL_AF_MODE)
            val lp = samsungFocusLensPosition(result)
            val physicalId = runningPhysicalCameraId(result)
            // evIdx is the HAL's echo of CONTROL_AE_EXPOSURE_COMPENSATION — the on-metal proof
            // line for the +/-4.0 EV window: past index +/-20 the echo AND the iso x expNs
            // product must keep moving, or the wider dial saturates and must be pulled back.
            val evEcho = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            val resultGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                String.format(java.util.Locale.US, "%.2f/%.2f", it.red, it.blue)
            }
            Log.d(
                TAG,
                "CaptureResult fd=$fd af=$af lpEcho=$lp lpActual=$lastObservedVendorLensPos " +
                    "wanted=$lastCommandedLensPos physical=$physicalId native=$nativeFocusActive " +
                    "iso=$lastAeSensitivity expNs=$lastAeExposureNs evIdx=$evEcho " +
                    "wbK=$meteredWbKelvin evMeter=$meteredEvTenths " +
                    "wbAnchor=${lastAutoWbAnchor?.kelvin} gains=$resultGains",
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
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        installResumeWatchdog(lifecycleOwner)
        this.onDetectedLenses = onDetectedLenses
        this.onCapabilities = onCapabilities
        this.onMeteredExposure = onMeteredExposure
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
        resumeObserver?.let { observer -> lifecycleOwner?.lifecycle?.removeObserver(observer) }
        resumeObserver = null
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        imageAnalysis = null
        previewView = null
        lifecycleOwner = null
        boundLensFacing = null
        boundLensValue = null
        boundResolution = null
        boundHdrExtension = false
        boundFocusMode = false
        cam2Session = null
        cam2Surfaces = emptyList()
        nativeFocusActive = false
        shutterAwbLockGeneration++
        shutterAwbLockInFlight = false
        shutterAwbLockActive = false
        focusAssistEnabled = false
        lastAppliedSessionSignature = null
        focusPeakingProcessor?.release()
        focusPeakingProcessor = null
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
    ) {
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
        focusAssistEnabled = isFocusAssistEnabled(cameraState)
        // Toggle GPU shader peaking — no camera rebinding needed.
        // Peaking works in both AF and manual focus modes.
        focusPeakingProcessor?.peakingEnabled = focusAssistEnabled
        if (cameraProvider == null || previewView == null || lifecycleOwner == null) {
            Log.d(TAG, "applyState: early return (provider/preview/lifecycle null)")
            return
        }

        val desiredLensFacing = desiredLensFacing(cameraState)
        val desiredResolution = desiredResolutionValue(cameraState)
        val desiredLens = selectedLensValue(cameraState)
        // Rebind when lens facing, physical lens, or resolution changes.
        val needsRebind = desiredLensFacing != boundLensFacing ||
                desiredResolution != boundResolution ||
                desiredLens != boundLensValue
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

    /**
     * True while the housing is being moved: focus tracks the subject continuously instead of
     * holding. Continuous-video AF is the mode built for this — it damps its own travel and
     * never sweeps the whole range looking for a peak, which is what made a one-shot search
     * land with a thud every time the camera was repointed.
     */
    @Volatile private var afTracking = false

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

    /**
     * Whether a contrast pull is actually reading [latestSharpness] right now.
     *
     * The metric is cheap per frame but it ran on EVERY frame forever, and its value is read
     * only while a pull is searching. Cheap-times-always is the shape that cost the most heat in
     * this file already, so the analyzer skips the measurement entirely when nothing wants it.
     */
    @Volatile private var afPullActive = false

    /**
     * Contrast in the middle of the frame, from the luma plane only.
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
                    val a = buffer.get(row + x).toInt() and 0xFF
                    val b = buffer.get(row + x + SHARPNESS_STRIDE).toInt() and 0xFF
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

    /** Autofocus, at the diver's pace. */
    private fun startAutofocusTracking(reason: String) {
        if (manualFocusRequestFor(latestState) != null) return
        Log.d(TAG, "Autofocus: searching ($reason)")
        runRampedAutofocus(reason)
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
                    finishRampedAutofocus(gen, bestValue, maxD, reason)
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
                        finishRampedAutofocus(gen, bestValue, maxD, reason)
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
     * Land on the sharpest plane the pull found, then give the lens to the HAL.
     *
     * Our job was the TRAVEL — getting there at the diver's chosen rate instead of snapping.
     * Keeping the lens afterwards would mean reimplementing autofocus badly: the HAL tracks a
     * subject far better than a contrast walk can, and it picks up from wherever the lens is,
     * so the handover itself moves nothing.
     */
    private fun finishRampedAutofocus(gen: Int, bestValue: Double, maxD: Float, reason: String) {
        if (gen != afSearchGen) return
        // The search is over; stop measuring until the next one asks.
        afPullActive = false
        val landed = ((1.0 - bestValue.coerceIn(0.0, 1.0)) * maxD).toFloat()
        afHoldDiopters = landed
        camera?.let { submitNativeRepeatingRequest(latestState, it) }
        Log.d(
            TAG,
            "Autofocus: landed at ${"%.2f".format(landed)} dpt (dial ${"%.2f".format(bestValue)}, $reason) — handing to HAL",
        )
        // One frame on the landed plane, then continuous AF takes over from exactly there.
        cameraRequestHandler.postDelayed({
            if (gen != afSearchGen) return@postDelayed
            if (manualFocusRequestFor(latestState) != null) return@postDelayed
            afHoldDiopters = null
            afTracking = true
            camera?.let { submitNativeRepeatingRequest(latestState, it) }
        }, AF_HANDOVER_DELAY_MS)
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
            selectedLensValue(latestState) == boundLensValue) {
            applySessionState(latestState)
            return
        }

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
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
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

        val capture = imageCaptureBuilder.build()
        val analysis = analysisBuilder.build().also { it ->
            // A do-nothing analyzer, kept for one reason: an ImageAnalysis with no analyzer
            // stays INACTIVE and contributes nothing to the repeating request, so its
            // interop capture callback never fires. Active, it is the pipeline's per-frame
            // telemetry tap — the live lens position that seeds the AF handoff comes from
            // here. The frame itself is closed immediately; nothing is read or retained.
            it.setAnalyzer(focusAssistExecutor) { image ->
                if (afPullActive) latestSharpness = centreSharpness(image)
                image.close()
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

        val effect = object : CameraEffect(
            PREVIEW,
            focusAssistExecutor,
            processor,
            { error -> Log.e(TAG, "Focus peaking effect error", error) },
        ) {}

        // Real video: a Recorder-backed VideoCapture rides in the same group. Highest
        // quality the device offers, falling down the ladder rather than failing the bind.
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val video = VideoCapture.withOutput(recorder)
        videoCapture = video

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addUseCase(analysis)
            .addUseCase(video)
            .addEffect(effect)
            .build()

        // Reset native session state before rebinding. A stale nativeFocusActive
        // from a previous lens would trigger cancelFocusAndMetering() on the new
        // camera, causing CameraX to switch physical cameras.
        nativeFocusActive = false
        cam2Session = null
        cam2Surfaces = emptyList()
        lastAppliedSessionSignature = null
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, selector, useCaseGroup)
        } catch (error: IllegalArgumentException) {
            val triedDirectPhysicalCamera = desiredLensFacing == CameraSelector.LENS_FACING_BACK &&
                selectedCameraId != null &&
                selectedCameraId == activeLensProfile?.physicalCameraId &&
                selectedCameraId != backCameraProfile?.logicalCameraId
            if (!triedDirectPhysicalCamera) {
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
            } else {
                Log.w(
                    TAG,
                    "Direct physical camera binding failed for cameraId=$selectedCameraId, falling back to logical multi-camera binding.",
                    error,
                )
                selectedCameraId?.let { failedDirectPhysicalCameraIds += it }
                bindCamera(force = true)
                return
            }
        }
        imageCapture = capture
        imageAnalysis = analysis
        camera?.let { refreshBoundCameraCapabilities(it) }
        boundLensFacing = desiredLensFacing
        boundLensValue = selectedLensValue(latestState)
        boundResolution = desiredResolution

        // Detect device capabilities from the bound camera

        // Apply zoom once at bind time. setZoomRatio on a logical multi-camera
        // can switch the active physical camera, so we must NOT call it from
        // applySessionState (which runs on every settings change).
        camera?.let { applyZoom(latestState, it) }
        applySessionState(latestState, force = true)
    }


    // ── Video recording ──────────────────────────────────────────────────────────────

    private fun startVideoRecording() {
        if (activeRecording != null || recordingSegmentFinalizingForReview) return
        val startedAt = System.currentTimeMillis()
        val sessionDirectory = File(
            context.cacheDir,
            "recording-segments/session-$startedAt-${System.nanoTime()}",
        )
        if (!sessionDirectory.mkdirs() && !sessionDirectory.isDirectory) {
            Log.e(TAG, "Could not create private recording workspace: $sessionDirectory")
            return
        }
        recordingSessionDirectory = sessionDirectory
        recordingSessionDisplayName = "DiveControl_$startedAt.mp4"
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
        if (activeRecording != null || recordingSegmentFinalizingForReview) return
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
        if (activeRecording != null) return
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
        ) == PackageManager.PERMISSION_GRANTED
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
                        recordingReviewFile = reviewFile
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
        recordingSessionActive = false
        pendingRecordingAction = null
        recordingSegmentFinalizingForReview = false
        recordingSessionDirectory = null
        recordingSessionDisplayName = null
        recordingSegmentFiles.clear()
        activeRecordingSegmentFile = null
        recordingReviewFile = null
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
            val cameraId = backCameraProfile?.logicalCameraId
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
            waterPressureKpa = latestWaterPressureKpa?.takeIf { filterValue == "Auto" },
            atmosphericPressureKpa = latestAtmosphericPressureKpa?.takeIf { filterValue == "Auto" },
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
            afHoldDiopters = null
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
        // ── HDR / LOG ──
        when (resolvedHdrLogMode(cameraState)) {
            "HDR" -> {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
            }
            "LOG" -> {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, flatLogCurve())
            }
            else -> builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }

        // ── White Balance ──
        val filterProfile = underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (filterProfile != null) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
            lastAutoColorTransform?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            val wbValue = currentValue(cameraState, ".white_balance")
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

        // ── Exposure Compensation (pass through when AE is ON) ──
        // With AE off the index means nothing and the native app flips EV to a read-only
        // meter — the reducer refuses the detents, and nothing is written here.
        if (!aeOff) {
            val ev = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                ?.replace("+", "")?.toDoubleOrNull()
            if (ev != null) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evCompensationIndex(boundCamera, ev))
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
        selectedFocusCapability(latestState)?.let { capability ->
            deviceMinFocusDistance = capability.minFocusDistance
            return
        }
        val cameraInfo = Camera2CameraInfo.from(boundCamera.cameraInfo)
        deviceMinFocusDistance = cameraInfo.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
        ) ?: 0f
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
        withShutterWhiteBalance(::capturePhotoNow)
    }

    private fun capturePhotoNow() {
        val capture = imageCapture ?: run {
            releaseShutterWhiteBalance()
            return
        }
        val name = "DiveControl_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Mobile DiveControl",
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
                    releaseShutterWhiteBalance()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    releaseShutterWhiteBalance()
                }
            },
        )
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

        // --- HDR / LOG / Off ---
        if (!boundHdrExtension) {
            when (resolvedHdrLogMode(cameraState)) {
                "HDR" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                }
                "LOG" -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, flatLogCurve())
                }
                else -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
            }
        }

        // --- White Balance ---
        val filterProfile = underwaterFilterProfile(
            value = currentValue(cameraState, ".filters"),
            depthMeters = currentDepthMeters(),
        )
        if (filterProfile == null) {
            val wbValue = currentValue(cameraState, ".white_balance")
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
            val ev = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                ?.replace("+", "")?.toDoubleOrNull()
            if (ev != null) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    evCompensationIndex(boundCamera, ev),
                )
            }
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
        return currentValue(cameraState, ".megapixels")
    }

    private fun resolvedHdrLogMode(cameraState: CameraState): String {
        currentValue(cameraState, ".hdr_log")?.let { return it }
        if (currentValue(cameraState, ".log") == "On") {
            return "LOG"
        }
        val hdr = currentValue(cameraState, ".hdr")
        return if (hdr == "On" || hdr == "HDR") "HDR" else "Off"
    }

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
        val water = latestWaterPressureKpa ?: return null
        val surface = latestSurfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA
        // Salt-water density correction is tracked separately — do not fold it in here.
        return (water - surface).coerceAtLeast(0.0) / 9.81
    }

    /**
     * A real log look: strongly concave ABOVE the identity line (y = ln(1+9x)/ln(10)), lifting
     * shadows and rolling highlights. The previous curve sat BELOW identity everywhere —
     * mathematically an anti-log — and rendered mids ~2.3 stops dark while AE, which meters
     * linear sensor light, reported everything as fine. Field report: "way way too dark".
     */
    private fun flatLogCurve(): TonemapCurve {
        val channel = floatArrayOf(
            0.000f, 0.000f,
            0.010f, 0.070f,
            0.025f, 0.130f,
            0.050f, 0.210f,
            0.100f, 0.310f,
            0.180f, 0.420f,
            0.250f, 0.490f,
            0.350f, 0.560f,
            0.500f, 0.660f,
            0.650f, 0.740f,
            0.800f, 0.820f,
            0.900f, 0.890f,
            1.000f, 1.000f,
        )
        return TonemapCurve(channel, channel, channel)
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
        return wb == null || CameraCatalog.isWhiteBalanceAuto(wb)
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
    private fun wbPipeline(cal: SensorColorCalibration, kelvin: Int): WbPipeline? {
        val k = kelvin.coerceIn(2300, 10000).toDouble()
        val (x, y) = whitePointXy(k)
        if (y <= 1e-6) return null
        val whiteXyz = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
        val m = interpolatedSensorMatrix(cal, k)
        val r = dot(m[0], whiteXyz)
        val g = dot(m[1], whiteXyz)
        val b = dot(m[2], whiteXyz)
        if (r <= 1e-6 || g <= 1e-6 || b <= 1e-6) return null
        // The MEASURED native curve wins where it exists; the matrix-derived ratio is the model
        // for anything nobody has measured yet. These are the TRUE white ratios and may sit
        // below 1 at warm kelvins.
        val measured = measuredWbGains(k)
        val rTrue = (measured?.first ?: (g / r)).coerceIn(0.1, 10.0)
        val bTrue = (measured?.second ?: (g / b)).coerceIn(0.1, 10.0)
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
        Log.i(
            TAG,
            "wbPipeline k=$kelvin rTrue=%.4f bTrue=%.4f minGain=%.4f gainScale=%.4f maxAbs=%.3f gains=[%.3f %.3f %.3f]"
                .format(rTrue, bTrue, minGain, gainScale, maxAbs, gains.red, gains.greenEven, gains.blue),
        )
        return WbPipeline(gains, transform)
    }

    /** [wbPipeline] with the anchor-continuity correction, quantised for the capture request. */
    private fun computedWbColour(
        kelvin: Int,
    ): Pair<RggbChannelVector, android.hardware.camera2.params.ColorSpaceTransform>? {
        val cal = sensorColorCalibration ?: return null
        val base = wbPipeline(cal, kelvin) ?: return null
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
    private fun whitePointXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin
        return if (t < 4000.0) {
            val x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
            val y = if (t <= 2222.0) {
                -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            } else {
                -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            }
            x to y
        } else {
            val x = if (t <= 7000.0) {
                -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
            } else {
                -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040
            }
            val y = -3.0 * x * x + 2.87 * x - 0.275
            x to y
        }
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
