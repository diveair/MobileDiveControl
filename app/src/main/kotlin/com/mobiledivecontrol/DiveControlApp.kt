package com.mobiledivecontrol

import android.app.Application
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

    
    val housingStore: HousingStore by lazy { HousingStore(this) }

    val housingLink: HousingLink by lazy {
        HousingLink(
            context = this,
            store = housingStore,
            transportFactory = { preferredAddress -> AndroidBleTransport(this, preferredAddress) },
        )
    }
}
