package com.mobiledivecontrol.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

internal class CameraMenuBounds {
    var trayHeightPx by mutableIntStateOf(0)
    var optionsHeightPx by mutableIntStateOf(0)
}

internal val LocalCameraMenuBounds = staticCompositionLocalOf<CameraMenuBounds?> { null }
