package com.ticketflow.comment;

import com.ticketflow.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CommentDtos {

    private CommentDtos() {
    }

    public record CommentRequest(
            @NotBlank(message = "Escreva o comentário.")
            @Size(max = 5000, message = "Comentário muito longo.") String text,
            Boolean internal) {

        /** Optional in the JSON: a missing value is a public comment. */
        public boolean isInternal() {
            return Boolean.TRUE.equals(internal);
        }
    }

    public record CommentResponse(Long id, String text, boolean internal, UserSummary author, Instant createdAt) {

        public static CommentResponse from(Comment comment) {
            return new CommentResponse(comment.getId(), comment.getText(), comment.isInternal(), UserSummary.from(comment.getAuthor()),
                    comment.getCreatedAt());
        }
    }
}
