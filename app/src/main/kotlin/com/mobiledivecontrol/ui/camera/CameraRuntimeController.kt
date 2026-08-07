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
import androidx.camera.video.MediaStoreOutputOptions
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
import java.util.concurrent.Executors

class CameraRuntimeController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DiveCameraCtrl"
        private const val RESUME_STREAM_CHECK_DELAY_MS = 2_500L
        private const val MACRO_STOP_REBIND_DEBOUNCE_MS = 450L

        /** One focus tick per frame-ish: the glide reads as continuous motion, not steps. */
        private const val FOCUS_SLEW_TICK_MS = 16L

        /** Below this the move is a single dial step: apply it straight, no glide. */
        private const val GLIDE_MIN_JUMP = 0.015

        /**
         * A full near-to-infinity pull, in milliseconds. At the 16 ms tick this is ~56 steps of
         * about 0.018 each — below the frame period, so every frame carries a new plane and the
         * travel is continuous rather than stepped. Going slower reads as sluggish; going much
         * faster stops being a pull and becomes the snap this exists to avoid.
         */
        private const val FULL_RACK_MS = 900L

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
    private var onCapabilities: ((CameraCapabilities) -> Unit)? = null
    private var boundLensFacing: Int? = null
    private var boundLensValue: String? = null
    private var boundResolution: String? = null
    private var boundHdrExtension: Boolean = false
    private var boundFocusMode: Boolean = false // true = manual focus, false = AF
    private var latestState: CameraState = CameraState()
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
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastAutoColorTransform = it }
            // The exposure envelope CameraX negotiated. Our own request must inherit it, or the
            // frame rate and flicker bounds change the instant we take over the session and the
            // preview visibly jumps in brightness.
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { lastAeFpsRange = it }
            result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)?.let { lastAntibandingMode = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeSensitivity = it }

            // Periodic diagnostic logging
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusResultLogAtMs < 500L) return
            lastFocusResultLogAtMs = now
            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val af = result.get(CaptureResult.CONTROL_AF_MODE)
            val lp = samsungFocusLensPosition(result)
            val physicalId = runningPhysicalCameraId(result)
            Log.d(
                TAG,
                "CaptureResult fd=$fd af=$af lpEcho=$lp lpActual=$lastObservedVendorLensPos " +
                    "wanted=$lastCommandedLensPos physical=$physicalId native=$nativeFocusActive " +
                    "iso=$lastAeSensitivity expNs=$lastAeExposureNs",
            )
        }
    }

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        initialState: CameraState,
        onReady: (Boolean) -> Unit,
        onDetectedLenses: ((List<String>) -> Unit)? = null,
        onCapabilities: ((CameraCapabilities) -> Unit)? = null,
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        installResumeWatchdog(lifecycleOwner)
        this.onDetectedLenses = onDetectedLenses
        this.onCapabilities = onCapabilities
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
    private var lastAppliedExposureIndex: Int? = null
    private var lastAppliedFlash: String? = null

    /** The last lens position we asked for — the thing [lastObservedVendorLensPos] must converge to. */
    @Volatile private var lastCommandedLensPos: Int? = null

    /** The value an in-flight glide is racking toward; null when no glide is running. */
    @Volatile private var focusSlewTarget: String? = null

    /** The lens's last reported plane, for seeding the AF→manual glide. */
    @Volatile private var lastObservedFocusDiopters: Float? = null

    /** The lens's last reported VCM position, for seeding the AF→manual handoff. */
    @Volatile private var lastObservedVendorLensPos: Int? = null

    /** The HAL's own sensor→sRGB matrix while AWB ran auto — the anchor for manual kelvin WB. */
    @Volatile private var lastAutoColorTransform: android.hardware.camera2.params.ColorSpaceTransform? = null

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
        if (key == null || target == null || fromNum == null || toNum == null ||
            kotlin.math.abs(toNum - fromNum) <= GLIDE_MIN_JUMP
        ) {
            focusSlewGen++
            focusSlewTarget = null
            lensFocusApplied = target
            applySessionState(cameraState)
            return
        }
        focusSlewGen++
        focusSlewTarget = target
        val gen = focusSlewGen
        // A focus PULL, not a jump: the lens walks from where it is to where it is going at a
        // constant rate, one step per frame, through every plane in between. Even speed is the
        // point — easing was tried and read as sluggish at the ends rather than cinematic — and
        // the duration scales with distance so a short correction stays quick. Interruptible
        // throughout: turning the wheel retargets the pull mid-flight.
        val travel = kotlin.math.abs(toNum - fromNum)
        val durationMs = (travel * FULL_RACK_MS).toLong().coerceIn(MIN_RACK_MS, FULL_RACK_MS)
        val stepCount = (durationMs / FOCUS_SLEW_TICK_MS).toInt().coerceIn(2, MAX_RACK_TICKS)
        val ticks = (1..stepCount).map { i ->
            if (i == stepCount) target else "%.4f".format(fromNum + (toNum - fromNum) * i / stepCount)
        }

        fun stepAt(index: Int) {
            if (gen != focusSlewGen) return
            val value = ticks.getOrNull(index) ?: return
            lensFocusApplied = value
            // Apply against the newest state, not the snapshot this glide started from, so a
            // setting changed mid-rack is not stalled behind the glide.
            val base = latestState
            applySessionState(
                base.copy(settingValues = base.settingValues + (key to value)),
            )
            if (index + 1 < ticks.size) {
                cameraRequestHandler.postDelayed({ stepAt(index + 1) }, FOCUS_SLEW_TICK_MS)
            } else {
                focusSlewTarget = null
            }
        }
        stepAt(0)
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
            lastAppliedExposureIndex = null
            lastAppliedFlash = null
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
            it.setAnalyzer(focusAssistExecutor) { image -> image.close() }
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
        if (activeRecording != null) return
        val capture = videoCapture ?: run {
            Log.w(TAG, "Record requested but VideoCapture is not bound — rebinding.")
            bindCamera(force = true)
            videoCapture
        } ?: return
        val name = "DiveControl_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Mobile DiveControl",
                )
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
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
                    RecordingClock.durationMs.value =
                        event.recordingStats.recordedDurationNanos / 1_000_000L
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Log.e(TAG, "Recording finalize error ${event.error}", event.cause)
                    } else {
                        Log.i(TAG, "Recording saved: ${event.outputResults.outputUri}")
                    }
                    activeRecording = null
                    RecordingClock.durationMs.value = 0L
                    RecordingClock.paused.value = false
                }
                else -> Unit
            }
        }
        RecordingClock.paused.value = false
        Log.i(TAG, "Recording started -> Movies/Mobile DiveControl/$name audio=$hasAudio")
    }

    private fun pauseVideoRecording() {
        activeRecording?.pause()
        RecordingClock.paused.value = true
    }

    private fun resumeVideoRecording() {
        activeRecording?.resume()
        RecordingClock.paused.value = false
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
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
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val zoomMax: Double? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper?.toDouble()
            } else {
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble()
            }
            val caps = CameraCapabilities(
                isoMin = isoRange?.lower,
                isoMax = isoRange?.upper,
                exposureMinNs = exposureRange?.lower,
                exposureMaxNs = exposureRange?.upper,
                evMin = if (evRange != null && evStep != null) evRange.lower * evStep else null,
                evMax = if (evRange != null && evStep != null) evRange.upper * evStep else null,
                manualFocusSupported = minFocus > 0f,
                zoomMaxRatio = zoomMax,
            )
            Log.i(TAG, "Camera capabilities probed: $caps")
            onCapabilities?.invoke(caps)
        } catch (e: Exception) {
            Log.w(TAG, "Capability probe failed: ${e.message}")
        }
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
            waterPressureKpa = latestWaterPressureKpa?.takeIf { filterValue == "Auto" },
            atmosphericPressureKpa = latestAtmosphericPressureKpa?.takeIf { filterValue == "Auto" },
        )
        if (!force && signature == lastAppliedSessionSignature) {
            return
        }

        val manualFocusRequest = manualFocusRequestFor(cameraState)
        val isManualFocus = manualFocusRequest != null

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
            applyFlash(cameraState)
            applyExposure(cameraState, boundCamera)
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
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                )
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyNativeFocusRequest(builder, cameraState, manualFocusRequest, session.device.id)
            }

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
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, filterProfile)
                lastAutoColorTransform?.let { builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
            } else {
                val wbValue = currentValue(cameraState, ".white_balance")
                val kelvin = wbValue?.removeSuffix("K")?.toIntOrNull()
                if (wbValue != null && wbValue != "Auto" && kelvin != null) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, colorGainsForKelvin(kelvin))
                    lastAutoColorTransform?.let {
                        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                    }
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                }
            }

            // ── AE / ISO / Shutter ──
            // Manual exposure per the Camera2 contract: AE OFF requires BOTH a sensitivity
            // and an exposure time in the request, or the missing half falls to a template
            // default. Like the native Pro camera, a lone manual ISO (or shutter) keeps its
            // partner at whatever auto-exposure last chose, observed live off the pipe.
            val isoValue = currentValue(cameraState, ".iso")?.toIntOrNull()
            val shutterNs = currentValue(cameraState, ".shutter_speed")?.let { parseShutterNs(it) }
            if (isoValue != null || shutterNs != null) {
                val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = isoValue ?: lastAeSensitivity ?: 800
                val exposure = shutterNs ?: lastAeExposureNs ?: 16_666_666L
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                if (isoRange != null) {
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
                }
                if (exposureRange != null) {
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceIn(exposureRange.lower, exposureRange.upper))
                }
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            val aeOff = isoValue != null || shutterNs != null

            // ── Exposure Compensation (pass through when AE is ON) ──
            if (!aeOff) {
                val ev = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
                    ?.replace("+", "")?.toDoubleOrNull()
                if (ev != null) {
                    val exposureState = boundCamera.cameraInfo.exposureState
                    val step = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.1f
                    val idx = (ev / step).roundToInt().coerceIn(
                        exposureState.exposureCompensationRange.lower,
                        exposureState.exposureCompensationRange.upper,
                    )
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
                }
            }

            // ── Flash ──
            val flashValue = currentValue(cameraState, ".flash")
            if (flashValue == "On" || flashValue == "Torch") {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }

            session.setRepeatingRequest(builder.build(), sessionCaptureCallback, cameraRequestHandler)
            val capability = selectedFocusCapability(cameraState)
            Log.d(
                TAG,
                "Native focus applied: af=${if (manualFocusRequest == null) "auto" else "manual"} lens=${selectedLensValue(cameraState)} sessionCameraId=${session.device.id} zoom=${nativeZoomRatio(cameraState, boundCamera)} diopters=${manualFocusRequest?.diopters} minFocusDist=${capability?.minFocusDistance} transport=${capability?.transport} vendorLensPos=${manualFocusRequest?.vendorLensPosition} (norm=${manualFocusRequest?.normalizedFocus})",
            )
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

    private fun capturePhoto() {
        val capture = imageCapture ?: return
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
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = Unit
                override fun onError(exception: ImageCaptureException) = Unit
            },
        )
    }

    private fun applyFlash(cameraState: CameraState) {
        val flashValue = currentValue(cameraState, ".flash") ?: return
        val capture = imageCapture ?: return
        // Same reasoning as applyExposure: re-asserting the torch on every focus step disturbs 3A.
        if (flashValue == lastAppliedFlash) return
        lastAppliedFlash = flashValue
        capture.flashMode = when (flashValue) {
            "Auto" -> ImageCapture.FLASH_MODE_AUTO
            "On", "Torch" -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flashValue == "On" || flashValue == "Torch")
    }

    private fun applyExposure(cameraState: CameraState, boundCamera: Camera) {
        val exposureValue = currentValue(cameraState, ".exposure_compensation", ".exposure_value", ".exposure")
            ?.replace("+", "")
            ?.toDoubleOrNull()
            ?: return
        val exposureState = boundCamera.cameraInfo.exposureState
        val compensationStep = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.1f
        val requestedIndex = (exposureValue / compensationStep).roundToInt()
        val clamped = requestedIndex.coerceIn(
            exposureState.exposureCompensationRange.lower,
            exposureState.exposureCompensationRange.upper,
        )
        // Idempotent: re-issuing the same index makes CameraX rebuild its request and the HAL
        // re-converge auto-exposure, which reads on screen as a brightness pulse. Focus moves
        // re-run this method on every step, so an unchanged value must cost nothing.
        if (clamped == lastAppliedExposureIndex) return
        lastAppliedExposureIndex = clamped
        boundCamera.cameraControl.setExposureCompensationIndex(clamped)
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
            if (wbValue != null && wbValue != "Auto" && kelvin != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, colorGainsForKelvin(kelvin))
                lastAutoColorTransform?.let {
                    builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                }
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }
        } else {
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
        if (isoValue != null || shutterNs != null) {
            try {
                val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = isoValue ?: lastAeSensitivity ?: 800
                val exposure = shutterNs ?: lastAeExposureNs ?: 16_666_666L
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                if (isoRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange.lower, isoRange.upper))
                }
                if (exposureRange != null) {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.coerceIn(exposureRange.lower, exposureRange.upper))
                }
            } catch (_: Exception) { }
        }

        try {
            val cam2Control = Camera2CameraControl.from(boundCamera.cameraControl)
            cam2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 options FAILED", e)
        }
    }

    private fun parseShutterNs(value: String): Long? {
        if (value == "Auto") return null
        return if (value.endsWith("\"")) {
            value.removeSuffix("\"").toDoubleOrNull()?.let { (it * 1_000_000_000L).toLong() }
        } else {
            val parts = value.split("/")
            if (parts.size == 2) {
                val num = parts[0].toDoubleOrNull() ?: return null
                val den = parts[1].toDoubleOrNull() ?: return null
                if (den == 0.0) return null
                ((num / den) * 1_000_000_000L).toLong()
            } else {
                null
            }
        }
    }

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

    /**
     * Manual white balance done to the Camera2 contract: AWB OFF is undefined without explicit
     * colour-correction gains. Black-body white point (Tanner Helland fit) at the chosen
     * kelvin, gains = reciprocal illuminant normalised to green — a scene lit at that kelvin
     * renders neutral. 2300K boosts blue ~3x; 6500K is ~unity; 10000K leans warm.
     */
    private fun colorGainsForKelvin(kelvin: Int): RggbChannelVector {
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
