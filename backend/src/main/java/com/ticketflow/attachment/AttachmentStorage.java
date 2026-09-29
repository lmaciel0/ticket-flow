package com.ticketflow.attachment;

/**
 * Where attachment bytes live. Today: a PostgreSQL table (zero cost).
 * Tomorrow: disk or S3 — only a new implementation of this interface is needed.
 */
public interface AttachmentStorage {

    void store(Long attachmentId, byte[] data);

    byte[] load(Long attachmentId);
}
