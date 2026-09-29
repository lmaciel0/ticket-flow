import { api } from '../api/client'
import type { Attachment } from '../api/types'

/** Same limits as the backend (spring.servlet.multipart.max-file-size and AllowedFileType). */
export const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
const ACCEPTED_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg', 'txt', 'docx']

/** Value for <input accept>: the file picker already filters these types. */
export const ACCEPTED_FILES = ACCEPTED_EXTENSIONS.map((extension) => `.${extension}`).join(',')

/**
 * Why this file cannot be sent, or null if it can. The backend validates again (including
 * the file signature); checking here avoids uploading megabytes only to get an error back.
 */
export function attachmentProblem(file: File): string | null {
  if (file.size === 0) {
    return 'O arquivo está vazio.'
  }
  if (file.size > MAX_ATTACHMENT_BYTES) {
    return 'O arquivo passa do limite de 5 MB.'
  }
  const dot = file.name.lastIndexOf('.')
  const extension = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase()
  if (!ACCEPTED_EXTENSIONS.includes(extension)) {
    return 'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.'
  }
  return null
}

/**
 * The download needs the Authorization header, so a plain <a href> does not work:
 * we fetch the bytes, wrap them in a temporary object URL and click a hidden link.
 */
export async function downloadAttachment(attachment: Attachment) {
  const blob = await api.blob(`/attachments/${attachment.id}`)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = attachment.filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000) // frees the memory once the browser has the file
}
