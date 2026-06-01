import test from "node:test";
import assert from "node:assert/strict";
import { advanceRun, createRun, runToEnd } from "../server/pipeline/orchestrator.js";

test("advanceRun completes one stage and forwards a merged prompt", async () => {
  const run = createRun({ idea: "AI MVP proposal builder" });
  await advanceRun(run);
  assert.equal(run.currentStageIndex, 1);
  assert.equal(run.stages[0].status, "complete");
  assert.match(run.currentPrompt, /Completed stage: Input analysis/);
});

test("runToEnd returns a delivery package", async () => {
  const run = createRun({ idea: "AI MVP proposal builder" });
  await runToEnd(run);
  assert.equal(run.status, "complete");
  assert.equal(run.stages.every((stage) => stage.status === "complete"), true);
  assert.equal(run.finalResult.files.includes("business-report.md"), true);
});

