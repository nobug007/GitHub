# SFC 디자인 방향 명세서 (Warm Paper / Guardian Teal)

- 대상: `app/src/main/java/com/sf/sfc/MainActivity.kt` (코드로 View를 조립하는 방식, 약 2,430줄)
- 목적: 하드코딩된 Tailwind 계열 색을 레퍼런스 시안 기반 디자인 토큰으로 통일하고, 화면 성격을 색(ambient glow)으로 알려주는 일관된 체계를 만든다.
- 이 문서는 명세서다. 코드 변경은 포함하지 않는다.

## 0. 원칙

1. **토큰 고정.** 1장의 색 값은 사용자가 확정한 값이다. 임의 변경 금지. 새 색이 필요하면 "승인 필요" 표시 후 별도 항목으로 제안한다(1.3).
2. **레이아웃 유지.** `renderHome`(721)은 **현재 레이아웃 구성을 그대로 둔다.** 헤더 → 타임스탬프 → 마지막 동기화 → 이름 → 큰 아이콘 → 구역명 → 상태 카드 → 타일 2개 → 배터리바 순서와 크기를 바꾸지 않는다. 색/질감/테두리만 교체한다.
3. **큰 글자 감각 유지.** 현재 앱은 22~36sp를 쓴다. 시안(웹 px 기준)의 9.5~13px 값을 그대로 sp로 옮기지 않는다. 3장 타이포 스케일을 따른다.
4. **색은 의미다.** teal=평온/정상, amber=확인 필요, gold=완료, danger=삭제/SOS 전용. 화면마다 다른 의미로 재사용하지 않는다.
5. **백엔드 로직 무변경.** `BleProvisioningManager`, `SfcApiClient`, `SfcBleMonitorService`는 건드리지 않는다. 상태 값 노출이 추가로 필요하면 `sfc-owner`에게 위임한다(11장).

---

## 1. 색 토큰

### 1.1 기본 토큰 (Kotlin ARGB Int)

새 파일 `app/src/main/java/com/sf/sfc/SfcTheme.kt`에 `object SfcColors`로 선언하는 것을 권장한다(MainActivity 하단 `private object`도 가능).

| 토큰 | CSS 값 | Kotlin 상수 | 용도 |
|---|---|---|---|
| `--bg` | `#FAF6F0` | `val BG = 0xFFFAF6F0.toInt()` | 모든 페이지 배경(warm paper) |
| `--surface` | `#FFFFFF` | `val SURFACE = 0xFFFFFFFF.toInt()` | 카드, 시트, 입력 필드 배경 |
| `--surface-alt` | `#F2ECE2` | `val SURFACE_ALT = 0xFFF2ECE2.toInt()` | 비활성 pill, 테이블 헤더, 보조 블록 |
| `--ink` | `#2E2A24` | `val INK = 0xFF2E2A24.toInt()` | 본문/제목 텍스트 |
| `--ink-soft` | `#746C5F` | `val INK_SOFT = 0xFF746C5F.toInt()` | 보조 텍스트, ghost 버튼 라벨 |
| `--ink-faint` | `#A69E8F` | `val INK_FAINT = 0xFFA69E8F.toInt()` | 비활성 요소, 아이콘, placeholder |
| `--primary` | `#24605C` | `val PRIMARY = 0xFF24605C.toInt()` | 주 버튼, 선택 상태, 정상 상태 |
| `--primary-soft` | `#E3EEEC` | `val PRIMARY_SOFT = 0xFFE3EEEC.toInt()` | 선택 배경, 정상 pill 배경, 아이콘 배지 |
| `--primary-dark` | `#163E3B` | `val PRIMARY_DARK = 0xFF163E3B.toInt()` | primary-soft 위 텍스트, 눌림 상태 |
| `--gold` | `#E3A94C` | `val GOLD = 0xFFE3A94C.toInt()` | 완료 상태(도형/배지 전용) |
| `--gold-soft` | `#FBF0DC` | `val GOLD_SOFT = 0xFFFBF0DC.toInt()` | 완료 배지/배경 |
| `--amber` | `#C9853B` | `val AMBER = 0xFFC9853B.toInt()` | 주의/확인 필요(도형/테두리/배지) |
| `--amber-soft` | `#F5E4CC` | `val AMBER_SOFT = 0xFFF5E4CC.toInt()` | 주의 pill/배너 배경 |
| `--line` | `#E7DFD2` | `val LINE = 0xFFE7DFD2.toInt()` | 모든 테두리, 구분선, 토글 off |
| `--danger` | `#C25B4A` | `val DANGER = 0xFFC25B4A.toInt()` | 실제 삭제 / SOS 전용 |

### 1.2 파생 토큰 (기본 토큰에서 기계적으로 유도, 승인 불필요)

| 이름 | 값 | 유도 방식 | 용도 |
|---|---|---|---|
| `DANGER_SOFT` | `0xFFF5E6E1.toInt()` | 사용자 지시에 이미 명시(선택모드 삭제 버튼 배경) | 삭제 액션 배경 |
| `GLOW_DAWN` | `0xFF7FA6D6.toInt()` | 사용자 지시에 명시(새벽빛) | 등록 플로우 1단계 glow |
| `GLOW_MORNING` | `0xFF518399.toInt()` | `GLOW_DAWN`↔`PRIMARY` 50% 보간 | 등록 플로우 2단계 glow |
| `SCRIM` | `0x662E2A24` | `INK` 40% alpha | 바텀시트/다이얼로그 뒤 배경 |
| `PRESSED_OVERLAY` | `0x14163E3B` | `PRIMARY_DARK` 8% alpha | 버튼 눌림 상태 |

glow는 항상 **alpha를 적용한 원본 토큰**으로 만든다. 별도 색을 새로 정의하지 않는다(6장).

### 1.3 승인 필요 항목 (기본 토큰만으로 접근성 기준을 못 맞추는 경우)

`--amber #C9853B`는 `--bg` 위 대비가 **2.82:1**이라 어떤 크기에서도 텍스트로 쓸 수 없다. `--gold`는 **1.94:1**로 더 낮다. 따라서:

