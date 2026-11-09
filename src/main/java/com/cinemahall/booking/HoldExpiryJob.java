package com.cinemahall.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.holds.sweep-enabled", havingValue = "true")
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldService holdService;

    public HoldExpiryJob(HoldService holdService) {
        this.holdService = holdService;
    }

    @Scheduled(fixedDelayString = "${app.holds.sweep-interval-ms}")
    public void sweep() {
        try {
            int expired = holdService.expireOverdue();
            if (expired > 0) {
                log.info("Expired {} overdue seat holds", expired);
            }
        } catch (RuntimeException ex) {
            // Safe to skip a cycle: expiry is enforced by timestamp, and the next sweep retries.
            log.warn("Hold sweep failed and will retry: {}", ex.getMessage());
        }
    }
}
