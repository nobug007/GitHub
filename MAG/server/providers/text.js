const providerSettings = {
  OpenAI: { key: "OPENAI_API_KEY", model: "OPENAI_TEXT_MODEL", defaultModel: "gpt-5.2", run: runOpenAI },
  Google: { key: "GEMINI_API_KEY", model: "GEMINI_TEXT_MODEL", defaultModel: "gemini-2.5-flash", run: runGemini },
  Anthropic: { key: "ANTHROPIC_API_KEY", model: "ANTHROPIC_TEXT_MODEL", defaultModel: "claude-sonnet-4-5", run: runAnthropic },
  OpenRouter: { key: ["OPENROUTER_API_KEY", "OpenRouter_API_KEY"], model: "OPENROUTER_TEXT_MODEL", defaultModel: "openrouter/auto", run: runOpenRouter },
  Groq: { key: "GROQ_API_KEY", model: "GROQ_TEXT_MODEL", defaultModel: "llama-3.3-70b-versatile", run: runGroq }
};
const recentEvents = {};

export async function generateText({ provider, model: requestedModel, prompt, fallback, useLiveAI = false }) {
  const mode = useLiveAI ? process.env.AI_PROVIDER_MODE || "hybrid" : "mock";
  const settings = providerSettings[provider];
  if (mode === "mock" || !settings) return localResult(fallback);

  const apiKey = readKey(settings.key);
  if (!apiKey) {
    if (mode === "real") throw new Error(`${settings.key}가 설정되지 않았습니다.`);
    recentEvents[provider] = { status: "fallback", reason: "missing-key" };
    return localResult(fallback);
  }

  const model = requestedModel || process.env[settings.model] || settings.defaultModel;
  try {
    const content = await settings.run({ apiKey, model, prompt });
    recentEvents[provider] = { status: "live", model };
    return { content: stripCodeFence(content), source: "live", actualModel: model };
  } catch (error) {
    if (mode === "real") throw error;
    recentEvents[provider] = { status: "fallback", model, reason: sanitizeError(error.message) };
    console.warn(`[AI fallback] ${provider}: ${error.message}`);
    return localResult(fallback);
  }
}

export function textProviderInfo(provider) {
  const settings = providerSettings[provider];
  return {
    configured: Boolean(settings && readKey(settings.key)),
    mode: process.env.AI_PROVIDER_MODE || "mock"
  };
}

export function providerStatus() {
  return {
    mode: process.env.AI_PROVIDER_MODE || "mock",
    text: Object.fromEntries(Object.entries(providerSettings).map(([provider, settings]) => [
      provider,
      { configured: Boolean(readKey(settings.key)), model: process.env[settings.model] || settings.defaultModel }
    ])),
    media: {
      mode: "runway-live-on-toggle",
      note: "ON 상태에서 Runway 선택 모델은 실제 비동기 영상 작업을 시작합니다.",
      Sora: { configured: Boolean(process.env.SORA_API_KEY || process.env.OPENAI_API_KEY), integration: "configured-only" },
      Veo: { configured: Boolean(process.env.VEO_API_KEY || process.env.GEMINI_API_KEY), integration: "via-runway" },
      Runway: { configured: Boolean(process.env.RUNWAY_API_KEY), integration: "live" }
    },
    recentEvents
  };
}

function localResult(content) {
  return { content, source: "mock", actualModel: "local-fallback" };
}

async function runOpenAI({ apiKey, model, prompt }) {
  const data = await requestJSON("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({ model, input: prompt })
  });
  const text = data.output_text || data.output?.flatMap((item) => item.content || []).map((item) => item.text).join("\n");
  if (!text) throw new Error("OpenAI 응답에 텍스트가 없습니다.");
  return text;
}

async function runGemini({ apiKey, model, prompt }) {
  const data = await requestJSON(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
    method: "POST",
    headers: { "x-goog-api-key": apiKey, "content-type": "application/json" },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] })
  });
  const text = data.candidates?.[0]?.content?.parts?.map((part) => part.text).join("\n");
  if (!text) throw new Error("Gemini 응답에 텍스트가 없습니다.");
  return text;
}

async function runAnthropic({ apiKey, model, prompt }) {
  const data = await requestJSON("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": apiKey, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model, max_tokens: 2200, messages: [{ role: "user", content: prompt }] })
  });
  const text = data.content?.map((item) => item.text).join("\n");
  if (!text) throw new Error("Claude 응답에 텍스트가 없습니다.");
  return text;
}

async function runOpenRouter({ apiKey, model, prompt }) {
  return runOpenAICompatible("https://openrouter.ai/api/v1/chat/completions", { apiKey, model, prompt });
}

async function runGroq({ apiKey, model, prompt }) {
  return runOpenAICompatible("https://api.groq.com/openai/v1/chat/completions", { apiKey, model, prompt });
}

async function runOpenAICompatible(url, { apiKey, model, prompt }) {
  const data = await requestJSON(url, {
    method: "POST",
    headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
    body: JSON.stringify({ model, messages: [{ role: "user", content: prompt }] })
  });
  const text = data.choices?.[0]?.message?.content;
  if (!text) throw new Error("호환 API 응답에 텍스트가 없습니다.");
  return text;
}

async function requestJSON(url, options) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(45000) });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${response.status} ${data.error?.message || data.message || "API 요청 실패"}`);
  return data;
}

function stripCodeFence(content) {
  return content.trim().replace(/^```(?:json|markdown|md|html)?\s*/i, "").replace(/\s*```$/, "");
}

function sanitizeError(message) {
  return String(message).replace(/sk-[A-Za-z0-9_-]+/g, "[redacted]").slice(0, 240);
}

function readKey(key) {
  return (Array.isArray(key) ? key : [key]).map((name) => process.env[name]).find(Boolean);
}
