# Health Claim Decision Agent — Project Explainer

> **⚠️ SYNTHETIC DATA ONLY.** This is a portfolio backend for AI-assisted,
> evidence-grounded health-insurance **claim decision support**. It is **not** real
> insurance authorization, medical advice, or a production decision engine. Every
> policy, patient, provider, and claim is fabricated.

This document explains the whole project with diagrams. For the terse,
text-only architecture reference see [`ARCHITECTURE.md`](ARCHITECTURE.md); for
deployment see [`docs/aws-ecr-deploy.md`](docs/aws-ecr-deploy.md).

---

## 1. What it is

A synthetic health-insurance **claim** comes in. The system:

1. **Extracts** structured facts from it with an LLM (schema-constrained + validated),
2. **Retrieves** the most relevant **policy clauses** via RAG (PostgreSQL + pgvector,
   embeddings from **NVIDIA NIM**),
3. runs **deterministic business rules**,
4. and — only if the rules raise no hard objection — asks an **AI judge**
   (**Nemotron 3.5 Bolt** on NVIDIA NIM, free) for a verdict and a confidence in ~2 s.

A Java **orchestrator** fuses rules + AI into a final, auditable decision —
**APPROVE / REJECT / HUMAN_REVIEW** — grounded in retrieved evidence. Every stage can
be **streamed live** (Server-Sent Events) to the React demo UI, which draws each stage
as a 3D check with its real timing. Humans can override; everything is recorded in an
append-only **audit trail**.

**Core idea:** *Java owns the verdict; the AI only assists.* Rejections are always
rule-driven; the AI can never cause an ungrounded approve/reject; any AI or RAG
failure routes to a human.

**Nothing is hidden.** The demo UI shows the whole policy rulebook live from
PostgreSQL (the terms the rules check and the wording indexed in pgvector), and for
every analysis the exact package the AI judge was given and what it answered (§6).

**Live:** https://d97tm0469lmul.cloudfront.net (AWS Lambda + CloudFront, §15).

---

## 2. High-level architecture

```mermaid
flowchart LR
  ui["React demo UI<br/>(Vite · Motion)"]
  client["Swagger / any client"]

  subgraph app["Spring Boot API — modular monolith (stateless)"]
    sec["Security<br/>JWT + RBAC · rate limit"]
    claim["Claim module"]
    policy["Policy + Document ingestion"]
    orch["Decision Orchestrator"]
    sse["SSE stage stream<br/>/analyze/stream"]
    review["Human Review"]
    audit["Audit + Metrics"]
  end

  subgraph ai["AI (behind interfaces)"]
    rules["Deterministic Rules"]
    rag["RAG retrieval"]
    chat["ChatLlmProvider"]
    dprov["DecisionProvider"]
    emb["EmbeddingProvider<br/>query / passage"]
  end

  pg[("PostgreSQL<br/>+ pgvector")]
  store[("Object storage<br/>Local / S3")]
  llm["Nemotron 3.5 Bolt<br/>AI judge + explanations (NVIDIA NIM)"]
  dm["Parallel Decisions<br/>decision model (optional)"]
  nim["NVIDIA NIM<br/>nemotron-3.5-embed-1b"]

  ui -->|"REST + SSE, Bearer JWT"| sec
  client -->|HTTPS + Bearer JWT| sec
  sec --> claim & policy & orch & review & audit
  orch -.->|"stage events"| sse
  sse -.->|"text/event-stream"| ui
  policy --> store
  policy --> emb
  orch --> rules
  orch --> rag --> emb --> nim
  rag --> pg
  orch --> dprov --> chat --> llm
  dprov -.-> dm
  claim --> pg
  orch --> pg
  audit --> pg
```

Every external dependency (AI judge, chat LLM, embeddings, object storage) sits
behind an interface chosen by configuration, so providers swap without touching
business logic — and the whole stack runs offline with zero API keys for dev/CI.

---

## 3. Technology stack

