# Health Claim Decision Agent

> **⚠️ SYNTHETIC DATA ONLY.** This is an AI-assisted, evidence-grounded
> decision-*support* system built as a portfolio project. It does **not** provide
> real insurance authorization, medical advice, or production insurance decisions.
> Every policy, claim, patient, and document is fabricated.

An AI-assisted health-insurance **claim decision-support** backend. It ingests a
synthetic claim, retrieves relevant policy clauses via **RAG** (PostgreSQL +
pgvector), extracts structured claim facts, applies **deterministic business
rules**, consults a **decision model** (Parallel Decisions, via OpenRouter) that returns a
probability for each outcome, and produces an auditable:

```
APPROVE   |   REJECT   |   HUMAN_REVIEW
```

The Java application owns the final decision; the AI model only assists with
semantic judgment. Every policy-based outcome is traceable to retrieved evidence.

This is **not** a chatbot and **not** a "chat with PDF" app — it is a real,
deployable backend.

**Live demo:** https://d97tm0469lmul.cloudfront.net (AWS Lambda + CloudFront; one-click
demo sign-in; the first request after it has been idle takes ~30 s while the JVM starts).

---

## Technology stack

| Area | Choice |
|------|--------|
| Language / runtime | Java 21 (built on JDK, run on a Java 21 JRE) |
| Framework | Spring Boot 3.5.x (Web, Validation, Actuator; JPA/Security added by phase) |
| Database | PostgreSQL + pgvector |
| Migrations | Flyway |
| AI judge (decision assist) | **Parallel Decisions Fast** decision model via OpenRouter's Decisions API: typed `choice` answer with per-outcome probabilities (`DecisionProvider`, swappable) |
| AI (extraction/explanation) | `nvidia/nemotron-3.5-super-vl` via OpenRouter (OpenAI-compatible chat, strict JSON schema) |
| Embeddings | Pluggable `EmbeddingProvider`: NVIDIA NIM `nemotron-3.5-embed-1b` (OpenAI-compatible, asymmetric query/passage, shortened to 384-d), or a local deterministic fallback for dev/CI |
| Docs | OpenAPI / Swagger UI (springdoc) |
| Build | Maven (via committed `./mvnw` wrapper) |
| Container | Docker (multi-stage, non-root, Java 21 JRE) |

### Provider abstraction (why it matters)

Research of the live Inception/OpenRouter docs established two facts that shape the design:

1. Model ids are **configuration, never hardcoded** (`DECISION_MODEL`, `MERCURY_MODEL`).
   That paid off: the project started on Inception's Mercury 2.5, OpenRouter retired it
   in November 2026, and switching the judge to a decision model and the chat client to
   Nemotron needed no change to business logic.
2. **OpenRouter exposes no first-party embeddings endpoint**, so
   embeddings come from a separate, swappable `EmbeddingProvider` — NVIDIA NIM
   (`nvidia/nemotron-3.5-embed-1b`) in the configured setup.

All external AI access sits behind interfaces (`ChatLlmProvider`, `DecisionProvider`,
`EmbeddingProvider`) so providers can change without touching business logic.

---

## Status — built in phases

This project is built incrementally. Each phase compiles and tests before the next.

