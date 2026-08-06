package com.mobiledivecontrol.platform.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime prerequisites for talking to the housing radio.
 *
 * The permission set is version-dependent in a way that is easy to get wrong: API 31 split BLE
 * out of the location permission group, and on API 30 and below a scan genuinely returns nothing
 * without `ACCESS_FINE_LOCATION` even though no location is wanted. Both spellings are kept here
 * so the activity, the link supervisor and the service all ask the same question.
 *
 * Every accessor is defensive. A missing permission surfaces as "not ready" rather than as a
 * `SecurityException` on a binder thread, because the link supervisor has to survive it and keep
 * polling — the diver may grant the permission after the app is already running.
 */
object BlePermissions {

    /** Permissions that must be granted before any scan or connect is attempted. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    /** Subset of [required] that the user has not granted yet. */
    fun missing(context: Context): List<String> = required().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /** True when the device has a Bluetooth adapter and the user has switched it on. */
    fun isAdapterEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.isEnabled == true
    }.getOrDefault(false)

    /**
     * A single human-readable reason the radio cannot be used right now, or `null` when it can.
     *
     * Returned verbatim to the diver: "Housing: Disconnected" without a cause is the kind of
     * uncertainty the product is not allowed to present.
     */
    fun blockingReason(context: Context): String? {
        val missing = missing(context)
        if (missing.isNotEmpty()) {
            return "Bluetooth permission not granted. Housing control unavailable."
        }
        if (!isAdapterEnabled(context)) {
            return "Bluetooth is switched off. Housing control unavailable."
        }
        return null
    }
}
