package com.mobiledivecontrol.platform.ble

import android.content.Context
import org.json.JSONObject

/**
 * Remembers which housing this phone belongs to, so the next connection needs no interaction.
 *
 * The diver seals the phone before the dive and cannot answer a device picker afterwards. A
 * remembered MAC address lets the scan short-circuit the moment the housing is seen instead of
 * waiting out a full scan window and ranking candidates.
 *
 * The resolved short-code to UUID map is cached for the same reason: the vendor base UUID in the
 * protocol document is malformed, so the mapping is discovered empirically on first contact. It
 * is a cache, never a source of truth — discovery always re-resolves and overwrites it.
 */
class HousingStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** MAC address of the last housing that reached a Ready link, or `null` if never paired. */
    val lastAddress: String?
        get() = preferences.getString(KEY_ADDRESS, null)

    /** Serial number of that housing, used to warn when a different housing appears. */
    val lastSerialNumber: String?
        get() = preferences.getString(KEY_SERIAL, null)

    /** Firmware revision of that housing, recorded for the compatibility matrix. */
    val lastFirmwareVersion: String?
        get() = preferences.getString(KEY_FIRMWARE, null)

    /**
     * Records a housing that completed the full connection sequence.
     *
     * Only called after subscriptions succeed — a device that connected but could not deliver
     * button events is not a housing worth remembering.
     */
    fun remember(address: String, serialNumber: String?, firmwareVersion: String?) {
        val editor = preferences.edit().putString(KEY_ADDRESS, address)
        if (serialNumber != null) editor.putString(KEY_SERIAL, serialNumber)
        if (firmwareVersion != null) editor.putString(KEY_FIRMWARE, firmwareVersion)
        editor.apply()
    }

    /** Cached short code (e.g. `"1524"`) to discovered 128-bit UUID mapping. */
    fun resolvedUuids(): Map<String, String> {
        val raw = preferences.getString(KEY_RESOLVED_UUIDS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.getString(key) }
        }.getOrDefault(emptyMap())
    }

    fun rememberResolvedUuids(resolved: Map<String, String>) {
        if (resolved.isEmpty()) return
        preferences.edit()
            .putString(KEY_RESOLVED_UUIDS, JSONObject(resolved).toString())
            .apply()
    }

    /** Drops every remembered fact about the housing. Exposed for a diagnostics reset action. */
    fun forget() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "housing_link"
        const val KEY_ADDRESS = "last_address"
        const val KEY_SERIAL = "last_serial"
        const val KEY_FIRMWARE = "last_firmware"
        const val KEY_RESOLVED_UUIDS = "resolved_uuids"
    }
}
