#include "DDM4006.hpp"
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/serial.h>
#include <errno.h>
#include <cstring>
#include <stdexcept>
#include <chrono>
#include <thread>

using namespace std::chrono_literals;

/* ---------- ctor / dtor ---------- */
DDM4006::DDM4006(const std::string& port, uint32_t baud)
: _port(port), _baud(baud) {}

DDM4006::~DDM4006() { closePort(); }

// Public:
/* ---------- open / close ---------- */
bool DDM4006::openPort() {
  _fd = ::open(_port.c_str(), O_RDWR | O_NOCTTY | O_NONBLOCK);
  if (_fd < 0) return false;
  setupTermios();

  int rx_buf_size = 4096;
  ioctl(_fd, TIOCSSERIAL, &rx_buf_size);

  struct serial_struct ss{};
  if (ioctl(_fd, TIOCGSERIAL, &ss) == 0) {
    ss.flags |= ASYNC_LOW_LATENCY;
    ioctl(_fd, TIOCSSERIAL, &ss);
  }
  return true;
}

void DDM4006::closePort() {
  if (_fd >= 0) { ::close(_fd); _fd = -1; }
}

/* ---------- public commands ---------- */
void DDM4006::restart(uint8_t addr) {
  auto frm = buildFrame(addr, 0x00);
  tx(frm);
  // no response by spec
}

std::vector<uint8_t> DDM4006::readVersions(uint8_t addr) {
  tx(buildFrame(addr, 0x0A));
  return rx();
}

std::vector<uint8_t> DDM4006::readRealtime(uint8_t addr) {
  tx(buildFrame(addr, 0x0B));
  return rx();
}

std::vector<uint8_t> DDM4006::clearFaults(uint8_t addr) {
  tx(buildFrame(addr, 0x0F));
  return rx();
}

/* ---- control ---- */
void DDM4006::disableMotor(uint8_t addr) {
  tx(buildFrame(addr, 0x2F));
  rx();           // response cmd is 0x2F, payload format same as 0x0B
}

void DDM4006::qAxisCurrent(uint8_t addr, float A, float rampA_s) {
  int32_t target = static_cast<int32_t>(A * 1000.0f);        // unit: 0.001 A
  uint32_t ramp  = (rampA_s<=0) ? 0 : static_cast<uint32_t>(rampA_s * 1000.0f);
  std::vector<uint8_t> pay(8);
  std::memcpy(&pay[0], &target, 4);
  std::memcpy(&pay[4], &ramp, 4);
  tx(buildFrame(addr, 0x20, pay));
  rx();
}

std::vector<uint8_t> DDM4006::speed(uint8_t addr, float rpm, float accel_rpm_s) {
  int32_t spd  = static_cast<int32_t>(rpm * 100.0f);         // 0.01rpm
  uint32_t acc = (accel_rpm_s<=0) ? 0 : static_cast<uint32_t>(accel_rpm_s*100.0f);

  std::vector<uint8_t> pay(8);
  std::memcpy(&pay[0], &spd, 4);
  std::memcpy(&pay[4], &acc, 4);

  tx(buildFrame(addr, 0x21, pay));
  auto resp = rx_speed();
  return resp;
}

void DDM4006::flushRx() {
  if (_fd >= 0) tcflush(_fd, TCIFLUSH);
}

std::vector<uint8_t> DDM4006::absPosition(uint8_t addr, int32_t cnt) {
  std::vector<uint8_t> pay(4);
  std::memcpy(pay.data(), &cnt, 4);
  tx(buildFrame(addr, 0x22, pay));
  return rx();
}

void DDM4006::relPosition(uint8_t addr, int32_t cnt) {
  std::vector<uint8_t> pay(4);
  std::memcpy(pay.data(), &cnt, 4);
  tx(buildFrame(addr, 0x23, pay));
  rx();
}

/* ---------- Parameter commands ---------- */
std::vector<uint8_t> DDM4006::readUserParams(uint8_t addr){
  tx(buildFrame(addr,0x10));
  return rx();
}
void DDM4006::writeUserParams(uint8_t addr,const std::array<uint8_t,22>& raw){
  tx(buildFrame(addr,0x11,{raw.begin(),raw.end()}));
  rx();
}

std::vector<uint8_t> DDM4006::readMotorHW(uint8_t addr){
  tx(buildFrame(addr,0x12));
  return rx();
}
void DDM4006::writeMotorHW(uint8_t addr,const std::vector<uint8_t>& raw){ // 30+ bytes
  tx(buildFrame(addr,0x13,raw));
  rx();
}

std::vector<uint8_t> DDM4006::readMotionCtrl(uint8_t addr){
  tx(buildFrame(addr,0x14));
  return rx();
}
void DDM4006::writeMotionCtrl(uint8_t addr,const std::vector<uint8_t>& raw,bool save){
  tx(buildFrame(addr,save?0x16:0x15,raw));
  rx();
}

/* zero-set, calibration, restore */
std::vector<uint8_t> DDM4006::setOrigin(uint8_t addr){
  tx(buildFrame(addr,0x1D));
  return rx();          // returns 2-byte mech angle offset
}
std::vector<uint8_t> DDM4006::encoderCalib(uint8_t addr){
  tx(buildFrame(addr,0x1E));
  return rx();          // 0x0B-like payload
}
std::vector<uint8_t> DDM4006::restoreDefaults(uint8_t addr){
  tx(buildFrame(addr,0x1F));
  return rx();          // empty payload, header only
}

/* shortest-path home (<=180 deg) */
std::vector<uint8_t> DDM4006::gotoHome(uint8_t addr){
  tx(buildFrame(addr,0x24));
  return rx();          // 0x0B-like payload
}

