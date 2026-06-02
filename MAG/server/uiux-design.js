const availableAIs = [
  { id: "openai-gpt-5-4", provider: "OpenAI", model: "gpt-5.4", name: "OpenAI GPT-5.4", fit: 98, description: "WorkFlow를 화면 목록, UI 요소, CTA와 이동 경로로 체계적으로 변환합니다." },
  { id: "google-gemini-3-pro", provider: "Google", model: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", fit: 96, description: "다양한 화면 상태와 사용자 접점을 폭넓게 비교하여 UI/UX 구성을 보완합니다." },
  { id: "anthropic-claude-opus-4-1", provider: "Anthropic", model: "claude-opus-4-1-20250805", name: "Claude Opus 4.1", fit: 95, description: "사용자의 맥락과 의사결정 흐름을 고려해 자연스러운 화면 경험을 설계합니다." },
  { id: "mistral-medium-3-5", provider: "Mistral AI", model: "mistral-medium-3-5", name: "Mistral Medium 3.5", fit: 92, description: "초기 MVP에 필요한 최소 화면과 반복 사용에 적합한 간결한 UI 구조를 제안합니다." },
  { id: "cohere-command-a-plus", provider: "Cohere", model: "command-a-plus-05-2026", name: "Cohere Command A+", fit: 89, description: "업무 흐름을 기준으로 입력, 검토, 결과 전달 화면을 구조화합니다." }
];

export function listUIUXAIs() {
  return availableAIs;
}

export async function runUIUXDesign({ idea, workflowDefinition, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!workflowDefinition?.trim()) throw new Error("편집된 WorkFlow 구성이 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 엔진을 정확히 3개 선택해 주세요.");

  const prompt = buildUIUXPrompt({ idea, workflowDefinition });
  return {
    idea,
    workflowDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        ...outputFor(ai.id, idea, workflowDefinition)
      };
    }))
  };
}

