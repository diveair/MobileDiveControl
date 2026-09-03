package com.mobiledivecontrol.testing

import androidx.compose.runtime.mutableStateOf

/** Visible proof of the exact option the debug stress runner is exercising. */
data class CameraStressVisualSnapshot(
    val runId: String,
    val sequence: Int,
    val mode: String,
    val setting: String,
    val requested: String,
    val actual: String = "",
    val status: String = "TESTING",
)

object CameraStressVisualStatus {
    val current = mutableStateOf<CameraStressVisualSnapshot?>(null)

    fun publish(snapshot: CameraStressVisualSnapshot?) {
        current.value = snapshot
    }
}
