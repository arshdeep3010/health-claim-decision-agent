# Architecture — Health Claim Decision Agent

> **SYNTHETIC DATA ONLY.** AI-assisted, evidence-grounded claim *decision support*.
> Not a real insurance authorization system, not medical advice.

This document explains how the system is built: the principles, the module map, the
runtime flows, the data model, the AI/provider abstractions, and the cross-cutting
concerns (security, observability, resilience, deployment). For the illustrated
version with Mermaid diagrams see [`PROJECT.md`](PROJECT.md).

---

## 1. What it does (in one paragraph)

A synthetic health-insurance **claim** is submitted. The system extracts structured
**facts** from it with an LLM (schema-constrained, validated), retrieves the most
relevant **policy clauses** from ingested policy documents via **RAG** (PostgreSQL +
pgvector, NVIDIA NIM embeddings), runs **deterministic business rules**, and — only
when the rules raise no hard objection — asks an **AI decision layer** (Mercury) for
a semantic judgment. A Java **orchestrator** fuses rules + AI into a final, auditable
decision: **APPROVE / REJECT / HUMAN_REVIEW**. Each analysis can be **streamed live**
stage by stage (Server-Sent Events) to the bundled React demo UI. Humans can
override; every run is recorded in an append-only **audit trail**. An LLM can produce
a human-readable **explanation** of a finished decision, but can never change it.

---

## 2. Design principles

1. **Java owns the verdict; the AI only assists.** Hard constraints (expired policy,
   excluded procedure, waiting period, missing/conflicting info) are deterministic.
   `REJECT` is *always* rule-driven; the AI can only move an otherwise-allowable claim
   to `APPROVE` or `HUMAN_REVIEW`, never auto-reject/approve against caution.
2. **Fail safe to a human.** Any AI failure, RAG failure, low confidence, or ambiguity
   routes to `HUMAN_REVIEW` — never a silent approve/reject.
3. **Evidence-grounded & auditable.** Every policy-based outcome is traceable to the
   retrieved chunk (document, page, section, similarity), snapshotted with the
   decision so it survives re-ingestion. Every analysis writes an audit record.
4. **Providers behind interfaces.** Chat LLM, decision LLM, embeddings, and document
   storage are all interfaces chosen by configuration — swappable without touching
   business logic, and runnable offline with zero API keys for dev/CI.
5. **Never trust model output.** Structured output is schema-constrained *and*
   validated/normalized in Java; confidence is kept only if the model actually
   returned it (never fabricated); embedding dimensions are checked on every call.
6. **Short DB transactions.** Slow work (PDF parsing, embeddings, AI calls) happens
   *outside* transactions; only quick load/persist steps are transactional.
7. **Observation never changes the outcome.** The live stage stream is a listener on
   the orchestrator; a failing listener is swallowed and cannot alter a decision.
8. **Stateless & modular monolith.** One deployable Spring Boot app, cleanly packaged
   by domain, stateless (JWT, object storage) so it scales horizontally and deploys as
   a container.

---

## 3. Technology stack

| Area | Choice | Why |
|------|--------|-----|
| Language / runtime | Java 21 (built on JDK 25, `--release 21`) | LTS, records/sealed/switch patterns |
| Framework | Spring Boot 3.5.x | Web, Validation, Data JPA, Security, Actuator |
| Database | PostgreSQL 16 + **pgvector** | relational + vector search in one store |
| Migrations | Flyway (`V1`–`V4`) | versioned schema, `ddl-auto=validate` (never auto-DDL) |
| RAG | pgvector cosine `<=>`, HNSW index | semantic retrieval with traceability |
| Chat / decision AI | **Mercury 2.5** (`inception/mercury-2.5`) via OpenRouter | fast diffusion LLM, strict JSON schema output |
| Embeddings | **NVIDIA NIM** `nvidia/nemotron-3.5-embed-1b` (OpenAI-compatible), **or** local deterministic | semantic retrieval; offline dev/CI |
| Streaming | Spring MVC `SseEmitter` + dedicated executor | live per-stage progress |
| Resilience | Resilience4j (chat); bounded retry (embeddings) | timeout + retry + circuit breaker |
| PDF | Apache PDFBox | policy-document text extraction |
| Security | Spring Security + JWT (jjwt, HMAC-SHA) | stateless auth, RBAC |
| Observability | Micrometer + Prometheus, structured JSON logs | metrics + traceable logs |
| Storage | Local FS (dev) / **S3-compatible** (deploy) | ephemeral-FS-safe object storage |
| Demo UI | React 19 + TypeScript + Vite 6 + Motion | stage-by-stage pipeline view |
| Testing | JUnit 5, Mockito, Testcontainers, MockWebServer, MockRestServiceServer, LocalStack | real deps, no paid APIs |
| Build / ship | Maven (`./mvnw`), multi-stage Docker, GitHub Actions, AWS ECR + Lambda | reproducible, portable |

