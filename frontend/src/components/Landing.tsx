import { useEffect, useRef, useState } from 'react'
import { motion, useInView } from 'motion/react'
import { Cube } from './Cube'
import { STAGES, type StageState } from '../pipeline'
import { DEMO_ACCOUNTS } from '../demoAccounts'

type Account = (typeof DEMO_ACCOUNTS)[number]

const reveal = {
  initial: { opacity: 0, y: 24 },
  whileInView: { opacity: 1, y: 0 },
  viewport: { once: true, margin: '-80px' },
  transition: { type: 'spring' as const, bounce: 0, duration: 0.9 },
}

const STACK: { group: string; items: [string, string][] }[] = [
  { group: 'Backend', items: [['Java 21', 'language'], ['Spring Boot 3.5', 'web, validation'], ['Spring Security', 'JWT, roles'], ['Spring Data JPA', 'persistence'], ['Flyway', 'migrations']] },
  { group: 'Data & retrieval', items: [['PostgreSQL 16', 'system of record'], ['pgvector', 'vector search'], ['PDFBox', 'PDF parsing'], ['Chunking', 'heading-aware'], ['NVIDIA NIM', 'nemotron embeddings, 384-d']] },
  { group: 'AI', items: [['Parallel Decisions', 'decision model, probabilities'], ['Nemotron 3.5 Super', 'explanations, fact extraction'], ['OpenRouter', 'one key, both models'], ['Resilience4j', 'retry, circuit breaker'], ['Provider abstraction', 'swap models']] },
  { group: 'Quality & ops', items: [['Testcontainers · Vitest', '122 tests'], ['GitHub Actions', 'CI + image publish'], ['Docker', 'multi-stage, non-root'], ['AWS Lambda + ECR', 'deploy target'], ['Micrometer', 'Prometheus metrics']] },
]

// Every condition for an automatic approval, and which part of the system checks it.
const APPROVAL: { text: string; who: string; ai?: boolean }[] = [
  { text: 'The policy is active and in force on the treatment date', who: 'Java rule' },
  { text: 'The procedure and diagnosis are not on the policy’s exclusion list', who: 'Java rule' },
  { text: 'The amount is within the policy’s coverage limit', who: 'Java rule' },
  { text: 'General and pre-existing waiting periods have passed', who: 'Java rule' },
  { text: 'Pre-existing condition status is known', who: 'Java rule' },
  { text: 'Every required fact is present and none conflict', who: 'Java rules' },
  { text: 'A decision model weighs the claim against the retrieved policy wording and picks approve', who: 'AI advises', ai: true },
  { text: 'Its probability for approve is at least 0.5, so Java accepts the suggestion', who: 'Java guardrail' },
]

const GUARDS = [
  { from: 'Any rule finds a problem', to: 'Rules decide, AI is never called' },
  { from: 'AI confidence below 0.5', to: 'Human review' },
  { from: 'AI suggests REJECT without a rule basis', to: 'Human review' },
  { from: 'AI or retrieval fails', to: 'Human review' },
]

