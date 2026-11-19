package com.mobiledivecontrol.platform

import android.content.Context
import com.mobiledivecontrol.core.AirAlertSettings
import com.mobiledivecontrol.core.AirAlertSettingsCodec
import com.mobiledivecontrol.core.defaultAirAlertSettings

class AirAlertStore(context: Context) {
    private val preferences = context.getSharedPreferences("dive_air_alerts", Context.MODE_PRIVATE)
    fun read(): AirAlertSettings = preferences.getString("settings_v1", null)?.let { value ->
        runCatching { AirAlertSettingsCodec.decode(value) }.getOrDefault(defaultAirAlertSettings())
    } ?: defaultAirAlertSettings()
    fun save(settings: AirAlertSettings) { preferences.edit().putString("settings_v1", AirAlertSettingsCodec.encode(settings)).apply() }
}
