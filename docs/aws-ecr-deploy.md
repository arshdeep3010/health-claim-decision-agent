# AWS ECR + Lambda — exact commands

Copy-paste runbook to push the image to **Amazon ECR** and run it on **AWS Lambda**
(container image) behind **API Gateway**. Replace values in the first block only.

> SYNTHETIC DATA ONLY. Portfolio project — not real insurance infrastructure.

---

## 0. Set variables

```bash
export AWS_REGION=ap-south-1                 # your region
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPO=health-claim-decision-agent
export IMAGE_TAG=latest
export REGISTRY=$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
export IMAGE=$REGISTRY/$ECR_REPO:$IMAGE_TAG
export FUNCTION=health-claim-decision-agent
```

Check the CLI is wired to the right account:

```bash
aws sts get-caller-identity
```

---

## 1. Create the ECR repository (once)

```bash
aws ecr create-repository \
  --repository-name "$ECR_REPO" \
  --image-scanning-configuration scanOnPush=true \
  --region "$AWS_REGION"
```

## 2. Log Docker in to ECR

```bash
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"
```

## 3. Build the Lambda image, tag, and push

The Lambda image = app + AWS Lambda Web Adapter (`Dockerfile.lambda`). Lambda runs
**linux/amd64** by default, so build for that platform explicitly (works from
Windows/macOS/Linux):

```bash
# from the project root
docker build --platform linux/amd64 -f Dockerfile.lambda -t "$IMAGE" .
docker push "$IMAGE"
```

> Prefer arm64 (cheaper)? Build `--platform linux/arm64` and create the function
> with `--architectures arm64` in step 6.

Verify it is in ECR:

```bash
aws ecr describe-images --repository-name "$ECR_REPO" --region "$AWS_REGION" \
  --query 'imageDetails[].imageTags'
```

---

## 4. One-time AWS prerequisites

These must exist before the function can serve traffic. (Full explanations in
[`deploy-aws.md`](deploy-aws.md).)

**4a. S3 bucket for documents**

```bash
export DOC_BUCKET=hcda-documents-$ACCOUNT_ID
aws s3 mb "s3://$DOC_BUCKET" --region "$AWS_REGION"
```

**4b. RDS PostgreSQL 16 + pgvector** (create via console/CLI in your VPC), then once:

```bash
psql "postgresql://<user>:<pw>@<rds-endpoint>:5432/healthclaim" \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

Create an **RDS Proxy** in front of it (Lambda connection pooling).

**4c. Execution role** for the function (Lambda + VPC + S3 on the bucket):

```bash
cat > trust.json <<'JSON'
{ "Version": "2012-10-17",
  "Statement": [{ "Effect": "Allow",
    "Principal": { "Service": "lambda.amazonaws.com" },
    "Action": "sts:AssumeRole" }] }
JSON

aws iam create-role --role-name hcda-lambda-role \
  --assume-role-policy-document file://trust.json

aws iam attach-role-policy --role-name hcda-lambda-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole

# S3 access scoped to the bucket
cat > s3policy.json <<JSON
{ "Version": "2012-10-17",
  "Statement": [{ "Effect": "Allow",
    "Action": ["s3:GetObject","s3:PutObject","s3:DeleteObject"],
    "Resource": "arn:aws:s3:::$DOC_BUCKET/*" }] }
JSON
aws iam put-role-policy --role-name hcda-lambda-role \
  --policy-name hcda-s3 --policy-document file://s3policy.json

export ROLE_ARN=$(aws iam get-role --role-name hcda-lambda-role --query Role.Arn --output text)
```

---

## 5. (recommended) Store secrets

```bash
aws secretsmanager create-secret --name hcda/jwt-secret \
  --secret-string "$(openssl rand -base64 48)"
# reference it in the function env, or paste the value into JWT_SECRET below
```

---

## 6. Create the Lambda function from the image

```bash
aws lambda create-function \
  --function-name "$FUNCTION" \
  --package-type Image \
  --code ImageUri=$IMAGE \
  --role "$ROLE_ARN" \
  --architectures x86_64 \
  --timeout 29 \
  --memory-size 1536 \
  --vpc-config SubnetIds=subnet-aaa,subnet-bbb,SecurityGroupIds=sg-ccc \
  --environment "Variables={
      SPRING_PROFILES_ACTIVE=prod,
      DATABASE_URL=jdbc:postgresql://<rds-proxy-endpoint>:5432/healthclaim,
      DATABASE_USERNAME=<user>,
      DATABASE_PASSWORD=<pw>,
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2,
      STORAGE_PROVIDER=s3,
      S3_BUCKET=$DOC_BUCKET,
      S3_REGION=$AWS_REGION,
      EMBEDDING_PROVIDER=local,
      MERCURY_PROVIDER=noop,
      JWT_SECRET=<long-random>,
      CORS_ALLOWED_ORIGINS=https://your-frontend.example.com
  }" \
  --region "$AWS_REGION"
