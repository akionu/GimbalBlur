package com.example.cameraxapp.utils

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.OutputStreamWriter
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.util.Log

// One fused IMU sample, recorded at each gyroscope event
data class SensorData(
    val timestamp: Long,  // [ns]
    // Angular velocity (gyroscope)
    var wx: Float = 0f,
    var wy: Float = 0f,
    var wz: Float = 0f,
    // Acceleration (accelerometer)
    var ax: Float = 0f,
    var ay: Float = 0f,
    var az: Float = 0f,
    // Orientation quaternion (rotation vector)
    var qx: Float = 0f,
    var qy: Float = 0f,
    var qz: Float = 0f,
    var qw: Float = 0f, // scalar part
    // Orientation as SensorManager.getOrientation() angles [rad]
    var roll: Float = 0f,  // rotation about Y
    var pitch: Float = 0f, // rotation about X
    var yaw: Float = 0f,    // azimuth, rotation about -Z
    var rotationMatrix: FloatArray? = null
)

class SensorDataManager(
    private val context: Context,
    // UI update callback
    private val onUpdate: (wx: Float, wy: Float, wz: Float, dt: Double, maxPulseSamples: Int) -> Unit
) : SensorEventListener2 {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var lastTimestamp: Long = 0L

    // Ring buffer
    private val bufferSize = 1024 // number of samples kept
    private val buffer = Array<SensorData?>(bufferSize) { null }
    private var currentIndex = 0

    @Volatile
    private var filled = false

    // Trigger and CSV export state
    @Volatile
    private var triggerPending = false
    private var triggerIndex = -1
    private var postCount = 0
    private var targetFilename: String? = null

    // Latest value of each sensor
    private val latestGyro = FloatArray(3)
    private val latestAccel = FloatArray(3)
    private val latestRotation = FloatArray(5) // x,y,z,w, accuracy

    // Playback mode
    @Volatile
    private var playbackMode = false
    private var autoShutterCallback: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoShutterRunnable: Runnable? = null

    // Pulse detection
    private val targetWz = 1.5f // [rad/s]
    private val wzToleranceLow = 0.9f // [rad/s]
    private val wzToleranceUp = 0.5f // [rad/s]
    private val minPulseDuration = 1 // min samples
    private var avgDtMs = 0.0 // moving average of the gyro sample period [ms]
    private var dynamicMaxPulseDuration = 4 // max samples, derived from the gyro rate
    private val shutterDelayMs = 3152L // 2000 + 1152 (512 samples)
    private val cooldownMs = 1500L // cooldown after a capture

    private var pulseCount = 0 // consecutive samples inside the pulse window
    private var lastPulseDetected = false // whether the previous sample was inside the pulse window
    private var pulseStartTimeNs = 0L // pulse start time [ns] (System.nanoTime)

    fun start() {
        gyroSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
        accelSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
        rotationSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // Call at the moment a photo is taken
    fun trigger(filename: String) {
        if (filled) {
            triggerPending = true
            // Index of the latest sample at trigger time
            triggerIndex = (currentIndex - 1 + bufferSize) % bufferSize
            postCount = 0
            targetFilename = filename
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, latestGyro, 0, 3)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, latestAccel, 0, 3)
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                // values: x,y,z,w[,estimated_accuracy]; older devices may report only x,y,z
                if (event.values.size >= 4) {
                    System.arraycopy(event.values, 0, latestRotation, 0, 4)
                }
            }
        }

        // Each gyroscope event produces one fused sample in the buffer
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val now = event.timestamp
            val dt = if (lastTimestamp != 0L) (now - lastTimestamp) / 1_000_000.0 else 0.0
            lastTimestamp = now

            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, latestRotation)

            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // rad
            val yaw = orientationAngles[0]
            val pitch = orientationAngles[1]
            val roll = orientationAngles[2]

            val data = SensorData(
                timestamp = now,
                wx = latestGyro[0],
                wy = latestGyro[1],
                wz = latestGyro[2],
                ax = latestAccel[0],
                ay = latestAccel[1],
                az = latestAccel[2],
                qx = latestRotation[0],
                qy = latestRotation[1],
                qz = latestRotation[2],
                qw = latestRotation[3],
                rotationMatrix = rotationMatrix,
                yaw = yaw,
                pitch = pitch,
                roll = roll
            )

            buffer[currentIndex] = data
            currentIndex = (currentIndex + 1) % bufferSize
            if (currentIndex == 0) {
                filled = true
            }

            // Max pulse length from the gyro rate, linear model: N = 0.03*f - 8 (400Hz->4, 500Hz->7)
            if (dt > 0) {
                avgDtMs = if (avgDtMs == 0.0) dt else avgDtMs * 0.99 + dt * 0.01
                val freqHz = 1000.0 / avgDtMs
                dynamicMaxPulseDuration = kotlin.math.round(0.03 * freqHz - 8.0).toInt().coerceIn(3, 10)
            }

            onUpdate(data.wx, data.wy, data.wz, dt, dynamicMaxPulseDuration)

            // Collect post-trigger samples
            if (triggerPending) {
                postCount++
                if (postCount >= 512) { // save once about half the buffer has passed since the trigger
                    targetFilename?.let { saveTriggeredCsv(it) }
                    triggerPending = false
                    targetFilename = null
                }
            }

            if (playbackMode) {
                detectPulse(data.wz)
            }
        }
    }

    fun setPlaybackMode(enabled: Boolean) {
        playbackMode = enabled
        if (!enabled) {
            // Cancel any pending shutter and reset the detector
            cancelAutoShutter()
            pulseCount = 0
            lastPulseDetected = false
        }
        Log.d("SensorDataManager", "Playback mode: $enabled")
    }

    fun setAutoShutterCallback(callback: () -> Unit) {
        autoShutterCallback = callback
    }

    // Detect the wz trigger pulse emitted by the gimbal
    private fun detectPulse(wz: Float) {
        val isPulse = targetWz-wzToleranceLow <= wz && wz <= targetWz+wzToleranceUp

        if (isPulse) {
            if (!lastPulseDetected) {
                pulseStartTimeNs = System.nanoTime()
            }
            pulseCount++
            lastPulseDetected = true
            Log.d("SensorDataManager", "Pulse detected: wz=$wz, count=$pulseCount")
        } else {
            if (lastPulseDetected) {
                // Pulse ended
                if (pulseCount in minPulseDuration..dynamicMaxPulseDuration) {
                    val pulseEndTimeNs = System.nanoTime()
                    val pulseDurationMs = (pulseEndTimeNs - pulseStartTimeNs) / 1_000_000.0
                    val pulseCenterOffsetMs = (pulseDurationMs / 2.0).toLong()
                    Log.d("SensorDataManager", "Valid pulse detected! Duration: $pulseCount samples (${pulseDurationMs}ms), center offset: ${pulseCenterOffsetMs}ms")
                    scheduleAutoShutter(pulseCenterOffsetMs)
                } else {
                    Log.d("SensorDataManager", "Invalid pulse duration: $pulseCount samples (expected $minPulseDuration-$dynamicMaxPulseDuration)")
                }
                pulseCount = 0
                lastPulseDetected = false
            }
        }
    }

    // Schedule the shutter relative to the pulse center
    private fun scheduleAutoShutter(pulseCenterOffsetMs: Long) {
        // Ignore new pulses while a shutter or cooldown is pending
        if (autoShutterRunnable != null) {
            Log.d("SensorDataManager", "Auto shutter already scheduled, ignoring new pulse")
            return
        }

        // Delay from pulse end = base delay - (pulse center -> pulse end)
        val adjustedDelayMs = shutterDelayMs - pulseCenterOffsetMs

        autoShutterRunnable = Runnable {
            Log.d("SensorDataManager", "Auto shutter triggered!")
            autoShutterCallback?.invoke()
            val cooldownRunnable = Runnable {
                autoShutterRunnable = null
                Log.d("SensorDataManager", "Cooldown complete, ready for next trigger")
            }
            autoShutterRunnable = cooldownRunnable
            handler.postDelayed(cooldownRunnable, cooldownMs)
        }
        handler.postDelayed(autoShutterRunnable!!, adjustedDelayMs)
        Log.d("SensorDataManager", "Auto shutter scheduled in ${adjustedDelayMs}ms (base=${shutterDelayMs}ms - offset=${pulseCenterOffsetMs}ms)")
    }

    private fun cancelAutoShutter() {
        autoShutterRunnable?.let {
            handler.removeCallbacks(it)
            autoShutterRunnable = null
            Log.d("SensorDataManager", "Auto shutter cancelled")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveTriggeredCsv(filename: String) {
        val collectedData = mutableListOf<Pair<SensorData, Int>>()

        // Read the whole ring buffer in chronological order
        val startIndex = if (filled) currentIndex else 0
        for (i in 0 until bufferSize) {
            val idx = (startIndex + i) % bufferSize
            buffer[idx]?.let { data ->
                val flag = if (idx == triggerIndex) 1 else 0
                collectedData.add(data to flag)
            }
        }

        // Sort by timestamp to be safe
        val sortedData = collectedData.sortedBy { it.first.timestamp }
        if (sortedData.isEmpty()) return

        val t0 = sortedData.first().first.timestamp

        // Write to Documents/CameraXApp via MediaStore
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CameraXApp")
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            // Fallback for Android 9 and below (may require storage permission)
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, contentValues)

        uri?.let { fileUri ->
            try {
                resolver.openOutputStream(fileUri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).buffered().use { writer ->
                        writer.write("time_us,wx,wy,wz,ax,ay,az,qx,qy,qz,qw,roll,pitch,yaw,flag\n")
                        for ((data, flag) in sortedData) {
                            val timeUs = (data.timestamp - t0) / 1000 // ns to us

                            val line = String.format(
                                Locale.US,
                                "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d",
                                timeUs,
                                data.wx, data.wy, data.wz,
                                data.ax, data.ay, data.az,
                                data.qx, data.qy, data.qz, data.qw,
                                data.roll, data.pitch, data.yaw,
                                flag
                            )
                            writer.write(line + "\n")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onFlushCompleted(sensor: Sensor?) {}
}
