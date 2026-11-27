package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.BleTransport
import com.mobiledivecontrol.core.HousingCharacteristic

/**
 * Android-side extension of the platform-agnostic [BleTransport] contract.
 *
 * The core interface describes a happy-path request/response link. A real radio also has
 * to report the discovered characteristics and transport-level disconnect cause.
 */
interface HousingTransport : BleTransport {
    fun setStandby(enabled: Boolean) {}

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
 * A peer-initiated GATT close does not identify a power-off.
 */
enum class DisconnectCause {
    LinkLoss,
    RemoteClosed,
    LocalClosed,
    Error,
}
