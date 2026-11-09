package com.cinemahall.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows password guessing: after repeated failures an email is locked out for a while.
 * Held in memory, which is enough for one instance; several instances would need a shared store.
 */
@Component
public class LoginThrottle {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Attempts(int failures, Instant windowEnds) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;

    public LoginThrottle(@Value("${app.auth.max-failed-logins}") int maxFailures,
                         @Value("${app.auth.lockout-minutes}") long lockoutMinutes,
                         Clock clock) {
        this.maxFailures = maxFailures;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        this.clock = clock;
    }

    public boolean isLocked(String email) {
        Attempts current = attempts.get(email);
        if (current == null) {
            return false;
        }
        if (!clock.instant().isBefore(current.windowEnds())) {
            attempts.remove(email, current);
            return false;
        }
        return current.failures() >= maxFailures;
    }

    public void recordFailure(String email) {
        Instant now = clock.instant();
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.values().removeIf(entry -> !now.isBefore(entry.windowEnds()));
        }
        attempts.merge(email, new Attempts(1, now.plus(lockout)), (previous, first) ->
                now.isBefore(previous.windowEnds())
                        ? new Attempts(previous.failures() + 1, previous.windowEnds())
                        : first);
    }

    public void recordSuccess(String email) {
        attempts.remove(email);
    }
}