- **원칙 해법(추가 색 없음):** 주의/완료 상태의 **텍스트는 `INK` 또는 `PRIMARY_DARK`로 쓰고**, 색 신호는 pill 배경(`AMBER_SOFT`/`GOLD_SOFT`), 아이콘 배지, 좌측 4dp 컬러 바로만 전달한다. **이 방식을 기본으로 채택한다.**
- **선택지(승인 필요):** 주의 텍스트에 색을 꼭 넣어야 한다면 `AMBER_INK = 0xFF8A5620.toInt()`(amber의 명도만 낮춘 값)를 **추가** 토큰으로 제안한다. bg 위 5.68:1, amber-soft 위 4.90:1. 기존 토큰은 변경하지 않는다. 사용자 승인 전에는 쓰지 않는다.

### 1.4 현재 하드코딩 색 → 토큰 치환표

`MainActivity.kt` 전역 일괄 치환 기준. 좌측 값이 파일에 남아 있으면 안 된다.

| 현재 값 | 등장 위치(예) | 치환 |
|---|---|---|
| `0xFF111827` | title/label/text/tableCell/tile/calendar 등 다수 | `INK` |
| `0xFF475569` | 상세 텍스트, 헤더 deviceId, 에러 상세, legend | `INK_SOFT` |
| `0xFF334155` | 로그 원문 텍스트(1579) | `INK_SOFT` |
| `0xFF64748B` | "Elder ID:", 안내 문구, 빈 목록 문구 | `INK_SOFT` (읽어야 하는 문구이므로 faint 금지) |
| `0xFF94A3B8` | 마지막 동기화(729), 달력 이전달 날짜(2160) | `INK_FAINT` (장식/비활성만) |
| `0xFFCBD5E1` | input 테두리(2228), secondaryButton 테두리(2249), 달력 `‹` 버튼(2107) | `LINE` |
| `0xFFD5DDE8` | 배터리바 빈 구간(2362) | `LINE` |
| `0xFFE2E8F0` | 오늘 날짜 배경(2153) | `PRIMARY_SOFT` |
| `0xFFEAF8FF`→`0xFFFFFBF2` 그라데이션 | `page()`(2190), `showMap` root(1815) | 단색 `BG` |
| `0xF7FFFFFF` | `card()` 배경(2370) | `SURFACE` + `LINE` 1dp 테두리 |
| `0xFF70C39A`→`0xFF4C7FC0` 그라데이션 | `primaryButton()`(2238) | 단색 `PRIMARY` |
| `0xFF2563EB` | `tile()` 기호 색(2332) | `PRIMARY` |
| `0xFF5B7ED5` | 달력 `›` 버튼(2118) | `PRIMARY` (양쪽 화살표 동일 스타일) |
| `0xFF77C98A` | 완료 체크 원(645) | `GOLD` (완료 = 골드) |
| `0xFF16A34A`, `0xFF12A150`, `0xFF43C96E`, `0xFF7CCB74`, `0xFF22C55E` | 정상 상태/배터리/요약타일/달력 N마커/지도 legend | `PRIMARY` |
| `0xFFF59E0B`, `0xFFFFC928`, `0xFFE7C82F`, `0xFFEAB308`, `0xFFF97316` | 주의/이탈/이동중/W마커 | `AMBER` |
| `0xFFDC2626`, `0xFFEF4444`, `0xFFC85E52` | SOS/긴급/E마커/에러 | `DANGER` |
| `0xFF991B1B` | 에러 화면 제목(1273, 1491) | `INK` (제목은 ink, 색 신호는 amber 배지로) |
| `0xFFEDEDED` | 지도 WebView 배경(1829) | `SURFACE_ALT` |
| 지도 HTML `#22C55E` / `#F97316` / `#EAB308` / `#EF4444` / `#94A3B8` | `mapPointColor`(1905) | `#24605C` / `#C9853B` / `#C9853B` / `#C25B4A` / `#A69E8F` |
| 지도 HTML `#2563eb` 폴리라인 | `buildMapHtml`(1965) | `#24605C` (opacity 0.55) |
| 지도 HTML `#e5e7eb` 컨테이너 | `buildMapHtml`(1954) | `#F2ECE2` |

주의: `mapPointColor`에서 "경고(WARNING)"와 "이동중(안전구역 밖)"이 지금은 노랑/주황으로 구분된다. 새 팔레트에서는 둘 다 `#C9853B`가 되어 **구분이 사라진다.** 이동중은 `#C9853B` **채움 + 흰 테두리**, 경고는 `#C9853B` **채움 + `#8A5620` 두꺼운 테두리(weight 3)** 로 형태로 구분한다. legend 라벨도 이에 맞춰 원 대신 링 모양을 쓴다.

---

## 2. 형태 토큰 (Shape / Elevation)

| 이름 | 값 | 적용 |
|---|---|---|
| `R_CARD` | 14dp | 카드, 요약 타일, 달력 카드 |
| `R_ITEM` | 13dp | 리스트 아이템, 주 버튼 |
| `R_INPUT` | 11dp | 입력 필드, Spinner |
| `R_CHIP` | 10dp | 선택 칩, 아이콘 배지 |
| `R_PILL` | 999(=`dp(999)`) | 상태 pill, 토글 |
| `R_SHEET` | 22dp (상단 2개 코너만) | 바텀시트 |
| `S_HAIRLINE` | 1dp | 카드 테두리, 구분선, 액션바 상단선 |
| `S_STROKE` | 1.5dp | 리스트 아이템, 입력 필드, 칩, ghost 버튼 |
| **elevation** | **0** | **모든 카드/버튼. 그림자를 쓰지 않고 `LINE` 테두리로 면을 구분한다.** |

현재 `card()`의 `elevation = dp(6)`, `appLogo()`의 `dp(8)`, 홈 큰 아이콘의 `dp(8)`은 전부 0으로 내리고 테두리로 대체한다(warm paper 질감에서 회색 그림자는 지저분해 보인다). 예외: 바텀시트만 `SCRIM`으로 깊이를 만든다.

### 2.1 여백

시안 값(카드 padding 14x16 = 세로 14 / 가로 16)을 기준으로 하되, SFC는 22~36sp 큰 글자를 쓰므로 아래 보정을 허용한다. **radius와 테두리 두께는 보정 금지.**

