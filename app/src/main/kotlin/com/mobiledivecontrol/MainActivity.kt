package com.mobiledivecontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        cameraPermissionGranted = grants[Manifest.permission.CAMERA] == true
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
            val state by viewModel.state.collectAsState()
            val effects by viewModel.effects.collectAsState()
            val useMetric by viewModel.depthUnitMetric.collectAsState()

            // Sync Android permission grants into the core state machine
            androidx.compose.runtime.LaunchedEffect(cameraPermissionGranted) {
                viewModel.updatePermission(
                    com.mobiledivecontrol.core.PermissionKind.Camera,
                    cameraPermissionGranted,
                )
            }

            DiveControlTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main UI
                    DiveControlScreen(
                        state = state,
                        cameraPermissionGranted = cameraPermissionGranted,
                        lifecycleOwner = this@MainActivity,
                        useMetric = useMetric,
                        effects = effects,
                        onEffectsConsumed = viewModel::clearEffects,
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

    override fun onResume() {
        super.onResume()
        // Re-check permission in case user granted it from settings
        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}
