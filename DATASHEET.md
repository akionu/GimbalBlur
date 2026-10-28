# Datasheet for GimbalBlur

Follows the structure of Gebru et al., "Datasheets for Datasets". Version: 1.0.

Paper: "GimbalBlur: IMU-Paired Multi-Device Deblurring Benchmark in the Wild", Akio Muragishi and Masahiro Okuda, Doshisha University, WACV 2027.

## Motivation

**Why was the dataset created?**
Existing deblurring benchmarks either synthesize blur (no native smartphone ISP, no sensor noise) or capture real blur with dual-camera rigs (sensor mismatch, irreproducible conditions). GimbalBlur provides real smartphone blur that is reproducible, paired with the gyroscope signal of the same shot, and captured by three phone models under identical blur.

**Who created it?**
Akio Muragishi and Masahiro Okuda, Doshisha University.

## Composition

**What do the instances represent?**
Each instance is a blurred image and a sharp ground-truth image of the same static scene, taken by the same phone with the same shutter speed, ISO, and white balance, plus the IMU recording of the blurred shot.

**How many instances are there?**
200 indoor and outdoor scenes (`scene_000` to `scene_199`), 3 phones, 3 shutter speeds (1/2, 1/8, 1/30 s), 10 trajectories per scene per shutter speed and phone (90 target pairs per scene). Of the 18,000 target pairs, 17,499 are valid J pairs; the D modality adds 11,498 pairs. Missing pairs come from failed captures and from images removed for privacy.

| Device | Modality | Pairs |
|---|---|---|
| Oppo Reno 11A | J (native ISP JPEG) | 5,890 |
| Oppo Reno 11A | D (straight-developed DNG) | 5,740 |
| Xiaomi Poco X7 | J | 5,898 |
| Xiaomi Poco X7 | D | 5,758 |
| Samsung Galaxy A36 5G | J (no DNG available) | 5,711 |

**What data does each instance consist of?**
Blurred and sharp JPEG, blurred and sharp DNG with its straight-developed PNG (Oppo and Xiaomi only), and one IMU CSV per blurred capture. Phones capture in the Android app in `app/` with manual ISO and shutter speed.

**Is there a label or target?**
The sharp image of the same scene is the target. The ground truth is captured sequentially, not simultaneously, so it is offset from the blurred image; see Preprocessing.

**Are there recommended data splits?**
Yes. Splits are by scene and shared across devices: 141 training, 16 validation, and 43 test scenes (J: 12,276 / 1,403 / 3,820 pairs; D: 8,035 / 947 / 2,516 pairs), as used in the paper. Scenes 000 to 100 were split first, and scenes 101 to 199 were added with the same rule (test ratio 50/232, seed 2027; validation carved from training with seed 2028). Pair lists in RealBlur format (`<gt path> <blur path>`) are in `splits/`.

**Are there errors, noise, or redundancy?**
The ground truth is not pixel-exact. Registration fails for 13.7% of the JPEG test pairs and for 12.3% of all released pairs (3,561 of 28,997); these keep the unwarped sharp image (see Preprocessing). Higher ISO at shorter exposures increases sensor noise. Replayed trajectories are reused across scenes (10 of the 100 per scene, cycling).

**Does the dataset contain sensitive or personal information?**
Scenes were photographed with the intent to exclude people. Images showing identifiable passers-by were removed or replaced with another capture of the same setting (scenes 008, 011, 166); person detections in other images were reviewed. Capture dates, times, and locations are removed from file names, EXIF, and IMU files. Hand-shake trajectories carry anonymized subject IDs only.

## Collection process

**How was the data acquired?**
Three stages:

1. Hand-shake recording. 11 participants photographed scenes with a custom Android app on a Google Pixel 6a (gyroscope about 415 Hz in the paper). From 193 sessions, trajectories exceeding the gimbal's physical limits were discarded, leaving 115.
2. Trajectory selection. K-means (K = 100) on the zero-padded time series gives 100 clusters. Each replayed trajectory is the recording nearest to a cluster centroid. The 100 replayed trajectories come from 10 subjects.
3. Replay. Three identical 3-axis gimbals (Mercury Motor DDM4006, roll/pitch/yaw, RS485, PI current control) each carry one phone and replay the angular velocity at the original non-uniform timestamps. For each scene and trajectory the phone first captures the sharp image with the gimbal at rest, then the blurred image during replay, with the same shutter speed, ISO, and white balance.

**Synchronization.**
Before each replay the gimbal sends a short rectangular pulse on the roll axis. The app detects it in the gyroscope stream and fires the shutter after a calibrated delay. The paper reports a timing offset within 4 samples (9 ms) between commanded and recorded angular velocity across the three gimbals.

