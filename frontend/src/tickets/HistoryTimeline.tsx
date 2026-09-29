import { Loader, Text, Timeline } from '@mantine/core'
import { formatDateTime } from '../shared/format'
import { useHistory } from './api'
import { describeEvent } from './history'

export function HistoryTimeline({ ticketId }: { ticketId: number }) {
  const history = useHistory(ticketId)

  if (history.isPending) {
    return <Loader size="sm" />
  }
  if (history.isError) {
    return <Text c="red">{history.error.message}</Text>
  }
  return (
    <Timeline active={history.data.length - 1} bulletSize={14} lineWidth={2}>
      {history.data.map((entry) => (
        <Timeline.Item key={entry.id} title={entry.actor.name}>
          <Text size="sm">{describeEvent(entry)}</Text>
          <Text size="xs" c="dimmed">
            {formatDateTime(entry.occurredAt)}
          </Text>
        </Timeline.Item>
      ))}
    </Timeline>
  )
}
