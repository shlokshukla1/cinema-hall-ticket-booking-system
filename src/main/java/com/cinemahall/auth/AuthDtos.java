package com.cinemahall.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Enter your name") @Size(max = 120) String fullName,
            @NotBlank(message = "Enter your email") @Email(message = "Enter a valid email") @Size(max = 255) String email,
            @NotBlank(message = "Choose a password")
            @Size(min = 8, max = 72, message = "Use 8 to 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "Include at least one letter and one number")
            String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "Enter your email") String email,
            @NotBlank(message = "Enter your password") @Size(max = 72) String password) {
    }

    public record UserView(Long id, String email, String fullName, Role role) {
        static UserView from(User user) {
            return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserView user) {
    }
}
