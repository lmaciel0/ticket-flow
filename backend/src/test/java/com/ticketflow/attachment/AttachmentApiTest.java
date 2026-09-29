package com.ticketflow.attachment;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class AttachmentApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII);

    User ana;
    User eva;
    User bruno;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        ticketId = createTicket(ana, "Impressora", "LOW");
    }

    ResultActions upload(User user, String filename, byte[] data) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream", data);
        return mvc.perform(multipart("/api/tickets/{id}/attachments", ticketId)
                .file(file)
                .header("Authorization", bearer(user)));
    }

    @Test
    void uploadListAndDownloadRoundTrip() throws Exception {
        String body = upload(ana, "relatório.pdf", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("relatório.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.size").value(PDF.length))
                .andReturn().getResponse().getContentAsString();
        long attachmentId = readLong(body, "$.id");

        mvc.perform(get("/api/tickets/{id}/attachments", ticketId).header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uploadedBy.name").value("Ana"));
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(bruno)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("UTF-8''relat%C3%B3rio.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[-1].eventType").value("ATTACHMENT_ADDED"))
                .andExpect(jsonPath("$[-1].newValue").value("relatório.pdf"));
    }

    @Test
    void otherRequestersCannotUploadOrDownload() throws Exception {
        long attachmentId = readLong(upload(ana, "a.pdf", PDF).andReturn().getResponse().getContentAsString(), "$.id");

        upload(eva, "b.pdf", PDF).andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDisguisedAndEmptyFiles() throws Exception {
        upload(ana, "virus.pdf", "MZ executable".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isBadRequest());
        upload(ana, "vazio.pdf", new byte[0]).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFilesOver5MbWith413() throws Exception {
        byte[] big = Arrays.copyOf(PDF, 5 * 1024 * 1024 + 1);

        upload(ana, "grande.pdf", big).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void keepsOnlyTheFileNameFromAFullPath() throws Exception {
        upload(ana, "C:\\Users\\ana\\Desktop\\nota.txt", "texto".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("nota.txt"));
    }

    @Test
    void closedTicketRejectsAttachments() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\", \"version\": 2}"))
                .andExpect(status().isOk());

        upload(ana, "tarde.pdf", PDF).andExpect(status().isConflict());
    }
}
