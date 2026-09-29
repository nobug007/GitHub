// Health — ESP32-CAM 스트리밍 송신기.
//
// 이 펌웨어가 하는 일은 하나뿐이다: SoftAP 를 띄우고 카메라 영상을 MJPEG 로 내보낸다.
// 관절 검출과 리깅 오버레이는 태블릿 앱(C:\GitHub\Streaming_ESP)이 YOLOv8-pose 로 처리한다.
// ESP32 칩으로는 pose 모델을 돌릴 수 없기 때문이다 — 자세한 이유는 health_config.h 상단 참고.
//
//   태블릿 → Wi-Fi "ESP32CAM-RIG" 접속 → 앱 실행 → http://192.168.4.1stream:81/stream 수신
//   브라우저로 확인만 할 때 → http://192.168.4.1/
#include "health_config.h"
#include "health_camera.h"
#include "health_web.h"

#include <WiFi.h>
#include <soc/soc.h>

#include <soc/rtc_cntl_reg.h>

void setup() {
  // ESP32-CAM 은 카메라에 전원이 들어가는 순간 전류가 튀어 브라운아웃 리셋이 걸리는 개체가 많다.
  // 전원이 부실한 보드에서 부팅이 무한 리셋으로 도는 것을 막는다.
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

  Serial.begin(115200);
  Serial.setDebugOutput(false);
  delay(200);

  Serial.println("\n[health] ESP32-CAM 스트리밍 송신기");

  if (!healthCameraInit()) {
    Serial.println("[health] 카메라 초기화 실패 — 재시작");
    delay(3000);
    ESP.restart();
  }

  WiFi.mode(WIFI_AP);
  WiFi.softAP(HEALTH_AP_SSID, HEALTH_AP_PASSWORD, HEALTH_AP_CHANNEL, 0, HEALTH_AP_MAX_CONN);
  Serial.printf("[wifi] SoftAP \"%s\"  →  http://%s/\n",
                HEALTH_AP_SSID, WiFi.softAPIP().toString().c_str());

  healthWebStart();
  Serial.println("[health] 준비 완료");
}

void loop() {
  // 실제 처리는 전부 HTTP 서버 태스크에서 일어난다. 여기서는 접속 단말 수가 바뀔 때만 찍어
  // 태블릿이 붙었는지를 시리얼로 확인할 수 있게 해둔다.
  static uint32_t last = 0;
  static uint8_t  prevClients = 255;
  if (millis() - last > 2000) {
    last = millis();
    uint8_t n = WiFi.softAPgetStationNum();
    if (n != prevClients) {
      prevClients = n;
      Serial.printf("[wifi] 접속 단말 %u대\n", n);
    }
  }
  delay(100);
}
