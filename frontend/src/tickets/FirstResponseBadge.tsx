import { Badge, Stack, Text } from '@mantine/core'
import type { FirstResponseIndicator } from '../api/types'
import { formatDueIn } from '../shared/format'
import { FIRST_RESPONSE_COLORS, FIRST_RESPONSE_LABELS } from '../shared/labels'
import { useNow } from '../shared/useNow'

interface FirstResponseBadgeProps {
  indicator: FirstResponseIndicator | null
  dueAt: string | null
  /** Only tests pass it; the screen uses a clock that ticks. */
  now?: Date
}

/**
 * First-response result computed by the backend. While nobody has answered, a countdown keeps ticking
 * and a deadline that passes with the page open flips "Pendente" to "Atrasada" without a reload.
 */
export function FirstResponseBadge({ indicator, dueAt, now }: FirstResponseBadgeProps) {
  const liveNow = useNow()
  if (indicator === null || dueAt === null) {
    return null
  }
  const current = now ?? liveNow
  const waiting = indicator === 'PENDING' || indicator === 'OVERDUE'
  const shown: FirstResponseIndicator =
    indicator === 'PENDING' && current.getTime() >= new Date(dueAt).getTime() ? 'OVERDUE' : indicator
  return (
    <Stack gap={2} align="flex-start">
      <Badge color={FIRST_RESPONSE_COLORS[shown]} variant={shown === 'OVERDUE' ? 'filled' : 'light'}>
        {FIRST_RESPONSE_LABELS[shown]}
      </Badge>
      {waiting && (
        <Text size="xs" c="dimmed">
          {formatDueIn(dueAt, current)}
        </Text>
      )}
    </Stack>
  )
}
