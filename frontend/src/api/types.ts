// TypeScript mirrors of the backend DTOs (see the Swagger UI at /swagger-ui).
// Enums are string unions: the JSON carries the enum name ("OPEN"), and a union
// gives autocomplete and compile errors without generating any JavaScript.

export type Role = 'REQUESTER' | 'AGENT' | 'MANAGER'

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_REQUESTER' | 'RESOLVED' | 'CLOSED'

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export type SlaIndicator = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE' | 'PAUSED' | 'MET' | 'BREACHED'

export type SlaFilter = 'OVERDUE' | 'AT_RISK'

export type HistoryEventType =
  | 'CREATED'
  | 'STATUS_CHANGED'
  | 'ASSIGNED'
  | 'PRIORITY_CHANGED'
  | 'CATEGORY_CHANGED'
  | 'COMMENT_ADDED'
  | 'ATTACHMENT_ADDED'

export interface User {
  id: number
  name: string
  email: string
  role: Role
  active: boolean
  demo: boolean
}

export interface UserSummary {
  id: number
  name: string
}

export interface AuthResponse {
  token: string
  user: User
}

export interface Category {
  id: number
  name: string
}

/** Instants arrive as ISO-8601 strings in UTC ("2026-09-29T13:00:00Z"). */
export interface Ticket {
  id: number
  title: string
  description: string
  priority: Priority
  status: TicketStatus
  category: Category
  requester: UserSummary
  assignee: UserSummary | null
  createdAt: string
  dueAt: string
  resolvedAt: string | null
  slaBreached: boolean | null
  sla: SlaIndicator
  version: number
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Named TicketComment because the DOM already has a global type called Comment. */
export interface TicketComment {
  id: number
  text: string
  author: UserSummary
  createdAt: string
}

export interface Attachment {
  id: number
  filename: string
  contentType: string
  size: number
  uploadedBy: UserSummary
  createdAt: string
}

export interface HistoryEntry {
  id: number
  eventType: HistoryEventType
  field: string | null
  oldValue: string | null
  newValue: string | null
  actor: UserSummary
  occurredAt: string
}

export interface Dashboard {
  from: string
  to: string
  ticketsByStatus: Record<TicketStatus, number>
  overdueNow: number
  resolvedInPeriod: number
  slaMetPercentage: number | null
  averageResolutionHours: number | null
  openedByCategory: { category: string; count: number }[]
  agents: { id: number; name: string; activeAssigned: number; resolvedInPeriod: number }[]
  daily: { date: string; opened: number; resolved: number }[]
}
