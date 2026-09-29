import { notifications } from '@mantine/notifications'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { api, setUnauthorizedHandler, TOKEN_KEY, tokenStorage } from '../api/client'
import type { AuthResponse, User } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setToken] = useState(tokenStorage.get)

  // With a stored token, ask the API who we are. The query only runs while there is a token,
  // and the token is part of the key: another token is another user.
  const me = useQuery({
    queryKey: ['me', token],
    queryFn: () => api.get<User>('/auth/me'),
    enabled: token !== null,
    staleTime: Infinity,
  })

  const login = useCallback(
    (response: AuthResponse) => {
      tokenStorage.set(response.token)
      queryClient.setQueryData(['me', response.token], response.user)
      setToken(response.token)
    },
    [queryClient],
  )

  const logout = useCallback(() => {
    tokenStorage.clear()
    setToken(null)
    queryClient.clear() // no data from this user may be shown to the next one
  }, [queryClient])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout()
      notifications.show({ color: 'yellow', message: 'Sua sessão expirou. Entre novamente.' })
    })
  }, [logout])

  // All tabs share localStorage. When another tab logs in, switches user or logs out, this tab
  // follows it: it must never show one user while its requests carry another user's token.
  useEffect(() => {
    function onStorage(event: StorageEvent) {
      if (event.key === TOKEN_KEY || event.key === null) {
        queryClient.clear()
        setToken(tokenStorage.get())
      }
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [queryClient])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: token !== null ? (me.data ?? null) : null,
      loading: token !== null && me.isPending,
      login,
      logout,
    }),
    [token, me.data, me.isPending, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
