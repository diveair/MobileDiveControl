package com.mobiledivecontrol.ui.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.CameraState
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.util.Locale

/** Exercises the production HUD and preview without taking photos or starting a recording. */
class CompassPreviewInstrumentation : Instrumentation() {
    private var modes = emptyList<CameraModeId>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        modes = arguments?.getString("modes")?.split(',')?.map(CameraModeId::valueOf).orEmpty()
        start()
    }

    override fun onStart() {
        val results = Bundle()
        val run = runCatching {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm: DiveViewModel
            runOnMainSync { vm = ViewModelProvider(activity)[DiveViewModel::class.java] }
            val original = vm.state.value
            check(!original.housing.connected && !original.camera.recording && !vm.introVisible.value)
            val camera = original.camera
            val power = targetContext.getSystemService(PowerManager::class.java)
            fun route(mode: AppMode, state: CameraState = camera) = runOnMainSync {
                vm.restoreCameraStressSnapshot(state, mode)
            }
            fun verifyHeading(label: String, visible: Boolean) {
                await(label) { (vm.compassReading.value.headingDegrees != null) == visible }
                SystemClock.sleep(350)
                check((vm.compassReading.value.headingDegrees != null) == visible) { label }
                results.putString(label, if (visible) "live" else "paused")
            }
            try {
                runOnMainSync { vm.finishWelcome() }
                route(AppMode.CameraLive)
                verifyHeading("CameraLive", true)
                runOnMainSync { vm.previewWelcome() }
                verifyHeading("Welcome", false)
                runOnMainSync { vm.finishWelcome() }
                verifyHeading("AfterWelcome", true)
                for (destination in listOf(AppMode.Gallery, AppMode.CameraAdjust, AppMode.Diagnostics,
                        AppMode.Safety, AppMode.DiveSettings, AppMode.PhoneCursor, AppMode.PhoneTarget)) {
                    route(destination)
                    verifyHeading(destination.name, destination in listOf(AppMode.CameraAdjust, AppMode.Safety,
                        AppMode.PhoneCursor, AppMode.PhoneTarget))
                    if (destination == AppMode.Gallery || destination == AppMode.Diagnostics) {
                        SystemClock.sleep(1600)
                        val cpu = Process.getElapsedCpuTime()
                        val start = SystemClock.elapsedRealtime()
                        SystemClock.sleep(3800)
                        results.putString(destination.name + " CPU", percent(cpu, start))
                    }
                }
                route(AppMode.CameraLive, camera.copy(recordingLocationChooserVisible = true))
                verifyHeading("Save location", false)
                val warning = vm.state.value.lastWarning
                runOnMainSync { vm.setTargetHeadingFromPoint(PointingGesture(0.5, 60.0, 1f)) }
                waitForIdleSync()
                check(vm.state.value.lastWarning == warning) { "Late pointing gesture while heading is hidden" }
                route(AppMode.CameraLive)
                verifyHeading("After chooser", true)
                route(AppMode.CameraLive, camera.copy(panoramaReviewAvailable = true))
                verifyHeading("Panorama review", false)
                route(AppMode.CameraLive)
                verifyHeading("After review", true)
                runOnMainSync { activity.moveTaskToBack(true) }
                verifyHeading("Background", false)
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                verifyHeading("Foreground again", true)

                for (mode in modes) {
                    if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                        route(AppMode.Gallery)
                        await("Cooldown", 180000) { power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE }
                    }
                    check(!vm.state.value.housing.connected)
                    val failures = CameraPipelineTelemetry.snapshot().runtimeFailureCount
                    route(AppMode.CameraLive, camera.copy(activeMode = mode))
                    SystemClock.sleep(3700)
                    val before = CameraPipelineTelemetry.snapshot()
                    val start = SystemClock.elapsedRealtime()
                    val cpu = Process.getElapsedCpuTime()
                    SystemClock.sleep(6200)
                    val after = CameraPipelineTelemetry.snapshot()
                    val frames = maxOf(after.displayedFrameCount - before.displayedFrameCount,
                        after.directPreviewFrameCount - before.directPreviewFrameCount)
                    val fps = 1000.0 * frames / (SystemClock.elapsedRealtime() - start)
                    check(vm.state.value.camera.activeMode == mode && fps > 10 &&
                        after.runtimeFailureCount == failures) { "$mode: $fps fps; ${after.recentEvents.takeLast(6)}" }
                    val result = String.format(Locale.US, "%.1f fps, %s app CPU", fps, percent(cpu, start))
                    results.putString(mode.name, result)
                    sendStatus(2, Bundle().apply { putString("verified", "$mode $result") })
                }
            } finally {
                runOnMainSync {
                    vm.restoreCameraStressSnapshot(camera, original.mode)
                    vm.flushCameraSettings()
                    activity.finish()
                }
                waitForIdleSync()
                // Instrumentation can terminate the process before SharedPreferences.apply reaches disk.
                val preferences = targetContext.getSharedPreferences("camera_session", android.content.Context.MODE_PRIVATE)
                check(preferences.edit().commit())
                check(preferences.getString("active_mode", null) == camera.activeMode.name)
            }
        }
        run.exceptionOrNull()?.let { results.putString("failure", it.stackTraceToString()) }
        finish(if (run.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun percent(cpu: Long, start: Long): String = String.format(Locale.US, "%.1f%%",
        100.0 * (Process.getElapsedCpuTime() - cpu) / (SystemClock.elapsedRealtime() - start))

    private fun await(label: String, timeoutMs: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        check(condition()) { "Timed out waiting for $label" }
    }
}
