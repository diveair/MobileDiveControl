package com.mobiledivecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale

internal enum class PermissionDialogChoice {
    Accept,
    Reject,
}

/** Pure classification shared by the service and JVM policy tests. */
internal fun permissionDialogChoice(viewId: String?, text: String?): PermissionDialogChoice? {
    val id = viewId.orEmpty().lowercase(Locale.ROOT)
    val label = text.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
    if (id.contains("permission_deny") || id.endsWith("button_negative") ||
        label in setOf(
            "don't allow", "deny", "no", "cancel", "not now", "reject", "disable",
        )
    ) {
        return PermissionDialogChoice.Reject
    }
    if (id.contains("permission_allow") || id.endsWith("button_positive") ||
        label in setOf(
            "allow", "while using the app", "only this time", "yes", "ok", "turn on",
            "enable", "continue", "accept",
        )
    ) {
        return PermissionDialogChoice.Accept
    }
    return null
}

internal fun isSystemPermissionControllerPackage(packageName: String?): Boolean =
    packageName in setOf(
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.systemui",
    )

/**
 * Process-local handoff from the BLE input path to the opt-in accessibility service.
 *
 * The bridge reports `false` unless a recognisable system permission prompt is currently active,
 * so ordinary housing input continues through ControlCore unchanged.
 */
object PermissionDialogHousingBridge {
    @Volatile
    private var connectedService: PermissionDialogHousingService? = null

    internal fun connect(service: PermissionDialogHousingService) {
        connectedService = service
    }

    internal fun disconnect(service: PermissionDialogHousingService) {
        if (connectedService === service) connectedService = null
    }

    fun handleButtonPayload(payload: ByteArray): Boolean {
        if (payload.size != 1) return false
        return connectedService?.handleHousingButton(payload[0].toInt() and 0xff) == true
    }

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PermissionDialogHousingService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { component ->
            component.equals(expected, ignoreCase = true) ||
                ComponentName.unflattenFromString(component) == ComponentName(
                    context,
                    PermissionDialogHousingService::class.java,
                )
        }
    }
}

/**
 * Lets the physical housing operate only Android/Samsung permission prompts.
 *
 * No gesture injection is requested. Directions move accessibility focus over recognised
 * Allow/Deny buttons, OK or Shutter invokes the focused button, and Back/Safety explicitly takes
 * the reject path. Any unrecognised system window is refused rather than treated as a generic UI.
 */
class PermissionDialogHousingService : AccessibilityService() {
    private data class Target(
        val node: AccessibilityNodeInfo,
        val choice: PermissionDialogChoice,
        val label: String,
        val bounds: Rect,
    )

