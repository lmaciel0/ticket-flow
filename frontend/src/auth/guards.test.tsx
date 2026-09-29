import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { renderRoutes } from '../test/render'
import { GuestOnly, RequireAuth } from './guards'

const routes = [
  { element: <RequireAuth />, children: [{ path: '/tickets', element: <p>Lista de chamados</p> }] },
  { element: <GuestOnly />, children: [{ path: '/login', element: <p>Tela de login</p> }] },
]

/** The free host sleeps after 15 minutes without traffic: the first request can take a minute. */
function serverStillWakingUp() {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(() => new Promise<Response>(() => {})))
}

afterEach(() => vi.unstubAllGlobals())

describe('guards while the saved session is being checked', () => {
  it('explain the wait on a protected page instead of showing a bare spinner', () => {
    tokenStorage.set('jwt')
    serverStillWakingUp()

    renderRoutes(routes, '/tickets')

    expect(screen.getByText(/pode levar cerca de um minuto/)).toBeInTheDocument()
  })

  it('explain the wait on the login page too, instead of a blank screen', () => {
    tokenStorage.set('jwt')
    serverStillWakingUp()

    renderRoutes(routes, '/login')

    expect(screen.getByText(/pode levar cerca de um minuto/)).toBeInTheDocument()
  })
})