| Layer | Choice |
|------|--------|
| Language / runtime | Java 21 (built on JDK 25, targets release 21) |
| Framework | Spring Boot 3.5 (Web, Validation, Data JPA, Security, Actuator) |
| Database | PostgreSQL 16 + **pgvector** (HNSW cosine index) |
| Migrations | Flyway `V1`–`V4` (`ddl-auto=validate`) |
| AI judge | `nvidia/nemotron-3.5-bolt-e4b` on **NVIDIA NIM** (free) — strict JSON schema, ~1–2 s; Parallel decision model by config |
| AI (extraction, explanation) | the same Nemotron 3.5 Bolt model — OpenAI-compatible, ~2 s |
| Embeddings | **NVIDIA NIM** `nemotron-3.5-embed-1b` (384-d, query/passage) **or** local deterministic (offline) |
| Streaming | Spring `SseEmitter` on a dedicated executor (MDC + security context carried over) |
| Resilience | Resilience4j (timeout, retry, circuit breaker); bounded retry for embeddings |
| PDF | Apache PDFBox |
| Security | Spring Security + JWT (jjwt, HMAC-SHA), BCrypt |
| Observability | Micrometer + Prometheus, structured JSON logs |
| Storage | Local FS (dev) / S3-compatible (deploy, AWS SDK v2) |
| Demo UI | React 19, TypeScript, Vite 6, Motion |
| Testing | JUnit 5, Mockito, Testcontainers, MockWebServer, MockRestServiceServer, LocalStack, Vitest (**122 backend + 24 frontend tests**) |
| Ship | Maven (`./mvnw`), multi-stage Docker, GitHub Actions, AWS ECR + Lambda |

---

## 4. Claim lifecycle (status machine)

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED
  SUBMITTED --> PROCESSING: extract facts (LLM)
  PROCESSING --> APPROVED: analyze → APPROVE
  PROCESSING --> REJECTED: analyze → REJECT
  PROCESSING --> HUMAN_REVIEW: analyze → HUMAN_REVIEW
  SUBMITTED --> APPROVED: analyze → APPROVE
  SUBMITTED --> REJECTED: analyze → REJECT
  SUBMITTED --> HUMAN_REVIEW: analyze → HUMAN_REVIEW
  HUMAN_REVIEW --> APPROVED: reviewer APPROVE
  HUMAN_REVIEW --> REJECTED: reviewer REJECT
  HUMAN_REVIEW --> HUMAN_REVIEW: reviewer REQUEST_INFORMATION
  APPROVED --> [*]
  REJECTED --> [*]
```

---

## 5. The decision engine (the core)

Deterministic rules run first and aggregate with strict precedence
**REJECT > REVIEW > ALLOW**. The AI is consulted **only on ALLOW** — which both
enforces "Java owns rejects" and avoids unnecessary AI spend.

```mermaid
flowchart TD
  start(["POST /claims/id/analyze<br/>or /analyze/stream"]) --> load["LOAD claim + policy facts + extracted facts"]
  load --> ragq["RETRIEVE policy evidence<br/>(NVIDIA query embedding + pgvector)"]
  ragq --> rules["RULES: 7 deterministic checks"]
  rules --> agg{"Aggregate outcome"}

  agg -->|any REJECT| rej["REJECT<br/>(no AI call)"]
  agg -->|else any REVIEW| rev["HUMAN_REVIEW<br/>(no AI call)"]
  agg -->|ALLOW| ragok{"RAG ok?"}

  ragok -->|failed| revR["HUMAN_REVIEW<br/>reason: RAG_FAILURE"]
  ragok -->|ok| aicall["AI judge: decide(context)"]

  aicall --> aifail{"AI error?"}
  aifail -->|yes| revA["HUMAN_REVIEW<br/>reason: AI_PROVIDER_FAILURE"]
  aifail -->|no| fuse{"GUARDRAILS"}

  fuse -->|APPROVE, conf ≥ 0.5| app["APPROVE"]
  fuse -->|APPROVE, conf < 0.5| revC["HUMAN_REVIEW<br/>LOW_AI_CONFIDENCE"]
  fuse -->|HUMAN_REVIEW| revH["HUMAN_REVIEW"]
  fuse -->|REJECT, no rule basis| revJ["HUMAN_REVIEW<br/>AI_SUGGESTED_REJECT"]

  rej & rev & revR & revA & app & revC & revH & revJ --> persist["PERSIST Decision + Evidence + Audit"]
  persist --> done(["Decision returned"])
