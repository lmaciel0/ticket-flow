import { describe, expect, it } from 'vitest'
import { formatDateTime, formatDueIn, formatDuration, formatFileSize, formatHours, formatPercent } from './format'

const MINUTE = 60_000
const HOUR = 60 * MINUTE

describe('formatDuration', () => {
  it('uses minutes below one hour', () => {
    expect(formatDuration(0)).toBe('0 min')
    expect(formatDuration(59 * MINUTE)).toBe('59 min')
  })

  it('uses whole hours below one day', () => {
    expect(formatDuration(HOUR)).toBe('1 h')
    expect(formatDuration(23 * HOUR + 59 * MINUTE)).toBe('23 h')
  })

  it('uses days and the remaining hours from one day on', () => {
    expect(formatDuration(24 * HOUR)).toBe('1 d')
    expect(formatDuration(50 * HOUR)).toBe('2 d 2 h')
  })
})

describe('formatDueIn', () => {
  const now = new Date('2026-09-29T12:00:00Z')

  it('says how long until the deadline', () => {
    expect(formatDueIn('2026-09-29T15:00:00Z', now)).toBe('vence em 3 h')
  })

  it('says how long ago the deadline passed', () => {
    expect(formatDueIn('2026-09-29T11:30:00Z', now)).toBe('venceu há 30 min')
  })

  it('treats the exact deadline as already passed (like the backend: now >= dueAt)', () => {
    expect(formatDueIn('2026-09-29T12:00:00Z', now)).toBe('venceu há 0 min')
  })
})

describe('formatDateTime', () => {
  it('shows the instant in the browser time zone (tests run in America/Sao_Paulo)', () => {
    // 01:30 UTC is still the previous day, 22:30, in São Paulo.
    expect(formatDateTime('2026-09-30T01:30:00Z')).toBe('29/09/2026, 22:30')
  })
})

describe('numbers that may be missing', () => {
  it('shows a dash when there is no value (e.g. nothing resolved in the period)', () => {
    expect(formatPercent(null)).toBe('—')
    expect(formatHours(null)).toBe('—')
  })

  it('uses the Brazilian decimal comma', () => {
    expect(formatPercent(87.5)).toBe('87,5%')
    expect(formatHours(12.25)).toBe('12,3 h')
  })
})

describe('formatFileSize', () => {
  it('picks a readable unit', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2 KB')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5 MB')
    expect(formatFileSize(1.5 * 1024 * 1024)).toBe('1,5 MB')
  })
})
