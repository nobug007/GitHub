#include "health_web.h"
#include "health_config.h"

#include <esp_camera.h>
#include <esp_http_server.h>
#include <WiFi.h>
#include <lwip/sockets.h>
#include <errno.h>

static httpd_handle_t sPageServer   = nullptr;
static httpd_handle_t sStreamServer = nullptr;

// 스트림 세대 번호. httpd 는 태스크가 하나뿐이라 MJPEG 핸들러가 도는 동안 서버 전체가 묶인다.
// 새 클라이언트가 들어오면 이 번호를 올려서, 먼저 잡고 있던 스트림이 스스로 물러나게 한다.
// 이게 없으면 브라우저 탭 하나가 스트림을 붙잡은 채로 앱을 영원히 굶긴다.
static volatile uint32_t sStreamGeneration = 0;

// 카메라 접근 직렬화.
//
// 프레임버퍼는 fb_count 장뿐이고, 드라이버는 다음 프레임을 채울 빈 버퍼가 최소 하나 있어야
// 한다. 그런데 HTTP 핸들러는 프레임버퍼를 쥔 채로 send() 에서 막힐 수 있다 (느리거나 죽은
// 클라이언트면 최대 send_wait_timeout 만큼). 스트림(:81)과 /capture(:80)가 동시에 그렇게
// 되면 두 장이 모두 묶여 드라이버가 채울 곳이 없어지고, 카메라가 멎어 두 서버가 함께 영구
// 대기에 빠진다. 실측된 증상이 정확히 이것이었다 — /capture 요청 하나로 :80 까지 죽었다.
//
// 한 번에 한 소비자만 프레임버퍼를 쥐도록 막으면 빈 버퍼가 항상 최소 한 장 남는다.
static SemaphoreHandle_t sCamMutex = nullptr;

static inline bool camLock(uint32_t ms) {
  return sCamMutex && xSemaphoreTake(sCamMutex, pdMS_TO_TICKS(ms)) == pdTRUE;
}
static inline void camUnlock() {
  if (sCamMutex) xSemaphoreGive(sCamMutex);
}

// ---------------------------------------------------------------------------
// 확인용 페이지 (:80)
//
// 리깅은 태블릿 앱(Streaming_ESP)이 그린다. 이 페이지는 앱 없이도 카메라가 살아 있는지
// 확인하기 위한 것뿐이다.
//
// 일부러 :81/stream 을 쓰지 않는다. httpd 는 태스크가 하나라 MJPEG 핸들러가 서버를 통째로
// 붙잡으므로, 이 페이지가 스트림을 열면 앱이 영상을 못 받는다. 대신 :80/capture 로 정지
// 프레임만 주기적으로 받아온다 — 다른 서버라서 스트림과 경쟁하지 않는다.
// ---------------------------------------------------------------------------
static const char kIndexPage[] PROGMEM =
    "<!doctype html><meta charset=utf-8>"
    "<meta name=viewport content='width=device-width,initial-scale=1'>"
    "<title>ESP32-CAM</title>"
    "<style>html,body{margin:0;height:100%;background:#000;display:flex;"
    "align-items:center;justify-content:center}img{max-width:100%;max-height:100%}</style>"
    "<img id=v alt='capture'>"
    // 스트림이 도는 중이면 /capture 가 503 을 준다. 그때 v.src 를 직접 바꿔놨으면 깨진
    // 이미지 아이콘이 뜨므로, 먼저 몰래 받아보고 성공했을 때만 화면을 교체한다.
    "<script>function r(){var i=new Image();i.onload=function(){v.src=i.src};"
    "i.src='/capture?'+Date.now();}r();setInterval(r,3000);</script>";

static esp_err_t indexHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  return httpd_resp_send(req, kIndexPage, HTTPD_RESP_USE_STRLEN);
}

