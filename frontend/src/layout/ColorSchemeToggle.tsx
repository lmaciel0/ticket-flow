import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core'

/** Light/dark switch; Mantine remembers the choice in localStorage and starts from the OS setting. */
export function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme()
  const computed = useComputedColorScheme('light')
  const dark = computed === 'dark'

  return (
    <ActionIcon
      variant="subtle"
      color="gray"
      onClick={() => setColorScheme(dark ? 'light' : 'dark')}
      aria-label={dark ? 'Usar tema claro' : 'Usar tema escuro'}
    >
      <span aria-hidden>{dark ? '☀' : '☾'}</span>
    </ActionIcon>
  )
}