| 컨텍스트 | padding |
|---|---|
| 카드(일반) | 가로 16dp / 세로 14dp |
| 카드(홈 상태 카드, 22sp 이상 텍스트 포함) | 가로 18dp / 세로 16dp (허용 보정 +2~4dp) |
| 리스트 아이템 | 가로 13dp / 세로 12dp |
| 입력 필드 | 가로 12dp / 세로 10dp, 최소 높이 52dp |
| 주 버튼 | 세로 13dp, 최소 높이 56dp |
| 페이지 | 가로 20dp / 하단 24dp / 상단 0(헤더가 상단을 채움) |

### 2.2 타이포 스케일 (sp)

시안의 px 값을 그대로 옮기지 않는다. 현재 앱의 큰 글자 감각을 유지한다.

| 역할 | 크기 | 굵기 | 색 |
|---|---|---|---|
| 홈 이름(elderName) | 36sp | Bold | `INK` |
| 홈 구역명 | 32sp | Bold | 상태색(9장) |
| 완료/대기 화면 제목 | 30~34sp | Bold | `INK` |
| 화면 제목(header) | 24sp | Bold | `INK` |
| 홈 타임스탬프 | 22sp | Regular | `INK_SOFT` |
| 섹션 라벨 | 18sp | Bold | `INK` |
| 본문 | 17~18sp | Regular | `INK` |
| 버튼 라벨(주) | 19sp | Bold | `#FFFFFF` |
| 버튼 라벨(보조/ghost) | 17sp | Bold | `INK_SOFT` |
| 보조 설명 | 15sp | Regular | `INK_SOFT` |
| 캡션(마지막 동기화, Elder ID) | **14sp 하한** | Regular | `INK_SOFT` |
| 상태 pill | **12sp 하한** | Bold | 9장 규칙 |
| 표 셀 | **14sp 하한** | Regular | `INK` |

현재 13sp 이하로 쓰이는 곳은 전부 하한까지 올린다: `lastSyncLabel` 13f→14f, "Elder ID:" 12f→14f, 헤더 deviceId 12f→14f, legend 12f→14f, 표 헤더 13f→14f, 표 셀 11~12f→14f, 로그 원문 13f→14f, 달력 마커 9f→12f(배지 크기 18dp→22dp), 안내 문구 13f→15f.

---

## 3. 스타일 헬퍼 개편 (before / after)

### 3.1 `page()` (2187)

| | 현재 | 변경 후 |
|---|---|---|
| 배경 | `gradient(0xFFEAF8FF, 0xFFFFFBF2)` | `BG` 단색 |
| padding | `22, 26, 22, 28` | `20, 0, 20, 24` |

상단 padding을 0으로 내리는 이유는 ambient glow가 화면 최상단에 붙어야 하기 때문이다. glow를 포함한 `header()`가 내부에서 상단 여백을 책임진다. glow는 좌우 full-bleed여야 하므로 `header()`가 자기 layoutParams에 `marginStart = -dp(20)`, `marginEnd = -dp(20)`을 적용해 페이지 가로 padding을 상쇄한다. **이 방식이면 각 화면의 `content.addView(header(...))` 호출 순서를 바꿀 필요가 없다.**

### 3.2 `card()` (2367)

| | 현재 | 변경 후 |
|---|---|---|
| 배경 | `rounded(0xF7FFFFFF, 18)` | `roundedStroke(SURFACE, R_CARD=14, LINE, S_HAIRLINE)` |
| padding | `22` 전방향 | 가로 16 / 세로 14 |
| elevation | `dp(6)` | `0` |

파라미터화 제안: `card(tone: Tone = Tone.NEUTRAL)` — `NEUTRAL`=surface/line, `INFO`=primary-soft 배경 + primary 테두리, `WARN`=amber-soft 배경 + amber 테두리. 에러 카드·안내 배너를 별도 코드로 만들지 않게 한다.

### 3.3 `title()` / `label()` / `text()` (2209~2220)

- `title(value, size)` — 색을 `INK` 고정으로 유지(현재 `0xFF111827`만 교체). 시그니처 변경 없음.
- `label(value)` — 18sp Bold `INK` 유지, padding `4,12,0,4` → `2,14,0,6`.
- `text(value, size, color, bold)` — 시그니처 유지. **단, 호출부에서 raw hex를 넘기는 것을 금지**하고 `SfcColors.*`만 넘긴다. 추가로 `line spacing 1.2`를 기본 적용해 한국어 다행 문구 가독성을 올린다.

### 3.4 `input()` (2222)

| | 현재 | 변경 후 |
|---|---|---|
| 배경 | `roundedStroke(0x00FFFFFF, 22, 0xFFCBD5E1)` (투명 채움) | `roundedStroke(SURFACE, R_INPUT=11, LINE, S_STROKE=1.5dp)` |
| padding | `18, 0, 18, 0` | `12, 10, 12, 10`, 최소 높이 52dp |
| 텍스트 | 18sp | 18sp `INK` |
| hint | 기본색 | `INK_FAINT` (`setHintTextColor`) |
| 포커스 | 없음 | 포커스 시 테두리 `PRIMARY`, 배경 `SURFACE` 유지 |
| 비활성 | 호출부에서 `setTextColor(0xFF475569)` 수동 지정(878, 884) | `input(..., enabled=false)` 시 배경 `SURFACE_ALT`, 텍스트 `INK_SOFT`, 테두리 `LINE` — 헬퍼가 처리 |

비활성 필드(WiFi AP Name, ssid, bssid)는 "왜 못 고치는지"가 보이지 않는 것이 문제다. `input()`에 `hintBelow: String? = null` 파라미터를 추가해 필드 아래 14sp `INK_SOFT` 한 줄 설명을 붙일 수 있게 한다(예: "지금 연결된 WiFi에서 자동으로 가져왔어요").

### 3.5 `primaryButton()` (2232)

| | 현재 | 변경 후 |
|---|---|---|
| 배경 | `gradient(0xFF70C39A, 0xFF4C7FC0, 24)` | `rounded(PRIMARY, R_ITEM=13)` |
| 텍스트 | 19sp Bold White | 유지 |
| 높이 | 호출부 `rowParams(height=56)` | 유지(최소 56dp) |
| 상태 | 없음 | `StateListDrawable`: pressed=`PRIMARY_DARK`, disabled=`LINE` 배경 + `INK_FAINT` 텍스트 |

### 3.6 `secondaryButton()` (2243) → `ghostButton()`

