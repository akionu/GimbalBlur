package com.example.cameraxapp.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class ExposurePreset(val iso: Int, val ss: Long)

class PreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("exposure_presets", Context.MODE_PRIVATE)

    private val _surfaceRequest = MutableStateFlow<androidx.camera.core.SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<androidx.camera.core.SurfaceRequest?> = _surfaceRequest.asStateFlow()

    private val _isoRange = MutableStateFlow<Range<Int>?>(null)
    val isoRange: StateFlow<Range<Int>?> = _isoRange.asStateFlow()

    private val _ssRange = MutableStateFlow<Range<Long>?>(null)
    val ssRange: StateFlow<Range<Long>?> = _ssRange.asStateFlow()

    private val _currentIso = MutableStateFlow(100)
    val currentIso: StateFlow<Int> = _currentIso.asStateFlow()

    private val _currentSs = MutableStateFlow(33_333_333L) // 1/30 s
    val currentSs: StateFlow<Long> = _currentSs.asStateFlow()

    private val _isManualMode = MutableStateFlow(false)
    val isManualMode: StateFlow<Boolean> = _isManualMode.asStateFlow()

    // Exposure presets A, B, C (persisted in SharedPreferences)
    private val _presetA = MutableStateFlow(loadPreset("A", 100, 8_333_333L))
    val presetA: StateFlow<ExposurePreset> = _presetA.asStateFlow()

    private val _presetB = MutableStateFlow(loadPreset("B", 200, 16_666_666L))
    val presetB: StateFlow<ExposurePreset> = _presetB.asStateFlow()

    private val _presetC = MutableStateFlow(loadPreset("C", 400, 33_333_333L))
    val presetC: StateFlow<ExposurePreset> = _presetC.asStateFlow()

    private val _selectedPreset = MutableStateFlow("A")
    val selectedPreset: StateFlow<String> = _selectedPreset.asStateFlow()

    // Model name used in file names (persisted)
    private val _modelName = MutableStateFlow(prefs.getString("model_name", "MODEL") ?: "MODEL")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    // Scene counter S (persisted across restarts)
    private val _sceneCounter = MutableStateFlow(prefs.getInt("scene_counter", 1))
    val sceneCounter: StateFlow<Int> = _sceneCounter.asStateFlow()

    // Trigger count n: captures in the current scene (persisted across restarts)
    private val _triggerCount = MutableStateFlow(prefs.getInt("trigger_count", 0))
    val triggerCount: StateFlow<Int> = _triggerCount.asStateFlow()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _isRawJpegMode = MutableStateFlow(false)
    val isRawJpegMode: StateFlow<Boolean> = _isRawJpegMode.asStateFlow()

    // Optical image stabilization
    private val _isOisAvailable = MutableStateFlow(false)
    val isOisAvailable: StateFlow<Boolean> = _isOisAvailable.asStateFlow()

    private val _isOisEnabled = MutableStateFlow(true)
    val isOisEnabled: StateFlow<Boolean> = _isOisEnabled.asStateFlow()

    // Whether AWB/AE/AF are locked in Playback mode
    private val _isPlaybackLocked = MutableStateFlow(false)
    val isPlaybackLocked: StateFlow<Boolean> = _isPlaybackLocked.asStateFlow()

    private fun loadPreset(name: String, defaultIso: Int, defaultSs: Long): ExposurePreset {
        val iso = prefs.getInt("${name}_iso", defaultIso)
        val ss = prefs.getLong("${name}_ss", defaultSs)
        return ExposurePreset(iso, ss)
    }

    private fun savePreset(name: String, preset: ExposurePreset) {
        prefs.edit()
            .putInt("${name}_iso", preset.iso)
            .putLong("${name}_ss", preset.ss)
            .apply()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindToCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            try {
                val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
                    ProcessCameraProvider.getInstance(context)
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.await()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request ->
                        _surfaceRequest.value = request
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Check whether simultaneous RAW+JPEG capture is supported
                val capabilities = ImageCapture.getImageCaptureCapabilities(
                    cameraProvider.getCameraInfo(cameraSelector)
                )
                val supportedFormats = capabilities.supportedOutputFormats
                Log.d("PreviewViewModel", "Supported output formats: $supportedFormats")

                val supportsRawJpeg = supportedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)

                cameraProvider.unbindAll()

                // Prefer RAW+JPEG when supported
                if (supportsRawJpeg) {
                    try {
                        val imageCaptureRaw = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                            .build()

                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCaptureRaw
                        )
                        imageCapture = imageCaptureRaw
                        _isRawJpegMode.value = true
                        Log.d("PreviewViewModel", "Bound in RAW+JPEG mode")
                    } catch (e: Exception) {
                        Log.w("PreviewViewModel", "RAW+JPEG bind failed, falling back to JPEG only", e)
                        cameraProvider.unbindAll()
                        bindJpegOnly(cameraProvider, lifecycleOwner, cameraSelector, preview)
                    }
                } else {
                    Log.d("PreviewViewModel", "RAW not supported, JPEG only")
                    bindJpegOnly(cameraProvider, lifecycleOwner, cameraSelector, preview)
                }

                // Read camera characteristics
                camera?.let { cam ->
                    val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
                    val cameraId = camera2Info.getCameraId()
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                            as android.hardware.camera2.CameraManager
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                    val isoR = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    _isoRange.value = isoR

                    val ssR = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    _ssRange.value = ssR

                    // Clamp the initial values into the supported range
                    isoR?.let {
                        if (_currentIso.value < it.lower || _currentIso.value > it.upper) {
                            _currentIso.value = it.lower
                        }
                    }
                    ssR?.let {
                        if (_currentSs.value < it.lower || _currentSs.value > it.upper) {
                            _currentSs.value = it.lower
                        }
                    }

                    // OIS support
                    val oisModes = characteristics.get(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                    )
                    _isOisAvailable.value = oisModes != null &&
                            oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    Log.d("PreviewViewModel", "OIS available: ${_isOisAvailable.value}, modes: ${oisModes?.toList()}")
                }

            } catch (e: Exception) {
                Log.e("PreviewViewModel", "Camera bind error", e)
            }
        }
    }

    private fun bindJpegOnly(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        preview: Preview
    ) {
        val imageCaptureJpeg = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCaptureJpeg
        )
        imageCapture = imageCaptureJpeg
        _isRawJpegMode.value = false
        Log.d("PreviewViewModel", "Bound in JPEG-only mode")
    }

    fun setManualMode(enabled: Boolean) {
        _isManualMode.value = enabled
        applyExposureSettings()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setOisEnabled(enabled: Boolean) {
        _isOisEnabled.value = enabled
        camera?.let { cam ->
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            val oisMode = if (enabled)
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            else
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                .build()
            camera2Control.addCaptureRequestOptions(options)
            Log.d("PreviewViewModel", "OIS set to: ${if (enabled) "ON" else "OFF"}")
        }
    }

    fun setIso(iso: Int) {
        _currentIso.value = iso
        if (_isManualMode.value) {
            applyExposureSettings()
        }
    }

    fun setSs(ss: Long) {
        _currentSs.value = ss
        if (_isManualMode.value) {
            applyExposureSettings()
        }
    }

    fun setPresetA(iso: Int, ss: Long) {
        val preset = ExposurePreset(iso, ss)
        _presetA.value = preset
        savePreset("A", preset)
    }

    fun setPresetB(iso: Int, ss: Long) {
        val preset = ExposurePreset(iso, ss)
        _presetB.value = preset
        savePreset("B", preset)
    }

    fun setPresetC(iso: Int, ss: Long) {
        val preset = ExposurePreset(iso, ss)
        _presetC.value = preset
        savePreset("C", preset)
    }

    fun setModelName(name: String) {
        _modelName.value = name
        prefs.edit().putString("model_name", name).apply()
    }

    fun incrementScene() {
        val newVal = _sceneCounter.value + 1
        _sceneCounter.value = newVal
        prefs.edit().putInt("scene_counter", newVal).apply()
        resetTriggerCount()
    }

    fun decrementScene() {
        val newVal = maxOf(0, _sceneCounter.value - 1)
        _sceneCounter.value = newVal
        prefs.edit().putInt("scene_counter", newVal).apply()
        resetTriggerCount()
    }

    fun resetTriggerCount() {
        _triggerCount.value = 0
        prefs.edit().putInt("trigger_count", 0).apply()
        unlockPlaybackSettings()
    }

    /**
     * Playback mode: lock AWB and AE, and switch AF to AUTO to hold the current focus.
     * Called right after the first capture of a scene; the locks persist for the rest of the scene.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun lockPlaybackSettings() {
        if (_isPlaybackLocked.value) return
        _isPlaybackLocked.value = true
        camera?.let { cam ->
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_AUTO
                )
                .build()
            camera2Control.addCaptureRequestOptions(options)
        }
        Log.d("PreviewViewModel", "Playback locks applied: AWB_LOCK, AE_LOCK, AF_MODE_AUTO")
    }

    /**
     * Playback mode: release the AWB/AE locks and restore continuous AF.
     * Called on n0 reset, scene change, and Playback toggle.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun unlockPlaybackSettings() {
        if (!_isPlaybackLocked.value) return
        _isPlaybackLocked.value = false
        camera?.let { cam ->
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                .build()
            camera2Control.addCaptureRequestOptions(options)
        }
        Log.d("PreviewViewModel", "Playback locks released: AWB, AE unlocked, AF back to CONTINUOUS")
    }

    /**
     * Builds the base file name and, in Playback mode, increments the trigger count.
     * Playback mode: GBP_S{x}_n{y}_{timestamp}_{model}_{iso}_{ss}
     * Otherwise:     GBP_Sx_nx_{timestamp}_{model}_{iso}_{ss}
     */
    fun buildFileNameAndIncrementTrigger(timestamp: Long, isPlaybackMode: Boolean): String {
        val model = _modelName.value
        val iso = _currentIso.value
        val ss = (1_000_000_000 / _currentSs.value).toInt()

        return if (isPlaybackMode) {
            val x = _sceneCounter.value
            val y = _triggerCount.value + 1
            _triggerCount.value = y
            prefs.edit().putInt("trigger_count", y).apply()
            "GBP_S${x}_n${y}_${timestamp}_${model}_${iso}_${ss}"
        } else {
            "GBP_Sx_nx_${timestamp}_${model}_${iso}_${ss}"
        }
    }

    // EV offset (ISO only), in stops
    private val _evOffset = MutableStateFlow(0)
    val evOffset: StateFlow<Int> = _evOffset.asStateFlow()

    fun selectPreset(preset: String) {
        _selectedPreset.value = preset
        _evOffset.value = 0
        val exposurePreset = when (preset) {
            "A" -> _presetA.value
            "B" -> _presetB.value
            "C" -> _presetC.value
            else -> return
        }
        _currentIso.value = exposurePreset.iso
        _currentSs.value = exposurePreset.ss
        if (_isManualMode.value) {
            applyExposureSettings()
        }
    }

    /**
     * Shifts EV by +/-1 stop using ISO only: ISO = preset ISO * 2^offset.
     * No-op if the result falls outside the sensor's ISO range.
     */
    fun adjustEvByIso(delta: Int) {
        val basePreset = when (_selectedPreset.value) {
            "A" -> _presetA.value
            "B" -> _presetB.value
            "C" -> _presetC.value
            else -> return
        }
        val newOffset = _evOffset.value + delta
        val newIso = (basePreset.iso * Math.pow(2.0, newOffset.toDouble())).toInt()

        val range = _isoRange.value ?: return
        if (newIso < range.lower || newIso > range.upper) return

        _evOffset.value = newOffset
        _currentIso.value = newIso
        if (_isManualMode.value) {
            applyExposureSettings()
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposureSettings() {
        camera?.let { cam ->
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)

            val captureRequestOptions = if (_isManualMode.value) {
                androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        _currentIso.value
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        _currentSs.value
                    )
                    .build()
            } else {
                androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_ON
                    )
                    .build()
            }

            camera2Control.addCaptureRequestOptions(captureRequestOptions)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun takePicture(context: Context, fullBaseName: String) {
        try {
            val capture = imageCapture ?: run {
                Log.e("PreviewViewModel", "ImageCapture is null")
                return
            }

            Log.d("PreviewViewModel", "Capture started: $fullBaseName")
            Log.d("PreviewViewModel", "Manual Mode: ${_isManualMode.value}, ISO: ${_currentIso.value}, SS: 1/${(1_000_000_000 / _currentSs.value).toInt()}")

            // Save to Pictures/CameraXApp
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val appDir = File(picturesDir, "CameraXApp")

            if (!appDir.exists()) {
                val created = appDir.mkdirs()
                Log.d("PreviewViewModel", "Created directory: ${appDir.absolutePath}, success: $created")
            }

            // Re-apply the manual exposure before capturing
            if (_isManualMode.value) {
                Log.d("PreviewViewModel", "Manual mode: applying exposure settings")
                try {
                    applyExposureSettings()
                } catch (e: Exception) {
                    Log.e("PreviewViewModel", "Failed to apply exposure settings", e)
                }
            }

            val jpegFile = File(appDir, "$fullBaseName.jpg")

            if (_isRawJpegMode.value) {
                // Save JPEG + DNG (OUTPUT_FORMAT_RAW_JPEG)
                val dngFile = File(appDir, "$fullBaseName.dng")
                val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile).build()
                val dngOptions = ImageCapture.OutputFileOptions.Builder(dngFile).build()

                capture.takePicture(
                    dngOptions,  // 1st argument: RAW
                    jpegOptions, // 2nd argument: JPEG
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Log.d("PreviewViewModel", "Saved JPEG+DNG")
                            Log.d("PreviewViewModel", "  JPEG: ${jpegFile.absolutePath}")
                            Log.d("PreviewViewModel", "  DNG: ${dngFile.absolutePath}")
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("PreviewViewModel", "Capture error: ${exception.message}", exception)
                        }
                    }
                )
            } else {
                // JPEG only
                val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile).build()

                capture.takePicture(
                    jpegOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Log.d("PreviewViewModel", "Saved JPEG: ${jpegFile.absolutePath}")
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("PreviewViewModel", "Capture error: ${exception.message}", exception)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("PreviewViewModel", "takePicture failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
    }
}

// Extension function to convert ListenableFuture to suspend function
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }, Executors.newSingleThreadExecutor())
}
