import test from "node:test";
import assert from "node:assert/strict";
import { listPresentationAIs, runPresentationGeneration } from "../server/presentation-generation.js";

test("presentation generation exposes five ranked PPT AIs", () => {
  const ais = listPresentationAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected PPT AIs receive edited final report and return three drafts", async () => {
  const businessReport = "편집된 최종 리포트: 공공기관 B2B 라이선스를 우선 검증한다.";
  const selectedAIIds = listPresentationAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runPresentationGeneration({
    idea: "AI MVP builder",
    businessReport,
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /공공기관 B2B 라이선스/);
  assert.ok(run.outputs.every((output) => output.content.includes("PPT 초안")));
});
