# Deploying to AWS (ECR + Lambda)

> **SYNTHETIC DATA ONLY.** This is a portfolio project, not a production insurance
> system. The steps below are a complete, honest runbook; substitute your own
> account id, region, and names.

This service is a **stateless container**. The deployment target here is **AWS
Lambda (container image)** fronted by **API Gateway**, using the
[AWS Lambda Web Adapter](https://github.com/awslabs/aws-lambda-web-adapter) so the
*same* Spring Boot app runs unchanged. Postgres (with pgvector) is **RDS**, and
uploaded documents live in **S3**.

```
Internet → API Gateway (HTTP API) → Lambda (container: app + Lambda Web Adapter)
                                        │
                        ┌───────────────┼───────────────┐
                        ▼               ▼               ▼
                  RDS Postgres     S3 bucket      OpenRouter (AI models)
                  + pgvector      (documents)        (optional)
                   via RDS Proxy
```

## Honest tradeoffs (read first)

Spring Boot on Lambda works, but mind these:

1. **Cold starts.** A JVM container cold start is seconds. Mitigate with
   **Lambda SnapStart for Java 21**, provisioned concurrency, and
   `-XX:MaxRAMPercentage` (already set). Still not as flat as a long-running server.
2. **API Gateway 29s timeout.** Synchronous claim analysis (RAG + AI calls) can
   approach this. For production, prefer the **async job model** (§24 of the spec:
   `202 Accepted` + `GET /jobs/{id}`) — the MVP analyze is synchronous and fine for
   small/fast cases and the demo.
3. **Database connections.** Lambda scales horizontally; raw Postgres connections
   exhaust quickly. Use **RDS Proxy** and keep the Hikari pool small
   (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2`).
4. **Ephemeral filesystem.** Only `/tmp` is writable. Document storage therefore
   **must** use S3 (`STORAGE_PROVIDER=s3`), implemented and tested in this project.

**Simpler alternatives using the exact same image:** AWS **ECS Fargate**, **Render**,
**Google Cloud Run**. If Lambda's constraints are not worth it for your use, deploy
the `Dockerfile` image to one of those instead — no code changes.

## Prerequisites

- AWS CLI v2 configured (`aws configure`), Docker, and an AWS account.
- Shell variables used below:

```bash
export AWS_REGION=us-east-1
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPO=health-claim-decision-agent
export IMAGE="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest"
```

## 1. Database — RDS Postgres + pgvector

1. Create an RDS PostgreSQL 16 instance (same VPC/subnets you will run Lambda in).
2. Enable the extension once (pgvector is available on RDS PostgreSQL):

```bash
psql "postgresql://<user>:<pw>@<rds-endpoint>:5432/healthclaim" \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

3. Create an **RDS Proxy** targeting the instance (for Lambda connection pooling).
   Flyway migrations run automatically on application startup.

## 2. Object storage — S3

```bash
aws s3 mb "s3://hcda-documents-$ACCOUNT_ID" --region "$AWS_REGION"
```

The app uses the **default AWS credentials chain** on Lambda, so grant the Lambda
execution role `s3:GetObject`/`PutObject`/`DeleteObject` on that bucket — no keys
in config needed.

## 3. Secrets

Store `JWT_SECRET` and the DB password in **AWS Secrets Manager** (or SSM Parameter
Store) and reference them from the Lambda function configuration. Never bake secrets
into the image.

## 4. Build & push the image to ECR

```bash
aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

# Lambda image = app + Lambda Web Adapter
docker build -f Dockerfile.lambda -t "$IMAGE" .
docker push "$IMAGE"
```

## 5. Create the Lambda function (container image)

- **Create function** → *Container image* → the `$IMAGE` you pushed.
- Memory **1024–2048 MB**, timeout **29s** (API Gateway max), attach to the VPC/subnets
  and the security group that can reach RDS Proxy.
- Enable **SnapStart** (Java) to reduce cold starts.
- Execution role: VPC access, S3 on the bucket, and read access to the secrets.

### Environment variables

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DATABASE_URL` | `jdbc:postgresql://<rds-proxy-endpoint>:5432/healthclaim` |
| `DATABASE_USERNAME` | `<user>` |
| `DATABASE_PASSWORD` | *(from Secrets Manager)* |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `2` |
| `STORAGE_PROVIDER` | `s3` |
| `S3_BUCKET` | `hcda-documents-<account>` |
| `S3_REGION` | `us-east-1` |
| `EMBEDDING_PROVIDER` | `openai-compatible` (+ `EMBEDDING_BASE_URL/MODEL/API_KEY/DIM`; for NVIDIA NIM also `EMBEDDING_QUERY_INPUT_TYPE=query`, `EMBEDDING_PASSAGE_INPUT_TYPE=passage`, `EMBEDDING_REQUEST_DIMENSIONS=true`, `EMBEDDING_BATCH_SIZE=1`) or `local` |
| `MERCURY_PROVIDER` | `openrouter` (+ `MERCURY_BASE_URL/API_KEY/MODEL`) or `noop` |
| `JWT_SECRET` | *(from Secrets Manager, ≥ 32 bytes)* |
| `CORS_ALLOWED_ORIGINS` | `https://your-frontend.example.com` |
| `AWS_LWA_READINESS_CHECK_PATH` | `/actuator/health/liveness` (already set in the image) |

> If `EMBEDDING_PROVIDER` changes the vector dimension, set `EMBEDDING_DIM` to match
> and deploy against a **fresh** schema — the app fails fast on a dimension mismatch.
> NVIDIA's `nemotron-3.5-embed-1b` can return 384-d vectors (`EMBEDDING_REQUEST_DIMENSIONS=true`),
> so it keeps the default schema; after switching models, re-embed with
> `POST /api/v1/policies/{id}/ingest?reembed=true`.
>
> The live stage stream (`POST /claims/{id}/analyze/stream`, SSE) is buffered by API
> Gateway, so on Lambda it arrives in one piece; it streams live on a container host
> (ECS Fargate / App Runner / Cloud Run).

## 6. API Gateway

Create an **HTTP API** with a `$default` route (proxy) integrated to the Lambda
function. Note the invoke URL; that is your API base. Configure a custom domain +
HTTPS (ACM certificate) as desired. Set `CORS_ALLOWED_ORIGINS` to your frontend.

## 7. Verify (the §36 checklist)

```bash
BASE=https://<your-api-id>.execute-api.$AWS_REGION.amazonaws.com

curl -s $BASE/actuator/health            # {"status":"UP", ... db UP}
# login (create a real ADMIN first via a one-off task or seeded admin), then:
TOKEN=$(curl -s $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<pw>"}' | jq -r .token)
curl -s $BASE/api/v1/policies -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{...}'    # create a synthetic policy
# submit + analyze a synthetic claim, then GET its /decision and /evidence
```

Checklist: ✅ DB reachable (health `db: UP`) · ✅ Flyway migrated (startup logs) ·
✅ S3 reachable (upload a document) · ✅ AI provider reachable (if configured) ·
✅ HTTPS + CORS · ✅ no SSH into the container required for normal operation.

## Creating the first admin user in production

The dev user seeder runs only under the `dev` profile. In `prod`, create the first
admin with a one-off invocation (e.g. temporarily set `SPRING_PROFILES_ACTIVE=dev,prod`
to seed, then remove), or add a short bootstrap task. Afterwards manage users via
`POST /api/v1/admin/users`.

## Running the same image elsewhere (no code changes)

- **ECS Fargate / Render / Cloud Run:** deploy the `Dockerfile` image (not the
  `.lambda` one), set the same environment variables, point `DATABASE_URL` at managed
  Postgres (pgvector enabled) and `STORAGE_PROVIDER=s3`. These avoid the Lambda
  cold-start/timeout caveats entirely.
