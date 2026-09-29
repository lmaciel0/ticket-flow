package com.ticketflow.user;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean active;

    private boolean demo;

    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String name, String email, String passwordHash, Role role, Instant createdAt) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.createdAt = createdAt;
    }

    /** Only active agents and managers can be responsible for a ticket. */
    public boolean canBeAssigned() {
        return active && (role == Role.AGENT || role == Role.MANAGER);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDemo() {
        return demo;
    }

    public void markAsDemo() {
        this.demo = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
