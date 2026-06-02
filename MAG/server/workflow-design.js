const availableAIs = [
  { id: "openai-gpt-5-4", provider: "OpenAI", model: "gpt-5.4", name: "OpenAI GPT-5.4", fit: 98, description: "복합 기능을 사용자 흐름, 시스템 처리, 데이터 이동으로 나누어 일관된 WorkFlow를 설계합니다." },
  { id: "google-gemini-3-pro", provider: "Google", model: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", fit: 96, description: "여러 단계와 분기 조건을 폭넓게 비교해 서비스 흐름과 예외 경로를 보완합니다." },
  { id: "anthropic-claude-opus-4-1", provider: "Anthropic", model: "claude-opus-4-1-20250805", name: "Claude Opus 4.1", fit: 95, description: "사용자 관점의 자연스러운 이동 흐름과 단계별 의사결정을 세밀하게 정리합니다." },
  { id: "mistral-medium-3-5", provider: "Mistral AI", model: "mistral-medium-3-5", name: "Mistral Medium 3.5", fit: 92, description: "핵심 기능을 빠르게 연결하고 초기 MVP에 필요한 최소 흐름을 명확히 제안합니다." },
  { id: "cohere-command-a-plus", provider: "Cohere", model: "command-a-plus-05-2026", name: "Cohere Command A+", fit: 89, description: "업무 절차와 결과 전달 과정을 구조화하여 운영 관점의 WorkFlow를 보완합니다." }
];

export function listWorkflowAIs() {
  return availableAIs;
}

export async function runWorkflowDesign({ idea, featureDefinition, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!featureDefinition?.trim()) throw new Error("편집된 핵심 기능 정의가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildWorkflowPrompt({ idea, featureDefinition });
  return {
    idea,
    featureDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        content: outputFor(ai.id, idea, featureDefinition)
      };
    }))
  };
}

export function mergeWorkflowDesign({ idea, featureDefinition, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!featureDefinition?.trim()) throw new Error("편집된 핵심 기능 정의가 필요합니다.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("편집된 WorkFlow 결과 3개가 필요합니다.");

  return {
    idea,
    featureDefinition,
    merged: [
      `# "${idea}" WorkFlow 구성`,
      "",
      "## 1. 사용자 WorkFlow",
      "아이디어 입력 → AI 서버 선택 → 병렬 답변 확인 및 편집 → Merge 서버 취합 → 다음 단계 AI 선택 → 결과물 확인",
      "",
      "## 2. 시스템 및 AI 처리 WorkFlow",
      "사용자 입력 저장 → 선택한 AI 서버 3곳 요청 → 답변 저장 → 사용자 편집본 저장 → Merge 서버 취합 → 다음 단계 프롬프트 생성",
      "",
      "## 3. 데이터 흐름",
      "프로젝트 기본 정보 → 단계별 AI 원본 응답 → 사용자 편집본 → 취합본 → 다음 단계 입력 데이터 → 최종 산출물",
      "",
      "## 4. 주요 화면 이동",
      "입력 화면 → AI 선택 화면 → AI 답변 편집 화면 → 취합 결과 편집 화면 → 다음 단계 선택 화면",
      "",
      "## 5. 예외 상황과 재시도",
      "- AI 서버 응답 실패 시 해당 서버만 다시 요청합니다.",
      "- 사용자가 이전 단계로 돌아가면 편집본을 유지합니다.",
      "- 다음 단계 Submit 시 화면의 최신 편집 내용을 다시 읽어 전달합니다.",
      "",
      "## 6. AI 서버별 의견 반영",
      ...outputs.map((output, index) => `${index + 1}. [${output.aiName}] ${compact(output.content).slice(0, 400)}`),
      "",
      "## 7. 다음 단계 입력",
      "이 WorkFlow를 기준으로 필요한 화면 목록, 화면별 목적, UI 요소, CTA, 화면 이동 경로를 설계합니다."
    ].join("\n")
  };
}

function buildWorkflowPrompt({ idea, featureDefinition }) {
  return [
    "당신은 MVP 서비스 흐름을 설계하는 WorkFlow 전문가입니다.",
    "사용자가 편집한 핵심 기능 정의를 기준으로 실제 화면과 시스템 처리가 연결되는 WorkFlow를 작성해 주세요.",
    "사용자 흐름, AI 처리 흐름, 데이터 흐름을 분리하고 Mermaid flowchart 초안을 포함하세요.",
    "",
    "반드시 포함할 항목:",
    "1. 사용자 WorkFlow",
    "2. 시스템 및 AI 처리 WorkFlow",
    "3. 데이터 입력, 저장, 출력 흐름",
    "4. 주요 화면 이동",
    "5. 예외 상황과 재시도 경로",
    "6. Mermaid flowchart",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "사용자가 편집한 핵심 기능 정의:",
    featureDefinition
  ].join("\n");
}

function outputFor(id, idea, featureDefinition) {
  const excerpt = compact(featureDefinition).slice(0, 260);
  const focus = {
    "openai-gpt-5-4": "사용자 행동과 Merge 서버 처리 중심",
    "google-gemini-3-pro": "단계별 분기와 결과 확인 중심",
    "anthropic-claude-opus-4-1": "사용자 의사결정과 편집 흐름 중심",
    "mistral-medium-3-5": "최소 MVP 실행 경로 중심",
    "cohere-command-a-plus": "데이터 저장과 결과 전달 중심"
  }[id];
  return [
    `# ${focus} WorkFlow`,
    "",
    "## 전달받은 핵심 기능 정의",
    excerpt,
    "",
    "## 1. 사용자 WorkFlow",
    "아이디어 입력 → AI 서버 선택 → 답변 확인 및 편집 → Merge 결과 확인 → 다음 단계 AI 선택 → 최종 결과 다운로드",
    "",
    "## 2. 시스템 및 AI 처리 WorkFlow",
    "사용자 입력 저장 → 선택한 AI 서버 3곳 병렬 요청 → 응답 저장 → Merge 서버 취합 → 편집 결과 저장 → 다음 단계 전달",
    "",
    "## 3. 데이터 흐름",
    "프로젝트 입력 → 단계별 원본 응답 → 사용자 편집본 → 취합본 → 다음 단계 프롬프트 → 최종 산출물",
    "",
    "## 4. Mermaid flowchart",
    "```mermaid",
    "flowchart TD",
    "  A[아이디어 입력] --> B[AI 서버 3개 선택]",
    "  B --> C[병렬 답변 생성]",
    "  C --> D[사용자 편집]",
    "  D --> E[Merge 서버 취합]",
    "  E --> F[다음 단계 전달]",
    "```",
    "",
    `## 5. 검증 기준`,
    `"${idea}"의 핵심 흐름이 중단 없이 다음 단계로 전달되는지 확인합니다.`
  ].join("\n");
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
