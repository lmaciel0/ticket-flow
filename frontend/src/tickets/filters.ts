import type { Priority, SlaFilter, TicketStatus } from '../api/types'
import { ACTIVE_STATUSES, PRIORITIES, STATUSES } from '../shared/labels'

export type TicketSort = 'dueAt,asc' | 'createdAt,desc'

/** The list filters. They live in the URL, so reload, back/forward and shared links keep them. */
export interface TicketFilters {
  /** Empty means "every status". */
  status: TicketStatus[]
  priority: Priority | null
  categoryId: number | null
  sla: SlaFilter | null
  q: string
  mine: boolean
  sort: TicketSort
  /** 1-based, as shown on screen (the API counts from 0). */
  page: number
}

export const PAGE_SIZE = 20

const SLA_FILTERS: SlaFilter[] = ['OVERDUE', 'AT_RISK']
const SORTS: TicketSort[] = ['dueAt,asc', 'createdAt,desc']

export const DEFAULT_FILTERS: TicketFilters = {
  status: ACTIVE_STATUSES,
  priority: null,
  categoryId: null,
  sla: null,
  q: '',
  mine: false,
  sort: 'dueAt,asc',
  page: 1,
}

/** Narrows a free string from the URL to one of the allowed values, or null. */
function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | null {
  return allowed.find((option) => option === value) ?? null
}

function positiveInt(value: string | null): number | null {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : null
}

export function parseFilters(params: URLSearchParams): TicketFilters {
  const status = params.has('status')
    ? STATUSES.filter((status) => params.getAll('status').includes(status))
    : DEFAULT_FILTERS.status
  return {
    status,
    priority: oneOf(params.get('priority'), PRIORITIES),
    categoryId: positiveInt(params.get('categoryId')),
    sla: oneOf(params.get('sla'), SLA_FILTERS),
    q: params.get('q') ?? '',
    mine: params.get('mine') === 'true',
    sort: oneOf(params.get('sort'), SORTS) ?? DEFAULT_FILTERS.sort,
    page: positiveInt(params.get('page')) ?? 1,
  }
}

function sameStatuses(a: TicketStatus[], b: TicketStatus[]): boolean {
  return a.length === b.length && a.every((status) => b.includes(status))
}

/** The filters every query has, in the same order, for the URL and for the API. */
function appendFilters(params: URLSearchParams, filters: TicketFilters) {
  if (filters.priority) params.set('priority', filters.priority)
  if (filters.categoryId) params.set('categoryId', String(filters.categoryId))
  if (filters.sla) params.set('sla', filters.sla)
  if (filters.q.trim()) params.set('q', filters.q.trim())
  if (filters.mine) params.set('mine', 'true')
}

/** Only what differs from the defaults goes to the URL. */
export function toSearchParams(filters: TicketFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.status.length === 0) {
    params.set('status', '')
  } else if (!sameStatuses(filters.status, DEFAULT_FILTERS.status)) {
    filters.status.forEach((status) => params.append('status', status))
  }
  appendFilters(params, filters)
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set('sort', filters.sort)
  if (filters.page > 1) params.set('page', String(filters.page))
  return params
}

/** Query string for GET /api/tickets. */
export function toApiQuery(filters: TicketFilters): string {
  const params = new URLSearchParams()
  filters.status.forEach((status) => params.append('status', status))
  appendFilters(params, filters)
  params.set('sort', filters.sort)
  params.set('page', String(filters.page - 1))
  params.set('size', String(PAGE_SIZE))
  return params.toString()
}
