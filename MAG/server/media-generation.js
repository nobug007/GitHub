import { mediaProviderInfo } from "./providers/catalog.js";

const availableAIs = [
  { id: "runway-gen-4-5", provider: "Runway", model: "gen4.5", name: "Runway Gen-4.5", fit: 98, description: "Runway API로 제품 데모 영상을 실제 생성합니다." },
  { id: "runway-veo-3-1", provider: "Runway", model: "veo3.1", name: "Google Veo 3.1 via Runway", fit: 97, description: "Runway API의 Veo 3.1 모델로 실제 영상과 오디오를 생성합니다." },
  { id: "runway-veo-3-1-fast", provider: "Runway", model: "veo3.1_fast", name: "Google Veo 3.1 Fast via Runway", fit: 96, description: "Runway API의 Veo 3.1 Fast 모델로 빠르게 실제 영상 초안을 생성합니다." },
  { id: "openai-sora-2", provider: "OpenAI", model: "sora-2", name: "OpenAI Sora 2", fit: 94, description: "Sora API 연결 후보입니다. 현재 자동 생성은 Runway 경로를 우선 사용합니다." },
  { id: "openai-sora-2-pro", provider: "OpenAI", model: "sora-2-pro", name: "OpenAI Sora 2 Pro", fit: 92, description: "Sora API 고품질 연결 후보입니다. 현재 자동 생성은 Runway 경로를 우선 사용합니다." }
];

export function listMediaGenerationAIs() {
  return availableAIs.map((ai) => ({ ...ai, connection: mediaProviderInfo(ai.provider) }));
}

export async function runMediaGeneration({ idea, indexPage, selectedAIIds, useLiveAI = false }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!indexPage?.headline) throw new Error("확정된 Index Page가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 엔진을 정확히 3개 선택해 주세요.");

  return {
    idea,
    indexPage,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const prompt = buildVideoPrompt({ idea, indexPage, index });
      const task = useLiveAI && ai.provider === "Runway"
        ? await startRunwayVideo({ model: ai.model, prompt })
        : null;
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        source: task ? "live" : "mock",
        taskId: task?.id || "",
        taskStatus: task ? "PENDING" : "PREVIEW",
        summary: `"${idea}"의 Index Page를 기준으로 5개의 작동 이미지와 1개의 데모 영상 시나리오를 구성합니다.`,
        deliverables: ["작동 이미지 5개", "데모 영상 시나리오", "영상 장면 구성표"]
      };
    }))
  };
}

export async function getMediaTask(taskId) {
  if (!taskId?.trim()) throw new Error("영상 작업 ID가 필요합니다.");
  const response = await fetch(`https://api.dev.runwayml.com/v1/tasks/${encodeURIComponent(taskId)}`, {
    headers: runwayHeaders(),
    signal: AbortSignal.timeout(45000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${response.status} ${data.error || data.message || "Runway 작업 조회 실패"}`);
  return data;
}

async function startRunwayVideo({ model, prompt }) {
  const response = await fetch("https://api.dev.runwayml.com/v1/text_to_video", {
    method: "POST",
    headers: { ...runwayHeaders(), "content-type": "application/json" },
    body: JSON.stringify({ model, promptText: prompt.slice(0, 1000), ratio: "1280:720", duration: 10 }),
    signal: AbortSignal.timeout(45000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${response.status} ${data.error || data.message || "Runway 영상 생성 실패"}`);
  return data;
}

function runwayHeaders() {
  const apiKey = process.env.RUNWAY_API_KEY;
  if (!apiKey) throw new Error("RUNWAY_API_KEY가 설정되지 않았습니다.");
  return { authorization: `Bearer ${apiKey}`, "X-Runway-Version": "2024-11-06" };
}

function buildVideoPrompt({ idea, indexPage, index }) {
  const moods = ["clean cinematic product demo", "energetic startup launch film", "polished interface walkthrough"];
  return `${moods[index]}. Show a bright modern SaaS interface for ${idea}. Begin with an idea input, then three AI engines analyzing in parallel, a merge result, UI screens appearing, and a final downloadable proposal. Use readable interface-like visual structure, blue accents, smooth camera motion, no logos, no tiny text. Headline concept: ${indexPage.headline}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
