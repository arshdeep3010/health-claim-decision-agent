import { useCallback, useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { backendHealth, loadSession, login, saveSession, setUnauthorizedHandler, type Session } from './api'
import { Landing } from './components/Landing'
import { ClaimsView } from './components/ClaimsView'
import { ClaimView } from './components/ClaimView'
import { PoliciesView } from './components/PoliciesView'
import { DEMO_ACCOUNTS } from './demoAccounts'

type Route = { name: 'landing' } | { name: 'claims' } | { name: 'claim'; id: string } | { name: 'policies' }

function parse(hash: string): Route {
  const m = hash.match(/^#\/claims\/([0-9a-f-]{36})/i)
  if (m) return { name: 'claim', id: m[1] }
  if (hash.startsWith('#/claims')) return { name: 'claims' }
  if (hash.startsWith('#/policies')) return { name: 'policies' }
  return { name: 'landing' }
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [route, setRoute] = useState<Route>(() => parse(location.hash))
  const [health, setHealth] = useState<{ up: boolean; ai?: string } | null>(null)
  const [signingIn, setSigningIn] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const go = (hash: string) => { location.hash = hash }

  const signOut = useCallback(() => { saveSession(null); setSession(null); go('#/') }, [])
  useEffect(() => setUnauthorizedHandler(signOut), [signOut])

  useEffect(() => {
    const onHash = () => {
      setRoute(parse(location.hash))
      const anchor = location.hash.match(/^#([a-z]+)$/i)?.[1]
      if (anchor) requestAnimationFrame(() => document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth' }))
      else window.scrollTo({ top: 0 })
    }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  useEffect(() => {
    const check = () => backendHealth().then(setHealth)
    check()
    const id = window.setInterval(check, 15000)
    return () => clearInterval(id)
  }, [])

  // Protected routes need a session.
  const needsAuth = route.name !== 'landing' && !session
  useEffect(() => { if (needsAuth) go('#demo') }, [needsAuth])

  async function signIn(a: (typeof DEMO_ACCOUNTS)[number]) {
    setSigningIn(a.role); setError(null)
    try {
      const s = await login(a.username, a.password)
      saveSession(s); setSession(s); go('#/claims')
    } catch (e) {
      setError(health?.up === false ? 'The backend is not reachable on localhost:8080. Start it, then try again.' : (e as Error).message)
    } finally { setSigningIn(null) }
  }

  const page = route.name === 'claim' && session
    ? <ClaimView key={route.id} session={session} claimId={route.id} onBack={() => go('#/claims')} />
    : route.name === 'claims' && session
      ? <ClaimsView session={session} onOpen={c => go(`#/claims/${c.claimId}`)} />
      : route.name === 'policies' && session
        ? <PoliciesView session={session} />
        : <Landing onSignIn={signIn} signingIn={signingIn} error={error} signedIn={!!session} onOpenClaims={() => go('#/claims')} />

  return (
    <>
      <nav className="nav">
        <div className="container nav-inner">
          <button className="brand" onClick={() => go('#/')}>
            <span className="brand-mark">
              <svg width="14" height="14" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" stroke="#fff" strokeWidth="3.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
            </span>
            Claim Decision Agent
          </button>
          <div className="nav-links">
            <a className="nav-anchor" href="#how">How it works</a>
            <a className="nav-anchor" href="#approves">Approvals</a>
            <a className="nav-anchor" href="#guardrails">Guardrails</a>
            <a className="nav-anchor" href="#stack">Stack</a>
            {session && <a href="#/policies">Rulebook</a>}
            {session && <a href="#/claims">Claims</a>}
          </div>
          <div className="nav-user">
            <span title={health?.up ? `Backend up · AI provider: ${health.ai ?? 'unknown'}` : 'Backend unreachable'}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
              <span className={`status-dot ${health && !health.up ? 'down' : ''}`} />
              <span className="hide-sm">{health == null ? 'Checking…' : health.up ? `Live · ${health.ai === 'noop' ? 'rules only' : health.ai ?? 'AI'}` : 'Offline'}</span>
            </span>
            {session
              ? <button className="btn btn-quiet btn-sm" onClick={signOut}>Sign out <span className="hide-sm">· {session.username}</span></button>
              : <a className="btn btn-primary btn-sm" href="#demo" style={{ textDecoration: 'none' }}>Try demo</a>}
          </div>
        </div>
      </nav>
      {/* Swap pages immediately (no exit wait) and fade the new one in. */}
      <motion.div key={route.name === 'claim' ? route.id : route.name}
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.25 }}>
        {page}
      </motion.div>
    </>
  )
}
