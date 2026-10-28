#include <iostream>
#include <chrono>
#include <thread>
#include <string>
#include <cmath>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>

#include "DDM4006.hpp"
#include "csv.h"
#include "origin_utils.hpp"

// 0: yaw, 1: pitch, 2: roll
// yaw tends to oscillate around count 0
// 360[deg] == 2^14 = 16384
const std::array<int32_t, 3> kDefaultOrigin = {8192, 4096, 0};

std::array<uint32_t, 16> data_time_us_trig = {0};
std::array<std::array<float, 3>, 16> data_w_rpm_trig = {{0.0f}};
int16_t data_valid_i_trig = 0;

std::array<uint32_t, 4096> data_time_us_play = {0};
std::array<std::array<float, 3>, 4096> data_w_rpm_play = {{0.0f}};
int16_t data_valid_i_play = 0;

const double RAD2DEG = 57.295779;
const double RAD2RPM = (RAD2DEG*60.0/360.0);
const uint32_t SHUTTER_DELAY_US = 2000*1000;
const double DEFAULT_TRIG_MUL=1.05;

// Capture app (replay mode): a valid pulse schedules a shot 3152 ms later,
// and pulses are ignored until 1500 ms after the shot. The process holds still
// until CYCLE_US after the trigger so that the next run's trigger lands just
// after that cooldown.
const uint32_t CYCLE_US = 4950*1000;
// The repositioning move runs while the app ignores pulses (after the trigger).
const uint32_t MOVE_START_US = 100*1000;
const uint32_t MOVE_END_US = 1800*1000;
const double MOVE_PEAK_RAD_S = 1.5;      // nominal peak speed of the min-jerk move
const uint32_t RAMP_DOWN_US = 60*1000;   // playback end -> zero speed
const uint32_t POS_LIMIT_001RPM = 60*100; // position-loop speed cap, 60 RPM
const double STILL_RPM = 3.0;
const double VIB_WARN_RPM = 3.0;
// playback stall check: healthy axes stay under ~0.15 s, a snagged one 0.8 s+
const double STALL_CMD_RPM = 3.0;
const double STALL_SPD_RPM = 0.5;
const double STALL_WARN_S = 0.4;

constexpr size_t PARAM_POS_KP = 0;   // offsets within 24-byte motion ctrl payload
constexpr size_t PARAM_POS_LIMIT = 8;
constexpr size_t PARAM_SPD_KP = 12;
constexpr size_t PARAM_SPD_KI = 16;

struct MotorState {
  int32_t multi_cnt = 0;
  double rpm = 0;
};

// Parse a 0x0B-like response (realtime, speed, position commands).
bool parse_state(const std::vector<uint8_t>& frame, MotorState& state) {
  if (frame.size() < 5 + 10) return false;
  const uint8_t* p = frame.data() + 5;
  int32_t spd;
  std::memcpy(&state.multi_cnt, p + 2, 4);
  std::memcpy(&spd, p + 6, 4);
  state.rpm = spd / 100.0;
  return true;
}

bool read_state(DDM4006& comm, uint8_t axis, MotorState& state) {
  for (int retry = 0; retry < 3; ++retry) {
    try {
      if (parse_state(comm.readRealtime(axis), state)) return true;
    } catch (...) {}
  }
  return false;
}

