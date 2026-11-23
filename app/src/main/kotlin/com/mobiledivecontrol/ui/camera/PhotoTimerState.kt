package com.mobiledivecontrol.ui.camera

import androidx.compose.runtime.mutableIntStateOf

internal object PhotoTimerState {
    val remainingSeconds = mutableIntStateOf(0)
}