---

## 4. Container & module view

Everything runs inside one Spring Boot application, organized by domain package under
`com.healthclaim`. The demo UI is a separate static front end that talks to the same
REST API.

```
     React demo UI (frontend/, Vite :5173)       Swagger UI / any HTTP client
                 │  REST + SSE (fetch stream), Bearer JWT       │
                 └───────────────────────┬──────────────────────┘
                                         │
                         ┌───────────────┴───────────────┐
                         │        Controllers            │   (thin; no business logic)
                         │  claim · policy · review ·    │
                         │  decision (+ /analyze/stream) │
                         │  rag · audit · auth/admin     │
                         └───────────────┬───────────────┘
                                         │
        ┌──────────────┬─────────────────┼───────────────┬───────────────┐
        ▼              ▼                 ▼               ▼               ▼
   claim/          policy/           decision/        review/         audit/
   extraction   document ingest    orchestration    human review   append-only log
        │              │                 │
        │              ▼                 ├── rules/    (deterministic engine)
        │          document/             ├── context/  (ClaimDecisionContext)
        │          (parser,              ├── provider/ (DecisionProvider)
        │           chunker,             ├── stream/   (AnalysisStage, StageEvent,
        │           storage)             │              AnalysisListener)
        │              │                 └── explanation/
        ▼              ▼                 ▼
   ┌─────────────────────────────────────────────────────────┐
   │  rag/ (embedding · retrieval · evidence)                 │
   │  ai/  (llm · mercury · resilience)                       │
   └──────┬──────────────────────┬──────────────────┬────────┘
          ▼                      ▼                  ▼
   PostgreSQL + pgvector   Mercury / OpenRouter   NVIDIA NIM
   (JPA + native vector    (chat completions,     (/v1/embeddings,
    SQL)                    JSON schema)            query / passage)
          ▲
          │
   Object storage (Local FS / S3)

   cross-cutting:  config/ · common/ (ApiError, exceptions, correlation-id) ·
                   security/ (JWT, RBAC) · ratelimit/ · observability/ (metrics)
```

**Package responsibilities**

- `common` — `ApiError` envelope, base entities, `GlobalExceptionHandler`,
  correlation-id filter (traceId in logs + `X-Correlation-Id`).
- `patient` / `policy` / `claim` — domain entities, repositories, DTOs, REST.
- `document` — storage abstraction, PDF extraction, cleaning, context-preserving
  chunking, ingestion orchestration.
- `rag` — `EmbeddingProvider` (query vs passage), vector retrieval, evidence model.
- `ai` — `ChatLlmProvider`, Mercury implementation, Resilience4j wiring, call metadata.
- `decision` — rules engine, typed `ClaimDecisionContext`, `DecisionProvider`,
  the orchestrator, explanation generation, and `stream/` (stage events) +
  `config/AnalysisStreamConfig` (the streaming executor).
- `review` — human review + AI/human agreement tracking.
- `audit` — append-only audit records and retrieval.
- `security` — users, JWT, filter chain, RBAC, dev seeder.
- `ratelimit` — per-caller limits on the expensive AI endpoints.
- `observability` — Micrometer metrics.

---

## 5. Request / data flows

### 5.1 Policy ingestion (RAG index build)

