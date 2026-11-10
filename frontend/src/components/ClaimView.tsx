import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { analyzeStream, api, ApiError, type AuditEvent, type Claim, type Decision, type Fact, type Policy, type Review, type Session, type StageEvent } from '../api'
import { Pipeline } from './Pipeline'
import { StagePacer } from '../streamStages'
import { DECISION_COPY, humanize, idlePipeline, type PipelineStatus, type StageId, type StageStatus } from '../pipeline'
import { money, parseJson, statusLabel, statusTone } from '../format'
import scenarios from '../data/scenarios.json'

const LABELS = scenarios as Record<string, { scenario: string; expected: string }>

/** The exact package the decision layer sends to the LLM (DecisionPromptPayload on the server). */
interface AiInput {
  claim: { diagnosis: string; procedure: string; treatment: string; claimAmount: number; currency: string }
  policy: { coverageLimit: number; exclusions: string[] }
  extractedFacts: { key: string; value: string | null; status: string }[]
  policyEvidence: { section: string | null; page: number | null; text: string }[]
  ruleReasonCodes: string[]
}
interface AiOutput { suggestion: string; confidence: number | null; model: string; summary: string; reasonCodes: string[]; elapsedMs: number }
type AiTrace =
  | { kind: 'called'; provider: string; instructions: string; input: AiInput; output?: AiOutput; failed?: boolean }
  | { kind: 'skipped'; reason: string; flagged: string[]; outcome: string }
const rise = {
  initial: { opacity: 0, y: 18 }, animate: { opacity: 1, y: 0 }, exit: { opacity: 0, y: 8 },
  transition: { type: 'spring' as const, bounce: 0, duration: 0.7 },
}


