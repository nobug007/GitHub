// Shared value types — direct analogues of SFD's Models.kt data classes.
#pragma once
#include <Arduino.h>

struct GyroSample {
  uint32_t timestampMs = 0;
  double gyX = 0, gyY = 0, gyZ = 0;
};

struct WifiStatus {
  String apName;
  String bssid;
  int  signal = -127;
  bool isAttached = false;         // connected to a registered safe-zone AP
  bool hasWifiConnection = false;  // any managed Wi-Fi link (incl. hotspot)
  bool wifiEnabled = false;
};

struct GpsStatus {
  bool hasFix = false;
  double latitude = 0, longitude = 0;
  float accuracy = 0;
  uint32_t timestampMs = 0;
};

struct LocationSnapshot {
  String locationType = "GPS";     // "WIFI" | "BLE" | "GPS"
  String apName;
  String bssid;
  String bluetoothName;
  String bluetoothAddress;
  bool   hasCoords = false;
  double latitude = 0, longitude = 0;
  float  accuracy = 0;
  int    signal = -127;
};