```

**The 7 rules**

| Rule | On violation | Reason code |
|------|------|-------------|
| Policy active & in force | REJECT | `POLICY_EXPIRED` / `POLICY_CANCELLED` / `POLICY_NOT_EFFECTIVE` |
| Procedure/diagnosis excluded | REJECT | `PROCEDURE_EXCLUDED` |
| Within coverage limit | REVIEW | `CLAIM_EXCEEDS_COVERAGE_LIMIT` |
| Waiting period satisfied | REJECT | `WAITING_PERIOD_VIOLATION` / `PRE_EXISTING_WAITING_PERIOD` |
| Required info present | REVIEW | `MISSING_REQUIRED_INFORMATION` |
| No conflicting facts | REVIEW | `CONFLICTING_INFORMATION` |
| Pre-existing status known | REVIEW | `PRE_EXISTING_STATUS_UNKNOWN` |

**Where the time goes** (one AI-approved demo claim):

| Stage | Time | What dominates |
|-------|------|----------------|
| Load | ~20 ms | local Postgres, pooled connections |
| Retrieve | ~275 ms | one NVIDIA query embedding; the vector search itself is a few ms |
| Rules | <1 ms | in-memory Java |
| AI judge | ~1–2.5 s | one Nemotron 3.5 Bolt call on NVIDIA NIM (1.8–2.4 s live on AWS) |
| Guardrails | ~0 ms | comparisons in Java |
| Persist + audit | ~85 ms | decision, evidence snapshot, audit row |

A claim the rules decide skips the AI entirely and finishes in well under a second.

---

## 6. Who decides, and what the AI sees

**Java decides.** A claim is approved automatically only when all eight conditions
hold. Six are Java rule checks over the policy's terms in PostgreSQL; the AI advises
on one; a Java guardrail has the last word.

| # | Condition for an automatic APPROVE | Checked by |
|---|------------------------------------|------------|
| 1 | The policy is active and in force on the treatment date | Java rule |
| 2 | The procedure and diagnosis are not excluded by the policy | Java rule |
| 3 | The amount is within the coverage limit | Java rule |
| 4 | General and pre-existing waiting periods have passed | Java rule |
| 5 | Pre-existing condition status is known | Java rule |
| 6 | Every required fact is present and none conflict | Java rules |
| 7 | The AI judge weighs the claim against the retrieved policy wording and suggests APPROVE | **AI judge (advisory)** |
| 8 | Its confidence is at least 0.5, so Java accepts the suggestion | Java guardrail |

If a rule fails, the rules decide (REJECT or HUMAN_REVIEW) and the AI judge is
never called. If it is unsure, suggests a REJECT no rule supports, or fails, Java sends
the claim to a person.

**What the AI judge receives.** `DecisionPromptPayload` builds one JSON package from
the decision context. It goes into the judge's prompt, and the same object is sent to the
UI on the `AI STARTED` stream event, so what the UI shows is what the model got. The
judge must answer in a strict JSON schema. From a live run on AWS (CLM-SYN-005, trimmed):

```jsonc
{
  "claim":  { "diagnosis": "Kidney stones", "procedure": "Ureteroscopy",
              "treatment": "2-day inpatient", "claimAmount": 110000, "currency": "INR" },
  "policy": { "coverageLimit": 1000000,
              "exclusions": ["Cosmetic surgery", "Infertility treatment"] },
  "extractedFacts": [ { "key": "diagnosis", "value": "Kidney stones", "status": "KNOWN" } /* 5 in all */ ],
  "policyEvidence": [ { "page": 1, "text": "… 3.5 Ureteroscopy: …" } /* whole chunks from pgvector */ ],
  "ruleReasonCodes": [ "POLICY_ACTIVE", "NO_EXCLUSION_MATCH", "WITHIN_COVERAGE_LIMIT", … ]
}
```

No patient identity, claim or policy ids, provider or dates reach the model; dates are
checked by the Java rules. On that run the judge (Nemotron 3.5 Bolt) answered in
**2.4 s**: APPROVE at 0.95, *"covered under policy wording 3.5"*. Confidence was above
0.5 and no rule objected, so Java made it the final APPROVE.

**Three judges, no business-logic change.** The judge has changed twice, by configuration only: Inception's Mercury 2.5 (LLM, retired by OpenRouter in November 2026), then the Parallel Decisions decision model (its free tier was withdrawn days later), then Nemotron 3.5 Bolt on NVIDIA NIM. The Parallel decision
model (a probability per outcome in ~1 s) is still one setting away, at $0.001 per
decision. In testing it caught what text matching cannot: a rhinoplasty claim, when the
exclusion list only says "cosmetic surgery", came back REJECT at 85% from the policy
wording, which Java still sends to a person.

**Whole clauses, not fragments.** Each retrieved chunk goes in whole (~1000
characters). It used to be cut to 500, which dropped POL-SYN-001's covering clause
("3.1 Laparoscopic cholecystectomy …", at character ~800); with it, the model's
confidence on CLM-SYN-001 rose from 64–84% to 97% (measured with the decision model).


---

## 7. Sequence — analyze a claim

```mermaid
sequenceDiagram
  autonumber
  actor U as User (JWT)
  participant C as DecisionController
  participant O as DecisionOrchestrator
  participant DB as Postgres
  participant R as RetrievalService
  participant N as NVIDIA NIM
  participant RE as RuleEngine
  participant AI as AI judge (NVIDIA NIM)

  U->>C: POST /claims/{id}/analyze (Idempotency-Key?)
  C->>O: analyze(claimId, key)
  O->>DB: load claim + policy + facts  (short tx)
  O->>R: retrieve evidence (outside tx)
  R->>N: embed query (input_type=query, dimensions=384)
  N-->>R: 384-d vector
  R->>DB: vector search (cosine <=>, HNSW)
  R-->>O: top-k evidence (or failure flag)
  O->>RE: evaluate(inputs)
  RE-->>O: outcome (REJECT / REVIEW / ALLOW)
  alt deterministic REJECT or REVIEW
    Note over O: no AI call (cost control)
  else ALLOW and RAG ok
    O->>AI: decide(context) — strict JSON schema
    AI-->>O: AiDecision (+confidence if given)
  end
  O->>O: guardrails → final decision
  O->>DB: persist Decision + Evidence + status (short tx)
  O->>DB: write audit record (traceId)
  O-->>U: DecisionResponse (reasons, evidence, confidence?)
