package com.example.cameraxapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cameraxapp.ui.PreviewViewModel
import com.example.cameraxapp.ui.ShutterButton
import com.example.cameraxapp.ui.theme.CameraXAppTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.example.cameraxapp.utils.SensorDataManager
import com.example.cameraxapp.utils.GyroViewModel
import java.time.Instant
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var gyroManager: SensorDataManager
    private lateinit var gyroViewModel: GyroViewModel

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // gyro
        gyroViewModel = ViewModelProvider(this)[GyroViewModel::class.java]
        gyroManager = SensorDataManager(this) { wx, wy, wz, dt, maxPulseSamples ->
            gyroViewModel.update(wx, wy, wz, dt, maxPulseSamples)
        }

        setContent {
            CameraXAppTheme {
                AppNav(gyroManager, gyroViewModel)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        gyroManager.start()
    }

    override fun onPause() {
        super.onPause()
        gyroManager.stop()
    }
}

@Composable
fun AppNav(
    gyroManager: SensorDataManager,
    gyroViewModel: GyroViewModel,
) {
    val navController = rememberNavController()
    val previewViewModel: PreviewViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    NavHost(navController, startDestination = "camera") {
        composable("camera") {
            CameraScreen(
                gyroManager,
                gyroViewModel,
                previewViewModel,
                onSettingsClick = {navController.navigate("setting")}
            )
        }
        composable("setting") {
            SettingScreen(
                previewViewModel = previewViewModel,
                onBackClick = {navController.navigate("camera")}
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingScreen (
    previewViewModel: PreviewViewModel,
    onBackClick: () -> Unit = {}
) {
    val presetA by previewViewModel.presetA.collectAsState()
    val presetB by previewViewModel.presetB.collectAsState()
    val presetC by previewViewModel.presetC.collectAsState()
    val modelName by previewViewModel.modelName.collectAsState()

    Scaffold (
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.Clear, contentDescription = "back")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Model name (used in file names)
            Text(text = "Model Name", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.material3.OutlinedTextField(
                value = modelName,
                onValueChange = { previewViewModel.setModelName(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. Pixel8") }
            )
            Box(modifier = Modifier.padding(16.dp))

            // Exposure presets
            Text(text = "Exposure Presets", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Box(modifier = Modifier.padding(8.dp))

            PresetEditor("A", presetA) { iso, ss -> previewViewModel.setPresetA(iso, ss) }
            PresetEditor("B", presetB) { iso, ss -> previewViewModel.setPresetB(iso, ss) }
            PresetEditor("C", presetC) { iso, ss -> previewViewModel.setPresetC(iso, ss) }

            // OIS toggle (shown only on supported devices)
            val isOisAvailable by previewViewModel.isOisAvailable.collectAsState()
            if (isOisAvailable) {
                Box(modifier = Modifier.padding(16.dp))
                Text(text = "OIS", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                val isOisEnabled by previewViewModel.isOisEnabled.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Optical Image Stabilization")
                    Box(modifier = Modifier.weight(1f))
                    Switch(
                        checked = isOisEnabled,
                        onCheckedChange = { previewViewModel.setOisEnabled(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun PresetEditor(
    name: String,
    preset: com.example.cameraxapp.ui.ExposurePreset,
    onUpdate: (Int, Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, modifier = Modifier.padding(end = 8.dp))

        // ISO
        Column(modifier = Modifier.weight(1f)) {
            Text("ISO", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            androidx.compose.material3.OutlinedTextField(
                value = preset.iso.toString(),
                onValueChange = { newValue ->
                    newValue.toIntOrNull()?.let { iso ->
                        onUpdate(iso, preset.ss)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
        }

        // Shutter speed, entered as the denominator x of 1/x s
        Column(modifier = Modifier.weight(1f)) {
            Text("SS (1/x)", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            androidx.compose.material3.OutlinedTextField(
                value = (1_000_000_000 / preset.ss).toInt().toString(),
                onValueChange = { newValue ->
                    newValue.toIntOrNull()?.let { denominator ->
                        if (denominator > 0) {
                            val ssNano = 1_000_000_000L / denominator
                            onUpdate(preset.iso, ssNano)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen (
    sensorDataManager: SensorDataManager,
    gyroViewModel: GyroViewModel,
    previewViewModel: PreviewViewModel,
    onSettingsClick: () -> Unit = {}
){
    val gyroStr by gyroViewModel.gyroStr.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "settings")
            }

        }
    ) { innerPadding ->
        val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
        val context = LocalContext.current

        // Storage permission is required on Android 12L (API 32) and below
        val storagePermissionState = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            null
        }

        LaunchedEffect(Unit) {
            cameraPermissionState.launchPermissionRequest()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                storagePermissionState?.launchPermissionRequest()
            }
        }

        // Show the camera UI only after all required permissions are granted
        val allPermissionsGranted = cameraPermissionState.status.isGranted &&
                (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 || storagePermissionState?.status?.isGranted == true)

        if (allPermissionsGranted) {
            var isPlaybackMode by remember { mutableStateOf(false) }

            // Auto-shutter callback fired by the gimbal pulse detector
            LaunchedEffect(Unit) {
                sensorDataManager.setAutoShutterCallback {
                    val timestamp = Instant.now().epochSecond
                    val fullBaseName = previewViewModel.buildFileNameAndIncrementTrigger(timestamp, true)
                    previewViewModel.takePicture(context, fullBaseName)
                    sensorDataManager.trigger("$fullBaseName.csv")
                    // Lock AWB/AE/AF after the first capture so the whole scene shares the same settings
                    previewViewModel.lockPlaybackSettings()
                }
            }

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                Box(modifier = Modifier.padding(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                ) {
                    PreviewView(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = previewViewModel,
                    )
                    Column {
                        Text(
                            text = gyroStr,
                            fontFamily = FontFamily.Monospace
                        )
                        // ISO/SS readout (manual mode only)
                        val isManualMode by previewViewModel.isManualMode.collectAsState()
                        if (isManualMode) {
                            val currentIso by previewViewModel.currentIso.collectAsState()
                            val currentSs by previewViewModel.currentSs.collectAsState()
                            Text(
                                text = "ISO: $currentIso",
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "SS: 1/${(1_000_000_000 / currentSs).toInt()} s",
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    // Scene/trigger counter overlay (bottom right)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPlaybackMode) {
                            val sceneCounter by previewViewModel.sceneCounter.collectAsState()
                            val triggerCount by previewViewModel.triggerCount.collectAsState()
                            IconButton(
                                onClick = { previewViewModel.decrementScene() },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text("-", color = Color.White, fontSize = 24.sp)
                            }
                            Text(
                                text = "S$sceneCounter n$triggerCount",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp
                            )
                            IconButton(
                                onClick = { previewViewModel.incrementScene() },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text("+", color = Color.White, fontSize = 24.sp)
                            }
                            // Reset the trigger count n
                            IconButton(
                                onClick = { previewViewModel.resetTriggerCount() },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Text("n0", color = Color.Yellow, fontSize = 16.sp)
                            }
                        } else {
                            Text(
                                text = "Sx nx",
                                color = Color.White.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp
                            )
                        }
                    }
                }
                // Manual switch, shutter button, Playback switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Manual")
                            val isManualMode by previewViewModel.isManualMode.collectAsState()
                            Switch(
                                checked = isManualMode,
                                onCheckedChange = { previewViewModel.setManualMode(it) }
                            )
                        }
                    }

                    // Manual shutter (disabled in Playback mode)
                    ShutterButton(
                        onClick = {
                            if (!isPlaybackMode) {
                                val timestamp = Instant.now().epochSecond
                                val fullBaseName = previewViewModel.buildFileNameAndIncrementTrigger(timestamp, false)
                                previewViewModel.takePicture(context, fullBaseName)
                                sensorDataManager.trigger("$fullBaseName.csv")
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Playback")
                            Switch(
                                checked = isPlaybackMode,
                                onCheckedChange = { enabled ->
                                    isPlaybackMode = enabled
                                    sensorDataManager.setPlaybackMode(enabled)
                                    // Release the locks on every toggle (ON: start a fresh scene, OFF: back to normal)
                                    previewViewModel.unlockPlaybackSettings()
                                }
                            )
                        }
                    }
                }
                ManualExposureControl(viewModel = previewViewModel)
            }
        }
    }
}

@Composable
fun PreviewView(modifier: Modifier = Modifier, viewModel: PreviewViewModel) {
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindToCamera(context.applicationContext, lifecycleOwner)
    }

    surfaceRequest?.let {
        CameraXViewfinder(
            modifier = modifier.fillMaxWidth(),
            surfaceRequest = it,
        )
    }
}


@Composable
fun ColumnScope.ManualExposureControl(viewModel: PreviewViewModel) {
    val isManualMode by viewModel.isManualMode.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val presetA by viewModel.presetA.collectAsState()
    val presetB by viewModel.presetB.collectAsState()
    val presetC by viewModel.presetC.collectAsState()

    if (isManualMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("A" to presetA, "B" to presetB, "C" to presetC).forEach { (name, preset) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedPreset == name,
                        onClick = { viewModel.selectPreset(name) }
                    )
                    Column {
                        Text(name, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        Text(
                            "ISO${preset.iso} 1/${(1_000_000_000 / preset.ss).toInt()}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        // EV -1/+1 buttons (adjust ISO only)
        val evOffset by viewModel.evOffset.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.adjustEvByIso(-1) }
            ) {
                Text("EV -1")
            }
            Text(
                text = if (evOffset >= 0) "EV+$evOffset" else "EV$evOffset",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge
            )
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.adjustEvByIso(+1) }
            ) {
                Text("EV +1")
            }
        }
    }
}
