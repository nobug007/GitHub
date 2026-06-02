import test from "node:test";
import assert from "node:assert/strict";
import { listFeatureAIs, mergeFeatureDefinition, runFeatureDefinition } from "../server/feature-definition.js";

test("feature definition exposes five ranked AIs", () => {
  const ais = listFeatureAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected feature AIs receive edited scenario and return three outputs", async () => {
  const scenarioDefinition = "편집된 시나리오: 사용자는 결과를 내려받아 지원사업에 제출한다.";
  const selectedAIIds = listFeatureAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runFeatureDefinition({
    idea: "AI MVP builder",
    scenarioDefinition,
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /사용자가 편집한 사용자 시나리오/);
  assert.match(run.prompt, /지원사업에 제출/);
  assert.ok(run.outputs.every((output) => output.content.includes("지원사업에 제출")));
});

test("feature merge uses edited AI answers", () => {
  const merged = mergeFeatureDefinition({
    idea: "AI MVP builder",
    scenarioDefinition: "사용자는 결과를 검토하고 제출한다.",
    outputs: [
      { aiName: "AI A", content: "수정된 기능: 제출용 PPT 자동 생성" },
      { aiName: "AI B", content: "결과 편집" },
      { aiName: "AI C", content: "결과 저장" }
    ]
  });
  assert.match(merged.merged, /제출용 PPT 자동 생성/);
  assert.match(merged.merged, /WorkFlow/);
});
