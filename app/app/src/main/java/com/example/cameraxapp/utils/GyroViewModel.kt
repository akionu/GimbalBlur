package com.example.cameraxapp.utils

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class GyroViewModel : ViewModel() {
    private val _gyroStr = MutableStateFlow("waiting for gyro")
    val gyroStr: StateFlow<String> = _gyroStr.asStateFlow()

    fun update(wx: Float, wy: Float, wz: Float, dt: Double, maxPulseSamples: Int) {
        _gyroStr.value = String.format(
            Locale.US,
            "gyro (wx,wy,wz)=\n (\n %+2.3f\n %+2.3f\n %+2.3f\n)[rad/s] \nT=%2.3f[ms]\nf=%3.0f[Hz]\nmaxPulse=%d",
            wx, wy, wz, dt / 1000.0, (1000000.0 / dt), maxPulseSamples
        )
    }
}
