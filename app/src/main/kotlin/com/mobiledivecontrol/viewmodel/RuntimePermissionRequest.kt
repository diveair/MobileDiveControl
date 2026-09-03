package com.mobiledivecontrol.viewmodel

/** Android capabilities that can become necessary after first-run onboarding has finished. */
enum class RuntimePermissionNeed {
    Camera,
    Microphone,
    Bluetooth,
    Notifications,
}

/**
 * A one-shot request from a feature path to [com.mobiledivecontrol.MainActivity].
 *
 * [id] deliberately changes even when [needs] does not. A diver who rejects a prompt must be
 * able to try the feature again and receive a fresh native prompt (or Android's app-permission
 * Settings fallback once the OS has made the denial permanent).
 */
data class RuntimePermissionRequest(
    val id: Long,
    val needs: Set<RuntimePermissionNeed>,
)
