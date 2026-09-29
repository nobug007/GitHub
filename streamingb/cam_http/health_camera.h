#pragma once
#include <Arduino.h>
#include <esp_camera.h>

// 카메라 센서를 health_config.h 의 설정으로 초기화한다. 실패하면 false.
bool healthCameraInit();
