export const money = (n: number, ccy = 'INR') =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: ccy, maximumFractionDigits: 0 }).format(n)

export const statusTone = (s: string) =>
  s === 'APPROVED' ? 'pass' : s === 'REJECTED' ? 'stop' : s === 'HUMAN_REVIEW' ? 'warn' : ''

const STATUS_LABELS: Record<string, string> = {
  SUBMITTED: 'Not analyzed', APPROVED: 'Approved', REJECTED: 'Rejected', HUMAN_REVIEW: 'In review',
  ANALYZED: 'Analyzed', PROCESSING: 'Processing', FAILED: 'Failed',
}
export const statusLabel = (s: string) => STATUS_LABELS[s] ?? s

/** Parses a JSON column stored as text (e.g. policy exclusions); null when absent or invalid. */
export function parseJson<T>(raw: string | null | undefined): T | null {
  if (!raw) return null
  try { return JSON.parse(raw) as T } catch { return null }
}
