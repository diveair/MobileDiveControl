package com.mobiledivecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PermissionDialogChoice {
    Accept,
    Reject,
}

private enum class HousingSystemTargetKind {
    NavigateBack,
    MoreOptions,
    PermissionAccept,
    PermissionReject,
    LocationMaster,
    AppPermissions,
    PermissionApp,
    PermissionPageOption,
}

private enum class HousingSystemSurface {
    RuntimePermissionPrompt,
    LocationSettings,
    PermissionApps,
    DiveControlAppInfo,
    DiveControlNotificationPermission,
    DiveControlAppPermissions,
    DiveControlPermissionEditor,
    DiveControlAllPermissions,
    PermissionOverflowMenu,
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
            "don't allow", "don't select more", "deny", "no", "cancel", "not now",
            "reject", "disable",
        )
    ) {
        return PermissionDialogChoice.Reject
    }
    if (id.contains("permission_allow") || id.endsWith("button_positive") ||
        label in setOf(
            "allow", "while using the app", "allow only while using the app",
            "only this time", "allow limited access", "allow all", "yes", "ok", "turn on",
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

internal fun isHousingControlledSystemPackage(packageName: String?): Boolean =
    isSystemPermissionControllerPackage(packageName) || packageName == "com.android.settings"

/** Restricts generic permission-page navigation to pages owned by this application. */
internal fun isDiveControlPermissionPage(
    pageTitle: String?,
    entityLabel: String?,
    appLabel: String,
): Boolean {
    if (!entityLabel.equals(appLabel, ignoreCase = true)) return false
    val title = pageTitle.orEmpty().trim().lowercase(Locale.ROOT)
    return title == "app permissions" ||
        title == "all permissions" ||
        title.endsWith(" permission")
}

/**
 * Process-local handoff from the BLE input path to the opt-in accessibility service.
 *
 * The bridge reports `false` unless a recognisable system permission or DiveControl-owned
 * permission Settings surface is active, so ordinary housing input continues unchanged.
 */
object PermissionDialogHousingBridge {
    @Volatile
    private var connectedService: PermissionDialogHousingService? = null
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected = _serviceConnected.asStateFlow()

    internal fun connect(service: PermissionDialogHousingService) {
        connectedService = service
        _serviceConnected.value = true
    }

    internal fun disconnect(service: PermissionDialogHousingService) {
        if (connectedService === service) {
            connectedService = null
            _serviceConnected.value = false
        }
    }

    fun handleButtonPayload(payload: ByteArray): Boolean {
        if (payload.size != 1) return false
        return connectedService?.handleHousingButton(payload[0].toInt() and 0xff) == true
    }

    /** Marks a Settings launch as app-owned before Android replaces DiveControl's window. */
    fun armPermissionSettingsFlow() {
        connectedService?.armPermissionSettingsFlow()
    }

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PermissionDialogHousingService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { service ->
            val info = service.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(info.packageName, info.name) == expected
        }
    }
}

/**
 * Lets the physical housing operate Android/Samsung permission prompts and the page-scoped
 * Location/App-permission Settings path opened by DiveControl.
 *
 * No gesture injection is requested. Directions move accessibility focus over recognised
 * Allow/Deny buttons or safe Location destinations, OK or Shutter invokes the focused target,
 * and Back/Safety rejects a prompt or navigates back. Any unrecognised system window is refused.
 */
class PermissionDialogHousingService : AccessibilityService() {
    private data class Target(
        val node: AccessibilityNodeInfo,
        val kind: HousingSystemTargetKind,
        val label: String,
        val bounds: Rect,
        val checked: Boolean? = null,
    )

    private data class Snapshot(
        val surface: HousingSystemSurface,
        val identity: String,
        val targets: List<Target>,
        val scrollable: AccessibilityNodeInfo? = null,
    )

    private var selectedKey: String? = null
    private var activeDialogSignature: String? = null
    private var visibleTargetsSignature: String? = null
    private var diveControlPermissionFlowActive = false
    private var permissionAppsPageIdentity: String? = null
    private var dialogOpenedAtMs = 0L
    private var lastInputAtMs = 0L
    private var toast: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        PermissionDialogHousingBridge.connect(this)
    }

    override fun onDestroy() {
        PermissionDialogHousingBridge.disconnect(this)
        mainHandler.removeCallbacksAndMessages(null)
        toast?.cancel()
        toast = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    internal fun armPermissionSettingsFlow() {
        diveControlPermissionFlowActive = true
        Log.i(TAG, "DiveControl permission Settings flow armed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isHousingControlledSystemPackage(event?.packageName?.toString())) return
        val snapshot = currentSnapshot()
        if (snapshot == null || snapshot.targets.isEmpty()) {
            selectedKey = null
            activeDialogSignature = null
            visibleTargetsSignature = null
            return
        }
        updateDialogIdentity(snapshot)
        logVisibleTargets(snapshot)
        val focused = snapshot.targets.firstOrNull { it.node.isAccessibilityFocused }
        if (focused != null) selectedKey = key(focused)
    }

    internal fun handleHousingButton(wireByte: Int): Boolean {
        val snapshot = currentSnapshot()
        if (snapshot == null || snapshot.targets.isEmpty()) {
            selectedKey = null
            activeDialogSignature = null
            visibleTargetsSignature = null
            return false
        }
        val targets = snapshot.targets

        val now = android.os.SystemClock.elapsedRealtime()
        updateDialogIdentity(snapshot, now)
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
            WIRE_LEFT -> {
                if (targets[currentIndex].kind == HousingSystemTargetKind.LocationMaster) {
                    setLocationMaster(targets[currentIndex], enabled = false)
                } else {
                    navigate(snapshot, currentIndex, forward = false)
                }
                true
            }
            WIRE_RIGHT -> {
                if (targets[currentIndex].kind == HousingSystemTargetKind.LocationMaster) {
                    setLocationMaster(targets[currentIndex], enabled = true)
                } else {
                    navigate(snapshot, currentIndex, forward = true)
                }
                true
            }
            WIRE_UP -> {
                navigate(snapshot, currentIndex, forward = false)
                true
            }
            WIRE_DOWN -> {
                navigate(snapshot, currentIndex, forward = true)
                true
            }
            WIRE_OK, WIRE_SHUTTER -> {
                click(targets[currentIndex])
                true
            }
            WIRE_BACK, WIRE_ZOOM_OUT -> {
                if (snapshot.surface == HousingSystemSurface.RuntimePermissionPrompt) {
                    targets.firstOrNull { it.kind == HousingSystemTargetKind.PermissionReject }
                        ?.let(::click)
                        ?: performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    // "Don't allow" is a selectable row inside a permission editor. Housing
                    // Back must leave the page, never silently change the permission grant.
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                true
            }
            else -> true // A permission dialog owns input until the diver resolves it.
        }
    }

    private fun currentSnapshot(): Snapshot? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString()
        if (!isHousingControlledSystemPackage(packageName)) {
            diveControlPermissionFlowActive = false
            permissionAppsPageIdentity = null
            return null
        }

        val nodes = flatten(root)
        val snapshot = when (packageName) {
            "com.android.settings" -> settingsSnapshot(nodes)
            else -> permissionControllerSnapshot(nodes)
        } ?: return null
        return snapshot.copy(
            targets = snapshot.targets
                .distinctBy(::key)
                .sortedWith(compareBy<Target>({ it.bounds.top }, { it.bounds.left })),
        )
    }

    private fun permissionControllerSnapshot(nodes: List<AccessibilityNodeInfo>): Snapshot? {
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val pageTitle = toolbarTitle(nodes)
        val entityLabel = nodes.firstOrNull {
            it.viewIdResourceName.orEmpty().endsWith(":id/entity_header_title")
        }?.let(::nodeLabel)
        val scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable }

        if (entityLabel != null &&
            pageTitle.orEmpty().trim().endsWith(" permission", ignoreCase = true) &&
            !entityLabel.equals(appLabel, ignoreCase = true) &&
            !isPermissionAppsPage(nodes)
        ) {
            diveControlPermissionFlowActive = false
            return null
        }

        val visiblePermissionAppRows = nodes.any { candidate ->
            candidate.viewIdResourceName == "android:id/title" &&
                !candidate.contentDescription.isNullOrBlank() &&
                nearestClickable(candidate) != null
        }
        val permissionAppsIdentity = "PermissionApps:${pageTitle.orEmpty()}"
        if (isPermissionAppsPage(nodes) ||
            (permissionAppsPageIdentity == permissionAppsIdentity && visiblePermissionAppRows)
        ) {
            permissionAppsPageIdentity = permissionAppsIdentity
            val targets = buildList {
                addAll(actionableTargets(nodes).filter {
                    it.kind == HousingSystemTargetKind.NavigateBack ||
                        it.kind == HousingSystemTargetKind.MoreOptions
                })
                addAll(nodes.mapNotNull { candidate ->
                    val label = nodeLabel(candidate) ?: return@mapNotNull null
                    // Samsung's app rows expose their app name as both title and content
                    // description. Section headers also use android:id/title, but have no
                    // content description and must not become false navigation targets.
                    if (candidate.viewIdResourceName != "android:id/title" ||
                        candidate.contentDescription.isNullOrBlank()
                    ) return@mapNotNull null
                    val clickable = nearestClickable(candidate) ?: return@mapNotNull null
                    target(clickable, HousingSystemTargetKind.PermissionApp, label)
                })
            }
            return Snapshot(
                surface = HousingSystemSurface.PermissionApps,
                identity = permissionAppsIdentity,
                targets = targets,
                scrollable = scrollable,
            )
        }

        if (isDiveControlPermissionPage(pageTitle, entityLabel, appLabel)) {
            diveControlPermissionFlowActive = true
            val surface = when {
                pageTitle.equals("App permissions", true) -> {
                    HousingSystemSurface.DiveControlAppPermissions
                }
                pageTitle.equals("All permissions", true) -> {
                    HousingSystemSurface.DiveControlAllPermissions
                }
                else -> HousingSystemSurface.DiveControlPermissionEditor
            }
            return Snapshot(
                surface = surface,
                identity = if (surface == HousingSystemSurface.DiveControlPermissionEditor) {
                    "PermissionEditor:${pageTitle.orEmpty()}"
                } else {
                    "DiveControlPermission:${pageTitle.orEmpty()}"
                },
                targets = actionableTargets(nodes),
                scrollable = scrollable,
            )
        }

        val virtualizedListSurface = when {
            pageTitle.equals("App permissions", true) -> {
                HousingSystemSurface.DiveControlAppPermissions
            }
            pageTitle.equals("All permissions", true) -> {
                HousingSystemSurface.DiveControlAllPermissions
            }
            else -> null
        }
        if (virtualizedListSurface != null && scrollable != null) {
            val targets = actionableTargets(nodes)
            return Snapshot(
                surface = virtualizedListSurface,
                identity = "DiveControlPermission:${pageTitle.orEmpty()}",
                targets = if (diveControlPermissionFlowActive) {
                    targets
                } else {
                    targets.filter { it.kind == HousingSystemTargetKind.NavigateBack }
                },
                scrollable = scrollable,
            )
        }

        val isVirtualizedPermissionEditor =
            pageTitle.orEmpty().trim().endsWith(" permission", ignoreCase = true) &&
                scrollable != null
        if (isVirtualizedPermissionEditor) {
            val targets = actionableTargets(nodes)
            return Snapshot(
                // Even if the entity header has scrolled out of the virtual tree, this remains a
                // full Settings editor rather than a modal runtime prompt. Back must navigate out.
                surface = HousingSystemSurface.DiveControlPermissionEditor,
                identity = "PermissionEditor:${pageTitle.orEmpty()}",
                targets = if (diveControlPermissionFlowActive) {
                    targets
                } else {
                    targets.filter { it.kind == HousingSystemTargetKind.NavigateBack }
                },
                scrollable = scrollable,
            )
        }

        val popupTargets = actionableTargets(nodes).filter {
            it.label.lowercase(Locale.ROOT) in PERMISSION_OVERFLOW_LABELS
        }
        if (popupTargets.isNotEmpty() && entityLabel == null) {
            return Snapshot(
                surface = HousingSystemSurface.PermissionOverflowMenu,
                identity = "PermissionOverflow:${popupTargets.joinToString { it.label }}",
                targets = popupTargets,
            )
        }

        // Runtime dialogs have no app-owned entity header. Once an Allow/Deny action proves
        // this is a permission prompt, expose every labelled clickable control so Samsung's
        // precise/approximate selectors and future permission variants remain reachable.
        val hasRuntimePermissionChoice = nodes.any { candidate ->
            permissionDialogChoice(candidate.viewIdResourceName, nodeLabel(candidate)) != null
        }
        if (hasRuntimePermissionChoice && entityLabel == null) {
            val targets = actionableTargets(nodes)
            return Snapshot(
                surface = HousingSystemSurface.RuntimePermissionPrompt,
                identity = "RuntimePermission:${targets.joinToString { it.label }}",
                targets = targets,
                scrollable = scrollable,
            )
        }

        // Never reinterpret another application's permission editor as DiveControl's surface.
        return null
    }

    private fun settingsSnapshot(nodes: List<AccessibilityNodeInfo>): Snapshot? {
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val pageText = nodes.mapNotNull(::nodeLabel)
        val pageTitle = toolbarTitle(nodes)
        val entityLabel = nodes.firstOrNull {
            it.viewIdResourceName.orEmpty().endsWith(":id/entity_header_title")
        }?.let(::nodeLabel)
        val toolbarBack = actionableTargets(nodes).filter {
            it.kind == HousingSystemTargetKind.NavigateBack
        }
        val switch = nodes.firstOrNull {
            it.viewIdResourceName.orEmpty().endsWith(":id/sesl_switchbar_switch")
        }
        val appPermissionsLabel = nodes.firstOrNull {
            nodeLabel(it).equals("App permissions", true)
        }
        val isLocationPage = switch != null && appPermissionsLabel != null &&
            pageText.any { it.equals("Location", true) }
        if (isLocationPage) {
            val locationSwitch = switch ?: return null
            val appPermissions = appPermissionsLabel ?: return null
            val switchTarget = nearestClickable(locationSwitch) ?: return null
            val permissionsTarget = nearestClickable(appPermissions) ?: return null
            return Snapshot(
                surface = HousingSystemSurface.LocationSettings,
                identity = "LocationSettings",
                targets = toolbarBack + listOf(
                    target(
                        switchTarget,
                        HousingSystemTargetKind.LocationMaster,
                        "Location ${if (locationSwitch.isChecked) "On" else "Off"}",
                        checked = locationSwitch.isChecked,
                    ),
                    target(
                        permissionsTarget,
                        HousingSystemTargetKind.AppPermissions,
                        "App permissions",
                    ),
                ),
                scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable },
            )
        }

        val isDiveControlAppInfo = pageTitle.equals("App info", true) &&
            entityLabel.equals(appLabel, true)
        if (isDiveControlAppInfo) {
            diveControlPermissionFlowActive = true
            val permissions = nodes.firstOrNull { nodeLabel(it).equals("Permissions", true) }
                ?.let(::nearestClickable)
            return Snapshot(
                surface = HousingSystemSurface.DiveControlAppInfo,
                identity = "DiveControlAppInfo",
                targets = toolbarBack + listOfNotNull(
                    permissions?.let {
                        target(
                            it,
                            HousingSystemTargetKind.AppPermissions,
                            "App permissions",
                        )
                    },
                ),
                scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable },
            )
        }

        val isDiveControlNotificationPermission = pageTitle.equals(appLabel, true) &&
            nodes.any {
                it.viewIdResourceName.orEmpty().endsWith(":id/sesl_switchbar_text") &&
                    nodeLabel(it).equals("Allow notifications", true)
            } &&
            nodes.any { it.viewIdResourceName.orEmpty().endsWith(":id/noti_main") }
        if (isDiveControlNotificationPermission) {
            diveControlPermissionFlowActive = true
            return Snapshot(
                surface = HousingSystemSurface.DiveControlNotificationPermission,
                identity = "DiveControlNotificationPermission",
                targets = actionableTargets(nodes),
                scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable },
            )
        }
        return null
    }

    private fun isPermissionAppsPage(nodes: List<AccessibilityNodeInfo>): Boolean {
        val hasPermissionEntity = nodes.any {
            it.viewIdResourceName.orEmpty().endsWith(":id/entity_header_title") &&
                !nodeLabel(it).isNullOrBlank()
        }
        val hasPermissionListHeader = nodes.any {
            it.viewIdResourceName.orEmpty().endsWith(":id/header_text") &&
                nodeLabel(it).orEmpty().contains("permission", ignoreCase = true)
        }
        return hasPermissionEntity && hasPermissionListHeader
    }

    private fun toolbarTitle(nodes: List<AccessibilityNodeInfo>): String? =
        nodes.asSequence()
            .filter { it.className?.toString() == "android.widget.TextView" }
            .mapNotNull { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                nodeLabel(node)?.takeIf { bounds.top < TOOLBAR_BOTTOM_PX }
            }
            .firstOrNull()

    private fun actionableTargets(nodes: List<AccessibilityNodeInfo>): List<Target> =
        nodes.mapNotNull { node ->
            if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable) {
                return@mapNotNull null
            }
            val hasLabelledClickableDescendant = flatten(node).drop(1).any { descendant ->
                descendant.isVisibleToUser && descendant.isEnabled && descendant.isClickable &&
                    actionLabel(descendant) != null
            }
            if (hasLabelledClickableDescendant) return@mapNotNull null
            val label = actionLabel(node) ?: return@mapNotNull null
            val choice = permissionDialogChoice(node.viewIdResourceName, label)
            val kind = when {
                label.equals("Navigate up", true) -> HousingSystemTargetKind.NavigateBack
                label.equals("More options", true) -> HousingSystemTargetKind.MoreOptions
                choice == PermissionDialogChoice.Accept -> HousingSystemTargetKind.PermissionAccept
                choice == PermissionDialogChoice.Reject -> HousingSystemTargetKind.PermissionReject
                label.equals("Permissions", true) || label.equals("App permissions", true) -> {
                    HousingSystemTargetKind.AppPermissions
                }
                else -> HousingSystemTargetKind.PermissionPageOption
            }
            val checked = flatten(node).firstOrNull { it.isCheckable }?.isChecked
            target(node, kind, label, checked)
        }.distinctBy { "${it.label}|${it.bounds.flattenToString()}" }

    private fun actionLabel(node: AccessibilityNodeInfo): String? {
        nodeLabel(node)?.let { return it }
        val descendants = flatten(node).drop(1)
        return descendants.firstOrNull {
            it.viewIdResourceName == "android:id/title" && !nodeLabel(it).isNullOrBlank()
        }?.let(::nodeLabel)
            ?: descendants.firstOrNull {
                it.viewIdResourceName == "android:id/summary" && !nodeLabel(it).isNullOrBlank()
            }?.let(::nodeLabel)
            ?: descendants.firstNotNullOfOrNull(::nodeLabel)
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf(String::isNotBlank)
            ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isVisibleToUser && candidate.isClickable && candidate.isEnabled) {
                return candidate
            }
            candidate = candidate.parent
        }
        return null
    }

    private fun target(
        node: AccessibilityNodeInfo,
        kind: HousingSystemTargetKind,
        label: String,
        checked: Boolean? = null,
    ): Target = Target(
        node = node,
        kind = kind,
        label = label,
        bounds = Rect().also(node::getBoundsInScreen),
        checked = checked,
    )

    private fun focus(target: Target) {
        selectedKey = key(target)
        target.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        announceSelection(target)
        Log.i(TAG, "Housing focused system choice '${target.label}'")
    }

    private fun navigate(snapshot: Snapshot, currentIndex: Int, forward: Boolean) {
        val targets = snapshot.targets
        val current = targets[currentIndex]
        val contentIndices = targets.indices.filter { index ->
            targets[index].kind != HousingSystemTargetKind.NavigateBack &&
                targets[index].kind != HousingSystemTargetKind.MoreOptions
        }
        if (snapshot.scrollable != null && currentIndex in contentIndices) {
            val atContentBoundary = if (forward) {
                currentIndex == contentIndices.lastOrNull()
            } else {
                currentIndex == contentIndices.firstOrNull()
            }
            if (atContentBoundary && scrollSurface(snapshot, forward, current)) return
        }
        val nextIndex = currentIndex + if (forward) 1 else -1
        focus(targets[(nextIndex + targets.size) % targets.size])
    }

    /**
     * Samsung virtualizes every long permission RecyclerView. Crossing a visible content boundary
     * scrolls the page and then focuses the newly revealed control instead of trapping the diver
     * among the handful of rows currently present in the accessibility tree.
     */
    private fun scrollSurface(snapshot: Snapshot, forward: Boolean, previous: Target): Boolean {
        val scrollable = snapshot.scrollable ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (scrollable.actionList.none { it.id == action }) return false
        if (!scrollable.performAction(action)) {
            announce(if (forward) "End of permissions" else "Top of permissions")
            return false
        }

        announce(if (forward) "More options" else "Previous options")
        mainHandler.postDelayed({
            val refreshed = currentSnapshot()?.takeIf {
                it.identity == snapshot.identity
            } ?: return@postDelayed
            val refreshedContent = refreshed.targets.filter {
                it.kind != HousingSystemTargetKind.NavigateBack &&
                    it.kind != HousingSystemTargetKind.MoreOptions
            }
            if (refreshedContent.isEmpty()) return@postDelayed
            val oldIndex = refreshedContent.indexOfFirst { it.label == previous.label }
            val target = when {
                forward && oldIndex in 0 until refreshedContent.lastIndex -> {
                    refreshedContent[oldIndex + 1]
                }
                !forward && oldIndex > 0 -> refreshedContent[oldIndex - 1]
                forward -> refreshedContent.first()
                else -> refreshedContent.last()
            }
            focus(target)
        }, PERMISSION_SCROLL_SETTLE_MS)
        return true
    }

    private fun click(target: Target) {
        selectedKey = key(target)
        if (target.kind == HousingSystemTargetKind.NavigateBack) {
            Log.i(TAG, "Housing activated system Back")
            if (!target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }
        if (target.kind == HousingSystemTargetKind.PermissionApp) {
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            if (!target.label.equals(appLabel, ignoreCase = true)) {
                announce("Navigate to $appLabel")
                Log.i(TAG, "Housing refused unrelated permission app '${target.label}'")
                return
            }
            diveControlPermissionFlowActive = true
        }
        Log.i(TAG, "Housing clicked system choice '${target.label}' (${target.kind})")
        if (!target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            target.node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun setLocationMaster(target: Target, enabled: Boolean) {
        if (target.checked == enabled) {
            announce("Location is already ${if (enabled) "On" else "Off"}")
            return
        }
        Log.i(TAG, "Housing setting Location master to ${if (enabled) "On" else "Off"}")
        click(target)
    }

    private fun announceSelection(target: Target) {
        val state = target.checked?.let { if (it) ", On" else ", Off" }.orEmpty()
        announce("Housing selection: ${target.label}$state")
    }

    private fun announce(message: String) {
        toast?.cancel()
        toast = Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun key(target: Target): String =
        "${target.kind}|${target.label}|${target.bounds.flattenToString()}"

    private fun updateDialogIdentity(
        snapshot: Snapshot,
        now: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        // A RecyclerView creates and destroys rows while scrolling. Snapshot identity describes
        // the whole page, so virtualization never re-arms the 650 ms launch guard.
        val signature = snapshot.identity
        if (signature == activeDialogSignature) return
        activeDialogSignature = signature
        dialogOpenedAtMs = now
        selectedKey = null
        Log.i(
            TAG,
            "System permission surface ${snapshot.surface} armed for housing control " +
                "(${snapshot.targets.size} choices: ${snapshot.targets.joinToString { it.label }})",
        )
    }

    private fun logVisibleTargets(snapshot: Snapshot) {
        val scrollActions = snapshot.scrollable?.actionList.orEmpty()
            .map { it.id }
            .filter {
                it == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                    it == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            .sorted()
        val signature = "${snapshot.identity}|${snapshot.targets.joinToString { it.label }}|$scrollActions"
        if (signature == visibleTargetsSignature) return
        visibleTargetsSignature = signature
        Log.i(
            TAG,
            "Visible housing choices on ${snapshot.surface}: " +
                "${snapshot.targets.joinToString { it.label }}; scrollActions=$scrollActions",
        )
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
        const val PERMISSION_SCROLL_SETTLE_MS = 300L
        const val TOOLBAR_BOTTOM_PX = 340
        val PERMISSION_OVERFLOW_LABELS = setOf(
            "all permissions",
            "show system",
            "hide system",
        )
        const val TAG = "HousingPermission"
    }
}
