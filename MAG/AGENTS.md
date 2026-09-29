<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-04 | Updated: 2026-07-04 -->

# MAG

## Purpose
"MAG AI MVP Builder" — an external-package-free development harness that walks a user from a raw idea to a validated feature set using multiple AI providers. Ships a working two-page flow: idea → AI-ranked problem definitions → merged/edited output.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Usage instructions, implemented pages, API routes, and provider config |
| `package.json` | Scripts: `dev`/`start` (runs `server/index.js`), `check` (syntax check key server files), `test` (`node --test`), `verify` (check + test) |
| `.env.example` | Template for AI provider keys (OpenAI, Gemini, Claude, OpenRouter, Groq) |
| `.env.ai` | Actual local provider keys (gitignored — do not commit) |
| `docs/ARCHITECTURE.md` | Architecture overview |
| `first-page.png` | Screenshot reference of Page 1 |
| `vercel.json` | Vercel deployment config |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `client/` | Static frontend: `index.html`, `app.js`, `styles.css` |
| `server/` | Node backend: `app.js` (routes), one module per pipeline stage (`business-analysis.js`, `feature-definition.js`, `persona-analysis.js`, `scenario-analysis.js`, `uiux-design.js`, `workflow-design.js`, `code-generation.js`, `media-generation.js`, `problem-definition.js`, `presentation-generation.js`), `pipeline/` (orchestrator), `providers/` (AI provider adapters) |
| `api/` | Thin Vercel entrypoint (`index.js`) wrapping the server |
| `test/` | `node --test` suite, one file per server module + `api.test.js`/`page-api.test.js`/`pipeline.test.js` |
| `docs/` | Architecture documentation |
| `deliverables/`, `outputs/` | Generated artifacts from pipeline runs — not source, safe to ignore for code changes |

## For AI Agents

### Working In This Directory
- No external frontend framework — `client/app.js` is hand-written vanilla JS; don't introduce a bundler/framework without discussing it first.
- Real AI provider calls are gated by `AI_PROVIDER_MODE=hybrid` and a sidebar toggle (default OFF); OFF always uses local fixture responses, so tests and local dev don't require API keys.
- Each pipeline stage in `server/*.js` is independent — mirror the existing pattern (module + matching `test/*.test.js`) when adding a new stage.

### Testing Requirements
- Run `npm run verify` (syntax check of `server/index.js`, `server/app.js`, `server/pipeline/orchestrator.js`, then the full `node --test` suite) before considering a change done.

### Common Patterns
- One file per pipeline stage in `server/`, one matching test file in `test/`.
- Provider status is exposed without leaking secrets via `GET /api/providers/status`.

## Dependencies

### Internal
- `server/pipeline/orchestrator.js` coordinates the per-stage modules in `server/`.
- `server/providers/` abstracts the external AI APIs used by the pipeline stages.

### External
- Node.js (`type: module`, ESM)
- OpenAI, Gemini, Claude, OpenRouter, Groq APIs (optional, hybrid mode only)
- Runway (async video generation for media pages)

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
