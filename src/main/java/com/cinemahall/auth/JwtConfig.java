package com.cinemahall.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public SecretKey jwtSigningKey(@Value("${app.jwt.secret:}") String secret) {
        byte[] keyBytes;
        if (secret == null || secret.isBlank()) {
            // No key is ever committed. Local runs get a random one; sessions simply end on restart.
            keyBytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(keyBytes);
            log.warn("APP_JWT_SECRET is not set: using a random signing key, so logins end when the app restarts.");
        } else {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("APP_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long");
            }
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