```
POST /policies/{id}/documents (PDF)        POST /policies/{id}/ingest[?reembed=true]
        │                                           │
   validate (magic bytes, size,              load doc refs (short tx)
   SHA-256 dedupe, generated key)            UPLOADED docs, or ALL docs with reembed
        │                                           │
   store bytes (Local/S3)                     retrieve bytes (storage)
        │                                           │
   persist PolicyDocument (UPLOADED)          PDFBox extract per page  ─┐
                                               clean text               │ outside
                                               context-preserving       │   any
                                               chunking (overlap,       │   tx
                                               heading detection)       │
                                               embedPassages(chunks) ───┘
                                               (input_type=passage, batched)
                                                     │
                                               replace PolicyChunk rows + vectors
                                               (native SQL: CAST(? AS vector)),
                                               record embedding_model,
                                               mark document INGESTED (short tx)
```

`reembed=true` exists for switching embedding models: every document is re-chunked
and re-embedded with the current provider. Earlier decisions keep their evidence
because `decision_evidence` is a snapshot whose chunk link is cleared, not enforced,
when the chunk row is replaced (`V3` migration, §8).

### 5.2 Claim fact extraction

```
POST /claims/{id}/extract
   load controlled claim slice (short tx)        ← no PII/ids/secrets to the model
        │
   ChatLlmProvider.chat(strict json_schema)      ← outside tx (Resilience4j)
        │
   validate + normalize (defensive):
     KNOWN-but-missing/unparseable → UNKNOWN
     numbers/booleans coerced, CONFLICTING kept
     confidence kept only if in [0,1]
        │
   upsert ClaimFact rows, SUBMITTED → PROCESSING (short tx)
```

On the `dev` profile the synthetic seeder stores pre-extracted facts, so analysis of
the demo claims does not need this call.

### 5.3 Decision analysis (the core)

```
POST /claims/{id}/analyze          (optional Idempotency-Key → reuse prior decision)
POST /claims/{id}/analyze/stream   (same pipeline, progress streamed as SSE, §5.5)

   LOAD      claim + policy facts + extracted facts (short tx)
        │
   RETRIEVE  embedQuery(diagnosis + procedure + treatment)   ── input_type=query
             pgvector top-k cosine search (outside tx)       ── fails? → ragFailed
        │
   RULES     RuleEngine.evaluate(DecisionInputs)             ── 7 pure rules
        │    aggregate: REJECT > REVIEW > ALLOW
        ▼
   ┌───────── deterministic outcome ─────────┐
   │ REJECT  → final REJECT        (no AI)    │   cost control: skip AI
   │ REVIEW  → final HUMAN_REVIEW  (no AI)    │
   │ ALLOW   → ragFailed? HUMAN_REVIEW        │
   │           else DecisionProvider.decide() │   AI   ← Mercury (strict schema)
   └──────────────────┬──────────────────────┘
                      │  GUARDRAILS (safety fusion):
                      │    APPROVE & conf≥min → APPROVE
                      │    APPROVE & conf<min → HUMAN_REVIEW (+LOW_AI_CONFIDENCE)
                      │    AI REJECT (no basis) → HUMAN_REVIEW
                      │    AI/provider error    → HUMAN_REVIEW (+AI_PROVIDER_FAILURE)
                      ▼
   PERSIST   Decision + DecisionEvidence snapshot, set claim status (short tx)
             decision metric + audit record (AI_DECISION, traceId from MDC)
```

### 5.4 Human review & explanation

```
POST /claims/{id}/review   (REVIEWER/ADMIN)        POST /claims/{id}/explain
   reviewer = authenticated principal                load finalized decision (short tx)
   APPROVE/REJECT → finalize claim status            generate text from decision +
   REQUEST_INFORMATION → stays in review             reasons + evidence + facts ONLY
   record agreedWithAi, audit HUMAN_OVERRIDE         (Mercury if configured, else
                                                      template; NEVER changes the
                                                      decision)
                                                     persist decision.explanation
```

### 5.5 Live analysis stream (SSE)

`POST /claims/{id}/analyze/stream` runs the **same** orchestrator as `/analyze`, with
an `AnalysisListener` that turns each stage into a Server-Sent Event:

