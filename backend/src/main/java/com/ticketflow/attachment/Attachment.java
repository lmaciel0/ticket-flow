package com.ticketflow.attachment;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Attachment metadata. The bytes are kept by an AttachmentStorage. */
@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User uploadedBy;

    private String filename;

    private String contentType;

    private long size;

    private Instant createdAt;

    protected Attachment() {
        // required by JPA
    }

    public Attachment(Ticket ticket, User uploadedBy, String filename, String contentType, long size,
            Instant createdAt) {
        this.ticket = ticket;
        this.uploadedBy = uploadedBy;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
