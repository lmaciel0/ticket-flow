import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'

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
        children: [
          // Temporary: Task 4 puts the ticket list here.
          { path: '/tickets', element: <p>Lista de chamados em construção.</p> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