| | 현재 | 변경 후 |
|---|---|---|
| 이름 | `secondaryButton` | `ghostButton` (시안 용어와 일치). 기존 이름은 별칭으로 남겨 호출부 일괄 수정 부담을 줄여도 됨 |
| 배경 | `roundedStroke(0xFFFFFFFF, 14, 0xFFCBD5E1)` | 투명 + `R_ITEM=13` + `LINE` 1.5dp 테두리 |
| 텍스트 | 16sp Bold `0xFF111827` | 17sp Bold `INK_SOFT` |
| 선택/활성 | 없음 | 활성 시 배경 `PRIMARY_SOFT`, 테두리 `PRIMARY`, 텍스트 `PRIMARY_DARK` |

### 3.7 `tile()` (2329) — 홈의 "지도 보기"/"Log 보기"

레이아웃(2열, 높이 142dp) 유지. 색만 변경.

| | 현재 | 변경 후 |
|---|---|---|
| 컨테이너 | `card()` | `card()` (자동으로 새 스펙 적용) |
| 기호 | 48sp `0xFF2563EB` | 기호를 `iconBadge`(38dp, `PRIMARY_SOFT` 배경, `R_CHIP`) 안에 26sp `PRIMARY`로 배치 |
| 라벨 | 23sp Bold `0xFF111827` | 23sp Bold `INK` 유지 |

### 3.8 `summaryTile()` (2336) — 로그 요약 3종

| | 현재 | 변경 후 |
|---|---|---|
| 원 | `oval(color)` 70dp, 흰 숫자 23sp | 유지하되 색: 정상=`PRIMARY`, 주의=`AMBER`, 긴급=`DANGER` |
| 흰 숫자 대비 | 주의(`0xFFFFC928`) 위 흰 글씨 = 1.5:1로 판독 불가 | **주의 원은 `AMBER_SOFT` 채움 + `AMBER` 1.5dp 테두리 + `INK` 숫자**로 변경. 정상/긴급은 채움 + 흰 숫자 유지(각각 7.2:1, 4.3:1) |
| 라벨 | 15sp `0xFF111827` | 16sp `INK` |

### 3.9 `batteryBar()` (2346)

| | 현재 | 변경 후 |
|---|---|---|
| 채움색 | null=`0xFF94A3B8`, ≤20=`0xFFEF4444`, ≤50=`0xFFF59E0B`, else `0xFF12A150` | null=`INK_FAINT`, ≤20=`AMBER`, else `PRIMARY` (사용자 규칙: 배터리 부족=amber, danger는 삭제/SOS 전용) |
| 트랙 | `0xFFD5DDE8` | `LINE` |
| 높이/radius | 18dp / 9 | 유지 |
| 텍스트 | `" 20%"` 24sp `0xFF111827` | `"배터리 20%"` 22sp `INK`. 숫자만으로는 보호자가 무엇의 20%인지 모른다 |
| 20% 이하 | 색만 바뀜 | 뒤에 14sp `INK_SOFT`로 "충전이 필요해요" 추가 |

### 3.10 `header()` (2001)

시그니처를 확장해 ambient glow를 흡수한다. **호출부 구조 변경 없음.**

```kotlin
private fun header(
    titleText: String = "Safe Finder",
    showBack: Boolean = false,
    glow: Glow = Glow.TEAL
): LinearLayout
```

| | 현재 | 변경 후 |
|---|---|---|
| 배경 | 없음 | `ambientGlowDrawable(glow)` — 높이 92~108dp의 radial glow (6장) |
| 뒤로가기 | `android.R.drawable.ic_media_previous` (시스템 "이전 트랙" 아이콘) | 좌향 chevron 벡터로 교체. 48dp 터치 타겟 유지, tint `INK` |
| 메뉴 | `android.R.drawable.ic_menu_sort_by_size` (정렬 아이콘, 메뉴 의미 아님) | 3점/햄버거 벡터로 교체, tint `INK`. **접근성**: `contentDescription = "메뉴"` |
| 제목 | 24sp Bold `INK` | 유지 |
| deviceId | 12sp `0xFF475569` | `statusPill(currentDeviceId(), PillTone.NEUTRAL)` — 14sp 이상, `SURFACE_ALT` 배경 |
| 상하 padding | `0,0,0,12` | `0,14,0,16` (glow 영역 내부에 정렬) |

### 3.11 표 렌더링 — `safeZoneHeaderRow`/`safeZoneRow`/`guardianHeaderRow`/`guardianRow` (2253~2321)

현재 6열/5열 가로 표에 11~12sp 글자와 `CheckBox`를 쓴다. 보호자·고령 사용자에게 가장 취약한 부분이다.

| | 현재 | 변경 후 |
|---|---|---|
| 구조 | 가로 표(6열, BSSID/SSID 원문 노출) | `listItem()` 세로 카드형(3.12의 신규 헬퍼). 1행: 이름 18sp Bold + 상태 pill / 2행: 유형·SSID 15sp `INK_SOFT` / BSSID는 접힘(길게 누르면 표시) |
| 선택 | `CheckBox` | 아이템 전체 탭. 선택 시 테두리 `PRIMARY` + 배경 `PRIMARY_SOFT` |
| 행 높이 | 54dp | 최소 64dp |
| 배경 | `rounded(0xFFFFFFFF, 8)` | `roundedStroke(SURFACE, R_ITEM=13, LINE, 1.5dp)` |
| `Enabled` 열 | `"true"/"false"` 문자열 | `toggleRow` 또는 `statusPill("사용 중"/"꺼짐")` |

용어: 사용자 화면에서 `BSSID`, `SSID`, `zoneType`, `Elder ID`를 그대로 노출하지 않는다. 각각 "기기 식별값", "WiFi 이름", "구역 종류", "등록 번호"로 표기하고 원문은 개발자용 로그 화면에만 남긴다. (전 앱 공통 용어집 대상 — `project-manager` 공유 항목, 11장)

---

## 4. 신규 헬퍼 (시그니처 제안)

모두 `MainActivity` private 멤버로 추가한다. 반환 타입은 기존 코드 스타일(`View`/`LinearLayout` 직접 반환)을 따른다.

