package com.sf.streamingesp

/**
 * ESP32-CAM 펌웨어(C:\GitHub\Health)와 맞춰야 하는 값들.
 * 기준 문서는 `Health/health_config.h` 상단의 프로토콜 블록이다. 한쪽만 고치면 안 된다.
 *
 *   Wi-Fi   SSID "ESP32CAM-RIG" / PSK "rig12345" / 게이트웨이 192.168.4.1
 *   GET :81 /stream   multipart/x-mixed-replace, 파트마다 Content-Length + JPEG
 *   GET :81 /capture  JPEG 한 장 (디버깅용)
 */
object StreamConfig {

    /** 접속해야 할 카메라 AP. 앱은 사용자가 이 Wi-Fi 에 붙어 있다고 가정한다. */
    const val AP_SSID = "ESP32CAM-RIG"

    /** SoftAP 게이트웨이. Health 펌웨어의 기본 SoftAP 주소다. */
    const val CAMERA_HOST = "192.168.4.1"

    /** MJPEG 스트림. 확인 페이지(80)와 포트를 나눈 이유는 펌웨어 쪽 주석 참고. */
    const val STREAM_URL = "http://$CAMERA_HOST:81/stream"

    /** 정지 프레임 한 장. 스트림이 의심스러울 때 확인용. */
    const val CAPTURE_URL = "http://$CAMERA_HOST:81/capture"

    /** 스트림이 끊겼을 때 재접속 간격(ms). */
    const val RECONNECT_DELAY_MS = 1500L

    /** 소켓 타임아웃(ms). MJPEG 은 응답이 끝나지 않으므로 read 타임아웃만 의미가 있다. */
    const val CONNECT_TIMEOUT_MS = 5000
    const val READ_TIMEOUT_MS = 8000
}
