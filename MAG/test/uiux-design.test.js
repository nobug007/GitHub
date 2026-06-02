import test from "node:test";
import assert from "node:assert/strict";
import { listUIUXAIs, mergeUIUXDesign, runUIUXDesign } from "../server/uiux-design.js";

test("uiux design exposes five ranked AI engines", () => {
  const ais = listUIUXAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected uiux engines receive edited workflow and return three outputs", async () => {
  const workflowDefinition = "편집된 WorkFlow: 관리자 검수 후 결과를 전달한다.";
  const selectedAIIds = listUIUXAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runUIUXDesign({
    idea: "AI MVP builder",
    workflowDefinition,
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /사용자가 편집한 WorkFlow/);
  assert.match(run.prompt, /관리자 검수 후 결과를 전달/);
  assert.ok(run.outputs.every((output) => output.content.includes("관리자 검수 후 결과를 전달")));
  assert.ok(run.outputs.every((output) => output.summary && output.screens.length === 4));
});

test("uiux merge returns one integrated screen composition", async () => {
  const selectedAIIds = listUIUXAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runUIUXDesign({
    idea: "AI MVP builder",
    workflowDefinition: "관리자 검수 후 결과를 전달한다.",
    selectedAIIds
  });
  const merged = mergeUIUXDesign({
    idea: run.idea,
    workflowDefinition: run.workflowDefinition,
    outputs: run.outputs
  });
  assert.match(merged.merged, /통합 UI \/ UX 화면 구성/);
  assert.match(merged.merged, /AI 엔진별 설계 관점/);
});
