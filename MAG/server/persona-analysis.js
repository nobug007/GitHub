const availableAIs = [
  { id: "openai-gpt-5-4", provider: "OpenAI", model: "gpt-5.4", name: "OpenAI GPT-5.4", fit: 98, description: "복합 요구사항을 구조화하고 핵심 타겟, 니즈, 행동 맥락을 정교하게 분석하는 범용 추론 모델입니다." },
  { id: "anthropic-claude-opus-4-1", provider: "Anthropic", model: "claude-opus-4-1-20250805", name: "Claude Opus 4.1", fit: 97, description: "긴 기획 문맥을 읽고 대표 페르소나의 상황, 동기, Pain Point를 상세하게 작성하는 데 적합합니다." },
  { id: "google-gemini-3-pro", provider: "Google", model: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", fit: 95, description: "넓은 문맥을 바탕으로 사용자 세그먼트와 서비스 이용 상황을 다각도로 비교 분석합니다." },
  { id: "cohere-command-a-plus", provider: "Cohere", model: "command-a-plus-05-2026", name: "Cohere Command A+", fit: 92, description: "다국어 비즈니스 문맥과 구조화된 결과 작성에 강점이 있어 고객군 분석을 보완합니다." },
  { id: "mistral-medium-3-5", provider: "Mistral AI", model: "mistral-medium-3.5", name: "Mistral Medium 3.5", fit: 90, description: "비즈니스 요구사항을 빠르게 정리하고 초기 타겟에서 확장 가능한 세그먼트를 제안합니다." }
];

export function listPersonaAIs() {
  return availableAIs;
}

export async function runPersonaAnalysis({ idea, problemDefinition, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!problemDefinition?.trim()) throw new Error("앞 단계의 취합된 문제 정의 및 목표가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildPersonaPrompt({ idea, problemDefinition });
  return {
    idea,
    problemDefinition,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const analysis = simulateStructuredResponse(ai.id, idea, problemDefinition);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        content: JSON.stringify(analysis, null, 2)
      };
    }))
  };
}

