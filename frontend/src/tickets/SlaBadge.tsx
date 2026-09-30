import { Badge, Stack, Text } from '@mantine/core'
import type { SlaIndicator } from '../api/types'
import { formatDueIn } from '../shared/format'
import { SLA_COLORS, SLA_LABELS } from '../shared/labels'
import { useNow } from '../shared/useNow'

const CLOCK_RUNNING: SlaIndicator[] = ['ON_TRACK', 'AT_RISK', 'OVERDUE']

interface SlaBadgeProps {
  sla: SlaIndicator
  dueAt: string
  /** Only tests pass it; the screen uses a clock that ticks. */
  now?: Date
}

/**
 * SLA indicator computed by the backend, plus a countdown that keeps ticking while the clock
 * runs. A deadline that passes while the page is open flips the badge to "Vencido" without a reload.
 */
export function SlaBadge({ sla, dueAt, now }: SlaBadgeProps) {
  const liveNow = useNow()
  const current = now ?? liveNow
  const running = CLOCK_RUNNING.includes(sla)
  const shown: SlaIndicator = running && current.getTime() >= new Date(dueAt).getTime() ? 'OVERDUE' : sla
  return (
    <Stack gap={2} align="flex-start">
      <Badge color={SLA_COLORS[shown]} variant={shown === 'OVERDUE' ? 'filled' : 'light'}>
        {SLA_LABELS[shown]}
      </Badge>
      {running && (
        <Text size="xs" c="dimmed">
          {formatDueIn(dueAt, current)}
        </Text>
      )}
    </Stack>
  )
}
