// ap_cam — ap_min(카메라 없이 정상 동작 확인됨)에 카메라만 다시 붙인 이분 탐색용 스케치.
//
// ap_min 은 SoftAP + HTTP 가 완벽히 동작했다(ARP REACHABLE, 200 OK 본문까지 수신).
// 즉 Wi-Fi/lwIP/TCP 는 멀쩡하고, 깨지는 건 카메라를 함께 켤 때다. 여기서 그 경계를 찾는다.
//
// 바꿔가며 볼 것: FRAME_SIZE, FB_COUNT, FB_IN_PSRAM.
// 메모리 압박이 원인이라면 카메라 초기화 전후의 heap/PSRAM 차이에서 바로 드러난다.
//
//   http://192.168.4.1/        상태 텍스트 (메모리 수치)
//   http://192.168.4.1/jpg     정지 프레임 한 장
#include <WiFi.h>
#include <esp_camera.h>

static const char *AP_SSID = "ESP32CAM-MIN";
static const char *AP_PASS = "rig12345";

// ---- 이분 탐색 손잡이 ----
static const framesize_t FRAME_SIZE = FRAMESIZE_QVGA;   // 320x240 부터 시작
static const int         JPEG_Q     = 12;
static const int         FB_COUNT   = 1;
static const bool        FB_PSRAM   = true;
// 카메라 픽셀 클럭. 기본 20MHz 의 고조파(20MHz x 120 = 2.4GHz)가 Wi-Fi 수신 대역에 그대로
// 떨어져 수신 감도를 깎는다. ESP32-CAM 에서 Wi-Fi 가 불안정할 때 가장 먼저 낮춰보는 값이다.
static const int         XCLK_HZ    = 10000000;

// AI-Thinker ESP32-CAM
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22

WiFiServer server(80);
static bool camOk = false;

static void reportMem(const char *tag) {
  Serial.printf("[mem] %-14s heap=%u  minheap=%u  psram=%u/%u\n",
                tag, ESP.getFreeHeap(), ESP.getMinFreeHeap(),
                ESP.getFreePsram(), ESP.getPsramSize());
}

static bool setupCamera() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer   = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM;  c.pin_d1 = Y3_GPIO_NUM;
  c.pin_d2 = Y4_GPIO_NUM;  c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM;  c.pin_d5 = Y7_GPIO_NUM;
  c.pin_d6 = Y8_GPIO_NUM;  c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM;   c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM;   c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = XCLK_HZ;
  c.pixel_format = PIXFORMAT_JPEG;
  c.grab_mode    = CAMERA_GRAB_LATEST;
  c.frame_size   = FRAME_SIZE;
  c.jpeg_quality = JPEG_Q;
  c.fb_count     = FB_COUNT;
  c.fb_location  = FB_PSRAM ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;

  esp_err_t err = esp_camera_init(&c);
  if (err != ESP_OK) {
    Serial.printf("[cam] init 실패 0x%x\n", err);
    return false;
  }
  Serial.println("[cam] init OK");
  return true;
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== ap_cam (SoftAP + 카메라) ===");
  reportMem("부팅직후");

  // 카메라를 Wi-Fi 보다 먼저 올린다. 순정 예제와 같은 순서라 비교가 된다.
  camOk = setupCamera();
  reportMem("카메라 후");

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS, 6, 0, 4);
  WiFi.setSleep(false);
  reportMem("SoftAP 후");

  Serial.printf("SoftAP \"%s\"  IP %s  cam=%s\n",
                AP_SSID, WiFi.softAPIP().toString().c_str(), camOk ? "OK" : "FAIL");
  server.begin();
  Serial.println("HTTP :80 시작");
}

static void drainRequest(WiFiClient &c, String &path) {
  uint32_t t0 = millis();
  bool first = true;
  while (c.connected() && millis() - t0 < 1500) {
    if (!c.available()) continue;
    String line = c.readStringUntil('\n');
    if (first) {
      first = false;
      int s = line.indexOf(' ');
      int e = line.indexOf(' ', s + 1);
      if (s >= 0 && e > s) path = line.substring(s + 1, e);
    }
    if (line.length() <= 1) break;
  }
}

void loop() {
  WiFiClient c = server.available();
  if (c) {
    String path = "/";
    drainRequest(c, path);
    Serial.printf("[http] 요청 %s\n", path.c_str());

    if (path.startsWith("/jpg")) {
      camera_fb_t *fb = camOk ? esp_camera_fb_get() : nullptr;
      if (!fb) {
        const char *e = "no frame\n";
        c.printf("HTTP/1.1 500 Internal Server Error\r\nContent-Length: %u\r\n"
                 "Connection: close\r\n\r\n%s", (unsigned)strlen(e), e);
        Serial.println("[http] 프레임 획득 실패");
      } else {
        Serial.printf("[http] jpg %u바이트 전송 시작\n", (unsigned)fb->len);
        c.printf("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n"
                 "Connection: close\r\n\r\n", (unsigned)fb->len);
        size_t sent = c.write(fb->buf, fb->len);
        Serial.printf("[http] jpg %u/%u 전송됨\n", (unsigned)sent, (unsigned)fb->len);
        esp_camera_fb_return(fb);
      }
    } else {
      char body[160];
      int n = snprintf(body, sizeof(body),
                       "cam=%s heap=%u psram=%u/%u\n",
                       camOk ? "OK" : "FAIL", ESP.getFreeHeap(),
                       ESP.getFreePsram(), ESP.getPsramSize());
      c.printf("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n"
               "Connection: close\r\n\r\n%s", n, body);
    }
    c.flush();
    c.stop();
    Serial.println("[http] 응답 완료");
  }

  static uint32_t last = 0;
  if (millis() - last > 5000) {
    last = millis();
    Serial.printf("[ap] 단말 %u대, heap=%u\n", WiFi.softAPgetStationNum(), ESP.getFreeHeap());
  }
}
