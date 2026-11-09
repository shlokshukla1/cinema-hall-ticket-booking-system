package com.cinemahall.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Follows real time plus an offset that tests can push forward, so expiry is tested without sleeping. */
public class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration by) {
        offset.updateAndGet(current -> current.plus(by));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }
}
