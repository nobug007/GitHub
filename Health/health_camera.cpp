#include "health_camera.h"
#include "health_config.h"

#if CAMERA_MODEL_AI_THINKER
// AI-Thinker ESP32-CAM 핀맵. 이 보드가 가장 흔하다.
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
#else
#error "보드 핀맵이 정의되지 않았습니다. health_config.h 에서 보드를 선택하세요."
#endif

bool healthCameraInit() {
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
  config.fb_location  = CAMERA_FB_IN_PSRAM;

  // PSRAM 이 없으면 프레임버퍼를 두 장 잡을 수 없다. 해상도를 낮춰서라도 뜨게 한다.
  if (psramFound()) {
    config.frame_size   = HEALTH_FRAME_SIZE;
    config.jpeg_quality = HEALTH_JPEG_QUALITY;
    config.fb_count     = HEALTH_FB_COUNT;
  } else {
    Serial.println("[cam] PSRAM 없음 — QVGA/1버퍼로 축소");
    config.frame_size   = FRAMESIZE_QVGA;
    config.jpeg_quality = 14;
    config.fb_count     = 1;
    config.fb_location  = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[cam] init 실패 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    // OV2640 은 기본이 약간 어둡고 대비가 낮다. 관절 검출은 윤곽 대비에 민감하므로 조금 올린다.
    s->set_brightness(s, 1);
    s->set_contrast(s, 1);
    s->set_saturation(s, 0);
    s->set_vflip(s, HEALTH_FLIP_VERTICAL);
    s->set_hmirror(s, HEALTH_FLIP_HORIZONTAL);
  }

  Serial.println("[cam] 초기화 완료");
  return true;
}
