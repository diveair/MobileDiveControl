package com.mobiledivecontrol.ui.dive

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.*
import com.mobiledivecontrol.platform.AirAlertStore
import com.mobiledivecontrol.theme.DiveControlTheme
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/** Device-only harness: real store/packets/core/composables, isolated from user settings and dive logs. */
class AirAlertWiringInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val checks = mutableListOf<String>()
        val directory = File(targetContext.getExternalFilesDir(null), "air-wiring-validation").apply { mkdirs() }
        val preferences = targetContext.getSharedPreferences("air_wiring_validation", Context.MODE_PRIVATE)
        val isolatedContext = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        var activity: MainActivity? = null
        try {
            val store = AirAlertStore(isolatedContext)
            val settings = defaultAirAlertSettings().copy(intervalSeconds = 30, autoDismiss = false, flash = false)
            store.save(settings)
            check(AirAlertStore(isolatedContext).read() == settings)
            checks += "Settings saved and reloaded through AirAlertStore"
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = checkNotNull(activity)
            val rendered = mutableStateOf(AppState())
            var now = pressureMonotonicMs()
            var serial = 0L
            lateinit var core: ControlCore
            fun fresh(config: AirAlertSettings) {
                core = ControlCore(AppState(mode = AppMode.CameraLive, permissions = PermissionsState(camera = true, microphone = true),
                    housing = HousingState(connected = true, inputEnabled = true),
                    airAlerts = AirAlertState(settings = config)), monotonicMs = { now })
            }
            fun sample(depth: Double) {
                val raw = ((STANDARD_SURFACE_PRESSURE_KPA + depth * FRESHWATER_KPA_PER_METER) * 100).roundToInt()
                core.handleNotificationPayload(HousingCharacteristic.WaterPressure.shortHex,
                    ByteArray(4) { (raw ushr (it * 8)).toByte() }, Instant.ofEpochMilli(now), receivedAtMonotonicMs = now)
                core.tickDiveProfile(now)
            }
            fun advance(seconds: Int, depth: Double = 20.0) { repeat(seconds * 2) { now += 500; sample(depth) } }
            fun command(c: AirAlertCommand) { core.dispatch(DiveSettingsCommand.Alerts(c)) }
            fun publish() { rendered.value = core.state }
            fun key(value: Int) {
                core.handleButtonPayload(byteArrayOf(value.toByte()), receivedAt = Instant.ofEpochMilli(now + ++serial * 250))
                publish()
            }
            runOnMainSync {
                fresh(store.read()); publish()
                screen.setContent {
                    DiveControlTheme {
                        Box(Modifier.fillMaxSize().background(Color(0xFF244753)), contentAlignment = Alignment.Center) {
                            val state = rendered.value
                            if (state.airAlerts.menuOpen) DiveAlertsScreen(state) { core.dispatch(it); publish() }
                            else DiveAlertLayer(state, true, { core.dispatch(it); publish() })
                        }
                    }
                }
            }
            fun show() { waitForIdleSync(); Thread.sleep(600) }
            fun capture(name: String) {
                show()
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            fun nodes(): List<AccessibilityNodeInfo> {
                val resultNodes = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    resultNodes += node
                    repeat(node.childCount) { visit(node.getChild(it)) }
                }
                visit(uiAutomation.rootInActiveWindow)
                return resultNodes
            }
            fun expect(text: String) {
                repeat(10) {
                    show()
                    if (nodes().any { it.text?.toString()?.contains(text) == true }) return
                }
                error("UI missing: $text; visible: ${nodes().mapNotNull { it.text }}")
            }
            fun click(text: String) {
                val node = nodes().first { it.text?.toString() == text }
                var target = node
                while (!target.isClickable && target.parent != null) target = target.parent
                check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
                show()
            }
            runOnMainSync { sample(20.0); advance(30); publish() }
            expect("CHECK YOUR AIR"); expect("DIVE TIME"); expect("0:30"); expect("1:00")
            capture("scheduled-reminder")
            click("OK")
            runOnMainSync { check(core.state.airAlerts.prompt == null) }
            checks += "Production pressure packets and 500 ms ticks produce the actual centred reminder; touch OK dismisses"
            runOnMainSync { advance(30); key(0x50); check(core.state.airAlerts.prompt == null) }
            checks += "Next recurring alert is dismissed by a decoded housing OK packet"

            runOnMainSync {
                fresh(settings.copy(confirmation = AirConfirmation.Every, hudMode = AirHudMode.BottomTime))
                core.forceMode(AppMode.DiveSettings)
                core.dispatch(DiveSettingsCommand.Select(DiveSettingsField.Alerts)); core.dispatch(DiveSettingsCommand.Confirm)
                command(AirAlertCommand.SelectPage(AirPage.Gas))
                command(AirAlertCommand.SelectRow(airFields(core.state.airAlerts).indexOf(AirField.Start)))
                command(AirAlertCommand.Activate); command(AirAlertCommand.SavePressure)
                command(AirAlertCommand.Back); command(AirAlertCommand.Back); core.forceMode(AppMode.CameraLive)
                sample(20.0); advance(30); command(AirAlertCommand.SetPressure(196)); publish()
            }
            expect("TANK PRESSURE"); expect("EXPECTED"); expect("ESTIMATED BOTTOM TIME")
            capture("pressure-report-live-estimate")
            click("OK  REPORT PRESSURE")
            runOnMainSync {
                check(core.state.airAlerts.observation?.bar == 196.0)
                check(core.state.airAlerts.prompt == null && core.state.airAlerts.entry == null)
                check(airCameraReadout(core.state.airAlerts, core.state.diveProfile, now)?.text?.startsWith("EST. BOTTOM TIME") == true)
                advance(30); key(0x70)
                check(core.state.airAlerts.entry?.text == "187")
                key(0x50); check(core.state.airAlerts.observation?.bar == 187.0)
                advance(181, 0.0); publish()
                check(core.state.airAlerts.entry == null && core.state.airAlerts.prompt == null && core.state.diveProfile.active == null)
            }
            checks += "Confirmed starting pressure, live slider estimate, actual pressure-report button, next prediction, reversed Z input, housing OK and surface cleanup"

            runOnMainSync {
                fresh(settings)
                core.restoreCameraSnapshot(core.state.camera.copy(activeMode = CameraModeId.ProVideo), AppMode.CameraLive)
                sample(20.0); advance(29); now += 500; sample(5.0); publish()
            }
            expect("OK  MINIMIZE")
            capture("stop-expanded-ok")
            click("OK  MINIMIZE")
            runOnMainSync {
                check(core.state.diveProfile.stopPresentation == DiveStopPresentation.Minimized)
                core.dispatch(DiveSettingsCommand.ExpandStop)
                advance(1, 5.0); publish()
            }
            expect("CHECK YOUR AIR"); expect("SAFETY STOP")
            fun assertStack() {
                val stop = nodes().first { it.text?.toString()?.contains("SAFETY STOP") == true }
                val air = nodes().first { it.text?.toString()?.contains("CHECK YOUR AIR") == true }
                val a = android.graphics.Rect(); val b = android.graphics.Rect()
                stop.getBoundsInScreen(a); air.getBoundsInScreen(b)
                check(a.bottom < b.top) { "Stop overlaps the air reminder: $a vs $b" }
            }
            assertStack(); capture("stop-above-air")
            runOnMainSync {
                val progress = core.state.diveProfile.active!!.stopElapsedMs
                key(0x20)
                check(core.state.camera.recording)
                check(core.state.airAlerts.prompt != null)
                check(core.state.diveProfile.active!!.stopElapsedMs == progress)
            }
            assertStack(); capture("recording-transparent-alerts")
            runOnMainSync {
                key(0x50)
                check(core.state.airAlerts.prompt == null)
                check(core.state.diveProfile.stopExpanded)
            }
            expect("OK  MINIMIZE"); capture("recording-transparent-stop")
            runOnMainSync {
                core.dispatch(DiveSettingsCommand.AcknowledgeStop)
                fresh(settings.copy(confirmation = AirConfirmation.Every))
                core.restoreCameraSnapshot(core.state.camera.copy(activeMode = CameraModeId.ProVideo), AppMode.CameraLive)
                sample(20.0); advance(29); now += 500; sample(5.0); advance(1, 5.0)
                command(AirAlertCommand.SetPressure(180)); publish()
            }
            expect("TANK PRESSURE"); capture("stop-above-pressure-entry")
            runOnMainSync { key(0x20); check(core.state.airAlerts.entry != null); check(core.state.camera.recording) }
            capture("recording-transparent-pressure-entry")
            checks += "Safety stop OK minimizes; reminder and pressure cards stack below it; shutter records without reporting air; recording cards expose the frame"
            runOnMainSync { advance(181, 0.0); publish() }

            runOnMainSync {
                core.forceMode(AppMode.DiveSettings)
                core.dispatch(DiveSettingsCommand.Select(DiveSettingsField.Alerts)); core.dispatch(DiveSettingsCommand.Confirm); publish()
            }
            expect("DIVE ALERTS")
            check(nodes().none { it.text?.toString()?.contains("Test current alert") == true })
            capture("settings-without-test-button")
            checks += "Notification settings have no test button"
            result.putString("checks", checks.joinToString("\n"))
        } catch (error: Throwable) {
            result.putString("failure", android.util.Log.getStackTraceString(error))
        } finally {
            preferences.edit().clear().commit()
            runOnMainSync { activity?.finish() }
            File(directory, "report.txt").writeText(checks.joinToString("\n") + "\n" + (result.getString("failure") ?: "PASS"))
            result.putString("report", File(directory, "report.txt").absolutePath)
            finish(if (result.containsKey("failure")) 1 else 0, result)
        }
    }
}
