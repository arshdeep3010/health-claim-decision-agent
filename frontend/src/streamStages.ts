// Turns live server stage events into what each pipeline row shows.
import type { ServerStage, StageEvent } from './api'
import { DECISION_COPY, humanize, type StageId, type StageStatus } from './pipeline'

export const STAGE_ID: Record<ServerStage, StageId> = {
  AUTH: 'auth', LOAD: 'load', RETRIEVE: 'rag', RULES: 'rules', AI: 'ai', GUARDRAILS: 'guard', PERSIST: 'audit',
}

const list = (v: unknown) => (Array.isArray(v) ? (v as string[]) : [])
const str = (v: unknown) => (v == null ? '' : String(v))

/** Server-measured duration, e.g. "<1 ms", "38 ms", "4.2 s". */
export function took(ms: number) {
  if (ms < 1) return '<1 ms'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

const OUTCOMES = ['APPROVE', 'HUMAN_REVIEW', 'REJECT'] as const

/** A decision model's per-outcome probabilities, or null when the engine gave none. */
export function probabilities(v: unknown): Record<string, number> | null {
  if (!v || typeof v !== 'object') return null
  const out: Record<string, number> = {}
  for (const k of OUTCOMES) {
    const p = (v as Record<string, unknown>)[k]
    if (typeof p === 'number') out[k] = p
  }
  return Object.keys(out).length ? out : null
}

/** "approve 99% · review 1% · reject 0%" */
export function probabilityLine(p: Record<string, number>) {
  const name: Record<string, string> = { APPROVE: 'approve', HUMAN_REVIEW: 'review', REJECT: 'reject' }
  return OUTCOMES.filter(k => k in p).map(k => `${name[k]} ${Math.round(p[k] * 100)}%`).join(' · ')
}

/** The final display state for a COMPLETED / SKIPPED / FAILED event. */
export function finalStatus(e: StageEvent): StageStatus {
  const d = e.data ?? {}
  const t = took(e.elapsedMs)
  if (e.status === 'FAILED') {
    const what: Record<ServerStage, string> = {
      AUTH: 'Not authorized', LOAD: 'Claim could not be loaded', RETRIEVE: 'Retrieval failed',
      RULES: 'Rules failed', AI: 'AI provider error', GUARDRAILS: 'Guardrails failed', PERSIST: 'Could not save',
    }
    return { state: 'failed', result: what[e.stage], detail: e.stage === 'AI' || e.stage === 'RETRIEVE' ? ['safely routed to human review', t] : [t] }
  }
  if (e.status === 'SKIPPED') {
    const reason = str(d.reason)
    const text = reason === 'RULES_DECIDED' ? 'Not needed: the rules decided'
      : reason === 'RAG_FAILURE' ? 'Skipped: no policy evidence'
        : reason === 'IDEMPOTENT_REPLAY' ? 'Replayed an earlier decision'
          : reason === 'NO_QUERY_TERMS' ? 'Nothing to search for' : 'Skipped'
    return { state: 'skipped', result: text, detail: e.stage === 'AI' ? ['no AI call was made'] : [] }
  }

  switch (e.stage) {
    case 'AUTH':
      return { state: 'done', tone: 'pass', result: `Token verified · role ${list(d.roles).join(', ') || 'none'}`, detail: [`signed in as ${str(d.principal)}`] }
    case 'LOAD':
      return { state: 'done', tone: 'pass', result: `${str(d.facts)} facts loaded`,
        detail: [`${str(d.knownFacts)} known`, `${str(d.policyNumber)} · ${str(d.policyStatus).toLowerCase()}`, t] }
    case 'RETRIEVE': {
      const n = Number(d.clauses ?? 0)
      return n > 0
        ? { state: 'done', tone: 'pass', result: `${n} policy clauses retrieved`, detail: [`top similarity ${Number(d.topSimilarity ?? 0).toFixed(2)}`, `top-k ${str(d.topK)}`, t, str(d.embeddingModel)].filter(Boolean) }
        : { state: 'done', tone: 'warn', result: 'No matching clauses', detail: ['no policy wording indexed for this policy', t] }
    }
    case 'RULES': {
      const flagged = list(d.flagged)
      const passed = list(d.passed)
      if (d.outcome === 'ALLOW') return { state: 'done', tone: 'pass', result: `All ${passed.length} applicable rules passed`, detail: [`${str(d.evaluated)} evaluated`, t] }
      return d.outcome === 'REJECT'
        ? { state: 'done', tone: 'stop', result: 'Blocked by a policy rule', detail: [...flagged.map(humanize), t] }
        : { state: 'done', tone: 'warn', result: 'Referred for review', detail: [...flagged.map(humanize), t] }
    }
    case 'AI': {
      const suggestion = str(d.suggestion) || 'HUMAN_REVIEW'
      const probs = probabilities(d.probabilities)
      const conf = typeof d.confidence === 'number' ? `${Math.round(d.confidence * 100)}% confidence` : 'no confidence given'
      const sources = Array.isArray(d.sources) ? d.sources.length : 0
      const who = d.engine === 'decision-model' ? 'Decision model' : 'AI'
      return { state: 'done', tone: DECISION_COPY[suggestion]?.tone ?? 'warn',
        result: `${who} suggests ${suggestion.replace('_', ' ').toLowerCase()}`,
        detail: [probs ? probabilityLine(probs) : conf, t, str(d.model), sources ? `${sources} web source${sources === 1 ? '' : 's'}` : '']
          .filter(Boolean) }
    }
    case 'GUARDRAILS': {
      const copy = DECISION_COPY[str(d.decision)] ?? DECISION_COPY.HUMAN_REVIEW
      const overrides = list(d.overrides)
      return { state: 'done', tone: copy.tone, result: `Final verdict: ${copy.word}`,
        detail: overrides.length ? overrides.map(humanize) : [d.decidedBy === 'RULES' ? 'Decided by rules' : 'Java confirmed the AI outcome'] }
    }
    case 'PERSIST':
      return { state: 'done', tone: 'pass', result: 'Decision saved & audited',
        detail: [d.traceId ? `trace ${str(d.traceId).slice(0, 8)}` : 'audit event recorded', `decision ${str(d.decisionId).slice(0, 8)}`, t] }
  }
}

/**
 * Applies events in order, keeping each stage visibly "running" for at least
 * `minRunMs` so sub-millisecond stages can still be followed. A stage that is
 * genuinely slow (the AI call) simply stays running until its event arrives.
 * Pacing only delays when a result is drawn; it never changes the result.
 */
export class StagePacer {
  private queue: Promise<void> = Promise.resolve()
  private startedAt = new Map<StageId, number>()
  private cancelled = false
  private readonly apply: (id: StageId, s: StageStatus) => void
  private readonly minRunMs: number

  constructor(apply: (id: StageId, s: StageStatus) => void, minRunMs = 520) {
    this.apply = apply
    this.minRunMs = minRunMs
  }

  push(e: StageEvent) {
    this.queue = this.queue.then(() => this.handle(e))
  }

  /** Resolves once everything queued so far is on screen. */
  drain() { return this.queue }

  cancel() { this.cancelled = true }

  private async handle(e: StageEvent) {
    if (this.cancelled) return
    const id = STAGE_ID[e.stage]
    if (e.status === 'STARTED') {
      this.startedAt.set(id, performance.now())
      this.apply(id, { state: 'running' })
      return
    }
    if (!this.startedAt.has(id)) {
      // Stages reported only on completion (auth, skipped AI, guardrails) still get a visible beat.
      this.startedAt.set(id, performance.now())
      if (e.status !== 'SKIPPED') this.apply(id, { state: 'running' })
    }
    const wait = this.minRunMs - (performance.now() - (this.startedAt.get(id) ?? 0))
    if (wait > 0) await new Promise(r => setTimeout(r, e.status === 'SKIPPED' ? Math.min(wait, 260) : wait))
    if (!this.cancelled) this.apply(id, finalStatus(e))
  }
}
