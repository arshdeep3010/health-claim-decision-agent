// The decision pipeline, stage by stage. Each stage mirrors a real step in
// DecisionOrchestrator.analyze() and lists only technology the project uses.

export type StageId = 'auth' | 'load' | 'rag' | 'rules' | 'ai' | 'guard' | 'audit'
export type StageState = 'idle' | 'running' | 'done' | 'skipped' | 'failed'
export type Tone = 'pass' | 'warn' | 'stop'

export interface StageDef {
  id: StageId
  index: string
  glyph: string
  short: string
  title: string
  what: string
  tech: string[]
}

export const STAGES: StageDef[] = [
  { id: 'auth', short: 'Authenticate', index: '01', glyph: 'JWT', title: 'Authenticate the request',
    what: 'A stateless JWT is verified by a Spring Security filter chain and the caller’s role is checked.',
    tech: ['Spring Security', 'JWT · HS384', 'Role-based access'] },
  { id: 'load', short: 'Load data', index: '02', glyph: 'SQL', title: 'Load claim, facts & policy',
    what: 'The claim, its structured facts and its policy are read from PostgreSQL through Spring Data JPA.',
    tech: ['Spring Data JPA', 'PostgreSQL 16', 'Flyway'] },
  { id: 'rag', short: 'Retrieve', index: '03', glyph: 'RAG', title: 'Retrieve policy evidence',
    what: 'Diagnosis, procedure and treatment become a query, embedded and matched against policy-wording chunks by vector similarity.',
    tech: ['pgvector', 'NVIDIA NIM embeddings', '384-d', 'PDFBox ingestion'] },
  { id: 'rules', short: 'Rules', index: '04', glyph: 'IF', title: 'Run deterministic rules',
    what: 'Seven Java rules check policy dates, exclusions, coverage limit, waiting periods, pre-existing status and missing or conflicting facts.',
    tech: ['Java 21', 'Rule engine', 'No AI involved'] },
  { id: 'ai', short: 'AI judge', index: '05', glyph: 'AI', title: 'Ask the AI judge',
    what: 'Only when every rule passes, Mercury reviews the facts and evidence and must answer in a strict JSON schema.',
    tech: ['Mercury 2.5', 'OpenRouter', 'JSON schema', 'Resilience4j'] },
  { id: 'guard', short: 'Guardrails', index: '06', glyph: '⚖', title: 'Apply safety guardrails',
    what: 'Java owns the verdict: low confidence, an unsupported AI rejection or any AI failure is routed to a human.',
    tech: ['Spring Boot 3.5', 'Confidence ≥ 0.5', 'Human-in-the-loop'] },
  { id: 'audit', short: 'Audit', index: '07', glyph: 'LOG', title: 'Persist & audit',
    what: 'The decision, its evidence and an audit event with a trace id are stored; latency and outcome metrics are recorded.',
    tech: ['Audit trail', 'Micrometer', 'Prometheus'] },
]

export interface StageStatus { state: StageState; tone?: Tone; result?: string; detail?: string[] }
export type PipelineStatus = Record<StageId, StageStatus>

export const idlePipeline = (): PipelineStatus =>
  Object.fromEntries(STAGES.map(s => [s.id, { state: 'idle' }])) as PipelineStatus

export const RULE_LABELS: Record<string, string> = {
  POLICY_ACTIVE: 'Policy active', POLICY_EXPIRED: 'Policy expired', POLICY_CANCELLED: 'Policy cancelled',
  POLICY_NOT_EFFECTIVE: 'Policy not yet effective', NO_EXCLUSION_MATCH: 'No exclusion matched',
  PROCEDURE_EXCLUDED: 'Procedure excluded', WITHIN_COVERAGE_LIMIT: 'Within coverage limit',
  CLAIM_EXCEEDS_COVERAGE_LIMIT: 'Exceeds coverage limit', WAITING_PERIOD_SATISFIED: 'Waiting period satisfied',
  WAITING_PERIOD_VIOLATION: 'Within waiting period', PRE_EXISTING_STATUS_KNOWN: 'Pre-existing status known',
  PRE_EXISTING_STATUS_UNKNOWN: 'Pre-existing status unknown', REQUIRED_INFORMATION_PRESENT: 'Required facts present',
  MISSING_REQUIRED_INFORMATION: 'Missing required facts', NO_CONFLICTING_INFORMATION: 'No conflicting facts',
  CONFLICTING_INFORMATION: 'Conflicting facts', AI_PROVIDER_FAILURE: 'AI provider failed',
  LOW_AI_CONFIDENCE: 'AI confidence too low', AI_SUGGESTED_REJECT_NO_DETERMINISTIC_BASIS: 'AI reject overruled',
  RAG_FAILURE: 'Evidence retrieval failed',
  AI_DECISION: 'Analysis recorded', HUMAN_REVIEW: 'Needs review', HUMAN_REVIEW_SUBMITTED: 'Human review submitted',
}

export const humanize = (code: string) =>
  RULE_LABELS[code] ?? code.toLowerCase().replace(/_/g, ' ').replace(/^\w/, c => c.toUpperCase())

export const DECISION_COPY: Record<string, { word: string; tone: Tone; line: string }> = {
  APPROVE: { word: 'Approved', tone: 'pass', line: 'Covered by the policy and supported by the evidence.' },
  REJECT: { word: 'Rejected', tone: 'stop', line: 'A deterministic policy rule rules this claim out.' },
  HUMAN_REVIEW: { word: 'Needs review', tone: 'warn', line: 'Sent to a human reviewer. The system never guesses.' },
}
