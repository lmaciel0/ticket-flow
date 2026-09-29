import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { mockApi, renderRoutes, sentBody } from '../test/render'
import { GuestOnly } from './guards'
import { LoginPage } from './LoginPage'

const routes = [
  { element: <GuestOnly />, children: [{ path: '/login', element: <LoginPage /> }] },
  { path: '/tickets', element: <p>Lista de chamados</p> },
]

const manager = { id: 5, name: 'Carla', email: 'gestor@ticketflow.demo', role: 'MANAGER', active: true, demo: true }

afterEach(() => vi.unstubAllGlobals())

describe('LoginPage', () => {
  it('logs in with a demo account in one click', async () => {
    const fetchMock = mockApi({ 'POST /auth/login': [200, { token: 'jwt-token', user: manager }] })
    const { user } = renderRoutes(routes, '/login')

    await user.click(screen.getByRole('button', { name: 'Gestor' }))

    expect(await screen.findByText('Lista de chamados')).toBeInTheDocument()
    expect(sentBody(fetchMock, 'POST /auth/login')).toEqual({ email: 'gestor@ticketflow.demo', password: 'demo1234' })
    expect(tokenStorage.get()).toBe('jwt-token')
  })

  it('warns that the demo data is reset every day', () => {
    renderRoutes(routes, '/login')

    expect(screen.getByText(/reiniciados diariamente/)).toBeInTheDocument()
  })

  it('explains the wake-up wait after any idle time, not only on the first access of the day', () => {
    renderRoutes(routes, '/login')

    const hint = screen.getByText(/pode levar cerca de um minuto/)
    expect(hint).toHaveTextContent(/estava dormindo/)
    expect(hint).not.toHaveTextContent(/do dia/)
  })

  it('shows the API message on a wrong password and stays on the page', async () => {
    mockApi({ 'POST /auth/login': [401, { status: 401, detail: 'E-mail ou senha inválidos.' }] })
    const { user } = renderRoutes(routes, '/login')

    await user.type(screen.getByLabelText('E-mail'), 'ana@empresa.com')
    await user.type(screen.getByLabelText('Senha'), 'senha-errada')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByText('E-mail ou senha inválidos.')).toBeInTheDocument()
    expect(screen.queryByText('Lista de chamados')).not.toBeInTheDocument()
    expect(tokenStorage.get()).toBeNull()
  })
})
