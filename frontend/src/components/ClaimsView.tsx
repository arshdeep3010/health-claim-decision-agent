import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { api, type Claim, type Session } from '../api'
import scenarios from '../data/scenarios.json'
import { money, statusLabel, statusTone } from '../format'

type Label = { scenario: string; expected: string }
const LABELS = scenarios as Record<string, Label>
const FILTERS = [['ALL', 'All'], ['APPROVE', 'Approve'], ['REJECT', 'Reject'], ['HUMAN_REVIEW', 'Review']] as const

export function ClaimsView({ session, onOpen }: { session: Session; onOpen: (c: Claim) => void }) {
  const [claims, setClaims] = useState<Claim[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<(typeof FILTERS)[number][0]>('ALL')

  useEffect(() => {
    api(session).claims().then(setClaims).catch(e => setError(e.message))
  }, [session])

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (claims ?? [])
      .filter(c => filter === 'ALL' || LABELS[c.claimNumber]?.expected === filter)
      .filter(c => !q || [c.claimNumber, c.diagnosis, c.procedureName, c.provider, c.policyNumber, LABELS[c.claimNumber]?.scenario]
        .some(v => v?.toLowerCase().includes(q)))
      .sort((a, b) => a.claimNumber.localeCompare(b.claimNumber))
  }, [claims, query, filter])

  return (
    <main className="container" style={{ paddingBlock: 'clamp(48px, 7vw, 88px)' }}>
      <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ type: 'spring', bounce: 0, duration: 0.8 }}>
        <p className="eyebrow accent">Synthetic claims</p>
        <h1 className="headline" style={{ marginTop: 8 }}>Choose a claim to decide.</h1>
        <p className="subhead" style={{ marginTop: 14, maxWidth: 680 }}>
          Each claim was built to test a specific scenario, and its expected outcome is labeled, so you can check the agent against the answer.
        </p>
      </motion.div>

      <div className="toolbar" style={{ margin: '40px 0 28px' }}>
        <input className="search" type="search" placeholder="Search diagnosis, procedure, scenario…" value={query}
          onChange={e => setQuery(e.target.value)} aria-label="Search claims" />
        <div className="segmented" role="group" aria-label="Filter by expected outcome">
          {FILTERS.map(([key, label]) => (
            <button key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>
              {filter === key && <motion.span layoutId="seg-pill" className="seg-pill" transition={{ type: 'spring', bounce: 0, duration: 0.35 }} />}
              {label}
            </button>
          ))}
        </div>
      </div>

      {error && <div className="notice stop">{error}</div>}
      {!claims && !error && (
        <div className="claims-grid">{Array.from({ length: 6 }, (_, i) => <div key={i} className="skeleton" style={{ height: 196 }} />)}</div>
      )}
      {claims && (
        <motion.div className="claims-grid" layout>
          <AnimatePresence mode="popLayout">
            {shown.map((c, i) => {
              const label = LABELS[c.claimNumber]
              return (
                <motion.button
                  layout
                  key={c.claimId}
                  className="claim-card"
                  onClick={() => onOpen(c)}
                  initial={{ opacity: 0, y: 14, scale: 0.98 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.96 }}
                  transition={{ type: 'spring', bounce: 0, duration: 0.5, delay: Math.min(i * 0.025, 0.3) }}
                >
                  <div className="claim-meta">
                    <span className="mono">{c.claimNumber}</span>
                    <span className={`chip ${statusTone(c.status)}`}>{statusLabel(c.status)}</span>
                  </div>
                  <h3>{c.diagnosis}</h3>
                  <span className="body-2" style={{ marginTop: -4 }}>{c.procedureName}</span>
                  {label && <span className="body-2" style={{ color: 'var(--text)', fontWeight: 500 }}>{label.scenario}</span>}
                  <div className="claim-meta" style={{ marginTop: 'auto', paddingTop: 8 }}>
                    <span className="claim-amount" style={{ color: 'var(--text)' }}>{money(c.claimAmount, c.currency)}</span>
                    {label && <span>Expected · {label.expected.replace('_', ' ').toLowerCase()}</span>}
                  </div>
                </motion.button>
              )
            })}
          </AnimatePresence>
        </motion.div>
      )}
      {claims && shown.length === 0 && <p className="body-2 center" style={{ marginTop: 40 }}>No claims match.</p>}
    </main>
  )
}
