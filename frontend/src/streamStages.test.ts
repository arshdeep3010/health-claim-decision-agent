import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ServerStage, StageEvent } from './api'
import type { StageId, StageStatus } from './pipeline'
import { finalStatus, StagePacer, took } from './streamStages'

const ev = (stage: ServerStage, status: StageEvent['status'], data: Record<string, unknown> = {}, elapsedMs = 12): StageEvent =>
  ({ stage, status, elapsedMs, data })

describe('took', () => {
  it('formats server-measured durations', () => {
    expect(took(0)).toBe('<1 ms')
    expect(took(38)).toBe('38 ms')
    expect(took(4200)).toBe('4.2 s')
  })
})

describe('finalStatus', () => {
  it('shows retrieval results with the embedding model', () => {
    const s = finalStatus(ev('RETRIEVE', 'COMPLETED', { clauses: 3, topK: 5, topSimilarity: 0.3034, embeddingModel: 'nvidia/nemotron-3.5-embed-1b' }))
    expect(s).toMatchObject({ state: 'done', tone: 'pass', result: '3 policy clauses retrieved' })
    expect(s.detail).toEqual(['top similarity 0.30', 'top-k 5', '12 ms', 'nvidia/nemotron-3.5-embed-1b'])
  })

  it('warns when no policy wording matched', () => {
    expect(finalStatus(ev('RETRIEVE', 'COMPLETED', { clauses: 0 }))).toMatchObject({ tone: 'warn', result: 'No matching clauses' })
  })

  it('names the rule that blocked a claim', () => {
    const s = finalStatus(ev('RULES', 'COMPLETED', { outcome: 'REJECT', flagged: ['PROCEDURE_EXCLUDED'], passed: [], evaluated: 7 }))
    expect(s).toMatchObject({ tone: 'stop', result: 'Blocked by a policy rule' })
    expect(s.detail).toContain('Procedure excluded')
  })

  it('counts the rules that passed', () => {
    const passed = ['A', 'B', 'C', 'D', 'E', 'F', 'G']
    expect(finalStatus(ev('RULES', 'COMPLETED', { outcome: 'ALLOW', passed, evaluated: 7 })).result).toBe('All 7 applicable rules passed')
  })

  it('reports the AI suggestion with its confidence and model', () => {
    const s = finalStatus(ev('AI', 'COMPLETED', { suggestion: 'APPROVE', confidence: 0.95, model: 'some/llm' }, 3600))
    expect(s).toMatchObject({ tone: 'pass', result: 'AI suggests approve' })
    expect(s.detail).toEqual(['95% confidence', '3.6 s', 'some/llm'])
  })

  it('shows a decision model’s probability for every outcome and its web sources', () => {
    const s = finalStatus(ev('AI', 'COMPLETED', {
      engine: 'decision-model', suggestion: 'REJECT', confidence: 0.85, model: 'parallel/decisions-fast',
      probabilities: { APPROVE: 0.05, HUMAN_REVIEW: 0.104, REJECT: 0.846 },
      sources: [{ url: 'https://a.example' }, { url: 'https://b.example' }],
    }, 1300))
    expect(s).toMatchObject({ tone: 'stop', result: 'Decision model suggests reject' })
    expect(s.detail).toEqual(['approve 5% · review 10% · reject 85%', '1.3 s', 'parallel/decisions-fast', '2 web sources'])
  })

  it('makes clear the AI was not called when the rules decided', () => {
    expect(finalStatus(ev('AI', 'SKIPPED', { reason: 'RULES_DECIDED' })))
      .toEqual({ state: 'skipped', result: 'Not needed: the rules decided', detail: ['no AI call was made'] })
  })

  it('shows an AI failure as routed to a human', () => {
    const s = finalStatus(ev('AI', 'FAILED', { reason: 'AI_PROVIDER_FAILURE' }))
    expect(s).toMatchObject({ state: 'failed', result: 'AI provider error' })
    expect(s.detail).toContain('safely routed to human review')
  })

  it('lists guardrail overrides in plain words', () => {
    const s = finalStatus(ev('GUARDRAILS', 'COMPLETED', { decision: 'HUMAN_REVIEW', decidedBy: 'AI_WITH_GUARDRAILS', overrides: ['LOW_AI_CONFIDENCE'] }))
    expect(s).toMatchObject({ tone: 'warn', result: 'Final verdict: Needs review', detail: ['AI confidence too low'] })
  })
})

describe('StagePacer', () => {
  let applied: [StageId, StageStatus['state']][]
  let pacer: StagePacer

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance', 'Date'] })
    applied = []
    pacer = new StagePacer((id, s) => applied.push([id, s.state]), 500)
  })
  afterEach(() => vi.useRealTimers())

  it('keeps a sub-millisecond stage running for the minimum time before showing its result', async () => {
    pacer.push(ev('RULES', 'STARTED'))
    pacer.push(ev('RULES', 'COMPLETED', { outcome: 'ALLOW', passed: [], evaluated: 7 }))
    await vi.advanceTimersByTimeAsync(0)
    expect(applied).toEqual([['rules', 'running']])

    await vi.advanceTimersByTimeAsync(499)
    expect(applied).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(applied).toEqual([['rules', 'running'], ['rules', 'done']])
  })

  it('does not delay a stage that already took longer than the minimum', async () => {
    pacer.push(ev('AI', 'STARTED'))
    await vi.advanceTimersByTimeAsync(3000) // the model call itself
    pacer.push(ev('AI', 'COMPLETED', { suggestion: 'APPROVE', confidence: 0.9 }, 3000))
    await vi.advanceTimersByTimeAsync(0)
    expect(applied).toEqual([['ai', 'running'], ['ai', 'done']])
  })

  it('gives a skipped stage only a short beat and keeps the order of stages', async () => {
    pacer.push(ev('AI', 'SKIPPED', { reason: 'RULES_DECIDED' }))
    pacer.push(ev('GUARDRAILS', 'COMPLETED', { decision: 'REJECT', decidedBy: 'RULES' }))
    await vi.advanceTimersByTimeAsync(259)
    expect(applied).toEqual([])
    await vi.advanceTimersByTimeAsync(1)
    expect(applied).toEqual([['ai', 'skipped'], ['guard', 'running']])
    await vi.advanceTimersByTimeAsync(500)
    expect(applied).toEqual([['ai', 'skipped'], ['guard', 'running'], ['guard', 'done']])
  })

  it('stops applying updates once cancelled', async () => {
    pacer.push(ev('LOAD', 'STARTED'))
    pacer.push(ev('LOAD', 'COMPLETED', { facts: 5 }))
    await vi.advanceTimersByTimeAsync(0)
    pacer.cancel()
    await vi.advanceTimersByTimeAsync(1000)
    expect(applied).toEqual([['load', 'running']])
  })
})