```

Enable **SnapStart** to cut JVM cold starts:

```bash
aws lambda put-function-configuration --function-name "$FUNCTION" \
  --snap-start ApplyOn=PublishedVersions --region "$AWS_REGION"
aws lambda publish-version --function-name "$FUNCTION" --region "$AWS_REGION"
```

To turn on real AI later, flip `MERCURY_PROVIDER=openrouter` and add
`MERCURY_BASE_URL`, `MERCURY_API_KEY`, `MERCURY_MODEL`. For semantic retrieval set
the embedding vars too (NVIDIA NIM: `EMBEDDING_PROVIDER=openai-compatible`,
`EMBEDDING_BASE_URL=https://integrate.api.nvidia.com/v1`,
`EMBEDDING_MODEL=nvidia/nemotron-3.5-embed-1b`, `EMBEDDING_API_KEY`,
`EMBEDDING_QUERY_INPUT_TYPE=query`, `EMBEDDING_PASSAGE_INPUT_TYPE=passage`,
`EMBEDDING_REQUEST_DIMENSIONS=true`, `EMBEDDING_TRUNCATE=END`,
`EMBEDDING_BATCH_SIZE=1`). It returns 384-d vectors, so `EMBEDDING_DIM=384` keeps the
existing schema; re-embed already-ingested documents with
`POST /api/v1/policies/{id}/ingest?reembed=true`.

---

## 7. Put it behind API Gateway (HTTP API)

```bash
export FN_ARN=$(aws lambda get-function --function-name "$FUNCTION" \
  --query Configuration.FunctionArn --output text --region "$AWS_REGION")

export API_ID=$(aws apigatewayv2 create-api \
  --name hcda-api --protocol-type HTTP --target "$FN_ARN" \
  --region "$AWS_REGION" --query ApiId --output text)

# allow API Gateway to invoke the function
aws lambda add-permission --function-name "$FUNCTION" \
  --statement-id apigw --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:$AWS_REGION:$ACCOUNT_ID:$API_ID/*" \
  --region "$AWS_REGION"

export BASE=$(aws apigatewayv2 get-api --api-id "$API_ID" \
  --query ApiEndpoint --output text --region "$AWS_REGION")
echo "API base: $BASE"
```

---

## 8. Verify

```bash
curl -s "$BASE/actuator/health"      # expect {"status":"UP", ... "db":{"status":"UP"}}
```

Create an admin (see "first admin" in `deploy-aws.md`), then:

```bash
TOKEN=$(curl -s "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<pw>"}' | jq -r .token)

curl -s "$BASE/api/v1/policies" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"policyNumber":"POL-1","policyType":"INDIVIDUAL","effectiveDate":"2025-01-01","expiryDate":"2027-01-01","coverageLimit":500000,"currency":"INR"}'
```

---

## 9. Redeploy (after code changes)

```bash
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"
docker build --platform linux/amd64 -f Dockerfile.lambda -t "$IMAGE" .
docker push "$IMAGE"

aws lambda update-function-code \
  --function-name "$FUNCTION" --image-uri "$IMAGE" \
  --region "$AWS_REGION"
```

---

## Notes / gotchas

- **Flyway** runs automatically on startup — no manual migration step; the DB user
  needs DDL rights on first boot.
- **Image platform** must match the function `--architectures` (amd64 ↔ x86_64,
  arm64 ↔ arm64), or the function fails to start.
- **29s API Gateway timeout**: synchronous analyze can approach it under load;
  move to the async job model (spec §24) for production scale.
- **No secrets in the image** — all via function environment / Secrets Manager.
- The **same ECR image** (built from `Dockerfile`, without the adapter) also runs on
  **ECS Fargate / App Runner** if you'd rather avoid Lambda's cold-start/timeout limits.
