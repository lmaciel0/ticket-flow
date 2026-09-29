package com.ticketflow.attachment;

import com.ticketflow.attachment.AttachmentService.AttachmentFile;
import com.ticketflow.attachment.AttachmentService.AttachmentResponse;
import com.ticketflow.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping("/api/tickets/{ticketId}/attachments")
    public List<AttachmentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        return attachments.list(ticketId, AuthUser.from(jwt));
    }

    @PostMapping(path = "/api/tickets/{ticketId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable Long ticketId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        return attachments.upload(ticketId, file, AuthUser.from(jwt));
    }

    /** Always downloaded (never rendered inline) and with nosniff, so a file cannot run as a web page. */
    @GetMapping("/api/attachments/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        AttachmentFile file = attachments.download(id, AuthUser.from(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.data());
    }
}
