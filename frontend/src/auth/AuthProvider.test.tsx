import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { mockApi, renderRoutes } from '../test/render'
import { useAuth } from './authContext'
import { RequireAuth } from './guards'

const ana = { id: 1, name: 'Ana', email: 'ana@x.com', role: 'REQUESTER', active: true, demo: true }
const bruno = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }

function WhoAmI() {
  const { user, logout } = useAuth()
  return (
    <>
      <p>Logado como {user?.name}</p>
      <button onClick={logout}>Sair</button>
    </>
  )
}

const routes = [
  { element: <RequireAuth />, children: [{ path: '/tickets', element: <WhoAmI /> }] },
  { path: '/login', element: <p>Tela de login</p> },
]

/** What another tab of the app sends on the shared channel. */
function anotherTabSays(type: 'login' | 'logout') {
  const channel = new BroadcastChannel('ticketflow-auth')
  channel.postMessage({ type })
  channel.close()
}

afterEach(() => vi.unstubAllGlobals())

describe('AuthProvider', () => {
  it('restores the session from the refresh cookie when the page opens', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })

    renderRoutes(routes, '/tickets')

    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()
    expect(tokenStorage.get()).toBe('token-ana')
  })

  it('shows the login page when there is no session to restore', async () => {
    mockApi({})

    renderRoutes(routes, '/tickets')

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })

  it('ends the session on the server when logging out', async () => {
    const fetchMock = mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    const { user } = renderRoutes(routes, '/tickets')
    await user.click(await screen.findByRole('button', { name: 'Sair' }))

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input) === '/api/auth/logout')).toBe(true)
    expect(tokenStorage.get()).toBeNull()
  })

  it('follows a login with another account in another tab', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    mockApi({ 'POST /auth/refresh': [200, { token: 'token-bruno', user: bruno }] })
    anotherTabSays('login')

    expect(await screen.findByText('Logado como Bruno')).toBeInTheDocument()
  })

  it('follows a logout in another tab', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    anotherTabSays('logout')

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })
})
