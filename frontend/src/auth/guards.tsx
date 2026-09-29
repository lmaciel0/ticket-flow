import { Center, Loader, Stack, Text } from '@mantine/core'
import { Navigate, Outlet, useLocation } from 'react-router'
import type { Role } from '../api/types'
import { useAuth } from './authContext'

/**
 * Shown while a saved session is checked. On the free host the server sleeps after 15 minutes
 * without traffic, so this first request can take about a minute: say so instead of a bare spinner.
 */
function CheckingSession() {
  return (
    <Center h="100vh" p="md">
      <Stack align="center" gap="sm">
        <Loader />
        <Text size="sm" c="dimmed" ta="center" maw={360}>
          Conectando ao servidor. Se ele estava dormindo, o primeiro acesso pode levar cerca de um minuto.
        </Text>
      </Stack>
    </Center>
  )
}

/**
 * Only lets logged-in users (optionally with one of the given roles) through.
 * This is UX only: the real authorization is in the backend, which checks every request.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return <CheckingSession />
  }
  if (user === null) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/tickets" replace />
  }
  return <Outlet />
}

/** Login and sign-up pages: a logged-in user goes back to where they came from (or to the list). */
export function GuestOnly() {
  const { user, loading } = useAuth()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/tickets'

  if (loading) {
    return <CheckingSession />
  }
  return user === null ? <Outlet /> : <Navigate to={from} replace />
}
