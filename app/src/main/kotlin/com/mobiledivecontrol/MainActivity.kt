package com.mobiledivecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
 * 3. Never show the keyboard
 * 4. Lock to landscape (set in manifest)
 */
class MainActivity : ComponentActivity() {

    private var cameraPermissionGranted by mutableStateOf(false)
    private var blePermissionsGranted by mutableStateOf(false)
    private var housingServiceStarted = false

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
            val state by viewModel.state.collectAsState()
            val effects by viewModel.effects.collectAsState()
            val useMetric by viewModel.depthUnitMetric.collectAsState()
            val introVisible by viewModel.introVisible.collectAsState()
            val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
            val missingPermissions by viewModel.missingPermissions.collectAsState()
            val capPromptVisible by viewModel.capPromptVisible.collectAsState()

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
            androidx.compose.runtime.LaunchedEffect(blePermissionsGranted) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Bluetooth,
                    blePermissionsGranted,
                )
                if (blePermissionsGranted) {
                    startHousingLinkService()
                } else {
                    // Without the permission the service cannot legally start on Android 14+, so
                    // nothing would ever move the link out of Idle. Say "unavailable" rather than
                    // leaving the diver with dead buttons and a banner that reads "searching".
                    viewModel.advanceBle(com.mobiledivecontrol.core.BleSignal.Fail)
                }
            }

            DiveControlTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main UI
                    DiveControlScreen(
                        bluetoothEnabled = bluetoothEnabled,
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

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}
