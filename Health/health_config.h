// Health — ESP32-CAM 스트리밍 송신기 설정.
//
// 역할 분담: ESP32-CAM 은 SoftAP 를 띄우고 MJPEG 영상만 내보낸다. 관절 추정과 리깅 오버레이는
// 태블릿 앱(Streaming_ESP)이 YOLOv8n-pose 로 처리한다.
//
// 왜 ESP32 에서 안 돌리는가: 메모리도 문제지만 연산이 더 큰 벽이다. YOLOv8n-pose 는 INT8 로
// 줄여도 3MB 대인데 AI-Thinker 보드의 앱 파티션은 기본 1.5MB 안팎이고, 설령 올린다 해도
// 벡터 명령이 없는 240MHz ESP32 에서는 프레임당 수 초가 걸린다. ESP-DL 로 YOLO 계열을 돌리는
// 사례는 벡터 확장이 있는 ESP32-S3/P4 이야기이고, 그마저도 결과는 "사람 바운딩 박스"지
// 관절 20점이 아니다. 태블릿이 맡으면 같은 모델이 프레임당 60~150ms 에 끝난다.
#pragma once
#include <Arduino.h>

// ============================================================================
// 태블릿 앱과의 프로토콜 — 이 블록이 유일한 기준이다.
// 여기를 바꾸면 Streaming_ESP 의 StreamConfig.kt 도 같이 바꿔야 한다.
//
//   Wi-Fi   SSID "ESP32CAM-RIG" / PSK "rig12345" / 게이트웨이 192.168.4.1
//   GET :80  /         확인용 HTML (앱은 쓰지 않는다)
//   GET :81  /stream   multipart/x-mixed-replace; boundary=123456789000000000000987654321
//                      파트마다  Content-Type: image/jpeg
//                                Content-Length: <바이트수>
//                                <빈 줄>
//                                <JPEG 본문>
//   GET :81  /capture  JPEG 한 장 (디버깅용)
//
// 앱의 MJPEG 파서는 파트마다 붙는 Content-Length 에 의존한다. 이 헤더를 빼면 앱은
// FFD8~FFD9 스캔으로 되돌아가서 동작은 하지만 느려진다.
// ============================================================================

// ===== SoftAP =====
// 인터넷이 없는 AP 라서 안드로이드가 "인터넷 없음"으로 보고 트래픽을 데이터망으로 되돌린다.
// 앱은 MainActivity 에서 이 Wi-Fi 네트워크에 프로세스를 직접 바인딩해 그 문제를 피한다.
#define HEALTH_AP_SSID      "ESP32CAM-RIG"
#define HEALTH_AP_PASSWORD  "rig12345"      // 8자 이상 (WPA2 최소 길이)
#define HEALTH_AP_CHANNEL   6
#define HEALTH_AP_MAX_CONN  2

// ===== 포트 =====
// 확인 페이지와 스트림을 다른 포트에 둔다. MJPEG 핸들러는 연결이 끊길 때까지 워커 스레드를
// 붙잡고 있으므로, 같은 포트에 두면 스트림이 열려 있는 동안 페이지 요청이 응답받지 못한다.
static const uint16_t HEALTH_HTTP_PORT   = 80;
static const uint16_t HEALTH_STREAM_PORT = 81;

// ===== 카메라 =====
// 해상도는 무선 대역폭과 맞바꾼다. 앱의 YOLOv8-pose 는 어차피 640x640 레터박스로 맞춰 넣으므로
// VGA(640x480)면 모델이 쓸 수 있는 정보를 거의 다 준다. SVGA 이상은 프레임레이트만 깎아먹는다.
#define HEALTH_FRAME_SIZE   FRAMESIZE_VGA   // 640x480
#define HEALTH_JPEG_QUALITY 12              // 낮을수록 고화질/큰 용량 (10~14 권장)
#define HEALTH_FB_COUNT     2               // PSRAM 있을 때 2 (없으면 자동으로 1)

// 카메라를 천장/거꾸로 매달았을 때 1 로. 오버레이는 영상과 같은 캔버스에 그려지므로
// 여기서 뒤집으면 스켈레톤도 같이 뒤집혀 정렬이 어긋나지 않는다.
#define HEALTH_FLIP_VERTICAL   0
#define HEALTH_FLIP_HORIZONTAL 0

// ===== 보드 =====
// AI-Thinker ESP32-CAM (OV2640). 다른 보드면 health_camera.cpp 의 핀맵을 교체할 것.
#define CAMERA_MODEL_AI_THINKER 1