```

---

## 8. Sequence — live stage stream (SSE)

`POST /claims/{id}/analyze/stream` runs the same orchestrator with a listener that
emits one event per stage transition. Timings are measured on the server.

```mermaid
sequenceDiagram
  autonumber
  participant UI as React UI (fetch + ReadableStream)
  participant C as DecisionController
  participant X as analysisStreamExecutor
  participant O as DecisionOrchestrator

  UI->>C: POST /analyze/stream (Bearer JWT)
  C-->>UI: 200 text/event-stream (SseEmitter)
  C-->>UI: stage AUTH COMPLETED {principal, roles}
  C->>X: submit analysis (MDC traceId + SecurityContext copied)
  X->>O: analyze(claimId, key, listener)
  O-->>UI: stage LOAD STARTED / COMPLETED {facts, policy}
  O-->>UI: stage RETRIEVE COMPLETED {clauses, topSimilarity, embeddingModel}
  O-->>UI: stage RULES COMPLETED {outcome, passed, flagged}
  alt rules ALLOW
    O-->>UI: stage AI STARTED {instructions, input: the exact state the model receives}
    O-->>UI: stage AI COMPLETED {suggestion, confidence, model, summary}
  else rules decided
    O-->>UI: stage AI SKIPPED {reason: RULES_DECIDED}
  end
  O-->>UI: stage GUARDRAILS COMPLETED {decision, overrides}
  O-->>UI: stage PERSIST COMPLETED {decisionId, traceId}
  X-->>UI: event decision {DecisionResponse}  (or event error)
  X->>C: emitter.complete()
