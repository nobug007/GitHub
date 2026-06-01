import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../server/app.js";

test("serves the first page and runs the five-page API flow", async () => {
  const server = createApp().listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address();
  const base = `http://127.0.0.1:${port}`;

  try {
    const page = await fetch(`${base}/`);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /MAG/);

    const ais = await fetch(`${base}/api/problem-definition/ais`).then((res) => res.json());
    assert.equal(ais.length, 5);

    const run = await fetch(`${base}/api/problem-definition/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: "AI MVP builder",
        selectedAIIds: ais.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(run.outputs.length, 3);

    const merged = await fetch(`${base}/api/problem-definition/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ idea: run.idea, outputs: run.outputs })
    }).then((res) => res.json());
    assert.match(merged.merged, /구체화된 목표/);

    const personaAIs = await fetch(`${base}/api/persona/ais`).then((res) => res.json());
    assert.equal(personaAIs.length, 5);

    const personaRun = await fetch(`${base}/api/persona/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: run.idea,
        problemDefinition: merged.merged,
        selectedAIIds: personaAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(personaRun.outputs.length, 3);

    const personaMerged = await fetch(`${base}/api/persona/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: personaRun.idea,
        problemDefinition: personaRun.problemDefinition,
        outputs: personaRun.outputs
      })
    }).then((res) => res.json());
    assert.match(personaMerged.merged, /핵심 타겟/);
  } finally {
    server.close();
  }
});
