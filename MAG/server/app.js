import http from "node:http";
import { readFile } from "node:fs/promises";
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
import { providers, stages } from "./workflow.js";

const serverDir = fileURLToPath(new URL(".", import.meta.url));
const clientDir = normalize(join(serverDir, "..", "client"));

export function createApp() {
  const runs = new Map();

  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, `http://${req.headers.host || "127.0.0.1"}`);

      if (req.method === "GET" && url.pathname === "/api/health") {
        return json(res, 200, { ok: true, providers: providers.length, stages: stages.length });
      }

      if (req.method === "GET" && url.pathname === "/api/workflow") {
        return json(res, 200, { providers, stages });
      }

      if (req.method === "GET" && url.pathname === "/api/problem-definition/ais") {
        return json(res, 200, listProblemDefinitionAIs());
      }

      if (req.method === "POST" && url.pathname === "/api/problem-definition/run") {
        return json(res, 200, await runProblemDefinition(await body(req)));
      }

      if (req.method === "POST" && url.pathname === "/api/problem-definition/merge") {
        return json(res, 200, mergeProblemDefinition(await body(req)));
      }

      if (req.method === "GET" && url.pathname === "/api/persona/ais") {
        return json(res, 200, listPersonaAIs());
      }

      if (req.method === "POST" && url.pathname === "/api/persona/run") {
        return json(res, 200, await runPersonaAnalysis(await body(req)));
      }

      if (req.method === "POST" && url.pathname === "/api/persona/merge") {
        return json(res, 200, mergePersonaAnalysis(await body(req)));
      }

      if (req.method === "POST" && url.pathname === "/api/runs") {
        const run = createRun(await body(req));
        runs.set(run.id, run);
        return json(res, 201, run);
      }

      const match = url.pathname.match(/^\/api\/runs\/([^/]+)(?:\/([^/]+))?$/);
      if (match) {
        const [, id, action] = match;
        const run = runs.get(id);
        if (!run) return json(res, 404, { error: "Run not found" });
        if (req.method === "GET" && !action) return json(res, 200, run);
        if (req.method === "POST" && action === "advance") return json(res, 200, await advanceRun(run));
        if (req.method === "POST" && action === "auto") return json(res, 200, await runToEnd(run));
      }

      if (req.method === "GET") {
        return serveClient(res, url.pathname);
      }

      return json(res, 404, { error: "Not found" });
    } catch (error) {
      return json(res, 500, { error: error.message });
    }
  });
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
