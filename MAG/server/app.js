import http from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { advanceRun, createRun, runToEnd } from "./pipeline/orchestrator.js";
import {
  listProblemDefinitionAIs,
  mergeProblemDefinition,
  runProblemDefinition
} from "./problem-definition.js";
import {
  listPersonaAIs,
  mergePersonaAnalysis,
  runPersonaAnalysis
} from "./persona-analysis.js";
import { listScenarioAIs, mergeScenarioAnalysis, runScenarioAnalysis } from "./scenario-analysis.js";
import { listFeatureAIs, mergeFeatureDefinition, runFeatureDefinition } from "./feature-definition.js";
import { listWorkflowAIs, mergeWorkflowDesign, runWorkflowDesign } from "./workflow-design.js";
import { listUIUXAIs, mergeUIUXDesign, runUIUXDesign } from "./uiux-design.js";
import { listCodeGenerationAIs, runIndexPageGeneration } from "./code-generation.js";
import { getMediaTask, getRunwayOrganizationStatus, listMediaGenerationAIs, runMediaGeneration } from "./media-generation.js";
import { listBusinessAnalysisAIs, mergeBusinessAnalysis, runBusinessAnalysis } from "./business-analysis.js";
import { listPresentationAIs, runPresentationGeneration } from "./presentation-generation.js";
import { getTextQuotaStatus, providerStatus } from "./providers/text.js";
import { providers, stages } from "./workflow.js";

const serverDir = fileURLToPath(new URL(".", import.meta.url));
const clientDir = normalize(join(serverDir, "..", "client"));
const deliverablesDir = normalize(join(serverDir, "..", "deliverables"));
const envFilePath = normalize(join(serverDir, "..", ".env.ai"));
const apiKeyFields = [
  "OPENAI_API_KEY",
  "GEMINI_API_KEY",
  "ANTHROPIC_API_KEY",
  "OPENROUTER_API_KEY",
  "OpenRouter_API_KEY",
  "GROQ_API_KEY",
  "RUNWAY_API_KEY",
  "SORA_API_KEY",
  "VEO_API_KEY"
];
const presentationFiles = [
  { name: "MAG Copilot Proposal", file: "MAG-Copilot-Proposal.pptx", description: "표준 사업 제안서형 PPT" },
  { name: "MAG Canva Proposal", file: "MAG-Canva-Proposal.pptx", description: "밝은 비주얼 중심 PPT" },
  { name: "MAG Gamma Proposal", file: "MAG-Gamma-Proposal.pptx", description: "스토리텔링 중심 PPT" }
];
const mediaFiles = [
  { name: "MAG Future MVP Demo", file: "MAG-Future-MVP-Demo.mp4", description: "최종 선택 영상 기반 10초 MVP 작동 데모" }
];
const deliverableFiles = [...presentationFiles, ...mediaFiles];

