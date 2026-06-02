const availableAIs = [
  { id: "microsoft-copilot-powerpoint", provider: "Microsoft", model: "copilot-powerpoint", name: "Microsoft Copilot for PowerPoint", fit: 98, description: "PowerPoint 문서 흐름과 발표용 슬라이드 구성을 빠르게 작성하는 데 적합합니다." },
  { id: "canva-magic-design", provider: "Canva", model: "magic-design", name: "Canva Magic Design", fit: 97, description: "밝고 정돈된 시각 스타일과 발표용 레이아웃을 적용한 PPT 초안을 구성합니다." },
  { id: "gamma-ai", provider: "Gamma", model: "gamma-ai", name: "Gamma AI", fit: 95, description: "긴 사업성 리포트를 읽기 쉬운 스토리라인과 카드형 슬라이드로 변환합니다." },
  { id: "beautiful-ai", provider: "Beautiful.ai", model: "designer-bot", name: "Beautiful.ai DesignerBot", fit: 93, description: "슬라이드별 정보 밀도와 시각적 균형을 자동으로 정리합니다." },
  { id: "pitch-ai", provider: "Pitch", model: "pitch-ai", name: "Pitch AI", fit: 90, description: "협업과 발표에 적합한 간결한 사업 제안서 구조를 만듭니다." }
];

export function listPresentationAIs() {
  return availableAIs;
}

export async function runPresentationGeneration({ idea, businessReport, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!businessReport?.trim()) throw new Error("편집된 최종 사업성 리포트가 필요합니다.");
  if (selected.length !== 3) throw new Error("PPT AI를 정확히 3개 선택해 주세요.");

  const prompt = buildPrompt({ idea, businessReport });
  return {
    idea,
    businessReport,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        content: outputFor(ai.id, idea, businessReport)
      };
    }))
  };
}

function buildPrompt({ idea, businessReport }) {
  return [
    "당신은 MVP 사업 제안서 PPT를 구성하는 프레젠테이션 전문가입니다.",
    "최종 사업성 분석 리포트를 바탕으로 발표용 PPT 초안을 작성해 주세요.",
    "",
    "반드시 포함할 슬라이드:",
    "1. 표지",
    "2. 사업 아이디어",
    "3. 문제 정의와 목표",
    "4. 타겟과 페르소나",
    "5. 사용자 시나리오",
    "6. 핵심 기능",
    "7. WorkFlow",
    "8. UI / UX 화면",
    "9. 미래 완성형 영상",
    "10. BM, 시장성, 기술성, 수익성, 리스크",
    "11. 유사 서비스 비교",
    "12. 실행 계획",
    "",
    `사업 아이디어: ${idea}`,
    "",
    "최종 사업성 분석 리포트:",
    businessReport
  ].join("\n");
}

function outputFor(id, idea, businessReport) {
  const focus = {
    "microsoft-copilot-powerpoint": "제안서 표준형",
    "canva-magic-design": "밝은 비주얼형",
    "gamma-ai": "스토리텔링형",
    "beautiful-ai": "정보 균형형",
    "pitch-ai": "간결한 피치형"
  }[id];
  return [
    `# ${focus} PPT 초안`,
    "",
    `## 표지`,
    `${idea}`,
    "AI 기반 MVP 자동 제작 플랫폼",
    "",
    "## 슬라이드 구성",
    "1. 사업 아이디어와 핵심 가치",
    "2. 고객 문제와 개선 목표",
    "3. 타겟 고객과 대표 페르소나",
    "4. 사용자 시나리오",
    "5. MVP 핵심 기능",
    "6. 서비스 WorkFlow",
    "7. UI / UX 화면 구성",
    "8. 미래 완성형 데모 영상",
    "9. BM과 수익 구조",
    "10. 시장성, 기술성, 수익성",
    "11. 리스크와 대응 전략",
    "12. 실행 로드맵",
    "",
    "## 작성 기준",
    `최종 리포트의 핵심 내용을 12장 안에서 명확하게 전달합니다: ${compact(businessReport).slice(0, 280)}`
  ].join("\n");
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
