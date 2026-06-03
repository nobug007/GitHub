import { textProviderInfo } from "./text.js";

const textModels = [
  { id: "openai-gpt-5-2", provider: "OpenAI", model: "gpt-5.2", name: "OpenAI GPT-5.2" },
  { id: "google-gemini-2-5-pro", provider: "Google", model: "gemini-2.5-pro", name: "Google Gemini 2.5 Pro" },
  { id: "google-gemini-2-5-flash", provider: "Google", model: "gemini-2.5-flash", name: "Google Gemini 2.5 Flash" },
  { id: "openrouter-auto", provider: "OpenRouter", model: "openrouter/auto", name: "OpenRouter Auto Router" },
  { id: "groq-llama-3-3-70b", provider: "Groq", model: "llama-3.3-70b-versatile", name: "Groq Llama 3.3 70B" }
];

export function createTextAIList({ fits = [98, 97, 95, 92, 90], descriptions, fallbackIds }) {
  return textModels.map((model, index) => ({
    ...model,
    fit: fits[index],
    description: descriptions[index],
    fallbackId: fallbackIds[index]
  }));
}

export function withTextConnections(ais) {
  return ais.map((ai) => ({ ...ai, connection: textProviderInfo(ai.provider) }));
}

export function mediaProviderInfo(provider) {
  const keys = {
    OpenAI: ["SORA_API_KEY", "OPENAI_API_KEY"],
    Google: ["VEO_API_KEY", "GEMINI_API_KEY"],
    Runway: ["RUNWAY_API_KEY"]
  };
  return { configured: (keys[provider] || []).some((key) => Boolean(process.env[key])) };
}
