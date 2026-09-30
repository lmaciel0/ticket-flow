import { describe, expect, it } from 'vitest'
import { DEFAULT_FILTERS, parseFilters, type TicketFilters, toApiQuery, toExportQuery, toSearchParams } from './filters'

describe('ticket list filters in the URL', () => {
  it('starts with the unfinished tickets, soonest deadline first, page 1', () => {
    expect(parseFilters(new URLSearchParams())).toEqual(DEFAULT_FILTERS)
    expect(DEFAULT_FILTERS.status).toEqual(['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER'])
  })

  it('keeps the URL clean when the filters are the defaults', () => {
    expect(toSearchParams(DEFAULT_FILTERS).toString()).toBe('')
  })

  it('survives a round trip through the URL', () => {
    const filters: TicketFilters = {
      status: ['RESOLVED'],
      priority: 'HIGH',
      categoryId: 3,
      sla: 'OVERDUE',
      q: 'impressora',
      mine: true,
      sort: 'createdAt,desc',
      page: 2,
    }

    expect(parseFilters(toSearchParams(filters))).toEqual(filters)
  })

  it('remembers "all statuses" (an empty selection) as different from the default', () => {
    const params = toSearchParams({ ...DEFAULT_FILTERS, status: [] })

    expect(params.toString()).toBe('status=')
    expect(parseFilters(params).status).toEqual([])
  })

  it('ignores garbage typed or pasted into the URL instead of breaking the page', () => {
    const params = new URLSearchParams(
      'status=FOO&status=OPEN&priority=URGENT&categoryId=abc&sla=LATE&sort=title&page=-3',
    )

    expect(parseFilters(params)).toEqual({ ...DEFAULT_FILTERS, status: ['OPEN'] })
  })
})

describe('toApiQuery', () => {
  it('repeats status, trims the search, sends a 0-based page and a fixed page size', () => {
    const query = toApiQuery({ ...DEFAULT_FILTERS, page: 3, q: '  vpn  ' })

    expect(query).toBe(
      'status=OPEN&status=IN_PROGRESS&status=WAITING_REQUESTER&q=vpn&sort=dueAt%2Casc&page=2&size=20',
    )
  })

  it('sends no status at all when every status is wanted', () => {
    expect(toApiQuery({ ...DEFAULT_FILTERS, status: [] })).toBe('sort=dueAt%2Casc&page=0&size=20')
  })

  it('exports with the same filters and sort as the list, but without paging', () => {
    const filters: TicketFilters = { ...DEFAULT_FILTERS, priority: 'HIGH', q: ' rede ', page: 3 }

    const query = new URLSearchParams(toExportQuery(filters, 'XLSX'))

    expect(query.get('format')).toBe('XLSX')
    expect(query.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER'])
    expect(query.get('priority')).toBe('HIGH')
    expect(query.get('q')).toBe('rede')
    expect(query.get('sort')).toBe('dueAt,asc')
    expect(query.has('page')).toBe(false)
    expect(query.has('size')).toBe(false)
  })
})