// Write runtime motion params (0x15, not saved). Reading 0x14 makes the
// driver reload the saved params, so read once here and only write after.
// Optional per-device overrides in origin.cfg: <yaw|pitch|roll>_<pos_kp|spd_kp|spd_ki>.
void apply_runtime_params(DDM4006& comm, const std::string& config_path,
                          const std::string& device_name) {
  static const char* names[3] = {"yaw", "pitch", "roll"};
  for (uint8_t axis = 1; axis <= 3; ++axis) {
    try {
      auto resp = comm.readMotionCtrl(axis);
      uint8_t len = resp[4];
      std::vector<uint8_t> payload(resp.begin() + 5, resp.begin() + 5 + len);
      std::string name = names[axis-1];
      const std::pair<const char*, size_t> gains[3] = {
        {"_pos_kp", PARAM_POS_KP}, {"_spd_kp", PARAM_SPD_KP}, {"_spd_ki", PARAM_SPD_KI}};
      for (const auto& g : gains) {
        double v = load_device_double(config_path, device_name, name + g.first, NAN);
        if (std::isnan(v)) continue;
        float f = static_cast<float>(v);
        std::memcpy(&payload[g.second], &f, 4);
      }
      std::memcpy(&payload[PARAM_POS_LIMIT], &POS_LIMIT_001RPM, 4);
      comm.writeMotionCtrl(axis, payload, false);
    } catch (const std::exception& e) {
      std::cerr << "[PARAM] axis=" << static_cast<int>(axis) << " " << e.what() << "\n";
    }
  }
}

void check_and_clear_faults(DDM4006& comm, const char* phase) {
  for (uint8_t axis = 1; axis <= 3; ++axis) {
    try {
      auto resp = comm.clearFaults(axis);
      if (resp.size() > 5) {
        uint8_t len = resp[4];
        for (uint8_t k = 0; k < len && (5+k) < resp.size(); ++k) {
          if (resp[5+k] != 0) {
            std::cerr << "[FAULT] " << phase << " axis=" << static_cast<int>(axis) << " payload:";
            for (uint8_t m = 0; m < len && (5+m) < resp.size(); ++m)
              fprintf(stderr, " %02x", resp[5+m]);
            std::cerr << "\n";
            break;
          }
        }
      }
    } catch (const std::exception& e) {
      std::cerr << "[FAULT] " << phase << " axis=" << static_cast<int>(axis)
                << " " << e.what() << "\n";
    }
  }
}

void wait_until_us(std::chrono::steady_clock::time_point target_time) {
    while (std::chrono::steady_clock::now() < target_time) {
        std::this_thread::yield();
    }
}

// Wait until every axis is still; returns the max |rpm| of the last sweep.
double wait_still(DDM4006& comm, std::chrono::milliseconds timeout) {
  auto t_end = std::chrono::steady_clock::now() + timeout;
  int quiet = 0;
  double sweep_max = 0;
  while (std::chrono::steady_clock::now() < t_end) {
    sweep_max = 0;
    for (uint8_t axis = 1; axis <= 3; ++axis) {
      MotorState s;
      if (read_state(comm, axis, s)) sweep_max = std::max(sweep_max, std::fabs(s.rpm));
    }
    if (sweep_max < STILL_RPM) {
      if (++quiet >= 5) break;
    } else {
      quiet = 0;
    }
  }
  return sweep_max;
}

