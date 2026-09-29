// Headless-device sensors: UART NMEA GPS, optional IMU movement, battery.
#pragma once
#include <Arduino.h>
#include "sfa_types.h"

void      sfaGpsBegin();
void      sfaGpsPoll();          // call often from loop() to feed the NMEA parser
GpsStatus sfaGpsRead();          // latest fix; hasFix=false when stale/unavailable
bool      sfaGpsHardwarePresent();  // any NMEA bytes seen since boot

void      sfaImuBegin();
double    sfaImuMovementMagnitude();  // 0 when no IMU (=> gyroMovementVerb "stayed")

void      sfaBatteryBegin();
int       sfaBatteryLevel();     // 0..100
