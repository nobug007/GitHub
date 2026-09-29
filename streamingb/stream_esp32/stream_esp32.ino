// stream_esp32 — ESP32-CAM RTSP 송신기.
//
// 하는 일은 하나뿐이다: SoftAP 를 띄우고 카메라 영상을 RTSP 로 내보낸다.
// 태블릿 앱(stream_android)은 그 주소를 열어 화면만 보여준다.
//
//   태블릿 → Wi-Fi "ESP32CAM-RIG" (rig12345) 접속 → 앱 실행
//   스트림 주소: rtsp://192.168.4.1:554/
//
// RTSP 서버는 ESP32-RTSPServer 1.3.5 를 이 폴더 안으로 들여와서 쓴다 (rtsp_*.cpp/h).
// 라이브러리 매니저 판을 그대로 쓰면 SoftAP 모드에서 동작하지 않는다 — 이유와 수정 내용은
// rtsp_handles.cpp 상단 참고. 별도 설치는 필요 없다.
//
// 보드 설정: AI Thinker ESP32-CAM / Partition Scheme = Huge APP (3MB No OTA)
#include <WiFi.h>
#include <esp_camera.h>
#include "rtsp_server.h"     // ESP32-RTSPServer 1.3.5 의 로컬 사본 (rtsp_handles.cpp 참고)
#include <soc/soc.h>
#include <soc/rtc_cntl_reg.h>

// ===== SoftAP =====
static const char *AP_SSID     = "ESP32CAM-RIG";
static const char *AP_PASSWORD = "rig12345";     // WPA2 최소 8자
static const int   AP_CHANNEL  = 6;
static const int   AP_MAX_CONN = 2;

// ===== RTSP =====
static const uint16_t RTSP_PORT = 554;

// ===== 카메라 (AI-Thinker ESP32-CAM / OV2640) =====
#define PWDN_GPIO_NUM   32
#define RESET_GPIO_NUM  -1
#define XCLK_GPIO_NUM    0
#define SIOD_GPIO_NUM   26
#define SIOC_GPIO_NUM   27
#define Y9_GPIO_NUM     35
#define Y8_GPIO_NUM     34
#define Y7_GPIO_NUM     39
#define Y6_GPIO_NUM     36
#define Y5_GPIO_NUM     21
#define Y4_GPIO_NUM     19
#define Y3_GPIO_NUM     18
#define Y2_GPIO_NUM      5
#define VSYNC_GPIO_NUM  25
#define HREF_GPIO_NUM   23
#define PCLK_GPIO_NUM   22

// 해상도는 무선 대역폭과 맞바꾼다. VGA 면 화면으로 보기에 충분하고, ESP32-CAM 의 SoftAP 가
// 감당할 수 있는 선이다. 더 올리면 프레임레이트만 떨어진다.
static const framesize_t FRAME_SIZE   = FRAMESIZE_VGA;   // 640x480
static const int         JPEG_QUALITY = 12;              // 낮을수록 고화질/큰 용량

RTSPServer rtspServer;
static int      frameQuality  = JPEG_QUALITY;
static TaskHandle_t videoTask = nullptr;

static bool setupCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer   = LEDC_TIMER_0;
  config.pin_d0       = Y2_GPIO_NUM;
  config.pin_d1       = Y3_GPIO_NUM;
  config.pin_d2       = Y4_GPIO_NUM;
  config.pin_d3       = Y5_GPIO_NUM;
  config.pin_d4       = Y6_GPIO_NUM;
  config.pin_d5       = Y7_GPIO_NUM;
  config.pin_d6       = Y8_GPIO_NUM;
  config.pin_d7       = Y9_GPIO_NUM;
  config.pin_xclk     = XCLK_GPIO_NUM;
  config.pin_pclk     = PCLK_GPIO_NUM;
  config.pin_vsync    = VSYNC_GPIO_NUM;
  config.pin_href     = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn     = PWDN_GPIO_NUM;
  config.pin_reset    = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode    = CAMERA_GRAB_LATEST;   // 지연이 쌓이지 않게 항상 최신 프레임
  config.frame_size   = FRAME_SIZE;
  config.jpeg_quality = JPEG_QUALITY;

  // PSRAM 이 없으면 프레임버퍼를 두 장 잡을 수 없다. 해상도를 낮춰서라도 뜨게 한다.
  if (psramFound()) {
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.fb_count    = 2;
  } else {
    Serial.println("[cam] PSRAM 없음 - QVGA/1버퍼로 축소");
    config.fb_location = CAMERA_FB_IN_DRAM;
    config.frame_size  = FRAMESIZE_QVGA;
    config.fb_count    = 1;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[cam] init 실패 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    s->set_framesize(s, config.frame_size);
    // RTSP 로 내보낼 때 실제 적용된 품질값을 그대로 넘겨야 수신측 JPEG 복원이 맞는다.
    frameQuality = s->status.quality;
  }
  Serial.printf("[cam] 초기화 완료 (quality=%d)\n", frameQuality);
  return true;
}

// RTP 로 프레임을 밀어 넣는 전용 태스크. 서버가 받을 준비가 됐을 때만 한 장 보낸다.
static void sendVideo(void *) {
  for (;;) {
    if (rtspServer.readyToSendFrame()) {
      camera_fb_t *fb = esp_camera_fb_get();
      if (fb) {
        rtspServer.sendRTSPFrame(fb->buf, fb->len, frameQuality, fb->width, fb->height);
        esp_camera_fb_return(fb);
      }
    }
    vTaskDelay(pdMS_TO_TICKS(1));
  }
}

void setup() {
  // ESP32-CAM 은 카메라에 전원이 들어가는 순간 전류가 튀어 브라운아웃 리셋이 걸리는 개체가
  // 많다. 전원이 부실한 보드에서 부팅이 무한 리셋으로 도는 것을 막는다.
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[stream] ESP32-CAM RTSP 송신기");

  if (!setupCamera()) {
    Serial.println("[stream] 카메라 실패 - 재시작");
    delay(3000);
    ESP.restart();
  }

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL, 0, AP_MAX_CONN);
  Serial.printf("[wifi] SoftAP \"%s\"  IP %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  if (rtspServer.init(RTSPServer::VIDEO_ONLY, RTSP_PORT)) {
    Serial.printf("[rtsp] rtsp://%s:%u/\n", WiFi.softAPIP().toString().c_str(), RTSP_PORT);
  } else {
    Serial.println("[rtsp] 서버 시작 실패");
  }

  xTaskCreate(sendVideo, "video", 8192, nullptr, 9, &videoTask);
  Serial.println("[stream] 준비 완료");
}

void loop() {
  // 전송은 videoTask 가 전담한다. 여기서는 접속 단말 수만 바뀔 때 찍어 상태를 확인할 수 있게 둔다.
  static uint8_t prev = 255;
  uint8_t n = WiFi.softAPgetStationNum();
  if (n != prev) {
    prev = n;
    Serial.printf("[wifi] 접속 단말 %u대\n", n);
  }
  delay(1000);
}
