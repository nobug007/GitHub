# Health — ESP32-CAM 스트리밍 송신기

ESP32-CAM 이 SoftAP 를 띄우고 카메라 영상을 MJPEG 로 내보내는 펌웨어. **이게 전부다.**
사람 관절 검출과 리깅 오버레이는 태블릿 앱 [`C:\GitHub\Streaming_ESP`](../Streaming_ESP) 가 맡는다.

```
ESP32-CAM ──SoftAP(Wi-Fi)──▶ 태블릿 앱
  MJPEG 송출만                YOLOv8n-pose 추론 + 초록 점/노란 선 오버레이
```

## 왜 ESP32 에서 리깅을 안 하는가

메모리도 벽이지만 **연산이 더 큰 벽**이다.

| | ESP32-CAM (ESP32-D0WD) | 필요한 것 |
|---|---|---|
| 앱 파티션 | 기본 ~1.5MB (플래시 4MB) | YOLOv8n-pose INT8 ≈ 3MB |
| 가속 | 벡터 명령 없음, 240MHz | — |
| 실측 기대치 | 프레임당 **수 초** | 최소 10fps |

ESP-DL 로 YOLO 계열을 돌리는 사례는 벡터 확장이 있는 **ESP32-S3/P4** 이야기고, 그마저도 결과는
"사람 바운딩 박스"지 관절 20점이 아니다. 같은 모델을 태블릿에서 돌리면 프레임당 60~150ms 로 끝난다.

## 하드웨어

- AI-Thinker ESP32-CAM (OV2640) + FTDI 또는 ESP32-CAM-MB 확장보드
- 5V 전원. USB-TTL 의 3.3V 로 구동하면 카메라 켜지는 순간 브라운아웃으로 리셋된다.
- microSD 는 필요 없다.

## 빌드 / 업로드 (Arduino IDE)

1. 보드 매니저에 `esp32` (Espressif Systems) 설치
2. 보드: **AI Thinker ESP32-CAM**, Partition Scheme: **Huge APP (3MB No OTA)**
3. FTDI 를 쓴다면 업로드 전 **GPIO0 ↔ GND** 를 점퍼로 연결하고 리셋, 업로드 후 점퍼 제거하고 다시 리셋
4. 시리얼 모니터 115200 에서 아래가 찍히면 정상

```
[cam] 초기화 완료
[wifi] SoftAP "ESP32CAM-RIG"  →  http://192.168.4.1/
[web] 확인 페이지 :80
[web] 스트림 :81/stream
[health] 준비 완료
```

## 동작 확인

태블릿/PC 를 Wi-Fi **`ESP32CAM-RIG`** (비밀번호 `rig12345`) 에 붙이고:

- `http://192.168.4.1/` — 브라우저에서 영상만 확인
- `http://192.168.4.1:81/capture` — 정지 프레임 한 장

여기까지 나오면 펌웨어는 끝난 것이고, 나머지는 앱이 한다.

## 태블릿 앱과의 프로토콜

`health_config.h` 상단 블록이 유일한 기준이다. 여기를 바꾸면
`Streaming_ESP/app/src/main/java/com/sf/streamingesp/StreamConfig.kt` 도 같이 바꿔야 한다.

| | |
|---|---|
| Wi-Fi | SSID `ESP32CAM-RIG` / PSK `rig12345` / GW `192.168.4.1` |
| `GET :81/stream` | `multipart/x-mixed-replace; boundary=123456789000000000000987654321` |
| 파트 헤더 | `Content-Type: image/jpeg` + `Content-Length: <N>` + 빈 줄 + JPEG |
| `GET :81/capture` | JPEG 한 장 |
| `GET :80/` | 확인용 HTML (앱은 쓰지 않음) |

앱의 MJPEG 파서는 파트마다 붙는 `Content-Length` 로 경계를 자른다. 이 헤더를 빼면
FFD8~FFD9 스캔으로 되돌아가 동작은 하지만 느려진다.

## 조정 지점 — `health_config.h`

| 항목 | 기본값 | 메모 |
|---|---|---|
| `HEALTH_FRAME_SIZE` | `FRAMESIZE_VGA` (640×480) | 모델 입력이 640이라 이 이상은 프레임레이트만 깎인다 |
| `HEALTH_JPEG_QUALITY` | 12 | 낮을수록 고화질·큰 용량 |
| `HEALTH_FLIP_VERTICAL/HORIZONTAL` | 0 | 거꾸로 매달았을 때 사용 |
| `HEALTH_AP_SSID/PASSWORD` | `ESP32CAM-RIG` / `rig12345` | 바꾸면 앱의 `StreamConfig` 도 같이 |

## 전신이 안 잡힐 때

관절 20점을 뽑으려면 **머리끝부터 발끝까지** 프레임 안에 들어와야 한다.
OV2640 기본 렌즈(약 65°) 기준 성인 전신은 **2.5~3.5m** 거리가 필요하다. 공간이 부족하면
광각(160°) 렌즈 모듈로 교체하는 편이 해상도를 올리는 것보다 효과가 크다.

## 상태

- 코드 작성 완료. 이 PC 에 `arduino-cli` 가 없어 **컴파일 검증은 하지 못했다** — Arduino IDE 에서 최초 1회 컴파일 확인 필요.
