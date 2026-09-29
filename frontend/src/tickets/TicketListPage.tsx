import { Alert, Anchor, Badge, Button, Center, Group, Loader, Pagination, Stack, Table, Text, Title } from '@mantine/core'
import { Link, useSearchParams } from 'react-router'
import { useCurrentUser } from '../auth/authContext'
import { PRIORITY_COLORS, PRIORITY_LABELS, STATUS_COLORS, STATUS_LABELS } from '../shared/labels'
import { useTickets } from './api'
import { parseFilters, type TicketFilters, toSearchParams } from './filters'
import { SlaBadge } from './SlaBadge'
import { TicketFiltersBar } from './TicketFiltersBar'

export function TicketListPage() {
  const user = useCurrentUser()
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = parseFilters(searchParams)
  const tickets = useTickets(filters)

  // Changing any filter goes back to page 1; changing the page keeps the filters.
  function changeFilters(changes: Partial<TicketFilters>) {
    setSearchParams(toSearchParams({ ...filters, page: 1, ...changes }))
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Chamados</Title>
        <Button component={Link} to="/tickets/new">
          Novo chamado
        </Button>
      </Group>

      <TicketFiltersBar filters={filters} onChange={changeFilters} showMine={user.role !== 'REQUESTER'} />

      {tickets.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {tickets.isError && <Alert color="red">{tickets.error.message}</Alert>}
      {tickets.data && tickets.data.content.length === 0 && (
        <Text c="dimmed" py="xl" ta="center">
          Nenhum chamado encontrado com esses filtros.
        </Text>
      )}
      {tickets.data && tickets.data.content.length > 0 && (
        <>
          <Table.ScrollContainer minWidth={860}>
            <Table striped highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Nº</Table.Th>
                  <Table.Th>Título</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th>Prioridade</Table.Th>
                  <Table.Th>Categoria</Table.Th>
                  <Table.Th>Responsável</Table.Th>
                  <Table.Th>SLA</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {tickets.data.content.map((ticket) => (
                  <Table.Tr key={ticket.id}>
                    <Table.Td>{ticket.id}</Table.Td>
                    <Table.Td>
                      <Anchor component={Link} to={`/tickets/${ticket.id}`}>
                        {ticket.title}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>
                      <Badge color={STATUS_COLORS[ticket.status]} variant="light">
                        {STATUS_LABELS[ticket.status]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Badge color={PRIORITY_COLORS[ticket.priority]} variant="outline">
                        {PRIORITY_LABELS[ticket.priority]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>{ticket.category.name}</Table.Td>
                    <Table.Td>{ticket.assignee?.name ?? '—'}</Table.Td>
                    <Table.Td>
                      <SlaBadge sla={ticket.sla} dueAt={ticket.dueAt} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {tickets.data.totalElements} chamado(s)
            </Text>
            <Pagination
              total={tickets.data.totalPages}
              value={filters.page}
              onChange={(page) => setSearchParams(toSearchParams({ ...filters, page }))}
            />
          </Group>
        </>
      )}
    </Stack>
  )
}
