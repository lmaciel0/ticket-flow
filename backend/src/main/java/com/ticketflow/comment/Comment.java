package com.ticketflow.comment;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    private String text;

    private boolean internal;

    private Instant createdAt;

    protected Comment() {
        // required by JPA
    }

    public Comment(Ticket ticket, User author, String text, Instant createdAt) {
        this(ticket, author, text, false, createdAt);
    }

    public Comment(Ticket ticket, User author, String text, boolean internal, Instant createdAt) {
        this.ticket = ticket;
        this.author = author;
        this.text = text.strip();
        this.internal = internal;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getAuthor() {
        return author;
    }

    public String getText() {
        return text;
    }

    public boolean isInternal() {
        return internal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
