import { screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderUi } from '../test/render'
import { SlaBadge } from './SlaBadge'

const now = new Date('2026-09-29T12:00:00Z')

describe('SlaBadge', () => {
  it('shows the indicator and how late an overdue ticket is', () => {
    renderUi(<SlaBadge sla="OVERDUE" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Vencido')).toBeInTheDocument()
    expect(screen.getByText('venceu há 2 h')).toBeInTheDocument()
  })

  it('shows how much time is left while the clock runs', () => {
    renderUi(<SlaBadge sla="AT_RISK" dueAt="2026-09-29T12:45:00Z" now={now} />)

    expect(screen.getByText('Em risco')).toBeInTheDocument()
    expect(screen.getByText('vence em 45 min')).toBeInTheDocument()
  })

  it('shows no countdown when the clock is stopped', () => {
    renderUi(<SlaBadge sla="PAUSED" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Pausado')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
  })

  describe('with the real clock', () => {
    afterEach(() => vi.useRealTimers())

    it('turns overdue on its own when the deadline passes while the page is open', () => {
      vi.useFakeTimers()
      vi.setSystemTime(now)
      renderUi(<SlaBadge sla="AT_RISK" dueAt="2026-09-29T12:10:00Z" />)
      expect(screen.getByText('Em risco')).toBeInTheDocument()
      expect(screen.getByText('vence em 10 min')).toBeInTheDocument()

      act(() => {
        vi.advanceTimersByTime(11 * 60_000)
      })

      expect(screen.getByText('Vencido')).toBeInTheDocument()
      expect(screen.getByText('venceu há 1 min')).toBeInTheDocument()
    })
  })
})