```
event: stage     {"stage":"AUTH",     "status":"COMPLETED", "elapsedMs":0,  "data":{principal, roles}}
event: stage     {"stage":"LOAD",     "status":"STARTED", ...}
event: stage     {"stage":"LOAD",     "status":"COMPLETED", "data":{facts, knownFacts, policyNumber, policyStatus}}
event: stage     {"stage":"RETRIEVE", "status":"COMPLETED", "data":{clauses, topK, topSimilarity, embeddingModel}}
event: stage     {"stage":"RULES",    "status":"COMPLETED", "data":{outcome, evaluated, passed[], flagged[]}}
event: stage     {"stage":"AI",       "status":"STARTED",   "data":{provider, instructions, input}}
event: stage     {"stage":"AI",       "status":"COMPLETED", "data":{suggestion, confidence, provider, model, summary, reasonCodes, uncertainties}}
                 (or SKIPPED {reason: RULES_DECIDED | RAG_FAILURE}, or FAILED)
event: stage     {"stage":"GUARDRAILS","status":"COMPLETED","data":{decision, decidedBy, overrides[]}}
event: stage     {"stage":"PERSIST",  "status":"COMPLETED", "data":{decisionId, traceId}}
event: decision  <DecisionResponse>        — or —      event: error {status, error, message}
```

- **Threading.** The controller returns an `SseEmitter` (120 s timeout) and runs the
  analysis on the `analysisStreamExecutor` (4–16 threads, queue 64; a full queue →
  `503 BUSY`). Its `TaskDecorator` copies the MDC (traceId) and the `SecurityContext`
  onto the worker, so logs, audit rows and stream events share one traceId.
- **Security.** Only an authorized request can start an async dispatch, so
  `DispatcherType.ASYNC` is permitted in the filter chain; the rate limiter skips the
  async re-dispatch so one stream is charged once.
- **Timings are server-measured.** `elapsedMs` is measured around each stage in the
  orchestrator. The UI only adds a minimum on-screen time per stage (~0.5 s) so
  sub-millisecond stages can be followed; it never changes a result.
- **What the AI sees.** `AI STARTED` carries the model's instructions and `input`, built by
  `DecisionPromptPayload` — the same object `MercuryDecisionProvider` serializes into
  the prompt, so what the UI shows cannot drift from what the model receives: the
  claim (diagnosis, procedure, treatment, amount), the policy's coverage limit and
  exclusions, the extracted facts, the retrieved policy clauses, and the rule results.
- **Client.** `EventSource` cannot send an `Authorization` header, so the UI reads the
  stream with `fetch` + `ReadableStream` and parses SSE frames itself.

---

## 6. The decision engine in detail

**Deterministic rules** (`decision/rules`), each a pure function of `DecisionInputs`:

| Rule | Outcome on violation | Reason code |
|------|----------------------|-------------|
| Policy active & in force | REJECT | `POLICY_EXPIRED` / `POLICY_CANCELLED` / `POLICY_NOT_EFFECTIVE` |
| Procedure/diagnosis excluded | REJECT | `PROCEDURE_EXCLUDED` |
| Within coverage limit | REVIEW | `CLAIM_EXCEEDS_COVERAGE_LIMIT` |
| Waiting period satisfied | REJECT | `WAITING_PERIOD_VIOLATION` / `PRE_EXISTING_WAITING_PERIOD` |
| Required info present | REVIEW | `MISSING_REQUIRED_INFORMATION` |
| No conflicting facts | REVIEW | `CONFLICTING_INFORMATION` |
| Pre-existing status known | REVIEW | `PRE_EXISTING_STATUS_UNKNOWN` |

Aggregation precedence is strict: **any REJECT → REJECT; else any REVIEW → REVIEW;
else ALLOW**. The AI layer is consulted **only on ALLOW**, which both enforces the
"Java owns rejects" rule and avoids unnecessary AI spend: a claim the rules decide
completes in well under a second with zero model calls.

`ClaimDecisionContext` is the *only* object passed to the AI — claim summary, typed
policy facts, extracted facts, retrieved evidence, rule results, uncertainties, and
missing information. Nothing else (no secrets, no raw DB rows) crosses the boundary.

