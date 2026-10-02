import type { AuthResponse } from './types'

// Typed by hand in the Render dashboard: drop pasted spaces and the trailing slash, if any
// ("https://api.example.com/" + "/api" would be a double slash).
const API_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').trim().replace(/\/+$/, '')

// Older versions kept the token in localStorage (readable by any script on the page): drop it.
try {
  localStorage.removeItem('ticketflow.token')
} catch {
  // storage blocked (private mode): nothing was stored either
}

const FALLBACK_MESSAGES: Record<number, string> = {
  0: 'Não foi possível falar com o servidor. Tente de novo em instantes.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado.',
  413: 'O arquivo passa do limite de 5 MB.',
  429: 'Muitas tentativas. Tente de novo em instantes.',
}

/** An error answered by the API (RFC 9457 ProblemDetail), or status 0 when the server was unreachable. */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>
  /** Id that identifies this request in the server logs (X-Request-Id), when the server answered. */
  readonly traceId: string | undefined

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.traceId = traceId
  }
}

/**
 * The access token lives only in memory: it lasts 15 minutes and a page script cannot carry a long session
 * away. The session itself is the HttpOnly refresh cookie, which no script can read.
 */
let accessToken: string | null = null
export const tokenStorage = {
  get: (): string | null => accessToken,
  set: (token: string) => {
    accessToken = token
  },
  clear: () => {
    accessToken = null
  },
}

/**
 * Routes that read or write the refresh cookie. They are called on the site's own origin (Render forwards
 * /api to the API) so the cookie belongs to the site; everything else goes straight to the API.
 */
const COOKIE_PATHS = ['/auth/session', '/auth/refresh', '/auth/logout']

// Login and sign-up never carry a token: Spring rejects an invalid Bearer header even on public
// routes, so a token left over from yesterday (demo reset) would make the login itself fail.
const PUBLIC_PATHS = ['/auth/login', '/auth/register', ...COOKIE_PATHS]

function urlFor(path: string): string {
  return COOKIE_PATHS.includes(path) ? `/api${path}` : `${API_URL}/api${path}`
}

let onUnauthorized: () => void = () => {}

/** The auth layer registers here what to do when the session dies (clear state, go to login). */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

let refreshing: Promise<AuthResponse | null> | null = null

/**
 * Gets a new access token from the refresh cookie. Requests that fail together share one renewal, and tabs
 * take turns (Web Locks): two renewals with the same cookie would look like a stolen token to the API.
 * Null means there is no session (no cookie, expired, or the server could not be reached).
 */
export function refreshSession(): Promise<AuthResponse | null> {
  refreshing ??= withRefreshLock(renew).finally(() => {
    refreshing = null
  })
  return refreshing
}

function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  return 'locks' in navigator ? navigator.locks.request('ticketflow-refresh', task) : task()
}

async function renew(): Promise<AuthResponse | null> {
  let response: Response
  try {
    response = await fetch(urlFor('/auth/refresh'), { method: 'POST' })
  } catch {
    return null
  }
  if (!response.ok) {
    tokenStorage.clear()
    return null
  }
  const session = (await response.json()) as AuthResponse
  tokenStorage.set(session.token)
  return session
}

async function toApiError(response: Response): Promise<ApiError> {
  let detail: string | undefined
  let fieldErrors: Record<string, string> = {}
  let traceId = response.headers.get('X-Request-Id') ?? undefined
  try {
    const problem = (await response.json()) as {
      detail?: string
      errors?: Record<string, string>
      traceId?: string
    }
    detail = problem.detail
    fieldErrors = problem.errors ?? {}
    traceId = problem.traceId ?? traceId
  } catch {
    // Not JSON (e.g. a proxy error page): fall back to a generic message.
  }
  const message =
    response.status === 413
      ? FALLBACK_MESSAGES[413]
      : (detail ?? FALLBACK_MESSAGES[response.status] ?? 'Algo deu errado. Tente de novo.')
  // Only server failures show the code: the user cannot fix them, so they report it to support.
  const shown = response.status >= 500 && traceId ? `${message} (código ${traceId})` : message
  return new ApiError(response.status, shown, fieldErrors, traceId)
}

async function send(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
  retried = false,
): Promise<Response> {
  const token = PUBLIC_PATHS.includes(path) ? null : tokenStorage.get()
  const headers = new Headers(extraHeaders)
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
    response = await fetch(urlFor(path), { method, headers, body: payload })
  } catch {
    throw new ApiError(0, FALLBACK_MESSAGES[0])
  }

  // 401 with a token: the 15-minute access token expired (or the demo reset deleted the user). Renew it once
  // from the refresh cookie and repeat the request; if that fails too, the session is over.
  // 401 without a token is just a wrong password on the login form.
  if (response.status === 401 && token) {
    if (!retried && (await refreshSession()) !== null) {
      return send(method, path, body, extraHeaders, true)
    }
    tokenStorage.clear()
    onUnauthorized()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response
}

async function json<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const response = await send(method, path, body, headers)
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** A downloaded file plus what the export endpoint says about it. */
export interface DownloadedFile {
  blob: Blob
  /** From Content-Disposition, when the server sent one. */
  filename: string | undefined
  /** More rows matched than the file holds (X-Export-Truncated). */
  truncated: boolean
}

export const api = {
  get: <T>(path: string) => json<T>('GET', path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => json<T>('POST', path, body, headers),
  patch: <T>(path: string, body: unknown, headers?: Record<string, string>) => json<T>('PATCH', path, body, headers),
  /** Binary responses (attachment downloads). */
  blob: async (path: string): Promise<Blob> => (await send('GET', path)).blob(),
  /** Generated files (ticket export): the bytes plus the file name and the truncation flag. */
  file: async (path: string): Promise<DownloadedFile> => {
    const response = await send('GET', path)
    const disposition = response.headers.get('Content-Disposition') ?? ''
    return {
      blob: await response.blob(),
      filename: /filename="?([^";]+)"?/.exec(disposition)?.[1],
      truncated: response.headers.get('X-Export-Truncated') === 'true',
    }
  },
}
