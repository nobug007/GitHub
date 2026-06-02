import test from "node:test";
import assert from "node:assert/strict";
import { listScenarioAIs, mergeScenarioAnalysis, runScenarioAnalysis } from "../server/scenario-analysis.js";

test("scenario analysis exposes five ranked AIs", () => {
  const ais = listScenarioAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
  assert.ok(ais.every((ai) => ai.provider && ai.model));
});

test("selected scenario AIs receive edited persona content and return three scenarios", async () => {
  const personaDefinition = "편집된 페르소나: 지원사업 마감이 임박한 1인 창업자";
  const selectedAIIds = listScenarioAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runScenarioAnalysis({
    idea: "AI MVP builder",
    personaDefinition,
    selectedAIIds
  });

  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /사용자가 편집한 타겟 및 페르소나 정의/);
  assert.match(run.prompt, /지원사업 마감이 임박한 1인 창업자/);
  assert.ok(run.outputs.every((output) => output.content.includes("지원사업 마감이 임박한 1인 창업자")));
  assert.ok(run.outputs.every((output) => output.content.includes("사용 후 변화")));
});

test("scenario merge uses edited AI answers", async () => {
  const outputs = [
    { aiName: "AI A", content: "수정된 시나리오: 사용자는 결과를 내려받아 지원사업에 제출한다." },
    { aiName: "AI B", content: "결과를 확인한다." },
    { aiName: "AI C", content: "후속 작업을 진행한다." }
  ];
  const merged = mergeScenarioAnalysis({
    idea: "AI MVP builder",
    personaDefinition: "지원사업 제출이 급한 1인 창업자",
    outputs
  });
  assert.match(merged.merged, /지원사업에 제출/);
  assert.match(merged.merged, /다음 단계 입력/);
});
