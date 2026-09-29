import { Alert, Badge, Center, Group, Loader, Pagination, Select, Stack, Switch, Table, Text, Title, Tooltip } from '@mantine/core'
import { useState } from 'react'
import type { Role } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { ROLE_LABELS, ROLES } from '../shared/labels'
import { useUpdateUser, useUsers } from './api'
import { whyUserIsLocked } from './userRules'

const ROLE_OPTIONS = ROLES.map((role) => ({ value: role, label: ROLE_LABELS[role] }))

export function UsersPage() {
  const me = useCurrentUser()
  const [page, setPage] = useState(1)
  const users = useUsers(page)
  const updateUser = useUpdateUser()

  return (
    <Stack>
      <Title order={2}>Usuários</Title>
      <Text size="sm" c="dimmed">
        Contas de demonstração não podem ser alteradas. Para testar, cadastre um usuário novo e promova-o aqui.
      </Text>

      {users.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {users.isError && <Alert color="red">{users.error.message}</Alert>}
      {users.data && (
        <>
          <Table.ScrollContainer minWidth={720}>
            <Table striped verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Nome</Table.Th>
                  <Table.Th>E-mail</Table.Th>
                  <Table.Th>Papel</Table.Th>
                  <Table.Th>Ativo</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {users.data.content.map((user) => {
                  const lockReason = whyUserIsLocked(user, me)
                  const busy = updateUser.isPending && updateUser.variables?.id === user.id
                  return (
                    <Table.Tr key={user.id}>
                      <Table.Td>
                        <Group gap="xs">
                          {user.name}
                          {user.demo && (
                            <Badge size="xs" variant="light" color="gray">
                              demo
                            </Badge>
                          )}
                        </Group>
                      </Table.Td>
                      <Table.Td>{user.email}</Table.Td>
                      <Table.Td>
                        <Tooltip label={lockReason} disabled={lockReason === null}>
                          <div>
                            <Select<Role>
                              aria-label={`Papel de ${user.name}`}
                              data={ROLE_OPTIONS}
                              value={user.role}
                              onChange={(role) => role && updateUser.mutate({ id: user.id, role })}
                              allowDeselect={false}
                              disabled={lockReason !== null || busy}
                              w={160}
                            />
                          </div>
                        </Tooltip>
                      </Table.Td>
                      <Table.Td>
                        <Tooltip label={lockReason} disabled={lockReason === null}>
                          <div>
                            <Switch
                              aria-label={`${user.name} ativo`}
                              checked={user.active}
                              onChange={(event) =>
                                updateUser.mutate({ id: user.id, active: event.currentTarget.checked })
                              }
                              disabled={lockReason !== null || busy}
                            />
                          </div>
                        </Tooltip>
                      </Table.Td>
                    </Table.Tr>
                  )
                })}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {users.data.totalPages > 1 && <Pagination total={users.data.totalPages} value={page} onChange={setPage} />}
        </>
      )}
    </Stack>
  )
}
