/*
  SFA_Test — ESP32-C5 통합 하드웨어 점검 스케치 (단일 파일, 수동 컴파일/플래시용)

  점검 항목:
    - RGB LED (GPIO27, 2초 blink)  → 보드/플래시 정상 확인
    - A7670E 모뎀: AT / SIM / 신호 / CFUN / 망등록(CEREG) / SMS 발송
    - A7670E 내장 GNSS: CGNSSPWR + CGNSSINFO (위경도)
    - WiFi 스캔
    - BLE 광고 (SafeFinder SERVICE_UUID) → SFC가 검색 가능

  배선 (SFA와 동일):
    ESP32-C5 D7(GPIO7) <- A7670E TXD   (우리 RX)
    ESP32-C5 D6(GPIO6) -> A7670E RXD   (우리 TX)
    GND 공통. 모뎀은 별도 안정 전원(2A급) 권장.

  시리얼 명령 (115200):
    h : 도움말        i : 모뎀 상태(SIM/CSQ/CEREG/CPSI)
    s : 테스트 SMS(영문)   k : 테스트 SMS(한글/UCS2)
    g : GNSS 위치 1회   w : WiFi 스캔
    b : BLE 광고 토글

  주의: BLE+WiFi 동시 사용으로 이미지가 큽니다.
        Arduino IDE에서 Tools > Partition Scheme = "Huge APP (3MB No OTA/1MB SPIFFS)" 선택,
        USB CDC On Boot = Enabled 권장.
*/

#include <Arduino.h>
#include <WiFi.h>

#define TEST_ENABLE_BLE 1
#if TEST_ENABLE_BLE
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#endif

// ---------------- 설정 ----------------
static const int      MODEM_RX_PIN = 7;   // D7  <- A7670E TXD
static const int      MODEM_TX_PIN = 6;   // D6  -> A7670E RXD
static const uint32_t MODEM_BAUD   = 115200;
HardwareSerial Modem(1);

static const char* SMS_NUMBER = "01072608813";
static const char* SMS_ASCII  = "SFA test SMS";
static const char* SMS_KOREAN = "[SafeFinder] SFA 테스트 문자입니다.";

#define SERVICE_UUID "7d9f0001-4f5d-4a6e-8d6a-534644544553"

static const uint32_t BLINK_MS = 2000;
static uint32_t lastBlink = 0;
static bool ledOn = false;
static uint32_t blinkCount = 0;

static bool modemOk = false, simReady = false, netReady = false, gnssOn = false;
static bool bleAdvertising = false;

// ---------------- LED ----------------
static void setLed(bool on) {
#ifdef RGB_BUILTIN
  if (on) rgbLedWrite(RGB_BUILTIN, 0, 80, 0); else rgbLedWrite(RGB_BUILTIN, 0, 0, 0);
#endif
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);
}

// ---------------- 모뎀 AT ----------------
static void clearModem() { while (Modem.available()) Modem.read(); }

static String readModem(uint32_t timeoutMs) {
  String r; uint32_t t = millis();
  while (millis() - t < timeoutMs) {
    while (Modem.available()) { char c = (char)Modem.read(); r += c; Serial.write(c); }
    delay(5);
  }
  return r;
}
static String sendAT(const String& cmd, uint32_t timeoutMs) {
  clearModem();
  Serial.print("\n[TX] "); Serial.println(cmd);
  Modem.println(cmd);
  return readModem(timeoutMs);
}
static bool waitForModem(uint32_t timeoutMs) {
  uint32_t t = millis();
  while (millis() - t < timeoutMs) {
    if (sendAT("AT", 1000).indexOf("OK") >= 0) return true;
    delay(800);
  }
  return false;
}
static bool waitForNetwork(uint32_t timeoutMs) {
  uint32_t t = millis();
  while (millis() - t < timeoutMs) {
    String r = sendAT("AT+CEREG?", 2000);
    if (r.indexOf("+CEREG: 0,1") >= 0 || r.indexOf("+CEREG: 0,5") >= 0 ||
        r.indexOf("+CEREG: 1,1") >= 0 || r.indexOf("+CEREG: 1,5") >= 0) return true;
    Serial.println("  ...망 등록 대기 중");
    delay(2000);
  }
  return false;
}

