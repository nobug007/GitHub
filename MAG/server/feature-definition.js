const availableAIs = [
  { id: "openai-gpt-5-4", provider: "OpenAI", model: "gpt-5.4", name: "OpenAI GPT-5.4", fit: 98, description: "사용자 시나리오를 구현 가능한 MVP 기능으로 분해하고 우선순위를 정교하게 판단합니다." },
  { id: "anthropic-claude-opus-4-1", provider: "Anthropic", model: "claude-opus-4-1-20250805", name: "Claude Opus 4.1", fit: 96, description: "사용자 경험을 해치지 않으면서 반드시 필요한 기능과 제외할 기능을 구분합니다." },
  { id: "google-gemini-3-pro", provider: "Google", model: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", fit: 94, description: "기능 간 연결 관계와 확장 가능성을 비교하여 단계별 구현 범위를 제안합니다." },
  { id: "mistral-medium-3-5", provider: "Mistral AI", model: "mistral-medium-3-5", name: "Mistral Medium 3.5", fit: 92, description: "빠른 MVP 검증에 적합한 최소 기능 세트와 기술 난이도를 정리합니다." },
  { id: "cohere-command-a-plus", provider: "Cohere", model: "command-a-plus-05-2026", name: "Cohere Command A+", fit: 89, description: "업무 흐름을 기준으로 기능 요구사항과 결과물 활용 방식을 구조화합니다." }
];

export function listFeatureAIs() {
  return availableAIs;
}

export async function runFeatureDefinition({ idea, scenarioDefinition, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!scenarioDefinition?.trim()) throw new Error("편집된 사용자 시나리오가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildFeaturePrompt({ idea, scenarioDefinition });
  return {
    idea,
    scenarioDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        content: outputFor(ai.id, idea, scenarioDefinition)
      };
    }))
  };
}

export function mergeFeatureDefinition({ idea, scenarioDefinition, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!scenarioDefinition?.trim()) throw new Error("편집된 사용자 시나리오가 필요합니다.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("편집된 핵심 기능 결과 3개가 필요합니다.");

  return {
    idea,
    scenarioDefinition,
    merged: [
      `# "${idea}" 핵심 기능 정의`,
      "",
      "## 1. 필수 기능",
      "- 프로젝트 아이디어 입력 및 요구사항 구조화",
      "- 단계별 AI 서버 선택과 병렬 답변 생성",
      "- AI 답변 비교, 편집, Merge 서버 취합",
      "- 단계별 결과 저장과 다음 단계 전달",
      "- 최종 결과 확인 및 다운로드",
      "",
      "## 2. 선택 기능",
      "- 진행 상태와 예상 소요 시간 표시",
      "- 결과 버전 비교",
      "- 공유 링크 생성",
      "",
      "## 3. 추후 확장 기능",
      "- 외부 AI 제공사 API 연동",
      "- 사용자 계정과 프로젝트 이력 관리",
      "- 협업, 승인, 관리자 검수",
      "",
      "## 4. 초기 MVP에서 제외할 기능",
      "- 복잡한 권한 체계",
      "- 자동 결제와 요금제",
      "- 검증 전 고급 배포 자동화",
      "",
      "## 5. AI 서버별 의견 반영",
      ...outputs.map((output, index) => `${index + 1}. [${output.aiName}] ${compact(output.content).slice(0, 380)}`),
      "",
      "## 6. 다음 단계 입력",
      "이 핵심 기능을 기준으로 사용자 흐름, 시스템 처리, 데이터 이동이 보이는 WorkFlow를 설계합니다."
    ].join("\n")
  };
}

function buildFeaturePrompt({ idea, scenarioDefinition }) {
  return [
    "당신은 초기 MVP 범위를 설계하는 제품 기획 전문가입니다.",
    "사용자가 편집한 사용자 시나리오를 기준으로 실제 검증에 필요한 핵심 기능을 정의해 주세요.",
    "완성형 서비스가 아니라 빠르게 검증 가능한 최소 기능을 우선하세요.",
    "",
    "반드시 포함할 항목:",
    "1. 필수 기능",
    "2. 선택 기능",
    "3. 추후 확장 기능",
    "4. 초기 MVP에서 제외할 기능",
    "5. 기능별 검증 목적",
    "6. 구현 우선순위와 난이도",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "사용자가 편집한 사용자 시나리오:",
    scenarioDefinition
  ].join("\n");
}

function outputFor(id, idea, scenarioDefinition) {
  const excerpt = compact(scenarioDefinition).slice(0, 260);
  const focus = {
    "openai-gpt-5-4": ["기능 우선순위 중심", "입력 수집, 핵심 분석, 결과 확인, 결과 수정 및 저장"],
    "anthropic-claude-opus-4-1": ["사용 경험 중심", "상황 입력, 진행 상태 확인, 결과 편집, 후속 행동 안내"],
    "google-gemini-3-pro": ["확장 구조 중심", "프로젝트 입력, 단계별 생성, 결과 비교, 결과 내보내기"],
    "mistral-medium-3-5": ["최소 구현 중심", "입력, 핵심 처리, 결과 화면"],
    "cohere-command-a-plus": ["업무 활용 중심", "입력 구조화, 결과 검토, 공유 및 다운로드"]
  }[id];
  return [
    `# ${focus[0]} 핵심 기능 정의`,
    "",
    "## 전달받은 사용자 시나리오",
    excerpt,
    "",
    "## 1. 필수 기능",
    ...focus[1].split(", ").map((item) => `- ${item}`),
    "",
    "## 2. 선택 기능",
    "- 결과 비교",
    "- 진행 상태 표시",
    "",
    "## 3. 추후 확장 기능",
    "- 외부 서비스 연동",
    "- 협업과 권한 관리",
    "",
    "## 4. 초기 MVP에서 제외할 기능",
    "- 복잡한 결제와 요금제",
    "- 검증 전 고급 자동화",
    "",
    `## 5. 검증 목적`,
    `"${idea}"의 핵심 사용자가 최소 흐름만으로 원하는 결과에 도달하는지 확인합니다.`
  ].join("\n");
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
