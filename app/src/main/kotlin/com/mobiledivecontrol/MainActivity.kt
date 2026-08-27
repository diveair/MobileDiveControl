package com.mobiledivecontrol

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobiledivecontrol.platform.ble.BlePermissions
import com.mobiledivecontrol.service.HousingLinkService
import com.mobiledivecontrol.theme.DiveControlTheme
import com.mobiledivecontrol.ui.DebugSimulationPanel
import com.mobiledivecontrol.ui.DiveControlScreen
import com.mobiledivecontrol.viewmodel.DiveViewModel
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
    private var blePermissionsGranted by mutableStateOf(false)
    private var housingServiceStarted = false
    /** Prevents a denied system enable dialog from reopening in a loop during one off-state. */
    private var bluetoothEnableRequestedWhileOff = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on — the diver can't touch it to wake it
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full immersive mode — hide system bars
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Request camera permission
        checkAndRequestPermissions()

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
            val missingPermissions by viewModel.missingPermissions.collectAsState()
            val capPromptVisible by viewModel.capPromptVisible.collectAsState()
            val galleryConsentRequest by viewModel.galleryConsentRequest.collectAsState()
            val galleryMediaManagementRequest by viewModel.galleryMediaManagementRequest.collectAsState()
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

            val galleryMediaManagementLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                viewModel.resolveGalleryMediaManagement(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        MediaStore.canManageMedia(this@MainActivity),
                )
            }

            androidx.compose.runtime.LaunchedEffect(galleryMediaManagementRequest) {
                if (galleryMediaManagementRequest != null) {
                    val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        galleryMediaManagementLauncher.launch(intent)
                    } else {
                        viewModel.resolveGalleryMediaManagement(granted = false)
                    }
                }
            }

            // Sync Android permission grants into the core state machine
            androidx.compose.runtime.LaunchedEffect(cameraPermissionGranted) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Camera,
                    cameraPermissionGranted,
                )
            }

            // Named by what they buy the diver, not by what Android calls them.
            androidx.compose.runtime.LaunchedEffect(cameraPermissionGranted, blePermissionsGranted) {
                viewModel.setMissingPermissions(
                    buildList {
                        if (!cameraPermissionGranted) add("Camera  —  so the app can see")
                        if (!blePermissionsGranted) add("Nearby devices  —  to find your housing")
                    },
                )
            }

            // The housing link only starts once the radio is legally usable. Starting it earlier
            // throws a SecurityException on a binder thread and the diver sees nothing.
            androidx.compose.runtime.LaunchedEffect(blePermissionsGranted, bluetoothEnabled) {
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

            DiveControlTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main UI
                    DiveControlScreen(
                        bluetoothEnabled = bluetoothEnabled,
                        compassReading = compassReading,
                        targetHeading = targetHeading,
                        state = state,
                        cameraPermissionGranted = cameraPermissionGranted,
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
                        onSystemCommand = { command -> viewModel.dispatch(command) },
                        introVisible = introVisible,
                        onIntroDismiss = viewModel::dismissIntro,
                        // The intro is the app's only honest surface for "why can I not do
                        // anything yet", so it needs both permission families, not just the camera.
                        permissionsGranted = cameraPermissionGranted && blePermissionsGranted,
                        missingPermissions = missingPermissions,
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
        // The radio can be toggled from quick settings while we are backgrounded, and a stale
        // "Bluetooth is off" banner is exactly as misleading as the housing fault it replaced.
        bluetoothStateRefresh?.invoke()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                // The foreground service notification is the only honest link-state surface once
                // the app leaves the foreground; without this it is silently invisible.
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MANAGE_MEDIA deliberately does not grant write access itself. Android's
                // createWriteRequest remains confirmation-free only while this runtime grant is
                // present, which is essential when the phone is sealed inside the housing.
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            addAll(BlePermissions.required())
        }
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        refreshPermissionState()

        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun refreshPermissionState() {
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        blePermissionsGranted = BlePermissions.allGranted(this)
    }

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

}