static void modemInit() {
  Serial.println("\n===== [모뎀] A7670E 초기화 =====");
  Modem.begin(MODEM_BAUD, SERIAL_8N1, MODEM_RX_PIN, MODEM_TX_PIN);
  delay(800);
  modemOk = waitForModem(15000);
  if (!modemOk) { Serial.println("[모뎀] 응답 없음 — 전원/배선/속도 확인"); return; }
  sendAT("AT+CMEE=2", 2000);
  sendAT("ATE0", 1000);
  simReady = sendAT("AT+CPIN?", 3000).indexOf("READY") >= 0;
  Serial.printf("[모뎀] SIM: %s\n", simReady ? "READY" : "미인식");
  sendAT("AT+CSQ", 2000);
  sendAT("AT+CFUN=1", 10000);
  netReady = waitForNetwork(60000);
  Serial.printf("[모뎀] 망 등록: %s\n", netReady ? "OK" : "실패");
  sendAT("AT+CMGF=1", 3000);
  sendAT("AT+CGSMS=1", 5000);
  sendAT("AT+CSCA?", 5000);
}

static void modemStatus() {
  if (!modemOk) { Serial.println("[모뎀] 미검출"); return; }
  sendAT("AT+CPIN?", 3000);
  sendAT("AT+CSQ", 3000);
  sendAT("AT+CEREG?", 3000);
  sendAT("AT+COPS?", 5000);
  sendAT("AT+CPSI?", 3000);
}

// UTF-8 -> UCS2 hex (한글)
static String toUcs2Hex(const String& s) {
  String out; const uint8_t* b = (const uint8_t*)s.c_str(); size_t n = s.length(); char h[5];
  for (size_t i = 0; i < n;) {
    uint32_t cp; uint8_t c = b[i];
    if (c < 0x80) { cp = c; i += 1; }
    else if ((c & 0xE0) == 0xC0 && i + 1 < n) { cp = ((c & 0x1F) << 6) | (b[i+1] & 0x3F); i += 2; }
    else if ((c & 0xF0) == 0xE0 && i + 2 < n) { cp = ((c & 0x0F) << 12) | ((b[i+1] & 0x3F) << 6) | (b[i+2] & 0x3F); i += 3; }
    else { cp = 0xFFFD; i += 1; }
    if (cp > 0xFFFF) cp = 0xFFFD;
    snprintf(h, sizeof(h), "%04X", (unsigned)cp); out += h;
  }
  return out;
}
static bool isAscii(const String& s) { for (size_t i = 0; i < s.length(); i++) if ((uint8_t)s[i] > 0x7F) return false; return true; }

static bool sendSMS(const char* number, const String& text) {
  if (!modemOk) { Serial.println("[SMS] 모뎀 미검출"); return false; }
  Serial.printf("\n===== [SMS] -> %s =====\n", number);
  bool ascii = isAscii(text);
  sendAT("AT+CMGF=1", 3000);
  String addr;
  if (ascii) { sendAT("AT+CSCS=\"GSM\"", 3000); addr = number; }
  else { sendAT("AT+CSCS=\"UCS2\"", 3000); sendAT("AT+CSMP=17,167,0,8", 3000); addr = toUcs2Hex(number); }
  clearModem();
  Modem.print("AT+CMGS=\""); Modem.print(addr); Modem.println("\"");
  if (readModem(5000).indexOf('>') < 0) { Serial.println("[SMS] '>' 프롬프트 없음"); return false; }
  if (ascii) Modem.print(text); else Modem.print(toUcs2Hex(text));
  Modem.write(0x1A);
  String r = readModem(60000);
  bool ok = r.indexOf("+CMGS:") >= 0;
  Serial.printf("\n[SMS] 결과: %s\n", ok ? "성공" : "실패");
  return ok;
}

