import { describe, expect, it } from 'vitest'
import type { HistoryEntry } from '../api/types'
import { describeEvent } from './history'

function entry(changes: Partial<HistoryEntry>): HistoryEntry {
  return {
    id: 1,
    eventType: 'CREATED',
    field: null,
    oldValue: null,
    newValue: null,
    actor: { id: 1, name: 'Ana' },
    occurredAt: '2026-09-29T10:00:00Z',
    ...changes,
  }
}

describe('describeEvent', () => {
  it('describes events without values', () => {
    expect(describeEvent(entry({ eventType: 'CREATED' }))).toBe('abriu o chamado')
    expect(describeEvent(entry({ eventType: 'COMMENT_ADDED' }))).toBe('comentou')
  })

  it('translates status and priority names from the API', () => {
    expect(
      describeEvent(entry({ eventType: 'STATUS_CHANGED', field: 'status', oldValue: 'OPEN', newValue: 'IN_PROGRESS' })),
    ).toBe('mudou o status de Aberto para Em atendimento')
    expect(
      describeEvent(entry({ eventType: 'PRIORITY_CHANGED', field: 'priority', oldValue: 'LOW', newValue: 'CRITICAL' })),
    ).toBe('mudou a prioridade de Baixa para Crítica')
  })

  it('tells a first assignment apart from a reassignment', () => {
    expect(describeEvent(entry({ eventType: 'ASSIGNED', field: 'assignee', newValue: 'Bruno' }))).toBe(
      'atribuiu a Bruno',
    )
    expect(
      describeEvent(entry({ eventType: 'ASSIGNED', field: 'assignee', oldValue: 'Bruno', newValue: 'Carla' })),
    ).toBe('reatribuiu de Bruno para Carla')
  })

  it('shows category names and attachment file names as they are', () => {
    expect(
      describeEvent(entry({ eventType: 'CATEGORY_CHANGED', field: 'category', oldValue: 'Hardware', newValue: 'Acesso' })),
    ).toBe('mudou a categoria de Hardware para Acesso')
    expect(describeEvent(entry({ eventType: 'ATTACHMENT_ADDED', field: 'attachment', newValue: 'log.txt' }))).toBe(
      'anexou log.txt',
    )
  })
})
