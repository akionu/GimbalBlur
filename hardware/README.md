# GimbalBlur Hardware

Three custom 3-axis gimbals, each carrying one smartphone, replay recorded hand-shake trajectories and trigger the phone's capture app (`../app`, replay mode) through a short pulse on the roll axis.

## Parts

- Motors: 3 x Mercury Motor DDM4006 direct-drive BLDC motors with integrated drivers per gimbal (yaw, pitch, roll).
- Bus: the three motors of a gimbal share one RS485 bus (address 1 = yaw, 2 = pitch, 3 = roll; 921600 baud).
- PC link: one FTDI USB-RS485 adapter (USB ID `0403:6001`) per gimbal, on a Linux laptop.
- Phones: Oppo Reno 11A, Xiaomi Poco X7, Samsung Galaxy A36 5G.

## CAD

STEP files of the custom gimbal parts, in `cad/`:

- `gimbal-bldc_frame2mount.step`
- `gimbal-bldc_yaw2frame.step`
- `gimbal-bldc_pitch2yaw.step`
- `gimbal-bldc_roll2pitch.step`

## Build

Requires Linux, CMake >= 3.13, and a C++17 compiler.

```bash
cd control
cmake -S . -B build
cmake --build build
```

This builds `build/home` and `build/ddm4006_ctrl_trig_align`. Run all commands below from `control/`.

## udev setup

Give the three adapters fixed names `/dev/ttyGBL0..2`. Read each adapter's serial with `udevadm info -a -n /dev/ttyUSB0 | grep '{serial}'`, then create `/etc/udev/rules.d/99-gimbal.rules`:

```udev
SUBSYSTEM=="tty", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6001", ATTRS{serial}=="<SERIAL_0>", SYMLINK+="ttyGBL0"
SUBSYSTEM=="tty", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6001", ATTRS{serial}=="<SERIAL_1>", SYMLINK+="ttyGBL1"
SUBSYSTEM=="tty", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6001", ATTRS{serial}=="<SERIAL_2>", SYMLINK+="ttyGBL2"
```

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger
ls -la /dev/ttyGBL*
```

## Usage

```bash
bash homeall.sh          # move all 3 gimbals to their origin
bash all_align.sh <g>    # g = 0..9: replay cl{10g}..cl{10g+9} on all 3 gimbals in parallel
```

- `origin.cfg`: per-device origin (yaw/pitch/roll), trigger scale `trig_mul`, and runtime gain overrides. The shipped values are for our rig.
- `data/ref_wxa.csv`: trigger pulse (shared by the three gimbals). `data/stopr.csv`: static file sent twice after a group.
- Each run writes one line per shot to `log/trig_YYYYMMDD.csv`.

## Trajectories

`control/cl/cl<i>_subject<j>.csv` (i = 0..99; j = anonymized subject ID), 1024 rows each:

| Column | Meaning |
| --- | --- |
| `time_us` | time from the first sample [us], non-uniform |
| `wx`, `wy`, `wz` | phone angular velocity [rad/s] |
| `ax`, `ay`, `az` | phone acceleration [m/s^2] |
| `qx`, `qy`, `qz`, `qw` | phone orientation quaternion |
| `roll`, `pitch`, `yaw` | phone orientation [rad] |
| `flag` | 1 at the shutter-press sample, else 0 |

Each replayed trajectory `cl<i>` is the recording nearest to the i-th K-means centroid (K = 100).

## Third-party

`control/lib/fast-cpp-csv-parser` (BSD-3-Clause, see its `LICENSE`).
