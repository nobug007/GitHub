# 샤인멍 3Screen2

- 작업 경로: `C:\GitHub\3Screen2`.
- 기존 호환 프로젝트: `C:\GitHub\ShineMung` — C# / .NET 8 / WPF / x64.
- Canon SDK 원본 경로: `C:\GitHub\EDSDK_64\Dll`.
- 앱 내 SDK 복사본: `lib\EDSDK\x64\EDSDK.dll`, `EdsImage.dll` (실행 파일 옆으로 복사).
- `src\EDSDK.cs`는 기존 샤인멍의 Canon P/Invoke 래퍼에서 복사했다.
- 기존 ShineMung 소스는 수정하지 않는다. 카메라 SD카드 사진은 삭제하지 않는다.
- 사용자 최신 요청: 카드 제거 + 카드 없이 셔터 사용 안함. 시작 시만 SaveTo.Host, 20분 후 SaveTo.Camera 복원 및 EdsCloseSession. 기존 Both/Camera 다운로드 운영 지침보다 이 요청이 우선한다.
- 메인 컨트롤은 지정한 노트북 모니터에 고정. 화면 교환은 외부 두 화면만 해당.
- 자연 배경 10종을 항상 5열 2행으로 표시. 털 색별 추천 2종 자동 선택, 선택 2종을 60초 주기로 교대.
- 이름은 실행마다 빈칸. Host/capacity 설정 성공 시 20분 카운트다운 시작. 활성 촬영 중 중복 시작/시간 연장 금지. 타이머는 CaptureLease와 SDK STA 스레드에서 관리한다.
- 시작 전/시간 만료 후 자동 Host 세션 열기 금지. 잠금 실패를 잠금 완료로 표시하지 않는다. 카드가 없으므로 시간 내 촬영된 진행 중 전송은 보존한다.
- 사진 화면 가로/세로는 콘텐츠 90도 회전이며 Windows 디스플레이 설정은 변경하지 않는다. 이름과 남은 시간은 사진 위에 항상 표시한다.
- 모든 EDSDK 호출과 참조 해제는 카메라 전용 STA 스레드에서 실행한다.
- 이 PC의 PATH dotnet은 x86 .NET 6이므로 `C:\Program Files\dotnet\dotnet.exe`를 사용한다.
- 빌드: `build.ps1`. 검증: `build.ps1 -Verify`. SDK 원본 래퍼 경고와 앱 자체 경고를 구분한다.
- 카메라 실촬영 / 혼합 DPI 3개 화면 / 케이블 재연결 검증은 실제 장비에서 수행해야 한다.
