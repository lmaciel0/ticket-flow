import { act, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { mockApi, renderRoutes } from '../test/render'
import { useAuth } from './authContext'
import { RequireAuth } from './guards'

const TOKEN_KEY = 'ticketflow.token'

const ana = { id: 1, name: 'Ana', email: 'ana@x.com', role: 'REQUESTER', active: true, demo: true }
const bruno = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }

function WhoAmI() {
  const { user } = useAuth()
  return <p>Logado como {user?.name}</p>
}

const routes = [
  { element: <RequireAuth />, children: [{ path: '/tickets', element: <WhoAmI /> }] },
  { path: '/login', element: <p>Tela de login</p> },
]

/** What the browser does in THIS tab when another tab writes to localStorage. */
function anotherTabSetsToken(value: string | null) {
  if (value === null) {
    localStorage.removeItem(TOKEN_KEY)
  } else {
    localStorage.setItem(TOKEN_KEY, value)
  }
  window.dispatchEvent(new StorageEvent('storage', { key: TOKEN_KEY, newValue: value }))
}

afterEach(() => vi.unstubAllGlobals())

describe('AuthProvider keeps every tab on the session that its requests use', () => {
  it('follows a login with another account in another tab', async () => {
    tokenStorage.set('token-ana')
    mockApi({ 'GET /auth/me': [200, ana] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    mockApi({ 'GET /auth/me': [200, bruno] })
    act(() => anotherTabSetsToken('token-bruno'))

    expect(await screen.findByText('Logado como Bruno')).toBeInTheDocument()
  })

  // rewritten in Task 5
  it.skip('follows a logout in another tab', async () => {
    tokenStorage.set('token-ana')
    mockApi({ 'GET /auth/me': [200, ana] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    act(() => anotherTabSetsToken(null))

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })
})
