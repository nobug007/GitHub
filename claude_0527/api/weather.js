/**
 * Vercel Serverless Function — /api/weather
 *
 * 환경변수 OPENWEATHER_API_KEY 를 서버에서 읽어
 * OpenWeatherMap API를 프록시합니다.
 *
 * Query params (둘 중 하나):
 *   ?city=Seoul
 *   ?lat=37.5665&lon=126.9780
 */
export default async function handler(req, res) {
  // CORS — 같은 Vercel 프로젝트에서만 허용 (필요 시 조정)
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET');

  const apiKey = process.env.OPENWEATHER_API_KEY;

  if (!apiKey) {
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
    const owmRes = await fetch(owmUrl);
    const data   = await owmRes.json();

    // OpenWeatherMap 에러를 그대로 전달
    if (!owmRes.ok) {
      return res.status(owmRes.status).json(data);
    }

    // 캐시 30초 (선택 사항)
    res.setHeader('Cache-Control', 's-maxage=30, stale-while-revalidate');
    return res.status(200).json(data);

  } catch (err) {
    return res.status(502).json({ error: '외부 API 호출에 실패했습니다.' });
  }
}
