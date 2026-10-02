import { notifications } from '@mantine/notifications'
import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, tokenStorage } from '../api/client'
import type { User } from '../api/types'
import { jsonResponse, mockApi, renderRoutes } from '../test/render'
import { useAuth } from './authContext'
import { RequireAuth } from './guards'

const ana: User = { id: 1, name: 'Ana', email: 'ana@x.com', role: 'REQUESTER', active: true, demo: true }
const bruno: User = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }

function WhoAmI() {
  const { user, logout } = useAuth()
  return (
    <>
      <p>Logado como {user?.name}</p>
      <button onClick={logout}>Sair</button>
    </>
  )
}

function SignIn() {
  const { login } = useAuth()
  return (
    <button onClick={() => login({ token: 'token-bruno', user: bruno, sessionCode: 'code-2' })}>Entrar de novo</button>
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

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

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

  it('shows the login page when the refresh answer is garbage instead of hanging', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async () => new Response('<html>not the API</html>', { status: 200 })),
    )

    renderRoutes(routes, '/tickets')

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })

  it('shows the login page when the refresh itself blows up instead of hanging', async () => {
    mockApi({})
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: () => Promise.reject(new Error('locks unavailable')) },
    })

    try {
      renderRoutes(routes, '/tickets')

      expect(await screen.findByText('Tela de login')).toBeInTheDocument()
    } finally {
      Reflect.deleteProperty(navigator, 'locks')
    }
  })

  it('waits for a logout still in flight before saving a new login in the cookie', async () => {
    // A late answer to POST /auth/logout (Set-Cookie Max-Age=0) would delete the cookie the new session just set.
    const calls: string[] = []
    let answerLogout: () => void = () => {}
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input, init) => {
        const key = `${init?.method ?? 'GET'} ${String(input)}`
        calls.push(key)
        if (key === 'POST /api/auth/refresh') {
          return jsonResponse(200, { token: 'token-ana', user: ana })
        }
        if (key === 'POST /api/auth/logout') {
          await new Promise<void>((resolve) => {
            answerLogout = resolve
          })
        }
        return jsonResponse(204, null)
      }),
    )
    const loginRoutes = [
      { element: <RequireAuth />, children: [{ path: '/tickets', element: <WhoAmI /> }] },
      { path: '/login', element: <SignIn /> },
    ]
    const { user } = renderRoutes(loginRoutes, '/tickets')
    await user.click(await screen.findByRole('button', { name: 'Sair' }))
    await user.click(await screen.findByRole('button', { name: 'Entrar de novo' }))
    await waitFor(() => expect(calls).toContain('POST /api/auth/logout'))

    expect(calls).not.toContain('POST /api/auth/session')

    answerLogout()

    await waitFor(() => expect(calls).toContain('POST /api/auth/session'))
    expect(calls.indexOf('POST /api/auth/session')).toBeGreaterThan(calls.indexOf('POST /api/auth/logout'))
  })

  it('warns, but keeps the user logged in, when the session cannot be saved in the cookie', async () => {
    const show = vi.spyOn(notifications, 'show')
    mockApi({ 'POST /auth/session': [500, { status: 500, detail: 'Erro interno.' }] })
    const loginRoutes = [{ path: '/', element: <SignIn /> }]
    const { user } = renderRoutes(loginRoutes, '/')

    await user.click(await screen.findByRole('button', { name: 'Entrar de novo' }))

    await waitFor(() =>
      expect(show).toHaveBeenCalledWith(
        expect.objectContaining({
          color: 'yellow',
          message: 'Não foi possível manter a sessão neste navegador: ao recarregar a página, entre de novo.',
        }),
      ),
    )
    expect(tokenStorage.get()).toBe('token-bruno')
  })

  it('shows one "session expired" notice when several requests fail together', async () => {
    const show = vi.spyOn(notifications, 'show')
    let refreshes = 0
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input, init) => {
        if (String(input) === '/api/auth/refresh') {
          // the first one restores the session on load; later ones are refused
          return refreshes++ === 0
            ? jsonResponse(200, { token: 'token-ana', user: ana })
            : jsonResponse(401, { status: 401, detail: 'Sessão expirada. Entre novamente.' })
        }
        return jsonResponse(401, { status: 401, detail: init?.method ?? 'Autenticação necessária.' })
      }),
    )
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    await Promise.allSettled([api.get('/tickets'), api.get('/categories'), api.get('/users')])

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
    expect(show).toHaveBeenCalledTimes(1)
    expect(show).toHaveBeenCalledWith(expect.objectContaining({ message: 'Sua sessão expirou. Entre novamente.' }))
  })

  describe('under StrictMode (effects run, clean up and run again)', () => {
    it('still ends the session on the server when logging out', async () => {
      const fetchMock = mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
      const { user } = renderRoutes(routes, '/tickets', { strict: true })
      await user.click(await screen.findByRole('button', { name: 'Sair' }))

      expect(await screen.findByText('Tela de login')).toBeInTheDocument()
      expect(fetchMock.mock.calls.some(([input]) => String(input) === '/api/auth/logout')).toBe(true)
    })

    it('still follows a logout in another tab', async () => {
      mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
      renderRoutes(routes, '/tickets', { strict: true })
      expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

      anotherTabSays('logout')

      expect(await screen.findByText('Tela de login')).toBeInTheDocument()
    })
  })
})
