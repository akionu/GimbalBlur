#include "origin_utils.hpp"

#include <cctype>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "DDM4006.hpp"

namespace {
constexpr int32_t kHalfTurn = kCountsPerRevolution / 2;

std::string trim_copy(const std::string& in) {
  size_t start = 0;
  while (start < in.size() && std::isspace(static_cast<unsigned char>(in[start]))) start++;
  size_t end = in.size();
  while (end > start && std::isspace(static_cast<unsigned char>(in[end - 1]))) end--;
  return in.substr(start, end - start);
}

std::string to_lower(std::string s) {
  for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return s;
}

int axis_index(const std::string& key) {
  if (key == "yaw" || key == "y") return 0;
  if (key == "pitch" || key == "p") return 1;
  if (key == "roll" || key == "r") return 2;
  return -1;
}

int32_t read_le16(const uint8_t* ptr) {
  return static_cast<int16_t>(ptr[0] | (ptr[1] << 8));
}

int32_t read_le32(const uint8_t* ptr) {
  return static_cast<int32_t>(ptr[0] | (ptr[1] << 8) | (ptr[2] << 16) | (ptr[3] << 24));
}

std::vector<int32_t> plan_steps(int32_t current_cnt, int32_t target_cnt, int32_t max_step) {
  std::vector<int32_t> steps;
  if (max_step <= 0) max_step = kCountsPerRevolution / 8;
  int32_t diff = wrap_counts(target_cnt - current_cnt);
  while (diff != 0) {
    int32_t step = diff;
    if (step > max_step) step = max_step;
    else if (step < -max_step) step = -max_step;
    steps.push_back(step);
    diff -= step;
  }
  return steps;
}

}  // namespace

std::string default_origin_config_path() {
  if (const char* env = std::getenv("ORIGIN_CFG")) {
    if (*env) return std::string(env);
  }
  return "origin.cfg";
}

std::string resolve_origin_config_path(const char* override_path) {
  if (override_path && *override_path) return std::string(override_path);
  return default_origin_config_path();
}

int32_t normalize_count(int32_t cnt) {
  int32_t mod = cnt % kCountsPerRevolution;
  if (mod < 0) mod += kCountsPerRevolution;
  return mod;
}

int32_t wrap_counts(int32_t diff) {
  int32_t wrapped = diff % kCountsPerRevolution;
  if (wrapped > kHalfTurn) wrapped -= kCountsPerRevolution;
  if (wrapped < -kHalfTurn) wrapped += kCountsPerRevolution;
  return wrapped;
}

std::array<int32_t, 3> clamp_origin_array(const std::array<int32_t, 3>& raw) {
  std::array<int32_t, 3> out{};
  for (size_t i = 0; i < raw.size(); ++i) out[i] = normalize_count(raw[i]);
  return out;
}

std::array<int32_t, 3> load_origin_config(const std::string& path,
                                          const std::string& device,
                                          const std::array<int32_t, 3>& fallback) {
  auto origin = clamp_origin_array(fallback);
  std::ifstream in(path);
  if (!in.is_open()) return origin;

  std::string line;
  while (std::getline(in, line)) {
    auto comment = line.find('#');
    if (comment != std::string::npos) line.resize(comment);
    auto trimmed = trim_copy(line);
    if (trimmed.empty()) continue;

    std::istringstream iss(trimmed);
    std::vector<std::string> tokens;
    std::string token;
    while (iss >> token) tokens.push_back(token);
    if (tokens.empty()) continue;

    std::string entry_device;
    size_t start_idx = 0;
    if (tokens[0].find('=') == std::string::npos) {
      entry_device = tokens[0];
      start_idx = 1;
    }

    for (const auto& tok : tokens) {
      auto eq = tok.find('=');
      if (eq == std::string::npos) continue;
      auto key = to_lower(tok.substr(0, eq));
      auto value = tok.substr(eq + 1);
      if (key == "device" || key == "dev") entry_device = value;
    }

    if (entry_device.empty() || entry_device != device) continue;

    auto selected = origin;
    for (size_t i = start_idx; i < tokens.size(); ++i) {
      auto eq = tokens[i].find('=');
      if (eq == std::string::npos) continue;
      auto key = to_lower(tokens[i].substr(0, eq));
      auto value = tokens[i].substr(eq + 1);
      bool deg = false;
      if (key.size() > 4 && key.compare(key.size() - 4, 4, "_deg") == 0) {
        deg = true;
        key.resize(key.size() - 4);
      }
      int axis = axis_index(key);
      if (axis < 0) continue;
      try {
        double raw_value = std::stod(value);
        double scaled = deg ? raw_value * DEG2STEP : raw_value;
        selected[axis] = normalize_count(static_cast<int32_t>(std::llround(scaled)));
      } catch (...) {
        continue;
      }
    }

    return selected;
  }

  return origin;
}

