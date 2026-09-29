import { Center, Loader } from '@mantine/core'
import { Navigate, Outlet, useLocation } from 'react-router'
import type { Role } from '../api/types'
import { useAuth } from './authContext'

/**
 * Only lets logged-in users (optionally with one of the given roles) through.
 * This is UX only: the real authorization is in the backend, which checks every request.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    )
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
    return null
  }
  return user === null ? <Outlet /> : <Navigate to={from} replace />
}
