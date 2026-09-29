import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Category, Page, Priority, Ticket } from '../api/types'
import { type TicketFilters, toApiQuery } from './filters'

/**
 * Query keys in one place. They are hierarchical: invalidating ticketKeys.detail(7)
 * also invalidates its comments, attachments and history, which start with the same prefix.
 */
export const ticketKeys = {
  lists: () => ['tickets', 'list'] as const,
  list: (query: string) => ['tickets', 'list', query] as const,
  detail: (id: number) => ['tickets', id] as const,
  comments: (id: number) => ['tickets', id, 'comments'] as const,
  attachments: (id: number) => ['tickets', id, 'attachments'] as const,
  history: (id: number) => ['tickets', id, 'history'] as const,
}

export function useTickets(filters: TicketFilters) {
  const query = toApiQuery(filters)
  return useQuery({
    queryKey: ticketKeys.list(query),
    queryFn: () => api.get<Page<Ticket>>(`/tickets?${query}`),
    placeholderData: keepPreviousData, // keeps the old page on screen while the next one loads
  })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}

export interface NewTicket {
  title: string
  description: string
  priority: Priority
  categoryId: number
}

export function useCreateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticket: NewTicket) => api.post<Ticket>('/tickets', ticket),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
    meta: { inlineError: true },
  })
}
