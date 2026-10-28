#include <array>
#include <chrono>
#include <iostream>
#include <string>
#include <thread>

#include "DDM4006.hpp"
#include "origin_utils.hpp"

// 0: yaw, 1: pitch, 2: roll
const std::array<int32_t, 3> kDefaultOrigin = {8192, 4096, 0};

int main(int argc, char* argv[]) {
  if (argc < 2) {
    std::cerr << "usage: " << argv[0] << " [device] [origin.cfg]\n";
    return 1;
  }

  std::string device_name = argv[1];
  const char* cfg_override = (argc >= 3) ? argv[2] : nullptr;
  std::string config_path = resolve_origin_config_path(cfg_override);
  auto origin = load_origin_config(config_path, device_name, kDefaultOrigin);

  DDM4006 comm(device_name, 921600);
  if (!comm.openPort()) {
    std::cerr << "open failed\n";
    return 1;
  }
  for (uint8_t axis = 1; axis <= 3; ++axis) {
    try {
      comm.clearFaults(axis);
    } catch (const std::exception& e) {
      std::cerr << "clearFaults failed axis " << static_cast<int>(axis)
                << ": " << e.what() << '\n';
    }
  }

  auto target = origin;
  std::array<bool, 3> reached = {false, false, false};
  for (uint8_t axis = 1; axis <= origin.size(); ++axis) {
    try {
      target[axis-1] = drive_axis_to_origin(comm, axis, origin[axis-1], 512, std::chrono::milliseconds(300));
      reached[axis-1] = true;
    } catch (const std::exception& e) {
      std::cerr << "origin return failed axis " << static_cast<int>(axis)
                << ": " << e.what() << '\n';
    }
  }

  for (uint8_t axis = 1; axis <= origin.size(); ++axis) {
    // a single-turn origin here could spin a full turn; skip failed axes
    if (!reached[axis-1]) continue;
    try {
      comm.absPosition(axis, target[axis-1]);
    } catch (const std::exception& e) {
      std::cerr << "origin return failed axis " << static_cast<int>(axis)
                << ": " << e.what() << '\n';
    }
  }

  std::this_thread::sleep_for(std::chrono::milliseconds(1000));
  comm.closePort();
}