int16_t DDM4006::deg2cnt(float deg) {
  const float cntperdeg = (16384.0 / 360.0);
  return (int16_t)(cntperdeg*deg);
}


// Private:
/* ---------- frame helpers ---------- */
std::vector<uint8_t>
DDM4006::buildFrame(uint8_t addr, uint8_t cmd,
                    const std::vector<uint8_t>& payload) {
  std::vector<uint8_t> f;
  f.reserve(6 + payload.size());
  f.push_back(0xAE);               // Header
  f.push_back(_seq++);             // Seq
  f.push_back(addr);
  f.push_back(cmd);
  f.push_back(static_cast<uint8_t>(payload.size()));
  f.insert(f.end(), payload.begin(), payload.end());

  uint16_t c = crc16(f.data(), f.size());
  f.push_back(c & 0xFF);
  f.push_back((c>>8)&0xFF);
  return f;
}

bool DDM4006::tx(const std::vector<uint8_t>& frm) {
  if (_fd<0) throw std::runtime_error("serial not open");
  ssize_t n = ::write(_fd, frm.data(), frm.size());
  std::this_thread::sleep_for(std::chrono::microseconds(50));
  return n == static_cast<ssize_t>(frm.size());
}

std::vector<uint8_t> DDM4006::rx() {
    if (_fd<0) throw std::runtime_error("serial not open");

    uint8_t hdr[5];
    size_t got = 0;
    auto deadline = std::chrono::steady_clock::now() + 50ms;

    // --- 1st stage: header ---
    while (got < 5) {
        if (std::chrono::steady_clock::now() > deadline)
            throw std::runtime_error("timeout");
        ssize_t n = ::read(_fd, hdr+got, 5-got);
        if (n > 0) got += n;
        else if (n < 0 && errno!=EAGAIN) throw std::runtime_error("read error");
    }
    if (hdr[0] != 0xAC) throw std::runtime_error("bad header");

    uint8_t len = hdr[4];
    std::vector<uint8_t> rest(len + 2);     // data + CRC
    got = 0;
    while (got < rest.size()) {
        if (std::chrono::steady_clock::now() > deadline)
            throw std::runtime_error("timeout");
        ssize_t n = ::read(_fd, rest.data()+got, rest.size()-got);
        if (n > 0) got += n;
        else if (n < 0 && errno!=EAGAIN) throw std::runtime_error("read error");
    }

    std::vector<uint8_t> full;
    full.reserve(5+rest.size());
    full.insert(full.end(), hdr, hdr+5);
    full.insert(full.end(), rest.begin(), rest.end());

    uint16_t crc_calc = crc16(full.data(), full.size()-2);
    uint16_t crc_recv = full[full.size()-2] | (full.back()<<8);
    if (crc_calc != crc_recv)
        throw std::runtime_error("CRC mismatch");

    return full;
}

std::vector<uint8_t> DDM4006::rx_speed() {
    if (_fd<0) throw std::runtime_error("serial not open");
    
    constexpr size_t EXPECTED_SIZE = 29;
    std::vector<uint8_t> buf(EXPECTED_SIZE);
    size_t got = 0;
    auto deadline = std::chrono::steady_clock::now() + 50ms;

    while (got < EXPECTED_SIZE) {
        if (std::chrono::steady_clock::now() > deadline)
            throw std::runtime_error("timeout");

        ssize_t n = ::read(_fd, buf.data()+got, EXPECTED_SIZE-got);
        if (n > 0) got += n;
        else if (n < 0 && errno!=EAGAIN) throw std::runtime_error("read error");
    }

    if (buf[0] != 0xAC) throw std::runtime_error("bad header");
    
    uint16_t crc_calc = crc16(buf.data(), EXPECTED_SIZE-2);
    uint16_t crc_recv = buf[EXPECTED_SIZE-2] | (buf[EXPECTED_SIZE-1]<<8);
    if (crc_calc != crc_recv)
        throw std::runtime_error("CRC mismatch");
    
    return buf;
}

/* ---------- CRC16-MODBUS ---------- */
uint16_t DDM4006::crc16(const uint8_t* p, size_t n) const {
  uint16_t crc = 0xFFFF;
  while (n--) {
    crc ^= *p++;
    for (int i=0;i<8;i++)
      crc = (crc & 1) ? (crc>>1) ^ 0xA001 : (crc>>1);
  }
  return crc;
}

/* ---------- termios ---------- */
void DDM4006::setupTermios() {
    struct termios tty;
    tcgetattr(_fd, &tty);

    speed_t spd = baud2speed(_baud);
    cfsetispeed(&tty, spd);
    cfsetospeed(&tty, spd);

    // Raw mode
    tty.c_cflag = (tty.c_cflag & ~CSIZE) | CS8;
    tty.c_cflag |= (CLOCAL | CREAD);
    tty.c_cflag &= ~(PARENB | PARODD);
    tty.c_cflag &= ~CSTOPB;
    tty.c_cflag &= ~CRTSCTS;
    
    tty.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON);
    tty.c_oflag = 0;
    tty.c_lflag = 0;
    
    tty.c_cc[VMIN]  = 0;  // non-blocking
    tty.c_cc[VTIME] = 0;
    
    tcsetattr(_fd, TCSANOW, &tty);
}

speed_t DDM4006::baud2speed(uint32_t b) const {
  switch (b) {
    case 921600: return B921600;
    case 460800: return B460800;
    case 115200: return B115200;
    case 57600:  return B57600;
    case 38400:  return B38400;
    case 19200:  return B19200;
    case 9600:   return B9600;
    default: throw std::invalid_argument("unsupported baud");
  }
}
