import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type {
  Attachment,
  Category,
  HistoryEntry,
  Page,
  Priority,
  Ticket,
  TicketComment,
  TicketStatus,
  UserSummary,
} from '../api/types'
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

export function useTicket(id: number) {
  return useQuery({ queryKey: ticketKeys.detail(id), queryFn: () => api.get<Ticket>(`/tickets/${id}`) })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}

export function useAssignableUsers(enabled: boolean) {
  return useQuery({
    queryKey: ['users', 'assignable'],
    queryFn: () => api.get<UserSummary[]>('/users/assignable'),
    enabled,
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

/**
 * Every change to a ticket answers with the updated ticket (and its new version). We put it
 * straight into the cache, so the next action on the same screen already sends the new version.
 * On 409 (someone changed it first, or a rule refused it) we reload the ticket.
 */
function useTicketChange<Variables>(ticketId: number, request: (variables: Variables) => Promise<Ticket>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (ticket) => {
      queryClient.setQueryData(ticketKeys.detail(ticketId), ticket)
      void queryClient.invalidateQueries({ queryKey: ticketKeys.history(ticketId) })
      void queryClient.invalidateQueries({ queryKey: ticketKeys.lists() })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      }
    },
  })
}

export function useChangeStatus(ticketId: number) {
  return useTicketChange(ticketId, (body: { status: TicketStatus; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/status`, body),
  )
}

export function useAssignTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { assigneeId: number; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/assign`, body),
  )
}

export function useUpdateTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { priority?: Priority; categoryId?: number; version: number }) =>
    api.patch<Ticket>(`/tickets/${ticketId}`, body),
  )
}

export function useComments(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.comments(ticketId),
    queryFn: () => api.get<TicketComment[]>(`/tickets/${ticketId}/comments`),
  })
}

/**
 * A comment returns only the comment, but it may have changed the ticket: when the requester
 * answers a ticket waiting for them, the backend moves it back to "in progress" (new status and
 * new version). So we reload the whole ticket: detail, comments and history.
 */
export function useAddComment(ticketId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (comment: { text: string; internal: boolean }) =>
      api.post<TicketComment>(`/tickets/${ticketId}/comments`, comment),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      void queryClient.invalidateQueries({ queryKey: ticketKeys.lists() })
    },
  })
}

export function useAttachments(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.attachments(ticketId),
    queryFn: () => api.get<Attachment[]>(`/tickets/${ticketId}/attachments`),
  })
}

export function useUploadAttachment(ticketId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api.post<Attachment>(`/tickets/${ticketId}/attachments`, form)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) }),
  })
}

export function useHistory(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.history(ticketId),
    queryFn: () => api.get<HistoryEntry[]>(`/tickets/${ticketId}/history`),
  })
}
