/**
 * Vercel Serverless Function — /api/weather
 * CommonJS 형식 (Vercel Node.js 런타임 기본값)
 *
 * Query params:
 *   ?city=Seoul
 *   ?lat=37.5665&lon=126.9780
 */
module.exports = async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const apiKey = process.env.OPENWEATHER_API_KEY;

  if (!apiKey) {
    console.error('[weather] OPENWEATHER_API_KEY 환경변수 없음');
    return res.status(500).json({
      error: 'OPENWEATHER_API_KEY 환경변수가 설정되어 있지 않습니다.',
    });
  }

  const { city, lat, lon } = req.query;

  let owmUrl;
  if (city) {
    owmUrl = `https://api.openweathermap.org/data/2.5/weather?q=${encodeURIComponent(city)}&appid=${apiKey}&units=metric&lang=kr`;
  } else if (lat && lon) {
    owmUrl = `https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&appid=${apiKey}&units=metric&lang=kr`;
  } else {
    return res.status(400).json({ error: 'city 또는 lat+lon 파라미터가 필요합니다.' });
  }

  try {
    console.log('[weather] 요청:', owmUrl.replace(apiKey, '***'));
    const owmRes = await fetch(owmUrl);
    const data   = await owmRes.json();

    if (!owmRes.ok) {
      console.error('[weather] OWM 에러:', owmRes.status, data);
      return res.status(owmRes.status).json(data);
    }

    res.setHeader('Cache-Control', 's-maxage=30, stale-while-revalidate');
    return res.status(200).json(data);

  } catch (err) {
    console.error('[weather] fetch 실패:', err.message);
    return res.status(502).json({ error: `외부 API 호출 실패: ${err.message}` });
  }
};
