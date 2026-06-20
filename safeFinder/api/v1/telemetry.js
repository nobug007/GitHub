import { nowKst, syncResponse } from "./config-store.js";

const seenSeq = globalThis.safeFinderTelemetrySeenSeq || new Set();
globalThis.safeFinderTelemetrySeenSeq = seenSeq;
const recentPayloads = globalThis.safeFinderTelemetryRecentPayloads || [];
globalThis.safeFinderTelemetryRecentPayloads = recentPayloads;

function parsePayload(body) {
  return typeof body === "string" ? JSON.parse(body) : body;
}

function validateReading(reading) {
  if (!Number.isFinite(Number(reading?.seq))) return "INVALID_SEQ";
  if (!reading.timestamp || Number.isNaN(Date.parse(reading.timestamp))) return "INVALID_TIMESTAMP";
  if (reading.locationType !== "WIFI" && reading.locationType !== "GPS") return "INVALID_LOCATION_TYPE";
  if (reading.locationType === "WIFI" && !reading.apName) return "MISSING_LOCATION";
  if (reading.locationType === "GPS" && (!Number.isFinite(Number(reading.lat)) || !Number.isFinite(Number(reading.lng)))) {
    return "MISSING_LOCATION";
  }
  return null;
}

export default function handler(req, res) {
  if (req.method === "GET") {
    res.status(200).json({
      success: true,
      data: {
        serverTime: nowKst(),
        latest: recentPayloads[0] || null,
        items: recentPayloads
      }
    });
    return;
  }

  if (req.method !== "POST") {
    res.status(405).json({ success: false, error: "Use GET or POST /api/v1/telemetry" });
    return;
  }

  try {
    const payload = parsePayload(req.body);
    const readings = Array.isArray(payload?.readings) ? payload.readings : [];
    let accepted = 0;
    let duplicated = 0;
    const rejected = [];

    for (const reading of readings) {
      const reason = validateReading(reading);
      if (reason) {
        rejected.push({ seq: reading?.seq ?? null, reason });
        continue;
      }

      const seqKey = `${payload.deviceId}:${Number(reading.seq)}`;
      if (seenSeq.has(seqKey)) {
        duplicated += 1;
      } else {
        seenSeq.add(seqKey);
        accepted += 1;
      }
    }

    recentPayloads.unshift({
      receivedAt: nowKst(),
      accepted,
      duplicated,
      rejected,
      payload
    });
    recentPayloads.splice(20);

    res.status(200).json(syncResponse());
  } catch (error) {
    res.status(400).json({ success: false, error: { code: "INVALID_JSON", message: error.message } });
  }
}