```kotlin
// 4.1 화면 성격을 알리는 상단 glow. header()가 내부에서 호출한다.
private enum class Glow { DAWN, MORNING, GOLD, TEAL, AMBER, DANGER, NEUTRAL }
private fun ambientGlowDrawable(glow: Glow, heightDp: Int = 100): Drawable

// glow 없이 헤더가 없는 화면(pairing/waiting/complete)에서 단독으로 얹는 경우
private fun ambientHeader(
    titleText: String? = null,
    glow: Glow,
    heightDp: Int = 108
): LinearLayout

// 4.2 상태 pill (radius 999, 12sp Bold 하한)
private enum class PillTone { ON, OFF, WARN, DONE, ALERT, NEUTRAL }
private fun statusPill(label: String, tone: PillTone = PillTone.NEUTRAL): TextView

// 4.3 리스트 아이템 (안전구역/보호자/BLE 기기 목록 공통)
private fun listItem(
    titleText: String,
    subtitle: String? = null,
    pill: Pair<String, PillTone>? = null,
    leading: View? = null,          // iconBadge 등
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
): LinearLayout

// 4.4 아이콘 배지 (36~38dp, radius 10)
private fun iconBadge(
    glyph: String? = null,           // "⌖", "▦" 등 기존 기호
    iconRes: Int? = null,
    tone: PillTone = PillTone.NEUTRAL,
    sizeDp: Int = 38
): TextView

// 4.5 토글 행 (42x24 트랙, 20dp knob) — 안전구역 사용/해제, 알림 설정
private fun toggleRow(
    titleText: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
): LinearLayout

// 4.6 칩 열 (안전구역 종류 선택 등 Spinner 대체)
private fun chipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
): LinearLayout

// 4.7 바텀시트 (radius 22 22 0 0 + 36x4 핸들). 확인/선택 다이얼로그 공통 컨테이너
private fun bottomSheet(
    titleText: String,
    message: String? = null,
    build: (LinearLayout) -> Unit
): Dialog

// 4.8 확인 시트 — AlertDialog를 대체하는 표준 확인 UI
private fun confirmSheet(
    titleText: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,    // true → danger 버튼, false → amber 강조 + primary 버튼
    onConfirm: () -> Unit
)

// 4.9 위험 버튼 / 하단 액션바
private fun dangerButton(label: String, onClick: () -> Unit): Button
private fun selectionActionBar(
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
): LinearLayout      // surface 배경 + 상단 1dp line, 편집=primary-soft/primary-dark, 삭제=DANGER_SOFT/DANGER

// 4.10 FAB (46dp 원형, primary, 우하단 16dp) — 안전구역/보호자 "추가"
private fun fab(glyph: String = "+", onClick: () -> Unit): TextView

// 4.11 상태 배너 / 빈 상태 / 에러 카드 (에러 문구 스타일 통일)
private fun noticeCard(
    titleText: String,
    body: String,
    tone: PillTone,                  // WARN / ALERT / NEUTRAL
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
): LinearLayout
private fun emptyState(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null): LinearLayout

// 4.12 섹션 라벨 + 구분선
private fun sectionLabel(value: String): TextView
private fun divider(): View          // 1dp LINE
```

### 4.13 `PillTone` 색 매핑

| tone | 배경 | 텍스트 | 사용 |
|---|---|---|---|
| `ON` | `PRIMARY_SOFT` | `PRIMARY_DARK` | 정상, 사용 중, 안전구역 안 (대비 9.9:1) |
| `OFF` | `SURFACE_ALT` | **`INK_SOFT`** | 꺼짐, 비활성. 시안의 `ink-faint`는 2.26:1로 판독 불가 → `INK_SOFT`(4.4:1)로 상향 |
| `WARN` | `AMBER_SOFT` | `INK` | 확인 필요, 안전구역 이탈 |
| `DONE` | `GOLD_SOFT` | `INK` | 등록 완료 |
| `ALERT` | `DANGER` | `#FFFFFF` | SOS만 |
| `NEUTRAL` | `SURFACE_ALT` | `INK_SOFT` | Device ID 등 정보 표시 |

---

## 5. Ambient Glow 구현 규격

시안의 `blur(30px)` 원형 그라데이션을 Android View 코드로 옮기는 방법.

1. **기본 구현(전 API 지원):** `GradientDrawable`에 `gradientType = RADIAL_GRADIENT`를 쓰고, 색 배열을 `[tint@38% alpha, tint@14% alpha, tint@0% alpha]`로 준다. radial 그라데이션 자체가 부드러워 별도 blur 없이 시안과 동일한 인상을 낸다.
   - `gradientRadius = 화면 폭 * 0.75`
   - `setGradientCenter(0.5f, 0.05f)` — 중심을 화면 상단 밖에 두어 위쪽만 물든 느낌을 만든다.
   - LayerDrawable로 `BG` 단색 위에 얹는다.
2. **API 31+ 보강(선택):** `view.setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.DECAL))`를 glow 전용 View에만 적용한다. 텍스트가 들어간 헤더 행에는 절대 적용하지 않는다(글자가 흐려짐).
3. **높이:** 92~108dp. 홈/등록 완료처럼 큰 타이틀이 있는 화면은 108dp, 폼 화면은 92dp.
4. **alpha 상한:** 최상단 alpha는 40%를 넘기지 않는다. 넘기면 헤더 텍스트 대비가 4.5:1 아래로 떨어진다. `DANGER`/`AMBER` glow는 30%로 더 낮춘다.
5. **애니메이션:** 상태가 바뀔 때(정상→이탈, 이탈→SOS) glow 색을 300ms `ValueAnimator`(ArgbEvaluator)로 전환한다. 등록 플로우의 새벽→골든아워도 동일. **SOS 진입만 예외로 애니메이션 없이 즉시 전환**한다(긴급 상황에서 0.3초라도 늦게 보이면 안 된다).

### 5.1 Glow tint 표

| `Glow` | tint | 최상단 alpha | 의미 |
|---|---|---|---|
| `DAWN` | `GLOW_DAWN #7FA6D6` | 38% | 등록 시작 — 새벽빛 |
| `MORNING` | `GLOW_MORNING #518399` | 36% | 등록 진행 중 |
| `GOLD` | `GOLD #E3A94C` | 40% | 완료 — 골든아워 |
| `TEAL` | `PRIMARY #24605C` | 26% | 평온한 둘러보기(관리 화면 기본) |
| `AMBER` | `AMBER #C9853B` | 30% | 확인 필요 |
| `DANGER` | `DANGER #C25B4A` | 30% | SOS |
| `NEUTRAL` | `INK_FAINT #A69E8F` | 20% | 서버 응답 없음/판단 불가 |