// ---------------------------------------------------------------------------
// MJPEG 스트림 (:81)
// ---------------------------------------------------------------------------
#define PART_BOUNDARY "123456789000000000000987654321"
static const char *kStreamContentType = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *kStreamBoundary    = "\r\n--" PART_BOUNDARY "\r\n";
static const char *kStreamPart        = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

// 반이닫힘(half-close) 감지 — 이 스트림 서버가 멈추지 않게 하는 핵심.
//
// 클라이언트가 FIN 만 보내고 사라져도 TCP 규칙상 우리 쪽 send() 는 계속 "성공"한다. 그래서
// 스트림 핸들러는 허공에 프레임을 밀어 넣으며 영원히 돌고, httpd 는 태스크가 하나뿐이라
// 그동안 accept() 를 못 한다. 실측된 증상이 정확히 이것이었다 — 태블릿에 :81 로의
// FIN-WAIT-2 와 SYN-SENT 가 동시에 남아, 앱이 다시는 붙지 못했다.
//
// send() 결과만 믿을 수 없으므로 매 프레임 소켓을 훔쳐본다. EOF(0) 면 상대가 이미 떠난 것이다.
static bool peerGone(int fd) {
  if (fd < 0) return false;
  char c;
  int n = recv(fd, &c, 1, MSG_DONTWAIT | MSG_PEEK);
  if (n == 0) return true;                                     // FIN 수신 = 상대 종료
  if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) return true;
  return false;
}

static esp_err_t streamHandler(httpd_req_t *req) {
  const uint32_t myGen  = ++sStreamGeneration;
  const int      sockfd = httpd_req_to_sockfd(req);
  Serial.printf("[stream] 시작 (gen %u, fd %d)\n", myGen, sockfd);

  esp_err_t res = httpd_resp_set_type(req, kStreamContentType);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  // MJPEG 를 캐시하거나 버퍼링하면 화면이 몇 초씩 밀린다.
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");

  uint32_t frames = 0;
  char part[64];
  while (true) {
    // 뒤에 들어온 클라이언트에게 자리를 내준다. 물러나면서 소켓도 닫히므로 서버가 다시
    // 요청을 받을 수 있게 된다.
    if (sStreamGeneration != myGen) {
      Serial.printf("[stream] 양보 (gen %u, %u프레임)\n", myGen, frames);
      break;
    }
    // send() 가 실패해 주기를 기다리면 늦다. 상대가 떠난 것을 직접 확인하고 즉시 빠져나온다.
    if (peerGone(sockfd)) {
      Serial.printf("[stream] 상대 종료 감지 (gen %u, %u프레임)\n", myGen, frames);
      break;
    }
    // 프레임버퍼를 쥐는 구간 전체를 잠근다. 이 잠금 덕분에 어느 순간에도 소비자는 하나뿐이고,
    // 드라이버에는 다음 프레임을 채울 빈 버퍼가 항상 남는다.
    if (!camLock(5000)) {
      Serial.println("[stream] 카메라 잠금 실패");
      res = ESP_FAIL;
      break;
    }

    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
      camUnlock();
      Serial.println("[stream] 프레임 획득 실패");
      res = ESP_FAIL;
      break;
    }

    size_t   len = fb->len;
    uint8_t *buf = fb->buf;
    uint8_t *converted = nullptr;

    // 센서 설정이 JPEG 이 아니게 바뀐 경우를 대비한 방어. 평소에는 타지 않는다.
    if (fb->format != PIXFORMAT_JPEG) {
      if (!frame2jpg(fb, 80, &converted, &len)) {
        esp_camera_fb_return(fb);
        camUnlock();
        res = ESP_FAIL;
        break;
      }
      buf = converted;
    }

    // 앱의 MJPEG 파서는 이 Content-Length 를 보고 파트를 정확히 잘라낸다.
    size_t hlen = snprintf(part, sizeof(part), kStreamPart, (unsigned)len);
    int step = 0;
    res = httpd_resp_send_chunk(req, kStreamBoundary, strlen(kStreamBoundary));
    if (res == ESP_OK) { step = 1; res = httpd_resp_send_chunk(req, part, hlen); }
    if (res == ESP_OK) { step = 2; res = httpd_resp_send_chunk(req, (const char *)buf, len); }

    if (converted) free(converted);
    esp_camera_fb_return(fb);
    camUnlock();

    if (res != ESP_OK) {        // 클라이언트가 끊으면 여기로 빠져나온다
      // 어느 단계에서 왜 실패했는지 남긴다. "그냥 실패"만 알면 원인을 좁힐 수 없다.
      Serial.printf("[stream] 종료 (gen %u, %u프레임) step=%d len=%u res=%s errno=%d\n",
                    myGen, frames, step, (unsigned)len, esp_err_to_name(res), errno);
      break;
    }
    frames++;
  }
  return res;
}

