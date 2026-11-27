package com.mobiledivecontrol

import android.content.Context
import com.mobiledivecontrol.core.BleConnectionState
import com.mobiledivecontrol.core.HousingButtonEvent
import java.io.File

/**
 * Records completion of the one-time permissions/housing walkthrough.
 *
 * The marker deliberately lives in [Context.getNoBackupFilesDir]. Camera preferences may be
 * restored after reinstall, but an Android backup must never make a genuinely fresh install skip
 * its safety-critical housing introduction.
 */
class FirstRunOnboardingStore internal constructor(
    private val marker: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, COMPLETION_MARKER),
    )

    fun isComplete(): Boolean = marker.isFile

    /** Show the permission-first welcome on a new install or whenever Android still requires a grant. */
    fun beginLaunch(permissionsGranted: Boolean): Boolean {
        if (isComplete()) return !permissionsGranted // Older builds may have only the completion marker.
        val firstOpen = try {
            File(marker.parentFile, "first-launch-started").createNewFile()
        } catch (_: java.io.IOException) {
            // If storage is unavailable, still let the user complete first-run setup.
            true
        }
        return firstOpen || !permissionsGranted
    }

    fun markComplete() {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("complete", Charsets.UTF_8)
        }
    }

    private companion object {
        const val COMPLETION_MARKER = "first-run-onboarding-v1-complete"
    }
}

enum class FirstLaunchSealStep {
    WaitingForDialogs, OnHousingAnimation, PowerOn, Connecting, Connected, Done;

    fun permissionWindowChanged(ready: Boolean): FirstLaunchSealStep = when (this) {
        WaitingForDialogs, OnHousingAnimation -> if (ready) OnHousingAnimation else WaitingForDialogs
        else -> this
    }

    fun afterPrompt(link: BleConnectionState): FirstLaunchSealStep = when (this) {
        OnHousingAnimation -> PowerOn
        Connecting -> if (link == BleConnectionState.Ready) Connected else this
        Connected -> if (link == BleConnectionState.Ready) Done else PowerOn
        else -> this
    }

    fun onButton(event: HousingButtonEvent): FirstLaunchSealStep = when {
        this == OnHousingAnimation && event.confirmsSealInstruction() -> PowerOn
        this == PowerOn && event == HousingButtonEvent.Shutter -> Connecting
        else -> this
    }

    /** Scanning alone cannot distinguish a powered-off housing from one outside radio range. */
    fun linkChanged(link: BleConnectionState): FirstLaunchSealStep = when (link) {
        BleConnectionState.Connecting, BleConnectionState.DiscoveringServices,
        BleConnectionState.Subscribing, BleConnectionState.Ready -> if (this == PowerOn) Connecting else this
        else -> if (this == Connecting || this == Connected) PowerOn else this
    }
}

private fun HousingButtonEvent.confirmsSealInstruction(): Boolean = when (this) {
    HousingButtonEvent.Up, HousingButtonEvent.Down, HousingButtonEvent.Left,
    HousingButtonEvent.Right, HousingButtonEvent.Ok -> true
    else -> false
}
