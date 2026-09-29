import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { NewTicketPage } from './tickets/NewTicketPage'
import { TicketDetailPage } from './tickets/TicketDetailPage'
import { TicketListPage } from './tickets/TicketListPage'
import { UsersPage } from './users/UsersPage'

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
          { path: '/tickets', element: <TicketListPage /> },
          { path: '/tickets/new', element: <NewTicketPage /> },
          { path: '/tickets/:id', element: <TicketDetailPage /> },
          {
            element: <RequireAuth roles={['MANAGER']} />,
            children: [
              { path: '/users', element: <UsersPage /> },
              {
                path: '/dashboard',
                // Code splitting: the charts library is only downloaded when a manager opens the dashboard.
                lazy: () => import('./dashboard/DashboardPage').then((module) => ({ Component: module.DashboardPage })),
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