export function ClaimView({ session, claimId, onBack }: { session: Session; claimId: string; onBack: () => void }) {
  const client = api(session)
  const [claim, setClaim] = useState<Claim | null>(null)
  const [facts, setFacts] = useState<Fact[]>([])
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [decision, setDecision] = useState<Decision | null>(null)
  const [audit, setAudit] = useState<AuditEvent[] | null>(null)
  const [status, setStatus] = useState<PipelineStatus>(idlePipeline)
  const [running, setRunning] = useState(false)
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [freshRun, setFreshRun] = useState(false)
  const [trace, setTrace] = useState<AiTrace | null>(null)
  const pacer = useRef<StagePacer | null>(null)
  const isAdmin = session.role === 'ADMIN'
  const canReview = session.role === 'ADMIN' || session.role === 'REVIEWER'

  const loadAudit = useCallback(async () => {
    if (!isAdmin) return null
    const events = await client.audit(claimId)
    setAudit(events)
    return events
  }, [claimId, isAdmin]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const [c, f, policies] = await Promise.all([client.claim(claimId), client.facts(claimId), client.policies()])
        if (!alive) return
        setClaim(c); setFacts(f); setPolicy(policies.find(p => p.policyNumber === c.policyNumber) ?? null)
        client.decision(claimId).then(d => alive && setDecision(d)).catch(() => {})
        loadAudit().catch(() => {})
      } catch (e) { if (alive) setError((e as Error).message) }
    })()
    return () => { alive = false; pacer.current?.cancel() }
  }, [claimId]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = (id: StageId, s: StageStatus) => setStatus(p => ({ ...p, [id]: s }))

  async function run() {
    if (running) return
    setRunning(true); setError(null); setElapsed(null); setFreshRun(true); setDecision(null); setTrace(null)
    setStatus(idlePipeline())
    let rules: { outcome: string; flagged: string[] } = { outcome: '', flagged: [] }
    // Keep what the AI was given and what it answered, straight from the live stage events.
    const capture = (e: StageEvent) => {
      const d = e.data ?? {}
      if (e.stage === 'RULES' && e.status === 'COMPLETED') {
        rules = { outcome: String(d.outcome ?? ''), flagged: (d.flagged as string[]) ?? [] }
      } else if (e.stage === 'AI' && e.status === 'STARTED' && d.input) {
        setTrace({ kind: 'called', provider: String(d.provider ?? ''), instructions: String(d.instructions ?? ''), input: d.input as AiInput })
      } else if (e.stage === 'AI' && e.status === 'COMPLETED') {
        const output: AiOutput = {
          suggestion: String(d.suggestion ?? ''), confidence: typeof d.confidence === 'number' ? d.confidence : null,
          model: String(d.model ?? ''), summary: String(d.summary ?? ''), reasonCodes: (d.reasonCodes as string[]) ?? [], elapsedMs: e.elapsedMs,
        }
        setTrace(t => (t && t.kind === 'called' ? { ...t, output } : t))
      } else if (e.stage === 'AI' && e.status === 'FAILED') {
        setTrace(t => (t && t.kind === 'called' ? { ...t, failed: true } : t))
      } else if (e.stage === 'AI' && e.status === 'SKIPPED') {
        setTrace({ kind: 'skipped', reason: String(d.reason ?? ''), ...rules })
      }
    }
    pacer.current?.cancel()
    const pacer_ = new StagePacer(set)
    pacer.current = pacer_
    const t0 = performance.now()
    try {
      // Every stage below is reported live by the server as it happens (SSE).
      const d = await analyzeStream(session, claimId, e => { pacer_.push(e); capture(e) })
      await pacer_.drain()
      setDecision(d)
      setElapsed(performance.now() - t0)
      // Refresh the input panels and audit trail with what the run just wrote.
      Promise.all([client.claim(claimId), client.facts(claimId), client.policies()])
        .then(([c, f, policies]) => { setClaim(c); setFacts(f); setPolicy(policies.find(p => p.policyNumber === c.policyNumber) ?? null) })
        .catch(() => {})
      loadAudit().catch(() => {})
    } catch (e) {
      await pacer_.drain()
      const msg = e instanceof ApiError && e.status === 429 ? 'Rate limit reached for AI endpoints. Wait a minute and retry.' : (e as Error).message
      setStatus(p => {
        const next = { ...p }
        const live = (Object.keys(next) as StageId[]).filter(k => next[k].state === 'running')
        for (const k of live) next[k] = { state: 'failed', result: 'Request failed', detail: [msg] }
        return next
      })
      setError(msg)
    } finally {
      setRunning(false)
    }
  }

  if (!claim) {
    return (
      <main className="container" style={{ paddingBlock: 64 }}>
        {error ? <div className="notice stop">{error}</div> : <div className="skeleton" style={{ height: 320 }} />}
      </main>
    )
  }

  const label = LABELS[claim.claimNumber]
  return (
    <main className="container" style={{ paddingBlock: 'clamp(32px, 5vw, 64px) 96px' }}>
      <button className="link-btn" onClick={onBack} style={{ fontSize: 15 }}>‹ All claims</button>

      <motion.header {...rise} style={{ margin: '22px 0 36px' }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <span className="mono" style={{ color: 'var(--text-3)' }}>{claim.claimNumber}</span>
          <span className={`chip ${statusTone(claim.status)}`}>{statusLabel(claim.status)}</span>
          {label && <span className="chip">{label.scenario}</span>}
        </div>
        <h1 className="headline" style={{ marginTop: 10 }}>{claim.diagnosis}</h1>
        <p className="subhead" style={{ marginTop: 8 }}>{claim.procedureName} · {money(claim.claimAmount, claim.currency)}</p>
      </motion.header>

      <div className="detail-grid">
        {/* Left: the inputs */}
        <div style={{ display: 'grid', gap: 24 }}>
          <Panel title="Claim">
            <dl className="kv">
              <dt>Provider</dt><dd>{claim.provider}</dd>
              <dt>Patient</dt><dd className="mono">{claim.patientReference}</dd>
              <dt>Treatment</dt><dd>{claim.treatment}</dd>
              <dt>Admitted</dt><dd>{claim.hospitalizationStart} → {claim.hospitalizationEnd}</dd>
              <dt>Amount</dt><dd>{money(claim.claimAmount, claim.currency)}</dd>
            </dl>
          </Panel>

          <Panel title="Extracted facts" note="What the rules and the AI actually see.">
            {facts.length === 0 && <p className="body-2">No facts extracted yet.</p>}
            {facts.map(f => (
              <div className="fact" key={f.key}>
                <span className="k">{humanize(f.key.replace(/([A-Z])/g, '_$1').toUpperCase())}</span>
                <span className="v">
                  {f.value ?? '-'}
                  {f.status !== 'KNOWN' && <span className={`chip ${f.status === 'CONFLICTING' ? 'stop' : 'warn'}`}>{f.status.toLowerCase()}</span>}
                </span>
              </div>
            ))}
          </Panel>

          {policy && <PolicyPanel policy={policy} />}
        </div>

        {/* Right: the decision */}
        <div style={{ display: 'grid', gap: 24 }}>
          <section className="card card-pad">
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }}>
              <div style={{ flex: '1 1 240px' }}>
                <p className="eyebrow accent" style={{ margin: 0 }}>Decision pipeline</p>
                <h2 className="title" style={{ marginTop: 6 }}>
                  {running ? 'Deciding…' : elapsed != null ? `Decided in ${(elapsed / 1000).toFixed(1)} s` : decision && !freshRun ? 'Run it again, live.' : 'Watch it decide.'}
                </h2>
              </div>
              <button className="btn btn-primary" onClick={run} disabled={running}>
                {running ? <><span className="spinner" /> Analyzing</> : decision ? 'Analyze again' : 'Analyze claim'}
              </button>
            </div>
            <div style={{ marginTop: 20, marginInline: -10 }}>
              <Pipeline status={status} />
            </div>
            <p className="body-2" style={{ fontSize: 13, marginTop: 14, marginBottom: 0 }}>
              Streamed live from <span className="mono">/analyze/stream</span> (Server-Sent Events): each stage is reported by the
              server as it happens, with server-measured timings. Stages that take under a millisecond are held on screen briefly so
              you can follow them.
            </p>
          </section>

          <AnimatePresence mode="popLayout">
            {decision && (
              <motion.div key={decision.decisionId} {...rise} style={{ display: 'grid', gap: 24 }}>
                <Verdict decision={decision} expected={label?.expected} stale={!freshRun} />
                <AiInputPanel trace={trace} decision={decision} />
                <EvidencePanel decision={decision} />
                <ExplainPanel claimId={claimId} session={session} decisionId={decision.decisionId} />
                {decision.decision === 'HUMAN_REVIEW' && canReview && (
                  <ReviewPanel claimId={claimId} session={session} onDone={() => {
                    client.claim(claimId).then(setClaim).catch(() => {})
                    loadAudit().catch(() => {})
                  }} />
                )}
              </motion.div>
            )}
          </AnimatePresence>

          {isAdmin && audit && audit.length > 0 && <AuditPanel events={audit} />}
        </div>
      </div>
    </main>
  )
}

