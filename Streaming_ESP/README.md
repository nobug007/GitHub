# Streaming_ESP — 태블릿 리깅 뷰어

ESP32-CAM([`C:\GitHub\Health`](../Health))이 보내는 MJPEG 를 받아, **YOLOv8n-pose** 로 사람의 관절을
찾고 **초록 점 · 노란 선**으로 전신 리그를 영상 위에 그리는 안드로이드 앱.

메뉴도 버튼도 설정 화면도 없다. 실행하면 바로 카메라에 붙고, 화면에 사람이 보이면 무조건 리깅이 나온다.

## 화면에 그려지는 20 관절

```
                    머리
                     │
        어깨L ─────── 목 ─────── 어깨R
          │           │           │
        팔꿈치L      허리        팔꿈치R
          │           │           │
        손목L        골반        손목R
          │         ╱   ╲         │
         손L    골반L   골반R     손R
                  │       │
                무릎L    무릎R
                  │       │
                발목L    발목R
                  │       │
                발끝L    발끝R
```

**정직하게 밝혀두는 부분**: YOLOv8-pose 가 실제로 주는 것은 COCO 17점(코·눈·귀·어깨·팔꿈치·손목·
골반·무릎·발목)뿐이다. 요청된 나머지는 [`Rig.kt`](app/src/main/java/com/sf/streamingesp/Rig.kt) 에서 만들어낸다.

| 관절 | 방식 |
|---|---|
| 목 | 양 어깨의 중점 |
| 골반(중심) | 양 엉덩이의 중점 |
| 허리 | 목→골반 사이 55% 지점 |
| 머리 | 코를 목 반대 방향으로 35% 밀어 정수리 쪽에 배치 |
| 손 | 팔꿈치→손목 방향으로 30% 연장한 **추정치** |
| 발끝 | 무릎→발목 방향으로 35% 연장한 **추정치** |

손끝·발끝이 실제 랜드마크여야 한다면 BlazePose(33점, 발/손 랜드마크 포함) 계열로 교체해야 하고,
그만큼 속도를 내주게 된다. `PoseEngine` 만 갈아끼우면 되도록 분리해 두었다.

## 구조

```
MJPEG 스레드 ── 최신 프레임 1장 ──▶ 추론 스레드 ──(프레임+리그 쌍)──▶ UI
 연결·파싱·JPEG 디코드              YOLOv8n-pose            RigOverlayView
```

- 프레임과 추론 결과를 **쌍으로** 넘긴다. 최신 영상을 따로 그리고 결과만 나중에 얹으면
  추론에 걸린 시간만큼 스켈레톤이 몸에서 밀린다. 표시 fps 를 추론 속도에 맞추는 대신
  관절이 항상 몸에 붙어 있게 했다.
- 추론이 못 따라간 프레임은 버린다. 큐를 쌓으면 지연만 늘어난다.

| 파일 | 역할 |
|---|---|
| [`MainActivity.kt`](app/src/main/java/com/sf/streamingesp/MainActivity.kt) | 전체화면, 네트워크 바인딩, 스레드 배선, 평활 |
| [`MjpegStream.kt`](app/src/main/java/com/sf/streamingesp/MjpegStream.kt) | multipart 파싱 → Bitmap, 자동 재접속 |
| [`PoseEngine.kt`](app/src/main/java/com/sf/streamingesp/PoseEngine.kt) | letterbox 전처리 → ONNX Runtime → COCO 17점 |
| [`Rig.kt`](app/src/main/java/com/sf/streamingesp/Rig.kt) | COCO 17 → 20 관절 + 뼈대 정의 |
| [`RigOverlayView.kt`](app/src/main/java/com/sf/streamingesp/RigOverlayView.kt) | 영상 + 스켈레톤을 같은 캔버스에 렌더 |

## 엔진

`app/src/main/assets/yolov8n-pose.onnx` — **13.5MB, 이미 포함되어 있다.** 따로 받을 것 없다.

- Ultralytics YOLOv8n-pose (COCO-pose 학습, `kpt_shape [17,3]`, 클래스 `person` 1개)
- 입력 `images` `[1,3,640,640]` RGB 0~1, letterbox(패딩 회색 114)
- 출력 `output0` `[1,56,8400]` = 4(박스) + 1(person 신뢰도) + 17×3(x,y,score)
- 런타임: ONNX Runtime Android 1.19.2, XNNPACK. 태블릿 CPU 기준 프레임당 60~150ms

다시 받아야 하면 [`tools/fetch_model.ps1`](tools/fetch_model.ps1).

## 빌드 / 설치

```bash
cd C:\GitHub\Streaming_ESP && .\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install -r C:\GitHub\Streaming_ESP\app\build\outputs\apk\debug\app-debug.apk
```

## 사용

1. ESP32-CAM 전원 인가
2. 태블릿 Wi-Fi 를 **`ESP32CAM-RIG`** (비밀번호 `rig12345`) 에 연결
3. **Rig** 앱 실행 — 끝

"인터넷이 없습니다. 이 네트워크를 유지할까요?" 가 뜨면 **유지**를 선택한다.
앱은 그와 별개로 `ConnectivityManager.bindProcessToNetwork()` 로 트래픽을 이 Wi-Fi 에
직접 묶기 때문에, 안드로이드가 기본 경로를 모바일 데이터로 되돌려도 영상은 계속 들어온다.

## 조정 지점

| 값 | 위치 | 기본 | 의미 |
|---|---|---|---|
| `minPersonScore` | `PoseEngine.detect()` | 0.45 | 사람으로 인정할 최소 신뢰도 |
| `minKeypointScore` | `MainActivity` | 0.30 | 낮추면 점이 늘지만 헛점도 늘어난다 |
| `minVisibleJoints` | `MainActivity` | 5 | 낮추면 사물에도 스켈레톤이 붙는다 |
| `smoothAlpha` | `MainActivity` | 0.55 | 1에 가까울수록 반응 빠르고 떨림 심함 |
| `holdMs` | `MainActivity` | 450 | 검출이 잠깐 끊겨도 스켈레톤을 유지하는 시간 |
| 색상 | `RigOverlayView` | `#00FF66` / `#FFE600` | 관절 초록 / 뼈대 노랑 |

## 상태

- `gradlew assembleDebug` **빌드 성공** (2026-08-20). 실제 기기에서의 동작 확인은 아직 하지 않았다.
