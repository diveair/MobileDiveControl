package com.mobiledivecontrol.viewmodel

import android.content.Context
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.FocusCurveMode
import com.mobiledivecontrol.core.SliderSensitivity
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln

/**
 * Re-spells saved focus values onto the current 0.005 ladder.
 *
 * A focus saved by an older build reads "0.42", which matches no rung once the ladder carries
 * three decimals. Left alone the reducer's `indexOf(...).coerceAtLeast(0)` resolves the miss to
 * index 0 — which is "AF" — so the HUD would keep showing 0.42 while the diver's very first
 * wheel click silently threw focus to autofocus. Snapping to the nearest real rung loses nothing:
 * every old 0.01 rung is exactly a 0.005 rung.
 *
 * Idempotent, so it needs no one-shot migration flag and survives a downgrade/upgrade cycle.
 * Kept free of Android types so it can be tested directly.
 */
internal fun snapFocusValuesToLadder(values: Map<String, String>): Map<String, String> {
    val result = values.toMutableMap()
    listOf(
        "photo.manual_focus", "expert.manual_focus", "pro.manual_focus", "pro_video.manual_focus",
        "portrait_video.manual_focus", "hyperlapse.manual_focus",
    )
        .forEach { key ->
            val value = result[key] ?: return@forEach
            if (value == "AF") return@forEach
            val numeric = value.toDoubleOrNull()
            if (numeric == null || numeric !in 0.0..1.0) {
                // Unreadable: drop it so the catalog default applies deliberately, rather than
                // the app arriving at AF by accident on the diver's next detent.
                result.remove(key)
                return@forEach
            }
            result[key] = String.format(
                java.util.Locale.US, "%.3f", Math.round(numeric * 200.0) / 200.0,
            )
        }
    return result
}

private val ISO_KEYS = listOf("expert.iso", "pro.iso", "pro_video.iso")
private val SHUTTER_KEYS =
    listOf("expert.shutter_speed", "pro.shutter_speed", "pro_video.shutter_speed")
private val WHITE_BALANCE_KEYS =
    listOf("expert.white_balance", "pro.white_balance", "pro_video.white_balance")

// Two exposure scales, matching the native split: the Pro dialer runs +/-4.0, the quick EV bar
// +/-2.0. Snapping each key against its OWN ladder is what stops a stored "+3.0" from surviving
// on a mode whose dial ends at +/-2.0.
private val EXPOSURE_PRO_KEYS = listOf(
    "expert.exposure_value", "pro.exposure_value", "pro_video.exposure_value",
)
private val EXPOSURE_QUICK_KEYS = listOf(
    "photo.exposure_compensation",
    "portrait.exposure",
    "food.exposure",
    "night.exposure",
    "panorama.exposure",
    "hyperlapse.exposure",
    "video.exposure",
    "portrait_video.exposure",
    "slow_motion.exposure",
)

/**
 * Re-spells saved ISO / shutter / white-balance / exposure values onto the current ladders.
 *
 * Same failure this guards as [snapFocusValuesToLadder]: a stored string that names no rung is
 * resolved by the reducer to index 0, and for all four of these scales index 0 is "Auto" — so a
 * saved "6400" would hand exposure back to the camera on the diver's first detent while the HUD
 * still read 6400. Snapping by VALUE (log-space for the two multiplicative scales) keeps the
 * exposure the diver actually chose wherever a rung exists for it.
 *
 * Idempotent by construction: a value already on its ladder is returned untouched, so a second
 * pass, or a downgrade/upgrade cycle, is a no-op. No migration flag needed.
 *
 * Kept free of Android types so it can be tested directly.
 */