export function createApp() {
  const runs = new Map();

  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, `http://${req.headers.host || "127.0.0.1"}`);
      const routePath = normalizeRoutePath(url.pathname);

      if (req.method === "GET" && routePath === "/api/health") {
        return json(res, 200, { ok: true, providers: providers.length, stages: stages.length });
      }

      if (req.method === "GET" && routePath === "/api/providers/status") {
        return json(res, 200, providerStatus(
          { Runway: await getRunwayOrganizationStatus() },
          await getTextQuotaStatus()
        ));
      }

      if (req.method === "GET" && routePath === "/api/settings/keys") {
        return json(res, 200, await apiKeySettings());
      }

      if (req.method === "POST" && routePath === "/api/settings/keys") {
        return json(res, 200, await saveApiKeySettings(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/workflow") {
        return json(res, 200, { providers, stages });
      }

      if (req.method === "GET" && routePath === "/api/problem-definition/ais") {
        return json(res, 200, listProblemDefinitionAIs());
      }

      if (req.method === "POST" && routePath === "/api/problem-definition/run") {
        return json(res, 200, await runProblemDefinition(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/problem-definition/merge") {
        return json(res, 200, mergeProblemDefinition(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/persona/ais") {
        return json(res, 200, listPersonaAIs());
      }

      if (req.method === "POST" && routePath === "/api/persona/run") {
        return json(res, 200, await runPersonaAnalysis(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/persona/merge") {
        return json(res, 200, mergePersonaAnalysis(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/scenario/ais") {
        return json(res, 200, listScenarioAIs());
      }

      if (req.method === "POST" && routePath === "/api/scenario/run") {
        return json(res, 200, await runScenarioAnalysis(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/scenario/merge") {
        return json(res, 200, mergeScenarioAnalysis(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/features/ais") {
        return json(res, 200, listFeatureAIs());
      }

      if (req.method === "POST" && routePath === "/api/features/run") {
        return json(res, 200, await runFeatureDefinition(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/features/merge") {
        return json(res, 200, mergeFeatureDefinition(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/workflow-design/ais") {
        return json(res, 200, listWorkflowAIs());
      }

      if (req.method === "POST" && routePath === "/api/workflow-design/run") {
        return json(res, 200, await runWorkflowDesign(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/workflow-design/merge") {
        return json(res, 200, mergeWorkflowDesign(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/uiux-design/ais") {
        return json(res, 200, listUIUXAIs());
      }

      if (req.method === "POST" && routePath === "/api/uiux-design/run") {
        return json(res, 200, await runUIUXDesign(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/uiux-design/merge") {
        return json(res, 200, mergeUIUXDesign(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/code-generation/ais") {
        return json(res, 200, listCodeGenerationAIs());
      }

      if (req.method === "POST" && routePath === "/api/code-generation/index-page") {
        return json(res, 200, await runIndexPageGeneration(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/media-generation/ais") {
        return json(res, 200, listMediaGenerationAIs());
      }

      if (req.method === "POST" && routePath === "/api/media-generation/run") {
        return json(res, 200, await runMediaGeneration(await body(req)));
      }

      const mediaTaskMatch = routePath.match(/^\/api\/media-generation\/tasks\/([^/]+)$/);
      if (req.method === "GET" && mediaTaskMatch) {
        return json(res, 200, await getMediaTask(mediaTaskMatch[1]));
      }

      if (req.method === "GET" && routePath === "/api/business-analysis/ais") {
        return json(res, 200, listBusinessAnalysisAIs());
      }

      if (req.method === "POST" && routePath === "/api/business-analysis/run") {
        return json(res, 200, await runBusinessAnalysis(await body(req)));
      }

      if (req.method === "POST" && routePath === "/api/business-analysis/merge") {
        return json(res, 200, mergeBusinessAnalysis(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/presentation-generation/ais") {
        return json(res, 200, listPresentationAIs());
      }

      if (req.method === "POST" && routePath === "/api/presentation-generation/run") {
        return json(res, 200, await runPresentationGeneration(await body(req)));
      }

      if (req.method === "GET" && routePath === "/api/presentation-generation/files") {
        return json(res, 200, presentationFiles);
      }

      if (req.method === "GET" && routePath.startsWith("/deliverables/")) {
        return serveDeliverable(res, routePath);
      }

      if (req.method === "POST" && routePath === "/api/runs") {
        const run = createRun(await body(req));
        runs.set(run.id, run);
        return json(res, 201, run);
      }

      const match = routePath.match(/^\/api\/runs\/([^/]+)(?:\/([^/]+))?$/);
      if (match) {
        const [, id, action] = match;
        const run = runs.get(id);
        if (!run) return json(res, 404, { error: "Run not found" });
        if (req.method === "GET" && !action) return json(res, 200, run);
        if (req.method === "POST" && action === "advance") return json(res, 200, await advanceRun(run));
        if (req.method === "POST" && action === "auto") return json(res, 200, await runToEnd(run));
      }

      if (req.method === "GET") {
        return serveClient(res, routePath);
      }

      return json(res, 404, { error: "Not found" });
    } catch (error) {
      return json(res, 500, { error: error.message });
    }
  });
}

async function serveDeliverable(res, pathname) {
  const relativePath = decodeURIComponent(pathname.replace(/^\/deliverables\/+/, ""));
  if (!deliverableFiles.some((item) => item.file === relativePath)) return text(res, 404, "Not found");
  const filePath = normalize(join(deliverablesDir, relativePath));
  if (!filePath.startsWith(deliverablesDir)) return text(res, 403, "Forbidden");

  try {
    const content = await readFile(filePath);
    res.writeHead(200, {
      "content-type": extname(relativePath).toLowerCase() === ".mp4"
        ? "video/mp4"
        : "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "content-disposition": `attachment; filename="${relativePath}"`,
      "content-length": content.byteLength,
      "cache-control": "no-store"
    });
    res.end(content);
  } catch {
    return text(res, 404, "Not found");
  }
}

async function apiKeySettings() {
  const env = await readEnvSettings();
  return {
    fields: apiKeyFields.map((name) => ({
      name,
      configured: Boolean(env[name] || process.env[name]),
      masked: maskSecret(env[name] || process.env[name] || "")
    }))
  };
}

async function saveApiKeySettings(payload) {
  const updates = payload?.keys || {};
  const env = await readEnvSettings();
  for (const name of apiKeyFields) {
    if (!Object.prototype.hasOwnProperty.call(updates, name)) continue;
    const value = String(updates[name] || "").trim();
    if (!value) continue;
    env[name] = value;
    process.env[name] = value;
  }
  if (!env.PORT) env.PORT = process.env.PORT || "8787";
  if (!env.AI_PROVIDER_MODE) env.AI_PROVIDER_MODE = process.env.AI_PROVIDER_MODE || "hybrid";
  await writeEnvSettings(env);
  return { ok: true, ...(await apiKeySettings()) };
}

async function readEnvSettings() {
  const env = {};
  try {
    const content = await readFile(envFilePath, "utf8");
    for (const line of content.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const separator = trimmed.indexOf("=");
      if (separator < 1) continue;
      env[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim();
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  return env;
}

async function writeEnvSettings(env) {
  const ordered = ["PORT", "AI_PROVIDER_MODE", ...apiKeyFields];
  const lines = [
    "# Local secrets only. This file is ignored by Git.",
    ...ordered.map((name) => `${name}=${env[name] || ""}`)
  ];
  await writeFile(envFilePath, `${lines.join("\n")}\n`, "utf8");
}

function maskSecret(value) {
  if (!value) return "";
  if (value.length <= 10) return "*".repeat(value.length);
  return `${value.slice(0, 6)}...${value.slice(-4)}`;
}

function normalizeRoutePath(pathname) {
  for (const marker of ["/api/", "/deliverables/"]) {
    const markerIndex = pathname.indexOf(marker);
    if (markerIndex >= 0) return pathname.slice(markerIndex);
  }

  const trimmedPath = pathname.replace(/\/+$/, "");
  const fileName = trimmedPath.split("/").pop();
  if (["app.js", "styles.css", "index.html"].includes(fileName)) return `/${fileName}`;
  if (!fileName.includes(".")) return "/";
  if (pathname === "/" || pathname.endsWith("/")) return "/";
  return pathname;
}

async function serveClient(res, pathname) {
  const relativePath = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const filePath = normalize(join(clientDir, relativePath));

  if (!filePath.startsWith(clientDir)) return text(res, 403, "Forbidden");

  try {
    const content = await readFile(filePath);
    res.writeHead(200, { "content-type": contentType(filePath) });
    res.end(content);
  } catch {
    return text(res, 404, "Not found");
  }
}

async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {};
}

function json(res, status, data) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(data));
}

function text(res, status, data) {
  res.writeHead(status, { "content-type": "text/plain; charset=utf-8" });
  res.end(data);
}

function contentType(filePath) {
  if (extname(filePath) === ".html") return "text/html; charset=utf-8";
  if (extname(filePath) === ".css") return "text/css; charset=utf-8";
  if (extname(filePath) === ".js") return "text/javascript; charset=utf-8";
  return "application/octet-stream";
}
