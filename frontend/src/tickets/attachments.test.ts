import { describe, expect, it } from 'vitest'
import { attachmentProblem, MAX_ATTACHMENT_BYTES } from './attachments'

function fileOfSize(name: string, size: number): File {
  return new File([new Uint8Array(size)], name)
}

describe('attachmentProblem (checked before uploading, so a big file is not sent for nothing)', () => {
  it('accepts the allowed types up to exactly 5 MB, whatever the case of the extension', () => {
    expect(attachmentProblem(fileOfSize('relatorio.pdf', MAX_ATTACHMENT_BYTES))).toBeNull()
    expect(attachmentProblem(fileOfSize('FOTO.JPG', 1000))).toBeNull()
    expect(attachmentProblem(fileOfSize('notas.txt', 10))).toBeNull()
  })

  it('refuses one byte over 5 MB', () => {
    expect(attachmentProblem(fileOfSize('video.pdf', MAX_ATTACHMENT_BYTES + 1))).toBe(
      'O arquivo passa do limite de 5 MB.',
    )
  })

  it('refuses empty files and types the backend does not accept', () => {
    expect(attachmentProblem(fileOfSize('vazio.txt', 0))).toBe('O arquivo está vazio.')
    expect(attachmentProblem(fileOfSize('setup.exe', 100))).toBe(
      'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.',
    )
    expect(attachmentProblem(fileOfSize('LEIAME', 100))).toBe(
      'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.',
    )
  })
})