internal fun snapScaleValuesToLadders(values: Map<String, String>): Map<String, String> {
    val result = values.toMutableMap()
    // "Auto" was the single WB mode in older builds. Its exact behavioural successor is the
    // continuously metering mode; spell it explicitly before generic ladder snapping so an
    // upgrade never drops a user's automatic-WB selection or mistakes it for a Kelvin value.
    WHITE_BALANCE_KEYS.forEach { key ->
        if (result[key] == "Auto") result[key] = CameraCatalog.WB_AUTO_CONTINUOUS
    }
    snapToLadder(result, ISO_KEYS, CameraCatalog.isoLadder, ::isoMagnitude)
    // Snap targets exclude the 1/24000 and 1/16000 rungs the native fast floor removes from
    // every render of this dial: a value snapped onto them would sit off the clipped ladder at
    // runtime, and the reducer resolves an unfound value to index 0 — which is Auto.
    val reachableShutterLadder = CameraCatalog.shutterLadder.filter { rung ->
        val ns = CameraCatalog.shutterOptionNanos(rung)
        ns == null || ns >= CameraCatalog.SHUTTER_NATIVE_MIN_NS
    }
    snapToLadder(result, SHUTTER_KEYS, reachableShutterLadder, ::shutterMagnitude)
    snapToLadder(result, WHITE_BALANCE_KEYS, CameraCatalog.whiteBalanceLadder, ::kelvinMagnitude)
    // A stored "Auto" from the old exposure ladder has no magnitude, so snapToLadder drops it
    // and the catalog default "0.0" applies — exactly the native reset, where EV has no Auto.
    snapToLadder(result, EXPOSURE_PRO_KEYS, CameraCatalog.exposureProLadder, ::exposureMagnitude)
    snapToLadder(result, EXPOSURE_QUICK_KEYS, CameraCatalog.exposureQuickLadder, ::exposureMagnitude)
    // Native demotion at restore (ProVideoPresenter.onStartPreviewCompleted): a stored Pro Video
    // shutter slower than the stored frame rate's period demotes to the slowest admitted rung,
    // so a value the fps-clipped dial cannot show never reaches the reducer.
    val fps = (result["pro_video.frame_rate"] ?: CameraCatalog.defaultSettingValues["pro_video.frame_rate"])
        ?.removeSuffix("fps")?.toIntOrNull()
    if (fps != null && fps > 0) {
        // ROUNDED, exactly like CameraCatalog.videoShutterCapNs: truncating division computes
        // 16666666 at 60 fps and evicts the legal "1/60" cap rung itself (which spells 16666667),
        // demoting a legitimately stored 1/60 to 1/90 on every restore.
        val capNs = Math.round(1_000_000_000.0 / fps)
        result["pro_video.shutter_speed"]?.let { stored ->
            val ns = CameraCatalog.shutterOptionNanos(stored)
            if (ns != null && ns > capNs) {
                val admitted = reachableShutterLadder.filter { rung ->
                    CameraCatalog.shutterOptionNanos(rung)?.let { it <= capNs } == true
                }
                CameraCatalog.nearestShutterOption(capNs, admitted)
                    ?.let { result["pro_video.shutter_speed"] = it }
            }
        }
    }
    return result
}

private fun snapToLadder(
    values: MutableMap<String, String>,
    keys: List<String>,
    ladder: List<String>,
    magnitude: (String) -> Double?,
) {
    // "Auto" yields null and so is never a snap TARGET; a stored "Auto" never reaches the snap
    // because it is already on the ladder.
    val rungs = ladder.mapNotNull { rung -> magnitude(rung)?.let { rung to it } }
    keys.forEach { key ->
        val stored = values[key] ?: return@forEach
        if (stored in ladder) return@forEach
        val target = magnitude(stored)
        if (target == null || rungs.isEmpty()) {
            // Unreadable: drop it so the catalog default applies deliberately, rather than the
            // app arriving at Auto by accident on the diver's next detent.
            values.remove(key)
            return@forEach
        }
        values[key] = rungs.minByOrNull { (_, value) -> abs(value - target) }!!.first
    }
}

/** Log-space: ISO is multiplicative, so 6400 must land on 3200, not be pulled toward 800. */
private fun isoMagnitude(option: String): Double? =
    option.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { ln(it) }

/** Log-space seconds. Accepts "1/60", "0.5\"" and the legacy "1/2". */
private fun shutterMagnitude(option: String): Double? =
    CameraCatalog.shutterOptionNanos(option)?.takeIf { it > 0L }?.let { ln(it.toDouble()) }

private fun kelvinMagnitude(option: String): Double? = option.removeSuffix("K").toDoubleOrNull()

private fun exposureMagnitude(option: String): Double? = option.replace("+", "").toDoubleOrNull()

class CameraSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun restoreAppState(): AppState {
        val activeMode = preferences.getString(KEY_ACTIVE_MODE, null)
            ?.let { stored -> runCatching { CameraModeId.valueOf(stored) }.getOrNull() }
            ?: CameraModeId.Photo

        val settingValues = migrateLegacyAssistDefaults(
            normalizeRestoredSettingValues(
                CameraCatalog.defaultSettingValues + restoreStringMap(KEY_SETTING_VALUES),
            ),
        )
        val sliderSensitivities = CameraCatalog.defaultSliderSensitivities + restoreSensitivityMap()
        val focusCurveModes = CameraCatalog.defaultFocusCurveModes + restoreFocusCurveMap()
        val detectedLenses = restoreDetectedLenses()