**Where the time goes** (one AI-approved demo claim, local stack): load ~20 ms,
retrieve ~275 ms (one NVIDIA query embedding + a few-ms vector search), rules <1 ms,
Mercury ~3–5 s, persist ~85 ms. The single model call dominates; Mercury is a
diffusion LLM, which is why even with its reasoning step it answers in seconds.

---

## 7. Provider abstractions

| Interface | Prod implementation | Offline/default | Selected by |
|-----------|---------------------|-----------------|-------------|
| `ChatLlmProvider` | `MercuryChatProvider` (OpenAI-compatible chat, strict JSON schema) | `NoopChatLlmProvider` (fails → HUMAN_REVIEW) | `app.mercury.provider` |
| `DecisionProvider` | `MercuryDecisionProvider` | `RuleOnlyDecisionProvider` (ALLOW→APPROVE) | same |
| `EmbeddingProvider` | `OpenAiCompatibleEmbeddingProvider` (NVIDIA NIM / OpenAI) | `DeterministicEmbeddingProvider` (384-dim, hashed, lexical) | `app.embedding.provider` |
| `DocumentStorageService` | `S3DocumentStorage` (AWS SDK v2) | `LocalDocumentStorage` | `app.storage.provider` |

**Mercury (chat / decision).** `inception/mercury-2.5` via OpenRouter. It is a
reasoning model that spends roughly 1k tokens thinking before it answers, so the
decision, extraction and explanation calls allow up to 4096 output tokens; a lower cap
truncates the JSON and fails safe to `HUMAN_REVIEW`.

**Embeddings.** `EmbeddingProvider` distinguishes `embedQuery` from `embedPassages`
(defaulting to plain `embed` for symmetric providers). The OpenAI-compatible
implementation adds the fields hosted retrieval models need, all optional and omitted
when blank:

| Setting | Sent as | NVIDIA NIM value |
|---------|---------|------------------|
| `EMBEDDING_QUERY_INPUT_TYPE` / `EMBEDDING_PASSAGE_INPUT_TYPE` | `input_type` | `query` / `passage` (required by asymmetric models) |
| `EMBEDDING_REQUEST_DIMENSIONS=true` | `dimensions = EMBEDDING_DIM` | 384 (the 2048-d Matryoshka model shortened to fit the existing `vector(384)` column — no migration) |
| `EMBEDDING_TRUNCATE` | `truncate` | `END` |
| `EMBEDDING_BATCH_SIZE` | inputs per request | `1` (the free endpoint intermittently fails multi-input batches) |

Each request is retried up to 3 attempts on 5xx / network errors (4xx is not
retried); provider error bodies are surfaced in the exception (never the key); every
returned vector's dimension is checked against `EMBEDDING_DIM`.

**AI resilience** (Resilience4j, programmatic, chat): per-attempt HTTP timeout;
**retry with exponential backoff** on transient (5xx/timeout/IO) only; **circuit
breaker** outermost (fail fast when open); 4xx never retried. A boot-time check fails
fast if the embedding provider's vector dimension ≠ the migrated `vector(N)` column.

---

## 8. Data model

Flyway-managed; Hibernate runs in `validate` mode (schema is owned by migrations,
never auto-generated):

- `V1__init_schema.sql` — domain tables, pgvector extension, HNSW index.
- `V2__users.sql` — `app_user`.
- `V4__decision_summary.sql` — stores each decision's one-line summary (Mercury's
  reasoning, or the rule engine's), so `GET /claims/{id}/decision` returns it.
- `V3__evidence_survives_reingestion.sql` — `decision_evidence.policy_chunk_id` becomes
  `ON DELETE SET NULL`, so re-ingesting a document can replace chunks that earlier
  decisions cited.

```
patient ─1─┬─< policy ─1─< policy_document ─1─< policy_chunk(embedding vector(N), HNSW,
           │                                                 embedding_model)
           └─< claim ─1─┬─< claim_document                         ┆ (link, SET NULL)
                        ├─< claim_fact (KNOWN/UNKNOWN/CONFLICTING)  ┆
                        └─1─ decision ─1─< decision_evidence ┄┄┄┄┄┄┄┘
                                   └──< human_review
audit_log (append-only, by claim)      app_user (BCrypt, role)
```

