package com.mobiledivecontrol.ui.camera

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.descendants
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.platform.CompassReading
import com.mobiledivecontrol.platform.HousingSealStore
import com.mobiledivecontrol.theme.DiveControlTheme
import com.mobiledivecontrol.ui.DiveControlScreen
import com.mobiledivecontrol.ui.components.LocalSealBannerObserver
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** Exercises the production camera menus and displayed-banner feedback without a housing transport. */
class VacuumMenuInstrumentation : Instrumentation() {
    private var saveInspectionBanners = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        saveInspectionBanners = arguments?.getString("saveInspectionBanners") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            val isolatedStandbyRun = targetContext.packageName == "com.mobiledivecontrol.standbytest"
            if (isolatedStandbyRun) {
                checkInspectionHistory()
                com.mobiledivecontrol.FirstRunOnboardingStore(targetContext).markComplete()
                targetContext.packageManager.setComponentEnabledSetting(android.content.ComponentName(targetContext,
                    com.mobiledivecontrol.service.HousingLinkService::class.java),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            }
            val intent = Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val screen = startActivitySync(intent) as MainActivity
            activity = screen
            if (isolatedStandbyRun) {
                checkStandby(screen)
                checkMenuShutOff(screen)
                result.putString("standby", "Vacuum Released > Shut Down showed the instruction until the next MENU/OK press, went black after three seconds, and woke on reconnect.")
                result.putString("menuShutOff", "One MENU/OK packet cleared the instruction after three seconds with the link still up. Reconnecting restored the camera.")
            }
            var now = pressureMonotonicMs()
            var displayed: Banner? = null
            val core = ControlCore(monotonicMs = { now }, reducer = ControlReducer(nowMs = { now }),
                displayedSealBanner = { displayed })
            val compass = MutableStateFlow(CompassReading())
            core.advanceBle(BleSignal.Ready)
            core.updateSensor(SensorUpdate.CoverState(true))
            repeat(2) {
                now += 500
                core.updateSensor(SensorUpdate.WaterPressure(101.3))
                core.updateSensor(SensorUpdate.BarometricPressure(101.3))
            }
            check(core.state.safety.sealMonitor.banner == Banner.B03) { "Unexpected initial seal state: ${core.state.safety}" }
            val rendered = mutableStateOf(AppState(bleConnectionState = BleConnectionState.Scanning))
            fun publish(outcome: ProcessingOutcome) {
                check(outcome.effects.isEmpty()) { "Unexpected effects: ${outcome.effects}" }
                rendered.value = outcome.state
            }
            runOnMainSync {
                screen.setContent {
                    DiveControlTheme {
                        CompositionLocalProvider(LocalSealBannerObserver provides { displayed = it }) {
                            DiveControlScreen(state = rendered.value,
                                compassReadings = compass, onCompassMonitoring = {},
                                onSafetyCommand = { publish(core.dispatch(it)) },
                                onCameraCommand = { publish(core.dispatch(it)) })
                        }
                    }
                }
            }

            fun press(code: Int) = runOnMainSync {
                now += 150
                publish(core.handleButtonPayload(byteArrayOf(code.toByte()), receivedAt = Instant.ofEpochMilli(now)))
            }
            val dismissHint = "Press MENU/OK to dismiss."
            fun checkSetupText(body: String) {
                // Wait for the banner-only instruction before locating the title shared with the outgoing Modes menu.
                val instruction = android.graphics.Rect().also { findText(body).getBoundsInScreen(it) }
                val hint = android.graphics.Rect().also { findText(dismissHint).getBoundsInScreen(it) }
                val title = Rect().also { findText("ESTABLISH VACUUM").getBoundsInScreen(it) }
                val window = Rect().also { uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                check((title.top + hint.bottom) / 2 > window.centerY())
                check(instruction.top >= title.bottom && hint.top - instruction.bottom in -1..1 && hint.height() < instruction.height())
                result.putString(body, "Text top edges: title=${title.top}, instruction=${instruction.top}, dismiss=${hint.top}")
            }
            fun bannerVisible() {
                runOnMainSync {
                    now = pressureMonotonicMs()
                    repeat(2) {
                        core.updateSensor(SensorUpdate.WaterPressure(101.3))
                        rendered.value = core.updateSensor(SensorUpdate.BarometricPressure(101.3)).state
                    }
                }
                val until = SystemClock.uptimeMillis() + 8_000
                while (SystemClock.uptimeMillis() < until) {
                    var showing = false
                    runOnMainSync { showing = displayed == Banner.B03 }
                    if (showing) break
                    SystemClock.sleep(50)
                }
                checkSetupText("Press DOWN to start vacuum pump.")
                runOnMainSync { check(displayed == Banner.B03) }
            }
            findText("HOUSING NOT CONNECTED")
            findText("Hold SHUTTER to turn on the housing and connect Bluetooth")
            val reference = bannerBounds(Color.rgb(255, 179, 0))
            result.putString("reference", "HOUSING NOT CONNECTED occupied $reference.")
            runOnMainSync { rendered.value = core.state }
            bannerVisible()
            val setupBand = bannerBounds(Color.rgb(255, 179, 0))
            check(setupBand == reference) { "Establish Vacuum $setupBand vs disconnected $reference" }
            runOnMainSync {
                now = pressureMonotonicMs()
                rendered.value = core.updateSensor(SensorUpdate.WaterPressure(103.3)).state
            }
            checkSetupText("Return to surface, then press DOWN to start vacuum pump.")
            check(bannerBounds(Color.rgb(255, 179, 0)) == setupBand)
            press(0x61) // publish rejects any hardware effects.
            bannerVisible()
            for (confirm in listOf(0x50, 0x20, null)) {
                press(0x50)
                findText("Vacuum")
                findText("DO NOT DIVE")
                findText("Use Menu to Establish Seal")
                if (confirm == null) clickText("Vacuum") else press(0x50)
                val vacuum = android.graphics.Rect().also { findText("ESTABLISH VACUUM").getBoundsInScreen(it) }
                val heading = android.graphics.Rect().also { findText("Track Heading").getBoundsInScreen(it) }
                val shutOff = android.graphics.Rect().also { findText("Shut Off").getBoundsInScreen(it) }
                check(vacuum.bottom <= shutOff.top && shutOff.bottom <= heading.top)
                checkChipColour(vacuum, yellow = true)
                runOnMainSync {
                    check(core.state.camera.primaryHighlightedEntry.action == CameraRailAction.EstablishVacuum)
                    check(displayed == null)
                }
                if (confirm == null) clickText("ESTABLISH VACUUM") else press(confirm)
                bannerVisible()
            }
            val setupCenter = setupBand.centerY()
            val holdStarted = System.currentTimeMillis()
            val holding = core.state.safety.copy(sealState = SealState.LeakMonitoring,
                barometricPressureKpa = 80.5, leakMonitoringStartedAtEpochMs = holdStarted,
                sealMonitor = core.state.safety.sealMonitor.copy(verification = Verification.Testing,
                    banner = Banner.B16, holdSinceMs = pressureMonotonicMs()))
            runOnMainSync { rendered.value = core.state.copy(safety = holding) }
            val holdCenter = bannerCenterY("VACUUM LEAK TEST", "Checking seal hold. Keep housing out of water.")
            check(kotlin.math.abs(holdCenter - setupCenter) <= 4) { "Hold center $holdCenter, setup center $setupCenter" }
            val compactFirst = android.graphics.Rect().also { findText("LEAK TEST").getBoundsInScreen(it) }
            checkChipColour(compactFirst, yellow = false)
            runOnMainSync {
                rendered.value = core.state.copy(safety = holding.copy(leakMonitoringStartedAtEpochMs = holdStarted - 180_000L,
                    sealState = SealState.Passed, sealConfidence = SealConfidence.Provisional))
            }
            val compactSecond = android.graphics.Rect().also { findText("LEAK TEST").getBoundsInScreen(it) }
            check(compactFirst == compactSecond && compactFirst.centerY() < holdCenter)
            checkChipColour(compactSecond, yellow = true)
            runOnMainSync {
                rendered.value = core.state.copy(safety = holding.copy(sealState = SealState.Passed,
                    sealConfidence = SealConfidence.ManufacturerMinimum,
                    sealMonitor = holding.sealMonitor.copy(banner = Banner.B18, verification = Verification.Passed)))
            }
            findText("LEAK TEST PASSED")
            result.putString("hold", "Centered introduction, compact LEAK TEST across both countdowns, verified-pass verdict.")
            result.putString("verified", "Center Vacuum, default Modes selection, MENU/OK, SHUTTER and touch reopen the visible prompt.")
            if (isolatedStandbyRun) {
                // This independent core drives the real HUD. No effects go to a transport.
                runOnMainSync { rendered.value = core.state }
                bannerVisible()
                fun baro(kpa: Double) {
                    now += 500
                    core.updateSensor(SensorUpdate.WaterPressure(100.79))
                    core.updateSensor(SensorUpdate.BarometricPressure(kpa))
                }
                val start = core.handleButtonPayload(byteArrayOf(0x61), receivedAt = Instant.ofEpochMilli(now + 120))
                check(PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(true)) in start.effects)
                repeat(222) { baro(79.128) }
                check(core.state.safety.sealConfidence == SealConfidence.Monitoring)
                runOnMainSync { rendered.value = core.state }
                findText("VACUUM REACHED")
                val reachedBand = bannerBounds(Color.rgb(0, 229, 255))
                check(reachedBand == setupBand) { "Reached $reachedBand, setup $setupBand" }
                result.putString("bannerSize", "Setup at both depths and Vacuum Reached occupied $reachedBand.")
                press(0x50)
                for (pressure in listOf(88.5, 92.974, 97.9, 100.792, 100.800)) {
                    baro(pressure)
                    check(core.state.safety.sealMonitor.banner in setOf(Banner.B00, Banner.ConfirmRelease))
                    runOnMainSync { rendered.value = core.state }
                    waitForIdleSync()
                }
                findText("DID YOU RELEASE THE VACUUM?")
                checkReleaseBand(setupBand)
                result.putString("releaseQuestionText", checkChoiceLayout("DID YOU RELEASE THE VACUUM?", "NO", "YES"))
                runOnMainSync {
                    val safety = core.state.safety
                    rendered.value = core.state.copy(safety = safety.copy(sealMonitor = safety.sealMonitor.copy(banner = Banner.Release)))
                }
                findText("VACUUM RELEASED")
                checkReleaseBand(reference)
                result.putString("releasedText", checkChoiceLayout("VACUUM RELEASED", "SHUT DOWN", "VACUUM SETUP"))
                runOnMainSync { result.putString("releasedLabelSize", checkReleasedLabelSize(screen)) }
                runOnMainSync { rendered.value = core.state }
                findText("DID YOU RELEASE THE VACUUM?")
                result.putString("releaseSize", "Release question and Vacuum Released both matched $reference.")
                clickText("MENU/OK CONFIRMS")
                var inspectionHeading: Rect? = null
                fun checkInspection(detail: String, scenario: String) {
                    val labels = listOf("HOUSING SEAL CHECK REQUIRED", detail, "then press MENU/OK to confirm")
                    val boxes = labels.map { label -> Rect().also { findText(label).getBoundsInScreen(it) } }
                    val (title, instruction, hint) = boxes
                    check(boxes.all { reference.contains(it) } && title.bottom <= instruction.top && instruction.bottom <= hint.top) { "$scenario: $boxes" }
                    check(title.top > reference.top && title.height() >= reference.height() / 3)
                    check(inspectionHeading == null || inspectionHeading == title)
                    inspectionHeading = title
                    checkBannerEdges(reference) { pixel ->
                        Color.red(pixel) >= 233 && Color.green(pixel) in 157..201 && Color.blue(pixel) <= 22
                    }
                    var overflow = emptyList<String>()
                    runOnMainSync {
                        overflow = textLayouts(screen, labels.toSet()).values.filter { layout ->
                            // A centered, wrap-content hint can report its paragraph as wider than its text box.
                            // Check the visible characters and the width of the actual lines.
                            layout.lineCount != 1 || layout.didOverflowHeight || layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) != layout.layoutInput.text.length ||
                                (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) ||
                                    layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1 }
                        }.map {
                            "${it.layoutInput.text} size=${it.size} lines=${it.lineCount} " +
                                "visibleEnd=${it.getLineEnd(it.lineCount - 1, visibleEnd = true)} " +
                                "inkWidth=${it.getLineRight(0) - it.getLineLeft(0)} heightOverflow=${it.didOverflowHeight} " +
                                "ellipsis=${it.isLineEllipsized(0)} font=${it.layoutInput.style.fontSize}"
                        }
                    }
                    result.putString("inspection-$scenario", "Single-line title $title, instruction $instruction, hint $hint")
                    if (saveInspectionBanners) {
                        val frame = checkNotNull(uiAutomation.takeScreenshot())
                        val crop = android.graphics.Bitmap.createBitmap(frame, reference.left, reference.top, reference.width(), reference.height())
                        try {
                            java.io.File(targetContext.cacheDir, "inspection-$scenario.png").outputStream().use {
                                check(crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { crop.recycle(); frame.recycle() }
                    }
                    check(overflow.isEmpty()) { "Inspection layout overflow: $overflow" }
                }
                checkInspection("INSPECT O-RING INTEGRITY, CHECK BLUE CAP & SILVER AIR DRAIN VALVE FOR LEAKS", "surface")
                press(0x61)
                check(!core.state.safety.sealMonitor.inspected)
                press(0x50)
                findText("ESTABLISH VACUUM")
                fun depth(water: Double) = runOnMainSync {
                    now += 500
                    publish(core.updateSensor(SensorUpdate.WaterPressure(water)))
                    publish(core.updateSensor(SensorUpdate.BarometricPressure(100.8)))
                }
                depth(140.0)
                findText("WARNING — VACUUM NOT ESTABLISHED")
                press(0x50)
                checkInspection("SURFACE SAFELY, REMEDY ANY WATER INGRESS, INSPECT O-RING, BLUE CAP & SILVER AIR DRAIN VALVE", "underwater")
                press(0x50)
                check(!core.state.safety.sealMonitor.inspected)
                repeat(2) { depth(100.79) }
                checkInspection("REMEDY ANY WATER OR SALT INGRESS, INSPECT O-RING, BLUE CAP & SILVER AIR DRAIN VALVE", "resurfaced")
                press(0x50)
                findText("ESTABLISH VACUUM")
                findText(dismissHint)
                check(core.state.safety.sealMonitor.let { it.banner == Banner.B03 && it.inspected && it.incidents.isNotEmpty() })
                result.putString("inspection", "All three depth messages fit the reference banner. Surface confirmation showed Establish Vacuum without starting the pump.")
                val pressureFailure = "Pressure back to ambient after 277s: cover open or seal lost."
                runOnMainSync {
                    val safety = core.state.safety
                    rendered.value = core.state.copy(lastWarning = pressureFailure, safety = safety.copy(
                        sealState = SealState.Failed, warning = pressureFailure,
                        sealMonitor = safety.sealMonitor.copy(banner = Banner.B17, verification = Verification.Failed)))
                }
                findText("LEAK TEST FAILED — DO NOT DIVE")
                check(bannerBounds(Color.rgb(127, 0, 0)) == reference)
                check(uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(pressureFailure).none { it.isVisibleToUser })
                result.putString("failedRecheck", "Dark red verdict matched reference dimensions and the orange pressure diagnostic was absent.")
                runOnMainSync {
                    screen.setContent { DiveControlTheme { com.mobiledivecontrol.ui.welcome.WelcomeSplashScreen() } }
                }
                val welcome = Rect().also {
                    findText(com.mobiledivecontrol.branding.NauticaseBranding.WELCOME_PROMPT).getBoundsInScreen(it)
                }
                SystemClock.sleep(400) // Wait for the welcome fade to reach its final fill colour.
                val welcomeBand = bannerBounds(Color.rgb(255, 179, 0), welcome.centerY())
                check(welcomeBand.width() == reference.width() && welcomeBand.height() == reference.height())
                fun welcomeArtBounds(): Rect {
                    val greeting = Rect().also { findText("Welcome to").getBoundsInScreen(it) }
                    val logo = Rect().also { findText("NAUTICASE").getBoundsInScreen(it) }
                    check(greeting.bottom <= logo.top && logo.width().toFloat() / logo.height() in 2.10f..2.13f)
                    return logo
                }
                val returningLogo = welcomeArtBounds()
                check(returningLogo.bottom < welcomeBand.top)
                runOnMainSync { screen.setContent { DiveControlTheme {
                    com.mobiledivecontrol.ui.welcome.WelcomeSplashScreen(showSealInstruction = false)
                } } }
                waitForIdleSync()
                val freshLogo = welcomeArtBounds()
                check(freshLogo.width() == returningLogo.width() && freshLogo.height() == returningLogo.height())
                check(uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(
                    com.mobiledivecontrol.branding.NauticaseBranding.WELCOME_PROMPT).none { it.isVisibleToUser })
                result.putString("welcomeArtwork", "Emblem and wordmark measured ${freshLogo.width()} × ${freshLogo.height()} px on first and repeat launch.")
                runOnMainSync {
                    screen.setContent {
                        DiveControlTheme {
                            com.mobiledivecontrol.ui.tutorial.IntroCarouselScreen(permissionsGranted = true,
                                bleState = BleConnectionState.Ready, firstLaunchSealStep = com.mobiledivecontrol.FirstLaunchSealStep.Connected)
                        }
                    }
                }
                findText("CONNECTED TO HOUSING")
                val intro = bannerBounds(Color.rgb(150, 238, 68))
                check(intro.width() == reference.width() && intro.height() == reference.height())
                result.putString("onboarding", "Welcome and animated connected message matched the disconnected banner size.")
                checkOtherMessageBanners(screen, reference, core.state, compass)
                result.putString("otherMessages", "Camera warning, gallery feedback, air reminder and buddy-air notice matched the reference.")
                result.putString("earlyRelease", "Replayed the first-countdown pressure ramp and confirmed No opens inspection.")
            }
        } catch (failure: Throwable) {
            result.putString("failure", android.util.Log.getStackTraceString(failure))
        } finally {
            runOnMainSync { activity?.finish() }
            finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }

    private fun checkInspectionHistory() {
        // Use separate storage in the isolated test package, independent of the simulated housing session.
        val storage = targetContext.createDeviceProtectedStorageContext()
        val prefs = storage.getSharedPreferences("housing_seal_history", android.content.Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        val store = HousingSealStore(storage)
        val dry = store.read()
        check(!dry.submergedSinceSealCheck)
        val wet = dry.copy(submergedSinceSealCheck = true)
        store.update(dry, wet)
        check(HousingSealStore(storage).read().submergedSinceSealCheck)
        store.update(wet, dry)
        check(!store.read().submergedSinceSealCheck)
        check(prefs.edit().remove("submergedSinceSealCheck")
            .putStringSet("incidents", setOf(SealIncident.UnderwaterLeak.name)).commit())
        check(store.read().submergedSinceSealCheck)
    }

    private fun findText(text: String): AccessibilityNodeInfo {
        fun visibleText(node: AccessibilityNodeInfo?, nodes: MutableList<AccessibilityNodeInfo>) {
            if (node == null) return
            if ((node.text != null || node.contentDescription != null) && node.isVisibleToUser) nodes += node
            repeat(node.childCount) { visibleText(node.getChild(it), nodes) }
        }
        val deadline = SystemClock.uptimeMillis() + 8_000
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        do {
            nodes.clear()
            visibleText(uiAutomation.rootInActiveWindow, nodes)
            val match = nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (match != null) return match
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing visible text: $text. Rendered: ${nodes.map { it.text }}")
    }

    private fun checkOtherMessageBanners(screen: MainActivity, reference: Rect, state: AppState,
                                         compass: MutableStateFlow<CompassReading>) {
        fun checkBanner(label: String, border: Int, content: @Composable () -> Unit) {
            runOnMainSync { screen.setContent { DiveControlTheme { content() } } }
            val labelY = Rect().also { findText(label).getBoundsInScreen(it) }.centerY()
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            try {
                fun matches(x: Int, y: Int): Boolean {
                    val pixel = frame.getPixel(x, y)
                    return kotlin.math.abs(Color.red(pixel) - Color.red(border)) <= 12 &&
                        kotlin.math.abs(Color.green(pixel) - Color.green(border)) <= 12 &&
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(border)) <= 12
                }
                val corner = (8 * targetContext.resources.displayMetrics.density).toInt()
                val x = reference.left + corner
                val top = ((labelY - reference.height() + 1).coerceAtLeast(1)..labelY).firstOrNull { y ->
                    val bottom = y + reference.height()
                    bottom < frame.height && matches(x, y) && !matches(x, y - 1) &&
                        matches(x, bottom - 1) && !matches(x, bottom) &&
                        matches(reference.left, y + corner) && matches(reference.right - 1, y + corner) &&
                        !matches(reference.left - 1, y + corner) && !matches(reference.right, y + corner)
                }
                check(top != null) { "$label border did not match reference width and height at label y=$labelY" }
            } finally { frame.recycle() }
        }
        val camera = state.copy(safety = SafetyState(), lastWarning = "Camera unavailable")
        checkBanner("Camera unavailable", Color.rgb(81, 57, 0)) {
            DiveControlScreen(state = camera, compassReadings = compass, onCompassMonitoring = {})
        }
        checkBanner("Album updated", Color.rgb(0, 103, 115)) {
            com.mobiledivecontrol.ui.gallery.GalleryScreen(GalleryState(operationMessage = "Album updated"), {})
        }
        val air = AirAlertState(settings = AirAlertSettings(confirmation = AirConfirmation.Never, flash = false),
            prompt = AirPrompt(1, false, 0))
        checkBanner("CHECK YOUR AIR", Color.rgb(255, 200, 87)) {
            com.mobiledivecontrol.ui.dive.DiveAlertLayer(camera.copy(airAlerts = air), true, {})
        }
        checkBanner("BUDDY AIR · GAS UNKNOWN", Color.rgb(0, 229, 255)) {
            com.mobiledivecontrol.ui.dive.DiveAlertLayer(camera.copy(airAlerts = air.copy(sharedAir = true)), true, {})
        }
    }

    private fun clickText(text: String) {
        var target = findText(text)
        while (!target.isClickable) target = checkNotNull(target.parent) { "Missing click action for $text" }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun bannerCenterY(headline: String, lastLine: String): Int {
        val top = android.graphics.Rect().also { findText(headline).getBoundsInScreen(it) }.top
        val bottom = android.graphics.Rect().also { findText(lastLine).getBoundsInScreen(it) }.bottom
        return (top + bottom) / 2
    }

    private fun bannerBounds(color: Int, centerY: Int? = null): Rect {
        val window = Rect().also { uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        var frame = checkNotNull(uiAutomation.takeScreenshot())
        try {
            // A translucent banner can differ from its fill by up to 21 RGB levels.
            fun matches(x: Int, y: Int): Boolean {
                val pixel = frame.getPixel(x, y)
                return kotlin.math.abs(Color.red(pixel) - Color.red(color)) <= 22 &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(color)) <= 22 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) <= 22
            }
            val x = window.left + window.width() / 8
            var top = centerY ?: window.centerY()
            var bottom = top
            // Accessibility can publish the new message before Android presents its first frame.
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (!matches(x, top) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                val next = checkNotNull(uiAutomation.takeScreenshot())
                frame.recycle()
                frame = next
            }
            check(matches(x, top)) { "Expected ${Integer.toHexString(color)}, saw ${Integer.toHexString(frame.getPixel(x, top))} at ($x, $top) in $window" }
            while (top > window.top && matches(x, top - 1)) top--
            while (bottom < window.bottom && matches(x, bottom)) bottom++
            val y = top + (8 * targetContext.resources.displayMetrics.density).toInt()
            // Scan inward from the window edges so letters within the band cannot end the scan.
            val left = (window.left..x).first { matches(it, y) && matches(it, y + 1) }
            val right = (window.right - 1 downTo x).first { matches(it, y) && matches(it, y + 1) } + 1
            return Rect(left, top, right, bottom)
        } finally {
            frame.recycle()
        }
    }

    private fun checkChipColour(bounds: android.graphics.Rect, yellow: Boolean) {
        repeat(10) {
            val frame = checkNotNull(uiAutomation.takeScreenshot())
            var matches = 0
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                val pixel = frame.getPixel(x, y)
                val green = android.graphics.Color.green(pixel)
                if (android.graphics.Color.red(pixel) > 190 && android.graphics.Color.blue(pixel) < 110 &&
                    (if (yellow) green > 190 else green < 75)) matches++
            }
            frame.recycle()
            if (matches >= 10) return
            SystemClock.sleep(100)
        }
        error("Missing ${if (yellow) "yellow" else "red"} LEAK TEST text")
    }

    private fun checkReleaseBand(expected: Rect) = checkBannerEdges(expected) { pixel ->
        Color.red(pixel) < 12 && Color.green(pixel) in 120..132 && Color.blue(pixel) in 133..146
    }

    private fun checkBannerEdges(expected: Rect, matches: (Int) -> Boolean) {
        val frame = checkNotNull(uiAutomation.takeScreenshot())
        try {
            fun border(x: Int, y: Int) = matches(frame.getPixel(x, y))
            val inset = (8 * targetContext.resources.displayMetrics.density).toInt()
            for (x in listOf(expected.left + inset, expected.centerX(), expected.right - inset)) {
                check(border(x, expected.top) && border(x, expected.bottom - 1))
                check(!border(x, expected.top - 1) && !border(x, expected.bottom))
            }
            check(border(expected.left, expected.top + inset) && border(expected.right - 1, expected.top + inset))
            check(!border(expected.left - 1, expected.top + inset) && !border(expected.right, expected.top + inset))
        } finally {
            frame.recycle()
        }
    }

    private fun checkStandby(screen: MainActivity) {
        lateinit var vm: com.mobiledivecontrol.viewmodel.DiveViewModel
        fun sample(internalKpa: Double) {
            vm.updateSensor(SensorUpdate.WaterPressure(101.3))
            vm.updateSensor(SensorUpdate.BarometricPressure(internalKpa))
        }
        runOnMainSync {
            vm = androidx.lifecycle.ViewModelProvider(screen)[com.mobiledivecontrol.viewmodel.DiveViewModel::class.java]
            vm.finishWelcome()
            vm.dismissIntro()
            vm.simulateHousingLink()
            vm.updateSensor(SensorUpdate.CoverState(false))
            repeat(4) { sample(101.3) }
            repeat(6) { sample(80.5) }
            check(vm.state.value.safety.adoptedHold)
            repeat(3) { sample(101.3) }
            check(vm.state.value.safety.sealMonitor.banner == Banner.ConfirmRelease)
            vm.dispatch(CameraCommand.ReportRuntimeFailure("Test camera warning behind vacuum choices"))
        }
        findText("DID YOU RELEASE THE VACUUM?")
        checkChoiceLayout("DID YOU RELEASE THE VACUUM?", "NO", "YES")
        clickText("YES")
        runOnMainSync { sample(101.3) }
        clickText("MENU/OK CONFIRMS")
        findText("VACUUM RELEASED")
        checkChoiceLayout("VACUUM RELEASED", "SHUT DOWN", "VACUUM SETUP")
        runOnMainSync { sample(101.3) }
        clickText("MENU/OK CONFIRMS")
        val instruction = "Hold MENU/OK for 3 seconds\nto power off the housing."
        findText(instruction)
        SystemClock.sleep(3_250)
        findText(instruction)
        runOnMainSync {
            check(vm.state.value.shutOffPrompt == ShutOffPrompt.AwaitingPress)
            check(screen.window.attributes.screenBrightness == android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            vm.onNotification(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x50))
            check(vm.state.value.shutOffPrompt is ShutOffPrompt.MenuPressed)
        }
        SystemClock.sleep(2_400)
        runOnMainSync { check(vm.state.value.shutOffPrompt is ShutOffPrompt.MenuPressed) }
        SystemClock.sleep(850)
        waitForIdleSync()
        runOnMainSync {
            check(vm.state.value.housingStandby)
            check(vm.state.value.shutOffPrompt == null)
            checkStandbyDisplay(screen)
        }
        val black = checkNotNull(uiAutomation.takeScreenshot())
        for (x in 1..8) for (y in 1..8)
            check(black.getPixel(black.width * x / 9, black.height * y / 9) == android.graphics.Color.BLACK)
        black.recycle()
        runOnMainSync {
            vm.simulateHousingLink() // end this connection
            check(vm.state.value.housingStandby)
            vm.simulateHousingLink() // new connection reaches Ready
            check(!vm.state.value.housingStandby)
            vm.updateSensor(SensorUpdate.CoverState(true))
            repeat(3) { sample(101.3) }
        }
        findText("Press DOWN to start vacuum pump.")
    }

    private fun checkMenuShutOff(screen: MainActivity) {
        lateinit var vm: com.mobiledivecontrol.viewmodel.DiveViewModel
        runOnMainSync {
            vm = androidx.lifecycle.ViewModelProvider(screen)[com.mobiledivecontrol.viewmodel.DiveViewModel::class.java]
            vm.dispatch(SafetyCommand.DismissSealCheck)
            vm.dispatch(CameraCommand.OpenModeRail)
        }
        clickText("Shut Off")
        val instruction = "Hold MENU/OK for 3 seconds\nto power off the housing."
        val bounds = android.graphics.Rect().also { findText(instruction).getBoundsInScreen(it) }
        val window = android.graphics.Rect().also { uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        val density = targetContext.resources.displayMetrics.density
        check(bounds.height() >= 60 * density && window.contains(bounds))
        check(kotlin.math.abs(bounds.centerX() - window.centerX()) <= 3 * density)
        check(kotlin.math.abs(bounds.centerY() - window.centerY()) <= 3 * density)
        runOnMainSync {
            check(vm.state.value.housingStandby)
            vm.onNotification(HousingCharacteristic.ButtonEvents.shortHex, byteArrayOf(0x50))
            check(vm.state.value.shutOffPrompt is ShutOffPrompt.MenuPressed)
        }
        SystemClock.sleep(2_400)
        runOnMainSync {
            check(vm.state.value.shutOffPrompt is ShutOffPrompt.MenuPressed)
            check(screen.window.attributes.screenBrightness == android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        SystemClock.sleep(850)
        runOnMainSync {
            check(vm.state.value.bleConnectionState == BleConnectionState.Ready)
            check(vm.state.value.housingStandby && vm.state.value.shutOffPrompt == null)
        }
        waitForIdleSync()
        runOnMainSync {
            check(vm.state.value.housingStandby && vm.state.value.shutOffPrompt == null)
            checkStandbyDisplay(screen)
        }
        check(uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(instruction).orEmpty().none { it.isVisibleToUser })
        runOnMainSync {
            vm.simulateHousingLink()
            check(vm.state.value.housingStandby && vm.state.value.shutOffPrompt == null)
            vm.simulateHousingLink()
            check(!vm.state.value.housingStandby)
        }
        waitForIdleSync()
        runOnMainSync {
            check(screen.window.attributes.screenBrightness == android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    private fun checkStandbyDisplay(screen: MainActivity) {
        val attributes = screen.window.attributes
        check(attributes.screenBrightness > 0f && attributes.screenBrightness < 0.1f)
        check(attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        check(screen.getSystemService(android.os.PowerManager::class.java).isInteractive)
        check(!screen.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
    }

    private fun textLayouts(screen: MainActivity, labels: Set<String>): Map<String, TextLayoutResult> {
        val layouts = mutableMapOf<String, TextLayoutResult>()
        fun collect(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
            if (text != null && text in labels) {
                val results = mutableListOf<TextLayoutResult>()
                check(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
                layouts[text] = results.single()
            }
            node.children.forEach(::collect)
        }
        val views = (screen.window.decorView as ViewGroup).descendants
        views.filterIsInstance<RootForTest>().forEach { collect(it.semanticsOwner.unmergedRootSemanticsNode) }
        check(layouts.keys == labels)
        return layouts
    }

    private fun checkReleasedLabelSize(screen: MainActivity): String {
        val layouts = textLayouts(screen, setOf("SHUT DOWN", "VACUUM SETUP"))
        val shutDown = layouts.getValue("SHUT DOWN")
        val setup = layouts.getValue("VACUUM SETUP")
        check(layouts.values.all { it.lineCount == 1 && !it.hasVisualOverflow })
        check(shutDown.layoutInput.style.fontSize == setup.layoutInput.style.fontSize && shutDown.firstBaseline == setup.firstBaseline)
        return "Both labels fit on one line at ${setup.layoutInput.style.fontSize}, baseline ${setup.firstBaseline}px."
    }

    private fun checkChoiceLayout(title: String, left: String, right: String): String {
        fun bounds(text: String) = android.graphics.Rect().also { findText(text).getBoundsInScreen(it) }
        val density = targetContext.resources.displayMetrics.density
        val heading = bounds(title)
        val first = bounds(left)
        val second = bounds(right)
        val select = bounds("LEFT / RIGHT TO SELECT")
        val confirm = bounds("MENU/OK CONFIRMS")
        check(first.centerY() == second.centerY() && first.right <= second.left)
        check(heading.bottom <= minOf(first.top, second.top))
        check(kotlin.math.abs(select.centerY() - confirm.centerY()) <= 2 * density)
        if (title == "DID YOU RELEASE THE VACUUM?") {
            val window = Rect().also { uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            check(first.width() / density in 55f..77f && second.width() / density in 55f..77f) { "Choice bounds $first, $second" }
            check(kotlin.math.abs((first.left + second.right) / 2 - window.centerX()) <= density * 2)
            // The clickable confirm hint's accessibility bounds include its touch target.
            check(heading.height() > select.height() && first.height() > select.height()) {
                "Heading $heading, choice $first, caption $select"
            }
        } else {
            check(confirm.bottom - heading.top <= 100 * density && second.right - first.left <= 288 * density) {
                "Heading $heading, choices $first and $second, confirm $confirm, density $density"
            }
        }
        check(uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Test camera warning behind vacuum choices")
            .none { it.isVisibleToUser })
        return "Text top edges: title=${heading.top}, choices=${first.top}"
    }
}
