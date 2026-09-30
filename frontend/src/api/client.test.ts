import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setUnauthorizedHandler, tokenStorage } from './client'

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
})
