import test from "node:test";
import assert from "node:assert/strict";
import {
  listProblemDefinitionAIs,
  mergeProblemDefinition,
  runProblemDefinition
} from "../server/problem-definition.js";

test("problem definition exposes five ranked AIs", () => {
  const ais = listProblemDefinitionAIs();
  assert.equal(ais.length, 5);
  assert.equal(ais[0].fit > ais[4].fit, true);
});

test("selected three AIs return editable outputs and merge", async () => {
  const ais = listProblemDefinitionAIs();
  const result = await runProblemDefinition({
    idea: "AI MVP builder",
    selectedAIIds: ais.slice(0, 3).map((ai) => ai.id)
  });
  assert.equal(result.outputs.length, 3);
  const merged = mergeProblemDefinition({ idea: result.idea, outputs: result.outputs });
  assert.match(merged.merged, /문제 정의/);
  assert.match(merged.merged, /다음 단계 입력/);
});

test("problem definition merge uses edited AI answers", async () => {
  const ais = listProblemDefinitionAIs();
  const result = await runProblemDefinition({
    idea: "AI MVP builder",
    selectedAIIds: ais.slice(0, 3).map((ai) => ai.id)
  });
  result.outputs[0].content = "수정된 답변: 지원사업 제출이 급한 예비 창업자에게 집중한다.";

  const merged = mergeProblemDefinition({ idea: result.idea, outputs: result.outputs });
  assert.match(merged.merged, /지원사업 제출이 급한 예비 창업자/);
});
