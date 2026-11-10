-- =====================================================================
-- V1__init_schema.sql
-- Initial schema for the Health Claim Decision Agent.
-- SYNTHETIC DATA ONLY. Enum-like columns use VARCHAR + CHECK constraints
-- and are mapped in Java via @Enumerated(STRING).
-- The pgvector dimension is injected via the Flyway placeholder ${embeddingDim}.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- patient
-- ---------------------------------------------------------------------
CREATE TABLE patient (
    id            uuid PRIMARY KEY,
    reference     varchar(64)  NOT NULL UNIQUE,
    display_name  varchar(255) NOT NULL,
    date_of_birth date,
    gender        varchar(16),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- policy
-- ---------------------------------------------------------------------
CREATE TABLE policy (
    id                uuid PRIMARY KEY,
    policy_number     varchar(64)  NOT NULL UNIQUE,
    policy_type       varchar(32)  NOT NULL,
    holder_patient_id uuid REFERENCES patient(id),
    effective_date    date         NOT NULL,
    expiry_date       date         NOT NULL,
    coverage_limit    numeric(14,2) NOT NULL,
    deductible        numeric(14,2) NOT NULL DEFAULT 0,
    copay_percentage  numeric(5,2)  NOT NULL DEFAULT 0,
    currency          varchar(3)   NOT NULL DEFAULT 'INR',
    status            varchar(16)  NOT NULL,
    waiting_periods   text,
    exclusions        text,
    coverage_rules    text,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_policy_type   CHECK (policy_type IN ('INDIVIDUAL','FAMILY_FLOATER','GROUP','SENIOR_CITIZEN','TRAVEL')),
    CONSTRAINT chk_policy_status CHECK (status IN ('ACTIVE','EXPIRED','CANCELLED'))
);
CREATE INDEX idx_policy_holder ON policy(holder_patient_id);

-- ---------------------------------------------------------------------
-- policy_document
-- ---------------------------------------------------------------------
CREATE TABLE policy_document (
    id                uuid PRIMARY KEY,
    policy_id         uuid NOT NULL REFERENCES policy(id),
    storage_key       varchar(255) NOT NULL,
    original_filename varchar(255),
    content_type      varchar(128),
    file_size_bytes   bigint,
    sha256            varchar(64),
    status            varchar(16) NOT NULL,
    page_count        integer,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_policy_document_status CHECK (status IN ('UPLOADED','INGESTING','INGESTED','FAILED'))
);
CREATE INDEX idx_policy_document_policy_id ON policy_document(policy_id);

-- ---------------------------------------------------------------------
-- policy_chunk  (RAG units; embedding managed via native SQL, not JPA)
-- ---------------------------------------------------------------------
CREATE TABLE policy_chunk (
    id              uuid PRIMARY KEY,
    document_id     uuid NOT NULL REFERENCES policy_document(id),
    policy_id       uuid NOT NULL REFERENCES policy(id),
    chunk_index     integer NOT NULL,
    page_number     integer,
    section         varchar(255),
    heading         varchar(512),
    chunk_text      text NOT NULL,
    token_count     integer,
    embedding       vector(${embeddingDim}),
    embedding_model varchar(128),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_chunk_doc_idx UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_policy_chunk_policy_id   ON policy_chunk(policy_id);
CREATE INDEX idx_policy_chunk_document_id ON policy_chunk(document_id);
-- HNSW (cosine) vector index; valid on an empty table, unlike ivfflat.
CREATE INDEX idx_policy_chunk_embedding   ON policy_chunk USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------
-- claim
-- ---------------------------------------------------------------------
CREATE TABLE claim (
    id                   uuid PRIMARY KEY,
    claim_number         varchar(64) NOT NULL UNIQUE,
    policy_id            uuid NOT NULL REFERENCES policy(id),
    patient_id           uuid NOT NULL REFERENCES patient(id),
    provider             varchar(255),
    diagnosis            varchar(512),
    procedure_name       varchar(512),
    treatment            text,
    hospitalization_start date,
    hospitalization_end   date,
    claim_amount         numeric(14,2) NOT NULL,
    currency             varchar(3) NOT NULL DEFAULT 'INR',
    status               varchar(16) NOT NULL,
    submitted_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_claim_status CHECK (status IN ('SUBMITTED','PROCESSING','ANALYZED','APPROVED','REJECTED','HUMAN_REVIEW','FAILED'))
);
CREATE INDEX idx_claim_policy_id  ON claim(policy_id);
CREATE INDEX idx_claim_patient_id ON claim(patient_id);
CREATE INDEX idx_claim_status     ON claim(status);
CREATE INDEX idx_claim_created_at ON claim(created_at);

-- ---------------------------------------------------------------------
-- claim_document
-- ---------------------------------------------------------------------
CREATE TABLE claim_document (
    id                uuid PRIMARY KEY,
    claim_id          uuid NOT NULL REFERENCES claim(id),
    doc_type          varchar(32) NOT NULL,
    storage_key       varchar(255) NOT NULL,
    original_filename varchar(255),
    content_type      varchar(128),
    file_size_bytes   bigint,
    sha256            varchar(64),
    status            varchar(16) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_claim_document_type   CHECK (doc_type IN ('DISCHARGE_SUMMARY','INVOICE','PRESCRIPTION','LAB_REPORT','OTHER')),
    CONSTRAINT chk_claim_document_status CHECK (status IN ('UPLOADED','INGESTING','INGESTED','FAILED'))
);
CREATE INDEX idx_claim_document_claim_id ON claim_document(claim_id);

-- ---------------------------------------------------------------------
-- claim_fact  (structured facts extracted from claim/documents)
-- ---------------------------------------------------------------------
CREATE TABLE claim_fact (
    id         uuid PRIMARY KEY,
    claim_id   uuid NOT NULL REFERENCES claim(id),
    fact_key   varchar(64) NOT NULL,
    fact_value text,
    value_type varchar(16),
    status     varchar(16) NOT NULL,
    source     varchar(16),
    confidence double precision,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_claim_fact UNIQUE (claim_id, fact_key),
    CONSTRAINT chk_claim_fact_status  CHECK (status IN ('KNOWN','UNKNOWN','CONFLICTING')),
    CONSTRAINT chk_claim_fact_valtype CHECK (value_type IS NULL OR value_type IN ('STRING','NUMBER','BOOLEAN','DATE')),
    CONSTRAINT chk_claim_fact_source  CHECK (source IS NULL OR source IN ('EXTRACTED','PROVIDED','DERIVED'))
);
CREATE INDEX idx_claim_fact_claim_id ON claim_fact(claim_id);

-- ---------------------------------------------------------------------
-- decision  (one current decision per claim; history lives in audit_log)
-- ---------------------------------------------------------------------
CREATE TABLE decision (
    id                  uuid PRIMARY KEY,
    claim_id            uuid NOT NULL UNIQUE REFERENCES claim(id),
    decision            varchar(16) NOT NULL,
    confidence          double precision,
    reason_codes        text,
    supporting_evidence text,
    missing_information text,
    uncertainties       text,
    model_provider      varchar(64),
    model_name          varchar(128),
    model_version       varchar(64),
    prompt_version      varchar(64),
    latency_ms          bigint,
    explanation         text,
    idempotency_key     varchar(128) UNIQUE,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_decision_type CHECK (decision IN ('APPROVE','REJECT','HUMAN_REVIEW'))
);
CREATE INDEX idx_decision_claim_id ON decision(claim_id);

-- ---------------------------------------------------------------------
-- decision_evidence  (snapshot of retrieved policy evidence per decision)
-- ---------------------------------------------------------------------
CREATE TABLE decision_evidence (
    id              uuid PRIMARY KEY,
    decision_id     uuid NOT NULL REFERENCES decision(id),
    policy_chunk_id uuid REFERENCES policy_chunk(id),
    document_id     uuid,
    policy_id       uuid,
    page_number     integer,
    section         varchar(255),
    similarity      double precision,
    evidence_rank   integer,
    snippet         text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_decision_evidence_decision_id ON decision_evidence(decision_id);

-- ---------------------------------------------------------------------
-- human_review
-- ---------------------------------------------------------------------
CREATE TABLE human_review (
    id                  uuid PRIMARY KEY,
    claim_id            uuid NOT NULL REFERENCES claim(id),
    decision_id         uuid REFERENCES decision(id),
    reviewer_id         varchar(128) NOT NULL,
    review_decision     varchar(32) NOT NULL,
    reason              text,
    previous_ai_decision varchar(16),
    final_decision      varchar(16),
    agreed_with_ai      boolean,
    reviewed_at         timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_review_decision CHECK (review_decision IN ('APPROVE','REJECT','REQUEST_INFORMATION')),
    CONSTRAINT chk_review_prev_ai  CHECK (previous_ai_decision IS NULL OR previous_ai_decision IN ('APPROVE','REJECT','HUMAN_REVIEW')),
    CONSTRAINT chk_review_final    CHECK (final_decision IS NULL OR final_decision IN ('APPROVE','REJECT','HUMAN_REVIEW'))
);
CREATE INDEX idx_human_review_claim_id    ON human_review(claim_id);
CREATE INDEX idx_human_review_decision_id ON human_review(decision_id);

-- ---------------------------------------------------------------------
-- audit_log  (append-only; NO secrets, NO raw sensitive data in detail)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id             uuid PRIMARY KEY,
    claim_id       uuid REFERENCES claim(id),
    event_type     varchar(64) NOT NULL,
    model_provider varchar(64),
    model_name     varchar(128),
    model_version  varchar(64),
    prompt_version varchar(64),
    final_decision varchar(16),
    latency_ms     bigint,
    human_override boolean,
    trace_id       varchar(64),
    detail         text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_claim_id   ON audit_log(claim_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX idx_audit_log_event_type ON audit_log(event_type);