    private var selectedKey: String? = null
    private var activeDialogSignature: String? = null
    private var dialogOpenedAtMs = 0L
    private var lastInputAtMs = 0L
    private var toast: Toast? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        PermissionDialogHousingBridge.connect(this)
    }

    override fun onDestroy() {
        PermissionDialogHousingBridge.disconnect(this)
        toast?.cancel()
        toast = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isSystemPermissionControllerPackage(event?.packageName?.toString())) return
        val targets = currentTargets()
        if (targets.isEmpty()) {
            selectedKey = null
            activeDialogSignature = null
            return
        }
        updateDialogIdentity(targets)
        val focused = targets.firstOrNull { it.node.isAccessibilityFocused }
        if (focused != null) selectedKey = key(focused)
    }

    internal fun handleHousingButton(wireByte: Int): Boolean {
        val targets = currentTargets()
        if (targets.isEmpty()) {
            selectedKey = null
            activeDialogSignature = null
            return false
        }

        val now = android.os.SystemClock.elapsedRealtime()
        updateDialogIdentity(targets, now)
        // A button press often launches the permission prompt and then repeats at ~15 Hz while
        // the diver is still holding it. Never let that initiating repeat silently accept a
        // permission; the dialog must be visible for a beat before a new press can act on it.
        if (now - dialogOpenedAtMs < DIALOG_ARM_DELAY_MS) return true
        val isAction = wireByte in setOf(WIRE_OK, WIRE_SHUTTER, WIRE_BACK, WIRE_ZOOM_OUT)
        val minimumGap = if (isAction) ACTION_DEBOUNCE_MS else NAVIGATION_DEBOUNCE_MS
        if (now - lastInputAtMs < minimumGap) return true
        lastInputAtMs = now

        val currentIndex = targets.indexOfFirst { key(it) == selectedKey }
            .takeIf { it >= 0 }
            ?: targets.indexOfFirst { it.node.isAccessibilityFocused }
                .takeIf { it >= 0 }
            ?: 0

        return when (wireByte) {
            WIRE_UP, WIRE_LEFT -> {
                focus(targets[(currentIndex - 1 + targets.size) % targets.size])
                true
            }
            WIRE_DOWN, WIRE_RIGHT -> {
                focus(targets[(currentIndex + 1) % targets.size])
                true
            }
            WIRE_OK, WIRE_SHUTTER -> {
                click(targets[currentIndex])
                true
            }
            WIRE_BACK, WIRE_ZOOM_OUT -> {
                targets.firstOrNull { it.choice == PermissionDialogChoice.Reject }
                    ?.let(::click)
                    ?: performGlobalAction(GLOBAL_ACTION_BACK)
                true
            }
            else -> true // A permission dialog owns input until the diver resolves it.
        }
    }

    private fun currentTargets(): List<Target> {
        val root = rootInActiveWindow ?: return emptyList()
        if (!isSystemPermissionControllerPackage(root.packageName?.toString())) return emptyList()

        val targets = mutableListOf<Target>()
        fun visit(node: AccessibilityNodeInfo) {
            val choice = permissionDialogChoice(
                node.viewIdResourceName,
                node.text?.toString() ?: node.contentDescription?.toString(),
            )
            if (node.isVisibleToUser && node.isClickable && choice != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                targets += Target(
                    node = node,
                    choice = choice,
                    label = node.text?.toString()
                        ?: node.contentDescription?.toString()
                        ?: if (choice == PermissionDialogChoice.Accept) "Allow" else "Don't allow",
                    bounds = bounds,
                )
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return targets
            .distinctBy(::key)
            .sortedWith(compareBy<Target>({ it.bounds.top }, { it.bounds.left }))
    }

    private fun focus(target: Target) {
        selectedKey = key(target)
        target.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        announceSelection(target)
        Log.i(TAG, "Housing focused permission choice '${target.label}'")
    }

    private fun click(target: Target) {
        selectedKey = key(target)
        Log.i(TAG, "Housing clicked permission choice '${target.label}' (${target.choice})")
        if (!target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            target.node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun announceSelection(target: Target) {
        toast?.cancel()
        toast = Toast.makeText(
            this,
            "Housing selection: ${target.label}",
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun key(target: Target): String =
        "${target.node.viewIdResourceName}|${target.label}|${target.bounds.flattenToString()}"

    private fun updateDialogIdentity(
        targets: List<Target>,
        now: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        val signature = targets.joinToString(separator = ";", transform = ::key)
        if (signature == activeDialogSignature) return
        activeDialogSignature = signature
        dialogOpenedAtMs = now
        selectedKey = null
        Log.i(TAG, "System permission dialog armed for housing control (${targets.size} choices)")
    }

    private companion object {
        const val WIRE_RIGHT = 0x10
        const val WIRE_SHUTTER = 0x20
        const val WIRE_UP = 0x30
        const val WIRE_LEFT = 0x40
        const val WIRE_OK = 0x50
        const val WIRE_BACK = 0x60
        const val WIRE_DOWN = 0x61
        const val WIRE_ZOOM_OUT = 0x80
        const val NAVIGATION_DEBOUNCE_MS = 120L
        const val ACTION_DEBOUNCE_MS = 350L
        const val DIALOG_ARM_DELAY_MS = 650L
        const val TAG = "HousingPermission"
    }
}
