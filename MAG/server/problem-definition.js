import { generateText } from "./providers/text.js";
import { createTextAIList, withTextConnections } from "./providers/catalog.js";

const availableAIs = createTextAIList({
  descriptions: [
    "복합적인 사업 아이디어를 문제 정의, 목표, 검증 지표로 정교하게 구조화합니다.",
    "긴 문맥과 복잡한 요구사항을 분석하여 사업 목표와 사용자 문제를 구체화합니다.",
    "빠른 응답으로 사용자 관점의 문제 상황과 개선 목표를 정리합니다.",
    "연결 가능한 모델을 자동 선택하여 문제 정의 관점을 보완합니다.",
    "빠른 추론으로 초기 MVP 범위와 핵심 검증 항목을 정리합니다."
  ],
  fallbackIds: ["problem-framer", "goal-setter", "customer-lens", "market-context", "feasibility-check"]
});

export function listProblemDefinitionAIs() {
  return withTextConnections(availableAIs);
}

export async function runProblemDefinition({ idea, selectedAIIds, useLiveAI = false }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (selected.length !== 3) throw new Error("AI를 정확히 3개 선택해 주세요.");

  return {
    idea,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const generated = await generateText({
        provider: ai.provider,
        model: ai.model,
        useLiveAI,
        prompt: `당신은 MVP 문제 정의 전문가입니다.\n다음 아이디어의 핵심 문제, 기존 방식의 한계, 개선 목표, 검증 지표를 구체적으로 작성하세요.\n\n아이디어: ${idea}`,
        fallback: outputFor(ai.fallbackId, idea)
      });
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        content: generated.content,
        source: generated.source,
        actualModel: generated.actualModel
      };
    }))
  };
}

export function mergeProblemDefinition({ idea, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!Array.isArray(outputs) || outputs.length !== 3) {
    throw new Error("편집된 AI 결과 3개가 필요합니다.");
  }

  return {
    idea,
    merged: [
      "## 문제 정의",
      `"${idea}"는 사용자가 기존 방식에서 겪는 반복적인 불편을 줄이고, 핵심 행동을 더 짧고 명확하게 완료하도록 돕는 서비스입니다.`,
      "",
      "## 구체화된 목표",
      "1. 사용자가 가장 크게 느끼는 불편을 한 문장으로 설명합니다.",
      "2. 초기 MVP에서 반드시 검증할 핵심 행동을 1~2개로 제한합니다.",
      "3. 사용 전과 사용 후의 변화를 측정 가능한 지표로 확인합니다.",
      "",
      "## 선택한 AI 의견 반영",
      ...outputs.map((item, index) => `${index + 1}. ${compact(item.content)}`),
      "",
      "## 다음 단계 입력",
      "이 문제 정의와 목표를 기준으로 타겟 고객과 대표 페르소나를 분석합니다."
    ].join("\n")
  };
}

function outputFor(id, idea) {
  const outputs = {
    "problem-framer": [
      "## 핵심 문제",
      `"${idea}"를 필요로 하는 사용자는 현재 여러 도구와 정보를 오가며 문제를 해결하고 있습니다.`,
      "기존 방식은 시간이 오래 걸리고, 결과의 품질이 사용자 경험에 따라 달라집니다.",
      "",
      "## 개선 방향",
      "사용자가 가장 중요한 행동을 한 흐름 안에서 완료할 수 있도록 MVP 범위를 좁혀야 합니다."
    ],
    "goal-setter": [
      "## 목표",
      `"${idea}"의 초기 목표는 완성형 서비스 제작이 아니라 핵심 가설 검증입니다.`,
      "",
      "## 검증 지표",
      "- 첫 행동 완료율",
      "- 결과 화면 도달률",
      "- 다시 사용할 의향",
      "- 정성 피드백"
    ],
    "customer-lens": [
      "## 사용자 관점",
      `사용자는 "${idea}" 자체보다 현재의 번거로운 과정을 더 빠르고 쉽게 끝내고 싶어합니다.`,
      "",
      "## 기대 변화",
      "Before: 여러 경로를 탐색하며 결정을 미룹니다.",
      "After: 핵심 정보를 확인하고 바로 다음 행동으로 이동합니다."
    ],
    "market-context": [
      "## 시장 맥락",
      `"${idea}"는 범용 기능보다 특정 사용 상황을 명확히 잡을 때 경쟁력이 생깁니다.`,
      "",
      "## 차별화 방향",
      "기존 대체 수단 대비 시간 절약, 쉬운 사용, 결과물 품질을 강조해야 합니다."
    ],
    "feasibility-check": [
      "## MVP 범위",
      `"${idea}"는 초기에는 입력, 핵심 처리, 결과 확인의 최소 흐름으로 검증할 수 있습니다.`,
      "",
      "## 제외 권장",
      "- 복잡한 권한 관리",
      "- 과도한 외부 API 연동",
      "- 검증 전 고급 자동화"
    ]
  };
  return outputs[id].join("\n");
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ").slice(0, 220);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
