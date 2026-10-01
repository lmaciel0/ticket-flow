package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.S3IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/** Same API as AttachmentApiTest, other adapter: AttachmentService did not change to make this work. */
class AttachmentS3ApiTest extends S3IntegrationTest {

    static final byte[] PDF = "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII);

    @Autowired S3Client s3;
    @Autowired S3StorageProperties properties;

    @Test
    void uploadAndDownloadGoThroughTheBucket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");
        MockMultipartFile file = new MockMultipartFile("file", "relatorio.pdf", "application/octet-stream", PDF);

        String body = mvc.perform(multipart("/api/tickets/{id}/attachments", ticketId)
                        .file(file).header("Authorization", bearer(ana)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long attachmentId = readLong(body, "$.id");

        byte[] stored = s3.getObjectAsBytes(b -> b.bucket(properties.bucket())
                .key(S3AttachmentStorage.key(attachmentId))).asByteArray();
        assertThat(stored).isEqualTo(PDF);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_content", Long.class)).isZero();

        mvc.perform(get("/api/tickets/{id}/attachments", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[0].filename").value("relatorio.pdf"));
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));
    }
}
