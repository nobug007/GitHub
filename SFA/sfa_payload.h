// Register + telemetry JSON builders — byte-for-byte the same shapes SFD sends.
#pragma once
#include <Arduino.h>
#include <vector>
#include "sfa_types.h"
#include "sfa_store.h"

String sfaBuildRegisterPayload(const String& configJson);

String sfaBuildTelemetryPayload(SfaStore& store,
                                const std::vector<GyroSample>& samples,
                                const LocationSnapshot& loc,
                                bool inSafeZone,
                                int battery,
                                const String& eventType,
                                const String& verb,
                                uint64_t durationMs,
                                const String& deviceStatus);
