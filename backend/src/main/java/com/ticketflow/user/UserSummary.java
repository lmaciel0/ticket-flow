package com.ticketflow.user;

/** Minimal user data embedded in other responses (ticket requester, comment author...). */
public record UserSummary(Long id, String name) {

    public static UserSummary from(User user) {
        return user == null ? null : new UserSummary(user.getId(), user.getName());
    }
}
