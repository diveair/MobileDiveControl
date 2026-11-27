package com.mobiledivecontrol.ui.tutorial

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.mobiledivecontrol.FirstLaunchSealStep
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.branding.NauticaseBranding
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.HousingButtonEvent
import com.mobiledivecontrol.theme.DiveControlTheme

/** Exercises the first-run UI with isolated state on a phone that has already completed setup. */
class FirstLaunchHousingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var activity: MainActivity? = null
        val report = Bundle()
        try {
            val screen = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val step = mutableStateOf(FirstLaunchSealStep.WaitingForDialogs)
            val link = mutableStateOf(BleConnectionState.Scanning)
            var dismissed = false
            var powerShownAt = 0L
            runOnMainSync {
                screen.setContent {
                    DiveControlTheme {
                        IntroCarouselScreen(
                            permissionsGranted = true, bleState = link.value, firstLaunchSealStep = step.value,
                            onDismiss = { dismissed = true },
                            onIntroPromptFinished = { shown ->
                                if (shown == step.value) {
                                    step.value = shown.afterPrompt(link.value)
                                    if (step.value == FirstLaunchSealStep.PowerOn) powerShownAt = SystemClock.uptimeMillis()
                                }
                            },
                        )
                    }
                }
            }
            waitForIdleSync()
            val started = SystemClock.uptimeMillis()
            runOnMainSync { step.value = step.value.permissionWindowChanged(true) }
            expectBanner(NauticaseBranding.WELCOME_PROMPT)
            val initial = capture()
            val front = backgroundSamples(initial)
            val insertBox = bannerPosition(initial)
            initial.recycle()
            tap(screen)
            expectBanner(NauticaseBranding.WELCOME_PROMPT)
            expectBanner(TURN_ON_HOUSING_PROMPT, timeout = 6_000L)
            val power = capture()
            check(front.contentEquals(backgroundSamples(power))) { "Housing artwork moved during the handoff" }
            check(insertBox == bannerPosition(power)) { "The two banners occupy different positions" }
            power.recycle()
            val elapsed = powerShownAt - started
            check(elapsed in 4_500L..5_500L) { "Insert duration was ${elapsed}ms" }

            tap(screen)
            SystemClock.sleep(5_000L)
            expectBanner(TURN_ON_HOUSING_PROMPT)
            fun radio(next: BleConnectionState) = runOnMainSync {
                link.value = next
                step.value = step.value.linkChanged(next)
            }
            radio(BleConnectionState.Connecting)
            expectBanner("CONNECTING TO HOUSING")
            radio(BleConnectionState.Subscribing)
            SystemClock.sleep(1_500L)
            expectBanner("CONNECTING TO HOUSING")
            val blue = connectionBannerBounds(connected = false)
            radio(BleConnectionState.Ready)
            expectBanner("CONNECTED TO HOUSING")
            val green = connectionBannerBounds(connected = true)
            check(blue == green) { "Connection banner changed size or position: blue=$blue green=$green" }
            report.putString("connectionBanner", "${blue.width()} x ${blue.height()} px for both banners")
            SystemClock.sleep(1_750L)
            runOnMainSync { check(step.value == FirstLaunchSealStep.Done && !dismissed) }
            // A housing already connected during permissions must still show the power instruction.
            runOnMainSync { step.value = FirstLaunchSealStep.OnHousingAnimation }
            expectBanner(NauticaseBranding.WELCOME_PROMPT)
            runOnMainSync { step.value = step.value.onButton(HousingButtonEvent.Ok) }
            expectBanner(TURN_ON_HOUSING_PROMPT)
            runOnMainSync { step.value = step.value.onButton(HousingButtonEvent.Shutter) }
            expectBanner("CONNECTING TO HOUSING")
            expectBanner("CONNECTED TO HOUSING")
            report.putString("checks", "Insert ${elapsed}ms, taps ignored, fixed artwork and banner position, waits for housing, blue then green then tutorial, MENU/OK and SHUTTER handoff on an already-ready link")
        } catch (error: Throwable) {
            report.putString("failure", android.util.Log.getStackTraceString(error))
        } finally {
            runOnMainSync { activity?.finish() }
            finish(if (report.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, report)
        }
    }

    private fun expectBanner(text: String, timeout: Long = 2_500L) {
        fun contains(node: AccessibilityNodeInfo?): Boolean = node != null &&
            (node.contentDescription?.toString() == text || (0 until node.childCount).any { contains(node.getChild(it)) })
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (contains(uiAutomation.rootInActiveWindow)) return
            SystemClock.sleep(40L)
        }
        error("Expected banner: $text")
    }

    private fun tap(screen: Activity) = runOnMainSync {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, 200f, 200f, 0)
            try { screen.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun capture(): Bitmap = checkNotNull(uiAutomation.takeScreenshot()).let { shot ->
        shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() }
    }

    private fun connectionBannerBounds(connected: Boolean): Rect {
        val frame = capture()
        try {
            // The neutral housing drawing is monochrome. Probe across its port for the status colour.
            fun fill(x: Int, y: Int): Boolean {
                val pixel = frame.getPixel(x, y)
                return if (connected) Color.green(pixel) - Color.red(pixel) > 55
                else Color.blue(pixel) - Color.green(pixel) > 35
            }
            val middle = frame.height / 2
            val columns = (0 until frame.width).filter { fill(it, middle) }
            val left = columns.first()
            val right = columns.last() + 1
            val probe = left + (right - left) / 6
            val rows = (frame.height / 4 until frame.height * 3 / 4).filter { fill(probe, it) }
            return Rect(left, rows.first(), right, rows.last() + 1)
        } finally {
            frame.recycle()
        }
    }

    private fun backgroundSamples(frame: Bitmap): IntArray = buildList {
        for (y in frame.height / 8 until frame.height * 7 / 8 step 17) {
            if (y in frame.height * 3 / 8..frame.height * 5 / 8) continue
            for (x in frame.width / 8 until frame.width * 7 / 8 step 17) add(frame.getPixel(x, y))
        }
    }.toIntArray()

    /** The two amber edges at mid-height locate the painted banner independently of text length. */
    private fun bannerPosition(frame: Bitmap): Pair<Int, Int> {
        val row = frame.height / 2
        val amber = (0 until frame.width).filter { x ->
            val color = frame.getPixel(x, row)
            android.graphics.Color.red(color) > 100 && android.graphics.Color.green(color) > 65 &&
                android.graphics.Color.blue(color) < 45
        }
        return amber.first() to amber.last()
    }
}
