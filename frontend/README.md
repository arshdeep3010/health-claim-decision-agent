# Claim Decision Agent — demo UI

React 19 + TypeScript + Vite 6 + Motion front end for the Health Claim Decision
Agent backend. It walks through one claim decision stage by stage, drawing each
stage as a 3D check driven by live Server-Sent Events from the backend.

**Synthetic data only.** Not insurance authorization or medical advice.

## Run

The backend must be running on `:8080` (dev profile). Vite proxies `/api` and
`/actuator` to it.

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # type-check + production build into dist/
npm run lint
npm test           # Vitest unit tests (stream parser, stage mapping and pacing, formatting)
```

Sign in with the one-click demo accounts (`admin` / `reviewer` / `user`). They exist
only on the backend's `dev` profile; remove `src/demoAccounts.ts` before any public
deployment.

## How the pipeline view works

`POST /api/v1/claims/{id}/analyze/stream` sends one `stage` event per stage
transition (`AUTH`, `LOAD`, `RETRIEVE`, `RULES`, `AI`, `GUARDRAILS`, `PERSIST`), then
a final `decision` or `error` event.

- `src/api.ts` — typed REST client; `analyzeStream()` reads the SSE stream with
  `fetch` + `ReadableStream`, because `EventSource` cannot send the bearer token.
- `src/streamStages.ts` — maps server events to what each row shows (`finalStatus`)
  and paces them (`StagePacer`): each stage stays visibly running for at least
  ~0.5 s so sub-millisecond stages can be followed. Durations shown are the server's
  own measurements; pacing never changes a result.
- `src/pipeline.ts` — the seven stages, their descriptions and technology chips.

## Layout

| File | What it is |
|------|------------|
| `src/App.tsx` | hash routes (`#/`, `#/claims`, `#/claims/<id>`), nav, backend health dot |
| `src/components/Landing.tsx` | overview: how a claim is decided, guardrails, stack |
| `src/components/ClaimsView.tsx` | searchable, filterable claim list |
| `src/components/PoliciesView.tsx` | policy rulebook: terms the Java rules check + indexed wording from pgvector |
| `src/components/ClaimView.tsx` | claim detail: pipeline, verdict, what the AI compared, evidence, explanation, review, audit |
| `src/components/Pipeline.tsx` / `Cube.tsx` | stage rows and the 3D check |
| `src/index.css` | design tokens, dark/light themes, reduced-motion and reduced-transparency rules |
