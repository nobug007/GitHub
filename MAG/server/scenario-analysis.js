import { generateText } from "./providers/text.js";
import { createTextAIList, withTextConnections } from "./providers/catalog.js";

const availableAIs = createTextAIList({
  descriptions: [
    "복합 사용자 맥락을 단계별 행동 흐름과 검증 가능한 시나리오로 구조화합니다.",
    "사용 전후 상황과 접점을 폭넓게 비교하여 서비스 이용 흐름을 보완합니다.",
    "빠른 응답으로 핵심 행동과 기대 결과를 명확하게 정리합니다.",
    "연결 가능한 모델을 자동 선택하여 사용자 여정을 보완합니다.",
    "빠른 추론으로 MVP에서 검증할 핵심 시나리오를 제안합니다."
  ],
  fallbackIds: ["openai-gpt-5-4", "google-gemini-3-pro", "google-gemini-3-pro", "cohere-command-a-plus", "mistral-medium-3-5"]
});

export function listScenarioAIs() {
  return withTextConnections(availableAIs);
}

export async function runScenarioAnalysis({ idea, personaDefinition, selectedAIIds, useLiveAI = false }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!personaDefinition?.trim()) throw new Error("편집된 타겟 및 페르소나 정의가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildScenarioPrompt({ idea, personaDefinition });
  return {
    idea,
    personaDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const generated = await generateText({ provider: ai.provider, model: ai.model, prompt, fallback: outputFor(ai.fallbackId, idea, personaDefinition), useLiveAI });
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

export function mergeScenarioAnalysis({ idea, personaDefinition, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!personaDefinition?.trim()) throw new Error("편집된 타겟 및 페르소나 정의가 필요합니다.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("편집된 시나리오 결과 3개가 필요합니다.");

  return {
    idea,
    personaDefinition,
    merged: [
      `# "${idea}" 사용자 시나리오`,
      "",
      "## 1. 사용 전 상황",
      "핵심 사용자는 기존 방식으로 문제를 해결하기 위해 여러 정보와 도구를 오가며 시간과 노력을 쓰고 있습니다.",
      "",
      "## 2. 서비스가 필요한 계기",
      "빠른 판단이 필요하거나 반복되는 문제를 다시 해결해야 하는 순간 서비스를 찾습니다.",
      "",
      "## 3. 서비스 이용 흐름",
      "1. 사용자는 자신의 상황과 원하는 결과를 입력합니다.",
      "2. 서비스는 입력 내용을 분석해 가장 중요한 선택지와 다음 행동을 제안합니다.",
      "3. 사용자는 결과를 검토하고 필요한 부분을 수정합니다.",
      "4. 사용자는 정리된 결과를 저장하거나 후속 작업에 활용합니다.",
      "",
      "## 4. 사용 후 변화",
      "탐색 시간과 시행착오가 줄어들고, 다음 행동이 더 명확해집니다.",
      "",
      "## 5. MVP 검증 지표",
      "- 첫 결과 도달률",
      "- 핵심 행동 완료 시간",
      "- 결과 수정률",
      "- 다음 행동 전환율",
      "- 다시 사용할 의향",
      "",
      "## 6. AI 서버별 의견 반영",
      ...outputs.map((output, index) => `${index + 1}. [${output.aiName}] ${compact(output.content).slice(0, 360)}`),
      "",
      "## 7. 다음 단계 입력",
      "이 사용자 시나리오를 기준으로 MVP에 반드시 필요한 핵심 기능과 제외할 기능을 정의합니다."
    ].join("\n")
  };
}

function buildScenarioPrompt({ idea, personaDefinition }) {
  return [
    "당신은 MVP 사용자 경험을 설계하는 사용자 시나리오 전문가입니다.",
    "사용자가 편집한 타겟 및 페르소나 정의를 근거로 실제 사용 장면이 보이는 시나리오를 작성해 주세요.",
    "일반적인 기능 설명보다 사용자의 상황, 행동, 서비스 접점, 확인 결과, 사용 후 변화를 구체적으로 작성하세요.",
    "",
    "반드시 포함할 항목:",
    "1. 사용 전 상황과 Pain Point",
    "2. 서비스가 필요한 계기",
    "3. 서비스 발견 또는 접속",
    "4. 핵심 기능을 이용하는 단계별 행동",
    "5. 결과 확인과 사용자의 판단",
    "6. 사용 후 변화",
    "7. MVP에서 검증할 행동 지표",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "사용자가 편집한 타겟 및 페르소나 정의:",
    personaDefinition
  ].join("\n");
}

function outputFor(id, idea, personaDefinition) {
  const targetExcerpt = compact(personaDefinition).slice(0, 240);
  const variations = {
    "openai-gpt-5-4": ["행동 중심 시나리오", "핵심 문제를 해결하기 위해 서비스를 접속하고, 입력부터 결과 확인까지 가장 짧은 흐름을 완료합니다.", "첫 결과 도달률과 완료 시간을 확인합니다."],
    "anthropic-claude-opus-4-1": ["상황 중심 시나리오", "마감 또는 의사결정 압박을 느낀 사용자가 기존 방식의 번거로움을 줄이기 위해 서비스를 찾고, 결과를 보며 안도감을 얻습니다.", "사용 전후 만족도와 다시 사용할 의향을 확인합니다."],
    "google-gemini-3-pro": ["접점 중심 시나리오", "사용자는 여러 대안을 비교하던 중 서비스를 발견하고, 추천 결과와 다음 행동을 연결해 판단 시간을 줄입니다.", "결과 신뢰도와 다음 행동 전환율을 확인합니다."],
    "mistral-medium-3-5": ["MVP 검증 중심 시나리오", "사용자는 최소 입력으로 핵심 결과를 확인하고, 복잡한 기능 없이도 문제 해결 가능성을 평가합니다.", "핵심 기능 사용률과 이탈 지점을 확인합니다."],
    "cohere-command-a-plus": ["업무 흐름 중심 시나리오", "사용자는 기존 업무 흐름 안에서 서비스를 사용하고, 정리된 결과를 공유하거나 후속 작업에 활용합니다.", "결과 활용률과 공유 행동을 확인합니다."]
  };
  const [title, usage, metric] = variations[id];
  return [
    `# ${title}`,
    "",
    "## 전달받은 타겟 / 페르소나 기준",
    targetExcerpt,
    "",
    "## 1. 사용 전 상황",
    `"${idea}"의 핵심 사용자는 기존 방식으로 문제를 해결하면서 여러 정보와 도구를 오가고 있습니다.`,
    "",
    "## 2. 서비스가 필요한 계기",
    "반복되는 문제를 다시 해결해야 하거나 빠른 판단이 필요한 순간 서비스를 찾습니다.",
    "",
    "## 3. 서비스 이용 과정",
    usage,
    "",
    "## 4. 결과 확인",
    "사용자는 결과가 자신의 상황에 맞는지 확인하고 바로 다음 행동으로 이동할지 판단합니다.",
    "",
    "## 5. 사용 후 변화",
    "기존 방식보다 탐색 시간과 시행착오가 줄어들고, 다음 행동이 명확해집니다.",
    "",
    "## 6. MVP 검증 지표",
    metric
  ].join("\n");
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