export function Landing({ onSignIn, signingIn, error, signedIn, onOpenClaims }: {
  onSignIn: (a: Account) => void
  signingIn: string | null
  error: string | null
  signedIn: boolean
  onOpenClaims: () => void
}) {
  return (
    <main>
      <Hero signedIn={signedIn} onOpenClaims={onOpenClaims} />

      <section className="container" style={{ paddingBottom: 40 }}>
        <motion.div className="stats" {...reveal}>
          {[['7', 'pipeline stages'], ['3', 'possible outcomes'], ['30', 'labeled test claims'], ['122', 'automated tests']].map(([n, l]) => (
            <div className="stat" key={l}><b>{n}</b><span>{l}</span></div>
          ))}
        </motion.div>
      </section>

      <section id="how" className="section">
        <div className="container">
          <motion.div className="center" {...reveal}>
            <p className="eyebrow accent">How a claim is decided</p>
            <h2 className="headline" style={{ marginTop: 10 }}>Seven steps. One auditable answer.</h2>
            <p className="subhead" style={{ maxWidth: 700, margin: '20px auto 0' }}>
              Rules run first and can stop a claim outright. The AI is consulted only when every rule
              passes, and even then Java has the final word.
            </p>
          </motion.div>
          <div className="flow-grid">
            {STAGES.map((s, i) => (
              <motion.div className="flow-card" key={s.id} {...reveal} transition={{ ...reveal.transition, delay: i * 0.05 }}>
                <span className="stage-index">{s.index}</span>
                <h3>{s.title}</h3>
                <p className="body-2" style={{ margin: 0 }}>{s.what}</p>
                <div className="stage-tech" style={{ marginTop: 'auto', paddingTop: 6 }}>
                  {s.tech.map(t => <span className="chip" key={t}>{t}</span>)}
                </div>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      <section id="approves" className="section section-alt">
        <div className="container">
          <motion.div className="center" {...reveal}>
            <p className="eyebrow accent">What gets approved</p>
            <h2 className="headline" style={{ marginTop: 10 }}>Approved only when all eight hold.</h2>
            <p className="subhead" style={{ maxWidth: 720, margin: '20px auto 0' }}>
              The terms come from each policy in PostgreSQL. Miss any check and the claim is rejected or handed to a person.
              It is never approved by default.
            </p>
          </motion.div>
          <ol className="approve-list">
            {APPROVAL.map((a, i) => (
              <motion.li key={a.text} {...reveal} transition={{ ...reveal.transition, delay: i * 0.04 }}>
                <span className="n">{i + 1}</span>
                <span style={{ fontSize: 16 }}>{a.text}</span>
                <span className={`who ${a.ai ? 'ai' : ''}`}>{a.who}</span>
              </motion.li>
            ))}
          </ol>
          <motion.div className="decider" {...reveal}>
            <h3>Who makes the decision? Java.</h3>
            <p className="body-2" style={{ margin: '8px 0 0' }}>
              The seven rules (<span className="mono">RuleEngine</span>) and the guardrails (<span className="mono">DecisionOrchestrator</span>)
              are plain Java. A decision model (Parallel Decisions Fast) is called only after every rule passes. It gets a fixed
              package (the claim, the policy’s terms, the retrieved clauses and the rule results) and returns a probability for
              each outcome in about two seconds. Java decides whether to accept it, and an AI-only rejection always goes to a person.
            </p>
            <p style={{ margin: '14px 0 0' }}>
              <a className="link-btn" href={signedIn ? '#/policies' : '#demo'} style={{ fontSize: 15 }}>
                {signedIn ? 'Open the policy rulebook ›' : 'Sign in to see the live policy rulebook ›'}
              </a>
            </p>
          </motion.div>
        </div>
      </section>

      <section id="guardrails" className="section">
        <div className="container">
          <motion.div className="center" {...reveal}>
            <p className="eyebrow accent">Guardrails</p>
            <h2 className="headline" style={{ marginTop: 10 }}>The model advises.<br />The code decides.</h2>
            <p className="subhead" style={{ maxWidth: 680, margin: '20px auto 0' }}>
              An AI can't approve or reject a claim on its own. Whenever there's doubt, a person decides.
            </p>
          </motion.div>
          <div className="guard-grid">
            {GUARDS.map((g, i) => (
              <motion.div className="guard" key={g.from} {...reveal} transition={{ ...reveal.transition, delay: i * 0.06 }}>
                <div className="from">If: {g.from}</div>
                <div className="to">{g.to}</div>
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      <section id="stack" className="section section-alt">
        <div className="container">
          <motion.div className="center" {...reveal}>
            <p className="eyebrow accent">The stack</p>
            <h2 className="headline" style={{ marginTop: 10 }}>Built like production software.</h2>
            <p className="subhead" style={{ maxWidth: 680, margin: '20px auto 0' }}>
              Everything listed here is actually used in the code. Nothing is decorative.
            </p>
          </motion.div>
          <div className="stack-grid">
            {STACK.map((col, i) => (
              <motion.div className="stack-col" key={col.group} {...reveal} transition={{ ...reveal.transition, delay: i * 0.06 }}>
                <h3>{col.group}</h3>
                {col.items.map(([name, role]) => (
                  <div className="stack-item" key={name}><span>{name}</span><span>{role}</span></div>
                ))}
              </motion.div>
            ))}
          </div>
        </div>
      </section>

      <section id="demo" className="section">
        <div className="container">
          <motion.div className="center" {...reveal}>
            <p className="eyebrow accent">Live demo</p>
            <h2 className="headline" style={{ marginTop: 10 }}>Pick a role. Run a real claim.</h2>
            <p className="subhead" style={{ maxWidth: 660, margin: '20px auto 0' }}>
              Every result comes from the running backend: real rules, real retrieval, a real AI call.
            </p>
          </motion.div>
          <div className="accounts">
            {DEMO_ACCOUNTS.map((a, i) => (
              <motion.button className="account" key={a.role} onClick={() => onSignIn(a)} disabled={!!signingIn}
                {...reveal} transition={{ ...reveal.transition, delay: i * 0.06 }}>
                <span className="chip">{a.role}</span>
                <b>{a.title}</b>
                <span className="body-2">{a.blurb}</span>
                <span className="go">
                  {signingIn === a.role ? <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}><span className="spinner" /> Signing in</span> : 'Continue ›'}
                </span>
              </motion.button>
            ))}
          </div>
          {error && <div className="notice stop" style={{ marginTop: 20 }}>{error}</div>}
          <p className="body-2 center" style={{ marginTop: 24 }}>
            Demo accounts exist only under the backend's dev profile.
          </p>
        </div>
      </section>

      <footer className="footer">
        <div className="container">
          Synthetic data only. This is an AI-assisted decision-support portfolio project. It does not provide real insurance
          authorization, medical advice or production insurance decisions. Every policy, claim, patient and document is fabricated.
        </div>
      </footer>
    </main>
  )
}

function Hero({ signedIn, onOpenClaims }: { signedIn: boolean; onOpenClaims: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const inView = useInView(ref, { once: true, margin: '-40px' })
  const [states, setStates] = useState<StageState[]>(STAGES.map(() => 'idle'))

  // One intro run of the pipeline: each cube spins, then lands on its check.
  useEffect(() => {
    if (!inView) return
    const timers: number[] = []
    STAGES.forEach((_, i) => {
      timers.push(window.setTimeout(() => setStates(p => p.map((s, j) => (j === i ? 'running' : s))), 500 + i * 420))
      timers.push(window.setTimeout(() => setStates(p => p.map((s, j) => (j === i ? 'done' : s))), 500 + i * 420 + 900))
    })
    return () => timers.forEach(clearTimeout)
  }, [inView])

  return (
    <section className="hero">
      <div className="container">
        <motion.p className="eyebrow" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6 }}>
          Health Claim Decision Agent
        </motion.p>
        <motion.h1 className="display" style={{ marginTop: 14 }}
          initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={{ type: 'spring', bounce: 0, duration: 1, delay: 0.05 }}>
          Every claim.<br /><span className="gradient-text">Decided with evidence.</span>
        </motion.h1>
        <motion.p className="subhead" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ type: 'spring', bounce: 0, duration: 1, delay: 0.15 }}>
          Deterministic rules, policy retrieval with pgvector and an AI judge, orchestrated by Spring Boot.
          Every outcome is traceable to the rule or clause behind it.
        </motion.p>
        <motion.div className="hero-cta" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ type: 'spring', bounce: 0, duration: 1, delay: 0.25 }}>
          {signedIn
            ? <button className="btn btn-primary btn-lg" onClick={onOpenClaims}>Open claims</button>
            : <a className="btn btn-primary btn-lg" href="#demo" style={{ textDecoration: 'none' }}>Try the live demo</a>}
          <a className="btn btn-secondary btn-lg" href="#how" style={{ textDecoration: 'none' }}>See how it works</a>
        </motion.div>
        <div className="hero-cubes" ref={ref}>
          {STAGES.map((s, i) => (
            <div className="hero-cube-item" key={s.id}>
              <Cube state={states[i]} glyph={s.glyph} size={50} />
              <span>{s.short}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
