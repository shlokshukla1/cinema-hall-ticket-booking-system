package com.cinemahall.auth;

import com.cinemahall.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.io.IOException;
import java.util.List;

/**
 * Server-side role enforcement. The frontend hides links for convenience only; every rule that matters is here
 * or, for per-record ownership, in the services.
 */
@Configuration
public class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                    + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                // Tokens travel in the Authorization header, never in cookies, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/movies/**", "/api/genres", "/api/theatres",
                                "/api/shows/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/staff/**").hasAnyRole("STAFF", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()) // static pages hold no data; every record comes from /api
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "You do not have permission to do this")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "You do not have permission to do this")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                            String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status.value(), code, message, request.getRequestURI()));
    }
}