        return AppState(
            camera = CameraCatalog.launchCameraState(
                activeMode = activeMode,
                settingValues = settingValues,
                sliderSensitivities = sliderSensitivities,
                focusCurveModes = focusCurveModes,
                detectedLenses = detectedLenses,
            ),
        )
    }

    fun save(state: AppState) {
        preferences.edit()
            .putString(KEY_ACTIVE_MODE, state.camera.activeMode.name)
            .putString(KEY_SETTING_VALUES, JSONObject(state.camera.settingValues).toString())
            .putString(
                KEY_SLIDER_SENSITIVITIES,
                JSONObject(
                    state.camera.sliderSensitivities.mapValues { (_, value) -> value.level.toString() },
                ).toString(),
            )
            .putString(
                KEY_FOCUS_CURVE_MODES,
                JSONObject(
                    state.camera.focusCurveModes.mapValues { (_, value) -> value.name },
                ).toString(),
            )
            .putString(
                KEY_DETECTED_LENSES,
                state.camera.detectedLenses.joinToString(","),
            )
            .apply()
    }

    private fun restoreStringMap(key: String): Map<String, String> {
        val raw = preferences.getString(key, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun restoreSensitivityMap(): Map<String, SliderSensitivity> {
        return restoreStringMap(KEY_SLIDER_SENSITIVITIES).mapNotNull { (key, value) ->
            value.toIntOrNull()?.let { level -> key to SliderSensitivity.of(level) }
        }.toMap()
    }

    private fun restoreFocusCurveMap(): Map<String, FocusCurveMode> {
        return restoreStringMap(KEY_FOCUS_CURVE_MODES).mapNotNull { (key, value) ->
            runCatching { FocusCurveMode.valueOf(value) }.getOrNull()?.let { mode -> key to mode }
        }.toMap()
    }

    private fun restoreDetectedLenses(): List<String> {
        val raw = preferences.getString(KEY_DETECTED_LENSES, null)
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    /**
     * Validate restored setting values against known valid options.
     * This prevents issues like a "5x" lens value persisted from an S26Ultra
     * being applied on an S24 that doesn't have a 5x lens.
     * Lens validation is deferred to runtime when detectedLenses are available.
     */
    internal fun normalizeRestoredSettingValues(values: Map<String, String>): Map<String, String> {
        val result = values.toMutableMap()
        // Validate focus curve values
        listOf(
            "photo.focus_curve", "expert.focus_curve", "pro.focus_curve", "pro_video.focus_curve",
            "portrait_video.focus_curve", "hyperlapse.focus_curve",
        ).forEach { key ->
            val value = result[key]
            if (value != null && value !in listOf("Linear", "SquareRoot", "Logarithmic")) {
                result[key] = "SquareRoot"
            }
        }
        return snapScaleValuesToLadders(snapFocusValuesToLadder(result))
    }

    /**
     * One-time reset of the assist-look toggles. Old builds shipped Pro Video with HDR and
     * LOG "On" by default and persisted that silently; on Samsung, the LOG tonemap bypasses
     * the vendor's adaptive tone mapping, so field phones kept rendering a dark, murky image
     * long after the catalog defaults changed to "Off" — persisted values always override new
     * defaults. Runs once; the user's future explicit choices persist normally.
     */
    private fun migrateLegacyAssistDefaults(values: Map<String, String>): Map<String, String> {
        if (preferences.getBoolean(KEY_ASSIST_DEFAULTS_MIGRATED, false)) return values
        preferences.edit().putBoolean(KEY_ASSIST_DEFAULTS_MIGRATED, true).apply()
        val result = values.toMutableMap()
        values.keys
            .filter { it.endsWith(".hdr") || it.endsWith(".log") || it.endsWith(".hdr_log") }
            .forEach { key -> result[key] = CameraCatalog.defaultSettingValues[key] ?: "Off" }
        return result
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_session"
        const val KEY_ACTIVE_MODE = "active_mode"
        const val KEY_SETTING_VALUES = "setting_values"
        const val KEY_SLIDER_SENSITIVITIES = "slider_sensitivities"
        const val KEY_FOCUS_CURVE_MODES = "focus_curve_modes"
        const val KEY_DETECTED_LENSES = "detected_lenses"
        const val KEY_ASSIST_DEFAULTS_MIGRATED = "assist_defaults_migrated_v2"
    }
}
