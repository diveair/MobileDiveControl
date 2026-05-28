package com.mobiledivecontrol.theme

import androidx.compose.ui.graphics.Color

/**
 * DiveControl color palette — optimized for underwater visibility.
 *
 * True black background saves OLED battery and maximizes contrast
 * through the housing port. Cyan is chosen as the primary accent
 * because it's the most visible color at depth (blue-green light
 * penetrates water furthest).
 */
object DiveColors {
    // Background
    val DeepBlack = Color(0xFF000000)
    val SurfaceCard = Color(0xFF0D1117)
    val SurfaceElevated = Color(0xFF161B22)
    val SurfaceBorder = Color(0xFF21262D)

    // Primary — Cyan/Teal (most visible underwater)
    val DiveCyan = Color(0xFF00E5FF)
    val DiveCyanDim = Color(0xFF0097A7)
    val DiveCyanGlow = Color(0x4000E5FF)
    val DiveTeal = Color(0xFF00BFA5)

    // Accent — Blue
    val DeepBlue = Color(0xFF2979FF)
    val OceanBlue = Color(0xFF0D47A1)
    val CoolBlue = Color(0xFF448AFF)

    // Status
    val Success = Color(0xFF00E676)
    val SuccessDim = Color(0xFF1B5E20)
    val Warning = Color(0xFFFFB300)
    val WarningDim = Color(0xFF6D4C00)
    val Critical = Color(0xFFFF1744)
    val CriticalDim = Color(0xFF7F0000)

    // Text
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val TextMuted = Color(0xFF484F58)

    // Battery color based on level
    fun batteryColor(percent: Int): Color = when {
        percent <= 10 -> Critical
        percent <= 20 -> Warning
        percent <= 50 -> DiveCyan
        else -> Success
    }

    // Temperature color
    fun temperatureColor(celsius: Double): Color = when {
        celsius < 10.0 -> DeepBlue
        celsius < 20.0 -> DiveCyan
        celsius < 30.0 -> Success
        else -> Warning
    }

    // Seal state color
    fun sealColor(passed: Boolean?, failed: Boolean?): Color = when {
        passed == true -> Success
        failed == true -> Critical
        else -> TextSecondary
    }
}