// ---------------- GNSS ----------------
static void gnssPowerOn() {
  if (!modemOk) return;
  Serial.println("\n===== [GNSS] 전원 ON =====");
  sendAT("AT+CGNSSPWR=1", 3000);
  gnssOn = true;
}
static double gnssToDeg(const String& v, const String& hemi) {
  if (v.length() < 3) return NAN;
  int dot = v.indexOf('.'); int dl = (dot >= 4) ? dot - 2 : 2;
  double d = v.substring(0, dl).toDouble() + v.substring(dl).toDouble() / 60.0;
  if (hemi == "S" || hemi == "W") d = -d;
  return d;
}
static String csv(const String& s, int idx) {
  int st = 0, c = 0;
  while (c < idx) { st = s.indexOf(',', st); if (st < 0) return ""; st++; c++; }
  int e = s.indexOf(',', st);
  return (e < 0) ? s.substring(st) : s.substring(st, e);
}
static void gnssInfo() {
  if (!modemOk) { Serial.println("[GNSS] 모뎀 미검출"); return; }
  if (!gnssOn) gnssPowerOn();
  String r = sendAT("AT+CGNSSINFO", 3000);
  int p = r.indexOf("+CGNSSINFO:");
  if (p < 0) { Serial.println("[GNSS] 응답 없음"); return; }
  String line = r.substring(p + 11); int nl = line.indexOf('\r'); if (nl >= 0) line = line.substring(0, nl);
  line.trim();
  String mode = csv(line, 0), lat = csv(line, 4), ns = csv(line, 5), lon = csv(line, 6), ew = csv(line, 7);
  if (mode.length() && lat.length() && lon.length()) {
    double la = gnssToDeg(lat, ns), lo = gnssToDeg(lon, ew);
    Serial.printf("[GNSS] FIX: %.6f, %.6f  (mode=%s)\n", la, lo, mode.c_str());
  } else {
    Serial.println("[GNSS] 아직 fix 없음 (실외/안테나 확인, 수십초~수분 소요)");
  }
}

// ---------------- WiFi ----------------
static void wifiScan() {
  Serial.println("\n===== [WiFi] 스캔 =====");
  WiFi.mode(WIFI_STA);
  int n = WiFi.scanNetworks();
  if (n <= 0) { Serial.println("[WiFi] AP 없음"); return; }
  for (int i = 0; i < n && i < 20; i++)
    Serial.printf("  %2d) %-24s  %d dBm  %s\n", i + 1, WiFi.SSID(i).c_str(), WiFi.RSSI(i), WiFi.BSSIDstr(i).c_str());
  WiFi.scanDelete();
}

// ---------------- BLE ----------------
#if TEST_ENABLE_BLE
static void bleToggle() {
  if (!bleAdvertising) {
    BLEDevice::init("Safe Finder 0.1");
    BLEServer* s = BLEDevice::createServer();
    BLEService* svc = s->createService(SERVICE_UUID);
    svc->start();
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    BLEDevice::startAdvertising();
    bleAdvertising = true;
    Serial.println("[BLE] 광고 시작 (SafeFinder SERVICE_UUID) — SFC에서 검색 가능");
  } else {
    BLEDevice::stopAdvertising();
    bleAdvertising = false;
    Serial.println("[BLE] 광고 중지");
  }
}
#endif

// ---------------- 도움말 ----------------
static void help() {
  Serial.println("\n===== 명령 =====");
  Serial.println("  h:도움말  i:모뎀상태  s:SMS(영문)  k:SMS(한글)");
  Serial.println("  g:GNSS위치  w:WiFi스캔  b:BLE광고토글");
}

// ---------------- Arduino ----------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n#################################################");
  Serial.println("#  SFA_Test — ESP32-C5 통합 하드웨어 점검        #");
  Serial.println("#################################################");
  setLed(true);

  modemInit();
  gnssPowerOn();
  WiFi.mode(WIFI_STA);
#if TEST_ENABLE_BLE
  bleToggle();   // BLE 광고 시작
#endif

  Serial.println("\n----- 부팅 자동 SMS 테스트 -----");
  sendSMS(SMS_NUMBER, SMS_ASCII);

  help();
}

void loop() {
  uint32_t now = millis();
  if (now - lastBlink >= BLINK_MS) {
    lastBlink = now; ledOn = !ledOn; blinkCount++;
    setLed(ledOn);
    Serial.printf("[%lus] blink#%lu  modem=%d sim=%d net=%d gnss=%d ble=%d\n",
                  (unsigned long)(now / 1000), (unsigned long)blinkCount,
                  modemOk, simReady, netReady, gnssOn, bleAdvertising);
  }
  if (Serial.available()) {
    char c = (char)Serial.read();
    switch (c) {
      case 'h': help(); break;
      case 'i': modemStatus(); break;
      case 's': sendSMS(SMS_NUMBER, SMS_ASCII); break;
      case 'k': sendSMS(SMS_NUMBER, SMS_KOREAN); break;
      case 'g': gnssInfo(); break;
      case 'w': wifiScan(); break;
#if TEST_ENABLE_BLE
      case 'b': bleToggle(); break;
#endif
      default: break;
    }
  }
}