**Capture settings.**
Shutter speeds 1/2, 1/8, 1/30 s. ISO is set per scene so brightness is roughly constant across shutter speeds. In replay mode the app locks auto white balance, auto exposure, and focus after the first capture of a scene. Scene distances are about 3 to 15 m.

## IMU data

One CSV per blurred capture, recorded by the capturing phone, with the following columns:

| Column | Unit / meaning |
|---|---|
| `time_us` | microseconds since the first row (relative; no wall-clock time) |
| `wx`, `wy`, `wz` | angular velocity [rad/s] |
| `ax`, `ay`, `az` | acceleration including gravity [m/s^2] |
| `qx`, `qy`, `qz`, `qw` | rotation-vector quaternion |
| `roll`, `pitch`, `yaw` | orientation from `SensorManager.getOrientation()` [rad], yaw = azimuth |
| `flag` | 1 at the sample where the capture was requested, else 0 |

- Each CSV has 1024 rows: 512 up to and including the `flag` sample, and 512 after it. One row is added per gyroscope event, with the latest accelerometer and rotation-vector values attached.
- The sampling is non-uniform. The paper states the IMU rate as above 400 Hz. The replayed trajectory CSVs measure about 443 Hz on the recording phone. Gyroscope rates of the capturing phones are device dependent.
- The exposure does not start exactly at the `flag` sample. The flag marks when the app requested the capture; shutter latency is device dependent and not calibrated per device here.
- The 100 replayed trajectories in `hardware/control/cl/` use the same columns and `flag` convention (flag = shutter press in the original recording).

## Preprocessing, cleaning, labeling

**GimbalBlur-Fullres.**
Full-resolution JPEG, DNG and its straight-developed PNG (Oppo, Xiaomi; `dataset/develop_raw.py`), and IMU CSV. No registration or cropping.

**GimbalBlur.**
Because ground truth and blurred images are captured sequentially, they are offset by 10 to 70 px at a 1920 px long edge. Each ground truth is registered to its blurred image with a homography  registration at a 768 px long edge, then warped at full resolution. A uniform central crop keeps 61% of each axis (about 37% of the area) to remove invalid borders. The result is resized to a 1920 px long edge and stored as PNG. When registration diverges (the warp would leave invalid pixels deeper than 20% of the image inside the border), the pair is kept with an identity transform instead of being dropped.

**Calibration.**
Per-device pinhole intrinsics and Brown-Conrady distortion from a 9 x 6 checkerboard (25 mm squares), in `calibration/intrinsics.json`. Reprojection RMS is 2.4 to 2.9 px. Checkerboard images are included without date metadata.

**Is the preprocessing software available?**
Yes, in the GitHub repository (`akionu/GimbalBlur`).

## Uses

**Intended uses.**
Training and benchmarking single-image and IMU-aided deblurring on real smartphone images; studying how device ISP pipelines change the score under identical blur (JPEG vs. DNG, three phones).

**Tasks the dataset is not suited for.**
Blur from object motion or close-range translation, rolling-shutter analysis beyond what the native pipeline gives, and any evaluation requiring pixel-exact ground truth.

**Known limitations.**
- Scenes are static; there is no object motion.
- Replay is rotational only; close-range translation is not reproduced. At 3 to 15 m, parallax from translation is small.
- All phones run Android; iOS is not covered.
- JPEG and DNG results measure the aggregate ISP pipeline, not individual stages.
- Failed registrations keep the unwarped ground truth, so scores on those pairs are lower. In the paper, excluding them raises PSNR by 1.36 to 1.51 dB for eight checked methods without changing any ranking.
- Samsung Galaxy A36 5G has JPEG only.
- Hand-shake comes from 11 participants on one recording phone.

## Distribution

**How is the dataset distributed?**
Two releases on Hugging Face: [GimbalBlur](https://huggingface.co/datasets/akiomu/GimbalBlur) (about 224 GiB) and [GimbalBlur-Fullres](https://huggingface.co/datasets/akiomu/GimbalBlur-Fullres) (about 692 GiB). Code, app, hardware files, trajectories, calibration, and split lists are on GitHub: `akionu/GimbalBlur`.

**License.**
Dataset: CC BY 4.0. Code: BSD-3-Clause. Third-party code (`fast-cpp-csv-parser`) keeps its own BSD-3-Clause license.

**Export controls or other restrictions?**
None known to the authors.

## Maintenance

**Who maintains the dataset?**
The authors.

**Will it be updated?**
Scenes may be added in future versions. Releases are versioned; the split lists of each version are kept with it.

**How can others report errors or contribute?**
Through GitHub issues on `akionu/GimbalBlur`.
