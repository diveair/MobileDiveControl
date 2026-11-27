package com.mobiledivecontrol.ui.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.AppMode
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraCommand
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.RecordingSaveLocation
import com.mobiledivecontrol.viewmodel.CameraSessionStore
import com.mobiledivecontrol.viewmodel.DiveViewModel
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Supply the public-domain Grace Hopper portrait (Matplotlib sample_data) via -e fixture filename. */
class PortraitPhotoInstrumentation : Instrumentation() {
    private var fixture: String? = null
    private var verifySamsung = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixture = arguments?.getString("fixture")
        verifySamsung = arguments?.getString("verifySamsung") == "true"
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            checkSession(report)
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = targetContext.getSystemService(CameraManager::class.java)
                val lenses = manager.cameraIdList.filter {
                    CameraExtensionCharacteristics.EXTENSION_BOKEH in manager.getCameraExtensionCharacteristics(it).supportedExtensions
                }
                report.putString("publicBokehCameraIds", lenses.joinToString())
            }
            fixture?.let { checkPortrait(File(targetContext.getExternalFilesDir(null), it), report) }
            checkCapture(report)
            if (Build.VERSION.SDK_INT >= 31) checkVendorReference(report)
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("failure", android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, report)
        }
    }

    private fun checkSession(report: Bundle) {
        val prefName = "portrait-verification-" + System.nanoTime()
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = targetContext.getSharedPreferences(prefName, mode)
        }
        try {
            val store = CameraSessionStore(isolated)
            val fresh = store.restoreAppState()
            CameraCatalog.centerModeCycle.forEach { mode ->
                val camera = fresh.camera.copy(activeMode = mode)
                val ratio = CameraCatalog.settingsFor(camera).single { it.id.endsWith(".aspect_ratio") }
                check(CameraCatalog.currentValue(camera, ratio) == "Full") { mode.name }
            }
            check(fresh.camera.settingValues["portrait.effect_strength"] == "5")
            val effectKeys = setOf("filters", "effect_strength", "background_effect", "radial_blur",
                "color_temperature", "hdr", "log", "hdr_log")
            val modeEffects = CameraCatalog.centerModeCycle.flatMap { mode ->
                CameraCatalog.settingsFor(fresh.camera.copy(activeMode = mode))
                    .filter { it.id.substringAfterLast('.') in effectKeys && it.options.size > 1 }
                    .map { it.id to it.options.last() }
            }.toMap()
            val edits = modeEffects + mapOf("photo.aspect_ratio" to "1:1", "portrait.aspect_ratio" to "4:3",
                "portrait.background_effect" to "Studio", "portrait.effect_strength" to "2",
                "portrait_video.background_effect" to "Glitch", "portrait_video.effect_strength" to "7")
            store.save(fresh.copy(camera = fresh.camera.copy(activeMode = CameraModeId.PortraitVideo,
                settingValues = fresh.camera.settingValues + edits)))
            val restored = CameraSessionStore(isolated).restoreAppState().camera
            check(restored.activeMode == CameraModeId.PortraitVideo)
            edits.forEach { (key, value) -> check(restored.settingValues[key] == value) { key } }
            report.putInt("fullOnFirstLaunch", CameraCatalog.centerModeCycle.size)
            report.putInt("restoredSeparateSettings", edits.size)
        } finally { targetContext.deleteSharedPreferences(prefName) }
    }

    private fun checkPortrait(file: File, report: Bundle) {
        val source = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            PortraitPhotoRenderer(targetContext).use { renderer ->
                check(renderer.layer(source, 0, PortraitPhotoLook("Blur", 0), 600) == null)
                val solid = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                try {
                    solid.eraseColor(Color.GRAY)
                    check(renderer.layer(solid, 0, PortraitPhotoLook("Blur", 7), 600) == null)
                } finally { solid.recycle() }
                fun backgroundContrast(image: Bitmap): Double {
                    var sum = 0.0
                    for (y in 30 until 200) for (x in 25 until 115) {
                        val a = image.getPixel(x,y)
                        val b = image.getPixel(x+1,y)
                        sum += abs(Color.green(a) - Color.green(b))
                    }
                    return sum
                }
                val names = listOf("Blur","Studio","Color point","Backdrop","High-key mono","Low-key mono")
                val samples = mutableSetOf<List<Int>>()
                for (name in names) {
                    val layer = checkNotNull(renderer.layer(source, 0, PortraitPhotoLook(name, 7), 600))
                    try {
                        check(Color.alpha(layer.getPixel(256, 220)) < 12) { "$name blurred the face" }
                        check(Color.alpha(layer.getPixel(60, 90)) > 230) { "$name missed the flag" }
                        if (name == "Blur") {
                            val contrast = backgroundContrast(layer) / backgroundContrast(source)
                            report.putDouble("blurredToOriginalFlagDetail", contrast)
                            check(contrast < 0.55) { "Background retained too much fine detail: $contrast" }
                        }
                        samples.add(listOf(layer.getPixel(40,80), layer.getPixel(80,170)))
                    } finally { layer.recycle() }
                }
                // Studio shares Blur's background but also applies a foreground lighting grade.
                check(samples.size >= 5 && PortraitPhotoLook("Studio", 7).gain > 1f)
                val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(-90f) }, false)
                try {
                    val layer = checkNotNull(renderer.layer(rotated, 90, PortraitPhotoLook("Blur", 4), 600))
                    try {
                        check(Color.alpha(layer.getPixel(220, source.width-1-256)) < 12)
                        check(Color.alpha(layer.getPixel(90, source.width-1-60)) > 230)
                    } finally { layer.recycle() }
                } finally { rotated.recycle() }
                report.putInt("portraitLooksChecked", names.size)
            }
            verifyMovingPortrait(targetContext, source, report)
        } finally { source.recycle() }
    }

    private fun checkCapture(report: Bundle) {
        fun launch() = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val activity = launch()
        lateinit var vm: DiveViewModel
        runOnMainSync { vm = ViewModelProvider(activity)[DiveViewModel::class.java] }
        val original = vm.state.value
        val album = RecordingSaveLocation("Portrait verification", "DCIM/Nauticase-test-${System.nanoTime()}/")
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = targetContext.contentResolver
        fun findShot() = resolver.query(collection, arrayOf("_id"), "relative_path=? AND owner_package_name=?",
            arrayOf(album.relativePath, targetContext.packageName), null)?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
        try {
            runOnMainSync {
                vm.dismissIntro()
                val settings = original.camera.settingValues + mapOf("portrait.effect_strength" to "4",
                    "portrait.background_effect" to "Blur", "portrait.timer" to "Off", "portrait.lens" to "1x",
                    "portrait.aspect_ratio" to "Full")
                vm.restoreCameraStressSnapshot(original.camera.copy(activeMode = CameraModeId.Portrait,
                    recordingSaveLocation = album, settingValues = settings), AppMode.CameraLive)
            }
            val before = CameraPipelineTelemetry.snapshot()
            await("live portrait preview") { CameraPipelineTelemetry.snapshot().let {
                it.displayedFrameCount >= before.displayedFrameCount + 15 &&
                    it.lastBindSignature.startsWith("mode=Portrait ")
            } }
            val timingStart = CameraPipelineTelemetry.snapshot()
            if (verifySamsung) {
                val gaps = mutableListOf<Double>()
                var previous = timingStart.lastSourceFrameTimestampNs
                val deadline = SystemClock.elapsedRealtime() + 3200
                while (SystemClock.elapsedRealtime() < deadline) {
                    val timestamp = CameraPipelineTelemetry.snapshot().lastSourceFrameTimestampNs
                    if (timestamp > previous && previous > 0L) gaps.add((timestamp-previous)/1_000_000.0)
                    previous = timestamp
                    SystemClock.sleep(5)
                }
                check(gaps.size >= 40) { "Too few Samsung preview frames: ${gaps.size}" }
                val sorted = gaps.sorted()
                report.putDouble("samsungPreviewMedianMs",sorted[sorted.size/2])
                report.putDouble("samsungPreviewP95Ms",sorted[(sorted.size*0.95).toInt()])
                report.putDouble("samsungPreviewMaxMs",sorted.last())
            } else SystemClock.sleep(1600)
            val timingEnd = CameraPipelineTelemetry.snapshot()
            report.putDouble("realCameraPreviewFps",1000.0*(timingEnd.displayedFrameCount-timingStart.displayedFrameCount)/
                (timingEnd.capturedAtMs-timingStart.capturedAtMs))
            if (verifySamsung) {
                check(timingEnd.recentEvents.any { it.contains("PORTRAIT Samsung BOKEH lens=1x") }) {
                    "Nauticase did not bind the Samsung portrait processor: ${timingEnd.recentEvents}"
                }
                runOnMainSync { vm.dispatch(CameraCommand.NudgeSetting("portrait.effect_strength", 1)) }
                await("Samsung strength 5 without a rebind") {
                    CameraPipelineTelemetry.snapshot().recentEvents.any { "PORTRAIT Samsung strength=64" in it }
                }
                check(CameraPipelineTelemetry.snapshot().bindCompletedCount == timingEnd.bindCompletedCount)
                report.putString("appPortraitBackend","Samsung BOKEH, level 5")
            }
            runOnMainSync { vm.dispatch(CameraCommand.CapturePhoto) }
            await("saved portrait JPEG") { findShot() != null }
            val uri = checkNotNull(findShot())
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            check(opts.outWidth > 0 && opts.outHeight > 0 && opts.outMimeType == "image/jpeg")
            report.putString("liveCapture", "${opts.outWidth}x${opts.outHeight}")
            check(CameraPipelineTelemetry.snapshot().runtimeFailureCount == before.runtimeFailureCount)
            if (verifySamsung) {
                val lensRoutes = mutableListOf<String>()
                val state = vm.state.value.camera
                val lenses = CameraCatalog.settingsFor(state).single { it.id == "portrait.lens" }
                for (lens in listOf("0.6x", "3x", "front", "1x").filter { it in lenses.options }) {
                    val previous = CameraPipelineTelemetry.snapshot()
                    runOnMainSync {
                        val value = CameraCatalog.currentValue(vm.state.value.camera, lenses)
                        vm.dispatch(CameraCommand.NudgeSetting(lenses.id, lenses.options.indexOf(lens)-lenses.options.indexOf(value)))
                    }
                    await("portrait lens $lens") {
                        val latest = CameraPipelineTelemetry.snapshot()
                        latest.bindCompletedCount > previous.bindCompletedCount &&
                            latest.lastDisplayedBindingGeneration != previous.lastDisplayedBindingGeneration &&
                            latest.recentEvents.lastOrNull { "PORTRAIT " in it && "lens=" in it }?.contains("lens=$lens") == true
                    }
                    lensRoutes += CameraPipelineTelemetry.snapshot().recentEvents.last { "PORTRAIT " in it && "lens=" in it }.substringAfter("PORTRAIT ")
                }
                report.putString("portraitLensRoutes",lensRoutes.joinToString("; "))
                val previous = CameraPipelineTelemetry.snapshot()
                runOnMainSync { vm.dispatch(CameraCommand.NudgeSetting("portrait.effect_strength",-5)) }
                await("unblurred preview at zero") {
                    val current = CameraPipelineTelemetry.snapshot()
                    current.lastDisplayedBindingGeneration != previous.lastDisplayedBindingGeneration &&
                        current.recentEvents.lastOrNull { "PORTRAIT " in it && "lens=" in it }?.contains("Nauticase") == true
                }
            }
            runOnMainSync {
                val state = vm.state.value.camera
                for ((id, wanted) in mapOf("portrait.effect_strength" to "2", "portrait.background_effect" to "Color point")) {
                    val spec = CameraCatalog.settingsFor(state).single { it.id == id }
                    val delta = spec.options.indexOf(wanted) - spec.options.indexOf(CameraCatalog.currentValue(state, spec))
                    vm.dispatch(CameraCommand.NudgeSetting(id, delta))
                }
                activity.finish()
            }
            await("activity close") { activity.isDestroyed }
            val reopened = launch()
            activeScreen = reopened
            runOnMainSync { vm = ViewModelProvider(reopened)[DiveViewModel::class.java] }
            val reopenedCamera = vm.state.value.camera
            check(reopenedCamera.activeMode == CameraModeId.Portrait &&
                reopenedCamera.settingValues["portrait.effect_strength"] == "2" &&
                reopenedCamera.settingValues["portrait.background_effect"] == "Color point")
            report.putString("activityRelaunch", "Color point, strength 2")
        } finally {
            findShot()?.let { resolver.delete(it, null, null) }
            runOnMainSync { vm.restoreCameraStressSnapshot(original.camera, original.mode); vm.flushCameraSettings() }
        }
    }

    /** The manufacturer processes a real exposure through its supported public BOKEH extension. */
    private fun checkVendorReference(report: Bundle) {
        val provider = ProcessCameraProvider.getInstance(targetContext).get(15, TimeUnit.SECONDS)
        val extensions = ExtensionsManager.getInstanceAsync(targetContext, provider).get(15, TimeUnit.SECONDS)
        val lens = CameraSelector.DEFAULT_BACK_CAMERA
        if (!extensions.isExtensionAvailable(lens, ExtensionMode.BOKEH)) return
        val reference = activeScreen ?: error("Missing the reopened Camera activity")
        val preview = Preview.Builder().build()
        val still = ImageCapture.Builder().build()
        try {
            lateinit var view: PreviewView
            runOnMainSync {
                view = PreviewView(reference)
                reference.setContentView(view)
            }
            waitForIdleSync()
            runOnMainSync {
                provider.unbindAll()
                preview.setSurfaceProvider(view.surfaceProvider)
                val selector = extensions.getExtensionEnabledCameraSelector(lens, ExtensionMode.BOKEH)
                val camera = provider.bindToLifecycle(reference, selector, preview, still)
                val adjustable = extensions.getCameraExtensionsInfo(camera.cameraInfo).isExtensionStrengthAvailable
                report.putBoolean("vendorStrengthAdjustable", adjustable)
                if (adjustable) extensions.getCameraExtensionsControl(camera.cameraControl)?.setExtensionStrength(100)
            }
            await("vendor bokeh preview") { view.previewStreamState.value == PreviewView.StreamState.STREAMING }
            val result = CompletableFuture<String>()
            runOnMainSync {
                still.takePicture(ContextCompat.getMainExecutor(targetContext), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try { result.complete("${image.width}x${image.height}") } finally { image.close() }
                    }
                    override fun onError(exception: ImageCaptureException) { result.completeExceptionally(exception) }
                })
            }
            report.putString("vendorBokehReference", result.get(30, TimeUnit.SECONDS))
        } finally {
            runOnMainSync { provider.unbind(preview, still); reference.finish() }
        }
    }

    private var activeScreen: MainActivity? = null

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (!ready()) {
            check(SystemClock.elapsedRealtime() < deadline) { "$label timed out: ${CameraPipelineTelemetry.snapshot()}" }
            SystemClock.sleep(100)
        }
    }
}
