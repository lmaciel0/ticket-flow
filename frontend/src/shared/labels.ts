import type { Priority, Role, SlaIndicator, TicketStatus } from '../api/types'

// Record<Union, string> forces one entry per value: adding a status to the union
// without a label here is a compile error.

export const STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Aberto',
  IN_PROGRESS: 'Em atendimento',
  WAITING_REQUESTER: 'Aguardando solicitante',
  RESOLVED: 'Resolvido',
  CLOSED: 'Fechado',
}

export const STATUS_COLORS: Record<TicketStatus, string> = {
  OPEN: 'blue',
  IN_PROGRESS: 'indigo',
  WAITING_REQUESTER: 'yellow',
  RESOLVED: 'teal',
  CLOSED: 'gray',
}

export const PRIORITY_LABELS: Record<Priority, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
  CRITICAL: 'Crítica',
}

export const PRIORITY_COLORS: Record<Priority, string> = {
  LOW: 'gray',
  MEDIUM: 'blue',
  HIGH: 'orange',
  CRITICAL: 'red',
}

export const SLA_LABELS: Record<SlaIndicator, string> = {
  ON_TRACK: 'No prazo',
  AT_RISK: 'Em risco',
  OVERDUE: 'Vencido',
  PAUSED: 'Pausado',
  MET: 'Cumprido',
  BREACHED: 'Violado',
}

export const SLA_COLORS: Record<SlaIndicator, string> = {
  ON_TRACK: 'green',
  AT_RISK: 'yellow',
  OVERDUE: 'red',
  PAUSED: 'gray',
  MET: 'teal',
  BREACHED: 'red',
}

export const ROLE_LABELS: Record<Role, string> = {
  REQUESTER: 'Solicitante',
  AGENT: 'Atendente',
  MANAGER: 'Gestor',
}

/** Same order as the backend enums, for selects and charts. */
export const STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER', 'RESOLVED', 'CLOSED']
export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const ROLES: Role[] = ['REQUESTER', 'AGENT', 'MANAGER']

/** Statuses of tickets still being worked on: the list shows only these by default. */
export const ACTIVE_STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER']
