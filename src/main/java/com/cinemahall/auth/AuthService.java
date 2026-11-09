package com.cinemahall.auth;

import com.cinemahall.audit.AuditService;
import com.cinemahall.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final Clock clock;
    private final Duration tokenTtl;
    /** Compared against for unknown emails, so response time does not reveal which accounts exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                       LoginThrottle throttle, AuditService audit, Clock clock,
                       @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.throttle = throttle;
        this.audit = audit;
        this.clock = clock;
        this.tokenTtl = Duration.ofMinutes(ttlMinutes);
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** Public registration can only ever create customers. The role is not taken from the request. */
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = users.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), Role.CUSTOMER, Instant.now(clock)));
        audit.record(user.toAuthUser(), "USER_REGISTERED", "USER", user.getId(), null);
        return issueToken(user);
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String email = normalize(request.email());
        if (throttle.isLocked(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many failed attempts. Please try again later.");
        }
        Optional<User> user = users.findByEmail(email);
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throttle.recordFailure(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password");
        }
        throttle.recordSuccess(email);
        return issueToken(user.get());
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserView me(AuthUser caller) {
        return users.findById(caller.id()).map(AuthDtos.UserView::from)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private AuthDtos.AuthResponse issueToken(User user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(tokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthDtos.AuthResponse(token, expiresAt, AuthDtos.UserView.from(user));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
