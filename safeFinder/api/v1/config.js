import {
  deleteFamily,
  deleteSafeZone,
  getConfig,
  syncResponse,
  upsertFamily,
  upsertSafeZone
} from "./config-store.js";

function parseBody(body) {
  return typeof body === "string" ? JSON.parse(body) : body || {};
}

export default function handler(req, res) {
  try {
    if (req.method === "GET") {
      res.status(200).json({ success: true, data: getConfig() });
      return;
    }

    if (req.method !== "POST") {
      res.status(405).json({ success: false, error: "Use GET or POST /api/v1/config" });
      return;
    }

    const body = parseBody(req.body);
    if (body.kind === "safeZone") {
      if (body.operation === "DELETE") {
        deleteSafeZone(body.safeZoneId);
      } else {
        upsertSafeZone(body);
      }
    } else if (body.kind === "family") {
      if (body.operation === "DELETE") {
        deleteFamily(body.familyId);
      } else {
        upsertFamily(body);
      }
    } else {
      res.status(400).json({ success: false, error: "kind must be safeZone or family" });
      return;
    }

    res.status(200).json(syncResponse());
  } catch (error) {
    res.status(400).json({ success: false, error: error.message });
  }
}