function Panel({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return (
    <motion.section className="card card-pad" {...rise}>
      <h2 className="title" style={{ fontSize: 22 }}>{title}</h2>
      {note && <p className="body-2" style={{ margin: '4px 0 0' }}>{note}</p>}
      <div style={{ marginTop: 18 }}>{children}</div>
    </motion.section>
  )
}

function PolicyPanel({ policy }: { policy: Policy }) {
  const exclusions = parseJson<string[]>(policy.exclusions) ?? []
  const waits = parseJson<Record<string, number>>(policy.waitingPeriods) ?? {}
  return (
    <Panel title="Policy">
      <dl className="kv">
        <dt>Number</dt><dd className="mono">{policy.policyNumber}</dd>
        <dt>Status</dt><dd><span className={`chip ${policy.status === 'ACTIVE' ? 'pass' : 'stop'}`}>{policy.status.toLowerCase()}</span></dd>
        <dt>Valid</dt><dd>{policy.effectiveDate} → {policy.expiryDate}</dd>
        <dt>Coverage</dt><dd>{money(policy.coverageLimit, policy.currency)}</dd>
        <dt>Deductible</dt><dd>{money(policy.deductible, policy.currency)} · {policy.copayPercentage}% co-pay</dd>
        <dt>Waiting</dt><dd>{Object.entries(waits).map(([k, v]) => `${humanize(k)} ${v} mo`).join(' · ') || '-'}</dd>
      </dl>
      {exclusions.length > 0 && (
        <div style={{ marginTop: 16, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {exclusions.map(x => <span key={x} className="chip stop">Excludes {x.toLowerCase()}</span>)}
        </div>
      )}
    </Panel>
  )
}

function ConfidenceRing({ value, tone }: { value: number; tone: string }) {
  const r = 26
  return (
    <svg width="64" height="64" viewBox="0 0 64 64" aria-label={`${Math.round(value * 100)} percent confidence`}>
      <circle cx="32" cy="32" r={r} fill="none" stroke="var(--hairline-strong)" strokeWidth="6" />
      <motion.circle cx="32" cy="32" r={r} fill="none" stroke={`var(--${tone}-fill)`} strokeWidth="6" strokeLinecap="round"
        transform="rotate(-90 32 32)" initial={{ pathLength: 0 }} animate={{ pathLength: value }}
        transition={{ type: 'spring', bounce: 0, duration: 1.2, delay: 0.2 }} />
      <text x="32" y="37" textAnchor="middle" fontSize="15" fontWeight="700" fill="var(--text)">{Math.round(value * 100)}</text>
    </svg>
  )
}

function Verdict({ decision, expected, stale }: { decision: Decision; expected?: string; stale: boolean }) {
  const copy = DECISION_COPY[decision.decision]
  const ai = decision.modelProvider !== 'deterministic' && decision.model !== 'unavailable'
  return (
    <section className="card card-pad verdict" data-tone={copy.tone}>
      <div className="verdict-glow" />
      <div style={{ position: 'relative' }}>
        <p className="eyebrow" style={{ margin: 0 }}>{stale ? 'Latest decision' : 'Verdict'}</p>
        <div className="verdict-word">{copy.word}</div>
        <p className="subhead" style={{ fontSize: 19 }}>{copy.line}</p>
        <div style={{ display: 'flex', gap: 22, alignItems: 'center', flexWrap: 'wrap', marginTop: 22 }}>
          {decision.confidence != null && (
            <div className="ring-wrap"><ConfidenceRing value={decision.confidence} tone={copy.tone} /><span className="body-2">AI confidence</span></div>
          )}
          {expected && (
            <span className={`chip ${expected === decision.decision ? 'pass' : 'warn'}`} style={{ fontSize: 14, padding: '7px 14px' }}>
              {expected === decision.decision ? '✓ Matches the dataset label' : `Dataset label: ${expected.replace('_', ' ').toLowerCase()}`}
            </span>
          )}
        </div>
        {decision.summary && (
          <blockquote style={{ margin: '22px 0 0', padding: '16px 18px', borderRadius: 14, background: 'var(--surface-2)', boxShadow: 'inset 0 0 0 1px var(--hairline)' }}>
            <p style={{ margin: 0, fontSize: 16 }}>“{decision.summary}”</p>
            <p className="body-2" style={{ margin: '8px 0 0', fontSize: 13 }}>
              {ai ? `${decision.model} via ${decision.modelProvider} · prompt ${decision.promptVersion}` : 'Deterministic rule engine'}
            </p>
          </blockquote>
        )}
        {decision.reasonCodes.length > 0 && (
          <div className="reason-list" style={{ marginTop: 18 }}>
            {decision.reasonCodes.map(c => <span key={c} className="chip">{humanize(c)}</span>)}
          </div>
        )}
        {(decision.uncertainties.length > 0 || decision.missingInformation.length > 0) && (
          <ul className="body-2" style={{ margin: '16px 0 0', paddingLeft: 18 }}>
            {decision.missingInformation.map(m => <li key={m}>Missing: {m}</li>)}
            {decision.uncertainties.map(u => <li key={u}>{u}</li>)}
          </ul>
        )}
      </div>
    </section>
  )
}

function EvidencePanel({ decision }: { decision: Decision }) {
  const ev = decision.supportingEvidence ?? []
  return (
    <Panel title="Policy evidence" note="Clauses retrieved from the policy wording by vector similarity.">
      {ev.length === 0 && <p className="body-2" style={{ margin: 0 }}>No clauses retrieved for this claim.</p>}
      {ev.map(e => (
        <div className="evidence" key={`${e.rank}-${e.chunkId ?? 'replaced'}`}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
            <span style={{ fontWeight: 600, fontSize: 15 }}>#{e.rank + 1} · page {e.pageNumber ?? '-'}</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-3)' }}>
              <span className="evidence-bar"><div style={{ width: `${Math.max(4, Math.min(100, (e.similarity ?? 0) * 100))}%` }} /></span>
              {(e.similarity ?? 0).toFixed(2)}
            </span>
          </div>
          <p className="snippet">{e.snippet}</p>
        </div>
      ))}
      {ev.length > 0 && (
        <p className="body-2" style={{ fontSize: 12.5, margin: '12px 0 0' }}>
          Cosine similarity from pgvector. The synthetic policy wording is short, so scores are modest; the ranking is what matters.
        </p>
      )}
    </Panel>
  )
}

function AiInputPanel({ trace, decision }: { trace: AiTrace | null; decision: Decision }) {
  const [raw, setRaw] = useState(false)
  const note = 'Java decides. This is exactly what Mercury was given on this run, and nothing else reaches the model.'
  const noAiNote = 'Java decides. Mercury is consulted only when every Java rule passes.'

  if (!trace) {
    const byRules = decision.modelProvider === 'deterministic'
    return (
      <Panel title="What the AI compared" note={noAiNote}>
        <p className="body-2" style={{ margin: 0 }}>
          {byRules
            ? 'Java’s rules decided this claim, so Mercury was not called.'
            : 'Analyze the claim to capture the exact input sent to Mercury and its reply, live from the stream.'}
        </p>
      </Panel>
    )
  }

  if (trace.kind === 'skipped') {
    return (
      <Panel title="What the AI compared" note={noAiNote}>
        <div className="notice info">
          {trace.reason === 'RULES_DECIDED'
            ? <>Mercury was not called. Java’s rules decided first ({trace.outcome === 'REJECT' ? 'rejected' : 'sent to review'}).</>
            : <>Mercury was not called: no policy evidence could be retrieved, so Java sent the claim to a person.</>}
        </div>
        {trace.flagged.length > 0 && (
          <div className="reason-list" style={{ marginTop: 14 }}>
            {trace.flagged.map(c => <span key={c} className="chip warn">{humanize(c)}</span>)}
          </div>
        )}
      </Panel>
    )
  }

  const { input, output } = trace
  const known = input.extractedFacts.filter(f => f.status === 'KNOWN').length
  return (
    <Panel title="What the AI compared" note={note}>
      <div className="ai-io">
        <div className="box">
          <h4>The claim</h4>
          <dl className="kv">
            <dt>Diagnosis</dt><dd>{input.claim.diagnosis}</dd>
            <dt>Procedure</dt><dd>{input.claim.procedure}</dd>
            <dt>Treatment</dt><dd>{input.claim.treatment}</dd>
            <dt>Amount</dt><dd>{money(input.claim.claimAmount, input.claim.currency)}</dd>
          </dl>
        </div>
        <div className="box">
          <h4>Against the policy (from PostgreSQL)</h4>
          <dl className="kv">
            <dt>Coverage limit</dt><dd>{money(input.policy.coverageLimit, input.claim.currency)}</dd>
            <dt>Exclusions</dt><dd>{input.policy.exclusions.join(', ') || 'None'}</dd>
          </dl>
          <p className="body-2" style={{ margin: '14px 0 6px', fontSize: 13 }}>
            {input.policyEvidence.length} retrieved clause{input.policyEvidence.length === 1 ? '' : 's'} (pgvector)
          </p>
          {input.policyEvidence.map((e, i) => (
            <p key={i} className="snippet" style={{ margin: '0 0 8px', fontSize: 13.5 }}>
              <b style={{ color: 'var(--text)' }}>p.{e.page ?? '-'}</b> {e.text.length > 220 ? `${e.text.slice(0, 220)}…` : e.text}
            </p>
          ))}
        </div>
        <div className="box">
          <h4>Plus</h4>
          <p className="body-2" style={{ margin: 0 }}>
            {input.extractedFacts.length} extracted facts ({known} known) and the results of all {input.ruleReasonCodes.length} Java rules:
          </p>
          <div className="reason-list" style={{ marginTop: 10 }}>
            {input.ruleReasonCodes.map(c => <span key={c} className="chip pass">{humanize(c)}</span>)}
          </div>
        </div>
        <div className="box">
          <h4>Mercury replied</h4>
          {trace.failed && <p className="body-2" style={{ margin: 0 }}>The AI call failed, so Java sent the claim to a person.</p>}
          {!trace.failed && !output && <p className="body-2" style={{ margin: 0 }}>Waiting for {trace.provider}…</p>}
          {output && (
            <>
              <p style={{ margin: 0, fontSize: 16 }}>
                Suggests <b>{output.suggestion.replace('_', ' ').toLowerCase()}</b>
                {output.confidence != null && <> at <b>{Math.round(output.confidence * 100)}%</b> confidence</>}
                <span className="body-2" style={{ fontSize: 13 }}> · {output.model} · {(output.elapsedMs / 1000).toFixed(1)} s</span>
              </p>
              {output.summary && <p className="snippet" style={{ marginTop: 8 }}>“{output.summary}”</p>}
              <p className="body-2" style={{ margin: '10px 0 0', fontSize: 13.5 }}>
                Java’s call: <b style={{ color: 'var(--text)' }}>{DECISION_COPY[decision.decision]?.word ?? decision.decision}</b>
                {decision.decision === output.suggestion ? ' (the guardrails accepted the suggestion)' : ' (the guardrails overrode the suggestion)'}
              </p>
            </>
          )}
        </div>
      </div>
      <button className="link-btn" onClick={() => setRaw(r => !r)} style={{ fontSize: 14, marginTop: 14 }} aria-expanded={raw}>
        {raw ? 'Hide' : 'Show'} the raw prompt and JSON
      </button>
      {raw && (
        <>
          <pre className="code-block">{trace.instructions}</pre>
          <pre className="code-block">{JSON.stringify(input, null, 2)}</pre>
        </>
      )}
    </Panel>
  )
}

function renderMarkdownLite(text: string) {
  const inline = (s: string) => s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : <span key={i}>{part}</span>)
  const out: ReactNode[] = []
  let items: { depth: number; text: string }[] = []
  const flush = () => {
    if (!items.length) return
    out.push(<ul key={out.length}>{items.map((it, i) => <li key={i} style={{ marginLeft: it.depth * 16 }}>{inline(it.text)}</li>)}</ul>)
    items = []
  }
  for (const line of text.split('\n')) {
    const m = line.match(/^(\s*)[*-]\s+(.*)$/)
    if (m) { items.push({ depth: Math.floor(m[1].length / 4), text: m[2] }); continue }
    flush()
    if (line.trim()) out.push(<p key={out.length}>{inline(line.replace(/^#+\s*/, ''))}</p>)
  }
  flush()
  return out
}

function ExplainPanel({ claimId, session, decisionId }: { claimId: string; session: Session; decisionId: string }) {
  const [text, setText] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => { setText(null); setErr(null) }, [decisionId])
  const go = async () => {
    setBusy(true); setErr(null)
    try { setText((await api(session).explain(claimId)).explanation) } catch (e) { setErr((e as Error).message) } finally { setBusy(false) }
  }
  return (
    <Panel title="Plain-English explanation" note="Written by Mercury from the stored decision, its reasons and evidence.">
      {!text && (
        <button className="btn btn-secondary btn-sm" onClick={go} disabled={busy}>
          {busy ? <><span className="spinner" /> Writing</> : 'Explain this decision'}
        </button>
      )}
      {err && <div className="notice stop" style={{ marginTop: 12 }}>{err}</div>}
      <AnimatePresence>
        {text && <motion.div className="explanation" {...rise}>{renderMarkdownLite(text)}</motion.div>}
      </AnimatePresence>
    </Panel>
  )
}

function ReviewPanel({ claimId, session, onDone }: { claimId: string; session: Session; onDone: () => void }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [result, setResult] = useState<Review | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const submit = async (choice: string) => {
    setBusy(choice); setErr(null)
    try { setResult(await api(session).review(claimId, choice, reason.trim())); onDone() }
    catch (e) { setErr((e as Error).message) } finally { setBusy(null) }
  }
  return (
    <Panel title="Human review" note="The system stopped short of an automatic answer. A reviewer makes the call.">
      {result ? (
        <motion.div {...rise}>
          <div className={`notice ${result.finalDecision === 'REJECTED' || result.reviewDecision === 'REJECT' ? 'stop' : 'info'}`}>
            Recorded <b style={{ margin: '0 4px' }}>{result.reviewDecision.replace(/_/g, ' ').toLowerCase()}</b> by {result.reviewerId ?? session.username}.{' '}
            {result.finalDecision
              ? `Final status: ${statusLabel(result.finalDecision)}.`
              : 'The claim stays in review until the information arrives.'}
          </div>
        </motion.div>
      ) : (
        <>
          <textarea className="input" placeholder="Reason for the decision (stored in the audit trail)" value={reason}
            onChange={e => setReason(e.target.value)} maxLength={4000} />
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 14 }}>
            {[['APPROVE', 'Approve', 'btn-primary'], ['REJECT', 'Reject', 'btn-quiet'], ['REQUEST_INFORMATION', 'Request information', 'btn-quiet']].map(([k, l, cls]) => (
              <button key={k} className={`btn btn-sm ${cls}`} onClick={() => submit(k)} disabled={!!busy}>
                {busy === k ? <><span className="spinner" /> Saving</> : l}
              </button>
            ))}
          </div>
          {err && <div className="notice stop" style={{ marginTop: 12 }}>{err}</div>}
        </>
      )}
    </Panel>
  )
}

function AuditPanel({ events }: { events: AuditEvent[] }) {
  const sorted = [...events].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 8)
  return (
    <Panel title="Audit trail" note="Every decision and review, with model, prompt version and trace id.">
      <ul className="timeline">
        {sorted.map(e => (
          <li key={e.id}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
              <b style={{ fontWeight: 600 }}>{humanize(e.eventType)}{e.finalDecision ? ` · ${humanize(e.finalDecision)}` : ''}</b>
              <span className="body-2" style={{ fontSize: 13 }}>{new Date(e.createdAt).toLocaleString()}</span>
            </div>
            <div className="body-2" style={{ fontSize: 13, marginTop: 2 }}>
              {[e.modelName, e.latencyMs ? `${e.latencyMs} ms` : null, e.traceId ? `trace ${e.traceId.slice(0, 8)}` : null].filter(Boolean).join(' · ')}
            </div>
          </li>
        ))}
      </ul>
    </Panel>
  )
}
