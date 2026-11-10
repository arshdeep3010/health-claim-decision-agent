// DEV ONLY: the accounts DevUserSeeder creates under the 'dev' profile, with its
// default passwords (override them via SEED_*_PASSWORD on the backend).
// These never exist in the prod profile, so this one-click sign-in is a local demo convenience.
import type { Role } from './api'

export const DEMO_ACCOUNTS: { role: Role; username: string; password: string; title: string; blurb: string }[] = [
  { role: 'ADMIN', username: 'admin', password: 'admin-dev-password', title: 'Administrator',
    blurb: 'Everything, including the full audit trail.' },
  { role: 'REVIEWER', username: 'reviewer', password: 'reviewer-dev-password', title: 'Claims reviewer',
    blurb: 'Analyze claims and resolve human reviews.' },
  { role: 'USER', username: 'user', password: 'user-dev-password', title: 'Claims analyst',
    blurb: 'Submit and analyze claims.' },
]