export function mergePersonaAnalysis({ idea, problemDefinition, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!problemDefinition?.trim()) throw new Error("앞 단계의 취합된 문제 정의 및 목표가 필요합니다.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("편집된 AI 서버 결과 3개가 필요합니다.");

  const analyses = outputs.map((output) => parseEditedAnalysis(output));
  const primary = analyses[0];
  const persona = primary.persona;
  return {
    idea,
    problemDefinition,
    merged: [
      `# "${idea}" 타겟 및 페르소나 정의`,
      "",
      "## 1. 핵심 타겟 정의",
      primary.coreTarget,
      "",
      "## 2. 타겟 선정 이유",
      ...bulletLines(unique(analyses.flatMap((item) => item.reasons))),
      "",
      "## 3. 타겟 세그먼트",
      `- 1차 타겟: ${primary.segments.primary}`,
      `- 2차 타겟: ${primary.segments.secondary}`,
      `- 잠재 타겟: ${primary.segments.potential}`,
      "",
      "## 4. 대표 페르소나 정의",
      `- 이름: ${persona.name}`,
      `- 역할: ${persona.role}`,
      `- 상황: ${persona.context}`,
      `- 행동 특성: ${persona.behavior}`,
      `- 핵심 니즈: ${persona.need}`,
      `- 사용 동기: ${persona.motivation}`,
      "",
      "## 5. Pain Point와 서비스 필요 순간",
      ...numberedLines(unique(analyses.flatMap((item) => item.painPoints))),
      `- 서비스 필요 순간: ${persona.triggerMoment}`,
      "",
      "## 6. MVP 검증 시 확인할 사용자 반응",
      ...bulletLines(unique(analyses.flatMap((item) => item.validationQuestions))),
      "",
      "## 7. AI 서버별 차이점 및 추가 확인 필요",
      ...analyses.map((item, index) => `${index + 1}. [${outputs[index].aiName}] ${item.additionalCheck}`),
      "",
      "## 8. 다음 단계 입력",
      "이 타겟과 대표 페르소나를 기준으로 사용 전, 서비스 이용, 사용 후 변화가 드러나는 사용자 시나리오를 작성합니다."
    ].join("\n")
  };
}

function buildPersonaPrompt({ idea, problemDefinition }) {
  return [
    "당신은 초기 MVP 기획을 돕는 타겟 고객 분석 및 페르소나 설계 전문가입니다.",
    "입력 내용을 근거로 초기 MVP 검증에 가장 적합한 타겟과 대표 페르소나를 정의해 주세요.",
    "일반적인 문구를 반복하지 말고 프로젝트의 목적과 문제 정의에 맞춰 작성하세요.",
    "근거가 부족한 내용은 임의로 단정하지 말고 '추가 확인 필요'로 표시하세요.",
    "",
    "JSON 형식으로만 답변하세요. 필수 키:",
    "coreTarget: 핵심 타겟 정의",
    "reasons[]: 타겟 선정 이유",
    "segments.primary, segments.secondary, segments.potential: 1차, 2차, 잠재 타겟",
    "persona.name, persona.role, persona.context, persona.behavior, persona.need, persona.motivation, persona.triggerMoment: 대표 페르소나",
    "painPoints[]: Pain Point",
    "validationQuestions[]: MVP 검증 질문",
    "additionalCheck: 추가 확인 필요 항목",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "앞 단계에서 사용자가 편집한 문제 정의:",
    problemDefinition
  ].join("\n");
}

function simulateStructuredResponse(aiId, idea, problemDefinition) {
  const context = inferProjectContext(`${idea} ${problemDefinition}`);
  const perspective = perspectiveFor(aiId);
  return {
    coreTarget: `${context.primaryAudience} 중 ${context.problemSituation} 상황을 반복적으로 경험하고, ${context.desiredOutcome}이 필요한 사용자`,
    reasons: [
      `${context.primaryAudience}은 문제 발생 순간이 명확해 MVP의 효용을 바로 평가할 수 있습니다.`,
      `${context.problemSituation} 과정의 불편이 반복되어 개선 전후를 비교하기 쉽습니다.`,
      perspective.reason
    ],
    segments: {
      primary: `${context.primaryAudience} 중 ${context.problemSituation} 문제를 지금 해결해야 하는 사용자`,
      secondary: `${context.secondaryAudience} 중 같은 문제를 반복적으로 관리하는 사용자`,
      potential: `${context.potentialAudience} 중 유사한 의사결정 과정이 필요한 사용자`
    },
    persona: {
      name: `${context.personaLabel} A`,
      role: context.personaRole,
      context: `${context.problemSituation} 상황에서 기존 방식으로 정보를 비교하고 직접 판단하고 있습니다.`,
      behavior: `${context.currentBehavior} 결과가 불명확하면 여러 대안을 다시 비교합니다.`,
      need: `${context.desiredOutcome}을 짧고 명확한 흐름으로 완료하고 싶습니다.`,
      motivation: `시간 절약, 시행착오 감소, ${context.valueMetric} 개선`,
      triggerMoment: `${context.triggerMoment} 즉시 서비스를 찾습니다.`
    },
    painPoints: [
      `${context.problemSituation}에 필요한 정보가 여러 경로에 분산되어 있습니다.`,
      "어떤 선택이 자신의 상황에 적합한지 판단하기 어렵습니다.",
      `${context.currentBehavior} 반복으로 시간과 노력이 많이 듭니다.`
    ],
    validationQuestions: [
      `${context.desiredOutcome}까지 걸리는 시간이 실제로 줄었는가?`,
      "첫 결과를 신뢰하고 다음 행동으로 이동하는가?",
      "기존 방식 대신 다시 사용할 의향이 있는가?"
    ],
    additionalCheck: perspective.additionalCheck
  };
}

function inferProjectContext(text) {
  const normalized = text.toLowerCase();
  if (/지원사업|창업|mvp|사업계획|제안서/.test(normalized)) {
    return {
      primaryAudience: "지원사업 제출을 준비하는 예비 창업자와 초기 창업자",
      secondaryAudience: "창업 지원기관의 코치와 컨설턴트",
      potentialAudience: "내부 신규 사업을 제안하는 실무자",
      personaLabel: "예비 창업자",
      personaRole: "아이디어를 제출 가능한 MVP 기획으로 구체화해야 하는 1인 창업자",
      problemSituation: "짧은 기간 안에 아이디어를 사업계획과 MVP 산출물로 정리해야 하는",
      currentBehavior: "문서 템플릿, 검색 결과, 여러 생성형 AI를 오가며 내용을 직접 정리합니다.",
      desiredOutcome: "검토 가능한 기획서와 MVP 초안을 빠르게 만드는 것",
      triggerMoment: "지원사업 또는 발표 마감이 다가오면",
      valueMetric: "제출 자료의 완성도"
    };
  }
  if (/관광|여행|상점|소상공인|지역/.test(normalized)) {
    return {
      primaryAudience: "낯선 지역에서 취향에 맞는 장소를 찾는 관광객",
      secondaryAudience: "관광객 유입을 원하는 지역 소상공인",
      potentialAudience: "지역 콘텐츠를 기획하는 운영기관 담당자",
      personaLabel: "지역 관광객",
      personaRole: "짧은 여행 시간 안에 만족도 높은 장소를 선택하려는 방문객",
      problemSituation: "지역 상점과 체험 정보를 빠르게 비교해야 하는",
      currentBehavior: "지도, 검색 결과, 후기 서비스를 오가며 후보를 직접 비교합니다.",
      desiredOutcome: "자신의 취향과 동선에 맞는 장소를 빠르게 찾는 것",
      triggerMoment: "방문 직전 또는 이동 중 갈 곳을 결정해야 할 때",
      valueMetric: "여행 만족도"
    };
  }
  return {
    primaryAudience: "현재 문제를 반복적으로 경험하는 초기 사용자",
    secondaryAudience: "같은 문제를 팀 단위로 관리하는 실무 담당자",
    potentialAudience: "유사한 의사결정 과정이 필요한 사용자",
    personaLabel: "초기 사용자",
    personaRole: "기존 방식보다 빠른 해결책을 찾는 실무 사용자",
    problemSituation: "기존 방식으로 문제를 해결하는 데 시간과 노력이 드는",
    currentBehavior: "여러 정보와 도구를 비교하며 해결 방법을 직접 판단합니다.",
    desiredOutcome: "필요한 결과를 더 빠르고 명확하게 얻는 것",
    triggerMoment: "반복되는 문제를 다시 해결해야 할 때",
    valueMetric: "업무 효율"
  };
}

function perspectiveFor(id) {
  const values = {
    "openai-gpt-5-4": { reason: "핵심 행동이 뚜렷해 기능 우선순위를 검증하기 좋습니다.", additionalCheck: "사용자가 가장 먼저 완료하려는 핵심 행동을 인터뷰로 확인합니다." },
    "anthropic-claude-opus-4-1": { reason: "사용 전후의 감정 변화와 행동 맥락을 관찰하기 좋은 고객군입니다.", additionalCheck: "기존 방식에서 가장 답답함을 느끼는 순간을 인터뷰로 확인합니다." },
    "google-gemini-3-pro": { reason: "개인 사용자와 조직 사용자의 요구 차이를 비교하기 좋습니다.", additionalCheck: "실제 사용자와 비용 지불자가 같은지 확인합니다." },
    "cohere-command-a-plus": { reason: "다양한 표현과 요구사항을 구조화해 비교하기 좋은 고객군입니다.", additionalCheck: "고객군별로 필요한 결과물 형식이 다른지 확인합니다." },
    "mistral-medium-3-5": { reason: "초기 고객 검증 후 인접 세그먼트로 확장하기 좋습니다.", additionalCheck: "2차 타겟에서도 핵심 기능을 재사용할 수 있는지 확인합니다." }
  };
  return values[id];
}

function parseEditedAnalysis(output) {
  try {
    return normalizeAnalysis(JSON.parse(output.content));
  } catch {
    return normalizeAnalysis({
      coreTarget: output.content,
      reasons: ["사용자가 직접 편집한 AI 답변을 우선 반영했습니다."],
      additionalCheck: "편집된 자유 형식 답변의 세부 항목을 추가 확인합니다."
    });
  }
}

function normalizeAnalysis(value) {
  const fallback = "추가 확인 필요";
  return {
    coreTarget: value.coreTarget || fallback,
    reasons: arrayOrFallback(value.reasons),
    segments: {
      primary: value.segments?.primary || fallback,
      secondary: value.segments?.secondary || fallback,
      potential: value.segments?.potential || fallback
    },
    persona: {
      name: value.persona?.name || fallback,
      role: value.persona?.role || fallback,
      context: value.persona?.context || fallback,
      behavior: value.persona?.behavior || fallback,
      need: value.persona?.need || fallback,
      motivation: value.persona?.motivation || fallback,
      triggerMoment: value.persona?.triggerMoment || fallback
    },
    painPoints: arrayOrFallback(value.painPoints),
    validationQuestions: arrayOrFallback(value.validationQuestions),
    additionalCheck: value.additionalCheck || fallback
  };
}

function arrayOrFallback(value) {
  return Array.isArray(value) && value.length ? value : ["추가 확인 필요"];
}

function unique(values) {
  return [...new Set(values)];
}

function bulletLines(values) {
  return values.map((value) => `- ${value}`);
}

function numberedLines(values) {
  return values.map((value, index) => `${index + 1}. ${value}`);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