---

## 6. 등록 플로우 glow 진행 매핑

새벽빛에서 시작해 골든아워로 끝나는 하나의 여정으로 읽히게 한다. 사용자는 "색이 밝아지고 있으니 끝나가는구나"를 말 없이 이해한다.

| 순서 | 화면 (라인) | Glow | 근거 |
|---|---|---|---|
| 1 | `showPairing` (167) | `DAWN` | 여정의 시작. 아직 아무것도 등록되지 않음 |
| 2 | `showBlePickerDialog` (325) | `DAWN` (시트 상단 잔상, 높이 64dp) | 1단계의 연속. 바텀시트로 전환(7장) |
| 3 | `showNewGuardianRegistration` (190) | `DAWN` | pairing에서 분기되는 또 다른 시작 경로 |
| 4 | `showRegistrationForm` (440) | `MORNING` | 기기를 찾았고 정보를 채우는 중 — 날이 밝아옴 |
| 5 | `showRegistrationWaiting` (554) | `GOLD` @ 24% alpha | 해 뜨기 직전. 완료색이 옅게 비침 |
| 6 | `showComplete` (636) | `GOLD` @ 40% alpha | 골든아워. 완료 |
| 7 | `renderHome` (721) | 상태 기반(9장) | 여정 종료, 일상 모드 진입 |

전환 규칙: 4→5→6은 `ValueAnimator` 300ms로 이어지게 하여 같은 흐름임을 강조한다. 등록 타임아웃(`onRegistrationTimeout`, 596)이 발생하면 glow를 `AMBER`로 되돌리고 재시도 안내를 띄운다 — "실패"가 아니라 "확인이 필요함"이므로 danger를 쓰지 않는다.

---

## 7. 화면별 적용 지침

| 화면 (라인) | Glow | 주 컴포넌트 | 바뀌는 점 |
|---|---|---|---|
| `showPairing` (167) | `DAWN` | `ambientHeader`, 로고, `card`, `primaryButton` x2, `ghostButton` | 배경 그라데이션 → `BG`. 로고 elevation 제거, `rounded(SURFACE,24)`+`LINE` 테두리. 버튼 그라데이션 → `PRIMARY` 단색. 안내문 "아직 Device 등록이 안되어있습니다."를 "아직 등록된 기기가 없어요.\n아래 순서대로 진행해 주세요."로. 버튼 라벨 "BLE 탐색"→"기기 찾기", "기기 등록"→"찾은 기기 등록하기"(BLE 용어 비노출) |
| `showBlePickerDialog` (325) | `DAWN`(64dp) | `bottomSheet`, `listItem` | `AlertDialog`+`Button` 나열 → 바텀시트 + `listItem`(기기 이름 18sp / 신호 세기는 dBm 대신 막대 3단계 + "가까움/보통/멂"). 빈 상태는 `emptyState("주변에서 기기를 찾고 있어요. 기기가 켜져 있는지 확인해 주세요.")` |
| `showNewGuardianRegistration` (190) | `DAWN` | `header`, `card`, `input`, `primaryButton` | "Device ID"→"기기 번호", "Send"→"조회", "Elder name: 조회 전"→"이름 확인 전". 조회 결과는 `statusPill`(성공=ON / 실패=WARN)로 표시 |
| `showRegistrationForm` (440) | `MORNING` | `card`, `input`(비활성 포함), `primaryButton` | 라벨 한글화: "WiFi: AP Name"→"연결된 WiFi", "AP 지정 이름"→"이 장소 이름(예: 집)", "Password"→"WiFi 비밀번호". 비활성 필드는 `SURFACE_ALT` 배경 + 아래 설명 한 줄 |
| `showRegistrationWaiting` (554) | `GOLD` 24% | `ProgressBar`(tint `PRIMARY`), 30sp 제목 | 스피너 색 `PRIMARY`. 하단에 `noticeCard(tone=NEUTRAL)`로 "1분 이상 걸리면 기기 전원을 확인해 주세요" 상시 노출(현재는 40초 타임아웃까지 아무 안내 없음) |
| `showComplete` (636) | `GOLD` 40% | 완료 원, `primaryButton` | 체크 원 `0xFF77C98A`→`GOLD` 채움 + `INK` 체크(흰 체크는 gold 위 2.1:1로 부적합). 제목 34sp 유지 |
| `renderHome` (721) | **상태 기반**(9장) | 기존 구성 100% 유지 | **레이아웃 변경 금지.** 배경 `BG`, 상태 카드 `card()` 신규 스펙, 큰 아이콘 원 `oval(SURFACE)`+`LINE` 테두리(elevation 0), 구역명·상태 텍스트 색은 9장 규칙, `tile` 2개는 3.7, 배터리바는 3.9. `lastSyncLabel` 13sp→14sp `INK_SOFT` |
| `showSafeZoneForm` (775) | `TEAL` | `card`, 로딩 문구 | 로딩 문구를 `emptyState`로 통일 |
| `showSafeZoneList` (804) | `TEAL` | `listItem`, `statusPill`, `selectionActionBar`, `fab` | 6열 표 → 카드 리스트(3.11). 하단 "추가/수정/삭제" 3버튼 → 미선택 시 `fab`("+"), 선택 시 `selectionActionBar` 슬라이드 업. "Elder ID: xxx" 캡션은 14sp `INK_SOFT` + 라벨 "등록 번호" |
| `showSafeZoneAddForm` (857) | `TEAL` | `chipRow`, `input`, `toggleRow`, `primaryButton` | `Spinner`(WiFi/BLE/GPS) → `chipRow(["WiFi","블루투스","위치(GPS)"])`. `CheckBox("Enable")` → `toggleRow("이 안전구역 사용", checked)`. 라벨 한글화 |
| `showMyPhoneAddForm` (969) | `TEAL` | 위와 동일 + `noticeCard(NEUTRAL)` | "bssid(자동 지정됨)" 설명을 `noticeCard`로 옮기고 필드 라벨은 "기기 식별값(자동)". 제목 22sp→24sp |
| `showGuardianList` (1310) | `TEAL` | `listItem`, `selectionActionBar`, `fab` | 5열 표 → 카드 리스트(이름 18sp / 전화 16sp / 등록일 14sp `INK_SOFT`). ID 열은 숨김 |
| `showGuardianEditForm` (1360) | `TEAL` | `card`, `input`, `primaryButton` | 색 교체만 |
| `showLogs` (1543) | `TEAL`, 단 `calendar.emergency > 0`이면 `AMBER` | `calendarCard`, `summaryTile` x3, `card` | 달력 `‹`/`›` 버튼 색 통일(`SURFACE_ALT` 배경 + `INK` 기호, 눌림 `PRIMARY_SOFT`). 오늘 배경 `PRIMARY_SOFT`. 날짜 마커 N/W/E → `PRIMARY`/`AMBER`/`DANGER`, 22dp 12sp. 하단 원문 로그 카드는 `card(tone=NEUTRAL)` + 14sp, 기본 접힘("자세히 보기"로 펼침) — 보호자에게 raw 로그를 첫 화면에 보이지 않는다 |
| `showMap` (1810) | `TEAL` | `header`, legend, WebView | root 배경 그라데이션 → `BG`. legend 14sp, 색은 1.4절 매핑. WebView 배경 `SURFACE_ALT`, 폴리라인 `#24605C`. 지도 상단에 현재 상태 `statusPill` 1개 고정 |
| `showDeviceInfo` (2076) | — | `bottomSheet` | `AlertDialog` → 바텀시트. "Device ID / 앱 버전 / FW 버전"을 `listItem` 3줄(라벨 `INK_SOFT` 14sp / 값 17sp `INK`)로. "FW 버전"→"기기 소프트웨어 버전" |
| `showMenu` (2031) | — | `bottomSheet` + `listItem` | `PopupMenu` → 바텀시트 메뉴(터치 타겟 56dp). "디바이스 초기화" 항목만 `INK` 텍스트 + 우측 `statusPill(WARN)`. "셋팅"은 미구현이므로 목록에서 숨기거나 `OFF` pill("준비 중") 표시 |
| `confirmResetDevice` (2051) / `confirmDeleteSafeZone` (1153) / `confirmDeleteGuardian` (1452) | — | `confirmSheet(destructive=true)` | 바텀시트(radius 22, 36x4 핸들) + `dangerButton`. 실제 삭제이므로 danger 허용 |
| `confirmUpdateSafeZoneEnabled` (1092) | — | `confirmSheet(destructive=false)` | 삭제가 아니므로 **amber**. 문구도 `"집 Enabled 값을 false 로 변경할까요?"` → `"'집' 안전구역을 끌까요? 끄면 이 장소에 있어도 알림을 받지 못해요."` |
| `showSafeZoneError` (1268) / `showGuardianError` (1486) | `AMBER` | `noticeCard(WARN)` + `primaryButton("다시 시도")` | 제목색 `0xFF991B1B` → `INK` + amber 아이콘 배지. 서버 원문 메시지는 접힘 처리하고, 보이는 문구는 "인터넷 연결을 확인한 뒤 다시 시도해 주세요"로. 원문은 "자세히" 안에 14sp `INK_SOFT` |
| `beginProvisionFlow` 실패 (413/430) | — | `confirmSheet(destructive=false)` | "ID 발급 실패" → "기기 번호를 받지 못했어요". 다음 행동("잠시 후 다시 시도" / "기기 다시 찾기")을 버튼으로 제시 |

