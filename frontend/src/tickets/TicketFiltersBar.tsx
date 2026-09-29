import { Checkbox, Group, MultiSelect, Select, TextInput } from '@mantine/core'
import type { Priority, SlaFilter, TicketStatus } from '../api/types'
import { PRIORITIES, PRIORITY_LABELS, STATUS_LABELS, STATUSES } from '../shared/labels'
import { useCategories } from './api'
import type { TicketFilters, TicketSort } from './filters'

const STATUS_OPTIONS = STATUSES.map((status) => ({ value: status, label: STATUS_LABELS[status] }))
const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))
const SLA_OPTIONS: { value: SlaFilter; label: string }[] = [
  { value: 'OVERDUE', label: 'Vencidos' },
  { value: 'AT_RISK', label: 'Em risco' },
]
const SORT_OPTIONS: { value: TicketSort; label: string }[] = [
  { value: 'dueAt,asc', label: 'Prazo mais próximo' },
  { value: 'createdAt,desc', label: 'Mais recentes' },
]

interface TicketFiltersBarProps {
  filters: TicketFilters
  onChange: (changes: Partial<TicketFilters>) => void
  /** "Assigned to me" only makes sense for agents and managers. */
  showMine: boolean
}

export function TicketFiltersBar({ filters, onChange, showMine }: TicketFiltersBarProps) {
  const categories = useCategories()
  const categoryOptions = (categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))

  return (
    <Group align="flex-end" gap="sm">
      {/* key: when the URL changes (back button), the input restarts with the new text. */}
      <form
        key={filters.q}
        onSubmit={(event) => {
          event.preventDefault()
          onChange({ q: String(new FormData(event.currentTarget).get('q') ?? '') })
        }}
      >
        <TextInput
          name="q"
          label="Buscar"
          placeholder="Título ou descrição + Enter"
          defaultValue={filters.q}
          w={240}
        />
      </form>
      <MultiSelect<TicketStatus>
        label="Status"
        placeholder={filters.status.length === 0 ? 'Todos' : undefined}
        data={STATUS_OPTIONS}
        value={filters.status}
        onChange={(status) => onChange({ status })}
        w={300}
      />
      <Select<Priority>
        label="Prioridade"
        placeholder="Todas"
        data={PRIORITY_OPTIONS}
        value={filters.priority}
        onChange={(priority) => onChange({ priority })}
        clearable
        w={140}
      />
      <Select<number>
        label="Categoria"
        placeholder="Todas"
        data={categoryOptions}
        value={filters.categoryId}
        onChange={(categoryId) => onChange({ categoryId })}
        clearable
        w={150}
      />
      <Select<SlaFilter>
        label="SLA"
        placeholder="Todos"
        data={SLA_OPTIONS}
        value={filters.sla}
        onChange={(sla) => onChange({ sla })}
        clearable
        w={130}
      />
      <Select<TicketSort>
        label="Ordenar por"
        data={SORT_OPTIONS}
        value={filters.sort}
        onChange={(sort) => sort && onChange({ sort })}
        allowDeselect={false}
        w={180}
      />
      {showMine && (
        <Checkbox
          label="Atribuídos a mim"
          checked={filters.mine}
          onChange={(event) => onChange({ mine: event.currentTarget.checked })}
          mb={8}
        />
      )}
    </Group>
  )
}
