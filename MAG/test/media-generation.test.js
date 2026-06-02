import test from "node:test";
import assert from "node:assert/strict";
import { listMediaGenerationAIs, runMediaGeneration } from "../server/media-generation.js";

test("media generation exposes five ranked AI engines", () => {
  const ais = listMediaGenerationAIs();
  assert.equal(ais.length, 5);
  assert.ok(ais[0].fit > ais[4].fit);
});

test("selected media engines receive final index page", async () => {
  const selectedAIIds = listMediaGenerationAIs().slice(0, 3).map((ai) => ai.id);
  const run = await runMediaGeneration({
    idea: "AI MVP builder",
    indexPage: { headline: "당신의 아이디어를 구체화 해 드립니다." },
    selectedAIIds
  });
  assert.equal(run.outputs.length, 3);
  assert.ok(run.outputs.every((output) => output.deliverables.includes("작동 이미지 5개")));
});
