// Without the trailing slash, if any: "https://api.example.com/" + "/api" would be a double slash.
const API_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/+$/, '')
export const TOKEN_KEY = 'ticketflow.token'

const FALLBACK_MESSAGES: Record<number, string> = {
  0: 'Não foi possível falar com o servidor. Tente de novo em instantes.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado.',
  413: 'O arquivo passa do limite de 5 MB.',
}

/** An error answered by the API (RFC 9457 ProblemDetail), or status 0 when the server was unreachable. */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

/** The JWT lives in localStorage (trade-off documented in the README: simple, but readable by XSS). */
export const tokenStorage = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

// Login and sign-up never carry a token: Spring rejects an invalid Bearer header even on public
// routes, so a token left over from yesterday (demo reset) would make the login itself fail.
const PUBLIC_PATHS = ['/auth/login', '/auth/register']

let onUnauthorized: () => void = () => {}

/** The auth layer registers here what to do when the session dies (clear state, go to login). */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

async function toApiError(response: Response): Promise<ApiError> {
  let detail: string | undefined
  let fieldErrors: Record<string, string> = {}
  try {
    const problem = (await response.json()) as { detail?: string; errors?: Record<string, string> }
    detail = problem.detail
    fieldErrors = problem.errors ?? {}
  } catch {
    // Not JSON (e.g. a proxy error page): fall back to a generic message.
  }
  const message =
    response.status === 413
      ? FALLBACK_MESSAGES[413]
      : (detail ?? FALLBACK_MESSAGES[response.status] ?? 'Algo deu errado. Tente de novo.')
  return new ApiError(response.status, message, fieldErrors)
}

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const token = PUBLIC_PATHS.includes(path) ? null : tokenStorage.get()
  const headers = new Headers()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body // the browser writes the multipart Content-Type with its boundary
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    payload = JSON.stringify(body)
  }

  let response: Response
  try {
    response = await fetch(`${API_URL}/api${path}`, { method, headers, body: payload })
  } catch {
    throw new ApiError(0, FALLBACK_MESSAGES[0])
  }

  // 401 with a token = the session is over (expired, or the demo reset deleted the user).
  // 401 without a token is just a wrong password on the login form.
  if (response.status === 401 && token) {
    tokenStorage.clear()
    onUnauthorized()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response
}

async function json<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await send(method, path, body)
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => json<T>('GET', path),
  post: <T>(path: string, body?: unknown) => json<T>('POST', path, body),
  patch: <T>(path: string, body: unknown) => json<T>('PATCH', path, body),
  /** Binary responses (attachment downloads). */
  blob: async (path: string): Promise<Blob> => (await send('GET', path)).blob(),
}
