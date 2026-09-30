import { AppShell, Badge, Burger, Button, Group, NavLink, Text } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { Link, Outlet, useLocation } from 'react-router'
import { useAuth, useCurrentUser } from '../auth/authContext'
import { ROLE_LABELS } from '../shared/labels'
import { ColorSchemeToggle } from './ColorSchemeToggle'

interface MenuLink {
  to: string
  label: string
  active: (pathname: string) => boolean
}

const TICKET_LINKS: MenuLink[] = [
  { to: '/tickets', label: 'Chamados', active: (path) => path === '/tickets' || /^\/tickets\/\d+/.test(path) },
  { to: '/tickets/new', label: 'Novo chamado', active: (path) => path === '/tickets/new' },
]

const MANAGER_LINKS: MenuLink[] = [
  { to: '/users', label: 'Usuários', active: (path) => path === '/users' },
  { to: '/dashboard', label: 'Painel', active: (path) => path === '/dashboard' },
]

/** Frame of every logged-in screen: header with the user, side menu by role, page in <Outlet />. */
export function AppLayout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const { pathname } = useLocation()
  const [menuOpened, menu] = useDisclosure()
  const links = user.role === 'MANAGER' ? [...TICKET_LINKS, ...MANAGER_LINKS] : TICKET_LINKS

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !menuOpened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={menuOpened} onClick={menu.toggle} hiddenFrom="sm" size="sm" aria-label="Menu" />
            <Text fw={700}>ticket-flow</Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Text size="sm" visibleFrom="xs">
              {user.name}
            </Text>
            <Badge variant="light">{ROLE_LABELS[user.role]}</Badge>
            <ColorSchemeToggle />
            <Button variant="subtle" size="xs" onClick={logout}>
              Sair
            </Button>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {links.map((link) => (
          <NavLink
            key={link.to}
            component={Link}
            to={link.to}
            label={link.label}
            active={link.active(pathname)}
            onClick={menu.close}
          />
        ))}
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