```

- `EventSource` cannot send an `Authorization` header, so the UI reads the stream with
  `fetch`. It keeps each cube on screen for at least ~0.5 s so sub-millisecond stages
  can be followed; the AI cube spins for exactly as long as the AI judge takes.
- The completion re-enters Spring Security as an ASYNC dispatch, which is permitted;
  the rate limiter skips it so a stream is charged once.

---

## 9. Sequence — ingest a policy document (RAG index build)

```mermaid
sequenceDiagram
  autonumber
  actor A as Admin (JWT)
  participant PC as PolicyController
  participant V as UploadedFileValidator
  participant S as DocumentStorage (Local/S3)
  participant I as IngestionService
  participant PB as PDFBox
  participant E as EmbeddingProvider (NVIDIA NIM)
  participant DB as Postgres + pgvector

  A->>PC: POST /policies/{id}/documents (PDF)
  PC->>V: validate (magic bytes, size, SHA-256)
  V-->>PC: ok
  PC->>S: store bytes (generated key)
  PC->>DB: PolicyDocument = UPLOADED

  A->>PC: POST /policies/{id}/ingest[?reembed=true]
  PC->>I: ingest(documentId) for UPLOADED (or all) docs
  I->>S: retrieve bytes
  I->>PB: extract text per page
  I->>I: clean + context-preserving chunking
  I->>E: embedPassages(chunks) — input_type=passage, outside any tx
  I->>DB: replace PolicyChunk rows + vectors (native SQL), mark INGESTED
```

`reembed=true` rebuilds every document's chunks with the current embedding model —
used when switching from the local embedder to NVIDIA
(`python scripts/seed_policy_documents.py --reingest`).

---

## 10. RAG pipeline

```mermaid
flowchart LR
  pdf["Policy PDF"] --> extract["PDFBox<br/>per-page text"]
  extract --> clean["Clean / normalize"]
  clean --> chunk["Chunk<br/>(paragraph-aware + overlap<br/>+ heading detection)"]
  chunk --> embed["NVIDIA NIM<br/>input_type=passage"]
  embed --> store[("policy_chunk.embedding<br/>vector(384), HNSW cosine<br/>+ embedding_model")]
  q["Claim query<br/>(diagnosis + procedure + treatment)"] --> embed2["NVIDIA NIM<br/>input_type=query"]
  embed2 --> search["cosine search <=>"]
  store --> search
  search --> ev["Evidence<br/>chunk · doc · page · section · score"]
```

`nemotron-3.5-embed-1b` is an **asymmetric** retrieval model (queries and passages
are embedded differently) and a **Matryoshka** model: it natively produces 2048-d
vectors but can return 384-d ones, which fit the existing `vector(384)` column with no
migration. Every policy-based decision is traceable to the retrieved chunk (document,
page, section, similarity) — stored as a `decision_evidence` snapshot.

---

## 11. Provider abstractions

```mermaid
classDiagram
  class ChatLlmProvider { <<interface>> +chat(ChatRequest) ChatResponse }
  class DecisionProvider { <<interface>> +decide(ClaimDecisionContext) AiDecision }
  class EmbeddingProvider {
    <<interface>>
    +embed(text) float[]
    +embedQuery(query) float[]
    +embedPassages(passages) List
    +dimension() int
  }
  class DocumentStorageService { <<interface>> +store() +retrieve() +delete() }

  ChatLlmProvider <|.. MercuryChatProvider
  ChatLlmProvider <|.. NoopChatLlmProvider
  DecisionProvider <|.. ParallelDecisionProvider
  DecisionProvider <|.. MercuryDecisionProvider
  DecisionProvider <|.. RuleOnlyDecisionProvider
  EmbeddingProvider <|.. OpenAiCompatibleEmbeddingProvider
  EmbeddingProvider <|.. DeterministicEmbeddingProvider
  DocumentStorageService <|.. LocalDocumentStorage
  DocumentStorageService <|.. S3DocumentStorage
