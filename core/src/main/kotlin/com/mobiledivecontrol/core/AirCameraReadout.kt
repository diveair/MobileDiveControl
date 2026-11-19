package com.mobiledivecontrol.core

import kotlin.math.floor

enum class AirHudMode(val label: String) {
    BottomTime("Bottom time remaining"),
    LastPressure("Last tank pressure"),
    TimeAndLast("Pulse: time + last pressure"),
    LivePressure("Live receiver pressure"),
    TimeAndLive("Pulse: time + live pressure"),
    Off("None / Off")
}

data class AirCameraReadout(val text: String, val unavailable: Boolean = false)

/** One clearly identified value at a time. A historical reading is never presented as live. */
fun airCameraReadout(air: AirAlertState, dive: DiveProfileState, nowMs: Long): AirCameraReadout? {
    if (air.sharedAir || !airTracksPressure(air.settings)) return null
    val second = (nowMs / 4_000) % 2 == 1L
    val mode = when (air.settings.hudMode) {
        AirHudMode.TimeAndLast -> if (second) AirHudMode.LastPressure else AirHudMode.BottomTime
        AirHudMode.TimeAndLive -> if (second) AirHudMode.LivePressure else AirHudMode.BottomTime
        else -> air.settings.hudMode
    }
    val unit = air.settings.unit
    return when (mode) {
        AirHudMode.Off -> null
        AirHudMode.BottomTime -> {
            val minutes = (if (dive.active != null && !dive.atSurface) AirAlertEngine.estimate(air, dive, nowMs)?.bottomMinutes else null)
                ?: return null
            AirCameraReadout("EST. BOTTOM TIME · ${floor(minutes).toInt()} MIN")
        }
        AirHudMode.LastPressure -> {
            val last = air.lastManualObservation ?: air.observation?.takeUnless { it.receiver }
            if (last == null) AirCameraReadout("LAST TANK · NO READING") else {
                val minutes = (air.elapsedMs - last.elapsedMs).coerceAtLeast(0) / 60_000
                val age = if (minutes == 0L) "<1 MIN AGO" else "$minutes MIN AGO"
                AirCameraReadout("LAST TANK · ${unit.display(last.bar)} ${unit.label} · $age")
            }
        }
        AirHudMode.LivePressure -> {
            if (air.manualTankOverride) return air.lastManualObservation?.let {
                AirCameraReadout("TANK " + air.tankNumber + " · " + unit.display(it.bar) + " " + unit.label + " · MANUAL")
            }
            val live = AirAlertEngine.liveBar(air, nowMs)
            if (live == null) AirCameraReadout("LIVE AIR · SIGNAL UNAVAILABLE", unavailable = true)
            else AirCameraReadout("LIVE · ${unit.display(live)} ${unit.label}")
        }
        else -> error("Pulse modes must resolve to a single readout")
    }
}
