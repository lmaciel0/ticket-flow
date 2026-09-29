import type { HistoryEntry, Priority, TicketStatus } from '../api/types'
import { PRIORITY_LABELS, STATUS_LABELS } from '../shared/labels'

/** The history stores enum names ("IN_PROGRESS"); the screen shows the labels. */
function statusLabel(value: string | null): string {
  return STATUS_LABELS[value as TicketStatus] ?? value ?? '—'
}

function priorityLabel(value: string | null): string {
  return PRIORITY_LABELS[value as Priority] ?? value ?? '—'
}

/** One line of the timeline, completing "<actor name> ..." */
export function describeEvent(entry: HistoryEntry): string {
  const { oldValue, newValue } = entry
  switch (entry.eventType) {
    case 'CREATED':
      return 'abriu o chamado'
    case 'STATUS_CHANGED':
      return `mudou o status de ${statusLabel(oldValue)} para ${statusLabel(newValue)}`
    case 'ASSIGNED':
      return oldValue === null ? `atribuiu a ${newValue}` : `reatribuiu de ${oldValue} para ${newValue}`
    case 'PRIORITY_CHANGED':
      return `mudou a prioridade de ${priorityLabel(oldValue)} para ${priorityLabel(newValue)}`
    case 'CATEGORY_CHANGED':
      return `mudou a categoria de ${oldValue} para ${newValue}`
    case 'COMMENT_ADDED':
      return 'comentou'
    case 'ATTACHMENT_ADDED':
      return `anexou ${newValue}`
  }
}
