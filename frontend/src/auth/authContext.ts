import { createContext, useContext } from 'react'
import type { AuthResponse, User } from '../api/types'

export interface AuthContextValue {
  /** The logged-in user, or null when nobody is logged in. */
  user: User | null
  /** True while a stored token is being checked against GET /auth/me. */
  loading: boolean
  login: (response: AuthResponse) => void
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (value === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}

/** Same as useAuth().user, for screens that are only reachable when logged in. */
export function useCurrentUser(): User {
  const { user } = useAuth()
  if (user === null) {
    throw new Error('useCurrentUser used on a screen without <RequireAuth>')
  }
  return user
}
