import test from "node:test";
import assert from "node:assert/strict";
import { listCodeGenerationAIs, runIndexPageGeneration } from "../server/code-generation.js";

test("code generation exposes five ranked AI servers", () => {
  const ais = listCodeGenerationAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected code servers receive finalized uiux and return three index pages only", async () => {
  const uiuxDefinition = "확정된 UI UX: 밝은 화면, 좌측 메뉴, 아이디어 입력, AI 선택, 실행 버튼";
  const selectedAIIds = listCodeGenerationAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runIndexPageGeneration({
    idea: "AI MVP builder",
    uiuxDefinition,
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.match(run.prompt, /Index Page 하나만/);
  assert.match(run.prompt, /좌측 메뉴/);
  assert.ok(run.outputs.every((output) => output.pageType === "index"));
  assert.ok(run.outputs.every((output) => output.preview.headline));
});
