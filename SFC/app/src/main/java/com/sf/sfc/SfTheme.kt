package com.sf.sfc

/**
 * Safe Finder 팔레트.
 *
 * 등록/관리 화면 시안(Guardian Teal · Sunrise Gold)에서 그대로 가져온 값이다. 앱의 모든 화면은
 * 여기서만 색을 가져오고, 화면 코드에는 원시 hex 리터럴을 두지 않는다.
 */
object SfTheme {
    // 배경 / 표면
    val BG = 0xFFFAF6F0.toInt()            // warm paper
    val SURFACE = 0xFFFFFFFF.toInt()
    val SURFACE_ALT = 0xFFF2ECE2.toInt()
    val LINE = 0xFFE7DFD2.toInt()

    // 글자
    val INK = 0xFF2E2A24.toInt()
    val INK_SOFT = 0xFF746C5F.toInt()
    val INK_FAINT = 0xFFA69E8F.toInt()

    // 브랜드
    val PRIMARY = 0xFF24605C.toInt()       // Guardian Teal — 정상
    val PRIMARY_SOFT = 0xFFE3EEEC.toInt()
    val PRIMARY_DARK = 0xFF163E3B.toInt()

    // 상태
    val GOLD = 0xFFE3A94C.toInt()          // Sunrise Gold — 완료 / 이동중
    val GOLD_SOFT = 0xFFFBF0DC.toInt()
    val AMBER = 0xFFC9853B.toInt()         // 주의 (WARNING)
    val AMBER_SOFT = 0xFFF5E4CC.toInt()
    val AMBER_INK = 0xFF8A5C25.toInt()
    val DANGER = 0xFFC25B4A.toInt()        // SOS / 삭제
    val DANGER_SOFT = 0xFFF5E6E1.toInt()

    // 앰비언트 글로우. 등록 플로우는 새벽빛에서 골든아워로 진행하고, 홈은 상태색을 따른다.
    val GLOW_DAWN = 0xFF7FA6D6.toInt()     // 등록 시작 — 차분한 새벽빛
    val GLOW_MORNING = 0xFF8FBFB4.toInt()  // 정보 입력 — 틸로 넘어가는 중
    val GLOW_DONE = GOLD                   // 등록 완료 — 골든아워
    val GLOW_CALM = 0xFF8FBFB4.toInt()     // 평온한 둘러보기 / 정상
    val GLOW_WARNING = AMBER
    val GLOW_SOS = DANGER

    /** 지도(Leaflet)는 HTML 안에서 문자열 색을 쓴다. 위 값과 같은 색이어야 한다. */
    const val MAP_SAFE = "#24605C"
    const val MAP_MOVING = "#E3A94C"
    const val MAP_WARNING = "#C9853B"
    const val MAP_SOS = "#C25B4A"
    const val MAP_UNKNOWN = "#A69E8F"
}
