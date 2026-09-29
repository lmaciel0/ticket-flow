package com.ticketflow.comment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.comment.CommentDtos.CommentRequest;
import com.ticketflow.comment.CommentDtos.CommentResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping
    public List<CommentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        return comments.list(ticketId, AuthUser.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse add(@PathVariable Long ticketId, @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return comments.add(ticketId, request, AuthUser.from(jwt));
    }
}
