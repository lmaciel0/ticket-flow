import { Alert, Anchor, Center, Grid, Loader, Paper, Stack, Text, Title } from '@mantine/core'
import { Link, useParams } from 'react-router'
import { useCurrentUser } from '../auth/authContext'
import { useTicket } from './api'
import { ticketPermissions } from './permissions'
import { TicketActions } from './TicketActions'
import { TicketSummary } from './TicketSummary'

export function TicketDetailPage() {
  const ticketId = Number(useParams().id)
  const user = useCurrentUser()
  const ticket = useTicket(ticketId)

  if (ticket.isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }
  if (ticket.isError) {
    return (
      <Stack>
        <Alert color="red">{ticket.error.message}</Alert>
        <Anchor component={Link} to="/tickets">
          Voltar para a lista
        </Anchor>
      </Stack>
    )
  }

  const current = ticket.data
  const permissions = ticketPermissions(current, user)

  return (
    <Stack>
      <Anchor component={Link} to="/tickets" size="sm">
        ← Chamados
      </Anchor>
      <Title order={2}>
        #{current.id} · {current.title}
      </Title>

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 8 }} order={{ base: 2, md: 1 }}>
          {/* Task 7 adds comments, attachments and history below the description. */}
          <Paper withBorder p="md" radius="md">
            <Text style={{ whiteSpace: 'pre-wrap' }}>{current.description}</Text>
          </Paper>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }} order={{ base: 1, md: 2 }}>
          <Stack>
            <TicketSummary ticket={current} />
            <TicketActions ticket={current} permissions={permissions} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
