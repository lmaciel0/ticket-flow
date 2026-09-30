import { Alert, Badge, Button, Checkbox, Group, Loader, Paper, Stack, Text, Textarea } from '@mantine/core'
import { useState } from 'react'
import type { Ticket } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { formatDateTime } from '../shared/format'
import { useAddComment, useComments } from './api'

interface CommentsSectionProps {
  ticket: Ticket
  canComment: boolean
}

export function CommentsSection({ ticket, canComment }: CommentsSectionProps) {
  const user = useCurrentUser()
  const comments = useComments(ticket.id)
  const addComment = useAddComment(ticket.id)
  const [text, setText] = useState('')
  const [internal, setInternal] = useState(false)
  const isTeam = user.role !== 'REQUESTER'
  const waitingForMe = ticket.status === 'WAITING_REQUESTER' && ticket.requester.id === user.id

  return (
    <Stack>
      {comments.isPending && <Loader size="sm" />}
      {comments.data?.length === 0 && (
        <Text size="sm" c="dimmed">
          Nenhum comentário ainda.
        </Text>
      )}
      {comments.data?.map((comment) => (
        <Paper
          key={comment.id}
          withBorder
          p="sm"
          radius="md"
          style={comment.internal ? { background: 'light-dark(var(--mantine-color-yellow-0), rgba(250, 176, 5, 0.12))' } : undefined}
        >
          <Group justify="space-between" mb={4}>
            <Group gap="xs">
              <Text size="sm" fw={600}>
                {comment.author.name}
              </Text>
              {comment.internal && (
                <Badge color="yellow" variant="light">
                  🔒 Nota interna
                </Badge>
              )}
            </Group>
            <Text size="xs" c="dimmed">
              {formatDateTime(comment.createdAt)}
            </Text>
          </Group>
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
            {comment.text}
          </Text>
        </Paper>
      ))}

      {waitingForMe && (
        <Alert color="yellow">O atendimento precisa de mais informações. Ao comentar, o chamado volta para atendimento.</Alert>
      )}
      {canComment ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            addComment.mutate(
              { text: text.trim(), internal },
              {
                onSuccess: () => {
                  setText('')
                  setInternal(false)
                },
              },
            )
          }}
        >
          <Stack gap="xs">
            <Textarea
              aria-label="Novo comentário"
              placeholder="Escreva um comentário"
              autosize
              minRows={3}
              maxLength={5000}
              value={text}
              onChange={(event) => setText(event.currentTarget.value)}
            />
            <Group justify={isTeam ? 'space-between' : 'flex-end'}>
              {isTeam && (
                <Checkbox
                  label="Nota interna (o solicitante não vê)"
                  checked={internal}
                  onChange={(event) => setInternal(event.currentTarget.checked)}
                />
              )}
              <Button type="submit" loading={addComment.isPending} disabled={text.trim() === ''}>
                {internal ? 'Salvar nota' : 'Comentar'}
              </Button>
            </Group>
          </Stack>
        </form>
      ) : (
        <Text size="sm" c="dimmed">
          Chamados fechados não aceitam comentários.
        </Text>
      )}
    </Stack>
  )
}
