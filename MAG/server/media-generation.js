const availableAIs = [
  { id: "openai-sora-2", provider: "OpenAI", model: "sora-2", name: "OpenAI Sora 2", fit: 98, description: "제품 작동 흐름을 자연스러운 장면 전환과 함께 영상으로 구성하는 데 적합합니다." },
  { id: "google-veo-3-1", provider: "Google", model: "veo-3.1", name: "Google Veo 3.1", fit: 97, description: "서비스 사용 장면을 선명한 영상과 시각적 스토리라인으로 표현합니다." },
  { id: "runway-gen-4-5", provider: "Runway", model: "gen-4.5", name: "Runway Gen-4.5", fit: 95, description: "제품 데모 영상과 화면 전환 중심의 모션 시퀀스를 빠르게 제작합니다." },
  { id: "adobe-firefly-video", provider: "Adobe", model: "firefly-video", name: "Adobe Firefly Video", fit: 92, description: "브랜드 톤을 유지한 홍보용 이미지와 안전한 상업용 영상 초안을 구성합니다." },
  { id: "luma-ray-2", provider: "Luma AI", model: "ray-2", name: "Luma Ray 2", fit: 90, description: "짧은 제품 소개 영상과 인상적인 작동 장면을 시각적으로 표현합니다." }
];

export function listMediaGenerationAIs() {
  return availableAIs;
}

export async function runMediaGeneration({ idea, indexPage, selectedAIIds }) {
  const selected = selectedAIIds.map((id) => availableAIs.find((ai) => ai.id === id)).filter(Boolean);
  if (!idea?.trim()) throw new Error("아이디어를 입력해 주세요.");
  if (!indexPage?.headline) throw new Error("확정된 Index Page가 필요합니다.");
  if (selected.length !== 3) throw new Error("AI 엔진을 정확히 3개 선택해 주세요.");

  return {
    idea,
    indexPage,
    outputs: await Promise.all(selected.map(async (ai, index) => {
      await sleep(80 + index * 50);
      return {
        aiId: ai.id,
        aiName: ai.name,
        provider: ai.provider,
        model: ai.model,
        fit: ai.fit,
        summary: `"${idea}"의 Index Page를 기준으로 5개의 작동 이미지와 1개의 데모 영상 시나리오를 구성합니다.`,
        deliverables: ["작동 이미지 5개", "데모 영상 시나리오", "영상 장면 구성표"]
      };
    }))
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
