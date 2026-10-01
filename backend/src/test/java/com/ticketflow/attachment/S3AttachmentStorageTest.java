package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.ApiException;
import com.ticketflow.support.S3IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class S3AttachmentStorageTest extends S3IntegrationTest {

    static final byte[] DATA = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);

    @Autowired AttachmentStorage storage;
    @Autowired S3Client s3;
    @Autowired S3StorageProperties properties;
    @Autowired PlatformTransactionManager transactionManager;

    boolean objectExists(long attachmentId) {
        try {
            s3.headObject(b -> b.bucket(properties.bucket()).key(S3AttachmentStorage.key(attachmentId)));
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        }
    }

    @Test
    void theS3AdapterIsTheActiveOne() {
        assertThat(storage).isInstanceOf(S3AttachmentStorage.class);
    }

    @Test
    void storesAndLoadsTheSameBytes() {
        storage.store(9001L, DATA); // no transaction: nothing to compensate, must not fail

        assertThat(storage.load(9001L)).isEqualTo(DATA);
        assertThat(objectExists(9001L)).isTrue();
    }

    @Test
    void aCommittedTransactionKeepsTheObject() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> storage.store(9002L, DATA));

        assertThat(objectExists(9002L)).isTrue();
    }

    @Test
    void aRolledBackTransactionDeletesTheObject() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            storage.store(9003L, DATA);
            assertThat(objectExists(9003L)).isTrue(); // already uploaded: S3 is not part of the transaction
            tx.setRollbackOnly();
        });

        assertThat(objectExists(9003L)).isFalse();
    }

    @Test
    void aMissingObjectIsNotFound() {
        assertThatThrownBy(() -> storage.load(424242L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
