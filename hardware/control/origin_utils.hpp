#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <string>

class DDM4006;

constexpr int32_t kCountsPerRevolution = 16384;
constexpr double DEG2STEP = static_cast<double>(kCountsPerRevolution) / 360.0;

struct AxisSample {
  int32_t mechanical_cnt = 0;
  int32_t multi_turn_cnt = 0;
};

std::string default_origin_config_path();
std::string resolve_origin_config_path(const char* override_path);

std::array<int32_t, 3> load_origin_config(const std::string& path,
                                          const std::string& device,
                                          const std::array<int32_t, 3>& fallback);

std::array<int32_t, 3> clamp_origin_array(const std::array<int32_t, 3>& raw);

double load_device_double(const std::string& path,
                          const std::string& device,
                          const std::string& param,
                          double fallback);

int32_t normalize_count(int32_t cnt);
int32_t wrap_counts(int32_t diff);

AxisSample read_axis_sample(DDM4006& comm, uint8_t axis);

// Returns the multi-turn count it settled on (nearest turn of target_cnt).
int32_t drive_axis_to_origin(DDM4006& comm,
                             uint8_t axis,
                             int32_t target_cnt,
                             int32_t max_step_cnt,
                             std::chrono::milliseconds dwell);
