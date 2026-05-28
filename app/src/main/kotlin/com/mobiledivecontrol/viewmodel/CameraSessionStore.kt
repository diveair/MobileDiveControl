package com.mobiledivecontrol.viewmodel

import android.content.Context
import com.mobiledivecontrol.core.AppState
import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.SliderSensitivity
import org.json.JSONObject

class CameraSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun restoreAppState(): AppState {
        val activeMode = preferences.getString(KEY_ACTIVE_MODE, null)
            ?.let { stored -> runCatching { CameraModeId.valueOf(stored) }.getOrNull() }
            ?: CameraModeId.Photo

        val settingValues = normalizeRestoredSettingValues(
            CameraCatalog.defaultSettingValues + restoreStringMap(KEY_SETTING_VALUES),
        )
        val sliderSensitivities = CameraCatalog.defaultSliderSensitivities + restoreSensitivityMap()

        return AppState(
            camera = CameraCatalog.launchCameraState(
                activeMode = activeMode,
                settingValues = settingValues,
                sliderSensitivities = sliderSensitivities,
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

    private fun normalizeRestoredSettingValues(values: Map<String, String>): Map<String, String> {
        val normalized = values.toMutableMap()
        values.forEach { (settingId, value) ->
            if (settingId.endsWith(".lens") && value == "0.6x") {
                val baseId = settingId.removeSuffix(".lens")
                val manualFocusValue = values["$baseId.manual_focus"]
                val focusPeakingValue = values["$baseId.focus_peaking"]
                if ((manualFocusValue != null && manualFocusValue != "AF") || focusPeakingValue == "On") {
                    normalized[settingId] = "1x"
                } else {
                    normalized["$baseId.manual_focus"] = "AF"
                    normalized["$baseId.focus_peaking"] = "Off"
                }
            }
        }
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_session"
        const val KEY_ACTIVE_MODE = "active_mode"
        const val KEY_SETTING_VALUES = "setting_values"
        const val KEY_SLIDER_SENSITIVITIES = "slider_sensitivities"
    }
}
