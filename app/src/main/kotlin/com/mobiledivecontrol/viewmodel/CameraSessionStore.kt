package com.mobiledivecontrol.viewmodel

import android.content.Context
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.FocusCurveMode
import com.mobiledivecontrol.core.SliderSensitivity
import org.json.JSONObject

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
    listOf("photo.manual_focus", "expert.manual_focus", "pro.manual_focus", "pro_video.manual_focus")
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
        listOf("photo.focus_curve", "expert.focus_curve", "pro.focus_curve", "pro_video.focus_curve").forEach { key ->
            val value = result[key]
            if (value != null && value !in listOf("Linear", "SquareRoot", "Logarithmic")) {
                result[key] = "SquareRoot"
            }
        }
        return snapFocusValuesToLadder(result)
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
