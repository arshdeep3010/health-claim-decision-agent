import { afterEach, describe, expect, it, vi } from 'vitest'
import { analyzeStream, ApiError, setUnauthorizedHandler, type Session, type StageEvent } from './api'

const session: Session = { token: 't0ken', role: 'ADMIN', username: 'admin', expiresAt: '2099-01-01T00:00:00Z' }

/** A fetch Response whose body arrives in the given chunks, like a real network stream. */
function streamResponse(chunks: string[], status = 200) {
  const enc = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(enc.encode(c))
      controller.close()
    },
  })
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } })
}

const sse = (event: string, data: unknown) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`
const stage = (s: string, status: string, data: Record<string, unknown> = {}) =>
  sse('stage', { stage: s, status, elapsedMs: 3, data })

afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(() => {})
})

describe('analyzeStream', () => {
  it('sends the bearer token, reports every stage in order and resolves with the decision', async () => {
    const decision = { decisionId: 'd1', decision: 'APPROVE' }
    const full = stage('AUTH', 'COMPLETED') + stage('LOAD', 'STARTED') + stage('LOAD', 'COMPLETED') + sse('decision', decision)
    // Split mid-event and mid-line to prove the parser buffers across network chunks.
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([full.slice(0, 17), full.slice(17, 90), full.slice(90)]))
    vi.stubGlobal('fetch', fetchMock)

    const seen: StageEvent[] = []
    const result = await analyzeStream(session, 'c1', e => seen.push(e))

    expect(result).toEqual(decision)
    expect(seen.map(e => `${e.stage}:${e.status}`)).toEqual(['AUTH:COMPLETED', 'LOAD:STARTED', 'LOAD:COMPLETED'])
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/claims/c1/analyze/stream')
    expect(init.method).toBe('POST')
    expect(init.headers.Authorization).toBe('Bearer t0ken')
  })

  it('accepts CRLF line endings', async () => {
    const text = (stage('AUTH', 'COMPLETED') + sse('decision', { decisionId: 'd2' })).replace(/\n/g, '\r\n')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([text])))

    const seen: StageEvent[] = []
    await expect(analyzeStream(session, 'c1', e => seen.push(e))).resolves.toEqual({ decisionId: 'd2' })
    expect(seen).toHaveLength(1)
  })

  it('rejects with the server status when the stream ends in an error event', async () => {
    const text = stage('LOAD', 'FAILED', { reason: 'CLAIM_NOT_LOADED' }) + sse('error', { status: 404, message: 'Claim not found' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([text])))

    const err = await analyzeStream(session, 'missing', () => {}).catch(e => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(404)
    expect(err.message).toBe('Claim not found')
  })

  it('rejects if the stream closes without a decision', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([stage('AUTH', 'COMPLETED')])))
    await expect(analyzeStream(session, 'c1', () => {})).rejects.toMatchObject({ status: 502 })
  })

  it('surfaces a non-OK response and signals 401 to the app', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: 'Token expired' }), { status: 401, headers: { 'Content-Type': 'application/json' } })))

    await expect(analyzeStream(session, 'c1', () => {})).rejects.toMatchObject({ status: 401, message: 'Token expired' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })
})