export function mergeUIUXDesign({ idea, workflowDefinition, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!workflowDefinition?.trim()) throw new Error("편집된 WorkFlow 구성이 필요합니다.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("UI / UX 구성 결과 3개가 필요합니다.");

  return {
    idea,
    workflowDefinition,
    merged: [
      `# "${idea}" 통합 UI / UX 화면 구성`,
      "",
      "## 1. 공통 레이아웃",
      "- 밝은 배경과 기능 중심의 화면 구성",
      "- 좌측에는 현재 위치를 확인할 수 있는 단계 메뉴",
      "- 중앙에는 현재 단계의 입력, 결과 확인, 편집 영역",
      "- 하단에는 이전 단계와 Submit 버튼",
      "",
      "## 2. 핵심 화면",
      "1. 프로젝트 아이디어 입력과 AI 엔진 선택",
      "2. AI 엔진별 답변 요약과 결과 편집",
      "3. Merge 서버의 통합 결과 확인",
      "4. 다음 단계 AI 엔진 추천과 선택",
      "5. 최종 산출물 확인과 다운로드",
      "",
      "## 3. 상태 설계",
      "- AI 실행 중에는 처리 상태를 표시합니다.",
      "- 오류 발생 시 현재 편집 내용을 유지하고 재시도할 수 있습니다.",
      "- 이전 단계로 돌아가도 사용자가 수정한 내용을 유지합니다.",
      "",
      "## 4. AI 엔진별 설계 관점",
      ...outputs.map((output, index) => `${index + 1}. [${output.aiName}] ${output.summary}`),
      "",
      "## 5. 다음 작업",
      "각 AI 엔진의 미리보기 링크에서 와이어프레임을 비교하고 최종 화면 구성을 확정합니다."
    ].join("\n")
  };
}

function buildUIUXPrompt({ idea, workflowDefinition }) {
  return [
    "당신은 초기 MVP의 UI/UX 화면 구성을 설계하는 제품 디자이너입니다.",
    "사용자가 편집한 WorkFlow를 기준으로 실제 제작에 필요한 화면 구성을 작성해 주세요.",
    "화려한 마케팅 페이지보다 기능 중심의 밝고 간결한 인터페이스를 우선하세요.",
    "",
    "반드시 포함할 항목:",
    "1. 필요한 화면 목록",
    "2. 화면별 목적",
    "3. 화면별 입력, 출력, UI 요소",
    "4. 주요 CTA와 화면 이동 경로",
    "5. 로딩, 오류, 빈 상태",
    "6. 모바일 대응",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "사용자가 편집한 WorkFlow:",
    workflowDefinition
  ].join("\n");
}

function outputFor(id, idea, workflowDefinition) {
  const excerpt = compact(workflowDefinition).slice(0, 280);
  const variants = {
    "openai-gpt-5-4": ["정보 구조 중심", "단계 메뉴와 편집 영역을 중심으로 정보 밀도를 균형 있게 구성합니다.", "Structured"],
    "google-gemini-3-pro": ["상태와 분기 중심", "진행 상태, 비교 결과, 예외 상황을 빠르게 파악하도록 구성합니다.", "State-aware"],
    "anthropic-claude-opus-4-1": ["사용자 경험 중심", "사용자가 다음 행동을 자연스럽게 이해하도록 흐름과 문구를 다듬습니다.", "Guided"],
    "mistral-medium-3-5": ["최소 화면 중심", "MVP 검증에 필요한 입력, 선택, 결과 화면만 간결하게 유지합니다.", "Minimal"],
    "cohere-command-a-plus": ["업무 효율 중심", "검토, 공유, 다운로드까지 반복 업무에 적합한 화면을 구성합니다.", "Operational"]
  }[id];
  const [focus, summary, style] = variants;
  return {
    summary,
    style,
    content: [
    `# ${focus} UI / UX 화면 구성`,
    "",
    "## 전달받은 WorkFlow",
    excerpt,
    "",
    "## 1. 화면 목록",
    "- 프로젝트 입력 화면",
    "- AI 엔진 선택 화면",
    "- AI 답변 비교 및 편집 화면",
    "- Merge 결과 편집 화면",
    "- 최종 결과 확인 화면",
    "",
    "## 2. 주요 UI 요소",
    "- 좌측 단계 메뉴",
    "- 입력 및 편집 가능한 텍스트 영역",
    "- AI 엔진 선택 리스트",
    "- 진행 상태, 오류, 로딩 표시",
    "- 이전 단계와 Submit 버튼",
    "",
    "## 3. 화면 이동",
    "입력 → 선택 → 답변 편집 → 취합 편집 → 다음 단계",
    "",
    `## 4. 설계 기준`,
    `"${idea}"의 사용자가 현재 위치와 다음 행동을 한눈에 이해하도록 구성합니다.`
    ].join("\n"),
    screens: previewScreens(style)
  };
}

function previewScreens(style) {
  return [
    { title: "프로젝트 입력", eyebrow: "STEP 01", description: "아이디어를 입력하고 적합한 AI 엔진을 선택합니다.", blocks: ["아이디어 입력 영역", "추천 AI 엔진 5개", "선택 상태", "실행 버튼"] },
    { title: "AI 답변 비교", eyebrow: "STEP 02", description: "세 엔진의 결과를 비교하고 필요한 내용을 수정합니다.", blocks: ["현재 단계 안내", "AI 답변 3개", "편집 영역", "Submit 버튼"] },
    { title: "통합 결과 편집", eyebrow: "STEP 03", description: "Merge 서버의 통합안을 확인하고 다음 단계로 전달합니다.", blocks: ["Merge 결과", "편집 영역", "다음 AI 추천", "이전 / Submit"] },
    { title: "최종 산출물", eyebrow: "DELIVERY", description: "완성된 파일과 위치를 확인하고 결과를 내려받습니다.", blocks: ["생성 파일 목록", "미리보기", "다운로드", "공유 링크"] }
  ].map((screen) => ({ ...screen, style }));
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
