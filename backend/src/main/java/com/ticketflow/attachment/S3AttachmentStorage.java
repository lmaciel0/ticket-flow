package com.ticketflow.attachment;

import com.ticketflow.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Stores the bytes in an S3-compatible bucket (AWS S3, MinIO, R2...), under attachments/{id}.
 *
 * <p>S3 is not part of the database transaction: the upload happens at once. If the transaction is
 * then rolled back (for example the history insert fails), the attachment row disappears but the object
 * would stay. So the upload registers a compensating action: on rollback, delete the object.
 */
@Component
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")
public class S3AttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3AttachmentStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3AttachmentStorage(S3Client s3, S3StorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
    }

    static String key(Long attachmentId) {
        return "attachments/" + attachmentId;
    }

    @Override
    public void store(Long attachmentId, byte[] data) {
        String key = key(attachmentId);
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(data));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        deleteQuietly(key);
                    }
                }
            });
        }
    }

    @Override
    public byte[] load(Long attachmentId) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key(attachmentId))).asByteArray();
        } catch (NoSuchKeyException missing) {
            log.error("Attachment {} has no object {} in bucket {}", attachmentId, key(attachmentId), bucket);
            throw ApiException.notFound("Anexo não encontrado.");
        }
    }

    /** The transaction is already over: a failure here can only be logged (the object is orphaned). */
    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            log.warn("Could not delete orphaned object {} from bucket {} after a rollback", key, bucket, e);
        }
    }
}
