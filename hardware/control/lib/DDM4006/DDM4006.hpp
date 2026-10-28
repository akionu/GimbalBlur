#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <array>
#include <termios.h>

class DDM4006 {
public:
  explicit DDM4006(const std::string& port = "/dev/ttyUSB0",
                   uint32_t baud = 115200);
  ~DDM4006();

  bool openPort();
  void closePort();

  /* ---------- System ---------- */
  void                  restart(uint8_t addr);          // 0x00
  std::vector<uint8_t>  readVersions(uint8_t addr);     // 0x0A
  std::vector<uint8_t>  readRealtime(uint8_t addr);     // 0x0B
  std::vector<uint8_t>  clearFaults(uint8_t addr);      // 0x0F

  /* ---------- Parameter ---------- */
  std::vector<uint8_t>  readUserParams(uint8_t addr);                         // 0x10
  void                  writeUserParams(uint8_t addr,
                                        const std::array<uint8_t,22>& raw);   // 0x11
  std::vector<uint8_t>  readMotorHW(uint8_t addr);                            // 0x12
  void                  writeMotorHW(uint8_t addr,
                                     const std::vector<uint8_t>& raw);        // 0x13
  std::vector<uint8_t>  readMotionCtrl(uint8_t addr);                         // 0x14
  void                  writeMotionCtrl(uint8_t addr,
                                        const std::vector<uint8_t>& raw,
                                        bool save);                           // 0x15/0x16

  std::vector<uint8_t>  setOrigin(uint8_t addr);          // 0x1D
  std::vector<uint8_t>  encoderCalib(uint8_t addr);       // 0x1E
  std::vector<uint8_t>  restoreDefaults(uint8_t addr);    // 0x1F

  /* ---------- Control ---------- */
  void                  qAxisCurrent(uint8_t addr, float A,
                                     float rampA_s = 0);   // 0x20
  std::vector<uint8_t>  speed(uint8_t addr, float rpm,
                              float accel_rpm_s = 0);      // 0x21 (0x0B-like resp)
  void                  flushRx();                          // discard rx buffer
  std::vector<uint8_t>  absPosition(uint8_t addr, int32_t cnt); // 0x22 (0x0B-like resp)
  void                  relPosition(uint8_t addr, int32_t cnt); // 0x23
  std::vector<uint8_t>  gotoHome(uint8_t addr);                 // 0x24
  void                  disableMotor(uint8_t addr);             // 0x2F

  /* -- utils -- */
  static int16_t        deg2cnt(float deg);

private:
  /* ----- serial state ----- */
  int         _fd   { -1 };      // file descriptor from open()
  std::string _port;             // e.g. "/dev/ttyUSB0"
  uint32_t    _baud { 115200 };  // baud rate
  uint8_t     _seq  { 0 };       // tx sequence number

  /* ----- low-level utils ----- */
  std::vector<uint8_t> buildFrame(uint8_t addr, uint8_t cmd,
                                  const std::vector<uint8_t>& payload = {});
  bool                 tx(const std::vector<uint8_t>& frm);
  std::vector<uint8_t> rx();
  std::vector<uint8_t> rx_speed();
  uint16_t             crc16(const uint8_t* p, size_t n) const;
  void                 setupTermios();
  speed_t              baud2speed(uint32_t b) const;
};
