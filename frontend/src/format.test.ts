import { describe, expect, it } from 'vitest'
import { money, parseJson, statusLabel, statusTone } from './format'
import { humanize } from './pipeline'

describe('format helpers', () => {
  it('formats rupees with Indian digit grouping', () => {
    expect(money(500000)).toBe('₹5,00,000')
    expect(money(110000, 'INR')).toBe('₹1,10,000')
  })

  it('parses JSON columns and tolerates missing or broken values', () => {
    expect(parseJson<string[]>('["Cosmetic surgery"]')).toEqual(['Cosmetic surgery'])
    expect(parseJson<Record<string, number>>('{"GENERAL":1}')).toEqual({ GENERAL: 1 })
    expect(parseJson(null)).toBeNull()
    expect(parseJson('')).toBeNull()
    expect(parseJson('{not json')).toBeNull()
  })

  it('labels claim statuses for people', () => {
    expect(statusLabel('SUBMITTED')).toBe('Not analyzed')
    expect(statusLabel('HUMAN_REVIEW')).toBe('In review')
    expect(statusTone('APPROVED')).toBe('pass')
    expect(statusTone('REJECTED')).toBe('stop')
  })

  it('turns reason codes into plain words', () => {
    expect(humanize('PROCEDURE_EXCLUDED')).toBe('Procedure excluded')
    expect(humanize('SOME_NEW_CODE')).toBe('Some new code')
  })
})
