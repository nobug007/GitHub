#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>

// ===========================
// Select camera model in board_config.h
// ===========================
#include "board_config.h"

// ===========================
// Enter your WiFi credentials
// ===========================
// 1 = SoftAP (보드가 AP, 주소 192.168.4.1) / 0 = 공유기 접속(STA)
#define USE_SOFTAP 0

// SoftAP 로 띄울 때 쓰는 SSID / 비밀번호 (WPA2 최소 8자).
const char *AP_SSID = "ESP32CAM-RIG";
const char *AP_PASS = "rig12345";

// STA 로 쓸 때의 집 공유기 2.4GHz SSID. ESP32 는 5GHz 를 잡지 못하므로 _5G 가 아닌 쪽이어야 한다.
const char *ssid = "nobug_home";
const char *password = "bang8813";

void startCameraServer();
void setupLedFlash();

void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(true);
  Serial.println();

  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  // [수정] 기본 20MHz -> 10MHz.
  //
  // 이게 이 보드에서 Wi-Fi 가 죽던 진짜 원인이었다. 20MHz 픽셀 클럭의 고조파(20MHz x 120)가
  // 정확히 2.4GHz 대역에 떨어져 수신 감도를 깎는다. 그 상태에서는 결합(association)과 ARP 는
  // 되는데 TCP SYN 이 보드에 도달하지 못해, 겉보기에는 "서버가 응답을 안 하는" 것처럼 보였다.
  // 카메라를 빼면 같은 코드가 멀쩡히 동작했고, 10MHz 로 낮추자 카메라를 켠 채로도 정상이 됐다.
  config.xclk_freq_hz = 10000000;
  config.frame_size = FRAMESIZE_UXGA;
  config.pixel_format = PIXFORMAT_JPEG;  // for streaming
  //config.pixel_format = PIXFORMAT_RGB565; // for face detection/recognition
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.jpeg_quality = 12;
  config.fb_count = 1;

  // if PSRAM IC present, init with UXGA resolution and higher JPEG quality
  //                      for larger pre-allocated frame buffer.
  if (config.pixel_format == PIXFORMAT_JPEG) {
    if (psramFound()) {
      config.jpeg_quality = 10;
      config.fb_count = 2;
      config.grab_mode = CAMERA_GRAB_LATEST;
    } else {
      // Limit the frame size when PSRAM is not available
      config.frame_size = FRAMESIZE_SVGA;
      config.fb_location = CAMERA_FB_IN_DRAM;
    }
  } else {
    // Best option for face detection/recognition
    config.frame_size = FRAMESIZE_240X240;
#if CONFIG_IDF_TARGET_ESP32S3
    config.fb_count = 2;
#endif
  }

#if defined(CAMERA_MODEL_ESP_EYE)
  pinMode(13, INPUT_PULLUP);
  pinMode(14, INPUT_PULLUP);
#endif

  // camera init
  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed with error 0x%x", err);
    return;
  }

  sensor_t *s = esp_camera_sensor_get();
  // initial sensors are flipped vertically and colors are a bit saturated
  if (s->id.PID == OV3660_PID) {
    s->set_vflip(s, 1);        // flip it back
    s->set_brightness(s, 1);   // up the brightness just a bit
    s->set_saturation(s, -2);  // lower the saturation
  }
  // drop down frame size for higher initial frame rate
  if (config.pixel_format == PIXFORMAT_JPEG) {
    // 원본은 프레임레이트를 위해 QVGA(320x240)로 시작한다. 화면으로 보기엔 너무 작아서
    // VGA 로 올렸다. 웹 UI 에서 언제든 바꿀 수 있다.
    s->set_framesize(s, FRAMESIZE_VGA);
  }

#if defined(CAMERA_MODEL_M5STACK_WIDE) || defined(CAMERA_MODEL_M5STACK_ESP32CAM)
  s->set_vflip(s, 1);
  s->set_hmirror(s, 1);
#endif

#if defined(CAMERA_MODEL_ESP32S3_EYE)
  s->set_vflip(s, 1);
#endif

// Setup LED FLash if LED pin is defined in camera_pins.h
#if defined(LED_GPIO_NUM)
  setupLedFlash();
#endif

#if USE_SOFTAP
  // SoftAP: 보드가 직접 AP 가 되고 태블릿이 여기에 붙는다. 주소는 192.168.4.1 로 고정.
  //
  // 이쪽을 기본으로 둔 이유는 링크 품질이다. 이 보드는 공유기를 -80dBm 으로밖에 듣지 못해
  // (같은 자리 태블릿은 -67dBm) STA 로는 스트림이 40초를 못 버티고 무너졌다. 태블릿을 카메라
  // 바로 옆에 두는 SoftAP 에서는 -26dBm 이라 여유가 크다.
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, 6, 0, 4);
  WiFi.setSleep(false);

  startCameraServer();

  Serial.print("Camera Ready! Use 'http://");
  Serial.print(WiFi.softAPIP());
  Serial.println("' to connect");
#else
  // STA: 집 공유기에 접속한다. PC 등 같은 망의 다른 기기에서도 볼 수 있지만, 이 보드의
  // 수신 감도가 약해 거리가 있으면 스트림이 오래 유지되지 않는다.
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  WiFi.setSleep(false);

  Serial.print("WiFi connecting");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("");
  Serial.println("WiFi connected");

  startCameraServer();

  Serial.print("Camera Ready! Use 'http://");
  Serial.print(WiFi.localIP());
  Serial.println("' to connect");
#endif
}

void loop() {
  // Do nothing. Everything is done in another task by the web server
  delay(10000);
}
