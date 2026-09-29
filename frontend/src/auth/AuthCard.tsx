import { Container, Paper, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'

/** Centered card shared by the login and sign-up pages. */
export function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Container size={440} py={60}>
      <Title ta="center">ticket-flow</Title>
      <Text ta="center" c="dimmed" mt={4}>
        Gestão de chamados com SLA
      </Text>
      <Paper withBorder shadow="sm" p="xl" mt="xl" radius="md">
        <Title order={3} mb="md">
          {title}
        </Title>
        {children}
      </Paper>
    </Container>
  )
}
