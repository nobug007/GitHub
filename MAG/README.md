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

