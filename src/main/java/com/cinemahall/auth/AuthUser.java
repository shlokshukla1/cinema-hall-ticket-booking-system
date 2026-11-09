package com.cinemahall.auth;

/** The authenticated caller, as established from a verified JWT. Never built from client input. */
public record AuthUser(long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
