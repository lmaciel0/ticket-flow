import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockApi, renderRoutes } from '../test/render'
import { ExportMenu } from './ExportMenu'
import { DEFAULT_FILTERS } from './filters'

afterEach(() => vi.unstubAllGlobals())

describe('ExportMenu', () => {
  it('downloads the chosen format with the filters on screen', async () => {
    // jsdom has no object URLs; only these two methods are faked, `new URL()` stays real.
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const fetchMock = mockApi({ 'GET /tickets/export': [200, {}] })
    const { user } = renderRoutes(
      [{ path: '/', element: <ExportMenu filters={{ ...DEFAULT_FILTERS, priority: 'HIGH' }} /> }],
      '/',
    )

    await user.click(screen.getByRole('button', { name: 'Exportar' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Excel (.xlsx)' }))

    await waitFor(() => expect(click).toHaveBeenCalled())
    const requested = new URL(String(fetchMock.mock.calls[0][0]))
    expect(requested.pathname).toBe('/api/tickets/export')
    expect(requested.searchParams.get('format')).toBe('XLSX')
    expect(requested.searchParams.get('priority')).toBe('HIGH')
    click.mockRestore()
  })
})
