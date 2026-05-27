/**
 * Vercel Serverless Function — /api/weather
 * Node.js 내장 https 모듈 사용 (fetch 불필요, 모든 버전 호환)
 */
const https = require('https');

function httpsGet(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (res) => {
      let body = '';
      res.on('data', chunk => (body += chunk));
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, data: JSON.parse(body) });
        } catch (e) {
          reject(new Error('JSON 파싱 실패: ' + body.slice(0, 200)));
        }
      });
    }).on('error', reject);
  });
}

module.exports = async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');

  if (req.method === 'OPTIONS') return res.status(200).end();

  const apiKey = process.env.OPENWEATHER_API_KEY;
  if (!apiKey) {
    return res.status(500).json({ error: 'OPENWEATHER_API_KEY 환경변수가 설정되어 있지 않습니다.' });
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
    const { status, data } = await httpsGet(owmUrl);
    res.setHeader('Cache-Control', 's-maxage=30, stale-while-revalidate');
    return res.status(status).json(data);
  } catch (err) {
    return res.status(502).json({ error: err.message });
  }
};
