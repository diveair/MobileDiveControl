package com.mobiledivecontrol

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobiledivecontrol.platform.ble.BlePermissions
import com.mobiledivecontrol.accessibility.PermissionDialogHousingBridge
import com.mobiledivecontrol.service.HousingLinkService
import com.mobiledivecontrol.theme.DiveControlTheme
import com.mobiledivecontrol.testing.CameraStressTestRunner
import com.mobiledivecontrol.ui.DebugSimulationPanel
import com.mobiledivecontrol.ui.DiveControlScreen
import com.mobiledivecontrol.viewmodel.DiveViewModel
import com.mobiledivecontrol.viewmodel.RuntimePermissionNeed
import com.mobiledivecontrol.viewmodel.RuntimePermissionRequest
import androidx.compose.ui.Modifier

/**
 * Single-activity architecture — full-screen, immersive, landscape.
 *
 * The phone is sealed inside a dive housing. The screen must:
 * 1. Stay on permanently (FLAG_KEEP_SCREEN_ON)
 * 2. Be fully immersive (no system bars — maximizes viewfinder area)
 * 3. Keep the keyboard hidden except while the user is explicitly renaming media
 * 4. Lock to landscape (set in manifest)
 */
class MainActivity : ComponentActivity() {

    private var cameraPermissionGranted by mutableStateOf(false)
    private var microphonePermissionGranted by mutableStateOf(false)
    private var blePermissionsGranted by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)
    private var locationPermissionGranted by mutableStateOf(false)
    private var locationServicesEnabled by mutableStateOf(false)
    private var popupControlEnabled by mutableStateOf(false)
    private var missingRuntimePermissionLabels by mutableStateOf<List<String>>(emptyList())
    private var housingServiceStarted = false
    private var housingInputReady = false
    private var popupControlReady = false
    private var runtimePermissionRequestInFlight by mutableStateOf(false)
    private var startupPermissionSequenceActive by mutableStateOf(true)
    private val startupPermissionsAttempted = mutableSetOf<String>()
    private val runtimePermissionsRequested = mutableSetOf<String>()
    private var skyGuideLocationWanted = false
    private var skyGuidePermissionRequestInFlight = false
    private var skyGuidePermissionRequestAttempted = false
    private var skyGuidePermissionRetryCount = 0
    private var locationSettingsShownForSelection = false
    /** Prevents a denied system enable dialog from reopening in a loop during one off-state. */
    private var bluetoothEnableRequestedWhileOff = false
    /** One explicit ADB stress launch may start exactly one runner for this Activity instance. */
    private var cameraStressStarted = false

    private data class PendingOnDemandPermissionRequest(
        val request: RuntimePermissionRequest,
        val resolve: (Long, Boolean) -> Unit,
    )

    private var activeOnDemandPermissionRequest: PendingOnDemandPermissionRequest? = null
    private var queuedOnDemandPermissionRequest: PendingOnDemandPermissionRequest? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        runtimePermissionRequestInFlight = false
        refreshPermissionState()
        // Let Android remove the current permission window before presenting the next one. This
        // keeps every group a distinct, housing-navigable surface instead of allowing controllers
        // to coalesce or visually overlap consecutive launches.
        window.decorView.postDelayed(
            { checkAndRequestPermissions() },
            STARTUP_PERMISSION_DIALOG_SETTLE_MS,
        )
    }

    private val onDemandPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        finishOnDemandPermissionRequest()
    }

    private val onDemandPermissionSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishOnDemandPermissionRequest()
    }

    private val skyGuideLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        skyGuidePermissionRequestInFlight = false
        refreshPermissionState()
        if (!locationPermissionGranted && skyGuideLocationWanted &&
            skyGuidePermissionRetryCount < SKY_GUIDE_PERMISSION_RETRY_LIMIT
        ) {
            // A rejected first prompt is not a dead end inside a sealed housing. Present it once
            // more after the system transition has settled so the diver can change the choice.
            skyGuidePermissionRetryCount++
            window.decorView.postDelayed(
                { requestSkyGuideLocationPrerequisites(forcePermissionDialog = true) },
                SKY_GUIDE_PERMISSION_RETRY_DELAY_MS,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on — the diver can't touch it to wake it
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full immersive mode — hide system bars
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val viewModel: DiveViewModel = viewModel()
            // onResume runs outside the composition; keep a handle so a Bluetooth toggle made
            // from quick settings is picked up the moment the diver returns to the app.
            bluetoothStateRefresh = viewModel::refreshBluetoothState
            compassMonitoring = viewModel::setCompassMonitoring
            val state by viewModel.state.collectAsState()
            val effects by viewModel.effects.collectAsState()
            val useMetric by viewModel.depthUnitMetric.collectAsState()
            val introVisible by viewModel.introVisible.collectAsState()
            val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
            val compassReading by viewModel.compassReading.collectAsState()
            val targetHeading by viewModel.targetHeading.collectAsState()
            val capPromptVisible by viewModel.capPromptVisible.collectAsState()
            val galleryConsentRequest by viewModel.galleryConsentRequest.collectAsState()
            val runtimePermissionRequest by viewModel.runtimePermissionRequest.collectAsState()
            val permissionDialogControlReady by
                PermissionDialogHousingBridge.serviceConnected.collectAsState()
            val skyGuideSelected = state.camera.activeMode ==
                com.mobiledivecontrol.core.CameraModeId.ExpertRaw &&
                state.camera.settingValues["expert.sky_guide"] == "On"

            val cameraStressRequested =
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                intent.getBooleanExtra(CameraStressTestRunner.EXTRA_ENABLED, false)
            androidx.compose.runtime.LaunchedEffect(
                cameraStressRequested,
                cameraPermissionGranted,
                introVisible,
            ) {
                if (!cameraStressRequested || cameraStressStarted) return@LaunchedEffect
                if (introVisible) {
                    // An explicit engineering run needs the actual camera surface. This follows
                    // the normal dismissal path so no test-only camera screen is introduced.
                    viewModel.dismissIntro()
                    return@LaunchedEffect
                }
                if (cameraPermissionGranted) {
                    cameraStressStarted = true
                    CameraStressTestRunner(
                        activity = this@MainActivity,
                        viewModel = viewModel,
                        config = CameraStressTestRunner.Config.from(intent),
                    ).run()
                }
            }

            androidx.compose.runtime.LaunchedEffect(skyGuideSelected) {
                onSkyGuideSelectionChanged(skyGuideSelected)
            }

            androidx.compose.runtime.LaunchedEffect(runtimePermissionRequest?.id) {
                runtimePermissionRequest?.let { request ->
                    requestOnDemandPermissions(request, viewModel::resolveRuntimePermissionRequest)
                }
            }

            androidx.compose.runtime.LaunchedEffect(permissionDialogControlReady) {
                popupControlReady = permissionDialogControlReady
                checkAndRequestPermissions()
            }

            // A revoked camera grant is needed as soon as a live camera surface is entered. This
            // also supplies one native retry after a first-run rejection; later attempts come from
            // the exact housing shutter/record command that needs the permission.
            androidx.compose.runtime.LaunchedEffect(state.mode, cameraPermissionGranted) {
                if (state.mode in setOf(
                        com.mobiledivecontrol.core.AppMode.CameraLive,
                        com.mobiledivecontrol.core.AppMode.CameraAdjust,
                    ) && !cameraPermissionGranted &&
                    !startupPermissionSequenceActive &&
                    Manifest.permission.CAMERA !in startupPermissionsAttempted &&
                    housingInputReady && popupControlEnabled && popupControlReady
                ) {
                    viewModel.requestRuntimePermissions(setOf(RuntimePermissionNeed.Camera))
                }
            }

            val bluetoothEnableLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                // ACTION_REQUEST_ENABLE returns only after the transition finishes or is denied.
                // Re-read the adapter; RESULT_OK alone is not a durable radio-state source.
                viewModel.refreshBluetoothState()
            }

            androidx.compose.runtime.LaunchedEffect(bluetoothEnabled, blePermissionsGranted) {
                if (bluetoothEnabled) {
                    bluetoothEnableRequestedWhileOff = false
                } else if (blePermissionsGranted && !bluetoothEnableRequestedWhileOff) {
                    val request = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    if (request.resolveActivity(packageManager) != null) {
                        bluetoothEnableRequestedWhileOff = true
                        bluetoothEnableLauncher.launch(request)
                    } else {
                        Log.e("DiveBluetooth", "No system activity can request Bluetooth enable")
                    }
                }
            }
            val galleryConsentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                viewModel.resolveGalleryConsent(result.resultCode == Activity.RESULT_OK)
            }

            androidx.compose.runtime.LaunchedEffect(galleryConsentRequest) {
                galleryConsentRequest?.let { request ->
                    galleryConsentLauncher.launch(
                        IntentSenderRequest.Builder(request.pendingIntent.intentSender).build(),
                    )
                }
            }

            // Sync Android permission grants into the core state machine
            androidx.compose.runtime.LaunchedEffect(cameraPermissionGranted) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Camera,
                    cameraPermissionGranted,
                )
            }

            // Microphone is requested after startup. Its grant can change without any of the
            // startup permission keys changing, so it needs its own observable synchronization.
            androidx.compose.runtime.LaunchedEffect(microphonePermissionGranted) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Microphone,
                    microphonePermissionGranted,
                )
            }

            // Named by what they buy the diver, not by what Android calls them.
            androidx.compose.runtime.LaunchedEffect(
                cameraPermissionGranted,
                blePermissionsGranted,
                notificationPermissionGranted,
                popupControlEnabled,
                missingRuntimePermissionLabels,
            ) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Accessibility,
                    popupControlEnabled,
                )
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Notifications,
                    notificationPermissionGranted,
                )
                viewModel.setMissingPermissions(startupPermissionLabels())
            }

            // The housing link only starts once the radio is legally usable. Starting it earlier
            // throws a SecurityException on a binder thread and the diver sees nothing.
            androidx.compose.runtime.LaunchedEffect(
                blePermissionsGranted,
                bluetoothEnabled,
            ) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Bluetooth,
                    blePermissionsGranted,
                )
                if (blePermissionsGranted && bluetoothEnabled) {
                    startHousingLinkService()
                } else {
                    // A stopped phone radio cannot produce evidence about the housing. Keeping the
                    // reconnect supervisor alive here only polls a prerequisite that changes via a
                    // broadcast, so stop it and let the explicit phone-radio banner own the state.
                    stopHousingLinkService()
                    viewModel.advanceBle(com.mobiledivecontrol.core.BleSignal.Fail)
                }
            }

            androidx.compose.runtime.LaunchedEffect(
                state.bleConnectionState,
                blePermissionsGranted,
                popupControlEnabled,
                permissionDialogControlReady,
            ) {
                housingInputReady = state.bleConnectionState in setOf(
                    com.mobiledivecontrol.core.BleConnectionState.Ready,
                    com.mobiledivecontrol.core.BleConnectionState.Degraded,
                )
                checkAndRequestPermissions()
            }

            DiveControlTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main UI
                    DiveControlScreen(
                        bluetoothEnabled = bluetoothEnabled,
                        compassReading = compassReading,
                        targetHeading = targetHeading,
                        state = state,
                        cameraPermissionGranted = cameraPermissionGranted,
                        locationPrerequisitesReady = locationPermissionGranted &&
                            locationServicesEnabled,
                        lifecycleOwner = this@MainActivity,
                        useMetric = useMetric,
                        effects = effects,
                        onEffectsConsumed = viewModel::clearEffects,
                        onDetectedLenses = { lenses ->
                            viewModel.dispatch(
                                com.mobiledivecontrol.core.CameraCommand.UpdateDetectedLenses(lenses)
                            )
                        },
                        onCapabilities = { caps ->
                            viewModel.dispatch(
                                com.mobiledivecontrol.core.CameraCommand.UpdateCameraCapabilities(caps)
                            )
                        },
                        onMeteredExposure = viewModel::updateMeteredExposure,
                        onPointingGesture = viewModel::setTargetHeadingFromPoint,
                        onCameraCommand = { command -> viewModel.dispatch(command) },
                        onGalleryCommand = { command -> viewModel.dispatch(command) },
                        onDiagnosticsCommand = { command -> viewModel.dispatch(command) },
                        introVisible = introVisible,
                        onIntroDismiss = {
                            viewModel.dismissIntro()
                            // Completion changes the next-launch permission policy immediately;
                            // re-evaluate so startup cannot remain artificially active this run.
                            checkAndRequestPermissions()
                        },
                        // Runtime grants use Android's native dialogs. Accessibility is not part
                        // of first-run setup and this flow never launches Android Settings.
                        permissionsGranted = missingRuntimePermissionLabels.isEmpty(),
                        missingPermissions = missingRuntimePermissionLabels,
                        permissionDialogVisible = runtimePermissionRequestInFlight,
                        onPermissionsSetup = ::openPermissionSetup,
                        capPromptVisible = capPromptVisible,
                        onCapPromptDismiss = viewModel::dismissCapPrompt,
                    )

                    // Debug simulation panel (tap 🐛 on the right edge)
                    DebugSimulationPanel(viewModel = viewModel)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private var bluetoothStateRefresh: (() -> Unit)? = null
    private var compassMonitoring: ((Boolean) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        compassMonitoring?.invoke(true)
    }

    override fun onStop() {
        compassMonitoring?.invoke(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions in case the user granted them from settings
        refreshPermissionState()
        // Post past the resume traversal. Launching from onCreate could mark Nearby Devices as
        // attempted before Android had a resumed window on which to display its permission dialog.
        window.decorView.post { checkAndRequestPermissions() }
        if (skyGuideLocationWanted && locationPermissionGranted) {
            requestSkyGuideLocationPrerequisites()
        }
        // The radio can be toggled from quick settings while we are backgrounded, and a stale
        // "Bluetooth is off" banner is exactly as misleading as the housing fault it replaced.
        bluetoothStateRefresh?.invoke()
    }

    private fun startupRuntimePermissionGroups(): List<List<Pair<String, String>>> = buildList {
            // Bootstrap first: these share Android's Nearby Devices dialog and must stay together.
            add(
                BlePermissions.required().map { permission ->
                    permission to "Nearby devices  —  to find your housing"
                },
            )
            add(listOf(Manifest.permission.CAMERA to "Camera  —  so the app can see"))
            add(
                listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION to
                        "Location/GPS  —  for dive position and Sky Guide",
                    Manifest.permission.ACCESS_FINE_LOCATION to
                        "Precise GPS  —  for accurate dive position and Sky Guide",
                ),
            )
            // Microphone is feature-scoped, not an application-start prerequisite. Request it
            // when the diver first starts an audio-enabled recording; putting it in this chain
            // left a secure GrantPermissionsActivity over the intro after camera acceptance and
            // made the still-running app look frozen.
            // Android 10+ lets an app read and modify media it created without a storage grant.
            // Requesting READ_MEDIA_* would let Android offer partial access and launch its
            // selected-media management picker, so modern startup deliberately has no media step.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(
                    listOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE to
                            "Media  —  DiveControl gallery access",
                    ),
                )
            }
            // Notification visibility is also optional and is requested by the housing-link
            // feature itself. It must never prevent the camera UI from opening.
        }.map { group -> group.distinctBy { it.first } }

    private fun requiredRuntimePermissions(): List<Pair<String, String>> =
        startupRuntimePermissionGroups().flatten().distinctBy { it.first }

    private fun pendingRuntimePermissionRequest(): List<String> =
        requiredRuntimePermissions().map { it.first }.filterNot(::hasPermission)

    /** Only native runtime permissions participate in first-run setup. */
    private fun startupPermissionLabels(): List<String> {
        return missingRuntimePermissionLabels(
            requiredPermissions = requiredRuntimePermissions(),
            grantedPermissions = requiredRuntimePermissions()
                .map { it.first }
                .filterTo(mutableSetOf(), ::hasPermission),
        )
    }

    private fun checkAndRequestPermissions(force: Boolean = false) {
        refreshPermissionState()
        val pending = pendingRuntimePermissionRequest()
        if (force) {
            startupPermissionsAttempted.removeAll(pending.toSet())
            startupPermissionSequenceActive = true
        }

        val groups = startupRuntimePermissionGroups().map { group -> group.map { it.first } }
        val allPermissions = groups.flatten().toSet()
        val granted = allPermissions.filterTo(mutableSetOf(), ::hasPermission)
        // A permission granted since its startup attempt is no longer a denial marker. If it is
        // revoked later, the feature-level request is allowed to surface it again.
        startupPermissionsAttempted.removeAll(granted)
        val step = nextStartupPermissionStep(
            permissionGroups = groups,
            bluetoothPermissions = BlePermissions.required().toSet(),
            grantedPermissions = granted,
            attemptedPermissions = startupPermissionsAttempted,
            popupControlRequired = false,
            popupControlSatisfied = true,
        )
        Log.d(
            STARTUP_PERMISSION_LOG_TAG,
            "gate=${step.gate} request=${step.permissions.joinToString()} " +
                "housingReady=$housingInputReady popupEnabled=$popupControlEnabled " +
                "popupConnected=$popupControlReady",
        )
        startupPermissionSequenceActive = step.gate != StartupPermissionGate.Complete

        if (canLaunchStartupPermissionDialog(
                step = step,
                lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                requestInFlight = runtimePermissionRequestInFlight,
                onDemandRequestActive = activeOnDemandPermissionRequest != null,
            )
        ) {
            startupPermissionsAttempted += step.permissions
            runtimePermissionsRequested += step.permissions
            runtimePermissionRequestInFlight = true
            permissionsLauncher.launch(step.permissions.toTypedArray())
        } else if (step.gate == StartupPermissionGate.Complete) {
            launchQueuedOnDemandPermissionRequest()
        }
    }

    private fun refreshPermissionState() {
        microphonePermissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        blePermissionsGranted = BlePermissions.allGranted(this)
        notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        locationPermissionGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val locationManager = getSystemService(LocationManager::class.java)
        locationServicesEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            locationManager?.let { manager ->
                runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                    .getOrDefault(false) ||
                    runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                        .getOrDefault(false)
            } == true
        }
        popupControlEnabled = PermissionDialogHousingBridge.isEnabled(this)
        missingRuntimePermissionLabels = startupPermissionLabels()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openPermissionSetup() {
        refreshPermissionState()
        val missing = pendingRuntimePermissionRequest().toSet()
        when (runtimePermissionRecoveryAction(
            missingPermissions = missing,
        )) {
            RuntimePermissionRecoveryAction.None -> checkAndRequestPermissions()
            RuntimePermissionRecoveryAction.RequestDialog ->
                checkAndRequestPermissions(force = true)
        }
    }

    /**
     * Launches the smallest native permission request for the feature the diver just attempted.
     * Android suppresses a runtime dialog after a permanent denial; that case opens DiveControl's
     * real app-permission Settings page and arms the same housing navigation bridge instead of
     * failing silently.
     */
    private fun requestOnDemandPermissions(
        request: RuntimePermissionRequest,
        resolve: (Long, Boolean) -> Unit,
    ) {
        val pending = PendingOnDemandPermissionRequest(request, resolve)
        if (runtimePermissionRequestInFlight || activeOnDemandPermissionRequest != null) {
            queuedOnDemandPermissionRequest = pending
            return
        }

        refreshPermissionState()
        val missing = permissionsFor(request.needs).filterNot(::hasPermission)
        if (missing.isEmpty()) {
            resolve(request.id, true)
            return
        }

        // Serialize with startup, but never wait for an optional accessibility service. Doing
        // so stranded the first audio-enabled recording behind a prompt that could never open.
        val missingOnlyBluetooth = missing.all { it in BlePermissions.required() }
        val canOperateSystemSurface = canLaunchFeaturePermissionDialog(
            lifecycleStarted = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
            startupSequenceActive = startupPermissionSequenceActive,
            bluetoothOnly = missingOnlyBluetooth,
        )
        if (!canOperateSystemSurface) {
            queuedOnDemandPermissionRequest = pending
            Log.d(
                STARTUP_PERMISSION_LOG_TAG,
                "queued on-demand request=${request.needs} housingReady=$housingInputReady " +
                    "popupEnabled=$popupControlEnabled popupConnected=$popupControlReady " +
                    "startupActive=$startupPermissionSequenceActive",
            )
            return
        }

        activeOnDemandPermissionRequest = pending
        // Android may suppress a repeatedly denied grant. Do not automatically leave the app.
        runtimePermissionsRequested += missing
        onDemandPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun finishOnDemandPermissionRequest() {
        val active = activeOnDemandPermissionRequest ?: return
        activeOnDemandPermissionRequest = null
        refreshPermissionState()
        val granted = permissionsFor(active.request.needs).all(::hasPermission)
        active.resolve(active.request.id, granted)
        checkAndRequestPermissions()
    }

    private fun launchQueuedOnDemandPermissionRequest() {
        if (runtimePermissionRequestInFlight || activeOnDemandPermissionRequest != null) return
        val queued = queuedOnDemandPermissionRequest ?: return
        queuedOnDemandPermissionRequest = null
        window.decorView.post {
            requestOnDemandPermissions(queued.request, queued.resolve)
        }
    }

    private fun permissionsFor(needs: Set<RuntimePermissionNeed>): List<String> = buildList {
        if (RuntimePermissionNeed.Camera in needs) add(Manifest.permission.CAMERA)
        if (RuntimePermissionNeed.Microphone in needs) add(Manifest.permission.RECORD_AUDIO)
        if (RuntimePermissionNeed.Bluetooth in needs) addAll(BlePermissions.required())
        if (RuntimePermissionNeed.Notifications in needs &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.distinct()

    /**
     * Location is requested at the moment Sky Guide is selected, not during unrelated startup.
     * Android permits a second ordinary request after a rejection; once the OS marks the grant as
     * permanently denied, only the app-permission settings page can legally restore it.
     */
    private fun onSkyGuideSelectionChanged(selected: Boolean) {
        skyGuideLocationWanted = selected
        locationSettingsShownForSelection = false
        if (!selected) {
            skyGuidePermissionRetryCount = 0
            return
        }
        skyGuidePermissionRetryCount = 0
        requestSkyGuideLocationPrerequisites()
    }

    private fun requestSkyGuideLocationPrerequisites(forcePermissionDialog: Boolean = false) {
        refreshPermissionState()
        if (!skyGuideLocationWanted) return
        if (locationPermissionGranted) {
            val locationManager = getSystemService(LocationManager::class.java)
            val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager?.isLocationEnabled == true
            } else {
                locationManager?.let { manager ->
                    runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                        .getOrDefault(false) ||
                        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                            .getOrDefault(false)
                } == true
            }
            if (!locationEnabled) {
                // A runtime grant cannot switch the phone's master Location control. Open the
                // real Android/Samsung control instead of drawing a fake "Location Required" HUD.
                if (!locationSettingsShownForSelection) {
                    locationSettingsShownForSelection = true
                    runCatching {
                        PermissionDialogHousingBridge.armPermissionSettingsFlow()
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }.onFailure { error ->
                        locationSettingsShownForSelection = false
                        Log.e("DiveLocation", "Could not open phone location settings", error)
                    }
                }
            } else {
                locationSettingsShownForSelection = false
            }
            return
        }
        if (skyGuidePermissionRequestInFlight) return

        val permanentlyDenied = !forcePermissionDialog && skyGuidePermissionRequestAttempted &&
            !canShowLocationPermissionDialogAgain()
        if (permanentlyDenied) {
            PermissionDialogHousingBridge.armPermissionSettingsFlow()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
            return
        }

        skyGuidePermissionRequestAttempted = true
        skyGuidePermissionRequestInFlight = true
        skyGuideLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    private fun canShowLocationPermissionDialogAgain(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Starts the link service once, and only after BLE permission is granted.
     *
     * From Android 14 a `connectedDevice` foreground service is refused outright without
     * `BLUETOOTH_CONNECT`, so an early start would take the housing offline for the whole session.
     */
    private fun startHousingLinkService() {
        if (housingServiceStarted) return
        housingServiceStarted = true
        runCatching {
            startForegroundService(Intent(this, HousingLinkService::class.java))
        }.onFailure { error ->
            housingServiceStarted = false
            Log.e("DiveControl", "Could not start housing link service", error)
        }
    }

    private fun stopHousingLinkService() {
        runCatching {
            stopService(Intent(this, HousingLinkService::class.java))
        }.onFailure { error ->
            Log.w("DiveBluetooth", "Could not stop housing link while Bluetooth is unavailable", error)
        }
        housingServiceStarted = false
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private companion object {
        const val STARTUP_PERMISSION_DIALOG_SETTLE_MS = 350L
        const val STARTUP_PERMISSION_LOG_TAG = "StartupPermissions"
        const val SKY_GUIDE_PERMISSION_RETRY_LIMIT = 1
        const val SKY_GUIDE_PERMISSION_RETRY_DELAY_MS = 900L
    }

}
