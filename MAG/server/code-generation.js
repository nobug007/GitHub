import { generateText } from "./providers/text.js";
import { createTextAIList, withTextConnections } from "./providers/catalog.js";

const availableAIs = createTextAIList({
  descriptions: [
    "화면 요구사항을 읽고 반응형 구성을 갖춘 Index Page 구현안을 작성합니다.",
    "UI/UX 문맥을 유지하면서 다양한 화면 상태와 레이아웃을 고려합니다.",
    "빠른 응답으로 간결한 Index Page 구현안을 생성합니다.",
    "연결 가능한 모델을 자동 선택하여 코드 생성 관점을 보완합니다.",
    "빠른 추론으로 입력, 상태, 결과 영역을 명확하게 구성합니다."
  ],
  fallbackIds: ["openai-gpt-5-4", "google-gemini-3-pro", "google-gemini-3-pro", "cohere-command-a-plus", "mistral-medium-3-5"]
});

export function listCodeGenerationAIs() {
  return withTextConnections(availableAIs);
}

export async function runIndexPageGeneration({ idea, uiuxDefinition, selectedAIIds, useLiveAI = false }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!uiuxDefinition?.trim()) throw new Error("확정된 UI / UX 화면 구성이 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildPrompt({ idea, uiuxDefinition });
  return {
    idea,
    uiuxDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const preview = indexPageFor(ai.fallbackId, idea);
      const generated = await generateText({ provider: ai.provider, model: ai.model, prompt, fallback: `로컬 Index Page 프리뷰: ${preview.title}`, useLiveAI });
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        ...preview,
        implementationNotes: generated.content,
        source: generated.source,
        actualModel: generated.actualModel
      };
    }))
  };
}

function buildPrompt({ idea, uiuxDefinition }) {
  return [
    "당신은 React 기반 MVP 프론트엔드 개발자입니다.",
    "확정된 UI / UX 화면 구성을 기준으로 첫 화면인 Index Page 하나만 작성해 주세요.",
    "다른 페이지를 만들지 말고, 밝고 간결하며 기능 중심인 첫 화면에 집중하세요.",
    "",
    "반드시 포함할 항목:",
    "1. 서비스 제목과 간단한 설명",
    "2. 아이디어 입력 영역",
    "3. 추천 AI 서버 선택 목록",
    "4. 실행 CTA",
    "5. 좌측 단계 메뉴",
    "6. 모바일 반응형 구성",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "확정된 UI / UX 화면 구성:",
    uiuxDefinition
  ].join("\n");
}

function indexPageFor(id, idea) {
  const variants = {
    "openai-gpt-5-4": ["Balanced Index", "입력 영역과 AI 선택 목록의 균형이 좋은 기능 중심 첫 화면", "balanced"],
    "anthropic-claude-opus-4-1": ["Guided Index", "사용자가 다음 행동을 자연스럽게 이해하도록 안내 흐름을 강화한 첫 화면", "guided"],
    "google-gemini-3-pro": ["Dashboard Index", "단계 메뉴와 선택 상태를 빠르게 파악할 수 있는 대시보드형 첫 화면", "dashboard"],
    "mistral-medium-3-5": ["Minimal Index", "검증에 필요한 입력과 실행 행동만 간결하게 남긴 첫 화면", "minimal"],
    "cohere-command-a-plus": ["Operational Index", "반복 사용과 업무 효율을 고려한 실무형 첫 화면", "operational"]
  };
  const [title, summary, style] = variants[id];
  return {
    title,
    summary,
    style,
    pageType: "index",
    preview: {
      brand: "MAG",
      product: "MVP Auto Generator",
      headline: "당신의 아이디어를 구체화 해 드립니다.",
      description: `"${idea}"를 시작으로 AI가 MVP 기획을 단계별로 구체화합니다.`,
      inputLabel: "아이디어를 입력해 주세요.",
      cta: "실행",
      aiOptions: ["Problem Framer AI", "Goal Setter AI", "Customer Lens AI", "Market Context AI", "Feasibility Check AI"],
      steps: ["문제 정의 및 목표", "타겟 / 페르소나", "사용자 시나리오", "핵심 기능 정의", "WorkFlow 구성", "UI / UX 화면 구성"]
    }
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
