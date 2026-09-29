import { Alert, Button, Group, Paper, Select, Stack, Textarea, TextInput, Title } from '@mantine/core'
import { hasLength, isNotEmpty, useForm } from '@mantine/form'
import { notifications } from '@mantine/notifications'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import type { Priority } from '../api/types'
import { PRIORITIES, PRIORITY_LABELS } from '../shared/labels'
import { useCategories, useCreateTicket } from './api'

const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))

interface NewTicketForm {
  title: string
  description: string
  priority: Priority
  categoryId: number | null
}

export function NewTicketPage() {
  const navigate = useNavigate()
  const categories = useCategories()
  const createTicket = useCreateTicket()
  const form = useForm<NewTicketForm>({
    initialValues: { title: '', description: '', priority: 'MEDIUM', categoryId: null },
    validate: {
      title: hasLength({ min: 1, max: 120 }, 'Informe um título de até 120 caracteres.'),
      description: hasLength({ min: 1, max: 5000 }, 'Descreva o problema (até 5000 caracteres).'),
      categoryId: isNotEmpty('Escolha a categoria.'),
    },
  })

  function submit(values: NewTicketForm) {
    createTicket.mutate(
      { ...values, title: values.title.trim(), categoryId: values.categoryId! },
      {
        onSuccess: (ticket) => {
          notifications.show({ color: 'green', message: `Chamado nº ${ticket.id} aberto.` })
          navigate(`/tickets/${ticket.id}`)
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            form.setErrors(error.fieldErrors)
          }
        },
      },
    )
  }

  return (
    <Stack maw={720}>
      <Title order={2}>Novo chamado</Title>
      <Paper withBorder p="lg" radius="md">
        <form onSubmit={form.onSubmit(submit)}>
          <Stack>
            {createTicket.error && <Alert color="red">{createTicket.error.message}</Alert>}
            <TextInput label="Título" placeholder="Resumo do problema" maxLength={120} {...form.getInputProps('title')} />
            <Textarea
              label="Descrição"
              placeholder="O que aconteceu, desde quando, o que já tentou..."
              autosize
              minRows={5}
              maxLength={5000}
              {...form.getInputProps('description')}
            />
            <Group grow align="flex-start">
              <Select<Priority>
                label="Prioridade"
                data={PRIORITY_OPTIONS}
                allowDeselect={false}
                {...form.getInputProps('priority')}
              />
              <Select<number>
                label="Categoria"
                placeholder="Escolha"
                data={(categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))}
                {...form.getInputProps('categoryId')}
              />
            </Group>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => navigate(-1)}>
                Cancelar
              </Button>
              <Button type="submit" loading={createTicket.isPending}>
                Abrir chamado
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  )
}
