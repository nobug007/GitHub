import test from "node:test";
import assert from "node:assert/strict";
import {
  listPersonaAIs,
  mergePersonaAnalysis,
  runPersonaAnalysis
} from "../server/persona-analysis.js";

test("persona analysis exposes five ranked AIs", () => {
  const ais = listPersonaAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
  assert.equal(ais[0].name, "OpenAI GPT-5.2");
  assert.ok(ais.every((ai) => ai.provider && ai.model && ai.connection));
});

test("selected persona AIs return three outputs and merge", async () => {
  const selectedAIIds = listPersonaAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runPersonaAnalysis({
    idea: "AI MVP builder",
    problemDefinition: "비개발자의 아이디어를 실제 MVP 기획으로 구체화한다.",
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /앞 단계에서 사용자가 편집한 문제 정의/);
  assert.match(run.prompt, /핵심 타겟/);
  assert.match(run.prompt, /타겟 선정 이유/);
  assert.match(run.prompt, /대표 페르소나/);
  assert.match(run.prompt, /JSON 형식으로만 답변/);
  assert.ok(run.outputs.every((output) => output.provider && output.model));
  assert.ok(run.outputs.every((output) => JSON.parse(output.content).persona));

  const merged = mergePersonaAnalysis({
    idea: run.idea,
    problemDefinition: run.problemDefinition,
    outputs: run.outputs
  });
  assert.match(merged.merged, /핵심 타겟 정의/);
  assert.match(merged.merged, /타겟 세그먼트/);
  assert.match(merged.merged, /대표 페르소나 정의/);
  assert.match(merged.merged, /다음 단계 입력/);
});

test("persona AI servers receive the edited merged content", async () => {
  const editedProblemDefinition = "수정된 취합문: 대학생 창업자가 지원사업 제출 전에 아이디어를 검증하도록 돕는다.";
  const selectedAIIds = listPersonaAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runPersonaAnalysis({
    idea: "AI MVP builder",
    problemDefinition: editedProblemDefinition,
    selectedAIIds
  });

  assert.match(run.prompt, /대학생 창업자/);
  assert.ok(run.outputs.every((output) => JSON.parse(output.content).coreTarget.includes("창업자")));
});

test("persona merge uses edited AI server answers", async () => {
  const selectedAIIds = listPersonaAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runPersonaAnalysis({
    idea: "AI MVP builder",
    problemDefinition: "비개발자의 아이디어를 실제 MVP 기획으로 구체화한다.",
    selectedAIIds
  });
  run.outputs[0].content = "수정된 페르소나: 정부지원사업 마감이 임박한 1인 창업자";

  const merged = mergePersonaAnalysis({
    idea: run.idea,
    problemDefinition: run.problemDefinition,
    outputs: run.outputs
  });
  assert.match(merged.merged, /정부지원사업 마감이 임박한 1인 창업자/);
});

test("different project inputs create different target personas", async () => {
  const selectedAIIds = listPersonaAIs().slice(0, 3).map((ai) => ai.id);
  const startup = await runPersonaAnalysis({
    idea: "지원사업용 AI MVP 제작 서비스",
    problemDefinition: "예비 창업자가 제출 자료를 빠르게 작성하도록 돕는다.",
    selectedAIIds
  });
  const tourism = await runPersonaAnalysis({
    idea: "지역 소상공인 관광 큐레이션 서비스",
    problemDefinition: "관광객이 취향에 맞는 지역 상점을 찾도록 돕는다.",
    selectedAIIds
  });

  const startupPersona = JSON.parse(startup.outputs[0].content).persona;
  const tourismPersona = JSON.parse(tourism.outputs[0].content).persona;
  assert.notEqual(startupPersona.role, tourismPersona.role);
  assert.match(startupPersona.role, /창업자/);
  assert.match(tourismPersona.role, /방문객/);
});