```

Each interface has a **production** implementation and an **offline default**, chosen
by configuration (`app.decision-model.engine`, `app.mercury.provider`,
`app.embedding.provider`, `app.storage.provider`). Resilience4j wraps the chat HTTP calls (timeout + retry
on transient failures + circuit breaker). The embedding client sends `input_type`,
`dimensions` and `truncate` only when configured, retries 5xx up to 3 attempts, and
checks every vector's dimension. The chat model reasons before answering, so its calls allow up
to 4096 output tokens.

---

## 12. Data model

```mermaid
erDiagram
  PATIENT ||--o{ POLICY : holds
  PATIENT ||--o{ CLAIM : files
  POLICY  ||--o{ POLICY_DOCUMENT : has
  POLICY_DOCUMENT ||--o{ POLICY_CHUNK : "chunked into"
  POLICY  ||--o{ CLAIM : covers
  CLAIM   ||--o{ CLAIM_DOCUMENT : attaches
  CLAIM   ||--o{ CLAIM_FACT : "extracted facts"
  CLAIM   ||--|| DECISION : "one decision"
  DECISION ||--o{ DECISION_EVIDENCE : "evidence snapshot"
  POLICY_CHUNK |o--o{ DECISION_EVIDENCE : "cited by (SET NULL on replace)"
  CLAIM   ||--o{ HUMAN_REVIEW : "reviewed by"
  CLAIM   ||--o{ AUDIT_LOG : "audited by"

  POLICY_CHUNK {
    uuid id
    int page_number
    string section
    vector embedding "vector(384)"
    string embedding_model
  }
  CLAIM_FACT {
    string fact_key
    string fact_value
    string status "KNOWN|UNKNOWN|CONFLICTING"
  }
  DECISION {
    string decision "APPROVE|REJECT|HUMAN_REVIEW"
    double confidence "nullable"
    text reason_codes
    string model_provider "openrouter|deterministic"
    string model_name
  }
  DECISION_EVIDENCE {
    uuid policy_chunk_id "nullable"
    string section
    int page_number
    double similarity
    text snippet
  }
  APP_USER {
    string username
    string password_hash "BCrypt"
    string role "USER|REVIEWER|ADMIN"
  }
```

Conventions: UUID keys; enums as `VARCHAR` + `CHECK`; one `decision` per claim
(re-analysis upserts); the `embedding` column is managed by native SQL (unmapped by
JPA) so the vector dimension stays decoupled from the entity. Schema is owned by
Flyway (`V1__init_schema.sql`, `V2__users.sql`,
`V3__evidence_survives_reingestion.sql`, `V4__decision_summary.sql`).

---

## 13. API surface (v1)

```mermaid
flowchart TB
  subgraph Public
    L["POST /auth/login"]
    H["GET /actuator/health · /info"]
  end
  subgraph USER_plus["USER / REVIEWER / ADMIN"]
    CL["POST/GET /claims · /{id}"]
    EX["POST /{id}/extract · /analyze · /analyze/stream · /explain"]
    DEC["GET /{id}/decision · /evidence · /facts"]
    PG["GET /policies · /{id}/chunks · /{id}/search"]
  end
  subgraph REVIEWER_plus["REVIEWER / ADMIN"]
    RV["POST /claims/{id}/review · GET /reviews"]
  end
  subgraph ADMIN_only["ADMIN"]
    POL["POST /policies · /{id}/documents · /ingest?reembed="]
    AUD["GET /claims/{id}/audit"]
    USR["POST/GET /admin/users"]
  end
```

Rate limiting protects the expensive AI endpoints (`/analyze`, `/analyze/stream`,
`/extract`, `/explain`, `/ingest`) → `429` when the per-caller allowance is exceeded.
All errors return a consistent envelope with a `traceId`.

---

## 14. Security

```mermaid
flowchart LR
  req["Request"] --> disp{"Dispatch type"}
  disp -->|"ASYNC (SSE completion)"| ok["permitted<br/>(request already authorized)"]
  disp -->|REQUEST| jwtf["JwtAuthenticationFilter<br/>(reads Bearer token)"]
  jwtf -->|valid| ctx["SecurityContext<br/>role authority"]
  jwtf -->|missing/invalid| anon["anonymous"]
  ctx --> authz{"Role allows path?"}
  anon --> authz
  authz -->|yes| ctrl["Controller"]
  authz -->|unauthenticated| e401["401 UNAUTHORIZED"]
  authz -->|wrong role| e403["403 FORBIDDEN"]
```

Stateless JWT (HMAC-SHA; jjwt picks HS256/384/512 from the secret's length — the dev
secret gives HS384), BCrypt passwords, env-driven CORS (no wildcard in prod), secrets
(JWT, DB, OpenRouter, NVIDIA) via env / gitignored `.env` / AWS Secrets Manager,
tokens and keys never logged.

---

## 15. Deployment (live on AWS)

Live at **https://d97tm0469lmul.cloudfront.net**. Infrastructure is one CloudFormation
template ([`infra/aws/template.yaml`](infra/aws/template.yaml)); every push to `main`
that passes CI is deployed by GitHub Actions through OIDC.

```mermaid
flowchart LR
  user["Browser"] -->|HTTPS| cf["CloudFront"]
  cf -->|"UI"| site[("S3 site bucket")]
  cf -->|"/api, /actuator"| url["Lambda Function URL<br/>RESPONSE_STREAM"]
  url --> lam["Lambda: Spring Boot<br/>+ Lambda Web Adapter"]
  lam --> neon[("Neon Postgres<br/>+ pgvector")]
  lam --> docs[("S3 documents")]
  lam --> or["NVIDIA NIM<br/>AI judge + explanations"]
  lam --> nim["NVIDIA NIM<br/>embeddings"]
  gh["GitHub Actions"] -->|"OIDC role"| ecr["ECR image"] --> lam
```

- **Live SSE on Lambda:** the Function URL streams and the Web Adapter runs in
  `response_stream` mode, so the stage events reach the browser as they happen.
- **One origin:** CloudFront serves the UI and forwards the API, so there's no CORS. Its
  request policy drops the browser's `Origin` header, which Spring's CORS check would reject.
- **Start-up:** the adapter waits for readiness, so Lambda never freezes the app while
  it is still seeding; a cold start takes ~30 s.
- **No keys in GitHub:** app secrets live only in the Lambda's configuration; the deploy
  role can only push this image, update this function and publish this site.
- **Public-demo profile:** demo accounts, 5 AI calls per minute per account, and shared
  data read-only except for a private operator account.

The **same image** (built from `Dockerfile`, without the adapter) runs on ECS Fargate /
App Runner / Cloud Run with no code changes.

---

## 16. Testing & evaluation

- **122 backend tests** — unit (rules, extractor normalization, embedders, chunker,
  embedding request shape / retry via MockRestServiceServer), contract (chat client via
  MockWebServer: retry / timeout / circuit breaker), integration (Testcontainers
  pgvector + LocalStack S3: APIs, RAG, re-embed, SSE stage order, evidence surviving
  re-ingestion), and security (JWT + RBAC). All offline — no paid APIs in CI.
- **Evaluation harness** over a **labeled synthetic dataset** (5 policies, 30 claims)
  computes accuracy, per-class precision/recall/F1, false-approval/rejection rates,
  human-review rate, latency, cost. The offline RULE_ONLY arm reproduces the
  rule-grounded labels (**accuracy 1.000**) as a consistency check; LLM/hybrid arms
  plug in when a provider is configured. See
  [`docs/evaluation-report.md`](docs/evaluation-report.md).

```mermaid
flowchart LR
  ds["synthetic/dataset.json<br/>30 labeled claims"] --> arm["Arm: RULE_ONLY / LLM / Hybrid"]
  arm --> cmp["Compare predicted vs expected"]
  cmp --> metrics["Accuracy · P/R/F1 · FAR/FRR<br/>· human-review rate · latency · cost"]
  metrics --> report["evaluation-report.md"]
```

---

## 17. Demo UI

A React 19 + TypeScript + Vite 6 app (Motion for animation) in Apple's design
language. Every number on it comes from the running backend.

```mermaid
flowchart LR
  land["Landing<br/>how it works · what gets approved<br/>guardrails · stack · demo sign-in"]
  rb["Policy rulebook<br/>terms the rules check<br/>+ wording in pgvector"]
  list["Claims<br/>search · filter by expected outcome"]
  detail["Claim detail"]
  pipe["7 live 3D stages<br/>(SSE, server timings)"]
  ai["What the AI compared<br/>exact input + verdict"]
  rest["Verdict · evidence · explanation<br/>human review · audit trail"]
  land --> rb
  land --> list --> detail
  detail --> pipe & ai & rest
```

- **Pipeline:** each stage is a cube that spins while running and lands on ✓ / ✕ / ! / –,
  with the real technology chips and live details (clauses and embedding model, rules
  passed or flagged, the AI judge's verdict and confidence, guardrail overrides, ids).
- **What the AI compared:** the claim, the policy terms, the retrieved clauses, the
  facts and rule results the AI judge received, its verdict and confidence (or, with the
  decision-model engine, probability bars and web sources), and Java's ruling. The raw prompt and JSON are one click away.
- **Plain-English explanation:** verdict, why (checks and the AI judge's verdict), the
  policy sentence that names the procedure, and what to check. It says who wrote it: the
  chat model, or the template when the free model is busy or slower than 40 s.
- **Policy rulebook:** every policy from PostgreSQL with the rule each term feeds, and
  its indexed wording (`GET /policies/{id}/chunks`, embedding model and dimensions).
- **Roles:** one-click dev accounts for admin, reviewer and user; review and audit
  panels follow the role. Dark and light themes, reduced motion and reduced
  transparency are handled.
- The Vite dev proxy forwards `/api` and `/actuator` to `:8080` and drops the browser's
  `Origin` header (the request is same-origin through the proxy), so the UI works on
  whichever port Vite picks.

---

## 18. Run it locally

```bash
# Postgres (pgvector) + backend, zero API keys needed
docker compose up --build          # use DB_HOST_PORT=5433 if 5432 is taken

# or run tests
./mvnw clean verify                # 122 backend tests (needs Docker for Testcontainers)
cd frontend && npm test            # 24 frontend tests (Vitest)
```

Real AI is configuration only (in `.env`): `MERCURY_PROVIDER=openrouter` + key/model
for the judge, and the `EMBEDDING_*` NVIDIA block from `.env.example` for semantic
retrieval. Then index the synthetic policy wording and start the UI:

```bash
python scripts/seed_policy_documents.py      # add --reingest after changing embedding models
cd frontend && npm install && npm run dev    # http://localhost:5173
```

Sign in as a demo role, open **Rulebook** to see what claims are checked against, then
open a claim and press **Analyze** to watch the stages and the AI's input live.

Dev seeds synthetic users (`admin` / `reviewer` / `user`) and the synthetic
policies + claims. Swagger UI at `/swagger-ui.html` (dev only, with an Authorize
button for the bearer token).

---

## 19. Limitations & future work

- The AI uses NVIDIA NIM's **free tier**; free models come and go (two were withdrawn
  during this project), so the engine and model are configuration.
- Authorization is **role-based**; per-row claim ownership (a USER seeing only their
  own claims) is a documented future enhancement.
- Analysis runs inside the request (plain or streamed); the async job model (`202` +
  `GET /jobs/{id}`) is designed for but not implemented (matters most under Lambda's
  29 s cap).
- The synthetic policy wording is short, so similarity scores are modest (~0.1–0.3)
  even though the ranking is sensible.
- NVIDIA's free endpoint is called with one input per request because multi-input
  batches fail intermittently.
- The demo sign-in accounts are dev-only and must not ship in a public deployment.
- **The AI's added value is not measured yet.** The evaluation's RULE_ONLY arm scores
  30/30 because the dataset labels are rule-grounded, so it is a consistency check,
  not a result. The hybrid (rules + AI judge) arm is supported by the harness but has
  not been run.
- Front-end tests cover the logic (stream parsing, stage mapping and pacing,
  formatting), not rendered components; the UI itself is checked in the browser.
