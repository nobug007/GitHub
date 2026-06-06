import http from "node:http";

const PORT = Number(process.env.PORT || 8080);
const seenSeq = new Set();

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        reject(new Error("Payload too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function nowKst() {
  const date = new Date();
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace("Z", "+09:00");
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  res.end(body);
}

function normalizeReadings(payload) {
  if (!payload || !Array.isArray(payload.readings)) return [];
  return payload.readings.filter(reading => Number.isFinite(Number(reading.seq)));
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    sendJson(res, 200, { ok: true, serverTime: nowKst() });
    return;
  }

  if (req.method !== "POST" || req.url !== "/readings") {
    sendJson(res, 404, { success: false, error: "Use POST /readings" });
    return;
  }

  try {
    const payload = JSON.parse(await readBody(req));
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
      deviceId: payload.deviceId,
      fwVersion: payload.fwVersion,
      sentAt: payload.sentAt,
      accepted,
      duplicated
    }));

    sendJson(res, 200, {
      success: true,
      data: {
        accepted,
        duplicated,
        serverTime: nowKst()
      }
    });
  } catch (error) {
    sendJson(res, 400, { success: false, error: error.message });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`SafeFinder server listening on http://0.0.0.0:${PORT}`);
});
