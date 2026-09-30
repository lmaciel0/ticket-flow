import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Ticket } from '../api/types'
import { tokenStorage } from '../api/client'
import { RequireAuth } from '../auth/guards'
import { mockApi, renderRoutes, sentBody } from '../test/render'
import { CommentsSection } from './CommentsSection'

const agent = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }
const requester = { id: 1, name: 'Ana', email: 'ana@x.com', role: 'REQUESTER', active: true, demo: true }

const ticket = {
  id: 7,
  status: 'IN_PROGRESS',
  requester: { id: 1, name: 'Ana' },
} as Ticket

const comments = [
  { id: 1, text: 'Olá, preciso de ajuda', internal: false, author: { id: 1, name: 'Ana' }, createdAt: '2026-09-29T12:00:00Z' },
  { id: 2, text: 'Suspeito do cabo', internal: true, author: { id: 2, name: 'Bruno' }, createdAt: '2026-09-29T12:05:00Z' },
]

function renderSection(me: typeof agent | typeof requester, replies: Parameters<typeof mockApi>[0] = {}) {
  tokenStorage.set('jwt')
  const fetchMock = mockApi({
    'GET /auth/me': [200, me],
    'GET /tickets/7/comments': [200, comments],
    ...replies,
  })
  const utils = renderRoutes(
    [{ element: <RequireAuth />, children: [{ path: '/t', element: <CommentsSection ticket={ticket} canComment /> }] }],
    '/t',
  )
  return { fetchMock, ...utils }
}

afterEach(() => vi.unstubAllGlobals())

describe('CommentsSection', () => {
  it('highlights internal notes and lets the team write them', async () => {
    const { fetchMock, user } = renderSection(agent, {
      'POST /tickets/7/comments': [201, { ...comments[1], id: 3 }],
      'GET /tickets/7': [200, ticket],
    })

    expect(await screen.findByText('🔒 Nota interna')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Novo comentário'), 'Pedir aprovação ao gestor')
    await user.click(screen.getByRole('checkbox', { name: /Nota interna/ }))
    await user.click(screen.getByRole('button', { name: 'Salvar nota' }))

    expect(sentBody(fetchMock, 'POST /tickets/7/comments')).toEqual({ text: 'Pedir aprovação ao gestor', internal: true })
  })

  it('does not offer the internal note option to the requester', async () => {
    renderSection(requester)

    expect(await screen.findByText('Olá, preciso de ajuda')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /Nota interna/ })).not.toBeInTheDocument()
  })
})
