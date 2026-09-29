import type { Ticket, TicketStatus, User } from '../api/types'
import { ACTIVE_STATUSES } from '../shared/labels'

export interface TicketPermissions {
  /** An agent takes an open ticket for themselves. */
  canTake: boolean
  /** A manager picks (or changes) the assignee. */
  canAssign: boolean
  /** Change priority and category. */
  canEdit: boolean
  /** Comment and attach files. */
  canComment: boolean
  /** Statuses this user can move the ticket to with a button. */
  transitions: TicketStatus[]
}

// OPEN -> IN_PROGRESS is not here: it only happens through assignment.
const NEXT_STATUSES: Record<TicketStatus, TicketStatus[]> = {
  OPEN: [],
  IN_PROGRESS: ['WAITING_REQUESTER', 'RESOLVED'],
  WAITING_REQUESTER: ['IN_PROGRESS'],
  RESOLVED: ['CLOSED', 'IN_PROGRESS'],
  CLOSED: [],
}

/**
 * What the screen offers to this user. It only hides buttons: the backend checks
 * every request again and answers 403/409 when the rules do not allow it.
 */
export function ticketPermissions(ticket: Ticket, user: User): TicketPermissions {
  const isManager = user.role === 'MANAGER'
  const isAssignee = ticket.assignee?.id === user.id
  const isRequester = ticket.requester.id === user.id
  // A resolved ticket is confirmed or reopened by whoever asked for it; the rest, by whoever works on it.
  const canMove = isManager || (ticket.status === 'RESOLVED' ? isRequester : isAssignee)

  return {
    canTake: user.role === 'AGENT' && ticket.status === 'OPEN',
    canAssign: isManager && ACTIVE_STATUSES.includes(ticket.status),
    canEdit: (isManager || isAssignee) && ticket.status !== 'CLOSED',
    canComment: ticket.status !== 'CLOSED',
    transitions: canMove ? NEXT_STATUSES[ticket.status] : [],
  }
}

export function transitionLabel(from: TicketStatus, to: TicketStatus): string {
  switch (to) {
    case 'WAITING_REQUESTER':
      return 'Pedir informação'
    case 'RESOLVED':
      return 'Marcar como resolvido'
    case 'CLOSED':
      return 'Confirmar e fechar'
    case 'IN_PROGRESS':
      return from === 'RESOLVED' ? 'Reabrir' : 'Retomar atendimento'
    case 'OPEN':
      return 'Abrir'
  }
}
