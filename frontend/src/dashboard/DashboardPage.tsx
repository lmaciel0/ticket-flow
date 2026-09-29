import { BarChart, LineChart } from '@mantine/charts'
import { Alert, Center, Group, Loader, Paper, SegmentedControl, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { type ReactNode, useState } from 'react'
import { api } from '../api/client'
import type { Dashboard } from '../api/types'
import { formatHours, formatPercent } from '../shared/format'
import { STATUS_LABELS, STATUSES } from '../shared/labels'
import { lastDays } from './period'

const PERIODS = [
  { value: '7', label: '7 dias' },
  { value: '30', label: '30 dias' },
  { value: '90', label: '90 dias' },
]

function Stat({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
    </Paper>
  )
}

function ChartCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Text fw={600} mb="md">
        {title}
      </Text>
      {children}
    </Paper>
  )
}

/** "2026-09-29" -> "29/09" for the chart axis. */
function shortDate(isoDate: string): string {
  const [, month, day] = isoDate.split('-')
  return `${day}/${month}`
}

export function DashboardPage() {
  const [days, setDays] = useState('30')
  const { from, to } = lastDays(Number(days))
  const dashboard = useQuery({
    queryKey: ['dashboard', from, to],
    queryFn: () => api.get<Dashboard>(`/dashboard?from=${from}&to=${to}`),
    placeholderData: keepPreviousData,
  })

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Painel</Title>
        <SegmentedControl data={PERIODS} value={days} onChange={setDays} />
      </Group>

      {dashboard.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {dashboard.isError && <Alert color="red">{dashboard.error.message}</Alert>}
      {dashboard.data && <DashboardContent data={dashboard.data} />}
    </Stack>
  )
}

function DashboardContent({ data }: { data: Dashboard }) {
  const byStatus = STATUSES.map((status) => ({ status: STATUS_LABELS[status], total: data.ticketsByStatus[status] }))
  const daily = data.daily.map((day) => ({ ...day, date: shortDate(day.date) }))

  return (
    <Stack>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        <Stat label="Vencidos agora" value={data.overdueNow} color={data.overdueNow > 0 ? 'red' : undefined} />
        <Stat label="Resolvidos no período" value={data.resolvedInPeriod} />
        <Stat label="SLA cumprido" value={formatPercent(data.slaMetPercentage)} />
        <Stat label="Tempo médio de resolução" value={formatHours(data.averageResolutionHours)} />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <ChartCard title="Chamados por status (agora)">
          <BarChart h={260} data={byStatus} dataKey="status" series={[{ name: 'total', label: 'Chamados', color: 'indigo.6' }]} />
        </ChartCard>
        <ChartCard title="Abertos no período por categoria">
          <BarChart
            h={260}
            data={data.openedByCategory}
            dataKey="category"
            series={[{ name: 'count', label: 'Abertos', color: 'blue.6' }]}
          />
        </ChartCard>
      </SimpleGrid>

      <ChartCard title="Abertos × resolvidos por dia">
        <LineChart
          h={280}
          data={daily}
          dataKey="date"
          withLegend
          curveType="monotone"
          series={[
            { name: 'opened', label: 'Abertos', color: 'blue.6' },
            { name: 'resolved', label: 'Resolvidos', color: 'teal.6' },
          ]}
        />
      </ChartCard>

      <ChartCard title="Atendentes">
        <Table.ScrollContainer minWidth={420}>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Atendente</Table.Th>
                <Table.Th>Chamados em andamento</Table.Th>
                <Table.Th>Resolvidos no período</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.agents.map((agent) => (
                <Table.Tr key={agent.id}>
                  <Table.Td>{agent.name}</Table.Td>
                  <Table.Td>{agent.activeAssigned}</Table.Td>
                  <Table.Td>{agent.resolvedInPeriod}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </ChartCard>
    </Stack>
  )
}
