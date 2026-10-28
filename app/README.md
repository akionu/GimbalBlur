# GimbalBlur Capture App

Android app used to capture GimbalBlur. It takes photos with the back camera at a fixed ISO and shutter speed and saves JPEG and DNG together. Each capture also gets a CSV of IMU samples recorded around it. In Playback mode the gimbal fires the shutter.

## Build and install

Requires JDK 17+ (e.g. the JBR bundled with Android Studio) and the Android SDK (platform 36).

```
ANDROID_HOME=/path/to/Android/Sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app installs as `CameraXApp` (`com.example.cameraxapp`, minSdk 26). IMU CSV export needs Android 10 (API 29) or later.

## Usage

- **Settings** (gear icon): model name used in file names, exposure presets A/B/C (ISO, shutter 1/x s), and an OIS switch on devices that support OIS.
- **Manual**: turns AE off and applies the selected preset's ISO and exposure time. EV -1/+1 scales the ISO by powers of 2.
- **Shutter button**: captures one photo when Playback is off.
- **Playback**: auto-shutter for the gimbal. The gimbal sends a short rotation pulse, detected when `wz` stays in [0.6, 2.0] rad/s for 1 to N gyro samples (N = round(0.03 f - 8), clamped to [3, 10], f = gyro rate in Hz). The photo is taken 3152 ms after the pulse center. Further pulses are ignored until 1.5 s after the capture. `-`/`+` change the scene counter, and `n0` resets the capture count.

In Playback mode, right after the first capture of a scene the app sets `CONTROL_AWB_LOCK` and `CONTROL_AE_LOCK` and switches AF from `CONTINUOUS_PICTURE` to `AUTO`. White balance, exposure and focus then stay fixed for the rest of the scene. The locks are released on `n0`, on a scene change, or when Playback is toggled. Noise reduction, tone mapping, edge enhancement and OIS keep the CameraX/device defaults unless OIS is toggled in Settings.

## Output

| File | Location |
|---|---|
| `<name>.jpg`, `<name>.dng` | `Pictures/CameraXApp/` |
| `<name>.csv` | `Documents/CameraXApp/` |

The DNG is saved only on devices that support simultaneous RAW+JPEG capture.

`<name>` is `GBP_S<scene>_n<count>_<unix_ts>_<model>_<iso>_<ss>`:

- `<scene>`: scene counter. `<count>`: capture index within the scene, starting at 1. Shutter-button captures (Playback off) use `Sx_nx`.
- `<unix_ts>`: Unix time in seconds. `<model>`: model name from Settings.
- `<iso>`, `<ss>`: the manual ISO and shutter denominator (exposure = 1/`<ss>` s). These are the manual values even when Manual is off.

## IMU CSV

The gyroscope, accelerometer and rotation-vector sensors run at `SENSOR_DELAY_FASTEST`. Every gyroscope event adds one row, with the most recent accelerometer and rotation-vector values attached. Each CSV has 1024 rows: 512 up to and including the capture sample, then 512 after it.

| Column | Description |
|---|---|
| `time_us` | time since the first row [us], from `SensorEvent.timestamp` |
| `wx, wy, wz` | angular velocity [rad/s] |
| `ax, ay, az` | acceleration incl. gravity [m/s^2] |
| `qx, qy, qz, qw` | rotation-vector quaternion |
| `roll, pitch, yaw` | `SensorManager.getOrientation()` angles [rad], yaw = azimuth |
| `flag` | 1 on the latest sample when the capture was requested, else 0 |