---

## 8. 상태 색 규칙 (전 화면 공통)

| 상태 | 판정 기준(기존 코드) | 색 | pill | glow |
|---|---|---|---|---|
| 정상 / 안전구역 안 | `inSafeZone == true`, `deviceStatus`가 `""`/`NORMAL` | `PRIMARY` | `ON` "안전구역 안" | `TEAL` |
| 주의 / 이탈 | `eventType == "GEOFENCE_EXIT_HINT"`, `deviceStatus == "WARNING"`, `inSafeZone == false`, `locationType == "GPS"` | `AMBER` | `WARN` "확인 필요" | `AMBER` |
| 긴급 | `eventType == "SOS"`, `deviceStatus == "EMERGENCY"` | `DANGER` | `ALERT` "긴급" | `DANGER` |
| 완료 | 등록 완료, 저장 성공 | `GOLD` | `DONE` "완료" | `GOLD` |
| 배터리 부족 | `battery <= 20` | `AMBER` | `WARN` "충전 필요" | 변경 없음 |
| 배터리 알 수 없음 | `battery == null` | `INK_FAINT` | `OFF` "확인 안 됨" | 변경 없음 |
| 서버 오류 / 로그 없음 | `error != null`, `log == null` | `INK_FAINT` | `OFF` "확인 중" | `NEUTRAL` |
| GPS 신호 약함 | `deviceStatus == "GPS_WEAK"` | `AMBER` | `WARN` "위치 확인 중" | `AMBER` |

`homePrimaryColor()`(1724)는 위 표대로 반환값만 교체한다. **주의: 현재 `error != null`이 빨강(`0xFFEF4444`)이다. 서버 통신 실패는 대상자의 위험이 아니라 앱의 문제이므로 `INK_FAINT`로 낮춘다.** 통신 실패를 빨강으로 보여주면 보호자가 SOS와 혼동한다 — 이번 개편에서 가장 중요한 안전 관련 수정이다.

`homeStatusLabel()`(1695)의 `"안전구역 · 정상"` 형태는 유지하되, 오류 시 문구를 `"서버 상태를 불러오지 못했습니다."` → `"최신 상태를 불러오지 못했어요. 마지막 확인: (시각)"`으로 바꿔 **마지막으로 알려진 상태가 언제 것인지**를 항상 함께 보여준다.

### 8.1 danger 사용 범위 (엄격)

`DANGER`를 쓸 수 있는 곳은 다음 4가지뿐이다.
1. SOS 상태(홈 glow / 구역명 / 상태 pill / 지도 마커)
2. 삭제 확인 시트의 확정 버튼
3. `selectionActionBar`의 삭제 영역(`DANGER_SOFT` 배경 + `DANGER` 텍스트)
4. 디바이스 초기화 확정 버튼

그 외 모든 실패·경고·검증 오류는 `AMBER`를 쓴다(입력 누락, 서버 오류, 등록 지연, 스캔 실패, 권한 없음 포함).

---

## 9. 접근성 기준

