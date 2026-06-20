import {
  deleteFamily,
  deleteSafeZone,
  getConfig,
  nowKst,
  syncResponse,
  upsertFamily,
  upsertSafeZone
} from "./config-store.js";

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

function isConfigRequest(req) {
  if (req.query?.config === "1") return true;
  const url = new URL(req.url || "/api/v1/telemetry", "http://localhost");
  return url.searchParams.get("config") === "1";
}

function applyConfigUpdate(body) {
  if (body.kind === "registration") {
    const familyId = body.familyId || `FM-${Date.now()}`;
    upsertFamily({
      familyId,
      name: body.familyName,
      relation: body.relation,
      phoneNo: body.phoneNo,
      priority: body.priority,
      notificationEnabled: body.notificationEnabled
    });
    upsertSafeZone({
      safeZoneId: body.safeZoneId,
      type: body.type,
      name: body.safeZoneName,
      familyId,
      latitude: body.latitude,
      longitude: body.longitude,
      radius: body.radius
    });
    return true;
  }

  if (body.kind === "safeZone") {
    if (body.operation === "DELETE") {
      deleteSafeZone(body.safeZoneId);
    } else {
      upsertSafeZone(body);
    }
    return true;
  }

  if (body.kind === "family") {
    if (body.operation === "DELETE") {
      deleteFamily(body.familyId);
    } else {
      upsertFamily(body);
    }
    return true;
  }

  return false;
}

export default function handler(req, res) {
  if (req.method === "GET") {
    if (isConfigRequest(req)) {
      res.status(200).json({ success: true, data: getConfig() });
      return;
    }

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
    if (applyConfigUpdate(payload)) {
      res.status(200).json(syncResponse());
      return;
    }

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
