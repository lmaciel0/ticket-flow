import { Badge, Stack, Text } from '@mantine/core'
import type { SlaIndicator } from '../api/types'
import { formatDueIn } from '../shared/format'
import { SLA_COLORS, SLA_LABELS } from '../shared/labels'

const CLOCK_RUNNING: SlaIndicator[] = ['ON_TRACK', 'AT_RISK', 'OVERDUE']

interface SlaBadgeProps {
  sla: SlaIndicator
  dueAt: string
  /** Only tests pass it; the screen uses the current time. */
  now?: Date
}

/** SLA indicator computed by the backend, plus the countdown while the clock runs. */
export function SlaBadge({ sla, dueAt, now }: SlaBadgeProps) {
  return (
    <Stack gap={2} align="flex-start">
      <Badge color={SLA_COLORS[sla]} variant={sla === 'OVERDUE' ? 'filled' : 'light'}>
        {SLA_LABELS[sla]}
      </Badge>
      {CLOCK_RUNNING.includes(sla) && (
        <Text size="xs" c="dimmed">
          {formatDueIn(dueAt, now)}
        </Text>
      )}
    </Stack>
  )
}
