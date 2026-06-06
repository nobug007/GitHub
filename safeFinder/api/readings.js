const seenSeq = globalThis.safeFinderSeenSeq || new Set();
globalThis.safeFinderSeenSeq = seenSeq;
const recentPayloads = globalThis.safeFinderRecentPayloads || [];
globalThis.safeFinderRecentPayloads = recentPayloads;

function nowKst() {
  const date = new Date();
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace("Z", "+09:00");
}

function normalizeReadings(payload) {
  if (!payload || !Array.isArray(payload.readings)) return [];
  return payload.readings.filter(reading => Number.isFinite(Number(reading.seq)));
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
    res.status(405).json({ success: false, error: "Use GET or POST /api/readings" });
    return;
  }

  try {
    const payload = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
    const readings = normalizeReadings(payload);
    let accepted = 0;
    let duplicated = 0;

    for (const reading of readings) {
      const seq = Number(reading.seq);
      if (seenSeq.has(seq)) {
        duplicated += 1;
      } else {
        seenSeq.add(seq);
        accepted += 1;
      }
    }

    console.log(JSON.stringify({
      deviceId: payload?.deviceId,
      fwVersion: payload?.fwVersion,
      sentAt: payload?.sentAt,
      accepted,
      duplicated
    }));

    recentPayloads.unshift({
      receivedAt: nowKst(),
      accepted,
      duplicated,
      payload
    });
    recentPayloads.splice(20);

    res.status(200).json({
      success: true,
      data: {
        accepted,
        duplicated,
        serverTime: nowKst()
      }
    });
  } catch (error) {
    res.status(400).json({ success: false, error: error.message });
  }
}
