// ap_min — 카메라를 완전히 뺀 최소 SoftAP + HTTP 서버.
//
// 목적은 하나: "결합은 되는데 ARP/데이터가 전혀 안 되는" 증상이 카메라/스트리밍 때문인지,
// 아니면 Wi-Fi·lwIP 자체 문제인지 가르는 것.
//
//   여기서도 실패 → 카메라와 무관. Wi-Fi 스택 또는 하드웨어 문제.
//   여기서는 성공  → 카메라 초기화/메모리(PSRAM 프레임버퍼)와의 상호작용 문제.
//
// 확인: 태블릿을 "ESP32CAM-MIN" 에 붙이고 http://192.168.4.1/ 접속.
#include <WiFi.h>

static const char *AP_SSID = "ESP32CAM-MIN";
static const char *AP_PASS = "rig12345";

WiFiServer server(80);

static void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  switch (event) {
    case ARDUINO_EVENT_WIFI_AP_STACONNECTED:
      Serial.println("[ap] 단말 결합됨");
      break;
    case ARDUINO_EVENT_WIFI_AP_STADISCONNECTED:
      Serial.println("[ap] 단말 이탈");
      break;
    case ARDUINO_EVENT_WIFI_AP_STAIPASSIGNED:
      Serial.println("[ap] DHCP 주소 배정됨");
      break;
    default:
      break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== ap_min (카메라 없음) ===");

  WiFi.onEvent(onWifiEvent);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, 6, 0, 4);
  WiFi.setSleep(false);

  Serial.printf("SoftAP \"%s\"  IP %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  server.begin();
  Serial.println("HTTP :80 시작");
  Serial.printf("free heap %u\n", ESP.getFreeHeap());
}

void loop() {
  WiFiClient c = server.available();
  if (c) {
    Serial.println("[http] 요청 수신");
    // 요청 줄만 비우고 바로 응답한다. 진단이 목적이라 내용은 짧을수록 좋다.
    uint32_t t0 = millis();
    while (c.connected() && millis() - t0 < 1000) {
      if (c.available()) {
        String line = c.readStringUntil('\n');
        if (line.length() <= 1) break;
      }
    }
    const char *body = "OK from ESP32\n";
    c.printf("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %u\r\n"
             "Connection: close\r\n\r\n%s", (unsigned)strlen(body), body);
    c.flush();
    c.stop();
    Serial.println("[http] 응답 완료");
  }

  static uint32_t last = 0;
  if (millis() - last > 5000) {
    last = millis();
    Serial.printf("[ap] 결합 단말 %u대, free heap %u\n",
                  WiFi.softAPgetStationNum(), ESP.getFreeHeap());
  }
}
