import { describe, expect, it } from 'vitest'
import { lastDays, toIsoDate } from './period'

describe('dashboard period (tests run in America/Sao_Paulo)', () => {
  it('uses the local date, not the UTC one: at 22:30 in São Paulo it is still the same day', () => {
    // 22:30 on Sep 29 in São Paulo is 01:30 on Sep 30 in UTC; toISOString() would say "2026-09-30".
    const lateEvening = new Date('2026-09-30T01:30:00Z')

    expect(toIsoDate(lateEvening)).toBe('2026-09-29')
  })

  it('counts today as one of the days, like the backend (both ends inclusive)', () => {
    const today = new Date(2026, 8, 29, 10, 0) // local time; months start at 0

    expect(lastDays(1, today)).toEqual({ from: '2026-09-29', to: '2026-09-29' })
    expect(lastDays(30, today)).toEqual({ from: '2026-08-31', to: '2026-09-29' })
    expect(lastDays(90, today)).toEqual({ from: '2026-07-02', to: '2026-09-29' })
  })
})
