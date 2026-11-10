// Typed client for the Spring Boot API (same-origin via the Vite proxy).

export type Role = 'ADMIN' | 'REVIEWER' | 'USER'
export type DecisionType = 'APPROVE' | 'REJECT' | 'HUMAN_REVIEW'

export interface Session { token: string; role: Role; username: string; expiresAt: string }

export interface Claim {
  claimId: string; claimNumber: string; policyNumber: string; patientReference: string
  provider: string; diagnosis: string; procedureName: string; treatment: string
  hospitalizationStart: string; hospitalizationEnd: string; claimAmount: number
  currency: string; status: string; submittedAt: string
}
export interface Fact { key: string; value: string; valueType: string; status: string; source: string; confidence: number | null }
export interface Evidence {
  chunkId: string | null; pageNumber: number | null; section: string | null; heading: string | null
  similarity: number | null; rank: number; snippet: string
}
export interface Decision {
  decisionId: string; claimId: string; decision: DecisionType; confidence: number | null
  reasonCodes: string[]; missingInformation: string[]; uncertainties: string[]
  supportingEvidence: Evidence[]; summary: string; modelProvider: string; model: string
  promptVersion: string; latencyMs: number | null; explanation: string | null; createdAt: string
}
export interface Policy {
  policyId: string; policyNumber: string; policyType: string; status: string
  effectiveDate: string; expiryDate: string; coverageLimit: number; deductible: number
  copayPercentage: number; currency: string; exclusions: string | null; waitingPeriods: string | null
}
export interface PolicyChunk {
  chunkId: string; documentId: string; chunkIndex: number; pageNumber: number | null
  section: string | null; heading: string | null; text: string; tokenCount: number | null
  embeddingModel: string | null; embeddingDimensions: number | null
}
export interface AuditEvent {
  id: string; eventType: string; modelProvider: string | null; modelName: string | null
  promptVersion: string | null; finalDecision: string | null; latencyMs: number | null
  humanOverride: boolean | null; traceId: string | null; detail: string | null; createdAt: string
}
export interface Review {
  reviewId: string; reviewerId: string | null; reviewDecision: string; reason: string | null; previousAiDecision: string | null
  finalDecision: string; agreedWithAi: boolean | null; reviewedAt: string
}
interface Page<T> { content: T[]; totalElements: number }

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}

const KEY = 'hcda.session'

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as Session
    return new Date(s.expiresAt).getTime() > Date.now() ? s : null
  } catch { return null }
}
export function saveSession(s: Session | null) {
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s))
    else sessionStorage.removeItem(KEY)
  } catch { /* private mode */ }
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const res = await fetch(path, { ...init, headers })
  if (res.status === 401 && token) onUnauthorized()
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try { const body = await res.json(); message = body.message ?? body.error ?? message } catch { /* not json */ }
    throw new ApiError(res.status, message)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : null) as T
}

export async function login(username: string, password: string): Promise<Session> {
  const r = await request<{ token: string; role: Role; expiresAt: string }>('/api/v1/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  })
  return { token: r.token, role: r.role, expiresAt: r.expiresAt, username }
}

export const api = (s: Session) => ({
  claims: () => request<Page<Claim>>('/api/v1/claims?size=100&sort=claimNumber,asc', {}, s.token).then(p => p.content),
  claim: (id: string) => request<Claim>(`/api/v1/claims/${id}`, {}, s.token),
  facts: (id: string) => request<Fact[]>(`/api/v1/claims/${id}/facts`, {}, s.token),
  policies: () => request<Page<Policy>>('/api/v1/policies?size=100', {}, s.token).then(p => p.content),
  chunks: (policyId: string) => request<PolicyChunk[]>(`/api/v1/policies/${policyId}/chunks`, {}, s.token),
  analyze: (id: string) => request<Decision>(`/api/v1/claims/${id}/analyze`, { method: 'POST' }, s.token),
  decision: (id: string) => request<Decision>(`/api/v1/claims/${id}/decision`, {}, s.token),
  explain: (id: string) => request<{ explanation: string }>(`/api/v1/claims/${id}/explain`, { method: 'POST' }, s.token),
  audit: (id: string) => request<AuditEvent[]>(`/api/v1/claims/${id}/audit`, {}, s.token),
  review: (id: string, reviewDecision: string, reason: string) =>
    request<Review>(`/api/v1/claims/${id}/review`, { method: 'POST', body: JSON.stringify({ reviewDecision, reason }) }, s.token),
})

// ---------- Streamed analysis (Server-Sent Events over fetch, so the JWT header can be sent) ----------

export type ServerStage = 'AUTH' | 'LOAD' | 'RETRIEVE' | 'RULES' | 'AI' | 'GUARDRAILS' | 'PERSIST'
export interface StageEvent {
  stage: ServerStage
  status: 'STARTED' | 'COMPLETED' | 'SKIPPED' | 'FAILED'
  elapsedMs: number
  data: Record<string, unknown>
}

/** POST /analyze/stream: calls onStage for each live stage event; resolves with the final decision. */
export async function analyzeStream(s: Session, claimId: string, onStage: (e: StageEvent) => void): Promise<Decision> {
  const res = await fetch(`/api/v1/claims/${claimId}/analyze/stream`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${s.token}`, Accept: 'text/event-stream' },
  })
  if (res.status === 401) onUnauthorized()
  if (!res.ok || !res.body) {
    let message = `${res.status} ${res.statusText}`
    try { const body = await res.json(); message = body.message ?? message } catch { /* not json */ }
    throw new ApiError(res.status, message)
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (value) buffer += value.replace(/\r\n/g, '\n')
    let cut: number
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, cut)
      buffer = buffer.slice(cut + 2)
      let name = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) name = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5))
      }
      if (!data.length) continue
      const payload = JSON.parse(data.join('\n'))
      if (name === 'stage') onStage(payload as StageEvent)
      else if (name === 'decision') { void reader.cancel(); return payload as Decision }
      else if (name === 'error') { void reader.cancel(); throw new ApiError(payload.status ?? 500, payload.message ?? 'Analysis failed') }
    }
    if (done) throw new ApiError(502, 'The analysis stream ended without a decision.')
  }
}

export async function backendHealth(): Promise<{ up: boolean; ai?: string }> {
  try {
    const r = await fetch('/actuator/health')
    const j = await r.json()
    return { up: j.status === 'UP', ai: j.components?.aiProvider?.details?.provider }
  } catch { return { up: false } }
}
