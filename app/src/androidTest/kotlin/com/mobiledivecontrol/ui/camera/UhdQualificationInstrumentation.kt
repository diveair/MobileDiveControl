package com.mobiledivecontrol.ui.camera

import android.app.Activity
import android.app.Instrumentation
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * Drives the shipping "Test UHD capture rates" qualification for one lens, exactly as the user's
 * Options button does, and reports the verdict. A pass stores the per-firmware verification that
 * makes the rate selectable in Slow Motion.
 *
 * adb shell am instrument -w -e lens 0.6x -e camera 2 -e fps 120 \
 *   com.mobiledivecontrol.test/com.mobiledivecontrol.ui.camera.UhdQualificationInstrumentation
 */
class UhdQualificationInstrumentation : Instrumentation() {
    private var args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            val lens = args.getString("lens") ?: "0.6x"
            val cameraId = args.getString("camera") ?: if (lens == "0.6x") "2" else "0"
            val fps = args.getString("fps")?.toInt() ?: 120
            val expectedSensor = args.getString("expectedSensor") ?: when (lens) {
                "0.6x" -> cameraId
                "1x" -> "5"
                else -> null
            }
            val audio = args.getString("audio") != "false" &&
                targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val standard = UhdCaptureProfile.discover(targetContext, cameraId, null, lens, 1f, expectedSensor)
            val profiles = (standard + listOfNotNull(SamsungUhdSlowMotion.discover(targetContext, "0", expectedSensor, lens)))
                .filter { it.fps == fps }
            result.putString("profiles", profiles.joinToString { "camera=${it.cameraId} relay=${it.relay} samsung=${it.samsungSlowMotion} constrained=${it.constrained} codec=${it.codec}" })
            check(profiles.isNotEmpty()) { "No UHD $fps candidate on camera $cameraId" }
            if (profiles.any { it.verified(targetContext, audio) }) {
                result.putString("status", "Already verified")
                result.putBoolean("verified", true)
                finish(Activity.RESULT_OK, result)
                return
            }
            val started = startActivitySync(Intent(targetContext, UhdRateTestActivity::class.java)
                .putExtra("profiles", ArrayList(profiles)).putExtra("recordAudio", audio)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = started
            result.putString("status", measureUhdRate(started, fps))
            result.putBoolean("verified", profiles.any { it.verified(targetContext, audio) })
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString().take(3000))
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(if (result.getBoolean("verified")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

}

internal fun Instrumentation.measureUhdRate(activity: Activity, fps: Int): String {
    fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    runOnMainSync {
        val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Test $fps FPS" }
        check(button.performClick())
    }
    val deadline = SystemClock.elapsedRealtime() + 45_000
    while (SystemClock.elapsedRealtime() < deadline) {
        var status = ""
        runOnMainSync {
            status = views(activity.window.decorView).filterIsInstance<TextView>().first { it !is Button }.text.toString()
        }
        if (!status.startsWith("Measuring") && !status.startsWith("Test UHD")) return status
        SystemClock.sleep(250)
    }
    error("Timed out waiting for the UHD $fps FPS test")
}
