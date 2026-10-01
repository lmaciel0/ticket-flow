import { MantineProvider } from '@mantine/core'
import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { createMemoryRouter, type RouteObject, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import { createQueryClient } from '../api/queryClient'
import { AuthProvider } from '../auth/AuthProvider'

/** Renders a single component that only needs Mantine (no API, no router). env="test" turns off animations and portals. */
export function renderUi(ui: ReactNode) {
  return render(<MantineProvider env="test">{ui}</MantineProvider>)
}

/** Renders routes with the same providers as main.tsx, starting at `path`. */
export function renderRoutes(routes: RouteObject[], path: string) {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()
  render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>,
  )
  return { user, router, queryClient }
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Reply = [status: number, body: unknown]

function requestKey(input: RequestInfo | URL, init?: RequestInit): string {
  const path = new URL(String(input)).pathname.replace(/^\/api/, '')
  return `${init?.method ?? 'GET'} ${path}`
}

/**
 * Replaces fetch with a fake API that answers by "METHOD /path" (query string ignored),
 * e.g. { 'GET /categories': [200, [...]] }. Requests run in parallel, so matching by route
 * is safer than answering in call order.
 */
export function mockApi(replies: Record<string, Reply>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const reply = replies[requestKey(input, init)]
    if (!reply) {
      throw new Error(`Unexpected request: ${requestKey(input, init)}`)
    }
    return jsonResponse(...reply)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function sentRequest(fetchMock: ReturnType<typeof mockApi>, key: string): RequestInit | undefined {
  const call = fetchMock.mock.calls.find(([input, init]) => requestKey(input, init) === key)
  if (!call) {
    throw new Error(`No request to ${key}`)
  }
  return call[1]
}

/** The JSON body of the (first) request sent to "METHOD /path". */
export function sentBody(fetchMock: ReturnType<typeof mockApi>, key: string): unknown {
  return JSON.parse(sentRequest(fetchMock, key)?.body as string)
}

/** The headers of the (first) request sent to "METHOD /path". */
export function sentHeaders(fetchMock: ReturnType<typeof mockApi>, key: string): Headers {
  return new Headers(sentRequest(fetchMock, key)?.headers)
}