주 사용자는 보호자이며, 고령 사용자가 포함된다. 아래는 필수 기준이다.

### 9.1 대비 (실측값, `--bg #FAF6F0` 기준)

| 조합 | 대비 | 판정 |
|---|---|---|
| `INK` on `BG` | 13.3:1 | 모든 크기 사용 가능 |
| `INK_SOFT` on `BG` | 4.8:1 | 본문 사용 가능(14sp 이상) |
| `INK_FAINT` on `BG` | **2.5:1** | **텍스트 사용 금지.** 아이콘/구분/placeholder 전용 |
| `PRIMARY` on `BG` | 6.7:1 | 사용 가능 |
| `#FFFFFF` on `PRIMARY` | 7.2:1 | 주 버튼 라벨 OK |
| `PRIMARY_DARK` on `PRIMARY_SOFT` | 9.9:1 | 정상 pill 최적 |
| `AMBER` on `BG` | **2.8:1** | **텍스트 금지.** 배경/테두리/아이콘 전용 |
| `GOLD` on `BG` | **1.9:1** | **텍스트 금지.** 도형 전용 |
| `INK` on `GOLD` | 6.8:1 | 골드 배지 위 텍스트는 반드시 `INK` |
| `INK` on `AMBER_SOFT` | 11.4:1 | 주의 pill 최적 |
| `DANGER` on `BG` | 4.0:1 | 18sp Bold 이상에서만 텍스트 허용 |
| `#FFFFFF` on `DANGER` | 4.3:1 | 버튼 라벨(18sp Bold 이상)만 |
| `INK_FAINT` on `SURFACE_ALT` | **2.3:1** | **시안의 off pill 조합. 사용 금지 → `INK_SOFT`(4.4:1)로 대체** |

### 9.2 크기 / 타겟

- 본문 최소 **16sp**, 캡션 최소 **14sp**, pill 최소 **12sp Bold**. 12sp 미만 텍스트는 앱 전체에서 금지(현재 9~13sp 사용처는 3.2절대로 상향).
- 터치 타겟 최소 **48x48dp**. 현재 헤더 아이콘 버튼(48dp)은 충족, 표의 `CheckBox`(기본 ~32dp)와 달력 셀(58dp 높이지만 폭이 화면/7≈50dp)은 아이템 전체를 탭 영역으로 만들어 해결한다.
- 버튼 최소 높이 **56dp**(주 액션), **52dp**(보조). 리스트 아이템 최소 **64dp**.
- 인접 터치 타겟 간 간격 최소 **8dp**.
- FAB 46dp는 시안 규격이지만 **터치 영역은 56dp**로 확장한다(투명 패딩).

### 9.3 그 외

- 색만으로 상태를 전달하지 않는다. 상태는 항상 **색 + 텍스트 라벨(+아이콘)** 3중으로 전달한다. 지도 마커도 팝업에 상태 문자열을 넣는다(현재 `mapStatusLabel`이 이미 함).
- 시스템 글꼴 확대(200%)에서 레이아웃이 깨지지 않도록, 고정 높이 대신 `wrap_content` + `minHeight`를 쓴다. `rowParams(height=...)`를 `minHeight`로 전환한다.
- 모든 `ImageButton`/`ImageView`에 `contentDescription`을 지정한다(현재 전부 누락).
- 애니메이션은 300ms 이하, SOS 전환은 즉시. 깜빡임(blink) 금지.
- 다크 모드는 이번 범위 밖. `Theme` 강제 라이트 유지.

---

## 10. 적용 순서 (마이그레이션)

1. `SfcTheme.kt`에 `SfcColors` / `Glow` / `PillTone` / shape 상수 추가. **코드 동작 변화 없음.**
2. 1.4 치환표대로 하드코딩 색 일괄 교체 + `page()`/`card()`/`primaryButton()`/`input()`/`ghostButton()` 5개 헬퍼 수정. 이 단계만으로 전 화면이 새 팔레트로 바뀐다.
3. `header(glow=)` + `ambientGlowDrawable` 추가, 화면별 glow 지정(7장 표).
4. `statusPill` / `noticeCard` / `emptyState` 추가 후 상태·에러 표현 통일(8장). 문구 한글화 동반.
5. `listItem` / `selectionActionBar` / `fab` 도입으로 안전구역·보호자 표를 카드 리스트로 전환.
6. `bottomSheet` / `confirmSheet`로 `AlertDialog`·`PopupMenu` 교체.
7. 접근성 감사: 글자 크기 하한, `contentDescription`, 200% 글꼴 확대 점검.

각 단계는 독립적으로 배포 가능하며, 2단계까지만 적용해도 시각적 통일은 달성된다.

## 11. 경계 / 위임

- **금지:** `BleProvisioningManager`, `SfcApiClient`, `SfcBleMonitorService`, `PhoneDefaultsReader`, `PhoneHotspotReader` 수정. 이 문서의 모든 변경은 `MainActivity.kt`의 뷰 조립 코드와 신규 `SfcTheme.kt`에 한정된다.
- **`sfc-owner`에게 위임 필요:**
  - BLE 스캔 신호 세기를 "가까움/보통/멂" 3단계로 보여주려면 RSSI→단계 변환값이 필요하다(뷰에서 계산해도 되지만 임계값은 owner가 정해야 한다).
  - 등록 대기 화면에서 "설정 전송됨 / 기기 응답 대기 / 서버 확인 중" 3단계 진행을 보여주려면 `BleProvisioningManager`의 상태 콜백 세분화가 필요하다. 현재는 `onStatus(String)` 문자열뿐이라 UI가 단계를 알 수 없다.
  - 서버 오류를 "인터넷 연결 문제 / 서버 문제 / 기기 미등록"으로 나눠 다른 안내를 주려면 `SfcApiClient`가 오류 유형을 구분해 노출해야 한다.
- **`project-manager`와 공유할 전사 결정:**
  - 색 토큰 및 상태 색 규칙(8장)을 SFW/safeFinder/MAG 공통 기준으로 승격할지 여부.
  - 용어집: BSSID/SSID/telemetry/provisioning/GATT 등 기술 용어의 사용자 화면 표기 통일안(3.11 참고).
  - 에러 메시지 스타일 가이드: "무엇이 일어났는가 + 지금 무엇을 하면 되는가" 2문장 원칙, 실패는 amber·삭제/긴급만 danger.