Conventions: UUID primary keys; enums stored as `VARCHAR` + `CHECK` (readable,
migration-friendly); JSON-ish lists stored as `text`; **one `decision` per claim**
(re-analysis upserts); `decision_evidence` copies snippet, section, page and
similarity at decision time, so it is a true snapshot; the `policy_chunk.embedding`
column is managed via **native SQL**, intentionally unmapped by JPA so the vector
dimension stays decoupled from the entity, and each chunk records the
`embedding_model` that produced it. Indexes on the hot foreign keys/status/created_at
and an **HNSW cosine** index on the embedding.

---

## 9. API surface (v1)

```
Auth    POST /api/v1/auth/login
Admin   POST/GET /api/v1/admin/users                         (ADMIN)
Policy  POST/GET /api/v1/policies · GET /{id}
        POST /{id}/documents · GET /{id}/documents
        POST /{id}/ingest[?reembed=true]
        GET  /{id}/chunks                                    (indexed wording + embedding model)
        GET  /{id}/search?query=                             (RAG debug)
Claim   POST/GET /api/v1/claims · GET /{id}
        POST /{id}/extract · GET /{id}/facts
        POST /{id}/analyze (Idempotency-Key) · GET /{id}/decision · GET /{id}/evidence
        POST /{id}/analyze/stream                            (text/event-stream)
        POST /{id}/explain
        POST /{id}/review (REVIEWER/ADMIN) · GET /{id}/audit (ADMIN)
Review  GET  /api/v1/reviews · GET /{id}                     (REVIEWER/ADMIN)
Ops     GET  /actuator/health · /info · /metrics · /prometheus
Docs    /swagger-ui.html · /v3/api-docs  (bearer-auth "Authorize"; prod: disabled/guarded)
```

Validation uses Jakarta Bean Validation; all errors return the consistent `ApiError`
envelope (`timestamp, status, error, message, path, fieldErrors, traceId`).

---

## 10. Security

- **Stateless JWT** (HMAC-SHA via jjwt; the algorithm follows the secret's length —
  HS256 at 32 bytes, the dev secret yields HS384): `POST /auth/login` issues a signed
  token carrying subject + role; a filter authenticates each request from the token
  (no per-request DB hit).
- **RBAC**: `USER` (submit/view claims), `REVIEWER` (review, evidence, override),
  `ADMIN` (manage policies/users, view audit). Enforced in the security filter chain.
- **Async dispatch**: SSE completion re-enters the filter chain as an ASYNC dispatch;
  it is permitted because only an already-authorized request can start one.
- **Passwords**: BCrypt only. **Secrets** (JWT, DB, OpenRouter and NVIDIA keys) via
  env / `.env` (gitignored) / AWS Secrets Manager — never in the image, never logged.
- **CORS** env-driven (no wildcard in prod). 401/403 return the `ApiError` envelope.
- **Rate limiting** on `/analyze`, `/analyze/stream`, `/extract`, `/explain`,
  `/ingest` (per caller) → `429 RATE_LIMITED`.
- *Documented limitation:* authorization is role-based; per-row claim ownership
  (a USER seeing only their own claims) is a future enhancement.

---

## 11. Observability

- **Metrics** (Micrometer → `/actuator/prometheus`): decision distribution
  (`healthclaim.decisions{decision}`), AI call outcomes, and latency timers for
  analyze / RAG / AI.
- **Structured logs**: native JSON (logstash format) in prod, every line carrying the
  request `traceId` (also on the streaming worker thread). Never logs secrets, JWTs,
  or raw patient data.
- **Audit** (`audit_log`): one record per analysis (event, model/provider/prompt
  version, final decision, latency, traceId, reason codes — operational metadata only)
  and per human review/override. Retrievable at `GET /claims/{id}/audit`.
- **Decision provenance**: each `decision` row records `model_provider` /
  `model_name` (`openrouter` / `inception/mercury-2.5` when the AI decided,
  `deterministic` / `rule-engine` when the rules did) and latency.
