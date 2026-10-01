import { screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderUi } from '../test/render'
import { FirstResponseBadge } from './FirstResponseBadge'

const now = new Date('2026-09-29T12:00:00Z')

describe('FirstResponseBadge', () => {
  it('shows how much time is left while nobody has answered', () => {
    renderUi(<FirstResponseBadge indicator="PENDING" dueAt="2026-09-29T12:25:00Z" now={now} />)

    expect(screen.getByText('Pendente')).toBeInTheDocument()
    expect(screen.getByText('vence em 25 min')).toBeInTheDocument()
  })

  it('shows how late the answer is', () => {
    renderUi(<FirstResponseBadge indicator="OVERDUE" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Atrasada')).toBeInTheDocument()
    expect(screen.getByText('venceu há 2 h')).toBeInTheDocument()
  })

  it('shows the result without a countdown once someone answered', () => {
    const { unmount } = renderUi(
      <FirstResponseBadge indicator="MET" dueAt="2026-09-29T10:00:00Z" now={now} />,
    )
    expect(screen.getByText('No prazo')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
    unmount()

    renderUi(<FirstResponseBadge indicator="BREACHED" dueAt="2026-09-29T10:00:00Z" now={now} />)
    expect(screen.getByText('Fora do prazo')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
  })

  it('renders nothing when the metric does not apply', () => {
    renderUi(<FirstResponseBadge indicator={null} dueAt={null} now={now} />)

    for (const label of ['Pendente', 'Atrasada', 'No prazo', 'Fora do prazo']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })

  describe('with the real clock', () => {
    afterEach(() => vi.useRealTimers())

    it('turns late on its own when the deadline passes while the page is open', () => {
      vi.useFakeTimers()
      vi.setSystemTime(now)
      renderUi(<FirstResponseBadge indicator="PENDING" dueAt="2026-09-29T12:10:00Z" />)
      expect(screen.getByText('Pendente')).toBeInTheDocument()

      act(() => {
        vi.advanceTimersByTime(11 * 60_000)
      })

      expect(screen.getByText('Atrasada')).toBeInTheDocument()
      expect(screen.getByText('venceu há 1 min')).toBeInTheDocument()
    })
  })
})
