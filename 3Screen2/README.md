# 샤인멍 · 3 Screen Studio

C:\GitHub\3Screen2 — C# / .NET 8 / WPF / x64. 기존 C:\GitHub\ShineMung의 Canon 래퍼를 재사용합니다.

## 카드 없는 20분 촬영 운영

카메라 메모리카드를 빼고 카메라 메뉴의 ‘카드 없이 셔터 작동’을 ‘사용 안함’으로 설정하는 운영 방식입니다. 촬영 허용은 다음 순서로 제어합니다.

| 상태 | EDSDK 동작 |
|---|---|
| 실행 / 첫 연결 | 잠시 세션을 열어 SaveTo.Camera 설정 및 읽기 확인 → EdsCloseSession |
| 촬영 시작 | EdsOpenSession → SaveTo.Host → EdsSetCapacity → 설정 읽기 확인 → 성공 시 20분 시작 |
| 촬영 중 | 카메라 촬영 이벤트마다 PC 저장 및 사진 표시. 재시작/시간 연장 버튼 비활성화 |
| 20분 종료 | SaveTo.Camera 복원 및 읽기 확인 → EdsCloseSession |
| 앱 정상 종료 | 동일한 Camera 복원과 세션 종료 수행. 복원 확인 실패 시 종료하지 않고 상태 표시 |
| USB 끊김 | 촬영 허용 상태 해제. 재연결 시 Camera 복원/세션 종료부터 확인. Host 자동 재개 없음 |
| 설정 실패 | Host 시작 실패 시 Camera 복원 시도. 잠금 실패는 성공으로 표시하지 않고 재시도 |

‘카메라 다시 연결’은 카메라 검색/잠금 확인만 수행하며 Host로 전환하지 않습니다. 종료 이후의 주기적 검색도 촬영 허용 세션을 자동으로 열지 않습니다. 다음 촬영은 운영자가 촬영 시작 버튼을 다시 눌러야 합니다.

20분은 시스템 시각 변경에 영향받지 않는 단조 증가 타이머로 관리합니다. UI 표시와 별개로 SDK 전용 STA 스레드가 만료를 확인합니다. RAW/JPEG 전송은 1MB 단위로 진행하며 청크 사이에도 만료를 확인합니다. 만료 시 이미 전송 중인 사진은 PC가 유일한 사본이므로 Camera 복원을 먼저 시도하고 저장 완료 후 세션을 닫습니다. USB/SDK 호출이 응답하지 않거나 카메라가 Busy이면 복원/닫기가 지연될 수 있으며 ‘잠금 미완료’로 표시합니다.

실제 셔터 차단은 카메라의 카드 없음 설정과 SaveTo 동작에 의존합니다. 강제 프로세스 종료/PC 전원 차단에서는 SDK 복원을 보장할 수 없고, EOS Utility 등 다른 카메라 제어 앱이 Host로 전환하면 이 앱의 시간 제어 밖입니다. 카드 없는 EOS R5m2에서 시작 전 / 촬영 중 / 20분 종료 후 물리 셔터 동작을 확인해야 합니다.

## 화면과 실행

Start.cmd 또는 bin\Release\net8.0-windows\ShineMung.ThreeScreen.exe를 실행합니다. Windows 모니터는 확장 모드로 설정합니다. 메인 메뉴는 스크롤 없이 한 화면에 표시되며 외부 두 화면은 전체 화면으로 출력됩니다.

1. 오늘의 주인공에 강아지 이름을 입력합니다. 앱 실행마다 빈칸입니다.
2. 메인 노트북 화면을 지정합니다. 화면 바꾸기는 외부 두 화면만 교환합니다.
3. 사진 가로/세로 버튼은 출력 콘텐츠를 전환합니다. 가로로 인식된 모니터에서는 세로 모드가 90도 회전하고, 이미 세로인 모니터는 추가 회전하지 않습니다. JPEG EXIF 방향 정보도 반영합니다.
4. 자연 배경 10종이 5열 2행으로 표시됩니다. 털 색별 추천 2종 또는 원하는 2종을 선택해 적용하면 1분마다 교대합니다.
5. 카메라가 ‘촬영 대기 / 세션 닫힘’ 상태인 것을 확인하고 촬영 시작을 누릅니다. Host 설정에 성공해야 20분이 시작됩니다. 버튼은 원격 셔터가 아닙니다.
6. 사진 위에는 이름과 남은 시간이 계속 표시됩니다. 20분 종료 후에도 마지막 사진은 유지합니다.

## EDSDK / 저장 위치

- SDK 원본: C:\GitHub\EDSDK_64\Dll
- 프로젝트 DLL: lib\EDSDK\x64\EDSDK.dll, EdsImage.dll
- 래퍼 원본: C:\GitHub\ShineMung\src\ShineMung.Edsdk\EDSDK.cs
- SaveTo.Host이므로 사진은 PC에만 저장됩니다. JPEG 또는 RAW+JPEG 권장. RAW만 촬영하면 원본을 저장하고 SDK 썸네일로 표시합니다.
- 저장 위치: 내 사진\ShineMung3Screen\yyyy-MM-dd\DOG_강아지이름
- 설정과 로그: %LocalAppData%\ShineMung3Screen\settings.json, camera.log, displays.log
- EOS Utility는 종료한 상태에서 사용하세요. 0xC0는 다른 앱이 카메라 통신 포트를 점유한 상태입니다.

## 배경과 검증

자연 배경 PNG 10종: assets\backgrounds. 내장 image_gen 생성 프롬프트: assets\backgrounds\prompts.json.

build.ps1 또는 build.ps1 -Verify로 빌드/검증합니다. 이 PC에서는 x64 C:\Program Files\dotnet\dotnet.exe를 사용합니다. verification\result.txt와 control.png, landscape.png, portrait.png에 결과를 남깁니다.

CaptureLeaseVerification은 가상 시간으로 시작 전 잠금, Host 성공 후 20분 시작, 20분 직전/정확한 만료 경계, Camera→Close 순서, 실패 롤백, 잠금 재시도, 중복 시작 방지, 연결 끊김 후 Host 자동 재개 방지를 검증합니다. 실제 카메라 셔터 잠금 실험을 대체하지 않습니다.