// 단일 정지 프레임. 스트림이 의심스러울 때 한 장만 받아보는 디버깅 경로.
//
// 스트림이 도는 동안에도 응답해야 하므로 카메라 잠금을 짧게만 기다린다. 무한정 기다리면
// 이 태스크가 묶여 :80 전체가 응답을 멈추고, 그러면 마지막 진단 수단까지 잃는다.
static esp_err_t captureHandler(httpd_req_t *req) {
  if (!camLock(2000)) {
    httpd_resp_set_status(req, "503 Service Unavailable");
    httpd_resp_set_type(req, "text/plain; charset=utf-8");
    return httpd_resp_send(req, "camera busy\n", HTTPD_RESP_USE_STRLEN);
  }

  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    camUnlock();
    httpd_resp_send_500(req);
    return ESP_FAIL;
  }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  camUnlock();
  return res;
}

// 스트림이 어떤 이유로든 :81 을 붙잡고 놓지 않을 때의 비상 탈출구.
//
// :80 은 별도 서버라 자기 태스크를 가지며, :81 이 묶여 있어도 응답한다. 여기서 세대 번호만
// 올려두면 갇혀 있던 스트림 핸들러가 다음 루프에서 스스로 물러나 :81 이 되살아난다.
// 이게 없으면 그 상태의 유일한 복구 수단이 보드 재부팅이다.
static esp_err_t releaseHandler(httpd_req_t *req) {
  const uint32_t gen = ++sStreamGeneration;
  Serial.printf("[stream] 외부 요청으로 해제 (gen -> %u)\n", gen);
  httpd_resp_set_type(req, "text/plain; charset=utf-8");
  return httpd_resp_send(req, "stream released\n", HTTPD_RESP_USE_STRLEN);
}

// 스트림 서버의 소켓 수명을 눈으로 보기 위한 계측.
//
// "연결이 안 된다"는 관찰만으로는 accept 가 안 되는 것인지, 됐다가 서버 스스로 닫는 것인지
// (예: LRU 회수가 살아 있는 스트림 소켓을 밀어내는 경우) 구분할 수 없다. 두 지점을 찍는다.
static esp_err_t onStreamOpen(httpd_handle_t hd, int sockfd) {
  Serial.printf("[:81] 소켓 열림 fd=%d\n", sockfd);
  return ESP_OK;
}

static void onStreamClose(httpd_handle_t hd, int sockfd) {
  Serial.printf("[:81] 소켓 닫힘 fd=%d\n", sockfd);
  // close_fn 을 지정하면 소켓을 닫는 책임도 이쪽으로 넘어온다.
  close(sockfd);
}