double load_device_double(const std::string& path,
                          const std::string& device,
                          const std::string& param,
                          double fallback) {
  std::ifstream in(path);
  if (!in.is_open()) return fallback;

  std::string key_lower = to_lower(param);
  std::string line;
  while (std::getline(in, line)) {
    auto comment = line.find('#');
    if (comment != std::string::npos) line.resize(comment);
    auto trimmed = trim_copy(line);
    if (trimmed.empty()) continue;

    std::istringstream iss(trimmed);
    std::vector<std::string> tokens;
    std::string token;
    while (iss >> token) tokens.push_back(token);
    if (tokens.empty()) continue;

    std::string entry_device;
    if (!tokens.empty() && tokens[0].find('=') == std::string::npos)
      entry_device = tokens[0];

    for (const auto& tok : tokens) {
      auto eq = tok.find('=');
      if (eq == std::string::npos) continue;
      auto k = to_lower(tok.substr(0, eq));
      auto v = tok.substr(eq + 1);
      if (k == "device" || k == "dev") entry_device = v;
    }

    if (entry_device.empty() || entry_device != device) continue;

    for (const auto& tok : tokens) {
      auto eq = tok.find('=');
      if (eq == std::string::npos) continue;
      auto k = to_lower(tok.substr(0, eq));
      auto v = tok.substr(eq + 1);
      if (k == key_lower) {
        try { return std::stod(v); } catch (...) {}
      }
    }
  }
  return fallback;
}

AxisSample read_axis_sample(DDM4006& comm, uint8_t axis) {
  auto frame = comm.readRealtime(axis);
  if (frame.size() < 7) throw std::runtime_error("realtime frame too short");
  const uint8_t len = frame[4];
  if (len < 2) throw std::runtime_error("realtime payload too short");
  if (frame.size() < static_cast<size_t>(len) + 7)
    throw std::runtime_error("realtime payload truncated");
  const uint8_t* payload = frame.data() + 5;

  int32_t mechanical = normalize_count(read_le16(payload));
  int32_t multi_turn = mechanical;
  if (len >= 6) {
    // [0..1] single-turn, [2..5] multi-turn absolute angle
    multi_turn = read_le32(payload + 2);
    int32_t alt = normalize_count(multi_turn);
    if (std::abs(wrap_counts(alt - mechanical)) <= 64) mechanical = alt;
  }

  AxisSample sample;
  sample.mechanical_cnt = mechanical;
  sample.multi_turn_cnt = multi_turn;
  return sample;
}

int32_t drive_axis_to_origin(DDM4006& comm,
                             uint8_t axis,
                             int32_t target_cnt,
                             int32_t max_step_cnt,
                             std::chrono::milliseconds dwell) {
  AxisSample sample = read_axis_sample(comm, axis);
  const int32_t target = normalize_count(target_cnt);
  // absPosition takes a multi-turn count: aim at the nearest turn of the
  // target, or a hand-rotated axis can spin a full turn back
  const int32_t target_multi = sample.multi_turn_cnt
                               + wrap_counts(target - sample.mechanical_cnt);
  auto steps = plan_steps(sample.mechanical_cnt, target, max_step_cnt);
  for (int32_t step : steps) {
    if (step == 0) continue;
    comm.relPosition(axis, step);
    std::this_thread::sleep_for(dwell);
    sample.mechanical_cnt = normalize_count(sample.mechanical_cnt + step);
  }
  comm.absPosition(axis, target_multi);
  std::this_thread::sleep_for(dwell);
  return target_multi;
}


