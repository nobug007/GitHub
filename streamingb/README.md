# streamingb — ESP32-CAM 영상 스트리밍

동작하는 것은 **`cam_web/`** 하나다. 나머지는 원인을 찾기 위한 진단용 스케치이고, 기록으로 남겨둔다.

## 결론부터

ESP32-CAM 에서 영상이 안 나오던 원인은 코드가 아니라 **RF 간섭**이었다. 두 클럭의 고조파가
2.4GHz Wi-Fi 대역에 그대로 떨어져 수신 감도를 깎고 있었다.

| 항목 | 기본값 | 바꾼 값 | 왜 |
|---|---|---|---|
| 카메라 픽셀 클럭 `xclk_freq_hz` | 20 MHz | **10 MHz** | 20 × 120 = 2400 MHz |
| 플래시/PSRAM SPI 클럭 `FlashFreq` | 80 MHz | **40 MHz** | 80 × 30 = 2400 MHz |

ESP32-CAM 은 플래시와 PSRAM 이 같은 SPI 버스를 쓰므로 `FlashFreq` 가 곧 PSRAM 클럭이다.
**둘 다 바꿔야 한다.** 하나만 바꾸면 증상이 줄어들 뿐 없어지지 않는다.

## 검증 결과 (2026-08-24)

STA 모드, 공유기 경유, PC 에서 측정.

```
VGA 640x480 · 120초 연속
  30초: 119 KB/s   9.0 fps
  60초: 166 KB/s  12.5 fps
  90초: 157 KB/s  11.9 fps
 120초: 144 KB/s  11.1 fps
합계 17,573 KB / 1,336프레임 / 평균 11.1 fps — 중단 0회
```

정지 프레임(`/capture`)도 완전한 JPEG(SOI ffd8 / EOI ffd9)로 수신 확인.

## 증상과 오진의 기록

이 문제가 왜 어려웠는지 남겨둔다. 겉으로는 전부 "서버가 응답을 안 한다"로 보였다.

| 관찰된 증상 | 실제 의미 |
|---|---|
| 태블릿이 AP 에 결합은 되는데 `ARP FAILED` | 비콘(저속·고출력)만 도달, 데이터 프레임은 유실 |
| TCP `SYN-SENT` 만 반복, `[stream] 시작` 로그 없음 | 요청이 보드까지 도달하지 못함 |
| `/status` 같은 짧은 응답은 되는데 스트림만 죽음 | 짧은 버스트는 재전송으로 통과, 지속 전송은 무너짐 |
| STA 모드에서 공유기 결합 자체가 실패 | 같은 원인. 카메라를 끄면 즉시 결합됨 |

원인을 가른 결정적 실험은 **`ap_min/`** 이었다 — 카메라를 완전히 뺀 SoftAP + HTTP 서버가
`ARP REACHABLE` + `200 OK` 로 완벽히 동작했다. 즉 Wi-Fi·lwIP·TCP·하드웨어는 멀쩡했고,
카메라를 켤 때만 깨진다는 것이 확정됐다. 그 다음 **`ap_cam/`** 에서 XCLK 를 낮춰 재현/해소를
확인했고, 마지막으로 `FlashFreq` 까지 낮춰 지속 스트리밍이 안정화됐다.

메모리는 원인이 아니었다 (heap 167KB / PSRAM 4.17MB 여유).

## cam_web — 실제로 쓰는 것

Arduino ESP32 코어의 순정 `CameraWebServer` 예제를 그대로 들여와 최소한만 고쳤다.
서버 코드(`app_httpd.cpp`)는 손대지 않았다.

바꾼 곳은 `cam_web.ino` 세 군데뿐이다:

1. `config.xclk_freq_hz` 20MHz → 10MHz
2. 시작 해상도 QVGA → VGA
3. `USE_SOFTAP` 스위치 추가 (SoftAP / 공유기 접속 선택)

### 빌드 · 업로드

`FlashFreq=40` 이 반드시 들어가야 한다. Arduino IDE 에서는
**도구 → Flash Frequency → 40MHz** 로 설정한다.

```bash
arduino-cli compile -b esp32:esp32:esp32cam:PartitionScheme=huge_app,FlashFreq=40 cam_web
```

```bash
arduino-cli upload -b esp32:esp32:esp32cam:PartitionScheme=huge_app,FlashFreq=40 -p COM8 cam_web
```

### 접속

시리얼(115200)에 주소가 찍힌다.

| 경로 | 내용 |
|---|---|
| `http://<IP>/` | 제어 UI (해상도·화질 등) |
| `http://<IP>:81/stream` | MJPEG 실시간 영상 — **브라우저에서 바로 열린다** |
| `http://<IP>/capture` | 정지 프레임 한 장 |

해상도는 `/control?var=framesize&val=N` 으로 바꾼다. **`val` 은 코드의 `FRAMESIZE_*` 순번이고
VGA 는 10 이다** (8 은 400x296). 잘못 넣으면 조용히 다른 해상도로 바뀌므로 주의.

### 모드 전환 — `cam_web.ino` 상단 `USE_SOFTAP`

```
#define USE_SOFTAP 0   // 공유기 접속 (검증 완료)
#define USE_SOFTAP 1   // 보드가 AP, 192.168.4.1
```

이 보드는 공유기를 **-80 dBm** 으로밖에 듣지 못한다 (같은 자리 태블릿은 -67 dBm).
거리가 있으면 STA 로는 처리량이 떨어지므로, 태블릿을 카메라 바로 옆에 두는 용도라면
SoftAP 가 링크 여유가 훨씬 크다 (-26 dBm).

**주의**: SoftAP 모드는 위 두 수정을 모두 적용한 상태에서 지속 스트리밍을 끝까지 재검증하지
못했다 (검증 도중 태블릿이 USB 에서 분리됨). 제어 경로(`:80`)는 정상 동작을 확인했다.

## 진단용 스케치 (참고용, 지울 수 있음)

| 폴더 | 목적 | 결과 |
|---|---|---|
| `ap_min/` | 카메라를 뺀 SoftAP + HTTP | 완벽 동작 → Wi-Fi 는 무죄 |
| `ap_cam/` | 거기에 카메라만 추가, XCLK 조절 | XCLK 20MHz 에서 깨지고 10MHz 에서 회복 |
| `wifi_scan/` | ESP32 자기 안테나 기준 RSSI 측정 | 공유기를 -80dBm 으로 수신 |
| `stream_esp32/` | RTSP 서버 시도 (ESP32-RTSPServer 반입본) | 폐기 — 브라우저가 RTSP 를 재생하지 못함 |
| `stream_android/` | libVLC 기반 RTSP 뷰어 | 폐기 — 위와 같은 이유 |

RTSP 를 접은 이유: **브라우저는 `rtsp://` 를 재생하지 못한다.** MJPEG over HTTP 는 그냥 된다.
(`stream_esp32/rtsp_handles.cpp` 에는 SoftAP 모드에서 SDP 의 Content-Base 가 `0.0.0.0` 으로
나가던 라이브러리 버그의 수정이 들어 있다. 나중에 RTSP 가 필요해지면 그 부분은 쓸 만하다.)
