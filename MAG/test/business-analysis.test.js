import test from "node:test";
import assert from "node:assert/strict";
import { listBusinessAnalysisAIs, mergeBusinessAnalysis, runBusinessAnalysis } from "../server/business-analysis.js";

test("business analysis exposes five ranked AI servers", () => {
  const ais = listBusinessAnalysisAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected business AIs receive selected video and return three reports", async () => {
  const selectedAIIds = listBusinessAnalysisAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runBusinessAnalysis({
    idea: "AI MVP builder",
    indexPage: { headline: "당신의 아이디어를 구체화 해 드립니다." },
    selectedVideo: { aiName: "OpenAI Sora 2", summary: "미래형 제품 필름" },
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /OpenAI Sora 2/);
  assert.ok(run.outputs.every((output) => output.content.includes("BM / 수익 구조")));
  assert.ok(run.outputs.every((output) => output.content.includes("시장성")));
});

test("business analysis merge uses edited reports", () => {
  const merged = mergeBusinessAnalysis({
    idea: "AI MVP builder",
    outputs: [
      { aiName: "AI A", content: "수정된 분석: 공공기관 B2B 라이선스를 우선 검증한다." },
      { aiName: "AI B", content: "구독형 모델을 검증한다." },
      { aiName: "AI C", content: "AI 호출 비용을 관리한다." }
    ]
  });
  assert.match(merged.merged, /공공기관 B2B 라이선스/);
  assert.match(merged.merged, /PPT 작성 방향/);
});
