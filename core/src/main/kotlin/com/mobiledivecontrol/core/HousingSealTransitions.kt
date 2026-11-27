package com.mobiledivecontrol.core

/**
 * Transition candidates for validated observations. Connection and sensor validity checks,
 * incident overrides, and acknowledgment are applied by the caller.
 */
object HousingSealTransitions {
    enum class Location { Surface, Underwater }
    enum class Vacuum { Stable, Leaking, None }

    data class Observation(val location: Location, val vacuum: Vacuum)

    /** These IDs describe historical observations and carry no live power assertion. */
    enum class Snapshot(val observation: Observation) {
        S01(Observation(Location.Surface, Vacuum.Stable)),
        S02(Observation(Location.Surface, Vacuum.Leaking)),
        S03(Observation(Location.Surface, Vacuum.None)),
        S04(Observation(Location.Underwater, Vacuum.Stable)),
        S05(Observation(Location.Underwater, Vacuum.Leaking)),
        S06(Observation(Location.Underwater, Vacuum.None)),
    }

    enum class Banner {
        B00, B01, B03, B04, B05, B06, B07, B08, B09, B10, B11, B12, B13, B14,
        B15, B16, B17, B18, B19, Cap, Pumping, Reached, Release, ConfirmRelease, NoSuction, NotBuilding, Status, Uncertain,
    }

    data class Disconnect(val snapshot: Snapshot, val banner: Banner = Banner.B01)

    private val snapshots = Snapshot.entries.associateBy(Snapshot::observation)

    fun disconnected(lastValidated: Observation): Disconnect =
        Disconnect(snapshots.getValue(lastValidated))

    fun reconnect(previous: Snapshot, current: Observation): Banner =
        reconnect(previous.observation, current)

    private val surface = arrayOf(
        arrayOf(Banner.B00, Banner.B04, Banner.B05),
        arrayOf(Banner.B04, Banner.B04, Banner.B05),
        arrayOf(Banner.B00, Banner.B04, Banner.B03),
    )

    private val enteringWater = arrayOf(
        arrayOf(Banner.B00, Banner.B06, Banner.B07),
        arrayOf(Banner.B06, Banner.B06, Banner.B07),
        arrayOf(Banner.B08, Banner.B08, Banner.B10),
    )

    private val resurfacing = arrayOf(
        arrayOf(Banner.B00, Banner.B13, Banner.B14),
        arrayOf(Banner.B14, Banner.B13, Banner.B14),
        arrayOf(Banner.B09, Banner.B09, Banner.B09),
    )

    private val underwater = arrayOf(
        arrayOf(Banner.B00, Banner.B06, Banner.B07),
        arrayOf(Banner.B06, Banner.B06, Banner.B07),
        arrayOf(Banner.B08, Banner.B08, Banner.B08),
    )

    private fun reconnect(previous: Observation, current: Observation): Banner {
        val matrix = when (previous.location) {
            Location.Surface -> if (current.location == Location.Surface) surface else enteringWater
            Location.Underwater -> if (current.location == Location.Surface) resurfacing else underwater
        }
        return matrix[previous.vacuum.ordinal][current.vacuum.ordinal]
    }

    fun connected(previous: Observation, current: Observation): Banner {
        if (previous.location == current.location) return reconnect(previous, current)
        if (current.location == Location.Underwater) {
            return when (current.vacuum) {
                Vacuum.Stable -> Banner.B00
                Vacuum.Leaking -> Banner.B06
                Vacuum.None -> Banner.B10
            }
        }
        // The first valid surface reading may expose a leak that had not been observed underwater.
        if (previous.vacuum == Vacuum.Stable && current.vacuum == Vacuum.Leaking) return Banner.B04
        return reconnect(previous, current)
    }
}
