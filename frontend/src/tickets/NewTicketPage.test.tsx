import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockApi, renderRoutes, sentBody } from '../test/render'
import { NewTicketPage } from './NewTicketPage'

const categories = [
  { id: 1, name: 'Acesso' },
  { id: 2, name: 'Hardware' },
]

const routes = [
  { path: '/tickets/new', element: <NewTicketPage /> },
  { path: '/tickets/:id', element: <p>Detalhe do chamado</p> },
]

afterEach(() => vi.unstubAllGlobals())

describe('NewTicketPage', () => {
  it('opens the ticket with the chosen category and goes to its page', async () => {
    const fetchMock = mockApi({
      'GET /categories': [200, categories],
      'POST /tickets': [201, { id: 42 }],
    })
    const { user, router } = renderRoutes(routes, '/tickets/new')

    await user.type(screen.getByLabelText('Título'), '  Sem acesso ao e-mail ')
    await user.type(screen.getByLabelText('Descrição'), 'Senha expirou')
    await user.click(screen.getByRole('combobox', { name: 'Categoria' }))
    await user.click(await screen.findByRole('option', { name: 'Hardware' }))
    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Detalhe do chamado')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/tickets/42')
    expect(sentBody(fetchMock, 'POST /tickets')).toEqual({
      title: 'Sem acesso ao e-mail',
      description: 'Senha expirou',
      priority: 'MEDIUM',
      categoryId: 2,
    })
  })

  it('does not call the API while required fields are empty', async () => {
    const fetchMock = mockApi({ 'GET /categories': [200, categories] })
    const { user } = renderRoutes(routes, '/tickets/new')

    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Escolha a categoria.')).toBeInTheDocument()
    // the session restore on mount is not what this test is about
    const requested = fetchMock.mock.calls.map(([input]) => String(input)).filter((url) => !url.endsWith('/auth/refresh'))
    expect(requested).toEqual(['http://localhost:8080/api/categories'])
  })

  it('shows the messages the API sends for each field', async () => {
    mockApi({
      'GET /categories': [200, categories],
      'POST /tickets': [400, { status: 400, detail: 'Dados inválidos.', errors: { title: 'Título muito longo.' } }],
    })
    const { user } = renderRoutes(routes, '/tickets/new')

    await user.type(screen.getByLabelText('Título'), 'Qualquer')
    await user.type(screen.getByLabelText('Descrição'), 'Qualquer')
    await user.click(screen.getByRole('combobox', { name: 'Categoria' }))
    await user.click(await screen.findByRole('option', { name: 'Acesso' }))
    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Título muito longo.')).toBeInTheDocument()
    expect(screen.getByText('Dados inválidos.')).toBeInTheDocument()
  })
})
