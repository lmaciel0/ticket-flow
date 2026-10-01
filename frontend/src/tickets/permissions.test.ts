import { describe, expect, it } from 'vitest'
import type { Ticket, TicketStatus, User } from '../api/types'
import { ticketPermissions, transitionLabel } from './permissions'

const requester: User = { id: 1, name: 'Sol', email: 's@x.com', role: 'REQUESTER', active: true, demo: false }
const agent: User = { id: 2, name: 'Ana', email: 'a@x.com', role: 'AGENT', active: true, demo: false }
const otherAgent: User = { id: 3, name: 'Beto', email: 'b@x.com', role: 'AGENT', active: true, demo: false }
const manager: User = { id: 4, name: 'Gil', email: 'g@x.com', role: 'MANAGER', active: true, demo: false }

function ticket(status: TicketStatus, assigneeId: number | null = agent.id): Ticket {
  return {
    id: 10,
    title: 'Sem VPN',
    description: 'Não conecta',
    priority: 'HIGH',
    status,
    category: { id: 1, name: 'Acesso' },
    requester: { id: requester.id, name: requester.name },
    assignee: assigneeId === null ? null : { id: assigneeId, name: 'Ana' },
    createdAt: '2026-09-29T10:00:00Z',
    dueAt: '2026-09-29T18:00:00Z',
    resolvedAt: null,
    slaBreached: null,
    sla: 'ON_TRACK',
    firstResponseDueAt: null,
    firstRespondedAt: null,
    firstResponse: null,
    version: 0,
  }
}

describe('ticketPermissions (mirrors the backend rules, which have the final word)', () => {
  it('lets an agent take an open ticket, but not change its status by hand', () => {
    const permissions = ticketPermissions(ticket('OPEN', null), agent)

    expect(permissions.canTake).toBe(true)
    expect(permissions.transitions).toEqual([])
  })

  it('gives status and field changes to the assignee only, not to other agents', () => {
    expect(ticketPermissions(ticket('IN_PROGRESS'), agent)).toMatchObject({
      canEdit: true,
      transitions: ['WAITING_REQUESTER', 'RESOLVED'],
    })
    expect(ticketPermissions(ticket('IN_PROGRESS'), otherAgent)).toMatchObject({
      canTake: false,
      canEdit: false,
      transitions: [],
    })
  })

  it('lets the requester close or reopen a resolved ticket, and nothing else', () => {
    expect(ticketPermissions(ticket('RESOLVED'), requester)).toEqual({
      canTake: false,
      canAssign: false,
      canEdit: false,
      canComment: true,
      transitions: ['CLOSED', 'IN_PROGRESS'],
    })
    expect(ticketPermissions(ticket('RESOLVED'), agent).transitions).toEqual([])
  })

  it('lets the manager assign any unfinished ticket and change any status', () => {
    expect(ticketPermissions(ticket('WAITING_REQUESTER'), manager)).toMatchObject({
      canAssign: true,
      canEdit: true,
      transitions: ['IN_PROGRESS'],
    })
    expect(ticketPermissions(ticket('RESOLVED'), manager).canAssign).toBe(false)
  })

  it('freezes a closed ticket for everyone', () => {
    expect(ticketPermissions(ticket('CLOSED'), manager)).toEqual({
      canTake: false,
      canAssign: false,
      canEdit: false,
      canComment: false,
      transitions: [],
    })
  })
})

describe('transitionLabel', () => {
  it('names the action from the user point of view', () => {
    expect(transitionLabel('IN_PROGRESS', 'WAITING_REQUESTER')).toBe('Pedir informação')
    expect(transitionLabel('IN_PROGRESS', 'RESOLVED')).toBe('Marcar como resolvido')
    expect(transitionLabel('WAITING_REQUESTER', 'IN_PROGRESS')).toBe('Retomar atendimento')
    expect(transitionLabel('RESOLVED', 'IN_PROGRESS')).toBe('Reabrir')
    expect(transitionLabel('RESOLVED', 'CLOSED')).toBe('Confirmar e fechar')
  })
})
