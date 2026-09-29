import { Alert, Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core'
import { hasLength, isEmail, isNotEmpty, useForm } from '@mantine/form'
import { useMutation } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { AuthResponse } from '../api/types'
import { AuthCard } from './AuthCard'
import { useAuth } from './authContext'

interface RegisterForm {
  name: string
  email: string
  password: string
}

export function RegisterPage() {
  const { login } = useAuth()
  const form = useForm<RegisterForm>({
    initialValues: { name: '', email: '', password: '' },
    validate: {
      name: isNotEmpty('Informe o nome.'),
      email: isEmail('E-mail inválido.'),
      password: hasLength({ min: 8, max: 64 }, 'A senha deve ter entre 8 e 64 caracteres.'),
    },
  })
  const mutation = useMutation({
    mutationFn: (values: RegisterForm) => api.post<AuthResponse>('/auth/register', values),
    onSuccess: login,
    // A 400 from the API brings one message per field: show each one under its input.
    onError: (error) => {
      if (error instanceof ApiError) {
        form.setErrors(error.fieldErrors)
      }
    },
    meta: { inlineError: true },
  })

  return (
    <AuthCard title="Criar conta">
      <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
        <Stack>
          <Text size="sm" c="dimmed">
            Toda conta nova é de solicitante. Um gestor pode promovê-la depois.
          </Text>
          {mutation.error && <Alert color="red">{mutation.error.message}</Alert>}
          <TextInput label="Nome" autoComplete="name" {...form.getInputProps('name')} />
          <TextInput label="E-mail" type="email" autoComplete="email" {...form.getInputProps('email')} />
          <PasswordInput
            label="Senha"
            description="No mínimo 8 caracteres."
            autoComplete="new-password"
            {...form.getInputProps('password')}
          />
          <Button type="submit" loading={mutation.isPending}>
            Criar conta
          </Button>
          <Text size="sm" ta="center">
            Já tem conta?{' '}
            <Anchor component={Link} to="/login">
              Entrar
            </Anchor>
          </Text>
        </Stack>
      </form>
    </AuthCard>
  )
}