- [x] **Phase 0** — Research real LLM/OpenRouter APIs (Mercury at the time); propose architecture
- [x] **Phase 1** — Spring Boot scaffold (web, validation, actuator, OpenAPI, error handling, correlation ids)
- [x] **Phase 2** — PostgreSQL + Flyway + pgvector + domain entities (Testcontainers-verified)
- [x] **Phase 3** — Claim APIs (create / get / list, validation, error envelope)
- [x] **Phase 4** — Policy APIs + document ingestion (upload, PDF extraction, chunking, storage abstraction)
- [x] **Phase 5** — pgvector RAG (EmbeddingProvider abstraction, embed-on-ingest, semantic retrieval)
- [x] **Phase 6** — AI provider abstraction (OpenAI-compatible chat via OpenRouter, Resilience4j, health)
- [x] **Phase 7** — Claim extraction (schema-constrained LLM → validated ClaimFacts)
- [x] **Phase 8** — Decision orchestration (deterministic rules + AI assist, APPROVE/REJECT/HUMAN_REVIEW)
- [x] **Phase 9** — Human review + explanation generation
- [x] **Phase 10** — Audit trail + observability (Micrometer/Prometheus, structured logging)
- [x] **Phase 11** — Security (JWT auth, roles USER/REVIEWER/ADMIN, CORS)
- [x] **Phase 12** — Testing hardening (unit + Testcontainers + contract + security; **111 backend + 23 frontend tests** today)
- [x] **Phase 13** — Docker (multi-stage image, non-root, compose stack verified end-to-end)
- [x] **Phase 14** — Cloud deployment (AWS ECR + Lambda) + S3 document storage
- [x] **Phase 15** — CI/CD (GitHub Actions: PR/main build+test, image publish to GHCR)
- [x] **Phase 16** — Evaluation framework (labeled synthetic dataset + metrics harness)
- [x] **Phase 17** — Live analysis stream (SSE `/analyze/stream`) + React demo UI
- [x] **Phase 18** — NVIDIA NIM semantic embeddings (query/passage, 384-d) + re-embedding
- [x] **Phase 19** — Transparency: policy rulebook (`GET /policies/{id}/chunks`) and the exact AI input streamed with each analysis
- [x] **Phase 20** — Decision model as the AI judge (Parallel Decisions: per-outcome probabilities in ~1–2 s, web sources shown)
- [x] **Phase 21** — Live on AWS: Lambda (container + Web Adapter, streaming Function URL), CloudFront + S3, Neon Postgres, GitHub Actions deploy via OIDC

---

## Synthetic data, rate limiting, evaluation & CI

**Synthetic dataset** (`src/main/resources/synthetic/dataset.json`) — 5 policies and
30 labeled claims spanning APPROVE / REJECT / HUMAN_REVIEW and the edge cases
(expired policy, excluded procedures, waiting periods, over-limit, missing/conflicting
facts, unknown pre-existing). On the **dev** profile a seeder loads them into the DB
(with pre-extracted facts, so decisions run offline); disable with
`SEED_SYNTHETIC_DATA=false`.

**Rate limiting** — the expensive AI endpoints (`/analyze`, `/analyze/stream`,
`/extract`, `/explain`, policy `/ingest`) are rate-limited per caller (Resilience4j); exceeding the allowance
returns `429 RATE_LIMITED`. Tune with `RATE_LIMIT_PER_PERIOD` / `RATE_LIMIT_REFRESH_SECONDS`.

**Evaluation harness** — runs a decision *arm* over the labeled dataset and computes
accuracy, per-class precision/recall/F1, false-approval/rejection rates, human-review
rate, latency and cost. The offline **RULE_ONLY** arm reproduces the rule-grounded
labels (a consistency check); LLM/hybrid arms plug in when a provider is configured.
Generated output: [docs/evaluation-report.md](docs/evaluation-report.md). Run with
`java -jar app.jar --spring.profiles.active=eval`.

**CI/CD** (`.github/workflows/`) — on PR and `main`, `ci.yml` runs `./mvnw verify`
(Testcontainers + LocalStack on the runner, no paid APIs); `docker-image.yml` builds
and publishes the image to GHCR on `main`/tags. AWS ECR/Lambda deployment stays manual
and documented (nothing auto-deploys to a paid environment).

## Documentation

- **[PROJECT.md](PROJECT.md)** — illustrated project explainer with Mermaid diagrams
  (architecture, decision flow, sequences, ER model, state machine, deployment).
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — text architecture reference (principles,
  module map, data model, rationale).
- **[docs/aws-ecr-deploy.md](docs/aws-ecr-deploy.md)** — exact AWS ECR + Lambda commands.
- **[docs/deploy-aws.md](docs/deploy-aws.md)** — deployment runbook + honest tradeoffs.
- **[docs/evaluation-report.md](docs/evaluation-report.md)** — generated evaluation metrics.

## Local development

Prerequisites: a Java 21+ JDK (JDK 25 works; the build targets Java 21 bytecode).
Maven is **not** required — use the committed wrapper.

```bash
# Build + run tests
./mvnw clean verify          # (Windows: .\mvnw.cmd clean verify)

# Run the app
./mvnw spring-boot:run
```

Then:

- Health: <http://localhost:8080/actuator/health>
- Info: <http://localhost:8080/actuator/info>
- API docs (Swagger UI): <http://localhost:8080/swagger-ui.html>

