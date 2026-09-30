import { Button, Menu } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useMutation } from '@tanstack/react-query'
import { exportTickets } from './exportTickets'
import type { ExportFormat, TicketFilters } from './filters'

const OPTIONS: { format: ExportFormat; label: string }[] = [
  { format: 'CSV', label: 'CSV (.csv)' },
  { format: 'XLSX', label: 'Excel (.xlsx)' },
  { format: 'PDF', label: 'PDF (.pdf)' },
]

/** Exports every ticket matching the filters on screen (not just the current page). */
export function ExportMenu({ filters }: Readonly<{ filters: TicketFilters }>) {
  const download = useMutation({
    mutationFn: (format: ExportFormat) => exportTickets(filters, format),
    onSuccess: ({ truncated }) => {
      if (truncated) {
        notifications.show({
          color: 'yellow',
          message: 'Muitos chamados: só os primeiros 5.000 foram exportados. Refine os filtros para ver o resto.',
        })
      }
    },
  })

  return (
    <Menu position="bottom-end">
      <Menu.Target>
        <Button variant="default" loading={download.isPending}>
          Exportar
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {OPTIONS.map((option) => (
          <Menu.Item key={option.format} onClick={() => download.mutate(option.format)}>
            {option.label}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  )
}