- **Health**: liveness/readiness probes; the AI-provider health check never fails
  liveness on a transient outage.

---

## 12. Configuration & profiles

Environment-driven (`application.yml` + `-dev` / `-test` / `-prod`). Key namespaces:
`app.storage.*`, `app.embedding.*`, `app.rag.*`, `app.mercury.*`, `app.decision.*`,
`app.ratelimit.*`, `app.security.*`. Out of the box, dev runs the whole stack **with
no API keys** (local embedder, Mercury `noop`, local storage); a `.env` flips on the
real providers:

```
MERCURY_PROVIDER=openrouter            MERCURY_MODEL=inception/mercury-2.5
EMBEDDING_PROVIDER=openai-compatible   EMBEDDING_BASE_URL=https://integrate.api.nvidia.com/v1
EMBEDDING_MODEL=nvidia/nemotron-3.5-embed-1b   EMBEDDING_DIM=384
EMBEDDING_QUERY_INPUT_TYPE=query       EMBEDDING_PASSAGE_INPUT_TYPE=passage
EMBEDDING_REQUEST_DIMENSIONS=true      EMBEDDING_TRUNCATE=END   EMBEDDING_BATCH_SIZE=1
```

After changing embedding models, re-embed the index with
`python scripts/seed_policy_documents.py --reingest`. Prod disables Swagger, tightens
actuator, and requires real secrets. `.env.example` documents every variable.

---

## 13. Testing strategy

**100 backend tests + 22 frontend tests**, all offline/CI-safe (no paid APIs):

- **Unit**: rule engine + aggregation, extractor normalization, Mercury decision
  parsing, deterministic embedder, chunker, local storage (path-traversal guard),
  OpenAI-compatible embedding requests (MockRestServiceServer: `input_type` per
  query/passage, `dimensions`, `truncate`, batching, 5xx retry, give-up, dimension
  check, error-body surfacing).
- **Contract** (MockWebServer): Mercury chat resilience — retry, 4xx no-retry,
  timeout, circuit-breaker-opens.
- **Integration** (Testcontainers): real pgvector (schema validate, cosine search,
  claim/policy/RAG/decision/review/audit APIs, re-embed with `reembed=true`),
  **LocalStack** for S3, security (JWT + RBAC: 401/403, hashed passwords), the SSE
  stream (stage order, the AI input and reply on the stream, AI skipped when rules
  decide, error event for an unknown claim), the chunks endpoint, evidence surviving
  re-ingestion, the summary being stored, and re-analysis dropping a stale
  explanation. AI is mocked (`@MockitoBean`) or `noop`.
- **Frontend** (Vitest): the SSE parser (events split across network chunks, CRLF,
  error events, a stream that ends without a decision, 401 handling), stage-event to
  display mapping, the stage pacer's timing (fake timers), and formatting helpers.

Covers covered-claim APPROVE, excluded/expired REJECT, over-limit / missing /
conflicting / unknown-pre-existing / RAG-failure / AI-failure HUMAN_REVIEW, duplicate
analysis idempotency, human override. CI (`.github/workflows/ci.yml`) runs
`./mvnw verify` and, in a second job, the frontend's lint, Vitest and production build
on every PR and `main`.

---

## 14. Deployment topology

```
Internet → API Gateway (HTTP API) → Lambda (container: app + Lambda Web Adapter)
                                        │
            ┌──────────────────┬────────┴─────────┬──────────────────────┐
            ▼                  ▼                  ▼                      ▼
      RDS Postgres         S3 bucket       OpenRouter / Mercury      NVIDIA NIM
      + pgvector           (documents)     (chat, optional)          (embeddings, optional)
      (via RDS Proxy)
```

- One container image in **ECR**; `Dockerfile.lambda` adds the AWS Lambda Web Adapter
  so the identical web app serves API Gateway events unchanged. SnapStart for cold
  starts; RDS Proxy for connection pooling; S3 because Lambda's filesystem is ephemeral.
- The **same image** (`Dockerfile`) runs on ECS Fargate / App Runner / Render /
  Cloud Run with no code changes; that is the better target for the SSE endpoint.
