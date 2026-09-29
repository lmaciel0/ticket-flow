import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../api/queryClient'
import type { Ticket } from '../api/types'
import { mockApi, sentBody } from '../test/render'
import { ticketKeys, useChangeStatus } from './api'

const ticket: Ticket = {
  id: 7,
  title: 'Impressora parada',
  description: 'Não imprime',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  category: { id: 2, name: 'Hardware' },
  requester: { id: 1, name: 'Sol' },
  assignee: { id: 2, name: 'Ana' },
  createdAt: '2026-09-29T10:00:00Z',
  dueAt: '2026-09-30T10:00:00Z',
  resolvedAt: null,
  slaBreached: null,
  sla: 'ON_TRACK',
  version: 3,
}

function setup() {
  const queryClient = createQueryClient()
  queryClient.setQueryData(ticketKeys.detail(7), ticket)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => vi.unstubAllGlobals())

describe('ticket mutations keep the screen in sync with the version', () => {
  it('stores the ticket answered by the API, so the next action sends the new version', async () => {
    const fetchMock = mockApi({ 'POST /tickets/7/status': [200, { ...ticket, status: 'WAITING_REQUESTER', version: 4 }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'WAITING_REQUESTER', version: 3 }))

    expect(sentBody(fetchMock, 'POST /tickets/7/status')).toEqual({ status: 'WAITING_REQUESTER', version: 3 })
    expect(queryClient.getQueryData<Ticket>(ticketKeys.detail(7))).toMatchObject({
      status: 'WAITING_REQUESTER',
      version: 4,
    })
  })

  it('reloads the ticket on 409, when someone else changed it first', async () => {
    mockApi({ 'POST /tickets/7/status': [409, { status: 409, detail: 'O chamado foi alterado por outra pessoa.' }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'RESOLVED', version: 3 }).catch(() => {}))

    expect(queryClient.getQueryState(ticketKeys.detail(7))?.isInvalidated).toBe(true)
  })
})
