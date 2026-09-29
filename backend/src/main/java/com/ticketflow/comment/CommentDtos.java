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
            @Size(max = 5000, message = "Comentário muito longo.") String text) {
    }

    public record CommentResponse(Long id, String text, UserSummary author, Instant createdAt) {

        public static CommentResponse from(Comment comment) {
            return new CommentResponse(comment.getId(), comment.getText(), UserSummary.from(comment.getAuthor()),
                    comment.getCreatedAt());
        }
    }
}
