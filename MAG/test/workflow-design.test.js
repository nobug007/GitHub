import test from "node:test";
import assert from "node:assert/strict";
import { listWorkflowAIs, mergeWorkflowDesign, runWorkflowDesign } from "../server/workflow-design.js";

test("workflow design exposes five ranked AIs", () => {
  const ais = listWorkflowAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected workflow AIs receive edited features and return three flows", async () => {
  const featureDefinition = "편집된 핵심 기능: 제출용 PPT 자동 생성";
  const selectedAIIds = listWorkflowAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runWorkflowDesign({
    idea: "AI MVP builder",
    featureDefinition,
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /사용자가 편집한 핵심 기능 정의/);
  assert.match(run.prompt, /제출용 PPT 자동 생성/);
  assert.ok(run.outputs.every((output) => output.content.includes("Mermaid flowchart")));
});

test("workflow merge uses edited AI answers", () => {
  const merged = mergeWorkflowDesign({
    idea: "AI MVP builder",
    featureDefinition: "프로젝트 입력과 결과 다운로드",
    outputs: [
      { aiName: "AI A", content: "수정된 WorkFlow: 관리자 검수 후 결과를 전달한다." },
      { aiName: "AI B", content: "답변을 취합한다." },
      { aiName: "AI C", content: "사용자가 결과를 편집한다." }
    ]
  });
  assert.match(merged.merged, /관리자 검수 후 결과를 전달/);
  assert.match(merged.merged, /UI 요소/);
});
