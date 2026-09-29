import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Page, Role, User } from '../api/types'

export function useUsers(page: number) {
  return useQuery({
    queryKey: ['users', 'list', page],
    queryFn: () => api.get<Page<User>>(`/users?page=${page - 1}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useUpdateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...changes }: { id: number; role?: Role; active?: boolean }) =>
      api.patch<User>(`/users/${id}`, changes),
    // Refresh the table and the assignee options (a promoted or deactivated user changes them).
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
}
