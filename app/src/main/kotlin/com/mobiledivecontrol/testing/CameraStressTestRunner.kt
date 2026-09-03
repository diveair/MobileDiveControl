package com.mobiledivecontrol.testing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraFeatureStatus
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.CameraSettingSpec
import com.mobiledivecontrol.core.CameraStressPlan
import com.mobiledivecontrol.core.CameraUiZone
import com.mobiledivecontrol.core.BottomBarItem
import com.mobiledivecontrol.ui.camera.CameraPipelineTelemetry
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal const val CAMERA_STRESS_REBIND_OBSERVATION_GRACE_MS = 250L

/**
 * A setting can be capable of replacing the camera graph without every one of its values doing so.
 * Playback-only frame-rate changes and resolutions that resolve to the already-bound preview graph
 * are examples. Give the runtime a short opportunity to announce a bind; once it does, only a frame
 * from that new binding is valid. If it does not, a fresh frame from the retained graph proves that
 * the preview remained responsive.
 */
internal fun cameraStressFrameIsValid(
    freshFrame: Boolean,
    rebindCapable: Boolean,
    bindObserved: Boolean,
    newBindingPresented: Boolean,
    rebindObservationGraceElapsed: Boolean,
): Boolean {
    if (!freshFrame) return false
    if (!rebindCapable) return true
    return if (bindObserved) newBindingPresented else rebindObservationGraceElapsed
}

/** Exposure-neutral values keep later sweeps visible and their dependency rules testable. */
internal fun cameraStressNeutralBaseline(
    settingId: String,
    options: List<String>,
    current: String,
): String {
    val preferred = when {
        settingId.endsWith(".iso") -> options.firstOrNull { it.equals("Auto", true) }
        settingId.endsWith(".shutter_speed") -> options.firstOrNull { it.equals("Auto", true) }
        settingId.endsWith(".exposure") || settingId.endsWith(".exposure_value") ->
            options.firstOrNull { it in setOf("0", "0.0", "+0.0") }
        settingId.endsWith(".white_balance") ->
            options.firstOrNull { it.equals("Auto", true) }
                ?: options.firstOrNull { it.startsWith("Auto ", true) }
        settingId.endsWith(".lens") -> options.firstOrNull { it.equals("Auto", true) }
        settingId.endsWith(".focus_peaking") ||
            settingId.endsWith(".focus_assist") ||
            settingId.endsWith(".exposure_display") ||
            settingId.endsWith(".hdr_log") ||
            settingId.endsWith(".hdr") ||
            settingId.endsWith(".log") -> options.firstOrNull { it.equals("Off", true) }
        settingId.endsWith(".filter") ->
            options.firstOrNull { it.equals("None", true) }
                ?: options.firstOrNull { it.equals("Original", true) }
                ?: options.firstOrNull { it.equals("Off", true) }
        else -> null
    }
    return preferred ?: current
}

/**
 * Explicitly launched, debug-only, on-device camera torture test.
 *
 * Every mutation is sent through [DiveViewModel.dispatch] and the production reducer, which means
 * the same CameraX/Camera2 effects, dependency rules, and UI state used by touch and housing input
 * are exercised. It never presses the shutter and never writes media.
 */
