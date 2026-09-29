import { waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { RequireAuth } from '../auth/guards'
import { mockApi, renderRoutes } from '../test/render'
import { TicketListPage } from './TicketListPage'

const agent = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }

afterEach(() => vi.unstubAllGlobals())

describe('TicketListPage', () => {
  it('goes back to the last page when the page in the URL no longer exists', async () => {
    // e.g. the agent resolved the only ticket of page 2 and pressed Back, or pasted an old link.
    tokenStorage.set('jwt')
    mockApi({
      'GET /auth/me': [200, agent],
      'GET /categories': [200, []],
      'GET /tickets': [200, { content: [], page: 2, size: 20, totalElements: 20, totalPages: 1 }],
    })
    const { router } = renderRoutes(
      [{ element: <RequireAuth />, children: [{ path: '/tickets', element: <TicketListPage /> }] }],
      '/tickets?page=3&mine=true',
    )

    await waitFor(() => expect(router.state.location.search).toBe('?mine=true'))
  })
})
