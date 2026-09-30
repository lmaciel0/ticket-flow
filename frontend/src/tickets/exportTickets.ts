import { api } from '../api/client'
import { type ExportFormat, type TicketFilters, toExportQuery } from './filters'

const EXTENSIONS: Record<ExportFormat, string> = { CSV: 'csv', XLSX: 'xlsx', PDF: 'pdf' }

/**
 * Downloads the tickets matching the current filters. Like attachments, the request needs the
 * Authorization header, so we fetch the bytes and click a temporary link. Returns whether the
 * file was cut short because more tickets matched than the server exports.
 */
export async function exportTickets(filters: TicketFilters, format: ExportFormat): Promise<{ truncated: boolean }> {
  const file = await api.file(`/tickets/export?${toExportQuery(filters, format)}`)
  const url = URL.createObjectURL(file.blob)
  const link = document.createElement('a')
  link.href = url
  link.download = file.filename ?? `chamados.${EXTENSIONS[format]}`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000) // frees the memory once the browser has the file
  return { truncated: file.truncated }
}
