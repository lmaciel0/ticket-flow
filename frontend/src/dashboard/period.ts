/** "2026-09-29" in the browser's time zone. (toISOString() would use UTC and jump a day at night.) */
export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** The last `days` days, today included: the from/to the API expects (both inclusive, at most 90 days). */
export function lastDays(days: number, today: Date = new Date()): { from: string; to: string } {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { from: toIsoDate(start), to: toIsoDate(today) }
}
