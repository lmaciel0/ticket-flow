import { Badge, Group, Paper, Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'
import type { Ticket } from '../api/types'
import { formatDateTime } from '../shared/format'
import { PRIORITY_COLORS, PRIORITY_LABELS, STATUS_COLORS, STATUS_LABELS } from '../shared/labels'
import { SlaBadge } from './SlaBadge'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group justify="space-between" align="flex-start" wrap="nowrap">
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <div style={{ textAlign: 'right' }}>{children}</div>
    </Group>
  )
}

export function TicketSummary({ ticket }: { ticket: Ticket }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Stack gap="xs">
        <Field label="Status">
          <Badge color={STATUS_COLORS[ticket.status]} variant="light">
            {STATUS_LABELS[ticket.status]}
          </Badge>
        </Field>
        <Field label="Prioridade">
          <Badge color={PRIORITY_COLORS[ticket.priority]} variant="outline">
            {PRIORITY_LABELS[ticket.priority]}
          </Badge>
        </Field>
        <Field label="SLA">
          <SlaBadge sla={ticket.sla} dueAt={ticket.dueAt} />
        </Field>
        <Field label="Prazo">
          <Text size="sm">{formatDateTime(ticket.dueAt)}</Text>
        </Field>
        <Field label="Categoria">
          <Text size="sm">{ticket.category.name}</Text>
        </Field>
        <Field label="Solicitante">
          <Text size="sm">{ticket.requester.name}</Text>
        </Field>
        <Field label="Responsável">
          <Text size="sm">{ticket.assignee?.name ?? '—'}</Text>
        </Field>
        <Field label="Aberto em">
          <Text size="sm">{formatDateTime(ticket.createdAt)}</Text>
        </Field>
        {ticket.resolvedAt && (
          <Field label="Resolvido em">
            <Text size="sm">{formatDateTime(ticket.resolvedAt)}</Text>
          </Field>
        )}
      </Stack>
    </Paper>
  )
}
