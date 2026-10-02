import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, refreshSession, setUnauthorizedHandler, tokenStorage } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  setUnauthorizedHandler(() => {})
})

describe('api client', () => {
  it('prefixes /api and sends the stored token as a Bearer header', async () => {
    tokenStorage.set('abc.def.ghi')
    const fetchMock = mockFetch(jsonResponse(200, { id: 1 }))

    const result = await api.get<{ id: number }>('/tickets/1')

    expect(result).toEqual({ id: 1 })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/tickets/1')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer abc.def.ghi')
  })

  it('ignores a trailing slash in VITE_API_URL (the API firewall refuses a double slash)', async () => {
    vi.stubEnv('VITE_API_URL', 'https://ticket-flow-api.onrender.com/')
    vi.resetModules() // the URL is read when the module loads
    const fresh = await import('./client')
    const fetchMock = mockFetch(jsonResponse(200, []))

    await fresh.api.get('/categories')

    expect(fetchMock.mock.calls[0][0]).toBe('https://ticket-flow-api.onrender.com/api/categories')
  })

  it('ignores spaces and line breaks pasted around VITE_API_URL', async () => {
    vi.stubEnv('VITE_API_URL', ' https://ticket-flow-api.onrender.com/ \n')
    vi.resetModules() // the URL is read when the module loads
    const fresh = await import('./client')
    const fetchMock = mockFetch(jsonResponse(200, []))

    await fresh.api.get('/categories')

    expect(fetchMock.mock.calls[0][0]).toBe('https://ticket-flow-api.onrender.com/api/categories')
  })

  it('sends JSON bodies with the JSON content type', async () => {
    const fetchMock = mockFetch(jsonResponse(201, { id: 7 }))

    await api.post('/tickets', { title: 'Sem acesso' })

    const [, init] = fetchMock.mock.calls[0]
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe('{"title":"Sem acesso"}')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
  })

  it('lets the browser set the multipart content type for uploads', async () => {
    const fetchMock = mockFetch(jsonResponse(201, { id: 3 }))
    const form = new FormData()
    form.append('file', new File(['hello'], 'nota.txt'))

    await api.post('/tickets/1/attachments', form)

    const [, init] = fetchMock.mock.calls[0]
    expect(init?.body).toBe(form)
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
  })

  it('never sends the stored token to login or sign-up (Spring answers 401 to a dead token even there)', async () => {
    tokenStorage.set('token-from-yesterday')
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, { token: 'new', user: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await api.post('/auth/login', { email: 'a@b.c', password: 'demo1234' })
    await api.post('/auth/register', { name: 'A', email: 'a@b.c', password: 'demo1234' })

    for (const [, init] of fetchMock.mock.calls) {
      expect(new Headers(init?.headers).has('Authorization')).toBe(false)
    }
  })

  it('turns a ProblemDetail into an ApiError with the message and the field errors', async () => {
    mockFetch(jsonResponse(400, {
      status: 400,
      detail: 'Dados inválidos.',
      errors: { title: 'Informe o título.' },
    }))

    const error = await api.post('/tickets', {}).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, message: 'Dados inválidos.', fieldErrors: { title: 'Informe o título.' } })
  })

  it('uses a Portuguese message for 413, whatever the server wrote', async () => {
    mockFetch(jsonResponse(413, { status: 413, detail: 'Maximum upload size exceeded' }))

    const error = await api.post('/tickets/1/attachments', new FormData()).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 413, message: 'O arquivo passa do limite de 5 MB.' })
  })

  it('shows the wait the server asked for after too many attempts', async () => {
    mockFetch(jsonResponse(429, { status: 429, detail: 'Muitas tentativas. Tente de novo em 12 s.' }))

    const error = await api.post('/auth/login', {}).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 429, message: 'Muitas tentativas. Tente de novo em 12 s.' })
  })

  it('still explains a 429 that comes without a message', async () => {
    mockFetch(new Response('', { status: 429 }))

    const error = await api.post('/auth/login', {}).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 429, message: 'Muitas tentativas. Tente de novo em instantes.' })
  })

  it('adds the trace id to server errors so the user can quote it to support', async () => {
    mockFetch(jsonResponse(500, { status: 500, detail: 'Erro interno.', traceId: 'abc-12345678' }))

    const error = await api.get('/tickets').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toBe('Erro interno. (código abc-12345678)')
    expect((error as ApiError).traceId).toBe('abc-12345678')
  })

  it('falls back to the X-Request-Id header when the error body has no trace id', async () => {
    mockFetch(
      new Response('<html>Bad gateway</html>', { status: 502, headers: { 'X-Request-Id': 'from-header-01' } }),
    )

    const error = (await api.get('/tickets').catch((e: unknown) => e)) as ApiError

    expect(error.traceId).toBe('from-header-01')
    expect(error.message).toContain('(código from-header-01)')
  })

  it('keeps the trace id out of the message for errors the user can fix', async () => {
    mockFetch(jsonResponse(409, { status: 409, detail: 'Chamado alterado.', traceId: 'abc-12345678' }))

    const error = (await api.get('/tickets').catch((e: unknown) => e)) as ApiError

    expect(error.message).toBe('Chamado alterado.')
    expect(error.traceId).toBe('abc-12345678')
  })

  it('explains when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await api.get('/tickets').catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 0, message: 'Não foi possível falar com o servidor. Tente de novo em instantes.' })
  })

  it('drops the session when a request WITH a token gets 401 (expired token, demo reset)', async () => {
    tokenStorage.set('expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mockFetch(jsonResponse(401, { status: 401, detail: 'Autenticação necessária.' }))

    await expect(api.get('/tickets')).rejects.toBeInstanceOf(ApiError)

    expect(tokenStorage.get()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not treat a wrong password (401 without token) as an expired session', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mockFetch(jsonResponse(401, { status: 401, detail: 'E-mail ou senha inválidos.' }))

    const error = await api.post('/auth/login', { email: 'a@b.c', password: 'errada' }).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 401, message: 'E-mail ou senha inválidos.' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('reads the file name and the truncation flag of a generated file', async () => {
    mockFetch(
      new Response('a;b', {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="chamados-2026-10-01.csv"', 'X-Export-Truncated': 'true' },
      }),
    )

    const file = await api.file('/tickets/export?format=CSV')

    expect(file.filename).toBe('chamados-2026-10-01.csv')
    expect(file.truncated).toBe(true)
    expect(await file.blob.text()).toBe('a;b')
  })

  it('calls the cookie routes on the site itself, never with the access token', async () => {
    tokenStorage.set('abc.def.ghi')
    const fetchMock = mockFetch(new Response(null, { status: 204 }))

    const result = await api.post('/auth/logout')

    expect(result).toBeUndefined()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/logout')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBeNull()
  })

  it('renews an expired access token once and repeats the request', async () => {
    tokenStorage.set('old')
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'new', user: { id: 1 } }))
      .mockResolvedValueOnce(jsonResponse(200, [{ id: 7 }]))
    vi.stubGlobal('fetch', fetchMock)

    const result = await api.get('/tickets')

    expect(result).toEqual([{ id: 7 }])
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh')
    expect(new Headers(fetchMock.mock.calls[2][1]?.headers).get('Authorization')).toBe('Bearer new')
    expect(tokenStorage.get()).toBe('new')
  })

  it('renews only once when many requests fail together', async () => {
    tokenStorage.set('old')
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      if (String(input) === '/api/auth/refresh') {
        return jsonResponse(200, { token: 'new', user: { id: 1 } })
      }
      const auth = new Headers(init?.headers).get('Authorization')
      return auth === 'Bearer new' ? jsonResponse(200, {}) : jsonResponse(401, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await Promise.all([api.get('/tickets'), api.get('/categories'), api.get('/users')])

    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/auth/refresh')).toHaveLength(1)
  })

  it('ends the session when the renewal is refused', async () => {
    tokenStorage.set('old')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => jsonResponse(401, { status: 401 })))

    await expect(api.get('/tickets')).rejects.toMatchObject({ status: 401 })

    expect(onUnauthorized).toHaveBeenCalledOnce()
    expect(tokenStorage.get()).toBeNull()
  })

  it('treats an unreachable server as no session', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))

    expect(await refreshSession()).toBeNull()
  })

  it('treats a successful refresh answer that is not JSON as no session', async () => {
    tokenStorage.set('old-token')
    mockFetch(new Response('<html>bad gateway page</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }))

    expect(await refreshSession()).toBeNull()
    expect(tokenStorage.get()).toBeNull()
  })

  it('forgets the token an older version kept in localStorage', async () => {
    localStorage.setItem('ticketflow.token', 'from-last-week')
    vi.resetModules()

    await import('./client')

    expect(localStorage.getItem('ticketflow.token')).toBeNull()
  })
})
