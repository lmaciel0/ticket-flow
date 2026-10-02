import { notifications } from '@mantine/notifications'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { api, refreshSession, setUnauthorizedHandler, tokenStorage } from '../api/client'
import type { AuthResponse, User } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

type AuthMessage = { type: 'login' } | { type: 'logout' }

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setToken] = useState(tokenStorage.get)
  // A freshly opened page has no access token in memory: the refresh cookie, if any, brings the session back.
  const [restoring, setRestoring] = useState(() => tokenStorage.get() === null)
  // Tabs no longer share the token, so they tell each other about logins and logouts.
  const [channel] = useState(() =>
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('ticketflow-auth'),
  )
  useEffect(() => () => channel?.close(), [channel])

  // The token is part of the key: another token is another user.
  const me = useQuery({
    queryKey: ['me', token],
    queryFn: () => api.get<User>('/auth/me'),
    enabled: token !== null,
    staleTime: Infinity,
  })

  const adopt = useCallback(
    (session: AuthResponse) => {
      tokenStorage.set(session.token)
      queryClient.setQueryData(['me', session.token], session.user)
      setToken(session.token)
    },
    [queryClient],
  )

  const restore = useCallback(
    () =>
      refreshSession().then((session) => {
        if (session) {
          adopt(session)
        } else {
          setToken(null)
        }
        setRestoring(false)
      }),
    [adopt],
  )

  useEffect(() => {
    if (tokenStorage.get() === null) {
      void restore()
    }
  }, [restore])

  const dropSession = useCallback(() => {
    tokenStorage.clear()
    setToken(null)
    queryClient.clear() // no data from this user may be shown to the next one
  }, [queryClient])

  const login = useCallback(
    (response: AuthResponse) => {
      adopt(response)
      if (response.sessionCode) {
        api
          .post('/auth/session', { code: response.sessionCode })
          .then(() => channel?.postMessage({ type: 'login' } satisfies AuthMessage))
          .catch(() =>
            notifications.show({
              color: 'yellow',
              message: 'Não foi possível manter a sessão neste navegador: ao recarregar a página, entre de novo.',
            }),
          )
      }
    },
    [adopt, channel],
  )

  const logout = useCallback(() => {
    dropSession()
    channel?.postMessage({ type: 'logout' } satisfies AuthMessage)
    api.post('/auth/logout').catch(() => {
      // best effort: the cookie is HttpOnly, only the server can end it; without the server it expires anyway
    })
  }, [dropSession, channel])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      dropSession()
      notifications.show({ color: 'yellow', message: 'Sua sessão expirou. Entre novamente.' })
    })
  }, [dropSession])

  // Another tab logged out: follow it. Another tab logged in (maybe as someone else): the shared cookie now
  // holds that session, so renew from it. This tab must never show one user while its requests carry another's.
  useEffect(() => {
    if (!channel) {
      return
    }
    function onMessage(event: MessageEvent<AuthMessage>) {
      queryClient.clear()
      tokenStorage.clear()
      if (event.data.type === 'logout') {
        setToken(null)
      } else {
        setRestoring(true)
        void restore()
      }
    }
    channel.addEventListener('message', onMessage)
    return () => channel.removeEventListener('message', onMessage)
  }, [channel, queryClient, restore])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: token !== null ? (me.data ?? null) : null,
      loading: restoring || (token !== null && me.isPending),
      login,
      logout,
    }),
    [token, me.data, me.isPending, restoring, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
