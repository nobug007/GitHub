# Harness Architecture

## Product Direction

The user enters an MVP idea in one or two lines. Every stage asks three AI
providers for different viewpoints, merges the outputs, and forwards one
improved prompt into the next stage.

## Pipeline

```text
User idea
-> Founder AI + Market AI + Product AI
-> Merge AI
-> next-stage prompt
-> repeat
-> final delivery package
```

## Boundaries

- `server/workflow.js`: canonical stage and provider metadata
- `server/providers/mock.js`: replaceable mock provider adapter
- `server/pipeline/orchestrator.js`: run state and merge loop
- `server/app.js`: HTTP API boundary
- `test/`: contract and smoke tests
- `client/`: reserved for the React frontend

## Initial Stages

1. Input analysis
2. Problem definition and goals
3. Target and persona analysis
4. User scenario
5. Core feature definition
6. Workflow composition
7. UI/UX screen composition
8. Code generation and deployment plan
9. Working-image capture and video plan
10. Business report
11. BM, market, technology, profitability, and risk analysis
12. Similar-service comparison
13. Admin review
14. Delivery package