- Commands: [`docs/aws-ecr-deploy.md`](docs/aws-ecr-deploy.md). Full runbook + tradeoffs:
  [`docs/deploy-aws.md`](docs/deploy-aws.md).

**Honest Lambda tradeoffs**: JVM cold starts (→ SnapStart/provisioned concurrency);
API Gateway's 29 s timeout (→ async job model for heavy analysis); connection limits
(→ RDS Proxy); API Gateway buffers responses, so `/analyze/stream` arrives all at
once there — live streaming needs a container host (or Lambda response streaming).

---

## 15. Demo UI (`frontend/`)

A React 19 + TypeScript + Vite 6 single-page app (Motion for animation) in Apple's
design language, built to show the pipeline to a reviewer:

- **Routes** (hash): landing (`#/`), claims list (`#/claims`), claim detail
  (`#/claims/<id>`), policy rulebook (`#/policies`). Vite proxies `/api` and `/actuator` to the backend on `:8080`.
- **Pipeline view**: seven stages (Authenticate, Load, Retrieve, Rules, AI judge,
  Guardrails, Audit), each a 3D cube that spins while running and lands on a ✓ / ✕ /
  ! / – result face, with the real technology chips, the server-measured duration, and
  live details (clauses + embedding model, rules passed/flagged, Mercury's raw
  suggestion and confidence, guardrail overrides, decision/trace ids).
- **Panels**: claim, facts, policy, verdict (confidence ring, dataset-label match),
  **what the AI compared** (the exact input from the `AI STARTED` event and Mercury's
  reply, or why Mercury was not called), policy evidence, AI explanation, human review
  (REVIEWER/ADMIN), audit trail (ADMIN).
- **Policy rulebook** (`#/policies`): every policy from PostgreSQL — the terms the Java
  rules check (dates, coverage, waiting periods, exclusions) and the indexed wording in
  pgvector with its embedding model (`GET /policies/{id}/chunks`).
- **"What gets approved"** (landing): the eight conditions for an automatic approval
  and which part checks each (Java rule, AI advice, Java guardrail).
- **Accessibility**: dark/light themes, `prefers-reduced-motion` and
  `prefers-reduced-transparency` handled.
- **Demo sign-in**: one-click dev accounts (`admin`/`reviewer`/`user`), which exist
  only on the `dev` profile — remove them before any public deployment.

---

## 16. Known limitations / future work

- Per-row claim **ownership** enforcement (USER sees only own claims) — RBAC only today.
- Analysis runs inside the request (plain or streamed); the async job model (202 +
  `GET /jobs/{id}`) is designed for but not yet implemented (matters most under the
  Lambda 29 s cap).
- The synthetic policy wording is short, so similarity scores are modest (~0.1–0.3);
  they rank clauses well but are not strong matches in absolute terms.
- NVIDIA's free endpoint is used with one input per request because multi-input
  batches fail intermittently; a paid/self-hosted NIM can raise `EMBEDDING_BATCH_SIZE`.
- The default local embedder is **lexical, not semantic** — it exists so dev/CI run
  with no keys; real retrieval quality comes from the hosted embedding provider.

---

## 17. Why these choices (rationale)

- **Modular monolith, not microservices** — one bounded problem, one team, one deploy;
  module boundaries keep it clean without distributed-systems overhead.
- **pgvector over a separate vector DB** — one datastore, transactional consistency
  between chunks and their vectors, simpler ops.
- **Rules + AI (not AI-only)** — safety, determinism, auditability, and cost control;
  the model assists judgment but cannot cause an ungrounded approve/reject.
- **Mercury for the judge** — a diffusion LLM is fast enough that the one model call
  per claim stays at a few seconds, which keeps the streamed demo responsive.
- **Matryoshka dimensions over a re-migration** — asking the embedding model for 384-d
  vectors kept the existing column and HNSW index; only the vectors were rebuilt.
- **SSE over WebSockets** — one-way progress fits SSE; it reuses the existing HTTP,
  JWT and rate-limit stack with no extra protocol.
- **Provider interfaces + offline defaults** — the project builds, tests, and runs end
  to end with zero paid credentials, which keeps CI free and onboarding instant, while
  production flips to real providers via configuration alone.
