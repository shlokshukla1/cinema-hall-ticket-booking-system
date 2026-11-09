package com.cinemahall.auth;

import com.cinemahall.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the caller from the security context, which holds only signature-verified token claims. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in");
        }
        return new AuthUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"),
                Role.valueOf(jwt.getClaimAsString("role")));
    }
}