// ---------------------------------------------------------------------------
// 죽은 소켓을 반드시 회수하게 만드는 설정.
//
// 기본값(max_open_sockets=7, lru_purge_enable=false)으로는 끊긴 연결이 그대로 쌓인다.
// MJPEG 클라이언트는 예고 없이 사라지는 일이 잦고, 그때마다 서버가 소켓을 붙든 채 남는다.
// 7개가 차는 순간부터 서버는 새 SYN 에 아예 응답하지 않아, 재접속조차 불가능해진다
// (실측: 태블릿에서 SYN-SENT 만 반복되고 ESP32 쪽 FIN 은 오지 않음).
//
// lru_purge_enable 로 가장 오래된 소켓을 밀어내고, wait timeout 으로 죽은 연결을 빨리
// 포기하게 한다. 이 둘이 없으면 위 상태에서 재부팅 말고는 복구 방법이 없다.
static void applySocketPolicy(httpd_config_t &cfg) {
  cfg.lru_purge_enable  = true;
  cfg.max_open_sockets  = 4;   // 스트림 1 + 여유. 적을수록 LRU 회수가 빨리 돈다
  cfg.recv_wait_timeout = 10;  // 초
  // 스트림은 종료될 때까지 계속 흘러야 한다. 이 값이 짧으면 태블릿이 잠깐 느려져 TCP 윈도가
  // 비지 않는 것만으로도 멀쩡한 스트림이 끊긴다. 죽은 상대는 peerGone() 이 즉시 잡아내므로
  // 여기서 서둘러 포기할 이유가 없다 — 넉넉히 두고 연속성을 우선한다.
  cfg.send_wait_timeout = 15;
}

void healthWebStart() {
  // 두 서버가 각자의 태스크에서 카메라를 만지므로, 서버를 띄우기 전에 잠금을 준비한다.
  sCamMutex = xSemaphoreCreateMutex();
  if (!sCamMutex) Serial.println("[web] 카메라 뮤텍스 생성 실패 — 동시 접근 보호 없음");

  // 스트림 핸들러는 연결이 끊길 때까지 워커를 붙잡고 있다. 확인 페이지를 같은 포트에 두면
  // 스트림이 열려 있는 동안 페이지 요청이 영영 응답받지 못하므로 서버를 둘로 나눈다.
  httpd_config_t pageCfg = HTTPD_DEFAULT_CONFIG();
  pageCfg.server_port = HEALTH_HTTP_PORT;
  pageCfg.ctrl_port   = 32768;
  applySocketPolicy(pageCfg);

  if (httpd_start(&sPageServer, &pageCfg) == ESP_OK) {
    httpd_uri_t index   = { "/",        HTTP_GET, indexHandler,   nullptr };
    httpd_uri_t capture = { "/capture", HTTP_GET, captureHandler, nullptr };
    httpd_uri_t release = { "/release", HTTP_GET, releaseHandler, nullptr };
    httpd_register_uri_handler(sPageServer, &index);
    httpd_register_uri_handler(sPageServer, &release);
    // 정지 프레임은 스트림 서버가 아니라 여기서 준다. 스트림이 도는 동안에도 응답할 수 있는
    // 유일한 경로다 — :81 은 MJPEG 핸들러가 태스크를 붙잡고 있어 아무것도 받지 못한다.
    httpd_register_uri_handler(sPageServer, &capture);
    Serial.printf("[web] 확인 페이지 :%u  (/, /capture)\n", HEALTH_HTTP_PORT);
  } else {
    Serial.println("[web] 확인 페이지 서버 시작 실패");
  }

  httpd_config_t streamCfg = HTTPD_DEFAULT_CONFIG();
  streamCfg.server_port = HEALTH_STREAM_PORT;
  streamCfg.ctrl_port   = 32769;
  streamCfg.stack_size  = 8192;
  applySocketPolicy(streamCfg);
  streamCfg.open_fn  = onStreamOpen;
  streamCfg.close_fn = onStreamClose;

  if (httpd_start(&sStreamServer, &streamCfg) == ESP_OK) {
    httpd_uri_t stream  = { "/stream",  HTTP_GET, streamHandler,  nullptr };
    httpd_uri_t capture = { "/capture", HTTP_GET, captureHandler, nullptr };
    httpd_register_uri_handler(sStreamServer, &stream);
    httpd_register_uri_handler(sStreamServer, &capture);
    Serial.printf("[web] 스트림 :%u/stream\n", HEALTH_STREAM_PORT);
  } else {
    Serial.println("[web] 스트림 서버 시작 실패");
  }
}
