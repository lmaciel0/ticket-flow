import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { TicketListPage } from './tickets/TicketListPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [{ path: '/tickets', element: <TicketListPage /> }],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