Start the local database (PostgreSQL + pgvector):

```bash
docker compose up -d db
```

> If you already run a local PostgreSQL on port 5432, set `DB_HOST_PORT=5433`
> (and change the port in `DATABASE_URL` to match) so the container doesn't clash.

Configuration is environment-driven. Copy `.env.example` to `.env` and fill in
values (Docker Compose loads it automatically).

### Demo UI (`frontend/`)

A React + TypeScript + Motion front end that walks through the decision pipeline
stage by stage, with a 3D check for each stage. Stages are streamed live from
`POST /api/v1/claims/{id}/analyze/stream` (Server-Sent Events): the orchestrator
reports each stage (`AUTH`, `LOAD`, `RETRIEVE`, `RULES`, `AI`, `GUARDRAILS`, `PERSIST`)
as it starts and finishes, with server-measured timings, then sends a final
`decision` (or `error`) event. The synchronous `/analyze` endpoint is unchanged.

```bash
# 1. Backend on :8080 (dev profile), then index synthetic policy wording for RAG evidence
python scripts/seed_policy_documents.py

# 2. Front end on :5173 (proxies /api and /actuator to :8080)
cd frontend
npm install
npm run dev
```

**Real embeddings (optional).** By default RAG uses a local deterministic embedder,
which matches words, not meaning. For semantic retrieval, point the
OpenAI-compatible provider at NVIDIA NIM (free key at build.nvidia.com); see the
commented example in `.env.example`. The model is asymmetric, so queries and
policy chunks are embedded with `input_type` `query` / `passage`, and
`dimensions=384` keeps the existing pgvector column (no migration). After
switching models, re-embed the indexed wording:

```bash
python scripts/seed_policy_documents.py --reingest
```

Sign in with one click as the dev-profile admin, reviewer or user (these
accounts exist only under the `dev` profile).

---

## Deployment (live on AWS)

**https://d97tm0469lmul.cloudfront.net** — sign in with a demo role and analyze a claim.

```
Browser ──HTTPS──▶ CloudFront ──▶ S3                 (React UI)
                       │
                       └─ /api/*, /actuator/* ─▶ Lambda Function URL (RESPONSE_STREAM)
                                                    └─ Spring Boot in a container image
                                                       + AWS Lambda Web Adapter
                                                         ├─▶ Neon Postgres + pgvector
                                                         ├─▶ S3 (policy documents)
                                                         └─▶ OpenRouter (decision model, chat)
                                                             NVIDIA NIM (embeddings)
```

- **Infrastructure as code:** [`infra/aws/template.yaml`](infra/aws/template.yaml)
  (CloudFormation) creates the Lambda, its streaming Function URL, both S3 buckets,
  CloudFront (one origin for UI and API, so no CORS), and the GitHub deploy role.
- **CI/CD:** [`deploy.yml`](.github/workflows/deploy.yml) runs after CI passes on `main`:
  builds `Dockerfile.lambda`, pushes to ECR, updates the Lambda, smoke-tests readiness,
  publishes the UI to S3 and invalidates CloudFront. It authenticates with **GitHub OIDC**
  to a role that can only touch this app's resources — no AWS keys in GitHub. App
  secrets live only in the Lambda's configuration.
- **Live streaming on Lambda:** the Function URL runs in `RESPONSE_STREAM` mode and the
  Web Adapter streams, so the SSE stage events arrive live (API Gateway would buffer them).
- **Public-demo profile** (`prod,demo`): seeds the synthetic data and demo accounts,
  limits AI endpoints to 5 per minute per account, and makes shared data read-only for
  everyone except a private operator account (`DemoWriteGuard`).
- **Start-up:** the Web Adapter waits for Spring's **readiness** (not liveness), so the
  app is never frozen mid-start-up; a cold start takes ~30 s on 3 GB.

The same image runs on ECS Fargate / App Runner / Cloud Run unchanged. Runbook and
trade-offs for an RDS-based setup: [docs/deploy-aws.md](docs/deploy-aws.md).

## Disclaimers

- **Synthetic data only.** No real people, hospitals, policy numbers, or medical records.
- Not medical, legal, or insurance advice. Not a production insurance decision engine.
