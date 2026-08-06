package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.BleTransport
import com.mobiledivecontrol.core.HousingCharacteristic

/**
 * Android-side extension of the platform-agnostic [BleTransport] contract.
 *
 * The core interface describes a happy-path request/response link. A real radio also has
 * to answer two questions the core cannot: which characteristics this particular housing
 * actually exposes, and why the link went away. Both drive product behaviour — an absent
 * characteristic disables a feature rather than failing the connection, and a peer-initiated
 * close means the diver held OK to power the housing off rather than swimming out of range.
 */
interface HousingTransport : BleTransport {

    /**
     * Characteristics resolved on the connected device.
     *
     * Populated by service discovery. Membership is decided by the 16-bit short code
     * embedded in each discovered UUID, not by a hardcoded 128-bit value — the vendor
     * base UUID in the protocol document is malformed and cannot be relied on.
     */
    val availableCharacteristics: Set<HousingCharacteristic>

    /**
     * Human-readable dump of the discovered GATT tree, one line per service and
     * characteristic. Recorded once per connection so a single session with real hardware
     * settles the UUID question permanently.
     */
    val discoveryReport: List<String>

    /** Registers the listener notified when the link drops without a local request. */
    fun setDisconnectListener(listener: ((DisconnectCause) -> Unit)?)
}

/**
 * Why a connection ended.
 *
 * [RemoteClosed] is distinct from [LinkLoss] because the housing closes the connection
 * itself when the diver long-presses OK to power it down. Presenting that as
 * "reconnecting…" would be a lie the diver cannot act on.
 */
enum class DisconnectCause {
    LinkLoss,
    RemoteClosed,
    LocalClosed,
    Error,
}