class CameraStressTestRunner(
    private val activity: Activity,
    private val viewModel: DiveViewModel,
    private val config: Config,
) {
    data class Config(
        val dwellMs: Long = 180L,
        val previewTimeoutMs: Long = 1_500L,
        val rebindTimeoutMs: Long = 8_000L,
        val maxResponsivePreviewMs: Long = 500L,
        val restoreOriginalState: Boolean = true,
        val exhaustiveSliders: Boolean = true,
        val modes: List<CameraModeId> = CameraStressPlan.modes,
    ) {
        companion object {
            fun from(intent: Intent): Config {
                val requestedModes = intent.getStringExtra(EXTRA_MODES)
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    .orEmpty()
                val modes = CameraStressPlan.modes.filter { mode ->
                    requestedModes.isEmpty() || mode.name in requestedModes || mode.label in requestedModes
                }.ifEmpty { CameraStressPlan.modes }
                return Config(
                    dwellMs = intent.getLongExtra(EXTRA_DWELL_MS, 180L).coerceIn(50L, 2_000L),
                    previewTimeoutMs = intent.getLongExtra(EXTRA_PREVIEW_TIMEOUT_MS, 1_500L)
                        .coerceIn(500L, 15_000L),
                    rebindTimeoutMs = intent.getLongExtra(EXTRA_REBIND_TIMEOUT_MS, 8_000L)
                        .coerceIn(1_500L, 30_000L),
                    maxResponsivePreviewMs = intent.getLongExtra(
                        EXTRA_MAX_RESPONSIVE_PREVIEW_MS,
                        500L,
                    ).coerceIn(100L, 5_000L),
                    restoreOriginalState = intent.getBooleanExtra(EXTRA_RESTORE, true),
                    exhaustiveSliders = intent.getBooleanExtra(EXTRA_EXHAUSTIVE_SLIDERS, true),
                    modes = modes,
                )
            }
        }
    }

    private class PreviewFreezeAbort(message: String) : RuntimeException(message)

    private data class ResourceSample(
        val processCpuMs: Long,
        val totalPssKb: Int,
        val heapUsedKb: Long,
        val batteryTemperatureC: Double?,
        val thermalStatus: Int?,
    )

    private data class TransitionResult(
        val status: String,
        val actualValue: String,
        val dispatchMs: Double,
        val stateSettleMs: Long,
        val previewLatencyMs: Long?,
        val note: String,
    )

    private val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val reportDir = File(
        requireNotNull(activity.getExternalFilesDir("camera-stress")) {
            "External app files directory is unavailable"
        },
        "run-$runId",
    )
    private val transitionsFile = File(reportDir, "transitions.csv")
    private val summaryFile = File(reportDir, "summary.json")
    private val originalState = viewModel.state.value.camera
    private val originalAppMode = viewModel.state.value.mode
    private val visitedModes = linkedSetOf<CameraModeId>()
    private val visitedSettings = linkedSetOf<String>()
    private val failures = mutableListOf<JSONObject>()
    private var sequence = 0
    private var passed = 0
    private var transformed = 0
    private var skipped = 0
    private var previewTimeouts = 0
    private var previewLagSpikes = 0
    private var maxPreviewLatencyMs = 0L
    private var maxDispatchMs = 0.0
    private var foregroundInterruptions = 0
    private var foregroundPauseActive = false
    private var previewPipelineFailed = false
    private lateinit var writer: BufferedWriter
    private val frameStats = WindowFrameStats(activity.window)
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    suspend fun run() {
        reportDir.mkdirs()
        CameraPipelineTelemetry.resetForStressRun()
        installUncaughtExceptionRecorder()
        frameStats.start()
        writer = withContext(Dispatchers.IO) {
            BufferedWriter(FileWriter(transitionsFile, false)).also {
                it.write(CSV_HEADER)
                it.newLine()
                it.flush()
            }
        }

        Log.i(TAG, "CAMERA_STRESS_START run=$runId report=${reportDir.absolutePath}")
        CameraStressVisualStatus.publish(
            CameraStressVisualSnapshot(runId, 0, "Starting", "Camera stress test", "Waiting for preview"),
        )
        var completion = "complete"
        try {
            val initialFrame = awaitDisplayedFrame(
                after = CameraPipelineTelemetry.snapshot(),
                timeoutMs = config.rebindTimeoutMs,
            )
            if (initialFrame == null) {
                recordFailure("initial_preview", "No displayed preview frame before the test began")
                abortForPreviewFailure("initial preview did not present")
            }

            for (mode in config.modes) {
                exerciseMode(mode)
            }
        } catch (error: PreviewFreezeAbort) {
            completion = "preview_failure"
            Log.e(TAG, "Stress runner stopped at first preview failure: ${error.message}")
        } catch (error: Throwable) {
            completion = "aborted"
            recordFailure("runner_exception", error.stackTraceToString())
            Log.e(TAG, "Stress runner aborted", error)
        } finally {
            if (config.restoreOriginalState && !previewPipelineFailed) {
                runCatching { restoreOriginalSelections() }
                    .onFailure { recordFailure("restore_failed", it.stackTraceToString()) }
            }
            frameStats.stop()
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
            withContext(Dispatchers.IO) { writer.close() }
            writeSummary(completion)
            CameraStressVisualStatus.publish(
                CameraStressVisualSnapshot(
                    runId = runId,
                    sequence = sequence,
                    mode = "Complete",
                    setting = "$sequence visible transitions",
                    requested = "",
                    actual = "previewTimeouts=$previewTimeouts lagSpikes=$previewLagSpikes " +
                        "failures=${failures.size}",
                    status = if (failures.isEmpty() && previewTimeouts == 0 && previewLagSpikes == 0) {
                        "PASS"
                    } else {
                        "FAIL"
                    },
                ),
            )
            Log.i(
                TAG,
                "CAMERA_STRESS_COMPLETE run=$runId status=$completion " +
                    "previewTimeouts=$previewTimeouts failures=${failures.size} " +
                    "report=${reportDir.absolutePath}",
            )
        }
    }

    private suspend fun exerciseMode(mode: CameraModeId) {
        awaitActivityForeground()
        val railIndex = CameraCatalog.primaryRailEntries.indexOfFirst { it.mode == mode }
        if (railIndex < 0) {
            skipped++
            recordFailure("missing_mode", "${mode.name} is not present in the user-facing mode rail")
            return
        }

        Log.i(TAG, "CAMERA_STRESS_MODE ${mode.name}")
        CameraStressVisualStatus.publish(
            CameraStressVisualSnapshot(runId, sequence + 1, mode.label, "Mode", mode.label),
        )
        val before = CameraPipelineTelemetry.snapshot()
        val dispatchStarted = SystemClock.elapsedRealtimeNanos()
        viewModel.dispatch(CameraCommand.ActivateModeRailEntry(railIndex))
        val dispatchMs = elapsedMs(dispatchStarted)
        maxDispatchMs = maxOf(maxDispatchMs, dispatchMs)
        val stateStarted = SystemClock.elapsedRealtime()
        val modeReady = withTimeoutOrNull(config.rebindTimeoutMs) {
            while (viewModel.state.value.camera.activeMode != mode) delay(10L)
            true
        } == true
        val stateMs = SystemClock.elapsedRealtime() - stateStarted
        val previewMs = awaitDisplayedFrame(
            after = before,
            timeoutMs = config.rebindTimeoutMs,
            requireNewBinding = true,
        )
        if (!modeReady || previewMs == null) {
            if (previewMs == null) previewTimeouts++
            recordFailure(
                "mode_transition",
                "mode=${mode.name} stateReady=$modeReady previewLatencyMs=${previewMs ?: -1}",
            )
        } else {
            recordPreviewLatency(previewMs, "mode=${mode.name}")
        }
        writeRow(
            phase = "exercise",
            mode = mode,
            setting = null,
            requestedValue = "",
            result = TransitionResult(
                status = when {
                    !modeReady || previewMs == null -> "FAIL"
                    previewMs > config.maxResponsivePreviewMs -> "LAG"
                    else -> "PASS"
                },
                actualValue = viewModel.state.value.camera.activeMode.name,
                dispatchMs = dispatchMs,
                stateSettleMs = stateMs,
                previewLatencyMs = previewMs,
                note = "mode activation",
            ),
            before = before,
        )
        visitedModes += mode
        if (previewMs == null) {
            abortForPreviewFailure("mode=${mode.name} did not present its own preview")
        }

        // Give the capability callback one short window to replace static catalog guesses with
        // the options this exact lens/session actually advertises.
        delay(maxOf(config.dwellMs, 120L))
        normalizeExposureStateForMode(mode)
        val specs = CameraCatalog.settingsFor(viewModel.state.value.camera)
        for (initialSpec in specs) {
            visitedSettings += initialSpec.id
            if (initialSpec.status == CameraFeatureStatus.Unavailable) {
                skipped++
                writeSkipped(mode, initialSpec, "catalog status=Unavailable: ${initialSpec.note.orEmpty()}")
                continue
            }

            val currentBeforeBaseline = CameraCatalog.currentValue(
                viewModel.state.value.camera,
                initialSpec,
            )
            presentSettingInUi(initialSpec)
            val neutralBaseline = cameraStressNeutralBaseline(
                settingId = initialSpec.id,
                options = initialSpec.options,
                current = currentBeforeBaseline,
            )
            if (neutralBaseline != currentBeforeBaseline) {
                driveSetting(mode, initialSpec, neutralBaseline, phase = "baseline")
            }
            val valueBeforeSweep = neutralBaseline
            val targets = CameraStressPlan.targetValues(
                initialSpec,
                exhaustiveSliders = config.exhaustiveSliders,
            )
            for (target in targets) {
                // Resolution, FPS and lens changes can reshape the live catalog. Re-resolve the
                // setting before every target and report options the device removed explicitly.
                val spec = CameraCatalog.settingsFor(viewModel.state.value.camera)
                    .firstOrNull { it.id == initialSpec.id }
                if (spec == null || target !in spec.options) {
                    skipped++
                    writeSkipped(mode, initialSpec, "target '$target' removed by current device constraints")
                    continue
                }
                val result = driveSetting(mode, spec, target)
                if (result.status == "PASS" || result.status == "ALREADY") passed++ else transformed++
            }
            // Extreme ISO, shutter, WB, focus and effect values are valid test cases, but carrying
            // the final extreme into the next several-hundred-value sweep can make a live preview
            // look uniformly white/black. Restore the value that was active before this setting's
            // sweep so every subsequent control is judged from the same visible baseline.
            val resetSpec = CameraCatalog.settingsFor(viewModel.state.value.camera)
                .firstOrNull { it.id == initialSpec.id }
            if (resetSpec != null &&
                valueBeforeSweep in resetSpec.options &&
                CameraCatalog.currentValue(viewModel.state.value.camera, resetSpec) != valueBeforeSweep
            ) {
                driveSetting(mode, resetSpec, valueBeforeSweep, phase = "reset")
            }
        }
    }

    /**
     * An interrupted engineering run can legitimately leave manual exposure, a physical lens or a
     * diagnostic colour effect persisted. Normalize those visual controls before the first
     * unrelated setting is exercised; resetting each control only when its own turn arrives is too
     * late when Focus precedes EV/filter controls.
     */
    private suspend fun normalizeExposureStateForMode(mode: CameraModeId) {
        val candidates = CameraCatalog.settingsFor(viewModel.state.value.camera)
        for (spec in candidates) {
            val currentSpec = CameraCatalog.settingsFor(viewModel.state.value.camera)
                .firstOrNull { it.id == spec.id } ?: continue
            val current = CameraCatalog.currentValue(viewModel.state.value.camera, currentSpec)
            val neutral = cameraStressNeutralBaseline(currentSpec.id, currentSpec.options, current)
            if (neutral == current || neutral !in currentSpec.options) continue
            presentSettingInUi(currentSpec)
            driveSetting(mode, currentSpec, neutral, phase = "mode_baseline")
        }
    }

    private suspend fun driveSetting(
        mode: CameraModeId,
        spec: CameraSettingSpec,
        target: String,
        phase: String = "exercise",
    ): TransitionResult {
        awaitActivityForeground()
        CameraStressVisualStatus.publish(
            CameraStressVisualSnapshot(
                runId = runId,
                sequence = sequence + 1,
                mode = mode.label,
                setting = spec.label,
                requested = target,
            ),
        )
        val before = CameraPipelineTelemetry.snapshot()
        val current = CameraCatalog.currentValue(viewModel.state.value.camera, spec)
        val currentIndex = spec.options.indexOf(current).takeIf { it >= 0 } ?: 0
        val targetIndex = spec.options.indexOf(target)
        if (targetIndex < 0) {
            val result = TransitionResult("SKIP", current, 0.0, 0L, 0L, "target absent")
            writeRow(phase, mode, spec, target, result, before)
            publishResult(mode, spec, target, result)
            delay(config.dwellMs)
            return result
        }

        if (current == target) {
            val result = TransitionResult("ALREADY", current, 0.0, 0L, 0L, "value already active")
            writeRow(phase, mode, spec, target, result, before)
            publishResult(mode, spec, target, result)
            delay(config.dwellMs)
            return result
        }

        val step = if (CameraCatalog.isCircularSlider(spec)) {
            (targetIndex - currentIndex + spec.options.size) % spec.options.size
        } else {
            targetIndex - currentIndex
        }.let { if (it == 0) 1 else it }

        val uiBefore = frameStats.snapshot()
        val stateStarted = SystemClock.elapsedRealtime()
        val dispatchStarted = SystemClock.elapsedRealtimeNanos()
        viewModel.dispatch(CameraCommand.NudgeSetting(spec.id, step))
        val dispatchMs = elapsedMs(dispatchStarted)
        maxDispatchMs = maxOf(maxDispatchMs, dispatchMs)

        val stateAccepted = withTimeoutOrNull(500L) {
            while (CameraCatalog.currentValue(viewModel.state.value.camera, spec) == current) delay(5L)
            true
        } == true
        val stateMs = SystemClock.elapsedRealtime() - stateStarted
        val actualSpec = CameraCatalog.settingsFor(viewModel.state.value.camera)
            .firstOrNull { it.id == spec.id } ?: spec
        val actual = CameraCatalog.currentValue(viewModel.state.value.camera, actualSpec)
        val timeout = if (CameraStressPlan.requiresCameraRebind(spec.id)) {
            config.rebindTimeoutMs
        } else {
            config.previewTimeoutMs
        }
        val previewMs = awaitDisplayedFrame(
            after = before,
            timeoutMs = timeout,
            requireNewBinding = CameraStressPlan.requiresCameraRebind(spec.id),
        )
        if (previewMs == null) {
            previewTimeouts++
            recordFailure(
                "preview_timeout",
                "mode=${mode.name} setting=${spec.id} requested=$target actual=$actual " +
                    "timeoutMs=$timeout bind=${CameraPipelineTelemetry.snapshot().lastBindSignature}",
            )
        } else {
            recordPreviewLatency(
                previewMs,
                "mode=${mode.name} setting=${spec.id} requested=$target actual=$actual",
            )
        }

        val uiAfter = frameStats.snapshot()
        val status = when {
            previewMs == null -> "FREEZE"
            previewMs > config.maxResponsivePreviewMs -> "LAG"
            actual == target -> "PASS"
            !stateAccepted -> "REJECTED"
            else -> "TRANSFORMED"
        }
        val note = buildString {
            if (actual != target) append("dependency/capability changed requested value; ")
            append("uiFrames=").append(uiAfter.frameCount - uiBefore.frameCount)
            append(" uiJank=").append(uiAfter.jankyFrameCount - uiBefore.jankyFrameCount)
            append(" dropped=").append(uiAfter.droppedFrameCount - uiBefore.droppedFrameCount)
        }
        val result = TransitionResult(status, actual, dispatchMs, stateMs, previewMs, note)
        writeRow(phase, mode, spec, target, result, before)
        publishResult(mode, spec, target, result)
        if (previewMs == null) {
            abortForPreviewFailure("mode=${mode.name} setting=${spec.id} value=$target froze")
        }
        delay(config.dwellMs)
        return result
    }

    private fun abortForPreviewFailure(message: String): Nothing {
        previewPipelineFailed = true
        throw PreviewFreezeAbort(message)
    }

    private fun recordPreviewLatency(previewMs: Long, context: String) {
        maxPreviewLatencyMs = maxOf(maxPreviewLatencyMs, previewMs)
        if (previewMs <= config.maxResponsivePreviewMs) return
        previewLagSpikes++
        // Preserve a bounded set of examples while the aggregate count remains authoritative.
        if (previewLagSpikes <= 32) {
            recordFailure(
                "preview_lag",
                "$context previewLatencyMs=$previewMs " +
                    "responsiveLimitMs=${config.maxResponsivePreviewMs}",
            )
        }
    }

    /**
     * Put the real bottom editor or Options row on screen before exercising its values. The value
     * mutation still goes through the production reducer/camera runtime; this presentation step
     * makes the run observable instead of silently changing an off-screen setting map.
     */
    private suspend fun presentSettingInUi(initialSpec: CameraSettingSpec) {
        awaitActivityForeground()
        var camera = viewModel.state.value.camera
        if (camera.focusedZone == CameraUiZone.ModeRail) {
            viewModel.dispatch(CameraCommand.Back)
            delay(24L)
            camera = viewModel.state.value.camera
        }
        if (camera.focusedZone == CameraUiZone.LiveView) {
            viewModel.dispatch(CameraCommand.Confirm)
            delay(24L)
            camera = viewModel.state.value.camera
        }
        if (camera.settingsEditing) {
            viewModel.dispatch(CameraCommand.Confirm)
            delay(24L)
            camera = viewModel.state.value.camera
        }

        var barItems = CameraCatalog.settingsBarItems(camera)
        val barIndex = barItems.indexOfFirst { item ->
            item is BottomBarItem.Setting && item.spec.id == initialSpec.id
        }
        if (barIndex >= 0) {
            if (camera.showMoreSettings) {
                viewModel.dispatch(CameraCommand.ToggleOptionsMenu)
                delay(24L)
                camera = viewModel.state.value.camera
                barItems = CameraCatalog.settingsBarItems(camera)
            }
            val count = barItems.size
            if (count > 0) {
                val current = camera.settingsCursor.coerceIn(0, count - 1)
                val right = (barIndex - current + count) % count
                val left = (current - barIndex + count) % count
                val command = if (right <= left) CameraCommand.NavigateRight else CameraCommand.NavigateLeft
                repeat(minOf(right, left)) {
                    viewModel.dispatch(command)
                    delay(18L)
                }
            }
            if (!viewModel.state.value.camera.settingsEditing) {
                viewModel.dispatch(CameraCommand.Confirm)
                delay(30L)
            }
            return
        }

        camera = viewModel.state.value.camera
        if (!camera.showMoreSettings) {
            viewModel.dispatch(CameraCommand.ToggleOptionsMenu)
            delay(30L)
            camera = viewModel.state.value.camera
        }
        val options = CameraCatalog.optionsMenuSettings(camera)
        val optionIndex = options.indexOfFirst { it.id == initialSpec.id }
        if (optionIndex >= 0) {
            viewModel.dispatch(CameraCommand.SelectOptionsItem(optionIndex))
            delay(30L)
        }
    }

    private fun publishResult(
        mode: CameraModeId,
        spec: CameraSettingSpec,
        requested: String,
        result: TransitionResult,
    ) {
        CameraStressVisualStatus.publish(
            CameraStressVisualSnapshot(
                runId = runId,
                sequence = sequence,
                mode = mode.label,
                setting = spec.label,
                requested = requested,
                actual = result.actualValue,
                status = result.status,
            ),
        )
    }

    private suspend fun restoreOriginalSelections() {
        Log.i(TAG, "CAMERA_STRESS_RESTORE_START")
        val before = CameraPipelineTelemetry.snapshot()
        viewModel.restoreCameraStressSnapshot(originalState, originalAppMode)
        val stateReady = withTimeoutOrNull(config.rebindTimeoutMs) {
            while (viewModel.state.value.camera != originalState) delay(10L)
            true
        } == true
        val previewMs = awaitDisplayedFrame(
            after = before,
            timeoutMs = config.rebindTimeoutMs,
            requireNewBinding = true,
        )
        if (!stateReady || previewMs == null) {
            if (previewMs == null) previewTimeouts++
            recordFailure(
                "restore_snapshot",
                "stateReady=$stateReady previewLatencyMs=${previewMs ?: -1}",
            )
            return
        }
        recordPreviewLatency(previewMs, "atomic stress snapshot restore")
        Log.i(TAG, "CAMERA_STRESS_RESTORE_COMPLETE")
    }

    private suspend fun awaitDisplayedFrame(
        after: CameraPipelineTelemetry.Snapshot,
        timeoutMs: Long,
        requireNewBinding: Boolean = false,
    ): Long? {
        var baseline = after
        var requireGeneration = requireNewBinding
        var foregroundBudgetMs = timeoutMs
        var foregroundElapsedMs = 0L
        var lastTick = SystemClock.elapsedRealtime()
        var reached = false
        while (foregroundBudgetMs > 0L) {
            if (!isActivityForeground()) {
                awaitActivityForeground()
                // The SurfaceView was legitimately destroyed while another app was in front.
                // Resume validation starts from the recovered activity, not from a stale frame
                // counter or graph generation that predates the lifecycle interruption.
                baseline = CameraPipelineTelemetry.snapshot()
                requireGeneration = false
                lastTick = SystemClock.elapsedRealtime()
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val foregroundDelta = (now - lastTick).coerceAtLeast(0L)
            foregroundBudgetMs -= foregroundDelta
            foregroundElapsedMs += foregroundDelta
            lastTick = now
            val current = CameraPipelineTelemetry.snapshot()
            val bindObserved = current.bindStartedCount > baseline.bindStartedCount
            val rebindObservationGraceElapsed =
                foregroundElapsedMs >= CAMERA_STRESS_REBIND_OBSERVATION_GRACE_MS
            val effectedGraphPresented = cameraStressFrameIsValid(
                freshFrame = current.displayedFrameCount > baseline.displayedFrameCount,
                rebindCapable = requireGeneration,
                bindObserved = bindObserved,
                newBindingPresented =
                    current.lastDisplayedBindingGeneration > baseline.lastDisplayedBindingGeneration,
                rebindObservationGraceElapsed = rebindObservationGraceElapsed,
            )
            val directGraphPresented = cameraStressFrameIsValid(
                freshFrame = current.directPreviewFrameCount > baseline.directPreviewFrameCount,
                rebindCapable = requireGeneration,
                bindObserved = bindObserved,
                newBindingPresented = current.bindCompletedCount > baseline.bindCompletedCount,
                rebindObservationGraceElapsed = rebindObservationGraceElapsed,
            )
            if (effectedGraphPresented || directGraphPresented) {
                reached = true
                break
            }
            delay(12L)
        }
        return foregroundElapsedMs.takeIf { reached }
    }

    private fun isActivityForeground(): Boolean {
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED)
            ?: true
        return resumed && activity.hasWindowFocus()
    }

    private suspend fun awaitActivityForeground() {
        if (isActivityForeground()) {
            foregroundPauseActive = false
            return
        }
        if (!foregroundPauseActive) {
            foregroundPauseActive = true
            foregroundInterruptions++
            Log.i(TAG, "CAMERA_STRESS_PAUSED activity not foreground")
        }
        while (!isActivityForeground()) delay(50L)
        foregroundPauseActive = false
        Log.i(TAG, "CAMERA_STRESS_RESUMED activity foreground")
    }

    private suspend fun writeSkipped(mode: CameraModeId, spec: CameraSettingSpec, reason: String) {
        writeRow(
            phase = "exercise",
            mode = mode,
            setting = spec,
            requestedValue = "",
            result = TransitionResult("SKIP", CameraCatalog.currentValue(viewModel.state.value.camera, spec), 0.0, 0L, 0L, reason),
            before = CameraPipelineTelemetry.snapshot(),
        )
    }

    private suspend fun writeRow(
        phase: String,
        mode: CameraModeId,
        setting: CameraSettingSpec?,
        requestedValue: String,
        result: TransitionResult,
        before: CameraPipelineTelemetry.Snapshot,
    ) {
        val pipeline = CameraPipelineTelemetry.snapshot()
        val ui = frameStats.snapshot()
        val resources = resourceSample()
        val now = System.currentTimeMillis()
        val row = listOf(
            (++sequence).toString(),
            now.toString(),
            phase,
            mode.name,
            setting?.id.orEmpty(),
            setting?.label.orEmpty(),
            requestedValue,
            result.actualValue,
            result.status,
            "%.3f".format(Locale.US, result.dispatchMs),
            result.stateSettleMs.toString(),
            result.previewLatencyMs?.toString().orEmpty(),
            (pipeline.capturedAtMs - maxOf(
                pipeline.lastDisplayedFrameAtMs,
                pipeline.lastDirectPreviewFrameAtMs,
            )).takeIf {
                pipeline.lastDisplayedFrameAtMs > 0L || pipeline.lastDirectPreviewFrameAtMs > 0L
            }?.toString().orEmpty(),
            (pipeline.displayedFrameCount + pipeline.directPreviewFrameCount).toString(),
            pipeline.lastDisplayedBindingGeneration.toString(),
            pipeline.lastSourceFrameTimestampNs.toString(),
            pipeline.sourceTimestampStallCount.toString(),
            (pipeline.bindStartedCount - before.bindStartedCount).toString(),
            (pipeline.bindCompletedCount - before.bindCompletedCount).toString(),
            pipeline.lastBindDurationMs.toString(),
            pipeline.previewSwapFailureCount.toString(),
            pipeline.runtimeFailureCount.toString(),
            ui.frameCount.toString(),
            ui.jankyFrameCount.toString(),
            "%.3f".format(Locale.US, ui.maxFrameMs),
            ui.droppedFrameCount.toString(),
            resources.processCpuMs.toString(),
            resources.totalPssKb.toString(),
            resources.heapUsedKb.toString(),
            resources.batteryTemperatureC?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
            resources.thermalStatus?.toString().orEmpty(),
            result.note,
        ).joinToString(",") { csv(it) }
        withContext(Dispatchers.IO) {
            writer.write(row)
            writer.newLine()
            writer.flush()
        }
    }

    private fun resourceSample(): ResourceSample {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val battery = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = rawTemperature?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            null
        }
        return ResourceSample(
            processCpuMs = Process.getElapsedCpuTime(),
            totalPssKb = memory.totalPss,
            heapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            batteryTemperatureC = temperature,
            thermalStatus = thermal,
        )
    }

    private fun recordFailure(kind: String, detail: String) {
        failures += JSONObject().put("kind", kind).put("detail", detail)
    }

    private fun installUncaughtExceptionRecorder() {
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        val downstream = previousUncaughtHandler
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Synchronous on purpose: after this callback Android terminates the process, so an
            // asynchronous write would usually lose the exact exception that ended the run.
            runCatching {
                File(reportDir, "uncaught-exception.txt").writeText(
                    "thread=${thread.name}\n" + error.stackTraceToString(),
                )
            }
            Log.e(TAG, "CAMERA_STRESS_UNCAUGHT thread=${thread.name}", error)
            downstream?.uncaughtException(thread, error)
        }
    }

    private suspend fun writeSummary(completion: String) {
        val pipeline = CameraPipelineTelemetry.snapshot()
        val ui = frameStats.snapshot()
        val resources = resourceSample()
        val summary = JSONObject()
            .put("runId", runId)
            .put("completion", completion)
            .put("package", activity.packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("config", JSONObject()
                .put("dwellMs", config.dwellMs)
                .put("previewTimeoutMs", config.previewTimeoutMs)
                .put("rebindTimeoutMs", config.rebindTimeoutMs)
                .put("maxResponsivePreviewMs", config.maxResponsivePreviewMs)
                .put("restoreOriginalState", config.restoreOriginalState)
                .put("exhaustiveSliders", config.exhaustiveSliders)
                .put("modes", JSONArray(config.modes.map(CameraModeId::name))))
            .put("coverage", JSONObject()
                .put("modesVisited", visitedModes.size)
                .put("modesExpected", config.modes.size)
                .put("settingsVisited", visitedSettings.size)
                .put("transitions", sequence)
                .put("passedOrAlreadyActive", passed)
                .put("transformedOrRejected", transformed)
                .put("skipped", skipped))
            .put("responsiveness", JSONObject()
                .put("previewTimeouts", previewTimeouts)
                .put("previewLagSpikes", previewLagSpikes)
                .put("maxPreviewLatencyMs", maxPreviewLatencyMs)
                .put("foregroundInterruptions", foregroundInterruptions)
                .put("maxDispatchMs", maxDispatchMs)
                .put("displayedFrames", pipeline.displayedFrameCount)
                .put("directPreviewAnalysisFrames", pipeline.directPreviewFrameCount)
                .put("previewSwapFailures", pipeline.previewSwapFailureCount)
                .put("cameraRuntimeFailures", pipeline.runtimeFailureCount)
                .put("cameraBindsStarted", pipeline.bindStartedCount)
                .put("cameraBindsCompleted", pipeline.bindCompletedCount)
                .put("maxCameraBindMs", pipeline.maxBindDurationMs)
                .put("uiFrames", ui.frameCount)
                .put("jankyUiFrames", ui.jankyFrameCount)
                .put("maxUiFrameMs", ui.maxFrameMs)
                .put("reportedDroppedUiFrames", ui.droppedFrameCount))
            .put("resourcesAtFinish", JSONObject()
                .put("processCpuMs", resources.processCpuMs)
                .put("totalPssKb", resources.totalPssKb)
                .put("heapUsedKb", resources.heapUsedKb)
                .put("batteryTemperatureC", resources.batteryTemperatureC)
                .put("thermalStatus", resources.thermalStatus))
            .put("failures", JSONArray(failures))
            .put("recentCameraEvents", JSONArray(pipeline.recentEvents))
            .put("transitionsCsv", transitionsFile.absolutePath)
        withContext(Dispatchers.IO) { summaryFile.writeText(summary.toString(2)) }
    }

    private fun elapsedMs(startedNs: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private class WindowFrameStats(private val window: Window) {
        data class Snapshot(
            val frameCount: Long,
            val jankyFrameCount: Long,
            val droppedFrameCount: Long,
            val maxFrameMs: Double,
        )

        private val frames = AtomicLong()
        private val jankyFrames = AtomicLong()
        private val droppedFrames = AtomicLong()
        private val maxFrameNs = AtomicLong()
        private val thread = HandlerThread("CameraStressFrameMetrics")
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
            frames.incrementAndGet()
            if (duration >= JANK_NS) jankyFrames.incrementAndGet()
            if (dropped > 0) droppedFrames.addAndGet(dropped.toLong())
            maxFrameNs.getAndUpdate { old -> maxOf(old, duration) }
        }

        fun start() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            thread.start()
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }

        fun stop() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }

        fun snapshot(): Snapshot = Snapshot(
            frameCount = frames.get(),
            jankyFrameCount = jankyFrames.get(),
            droppedFrameCount = droppedFrames.get(),
            maxFrameMs = maxFrameNs.get() / 1_000_000.0,
        )

        companion object {
            // 30 fps preview budget. 32 ms is intentional: 16.7 ms would count nearly every
            // healthy 30 fps camera-driven recomposition as jank on this landscape surface.
            private const val JANK_NS = 32_000_000L
        }
    }

    companion object {
        const val EXTRA_ENABLED = "camera_stress_test"
        const val EXTRA_DWELL_MS = "camera_stress_dwell_ms"
        const val EXTRA_PREVIEW_TIMEOUT_MS = "camera_stress_preview_timeout_ms"
        const val EXTRA_REBIND_TIMEOUT_MS = "camera_stress_rebind_timeout_ms"
        const val EXTRA_MAX_RESPONSIVE_PREVIEW_MS = "camera_stress_max_responsive_preview_ms"
        const val EXTRA_RESTORE = "camera_stress_restore"
        const val EXTRA_EXHAUSTIVE_SLIDERS = "camera_stress_exhaustive_sliders"
        const val EXTRA_MODES = "camera_stress_modes"
        private const val TAG = "CameraStress"
        private const val CSV_HEADER =
            "sequence,epoch_ms,phase,mode,setting_id,setting_label,requested_value,actual_value," +
                "status,dispatch_ms,state_settle_ms,preview_latency_ms,preview_gap_ms," +
                "displayed_frame_count,last_displayed_binding_generation,last_source_frame_timestamp_ns," +
                "source_timestamp_stalls,binds_started_delta," +
                "binds_completed_delta,last_bind_ms,swap_failures," +
                "runtime_failures,ui_frame_count,janky_ui_frame_count,max_ui_frame_ms," +
                "reported_dropped_ui_frames,process_cpu_ms,total_pss_kb,heap_used_kb," +
                "battery_temperature_c,thermal_status,note"
    }
}
