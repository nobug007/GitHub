// BLE GATT provisioning peripheral — analogue of BlePeripheralManager.kt.
// Same SERVICE/characteristic UUIDs and the same BEGIN:<n> / chunks / END chunk protocol,
// so SFC provisions SFA exactly like SFD/SFW. HTTP registration is NOT done here (BLE stack
// task); a completed config is handed to the main loop which saves + registers.
#pragma once
#include <Arduino.h>
#include "sfa_store.h"

void sfaBleBegin(SfaStore* store);
void sfaBleSetAdvertising(bool on);
bool sfaBleIsAdvertising();
bool sfaBleTakePendingConfig(String& out);   // true (and clears) when a full config arrived
void sfaBleSetRegistrationState(const String& state, int serverCode, const String& body);
bool sfaBleCentralConnected();
