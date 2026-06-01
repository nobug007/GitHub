import { randomUUID } from "node:crypto";
import { providers, stages } from "../workflow.js";
import { askMockProvider } from "../providers/mock.js";

export function createRun(input = {}) {
  const project = {
    idea: input.idea || "AI-based MVP builder",
    purpose: input.purpose || "proposal",
    audience: input.audience || "founders and proposal reviewers"
  };

  return {
    id: randomUUID(),
    status: "ready",
    createdAt: new Date().toISOString(),
    currentStageIndex: 0,
    project,
    currentPrompt: seedPrompt(project),
    stages: stages.map((stage) => ({
      ...stage,
      status: "pending",
      providers: providers.map((provider) => ({
        ...provider,
        status: "pending",
        output: ""
      })),
      merge: { status: "pending", summary: "", nextPrompt: "" }
    })),
    finalResult: null
  };
}

export async function advanceRun(run) {
  if (run.status === "complete") return run;
  const stage = run.stages[run.currentStageIndex];
  if (!stage) return completeRun(run);

  run.status = "running";
  stage.status = "running";
  stage.providers.forEach((provider) => {
    provider.status = "running";
  });

  const outputs = await Promise.all(stage.providers.map((provider) =>
    askMockProvider({
      provider,
      stage,
      prompt: run.currentPrompt,
      project: run.project,
      stageIndex: run.currentStageIndex
    })
  ));

  outputs.forEach((output, index) => {
    stage.providers[index].status = "complete";
    stage.providers[index].output = output;
  });

  stage.merge.status = "running";
  const merged = mergeOutputs({ stage, outputs, run });
  stage.merge = { status: "complete", ...merged };
  stage.status = "complete";
  run.currentPrompt = merged.nextPrompt;
  run.currentStageIndex += 1;

  return run.currentStageIndex >= run.stages.length
    ? completeRun(run)
    : Object.assign(run, { status: "ready" });
}

export async function runToEnd(run) {
  while (run.status !== "complete") await advanceRun(run);
  return run;
}

function mergeOutputs({ stage, outputs, run }) {
  const recommendations = outputs.map((output) =>
    output.split("\n").find((line) => line.startsWith("Recommendation:"))
  );
  const summary = [
    `${stage.title} merged output`,
    ...recommendations.map((item, index) => `${index + 1}. ${item}`)
  ].join("\n");

  return {
    summary,
    nextPrompt: [
      "Continue the AI MVP builder pipeline.",
      `Project: ${run.project.idea}`,
      `Completed stage: ${stage.title}`,
      summary,
      "Use this merged context to produce a concrete, proposal-ready next-stage output."
    ].join("\n\n")
  };
}

function completeRun(run) {
  run.status = "complete";
  run.completedAt = new Date().toISOString();
  run.finalResult = {
    title: `${run.project.idea} delivery package`,
    files: [
      "mvp-demo.html",
      "business-report.md",
      "pitch-outline.md",
      "workflow.json",
      "final-prompt.txt"
    ]
  };
  return run;
}

function seedPrompt(project) {
  return [
    "Convert the user's idea into a proposal-ready MVP package.",
    `Idea: ${project.idea}`,
    `Purpose: ${project.purpose}`,
    `Audience: ${project.audience}`
  ].join("\n");
}

