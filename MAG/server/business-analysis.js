import { generateText } from "./providers/text.js";
import { createTextAIList, withTextConnections } from "./providers/catalog.js";

const availableAIs = createTextAIList({
  descriptions: [
    "시장성, 기술성, 수익성, 리스크를 연결하여 실행 가능한 사업 분석을 작성합니다.",
    "시장 구조와 성장 가능성을 폭넓게 비교하여 분석을 보완합니다.",
    "빠른 응답으로 BM과 유사 서비스 비교를 정리합니다.",
    "연결 가능한 모델을 자동 선택하여 사업성 관점을 보완합니다.",
    "빠른 추론으로 검증 범위와 현실적인 리스크를 분석합니다."
  ],
  fallbackIds: ["openai-gpt-5-4", "google-gemini-3-pro", "google-gemini-3-pro", "cohere-command-a-plus", "mistral-medium-3-5"]
});

export function listBusinessAnalysisAIs() {
  return withTextConnections(availableAIs);
}

export async function runBusinessAnalysis({ idea, indexPage, selectedVideo, selectedAIIds, useLiveAI = false }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!indexPage?.headline) throw new Error("확정된 Index Page가 필요합니다.");
  if (!selectedVideo?.aiName) throw new Error("최종 영상을 선택해 주세요.");
  if (selected.length !== 3) throw new Error("AI 서버를 정확히 3개 선택해 주세요.");

  const prompt = buildPrompt({ idea, indexPage, selectedVideo });
  return {
    idea,
    indexPage,
    selectedVideo,
    prompt,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      const generated = await generateText({ provider: ai.provider, model: ai.model, prompt, fallback: outputFor(ai.fallbackId, idea, selectedVideo), useLiveAI });
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

export function mergeBusinessAnalysis({ idea, outputs }) {
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!Array.isArray(outputs) || outputs.length !== 3) throw new Error("편집된 사업성 분석 결과 3개가 필요합니다.");

  return {
    idea,
    merged: [
      `# "${idea}" 최종 사업성 분석 리포트`,
      "",
      "## 1. 사업 개요",
      `"${idea}"는 사용자의 아이디어를 AI 인터뷰, 병렬 분석, 사용자 편집, Merge 서버 취합을 통해 실제 MVP 산출물로 전환하는 서비스입니다.`,
      "",
      "## 2. BM / 수익 구조",
      "- 프로젝트 단위 MVP 제작 패키지",
      "- 월 구독형 AI MVP 제작 도구",
      "- 관리자 검수와 문서 보완을 포함한 프리미엄 상품",
      "- 기관 및 창업 지원 프로그램용 B2B 라이선스",
      "",
      "## 3. 시장성",
      "- 지원사업 제출을 준비하는 예비 창업자와 초기 창업자",
      "- 빠른 고객 검증과 투자 자료가 필요한 스타트업",
      "- 신규 사업 기획을 반복하는 조직 실무자",
      "",
      "## 4. 기술성",
      "단계별 입력, AI 서버 병렬 요청, 사용자 편집, Merge 서버 취합을 독립 모듈로 구현하여 점진적으로 확장할 수 있습니다.",
      "",
      "## 5. 수익성",
      "초기에는 템플릿 기반 결과물과 관리자 검수를 결합하여 품질을 관리하고, 반복 사용 고객을 구독 상품으로 전환합니다.",
      "",
      "## 6. 핵심 리스크와 대응",
      "- AI 결과 품질 편차: 사용자 편집과 관리자 검수로 보완",
      "- AI 호출 비용: 단계별 모델 선택과 캐시 적용",
      "- 결과물 신뢰도: 근거 표시와 버전 이력 관리",
      "",
      "## 7. AI 서버별 분석 반영",
      ...outputs.map((output, index) => `${index + 1}. [${output.aiName}] ${compact(output.content).slice(0, 420)}`),
      "",
      "## 8. PPT 작성 방향",
      "문제, 타겟, 솔루션, WorkFlow, 화면, 미래 영상, 사업성, 실행 계획 순서로 최종 제안서를 구성합니다."
    ].join("\n")
  };
}

function buildPrompt({ idea, indexPage, selectedVideo }) {
  return [
    "당신은 초기 MVP의 사업성을 검토하는 사업 전략 전문가입니다.",
    "확정된 Index Page와 선택한 미래 완성형 데모 영상의 방향을 기준으로 사업성 분석을 작성해 주세요.",
    "",
    "반드시 포함할 항목:",
    "1. BM 및 수익 구조",
    "2. 시장성과 초기 고객",
    "3. 기술성과 구현 가능성",
    "4. 수익성과 비용 구조",
    "5. 핵심 리스크와 대응 전략",
    "6. 유사 서비스 비교 관점",
    "7. MVP 검증 지표",
    "",
    `사업 아이디어: ${idea}`,
    `Index Page 제목: ${indexPage.headline}`,
    `선택한 영상 방향: ${selectedVideo.aiName} - ${selectedVideo.summary}`
  ].join("\n");
}

function outputFor(id, idea, selectedVideo) {
  const focus = {
    "openai-gpt-5-4": "종합 사업성",
    "anthropic-claude-opus-4-1": "고객 가치와 설득력",
    "google-gemini-3-pro": "시장성과 경쟁 환경",
    "cohere-command-a-plus": "수익 구조와 운영 효율",
    "mistral-medium-3-5": "초기 검증과 리스크"
  }[id];
  return [
    `# ${focus} 분석`,
    "",
    `## 프로젝트`,
    `"${idea}"는 사용자의 아이디어를 단계별 AI 분석과 결과 편집을 통해 실제 MVP 산출물로 전환하는 서비스입니다.`,
    "",
    "## 1. BM / 수익 구조",
    "- 프로젝트 단위 제작 패키지",
    "- 월 구독형 AI MVP 제작 도구",
    "- 관리자 검수와 문서 보완을 포함한 프리미엄 서비스",
    "",
    "## 2. 시장성",
    "- 지원사업 제출을 준비하는 예비 창업자",
    "- 빠른 고객 검증이 필요한 초기 스타트업",
    "- 신규 사업 기획이 필요한 조직 실무자",
    "",
    "## 3. 기술성",
    "단계별 입력, AI 병렬 요청, 사용자 편집, Merge 서버 취합을 분리하여 점진적으로 구현할 수 있습니다.",
    "",
    "## 4. 수익성과 리스크",
    "- AI 호출 비용과 결과 품질을 함께 관리해야 합니다.",
    "- 초기에는 템플릿과 관리자 검수를 결합해 품질 편차를 줄입니다.",
    "",
    "## 5. 영상 방향 반영",
    `${selectedVideo.aiName}의 미래형 제품 영상은 완성 서비스의 기대 가치를 짧고 강하게 전달합니다.`,
    "",
    "## 6. MVP 검증 지표",
    "- 프로젝트 시작 대비 완료율",
    "- 결과물 다운로드율",
    "- 유료 전환 의향",
    "- 관리자 보정 시간"
  ].join("\n");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function compact(value) {
  return value.replaceAll("\n", " ").replace(/\s+/g, " ");
}
