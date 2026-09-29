import { Button, FileButton, Group, Loader, Stack, Table, Text } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { showError } from '../api/queryClient'
import type { Attachment } from '../api/types'
import { formatDateTime, formatFileSize } from '../shared/format'
import { useAttachments, useUploadAttachment } from './api'
import { ACCEPTED_FILES, attachmentProblem, downloadAttachment } from './attachments'

interface AttachmentsSectionProps {
  ticketId: number
  canAttach: boolean
}

export function AttachmentsSection({ ticketId, canAttach }: AttachmentsSectionProps) {
  const attachments = useAttachments(ticketId)
  const upload = useUploadAttachment(ticketId)

  function send(file: File | null) {
    if (file === null) {
      return
    }
    const problem = attachmentProblem(file)
    if (problem) {
      notifications.show({ color: 'red', title: 'Arquivo não enviado', message: problem })
      return
    }
    upload.mutate(file, {
      onSuccess: () => notifications.show({ color: 'green', message: `${file.name} anexado.` }),
    })
  }

  function download(attachment: Attachment) {
    downloadAttachment(attachment).catch(showError)
  }

  return (
    <Stack>
      {attachments.isPending && <Loader size="sm" />}
      {attachments.data?.length === 0 && (
        <Text size="sm" c="dimmed">
          Nenhum anexo.
        </Text>
      )}
      {attachments.data && attachments.data.length > 0 && (
        <Table.ScrollContainer minWidth={520}>
          <Table verticalSpacing="xs">
            <Table.Tbody>
              {attachments.data.map((attachment) => (
                <Table.Tr key={attachment.id}>
                  <Table.Td>
                    <Button variant="subtle" size="compact-sm" onClick={() => download(attachment)}>
                      {attachment.filename}
                    </Button>
                  </Table.Td>
                  <Table.Td>{formatFileSize(attachment.size)}</Table.Td>
                  <Table.Td>{attachment.uploadedBy.name}</Table.Td>
                  <Table.Td>{formatDateTime(attachment.createdAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {canAttach && (
        <Group>
          <FileButton onChange={send} accept={ACCEPTED_FILES}>
            {(props) => (
              <Button {...props} variant="light" loading={upload.isPending}>
                Anexar arquivo
              </Button>
            )}
          </FileButton>
          <Text size="xs" c="dimmed">
            PDF, PNG, JPEG, TXT ou DOCX, até 5 MB.
          </Text>
        </Group>
      )}
    </Stack>
  )
}
