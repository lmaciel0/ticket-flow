package com.ticketflow.attachment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketService;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import com.ticketflow.user.UserSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class AttachmentService {

    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    public record AttachmentResponse(Long id, String filename, String contentType, long size,
            UserSummary uploadedBy, Instant createdAt) {

        static AttachmentResponse from(Attachment attachment) {
            return new AttachmentResponse(attachment.getId(), attachment.getFilename(), attachment.getContentType(),
                    attachment.getSize(), UserSummary.from(attachment.getUploadedBy()), attachment.getCreatedAt());
        }
    }

    public record AttachmentFile(String filename, String contentType, byte[] data) {
    }

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TicketService tickets;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AttachmentService(AttachmentRepository attachments, AttachmentStorage storage, TicketService tickets,
            UserRepository users, ApplicationEventPublisher events, Clock clock) {
        this.attachments = attachments;
        this.storage = storage;
        this.tickets = tickets;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long ticketId, AuthUser authUser) {
        tickets.findVisible(ticketId, authUser);
        return attachments.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    public AttachmentResponse upload(Long ticketId, MultipartFile file, AuthUser authUser) {
        Ticket ticket = tickets.findVisible(ticketId, authUser);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não aceitam anexos.");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("O arquivo está vazio.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw ApiException.payloadTooLarge("O arquivo passa do limite de 5 MB.");
        }
        String filename = sanitize(file.getOriginalFilename());
        byte[] data = readBytes(file);
        AllowedFileType type = AllowedFileType.detect(filename, data)
                .orElseThrow(() -> ApiException.badRequest("Tipo de arquivo não permitido. Envie PDF, PNG, JPEG, TXT ou DOCX."));

        User uploader = users.getCurrent(authUser);
        Instant now = clock.instant();
        Attachment attachment = attachments.save(
                new Attachment(ticket, uploader, filename, type.contentType(), data.length, now));
        storage.store(attachment.getId(), data);
        events.publishEvent(new AttachmentAdded(ticket, uploader, now, filename));
        return AttachmentResponse.from(attachment);
    }

    @Transactional(readOnly = true)
    public AttachmentFile download(Long attachmentId, AuthUser authUser) {
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Anexo não encontrado."));
        try {
            tickets.findVisible(attachment.getTicket().getId(), authUser);
        } catch (ApiException notVisible) {
            throw ApiException.notFound("Anexo não encontrado.");
        }
        return new AttachmentFile(attachment.getFilename(), attachment.getContentType(),
                storage.load(attachment.getId()));
    }

    /** Keeps only the name (browsers may send a full path) and drops control characters. */
    static String sanitize(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Nome de arquivo inválido.");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
