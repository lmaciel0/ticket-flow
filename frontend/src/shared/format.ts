const MINUTE = 60_000

const dateTimeFormat = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
const decimalFormat = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 })

/** "29/09/2026, 22:30" in the browser's time zone (the API always sends UTC). */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}

/** A positive duration in the largest useful unit: "45 min", "3 h", "2 d 4 h". */
export function formatDuration(ms: number): string {
  const minutes = Math.floor(ms / MINUTE)
  if (minutes < 60) {
    return `${minutes} min`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} h`
  }
  const days = Math.floor(hours / 24)
  const restHours = hours % 24
  return restHours === 0 ? `${days} d` : `${days} d ${restHours} h`
}

/** "vence em 3 h" or "venceu há 30 min". The deadline itself already counts as overdue. */
export function formatDueIn(dueAt: string, now: Date = new Date()): string {
  const diff = new Date(dueAt).getTime() - now.getTime()
  return diff > 0 ? `vence em ${formatDuration(diff)}` : `venceu há ${formatDuration(-diff)}`
}

export function formatPercent(value: number | null): string {
  return value === null ? '—' : `${decimalFormat.format(value)}%`
}

export function formatHours(value: number | null): string {
  return value === null ? '—' : `${decimalFormat.format(value)} h`
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${decimalFormat.format(bytes / 1024)} KB`
  }
  return `${decimalFormat.format(bytes / (1024 * 1024))} MB`
}
