// wifi_scan — ESP32-CAM 이 주변 Wi-Fi 를 "자기 안테나로" 어떻게 듣는지 재는 진단용 스케치.
//
// 태블릿이나 PC 가 보는 RSSI 는 그 기기의 안테나 기준이라, ESP32 의 RF 상태를 알려주지 않는다.
// ESP32-CAM 은 온보드 PCB 안테나와 u.FL 커넥터 중 하나를 0옴 저항으로 고르게 되어 있어서,
// 그 저항이 u.FL 쪽에 있는데 외장 안테나가 없으면 신호가 크게 죽는다. 그 상태를 여기서 잡는다.
//
// 판독 기준 (같은 방 / 공유기까지 몇 미터):
//   -30 ~ -55 dBm  정상
//   -60 ~ -70 dBm  약함. 접속은 되나 처리량이 떨어짐
//   -75 dBm 이하   비정상. 안테나 경로 의심
#include <WiFi.h>

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println();
  Serial.println("=== ESP32-CAM Wi-Fi 진단 ===");

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  delay(100);
  Serial.printf("STA MAC : %s\n", WiFi.macAddress().c_str());
}

void loop() {
  Serial.println("--- 스캔 시작 ---");
  int n = WiFi.scanNetworks(false, true);   // 동기 스캔, 숨김 SSID 포함
  if (n <= 0) {
    Serial.println("스캔 결과 없음 (RF 경로 이상 가능성)");
  } else {
    Serial.printf("%d개 발견\n", n);
    for (int i = 0; i < n; i++) {
      Serial.printf("  %-28s ch%-3d %4d dBm  %s\n",
                    WiFi.SSID(i).c_str(), WiFi.channel(i), WiFi.RSSI(i),
                    WiFi.BSSIDstr(i).c_str());
    }
  }
  WiFi.scanDelete();
  Serial.println("--- 스캔 끝 ---");
  delay(5000);
}
