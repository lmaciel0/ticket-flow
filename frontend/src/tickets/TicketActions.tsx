import { Button, Paper, Select, Stack, Text, Title } from '@mantine/core'
import type { Priority, Ticket } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { PRIORITIES, PRIORITY_LABELS } from '../shared/labels'
import { useAssignableUsers, useAssignTicket, useCategories, useChangeStatus, useUpdateTicket } from './api'
import { type TicketPermissions, transitionLabel } from './permissions'

const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))

interface TicketActionsProps {
  ticket: Ticket
  permissions: TicketPermissions
}

/** Buttons and selects this user may use on this ticket. Every request sends the version on screen. */
export function TicketActions({ ticket, permissions }: TicketActionsProps) {
  const user = useCurrentUser()
  const assign = useAssignTicket(ticket.id)
  const changeStatus = useChangeStatus(ticket.id)
  const update = useUpdateTicket(ticket.id)
  const categories = useCategories()
  const assignable = useAssignableUsers(permissions.canAssign)
  const busy = assign.isPending || changeStatus.isPending || update.isPending
  const { version } = ticket

  const nothingToDo =
    !permissions.canTake && !permissions.canAssign && !permissions.canEdit && permissions.transitions.length === 0

  return (
    <Paper withBorder p="md" radius="md">
      <Stack gap="sm">
        <Title order={5}>Ações</Title>
        {nothingToDo && (
          <Text size="sm" c="dimmed">
            Nenhuma ação disponível para você neste chamado.
          </Text>
        )}

        {permissions.canTake && (
          <Button loading={assign.isPending} onClick={() => assign.mutate({ assigneeId: user.id, version })}>
            Assumir chamado
          </Button>
        )}

        {permissions.transitions.map((status) => (
          <Button
            key={status}
            variant={status === 'IN_PROGRESS' && ticket.status === 'RESOLVED' ? 'default' : 'filled'}
            loading={changeStatus.isPending && changeStatus.variables?.status === status}
            disabled={busy}
            onClick={() => changeStatus.mutate({ status, version })}
          >
            {transitionLabel(ticket.status, status)}
          </Button>
        ))}

        {permissions.canAssign && (
          <Select<number>
            label="Responsável"
            placeholder="Escolha quem atende"
            data={(assignable.data ?? []).map((person) => ({ value: person.id, label: person.name }))}
            value={ticket.assignee?.id ?? null}
            onChange={(assigneeId) => assigneeId !== null && assign.mutate({ assigneeId, version })}
            allowDeselect={false}
            disabled={busy}
          />
        )}

        {permissions.canEdit && (
          <>
            <Select<Priority>
              label="Prioridade"
              data={PRIORITY_OPTIONS}
              value={ticket.priority}
              onChange={(priority) => priority && update.mutate({ priority, version })}
              allowDeselect={false}
              disabled={busy}
            />
            <Select<number>
              label="Categoria"
              data={(categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))}
              value={ticket.category.id}
              onChange={(categoryId) => categoryId !== null && update.mutate({ categoryId, version })}
              allowDeselect={false}
              disabled={busy}
            />
          </>
        )}
      </Stack>
    </Paper>
  )
}
