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

    const scenarioAIs = await fetch(`${base}/api/scenario/ais`).then((res) => res.json());
    assert.equal(scenarioAIs.length, 5);

    const scenarioRun = await fetch(`${base}/api/scenario/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: run.idea,
        personaDefinition: personaMerged.merged,
        selectedAIIds: scenarioAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(scenarioRun.outputs.length, 3);

    const scenarioMerged = await fetch(`${base}/api/scenario/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: scenarioRun.idea,
        personaDefinition: scenarioRun.personaDefinition,
        outputs: scenarioRun.outputs
      })
    }).then((res) => res.json());
    assert.match(scenarioMerged.merged, /사용자 시나리오/);

    const featureAIs = await fetch(`${base}/api/features/ais`).then((res) => res.json());
    assert.equal(featureAIs.length, 5);

    const featureRun = await fetch(`${base}/api/features/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: scenarioRun.idea,
        scenarioDefinition: scenarioMerged.merged,
        selectedAIIds: featureAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(featureRun.outputs.length, 3);

    const featureMerged = await fetch(`${base}/api/features/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: featureRun.idea,
        scenarioDefinition: featureRun.scenarioDefinition,
        outputs: featureRun.outputs
      })
    }).then((res) => res.json());
    assert.match(featureMerged.merged, /핵심 기능 정의/);

    const workflowAIs = await fetch(`${base}/api/workflow-design/ais`).then((res) => res.json());
    assert.equal(workflowAIs.length, 5);

    const workflowRun = await fetch(`${base}/api/workflow-design/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: featureRun.idea,
        featureDefinition: featureMerged.merged,
        selectedAIIds: workflowAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(workflowRun.outputs.length, 3);

    const workflowMerged = await fetch(`${base}/api/workflow-design/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: workflowRun.idea,
        featureDefinition: workflowRun.featureDefinition,
        outputs: workflowRun.outputs
      })
    }).then((res) => res.json());
    assert.match(workflowMerged.merged, /WorkFlow 구성/);

    const uiuxAIs = await fetch(`${base}/api/uiux-design/ais`).then((res) => res.json());
    assert.equal(uiuxAIs.length, 5);

    const uiuxRun = await fetch(`${base}/api/uiux-design/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: workflowRun.idea,
        workflowDefinition: workflowMerged.merged,
        selectedAIIds: uiuxAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(uiuxRun.outputs.length, 3);

    const uiuxMerged = await fetch(`${base}/api/uiux-design/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: uiuxRun.idea,
        workflowDefinition: uiuxRun.workflowDefinition,
        outputs: uiuxRun.outputs
      })
    }).then((res) => res.json());
    assert.match(uiuxMerged.merged, /통합 UI \/ UX 화면 구성/);

    const codeAIs = await fetch(`${base}/api/code-generation/ais`).then((res) => res.json());
    assert.equal(codeAIs.length, 5);

    const indexPages = await fetch(`${base}/api/code-generation/index-page`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: uiuxRun.idea,
        uiuxDefinition: uiuxMerged.merged,
        selectedAIIds: codeAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(indexPages.outputs.length, 3);
    assert.ok(indexPages.outputs.every((output) => output.pageType === "index"));

    const mediaAIs = await fetch(`${base}/api/media-generation/ais`).then((res) => res.json());
    assert.equal(mediaAIs.length, 5);

    const mediaRun = await fetch(`${base}/api/media-generation/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: uiuxRun.idea,
        indexPage: indexPages.outputs[0].preview,
        selectedAIIds: mediaAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(mediaRun.outputs.length, 3);

    const businessAIs = await fetch(`${base}/api/business-analysis/ais`).then((res) => res.json());
    assert.equal(businessAIs.length, 5);

    const businessRun = await fetch(`${base}/api/business-analysis/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: uiuxRun.idea,
        indexPage: indexPages.outputs[0].preview,
        selectedVideo: mediaRun.outputs[0],
        selectedAIIds: businessAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(businessRun.outputs.length, 3);

    const businessMerged = await fetch(`${base}/api/business-analysis/merge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: businessRun.idea,
        outputs: businessRun.outputs
      })
    }).then((res) => res.json());
    assert.match(businessMerged.merged, /최종 사업성 분석 리포트/);

    const presentationAIs = await fetch(`${base}/api/presentation-generation/ais`).then((res) => res.json());
    assert.equal(presentationAIs.length, 5);

    const presentationRun = await fetch(`${base}/api/presentation-generation/run`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        idea: businessRun.idea,
        businessReport: businessMerged.merged,
        selectedAIIds: presentationAIs.slice(0, 3).map((ai) => ai.id)
      })
    }).then((res) => res.json());
    assert.equal(presentationRun.outputs.length, 3);

    const presentationFiles = await fetch(`${base}/api/presentation-generation/files`).then((res) => res.json());
    assert.equal(presentationFiles.length, 3);

    const pptx = await fetch(`${base}/deliverables/${presentationFiles[0].file}`);
    assert.equal(pptx.status, 200);
    assert.match(pptx.headers.get("content-type"), /presentationml/);
    assert.ok((await pptx.arrayBuffer()).byteLength > 1000);
  } finally {
    server.close();
  }
});
