package com.ticketflow.attachment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stores the bytes in the attachment_content table (bytea column). */
@Component
public class DatabaseAttachmentStorage implements AttachmentStorage {

    private final JdbcTemplate jdbc;

    public DatabaseAttachmentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(Long attachmentId, byte[] data) {
        jdbc.update("INSERT INTO attachment_content (attachment_id, data) VALUES (?, ?)", attachmentId, data);
    }

    @Override
    public byte[] load(Long attachmentId) {
        return jdbc.queryForObject("SELECT data FROM attachment_content WHERE attachment_id = ?", byte[].class,
                attachmentId);
    }
}
