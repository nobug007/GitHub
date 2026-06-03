# MAG AI MVP Builder

External-package-free development harness with the first two working pages.

## Start

```bash
npm run verify
npm run dev
```

Open:

```text
http://127.0.0.1:8787
```

## Implemented Pages

### Page 1

- Bright, minimal, function-first layout
- Central title: `당신의 아이디어를 구체화 해 드립니다.`
- Idea input
- Five ranked problem-definition AIs
- Select exactly three AIs
- Execute button

### Page 2

- Three editable AI outputs
- Submit to merge the edited outputs
- Editable merged answer
- Submit to the third-page placeholder

## API

```text
GET  /api/problem-definition/ais
POST /api/problem-definition/run
POST /api/problem-definition/merge
```

The original multi-stage harness API remains available under `/api/runs`.

## Real AI providers

Copy `.env.example` to `.env.ai`, add local keys, and start the server normally. `AI_PROVIDER_MODE=hybrid` uses configured OpenAI, Gemini, Claude, OpenRouter, and Groq text APIs and falls back to local harness responses when a key is missing or a provider is temporarily unavailable.

The sidebar connection toggle defaults to OFF. OFF always uses local test responses without calling external APIs. ON attempts configured real APIs and keeps the local fallback for unavailable providers.

For media generation, ON starts asynchronous Runway video tasks for the selected Runway-backed models. The media page shows task status, lets the user refresh completed tasks, and exposes the actual generated video for playback and download. OFF keeps the local motion preview.

Provider status can be checked without exposing secrets:

```text
GET /api/providers/status
```
