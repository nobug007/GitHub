function nowKst() {
  const date = new Date();
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace("Z", "+09:00");
}

export default function handler(req, res) {
  if (req.method !== "GET") {
    res.status(405).json({ success: false, error: "Use GET /api/health" });
    return;
  }

  res.status(200).json({
    ok: true,
    serverTime: nowKst()
  });
}
