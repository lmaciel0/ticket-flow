import { Alert, Anchor, Button, Divider, PasswordInput, SimpleGrid, Stack, Text, TextInput } from '@mantine/core'
import { isEmail, isNotEmpty, useForm } from '@mantine/form'
import { useMutation } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { AuthResponse, Role } from '../api/types'
import { ROLE_LABELS } from '../shared/labels'
import { AuthCard } from './AuthCard'
import { useAuth } from './authContext'

const DEMO_PASSWORD = 'demo1234'

const DEMO_ACCOUNTS: { role: Role; email: string }[] = [
  { role: 'REQUESTER', email: 'solicitante@ticketflow.demo' },
  { role: 'AGENT', email: 'atendente@ticketflow.demo' },
  { role: 'MANAGER', email: 'gestor@ticketflow.demo' },
]

interface Credentials {
  email: string
  password: string
}

export function LoginPage() {
  const { login } = useAuth()
  const form = useForm<Credentials>({
    initialValues: { email: '', password: '' },
    validate: { email: isEmail('E-mail inválido.'), password: isNotEmpty('Informe a senha.') },
  })
  const mutation = useMutation({
    mutationFn: (credentials: Credentials) => api.post<AuthResponse>('/auth/login', credentials),
    onSuccess: login, // <GuestOnly> notices the user and leaves this page
    meta: { inlineError: true },
  })

  return (
    <AuthCard title="Entrar">
      <Stack>
        <Text size="sm">Explore com uma conta de demonstração:</Text>
        <SimpleGrid cols={{ base: 1, xs: 3 }}>
          {DEMO_ACCOUNTS.map((account) => (
            <Button
              key={account.role}
              variant="light"
              loading={mutation.isPending && mutation.variables?.email === account.email}
              disabled={mutation.isPending}
              onClick={() => mutation.mutate({ email: account.email, password: DEMO_PASSWORD })}
            >
              {ROLE_LABELS[account.role]}
            </Button>
          ))}
        </SimpleGrid>
        <Text size="xs" c="dimmed">
          Os dados da demonstração são reiniciados diariamente. O primeiro acesso do dia pode levar até um minuto,
          enquanto o servidor acorda.
        </Text>

        <Divider label="ou entre com e-mail e senha" labelPosition="center" />

        <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
          <Stack>
            {mutation.error && <Alert color="red">{mutation.error.message}</Alert>}
            <TextInput label="E-mail" type="email" autoComplete="email" {...form.getInputProps('email')} />
            <PasswordInput label="Senha" autoComplete="current-password" {...form.getInputProps('password')} />
            <Button type="submit" loading={mutation.isPending}>
              Entrar
            </Button>
          </Stack>
        </form>

        <Text size="sm" ta="center">
          Não tem conta?{' '}
          <Anchor component={Link} to="/register">
            Cadastre-se
          </Anchor>
        </Text>
      </Stack>
    </AuthCard>
  )
}