int main(int argc, char* argv[]) {
  if (argc < 4) {
    std::cerr << "usage: " << argv[0] << " [device] [trig csv] [playback csv] [origin.cfg]\n";
    std::cerr << "  offset by integrating wx/wy/wz angular velocity up to flag=1\n";
    return 1;
  }

  std::string device_name = argv[1];
  std::string csv_name_trig = argv[2];
  std::string csv_name_playback = argv[3];
  const char* cfg_override = (argc >= 5) ? argv[4] : nullptr;
  std::string config_path = resolve_origin_config_path(cfg_override);
  auto origin = load_origin_config(config_path, device_name, kDefaultOrigin);
  double device_trig_mul = load_device_double(config_path, device_name, "trig_mul", DEFAULT_TRIG_MUL);

  // read trig pulse
  try {
    io::CSVReader<4> in(csv_name_trig);
    in.read_header(io::ignore_extra_column, "time_us", "wx", "wy", "wz");

    int16_t i = 0;
    uint32_t time; float wx, wy, wz;
    while (in.read_row(time, wx, wy, wz)) {
      data_time_us_trig[i] = time;
      // map to the phone's coordinate frame
      data_w_rpm_trig[i][0] = wy * RAD2RPM; // unit: RPM
      data_w_rpm_trig[i][1] = wx * RAD2RPM;
      data_w_rpm_trig[i][2] = device_trig_mul*wz * RAD2RPM;
      i++;
    }
    data_valid_i_trig = i-1;

  } catch (const std::exception& e) {
    std::cerr << "Err (trig csv): " << e.what() << '\n';
    return 1;
  }


  // read playback data with flag column
  int16_t flag1_index = -1;
  // accumulated position change in encoder steps
  double accum_steps[3] = {0, 0, 0};

  try {
    io::CSVReader<5> in(csv_name_playback);
    in.read_header(io::ignore_extra_column,
                   "time_us", "wx", "wy", "wz", "flag");

    int16_t i = 0;
    uint32_t time; float wx, wy, wz; int flag;
    while (in.read_row(time, wx, wy, wz, flag)) {
      data_time_us_play[i] = time;
      // map to the phone's coordinate frame
      data_w_rpm_play[i][0] = 1.8 * wy * RAD2RPM; // unit: RPM
      data_w_rpm_play[i][1] = 1.2 * wx * RAD2RPM;
      data_w_rpm_play[i][2] = 1.0 * wz * RAD2RPM;

      if (flag == 1 && flag1_index < 0) {
        flag1_index = i;
      }

      // integrate angular velocity up to flag=1
      if (flag1_index < 0 && i > 0) {
        double dt_sec = (time - data_time_us_play[i-1]) / 1.0e6;
        for (int j = 0; j < 3; j++) {
          // RPM -> rev/s -> rev -> steps
          accum_steps[j] += (data_w_rpm_play[i-1][j] / 60.0) * dt_sec
                            * static_cast<double>(kCountsPerRevolution);
        }
      }

      i++;
    }
    data_valid_i_play = i-1;

  } catch (const std::exception& e) {
    std::cerr << "Err (playback csv): " << e.what() << '\n';
    return 1;
  }

  bool static_playback = true;
  for (int16_t i = 0; i <= data_valid_i_play; i++) {
    for (int j = 0; j < 3; j++) {
      if (data_w_rpm_play[i][j] != 0.0f) static_playback = false;
    }
  }


  // compute offset origin so that gimbal faces straight ahead at flag=1
  std::array<int32_t, 3> offset_origin = origin;
  if (flag1_index >= 0) {
    for (int j = 0; j < 3; j++) {
      offset_origin[j] = normalize_count(
        origin[j] - static_cast<int32_t>(std::round(accum_steps[j])));
    }

    std::cout << "align flag1_idx=" << flag1_index
              << " flag1_t=" << data_time_us_play[flag1_index] << "us\n";
    for (int j = 0; j < 3; j++) {
      int32_t diff = wrap_counts(offset_origin[j] - origin[j]);
      double deg = diff / DEG2STEP;
      std::cout << "  axis" << j << ": offset=" << diff
                << " steps (" << deg << " deg)\n";
    }
  } else {
    std::cerr << "warning: no flag=1 in playback CSV, no offset applied\n";
  }


  DDM4006 comm(device_name, 921600);
  if (!comm.openPort()) {
    std::cerr << "open failed\n";
    return 1;
  }
  check_and_clear_faults(comm, "init");
  apply_runtime_params(comm, config_path, device_name);

  // The gimbal holds wherever the previous run stopped; it must be still
  // before the trigger so the phone only sees the pulse.
  double still_rpm = wait_still(comm, std::chrono::milliseconds(300));
  if (still_rpm >= STILL_RPM)
    std::cerr << "[VIB] not still before trig: " << still_rpm << " rpm\n";


  //// trig
  // Every row is sent; the reference is the first positive step (later
  // pulses are backups the app ignores once a shot is scheduled).
  try { comm.speed(3, 0, 0); } catch (...) {}
  std::cout << "begin\n";
  auto begin = std::chrono::steady_clock::now();
  auto trig = begin;
  auto trig_wall = std::chrono::system_clock::now();
  bool trig_set = false;
  for (std::int16_t i = 0; i <= data_valid_i_trig; i++) {
    wait_until_us(begin + std::chrono::microseconds(data_time_us_trig[i] - data_time_us_trig[0]));
    if (!trig_set && data_w_rpm_trig[i][2] > 0) {
      trig = std::chrono::steady_clock::now();
      trig_wall = std::chrono::system_clock::now();
      trig_set = true;
    }
    try { comm.speed(3, data_w_rpm_trig[i][2], 0); } catch (...) {}
  }
  try { comm.speed(3, 0, 0); } catch (...) {}
  auto end_trig = std::chrono::steady_clock::now();
  auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_trig - begin).count();
  std::cout << "end trig\n";
  std::cout << "time: planned: " << ((data_time_us_trig[data_valid_i_trig] - data_time_us_trig[0])/1000.0f) << ", elapsed: " << elapsed_ms << "[ms]\n";


  // reposition all axes along a min-jerk path from where the previous run
  // ended to the offset origin (pre-shifted playback start)
  wait_until_us(trig + std::chrono::microseconds(MOVE_START_US));
  std::array<int32_t, 3> move_from{}, move_to{};
  int32_t move_max_cnt = 0;
  for (uint8_t i = 1; i <= 3; i++) {
    MotorState s;
    if (!read_state(comm, i, s)) s.multi_cnt = offset_origin[i-1];
    move_from[i-1] = s.multi_cnt;
    move_to[i-1] = s.multi_cnt + wrap_counts(offset_origin[i-1] - normalize_count(s.multi_cnt));
    move_max_cnt = std::max(move_max_cnt, std::abs(move_to[i-1] - move_from[i-1]));
  }
  double move_rad = move_max_cnt * 2.0 * M_PI / kCountsPerRevolution;
  double move_s = std::min(std::max(1.875 * move_rad / MOVE_PEAK_RAD_S, 0.3),
                           (MOVE_END_US - MOVE_START_US) / 1.0e6);
  auto move_begin = std::chrono::steady_clock::now();
  while (true) {
    double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - move_begin).count() / move_s;
    if (s >= 1.0) break;
    double k = s*s*s * (10.0 - 15.0*s + 6.0*s*s);
    for (uint8_t i = 1; i <= 3; i++) {
      int32_t cnt = move_from[i-1] + static_cast<int32_t>(std::lround((move_to[i-1] - move_from[i-1]) * k));
      try { comm.absPosition(i, cnt); } catch (...) {}
    }
  }
  for (uint8_t i = 1; i <= 3; i++) {
    try { comm.absPosition(i, move_to[i-1]); } catch (...) {}
  }


  //// playback
  auto target = trig + std::chrono::microseconds(SHUTTER_DELAY_US);
  std::cout << "begin\n";
  wait_until_us(target);
  auto begin_play = std::chrono::steady_clock::now();

  std::array<float, 3> last_rpm = {0.0f, 0.0f, 0.0f};
  // time an axis is commanded to move but reports ~0 speed (cable/jig snag)
  std::array<double, 3> stall_s = {0, 0, 0};
  int8_t slice = 2;
  if (static_playback) {
    // static shot: keep holding position instead of zero-speed control
    wait_until_us(begin_play + std::chrono::microseconds(data_time_us_play[data_valid_i_play] - data_time_us_play[0]));
  } else {
    for (std::int16_t i = 0; i < data_valid_i_play-slice; i+=slice) {
     uint32_t delta_us = data_time_us_play[i+slice]-data_time_us_play[i];
     auto deadline = std::chrono::steady_clock::now()
       + std::chrono::microseconds(delta_us-25); // 25 us: per-step loop overhead compensation
      for (std::int8_t j = 1; j <= 3; j++) {
        MotorState s;
        try {
          if (parse_state(comm.speed(j, data_w_rpm_play[i][j-1], 0), s)
              && std::fabs(data_w_rpm_play[i][j-1]) > STALL_CMD_RPM && std::fabs(s.rpm) < STALL_SPD_RPM)
            stall_s[j-1] += delta_us / 1.0e6;
        } catch (...) {}
        last_rpm[j-1] = data_w_rpm_play[i][j-1];
      }
      wait_until_us(deadline);
    }
  }
  double stall_max_s = 0;
  for (int j = 0; j < 3; j++) {
    if (stall_s[j] > STALL_WARN_S)
      std::cerr << "[TRACK] " << device_name << " axis" << j+1 << " did not follow for "
                << stall_s[j] << " s (stalled? check cables/jig)\n";
    stall_max_s = std::max(stall_max_s, stall_s[j]);
  }

  auto end_play = std::chrono::steady_clock::now();

  elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_play - begin_play).count();
  std::cout << "end\n";
  std::cout << "time: planned: " << ((data_time_us_play[data_valid_i_play] - data_time_us_play[0])/1000.0f) << ", elapsed: " << elapsed_ms << "[ms]\n";

  // ramp down to zero speed, then hold position where it stopped; the next
  // run moves on from here after its trigger
  if (!static_playback) {
    auto ramp_begin = std::chrono::steady_clock::now();
    while (true) {
      double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - ramp_begin).count()
                 / (RAMP_DOWN_US / 1.0e6);
      if (s >= 1.0) break;
      for (std::int8_t j = 1; j <= 3; j++) {
        try { comm.speed(j, last_rpm[j-1] * (1.0 - s), 0); } catch (...) {}
      }
    }
    for (std::int8_t j = 1; j <= 3; j++) {
      try { comm.speed(j, 0, 0); } catch (...) {}
    }
    for (uint8_t i = 1; i <= 3; i++) {
      MotorState s;
      if (read_state(comm, i, s)) {
        try { comm.absPosition(i, s.multi_cnt); } catch (...) {}
      }
    }
  }

  // hold still until the app is ready for the next trigger; watch for vibration
  auto hold_check = std::chrono::steady_clock::now() + std::chrono::milliseconds(200);
  auto hold_end = trig + std::chrono::microseconds(CYCLE_US);
  double hold_max_rpm = 0;
  int hold_max_axis = 0;
  while (std::chrono::steady_clock::now() < hold_end) {
    for (uint8_t i = 1; i <= 3; i++) {
      MotorState s;
      if (std::chrono::steady_clock::now() > hold_check && read_state(comm, i, s)
          && std::fabs(s.rpm) > hold_max_rpm) {
        hold_max_rpm = std::fabs(s.rpm);
        hold_max_axis = i;
      }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  if (hold_max_rpm > VIB_WARN_RPM)
    std::cerr << "[VIB] " << device_name << " axis" << hold_max_axis
              << " moved while holding: " << hold_max_rpm << " rpm\n";

  comm.closePort();

  // one line per shot to match phone files (shot ~= trig + 3.16 s)
  double trig_epoch = std::chrono::duration<double>(trig_wall.time_since_epoch()).count();
  char line[512];
  snprintf(line, sizeof(line), "%.3f,%s,%s,%.1f,%.1f,%.2f,%ld,%.2f,%.1f\n",
           trig_epoch, device_name.c_str(), csv_name_playback.c_str(), still_rpm,
           move_rad * RAD2DEG, move_s, static_cast<long>(elapsed_ms), stall_max_s, hold_max_rpm);
  std::cout << "shot log: " << line;
  std::error_code ec;
  std::filesystem::create_directories("log", ec);
  char date[16];
  std::time_t now_t = std::time(nullptr);
  std::strftime(date, sizeof(date), "%Y%m%d", std::localtime(&now_t));
  std::string log_path = std::string("log/trig_") + date + ".csv";
  bool new_file = !std::filesystem::exists(log_path);
  std::ofstream log(log_path, std::ios::app);
  if (new_file) log << "trig_epoch,device,playback,still_rpm,move_deg,move_s,play_ms,stall_s,hold_max_rpm\n";
  log << line;
}
