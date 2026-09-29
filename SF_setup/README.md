# SF_setup — SafeFinder onboarding orchestration / test harness

PC(ADB) 기반으로 아래 4단계를 끝까지 자동 실행하고 검증하는 테스트 하네스입니다.

1. 태블릿에 SFD 설치 후 실행 → SFD가 BLE advertising 시작
2. SFD의 BLE advertising 활성 확인
3. 폰(SFC)에서 BLE 스캔 → SFD를 찾으면 폰의 **모바일 핫스팟** 켜기
4. 태블릿을 폰 핫스팟 SSID에 연결 (태블릿에 이미 저장된 네트워크)

---

## 결론 먼저: 셋팅으로 되나, 프로그램이 필요한가?

**핵심(3번 단계)은 "폰 설정"만으로도, "폰 앱" 단독으로도 자동화할 수 없습니다.
PC에서 오케스트레이션하는 프로그램(이 하네스)이 있어야 자동으로 돌아갑니다.**

이유:

- **폰 앱이 스스로 핫스팟을 못 켭니다.** Android 8(Oreo)부터 인터넷 공유형 모바일
  핫스팟(테더링) on/off는 서드파티 앱에 막혀 있습니다. `TetheringManager.startTethering()` /
  `ConnectivityManager`의 테더링 API는 시스템/특권(`TETHER_PRIVILEGED`, 시스템 서명) 앱만
  호출할 수 있습니다. 일반 앱이 쓸 수 있는 `WifiManager.startLocalOnlyHotspot()`은 SSID/암호가
  매번 랜덤으로 생성되는 **local-only**(인터넷 미공유) 핫스팟이라, "이미 등록된 고정 SSID(Nobug)"
  라는 이 시나리오에는 맞지 않습니다.
- **adb로도 직접 명령이 없습니다.** 이 폰(SM-S928N)에서 `cmd wifi`에는
  `get-softap-supported-features`만 있고 `start-softap` 류의 명령이 없습니다. 즉 핫스팟은
  반드시 **설정 UI 토글**(또는 시스템 특권 앱)을 거쳐야 켜집니다.
- 따라서 "BLE로 SFD를 찾으면 → 핫스팟 자동 ON" 이라는 이벤트 연동은 **온디바이스 순수 설정/앱으로
  불가능**하고, 설정 화면 토글을 대신 눌러주는 **외부 오케스트레이터**가 필요합니다. 그래서 이
  `SF_setup`을 만들었습니다.

> 실기기에 이 자동화를 "앱만으로" 심으려면 시스템 서명 권한(제조사/MDM/DeviceOwner)이 필요합니다.
> 그 경로는 별도 검토 사항입니다. 여기서는 PC-driven 하네스로 전체 플로우가 동작함을 검증합니다.

---

## 검증 결과 (실기기 통과)

- 폰: Samsung SM-S928N (`R3CX10423CL`) — SFC + 모바일 핫스팟 "Nobug"
- 태블릿: Samsung SM-X920 (`R54XA00DJPN`) — SFD

전 단계 통과. 최종 상태에서 태블릿이 폰 핫스팟에 연결됨:

```
Wifi is connected to "Nobug"
SSID: "Nobug", BSSID: 2e:e8:f4:f3:d9:89, IP: 10.199.74.90, Metered hint: true, Net ID: 42
```

BSSID가 폰 SoftAp BSSID와 일치하고, `Metered hint: true`(테더링 표식)이므로 홈 공유기가 아닌
폰 핫스팟에 붙은 것이 확인됩니다.

---

## 사용법

```powershell
# 기본: 폰/태블릿 자동 인식 + SFD 재설치 + 전체 플로우
powershell -ExecutionPolicy Bypass -File .\Run-SFSetup.ps1

# 깨끗한 상태에서 재현: 핫스팟 끄고 태블릿 wifi를 껐다 켠 뒤 시작
powershell -ExecutionPolicy Bypass -File .\Run-SFSetup.ps1 -Reset

# SFD 재설치 생략(이미 설치돼 있을 때 빠르게)
powershell -ExecutionPolicy Bypass -File .\Run-SFSetup.ps1 -SkipInstall
```

주요 파라미터:

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `-PhoneSerial` | 자동 인식 | 폰 adb serial 지정 |
| `-TabletSerial` | 자동 인식 | 태블릿 adb serial 지정 |
| `-SfdApk` | `C:\GitHub\SFD\app\build\outputs\apk\debug\app-debug.apk` | 설치할 SFD APK |
| `-SfdAdvertiseName` | `Safe Finder` | SFC 스캔 목록에서 SFD를 식별할 이름(부분일치) |
| `-Reset` | off | 시작 전 핫스팟 off + 태블릿 wifi 재기동 |
| `-SkipInstall` | off | SFD 설치 건너뜀 |

---

## 사전 조건

- 폰/태블릿 모두 `adb devices`에서 **authorized(`device`)** 상태 (USB 디버깅 허용).
- 폰에 SFC(`com.sf.sfc`), 태블릿에 SFD(`com.sf.sfd`)가 설치/권한 부여돼 있어야 합니다.
  (하네스는 SFD를 `-g`로 재설치하며 권한을 부여합니다. SFC는 이미 설치된 것을 사용합니다.)
- 태블릿에 폰 핫스팟 SSID(`Nobug`)가 **저장된 네트워크**로 등록돼 있어야 합니다(요구사항대로 기등록).
- 폰 핫스팟 SSID 이름은 하네스가 폰에서 자동으로 읽어옵니다(`mCurrentSoftApConfiguration`).

---

## 동작 방식 / 견고성 메모

- **핫스팟 상태 판정**은 `dumpsys wifi`의 `mCurrentSoftApInfoMap`에 `SoftApInfo{...}`가 있는지로
  판단합니다. `num SoftApManagers` 문자열은 과거 상태머신 로그(rec[N])에도 등장하므로 사용하지
  않습니다(오탐 원인).
- **핫스팟 토글**은 Samsung 테더링 화면에서 가장 위쪽(Y 최소) Switch = "모바일 핫스팟"을 눌러
  켭니다. 첫 탭이 안 먹으면 1회 재시도합니다.
- **UI 자동화**는 `uiautomator dump`를 파싱해 좌표를 동적으로 계산합니다(하드코딩 좌표 아님).
- 스크립트는 **ASCII 전용**으로 작성했습니다. Windows PowerShell 5.1은 BOM 없는 파일을 시스템
  코드페이지(CP949)로 읽어 한글이 섞이면 파싱이 깨질 수 있어, 한글 라벨 매칭 대신 위치/영문 기준으로
  요소를 찾습니다.

## 알려진 한계

- 실제 온디바이스 무인 자동화(폰 앱이 스스로 핫스팟 ON)는 시스템 서명 권한 없이는 불가.
- UI 좌표/문구는 제조사 One UI 버전에 의존적일 수 있습니다(현재 One UI/삼성 테더링 화면 기준).
- `SFD not found` 경고가 떠도 advertising이 확인되면 진행합니다(uiautomator가 AlertDialog 행을
  가끔 놓치기 때문).
