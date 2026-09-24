package com.mobiledivecontrol

import android.app.Application
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.mobiledivecontrol.platform.ble.PressurePacketRecorder
import com.mobiledivecontrol.platform.ble.AndroidBleTransport
import com.mobiledivecontrol.platform.ble.HousingLink
import com.mobiledivecontrol.platform.ble.HousingStore

/**
 * Process-scoped owner of the housing link.
 *
 * The link outlives every activity and view model on purpose. A configuration change, a screen
 * rotation or a view model recreation must not drop the GATT connection while the phone is sealed
 * inside the housing, because nothing can reconnect it by hand at depth.
 *
 * The transport is built through a factory lambda rather than constructed here so the link can be
 * driven by a fake transport in a JVM test without an Android radio.
 */
class DiveControlApp : Application() {

    @Volatile private var pressureRecorder: PressurePacketRecorder? = null

    /** Diagnostics owns capture; opening it starts a new marked segment, leaving it stops. */
    fun setPressureCapture(active: Boolean, surfaceBaselineKpa: Double? = null) {
        if (active && pressureRecorder == null) {
            pressureRecorder = PressurePacketRecorder(
                File(getExternalFilesDir(null) ?: filesDir, "pressure-logs"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                onFailure = { Log.w("DiveControl", "Pressure packet logging stopped", it) },
            )
        }
        pressureRecorder?.setEnabled(active, surfaceBaselineKpa)
    }

    val housingStore: HousingStore by lazy { HousingStore(this) }

    val housingLink: HousingLink by lazy {
        HousingLink(
            context = this,
            store = housingStore,
            onDiagnosticEvent = { pressureRecorder?.record(it) },
            transportFactory = { preferredAddress -> AndroidBleTransport(this, preferredAddress) },
        )
    }
}
