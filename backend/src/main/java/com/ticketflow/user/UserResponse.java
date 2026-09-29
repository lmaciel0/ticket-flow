package com.ticketflow.user;

public record UserResponse(Long id, String name, String email, Role role, boolean active, boolean demo) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(), user.isDemo());
    }
}
