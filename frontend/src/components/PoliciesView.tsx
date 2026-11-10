import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { api, type Policy, type PolicyChunk, type Session } from '../api'
import { humanize } from '../pipeline'
import { money, parseJson } from '../format'

const rise = {
  initial: { opacity: 0, y: 16 }, animate: { opacity: 1, y: 0 },
  transition: { type: 'spring' as const, bounce: 0, duration: 0.8 },
}

/** Everything the agent checks a claim against, read live from PostgreSQL. */
export function PoliciesView({ session }: { session: Session }) {
  const [policies, setPolicies] = useState<Policy[] | null>(null)
  const [chunks, setChunks] = useState<Record<string, PolicyChunk[]>>({})
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const client = api(session)
    client.policies()
      .then(ps => {
        const sorted = [...ps].sort((a, b) => a.policyNumber.localeCompare(b.policyNumber))
        setPolicies(sorted)
        sorted.forEach(p => client.chunks(p.policyId)
          .then(c => setChunks(prev => ({ ...prev, [p.policyId]: c })))
          .catch(() => setChunks(prev => ({ ...prev, [p.policyId]: [] }))))
      })
      .catch(e => setError((e as Error).message))
  }, [session])

  const all = Object.values(chunks).flat()
  const models = [...new Set(all.map(c => c.embeddingModel).filter(Boolean))]

  return (
    <main className="container" style={{ paddingBlock: 'clamp(48px, 7vw, 88px) 96px' }}>
      <motion.div {...rise}>
        <p className="eyebrow accent">Policy rulebook · live from PostgreSQL</p>
        <h1 className="headline" style={{ marginTop: 8 }}>What every claim is checked against.</h1>
        <p className="subhead" style={{ marginTop: 14, maxWidth: 760 }}>
          Each policy has two parts. Its structured terms are what the seven Java rules check. Its wording is split into
          chunks, embedded and stored in pgvector; the closest chunks are retrieved as evidence and handed to Mercury.
        </p>
      </motion.div>

      <div className="rulebook-legend">
        <div className="card card-pad">
          <span className="chip on">Checked by Java rules</span>
          <p className="body-2" style={{ margin: '10px 0 0' }}>
            Status and dates, coverage limit, waiting periods and exclusions. A failed check rejects the claim or sends it
            to a person, and the AI is never called.
          </p>
        </div>
        <div className="card card-pad">
          <span className="chip">Evidence for the AI</span>
          <p className="body-2" style={{ margin: '10px 0 0' }}>
            Policy wording in pgvector{models.length ? <> (embedded with <span className="mono">{models.join(', ')}</span>)</> : null}.
            Mercury only sees the chunks retrieved for a claim, and only after every rule has passed.
          </p>
        </div>
      </div>

      {error && <div className="notice stop" style={{ marginTop: 24 }}>{error}</div>}
      {!policies && !error && (
        <div style={{ display: 'grid', gap: 18, marginTop: 32 }}>
          {Array.from({ length: 3 }, (_, i) => <div key={i} className="skeleton" style={{ height: 220 }} />)}
        </div>
      )}
      <div style={{ display: 'grid', gap: 22, marginTop: 32 }}>
        {policies?.map((p, i) => (
          <motion.div key={p.policyId} {...rise} transition={{ ...rise.transition, delay: Math.min(i, 6) * 0.05 }}>
            <PolicyCard policy={p} chunks={chunks[p.policyId]} />
          </motion.div>
        ))}
      </div>
    </main>
  )
}

function PolicyCard({ policy, chunks }: { policy: Policy; chunks: PolicyChunk[] | undefined }) {
  const [open, setOpen] = useState(false)
  const exclusions = parseJson<string[]>(policy.exclusions) ?? []
  const waits = parseJson<Record<string, number>>(policy.waitingPeriods) ?? {}
  const active = policy.status === 'ACTIVE'
  const model = chunks?.find(c => c.embeddingModel)?.embeddingModel
  const dims = chunks?.find(c => c.embeddingDimensions)?.embeddingDimensions

  return (
    <section className="card card-pad">
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <h2 className="title" style={{ fontSize: 24, margin: 0 }}><span className="mono" style={{ fontSize: '0.9em' }}>{policy.policyNumber}</span></h2>
        <span className={`chip ${active ? 'pass' : 'stop'}`}>{policy.status.toLowerCase()}</span>
        <span className="chip">{humanize(policy.policyType)}</span>
      </div>
      {!active && (
        <p className="body-2" style={{ margin: '10px 0 0' }}>
          Not active, so every claim on this policy is rejected by the policy-status rule before any retrieval or AI.
        </p>
      )}

      <div className="rule-terms">
        <Term label="In force" value={`${policy.effectiveDate} → ${policy.expiryDate}`} rule="Policy active & in force" />
        <Term label="Coverage limit" value={money(policy.coverageLimit, policy.currency)} rule="Within coverage limit" />
        <Term label="Deductible · co-pay" value={`${money(policy.deductible, policy.currency)} · ${policy.copayPercentage}%`} />
        <Term label="Waiting periods"
          value={Object.entries(waits).map(([k, v]) => `${humanize(k)} ${v} mo`).join(' · ') || 'None'}
          rule="Waiting period satisfied" />
      </div>

      <div style={{ marginTop: 16 }}>
        <span className="body-2" style={{ fontSize: 13 }}>Excluded (rule: procedure / diagnosis excluded)</span>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
          {exclusions.length ? exclusions.map(x => <span key={x} className="chip stop">{x}</span>) : <span className="body-2">Nothing excluded</span>}
        </div>
      </div>

      <div className="divider" style={{ margin: '20px 0 14px' }} />
      <button className="link-btn" onClick={() => setOpen(o => !o)} aria-expanded={open} style={{ fontSize: 15 }}>
        {open ? 'Hide' : 'Show'} indexed wording
        <span className="body-2" style={{ marginLeft: 8, fontSize: 13 }}>
          {chunks == null ? 'loading…' : `${chunks.length} chunk${chunks.length === 1 ? '' : 's'}${model ? ` · ${model}` : ''}${dims ? ` · ${dims}-d` : ''}`}
        </span>
      </button>
      <AnimatePresence initial={false}>
        {open && chunks && (
          <motion.div key="chunks" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }}
            transition={{ type: 'spring', bounce: 0, duration: 0.45 }} style={{ overflow: 'hidden' }}>
            {chunks.length === 0 && <p className="body-2">No wording indexed. Run <span className="mono">scripts/seed_policy_documents.py</span>.</p>}
            {chunks.map(c => (
              <div className="evidence" key={c.chunkId}>
                <span style={{ fontWeight: 600, fontSize: 14 }}>
                  Chunk {c.chunkIndex + 1} · page {c.pageNumber ?? '-'}
                  {c.heading && !c.heading.includes(policy.policyNumber) ? ` · ${c.heading}` : ''}
                </span>
                <p className="snippet">{c.text}</p>
              </div>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  )
}

function Term({ label, value, rule }: { label: string; value: string; rule?: string }) {
  return (
    <div className="rule-term">
      <span className="body-2" style={{ fontSize: 13 }}>{label}</span>
      <b>{value}</b>
      {rule && <span className="rule-tag">rule: {rule}</span>}
    </div>
  )
}
